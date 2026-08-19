package com.mengxia.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 替他发推。
 *
 * 为什么要放在壳里而不是网页里：
 * 一、x.com 不给浏览器发跨域请求（没有 CORS 头），网页里 fetch 一定失败；
 * 二、OAuth 1.0a 要拿密钥算签名，那串东西不该在网页那一层来回传。
 *
 * 四把钥匙只存在这台手机的 SharedPreferences 里，不上传，也不进备份文件。
 * 发之前当然还是要能连上 x.com —— 国内得挂梯子，这个我没办法。
 */
public class XBridge {

    private static final String PREF = "mengxia_x";
    private final Context ctx;
    private final HashMap<String, String> results = new HashMap<>();

    public XBridge(Context c) { this.ctx = c.getApplicationContext(); }

    private SharedPreferences sp() { return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    /** 四把钥匙齐了没 */
    @JavascriptInterface
    public boolean ready() {
        SharedPreferences p = sp();
        return p.getString("ck", "").length() > 0 && p.getString("cs", "").length() > 0
                && p.getString("at", "").length() > 0 && p.getString("as", "").length() > 0;
    }

    /** 存钥匙。{ck, cs, at, as} */
    @JavascriptInterface
    public void keys(String json) {
        try {
            JSONObject j = new JSONObject(json);
            sp().edit()
                    .putString("ck", j.optString("ck", "").trim())
                    .putString("cs", j.optString("cs", "").trim())
                    .putString("at", j.optString("at", "").trim())
                    .putString("as", j.optString("as", "").trim())
                    .apply();
        } catch (Exception ignored) {}
    }

    /** 把钥匙全忘掉 */
    @JavascriptInterface
    public void forget() { sp().edit().clear().apply(); }

    /**
     * 发一条。网络不能卡在 WebView 那条线上，所以这里只领个号就回，
     * 网页拿着号去问 result(号)：还没好回空串，好了回一段 JSON。
     */
    @JavascriptInterface
    public String post(final String text) {
        final String id = String.valueOf(System.currentTimeMillis()) + "-" + new Random().nextInt(9999);
        synchronized (results) { results.put(id, ""); }
        new Thread(new Runnable() {
            public void run() {
                String out;
                try { out = doPost(text); }
                catch (Exception e) { out = err(String.valueOf(e.getMessage())); }
                synchronized (results) { results.put(id, out); }
            }
        }).start();
        return id;
    }

    /** 问结果。空串 = 还在发 */
    @JavascriptInterface
    public String result(String id) {
        synchronized (results) {
            String r = results.get(id);
            if (r != null && r.length() > 0) results.remove(id);
            return r == null ? err("这个号不存在") : r;
        }
    }

    private static String err(String why) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", why == null ? "说不上来" : why);
            return o.toString();
        } catch (Exception e) { return "{\"ok\":false,\"err\":\"?\"}"; }
    }

    // ---------- 真正干活的 ----------

    /** 一次请求的结果 */
    private static class Res {
        int code; String raw = ""; long skew = 0;
    }

    /**
     * 真正发一次。host 是 api.x.com 或者 api.twitter.com —— 有些号在新域名下会 401，
     * 老域名反而通，所以这里两个都要能试。
     */
    private Res call(String host, String method, String path, String body) throws Exception {
        String url = "https://" + host + path;
        String auth = oauthHeader(method, url);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        con.setRequestProperty("Authorization", auth);
        if (body != null) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = con.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        Res r = new Res();
        r.code = con.getResponseCode();
        // 顺手记一下手机的表跟人家差多少 —— OAuth 1.0a 对时间很敏感，差太多就一直 401
        try {
            long srv = con.getHeaderFieldDate("Date", 0);
            if (srv > 0) r.skew = (System.currentTimeMillis() - srv) / 1000L;
        } catch (Exception ig) {}
        InputStream in = (r.code >= 400) ? con.getErrorStream() : con.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        con.disconnect();
        r.raw = sb.toString();
        return r;
    }

    /** 把 x 那边绕来绕去的错误挑一句人能看懂的 */
    private static String why(Res r) {
        String w = r.raw;
        try {
            JSONObject j = new JSONObject(r.raw);
            if (j.has("detail")) w = j.optString("detail");
            else if (j.has("title")) w = j.optString("title");
            else if (j.has("errors")) w = j.getJSONArray("errors").getJSONObject(0).optString("message", r.raw);
        } catch (Exception ig) {}
        return w == null ? "" : w;
    }

    private String doPost(String text) throws Exception {
        if (!ready()) return err("四把钥匙还没填全");
        String body = new JSONObject().put("text", String.valueOf(text)).toString();
        Res r = call("api.x.com", "POST", "/2/tweets", body);
        if (r.code == 401) {
            Res r2 = call("api.twitter.com", "POST", "/2/tweets", body);
            if (r2.code != 401) r = r2;
        }

        JSONObject out = new JSONObject();
        if (r.code >= 200 && r.code < 300) {
            out.put("ok", true);
            try { out.put("id", new JSONObject(r.raw).getJSONObject("data").optString("id", "")); } catch (Exception ig) {}
            return out.toString();
        }
        out.put("ok", false);
        out.put("code", r.code);
        String w = why(r);
        if (r.code == 401) {
            w = "401 —— 这四把钥匙 x 那边不认。原话：" + w
                + "\n先点上面那个「自检一下」，它能分清是钥匙本身不对，还是只是没有发帖权限。";
            if (Math.abs(r.skew) > 120) w += "\n另外你手机的时间比 x 那边差了 " + r.skew + " 秒，差太多也会一直 401，去把系统时间调成自动。";
        }
        if (r.code == 403) w = "403 —— 这个号不让发，或者内容被挡下来了。原话：" + w
                + "\n最常见的是 App 权限还停在 Read only。去 developer.x.com 那个 App 的"
                + " User authentication settings，改成 Read and write，改完必须重新 Regenerate"
                + " 一次 Access Token（旧的会作废）。";
        if (r.code == 429) w = "429 —— 发太快了，或者这个月的额度用完了。原话：" + w
                + "\n去 developer.x.com 首页看 Usage 那一栏，上面写着还剩多少。用完的话只能等下个月一号重置。";
        if (r.code == 402) w = "402 —— 钥匙是好的，是 x 那边的额度／计费用完了。原话：" + w
                + "\n\n这一条跟代码没关系，梦匣这边改不了。去 developer.x.com 首页看 Usage："
                + "\n· 免费档每个月能发的条数很少，用完就得等下个月一号重置"
                + "\n· 也可能是这个 App 没挂在正确的 Project 下面，或者那个 Project 的档位没配好"
                + "\n· 「自检一下」也算一次调用，别反复点"
                + "\n\n等不及的话，用下面那个「复制这条」，去推特 App 里自己贴一下发。";
        if (r.code == 400) w = "400 —— x 说这条请求它看不懂。原话：" + w
                + "\n多半是正文有它不收的东西（太长、或者带了什么它不认的字符）。";
        out.put("err", w.length() > 400 ? w.substring(0, 400) : w);
        return out.toString();
    }

    /** 自检：拿这四把钥匙去问「我是谁」。这个只要读权限，能把问题分成两半 */
    @JavascriptInterface
    public String checkNow() {
        final String id = "chk-" + System.currentTimeMillis();
        synchronized (results) { results.put(id, ""); }
        new Thread(new Runnable() {
            public void run() {
                String out;
                try { out = doCheck(); }
                catch (Exception e) { out = err(String.valueOf(e.getMessage())); }
                synchronized (results) { results.put(id, out); }
            }
        }).start();
        return id;
    }

    private String doCheck() throws Exception {
        if (!ready()) return err("四把钥匙还没填全");
        Res r = call("api.x.com", "GET", "/2/users/me", null);
        String host = "api.x.com";
        if (r.code == 401) {
            Res r2 = call("api.twitter.com", "GET", "/2/users/me", null);
            if (r2.code != 401) { r = r2; host = "api.twitter.com"; }
        }
        JSONObject out = new JSONObject();
        out.put("code", r.code);
        out.put("host", host);
        out.put("skew", r.skew);
        if (r.code >= 200 && r.code < 300) {
            out.put("ok", true);
            try {
                JSONObject d = new JSONObject(r.raw).getJSONObject("data");
                out.put("who", d.optString("username", ""));
                out.put("name", d.optString("name", ""));
            } catch (Exception ig) {}
            return out.toString();
        }
        out.put("ok", false);
        out.put("err", why(r));
        return out.toString();
    }

    /** OAuth 1.0a 的签名头。发的是 JSON body，所以签名里只算 oauth_* 那几个参数 */
    private String oauthHeader(String method, String url) throws Exception {
        SharedPreferences p = sp();
        String ck = p.getString("ck", ""), cs = p.getString("cs", "");
        String at = p.getString("at", ""), as = p.getString("as", "");

        String nonce = Long.toHexString(System.nanoTime()) + Long.toHexString(new Random().nextLong());
        nonce = nonce.replaceAll("[^A-Za-z0-9]", "");
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);

        // 参数要按名字排好序，这里手排，就这五个
        String params = "oauth_consumer_key=" + enc(ck)
                + "&oauth_nonce=" + enc(nonce)
                + "&oauth_signature_method=" + enc("HMAC-SHA1")
                + "&oauth_timestamp=" + enc(ts)
                + "&oauth_token=" + enc(at)
                + "&oauth_version=" + enc("1.0");

        String base = method.toUpperCase() + "&" + enc(url) + "&" + enc(params);
        String signKey = enc(cs) + "&" + enc(as);

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(signKey.getBytes("UTF-8"), "HmacSHA1"));
        String sig = Base64.encodeToString(mac.doFinal(base.getBytes("UTF-8")), Base64.NO_WRAP);

        return "OAuth oauth_consumer_key=\"" + enc(ck) + "\", "
                + "oauth_nonce=\"" + enc(nonce) + "\", "
                + "oauth_signature=\"" + enc(sig) + "\", "
                + "oauth_signature_method=\"HMAC-SHA1\", "
                + "oauth_timestamp=\"" + enc(ts) + "\", "
                + "oauth_token=\"" + enc(at) + "\", "
                + "oauth_version=\"1.0\"";
    }

    /** OAuth 那套百分号编码，比 URLEncoder 严 */
    private static String enc(String s) throws Exception {
        String t = URLEncoder.encode(s == null ? "" : s, "UTF-8");
        return t.replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
}
