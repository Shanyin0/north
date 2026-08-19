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
 * 头一版是「先拿本地那份开，开完后台再去看有没有新的」。那样有个坑：
 * 这一次看到的永远是上一次存的，慢一步；后台那一趟要是也没成
 * （挂着梯子最容易），她就一直卡在某个旧版本上，我改了什么她都看不见。
 * 她为这个问过我好几次「你到底传了没有」—— 传了，是这儿把她挡住了。
 *
 * 现在反过来：<b>先去网上拿</b>，拿到就用最新的、顺手存一份；
 * 六秒还没个动静、或者根本连不上，才把本地那份端出来。
 * 网好的时候她永远看的是最新的，网坏的时候照样进得去，不白屏。
 *
 * 有一点很要紧：不能改成 file:// 去加载。localStorage 是按网址分家的，
 * 一换网址她所有的东西（聊天、日记、相册、账本）都会像凭空消失。
 * 所以这里走 shouldInterceptRequest —— 网址还是原来那个，
 * 只是内容我们自己拿。
 */
public class PageCache {

    private static final String FILE = "page_cache.html";
    // 太小的多半是报错页或者半截，别拿它覆盖好的
    private static final int MIN_OK = 200 * 1024;

    static File file(Context ctx) { return new File(ctx.getFilesDir(), FILE); }

    static boolean has(Context ctx) {
        File f = file(ctx);
        return f.exists() && f.length() > MIN_OK;
    }

    /** 本地那一份，拿来喂给 WebView */
    static InputStream open(Context ctx) {
        try { return new FileInputStream(file(ctx)); }
        catch (Exception e) { return new ByteArrayInputStream(new byte[0]); }
    }

    /**
     * 主页面这一趟：先去网上拿，拿到就用新的；拿不到再用本地存的那份。
     *
     * 这个方法是在后台线程上跑的（shouldInterceptRequest 本来就不在主线程），
     * 所以在这儿联网没问题。超时给得短，网卡住也不至于让她盯着白屏干等。
     *
     * 返回 null = 我们也没辙了，交给 WebView 自己去试。
     */
    static byte[] fetchFresh(Context ctx, String site) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(site).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(25000);
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
