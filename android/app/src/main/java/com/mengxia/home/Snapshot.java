package com.mengxia.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 先把现场封存起来，再让 App 跑。
 *
 * 挖旧聊天记录靠的是 LevelDB 那些还没被合并掉的旧文件。可 App 一跑起来就会写东西 ——
 * 光是把新页面存一份就是八兆，这种写入最容易触发合并，一合并旧记录就真没了。
 *
 * 所以第一次装上新壳的时候，在 WebView 起来之前，先把存东西那几个目录原样拷一份到
 * rescue_snapshot 里。之后 App 怎么写都不影响这份拷贝，挖的时候从拷贝里挖，
 * 想挖几次挖几次。
 *
 * 只做一次（做完记个数），只拷自己的目录，不联网。
 */
public class Snapshot {

    private static final String PREF = "mengxia_snap";
    private static final String KEY = "done_v1";
    public static final String DIR = "rescue_snapshot";

    private static final long MAX = 220L * 1024 * 1024;   // 最多封这么多，别把她手机撑爆
    private static final long BUDGET_MS = 9000;           // 最多花这么久，不能让开机卡死

    public static File dir(Context c) {
        return new File(c.getApplicationInfo().dataDir, DIR);
    }

    /** 已经封过了没有 */
    public static boolean done(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return p.getBoolean(KEY, false);
        } catch (Throwable t) { return true; }   // 拿不准就别再拷，宁可不做也别添乱
    }

    /**
     * 封存一次。必须在 WebView 起来之前调用。
     * 返回拷了多少字节；已经封过了返回 -1。
     */
    public static long once(Context c) {
        if (done(c)) return -1;
        long copied = 0;
        try {
            File data = new File(c.getApplicationInfo().dataDir);
            File out = dir(c);
            if (!out.exists() && !out.mkdirs()) return 0;
            long t0 = System.currentTimeMillis();
            copied = walk(data, data, out, 0, 0, t0);
        } catch (Throwable t) {
            /* 封不成也不能拦着她开 App */
        }
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
             .edit().putBoolean(KEY, true).apply();
        } catch (Throwable t) {}
        return copied;
    }

    private static long walk(File root, File cur, File outRoot, int depth, long soFar, long t0) {
        if (cur == null || depth > 8 || soFar > MAX) return soFar;
        if (System.currentTimeMillis() - t0 > BUDGET_MS) return soFar;
        String name = cur.getName();
        // 别把封存目录自己再封一遍，也别去啃代码和缓存
        if (name.equals(DIR) || name.equals("cache") || name.equals("code_cache")
                || name.equals("lib") || name.equals("no_backup")) return soFar;
        File[] kids = cur.listFiles();
        if (kids == null) return soFar;
        for (File k : kids) {
            if (soFar > MAX) break;
            if (System.currentTimeMillis() - t0 > BUDGET_MS) break;
            if (k.isDirectory()) { soFar = walk(root, k, outRoot, depth + 1, soFar, t0); continue; }
            if (!worth(k)) continue;
            long n = copy(root, k, outRoot);
            if (n > 0) soFar += n;
        }
        return soFar;
    }

    /** 只封存东西的那些文件 */
    private static boolean worth(File f) {
        if (f.length() <= 0) return false;
        String p = f.getAbsolutePath().toLowerCase();
        if (!(p.contains("leveldb") || p.contains("indexeddb")
                || p.contains("local storage") || p.contains("session storage")
                || p.contains("databases"))) return false;
        // .so、图片缓存之类的不要
        return !(p.endsWith(".so") || p.endsWith(".png") || p.endsWith(".jpg"));
    }

    private static long copy(File root, File src, File outRoot) {
        InputStream in = null;
        OutputStream os = null;
        try {
            String rel = src.getAbsolutePath().substring(root.getAbsolutePath().length());
            File dst = new File(outRoot, rel.replace(File.separatorChar, '_')
                                            .replace(' ', '_'));
            if (dst.exists() && dst.length() == src.length()) return 0;
            in = new FileInputStream(src);
            os = new FileOutputStream(dst);
            byte[] b = new byte[1 << 16];
            long n = 0, r;
            while ((r = in.read(b)) > 0) { os.write(b, 0, (int) r); n += r; }
            return n;
        } catch (Throwable t) {
            return 0;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
            try { if (os != null) os.close(); } catch (Throwable t) {}
        }
    }
}
