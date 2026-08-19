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
 * 现在改成：先拿本地这一份开，开完之后在后台悄悄去看有没有新的，
 * 有就换掉，下次打开就是新的。断网、梯子抽风，都照样进得去。
 *
 * 有一点很要紧：不能改成 file:// 去加载。localStorage 是按网址分家的，
 * 一换网址她所有的东西（聊天、日记、相册、账本）都会像凭空消失。
 * 所以这里走 shouldInterceptRequest —— 网址还是原来那个，
 * 只是内容从本地拿。
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

    /** 这个网址是不是"主页面"——只有主页面要拦，别的（图、字体、接口）一律放过 */
    static boolean isMain(String url, String site) {
        if (url == null || site == null) return false;
        String u = url.split("[?#]")[0];
        String s = site.split("[?#]")[0];
        if (u.equals(s)) return true;
        if (!s.endsWith("/")) s = s + "/";
        return u.equals(s) || u.equals(s + "index.html");
    }

    /**
     * 后台去看有没有新的。整个过程一声不吭：
     * 拉不到就算了，本地那份还在，她照样能用。
     */
    static void refresh(final Context ctx, final String site) {
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) new URL(site).openConnection();
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(60000);
                    con.setRequestProperty("Accept", "text/html");
                    if (con.getResponseCode() != 200) return;
                    File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
                    InputStream in = con.getInputStream();
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[16384];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                    out.close();
                    in.close();
                    if (total < MIN_OK) { tmp.delete(); return; }
                    File dst = file(ctx);
                    // 先删后改名：有些机器上 renameTo 覆盖不了已存在的文件
                    if (dst.exists() && !dst.delete()) { tmp.delete(); return; }
                    if (!tmp.renameTo(dst)) tmp.delete();
                } catch (Exception ignored) {
                    // 网不通就网不通，本地那份还在
                } finally {
                    if (con != null) try { con.disconnect(); } catch (Exception ig) {}
                }
            }
        }).start();
    }
}
