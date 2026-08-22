package com.mengxia.home;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 把网页存一份在手机里。
 *
 * 起因很实在：梦匣本来每次打开都得先去 github 把 index.html 拉下来。
 * 挂着梯子的时候那一下常常被卡住，于是就白屏 —— 明明 App 装着，
 * 却因为网不通打不开。
 *
 * 中间试过两版都不对：
 * 一、「先拿本地那份开，后台再去看新的」—— 这一次看到的永远是上一次存的，
 *     慢一步；后台那趟没成，她就永远卡在旧版本。
 * 二、「先去网上拿，拿不到才用本地」—— 新是新了，可开 App 得等网。
 *     她开着梯子、关着梯子、WiFi、流量，四种情况里总有一种要她干等。
 *
 * 现在这一版：<b>手边有什么就先开什么，一秒不等</b>。
 * 手边的东西有两份：装 APK 时一起装进来的那一份（assets/page.html），
 * 和之前下载过存下来的那一份。所以哪怕她从来没联过网，App 也开得起来 ——
 * 梯子开不开、WiFi 还是流量，都进得去。
 *
 * 那怎么拿到新的？交给网页自己：它开起来一秒半之后会去拉一个几十字节的
 * build.txt 对版本号，不一样就把新的抓回来当场换上。壳这边也在后台
 * 顺手更新一次存货，下次开就更快。两条路都断了也无所谓 —— 她照样能用。
 *
 * 有一点很要紧：不能改成 file:// 去加载。localStorage 是按网址分家的，
 * 一换网址她所有的东西（聊天、日记、相册、账本）都会像凭空消失。
 * 所以这里走 shouldInterceptRequest —— 网址还是原来那个，
 * 只是内容我们自己拿。
 */
public class PageCache {

    private static final String FILE = "page_cache.html";
    // 打包时一起装进 APK 的那一份（.github/workflows 里 cp 进去的）
    private static final String ASSET = "page.html";
    // 太小的多半是报错页或者半截，别拿它覆盖好的
    private static final int MIN_OK = 200 * 1024;

    static File file(Context ctx) { return new File(ctx.getFilesDir(), FILE); }

    /**
     * 换了新 APK 就把下载存的那份丢掉。
     *
     * open() 永远先用下载过的那一份。可她手机上存着的可能比新 APK 里带的还旧 ——
     * 那样装了新版也白装，开出来还是旧页面，新加的东西一样都看不见。
     * 所以：APK 里带的版本号一变，就把存货清掉，让这一次用 APK 自带的那份。
     * 清掉的只是页面代码，她的数据一个字都不动。
     */
    static void dropIfUpgraded(Context ctx) {
        try {
            String now = "";
            java.io.InputStream in = ctx.getAssets().open("build.txt");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            now = bo.toString("UTF-8").trim();
            if (now.length() == 0) return;
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences("mengxia_apk", Context.MODE_PRIVATE);
            String was = p.getString("bundled", "");
            if (now.equals(was)) return;                 // 还是上一次那个 APK
            File f = file(ctx);
            if (f.exists()) f.delete();
            p.edit().putString("bundled", now).apply();
        } catch (Throwable ignored) {}
    }

    static boolean has(Context ctx) {
        File f = file(ctx);
        return f.exists() && f.length() > MIN_OK;
    }

    /**
     * 手边这一份，拿来喂给 WebView。
     * 先用下载过的；一次都没下载成功过，就用装 APK 时一起装进来的那一份。
     * 所以哪怕她从来没联过网，App 也开得起来。
     */
    static InputStream open(Context ctx) {
        try { if (has(ctx)) return new FileInputStream(file(ctx)); } catch (Exception ignored) {}
        try { return ctx.getAssets().open(ASSET); } catch (Exception ignored) {}
        return new ByteArrayInputStream(new byte[0]);
    }

    /** 手边到底有没有东西可开 —— 下载的、或者装进来的，有一个就算 */
    static boolean hasAny(Context ctx) {
        if (has(ctx)) return true;
        try { ctx.getAssets().open(ASSET).close(); return true; }
        catch (Exception e) { return false; }
    }

    /**
     * 后台去把存货换成新的。开页面不等它 —— 她那一趟早就用手边的开起来了。
     * 拉到了下次开更快；拉不到就算了，什么都不影响。
     */
    static void refreshLater(final Context ctx, final String site) {
        new Thread(new Runnable() {
            public void run() { fetchFresh(ctx, site); }
        }).start();
    }

    /**
     * 去网上拿一份新的存起来。在后台线程上跑。
     * 返回 null = 没拿到。
     */
    static byte[] fetchFresh(Context ctx, String site) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(site).openConnection();
            con.setConnectTimeout(10000);
            // 整页八兆。二十五秒读超时是我拍脑袋定的 —— 那要求一路
            // 三百多 KB/s 不断，挂着梯子走流量根本达不到，于是这一趟
            // 每次都在半路超时，存货就永远换不了新。她说"重开也没更新"
            // 最根上的原因在这儿。这是后台线程，没人等它，给足时间
            con.setReadTimeout(180000);
            con.setInstanceFollowRedirects(true);
            con.setUseCaches(false);
            con.setRequestProperty("Accept", "text/html");
            con.setRequestProperty("Cache-Control", "no-cache");
            con.setRequestProperty("Pragma", "no-cache");
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] all = bos.toByteArray();
            if (all.length < MIN_OK) return null;   // 半截或者报错页，不要
            try {
                File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
                FileOutputStream fo = new FileOutputStream(tmp);
                fo.write(all);
                fo.close();
                File dst = file(ctx);
                if (!dst.exists() || dst.delete()) { if (!tmp.renameTo(dst)) tmp.delete(); }
                else tmp.delete();
            } catch (Exception ignored) {}
            return all;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (con != null) try { con.disconnect(); } catch (Exception ig) {}
        }
    }

    /** 这个网址是不是"主页面"——只有主页面要拦，别的（图、字体、接口）一律放过 */
    static boolean isMain(String url, String site) {
        if (url == null || site == null) return false;
        String u = url.split("[?#]")[0];
        String s = site.split("[?#]")[0];
        if (u.equals(s)) return true;
        if (!s.endsWith("/")) s = s + "/";
        return u.equals(s) || u.equals(s + "index.html");
    }

    /** 把存的那一份丢掉。她自己想强行去拿最新的时候用 */
    static boolean drop(Context ctx) {
        try { return file(ctx).delete(); } catch (Exception e) { return false; }
    }
}
