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

    private String doPost(String text) throws Exception {
        if (!ready()) return err("四把钥匙还没填全");
        String body = new JSONObject().put("text", String.valueOf(text)).toString();
        String url = "https://api.x.com/2/tweets";
        String auth = oauthHeader("POST", url);

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setRequestProperty("Authorization", auth);
        OutputStream os = con.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();

        int code = con.getResponseCode();
        InputStream in = (code >= 400) ? con.getErrorStream() : con.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
        }
        con.disconnect();
        String raw = sb.toString();

        JSONObject out = new JSONObject();
        if (code >= 200 && code < 300) {
            out.put("ok", true);
            try { out.put("id", new JSONObject(raw).getJSONObject("data").optString("id", "")); } catch (Exception ig) {}
            return out.toString();
        }
        out.put("ok", false);
        out.put("code", code);
        // x 那边的错误话说得很绕，尽量挑一句人能看懂的出来
        String why = raw;
        try {
            JSONObject j = new JSONObject(raw);
            if (j.has("detail")) why = j.optString("detail");
            else if (j.has("title")) why = j.optString("title");
            else if (j.has("errors")) why = j.getJSONArray("errors").getJSONObject(0).optString("message", raw);
        } catch (Exception ig) {}
        if (code == 401) why = "钥匙不对，或者 Access Token 是在改成「读+写」之前生成的（那种只能读，得重新生成一遍）";
        if (code == 403) why = "这个号没有发推权限，或者内容被挡下来了。" + why;
        if (code == 429) why = "这个月的额度用完了，或者发太快了。" + why;
        out.put("err", why.length() > 300 ? why.substring(0, 300) : why);
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
