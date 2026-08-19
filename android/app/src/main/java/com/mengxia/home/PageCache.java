package com.mengxia.home;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

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
     *
     * 拉回来的确实跟刚才喂给 WebView 的那份不一样，就回一声 ——
     * 不回的话她永远慢一步：这次开看的是上次存的，下次开才轮到这次的。
     */
    static void refresh(final Context ctx, final String site, final Runnable onNew) {
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection con = null;
                try {
                    // 刚才喂出去的是哪一份，先记下来
                    boolean had = has(ctx);
                    String was = had ? sum(file(ctx)) : "";

                    con = (HttpURLConnection) new URL(site).openConnection();
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(60000);
                    con.setRequestProperty("Accept", "text/html");
                    // 别让中间哪一层塞一份旧的回来
                    con.setRequestProperty("Cache-Control", "no-cache");
                    con.setRequestProperty("Pragma", "no-cache");
                    con.setUseCaches(false);
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

                    String now = sum(tmp);
                    File dst = file(ctx);
                    // 先删后改名：有些机器上 renameTo 覆盖不了已存在的文件
                    if (dst.exists() && !dst.delete()) { tmp.delete(); return; }
                    if (!tmp.renameTo(dst)) { tmp.delete(); return; }

                    // 本来就没存货的那一次，页面就是从网上下来的，不用再翻一遍
                    if (had && !now.equals(was) && onNew != null) onNew.run();
                } catch (Exception ignored) {
                    // 网不通就网不通，本地那份还在
                } finally {
                    if (con != null) try { con.disconnect(); } catch (Exception ig) {}
                }
            }
        }).start();
    }

    /** 这一份的指纹。只用来比"是不是同一份"，不防谁 */
    private static String sum(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            byte[] d = md.digest();
            for (int i = 0; i < d.length; i++) sb.append(Integer.toHexString((d[i] & 0xFF) | 0x100).substring(1));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(f.length()); }
    }

    /** 把存的那一份丢掉。她自己想强行去拿最新的时候用 */
    static boolean drop(Context ctx) {
        try { return file(ctx).delete(); } catch (Exception e) { return false; }
    }
}
