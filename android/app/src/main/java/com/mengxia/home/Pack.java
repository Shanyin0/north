package com.mengxia.home;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把家底原样打包到手机「下载」。
 *
 * 她朋友那台机建议用 adb 把 /data/data/包名/app_webview/ 整个 tar 出来，
 * 再用 LevelDB 工具翻墓碑后面的旧记录。方向是对的，可那条命令在她手机上
 * 跑不了 —— App 私有目录，没 root 的 adb shell 读不了，run-as 又要 APK
 * 开了调试（这个是 release 版，没开）。
 *
 * 但那件事我们自己做得到：壳子本来就在那个目录里面，有权限。
 * 这里就是把那些原始文件原样塞进一个 zip 丢到「下载」——
 * 不解析、不过滤、一个字节都不动。拿到电脑上用什么工具翻都行。
 *
 * 只读自己的目录，不联网。
 */
public class Pack {

    private final Context ctx;

    public Pack(Context c) { this.ctx = c; }

    /**
     * which: "local" 只打包 localStorage（小，最值钱 —— 搬家前整段聊天就存这儿）
     *        "idb"   只打包 IndexedDB
     *        "all"   两样都要，外加封存下来的旧现场
     */
    @JavascriptInterface
    public String packRaw(String which) {
        String w = (which == null) ? "all" : which;
        OutputStream os = null;
        ZipOutputStream zip = null;
        try {
            File root = new File(ctx.getApplicationInfo().dataDir);
            List<File> fs = new ArrayList<File>();
            collect(root, fs, w, 0);
            if (fs.isEmpty()) return "{\"ok\":false,\"why\":\"没找到那些文件\"}";

            String name = "梦匣-家底-" + w + "-" + stamp() + ".zip";
            os = openDownload(name);
            if (os == null) return "{\"ok\":false,\"why\":\"写不进下载文件夹\"}";
            zip = new ZipOutputStream(os);
            zip.setLevel(6);

            long raw = 0;
            int n = 0;
            byte[] buf = new byte[1 << 16];
            for (File f : fs) {
                try {
                    String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length());
                    if (rel.startsWith("/")) rel = rel.substring(1);
                    zip.putNextEntry(new ZipEntry(rel));
                    FileInputStream in = new FileInputStream(f);
                    int r;
                    while ((r = in.read(buf)) > 0) { zip.write(buf, 0, r); raw += r; }
                    in.close();
                    zip.closeEntry();
                    n++;
                } catch (Throwable t) { /* 单个文件读不了就跳过，别整包失败 */ }
            }
            zip.finish();
            zip.close(); zip = null; os = null;
            return "{\"ok\":true,\"name\":\"" + esc(name) + "\",\"files\":" + n
                 + ",\"bytes\":" + raw + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"why\":\"" + esc(String.valueOf(t.getMessage())) + "\"}";
        } finally {
            try { if (zip != null) zip.close(); } catch (Throwable t) {}
            try { if (os != null) os.close(); } catch (Throwable t) {}
        }
    }

    /** 先看看有多大，免得她点下去等半天又塞爆手机 */
    @JavascriptInterface
    public String sizeOf(String which) {
        try {
            File root = new File(ctx.getApplicationInfo().dataDir);
            List<File> fs = new ArrayList<File>();
            collect(root, fs, which == null ? "all" : which, 0);
            long n = 0;
            for (File f : fs) n += f.length();
            return "{\"files\":" + fs.size() + ",\"bytes\":" + n + "}";
        } catch (Throwable t) { return "{\"files\":0,\"bytes\":0}"; }
    }

    // ===== 底下是干活的 =====

    private void collect(File dir, List<File> out, String which, int depth) {
        if (dir == null || depth > 8 || out.size() > 6000) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) { collect(k, out, which, depth + 1); continue; }
            if (k.length() <= 0) continue;
            String p = k.getAbsolutePath().toLowerCase();
            boolean local = p.contains("local storage") || p.contains("session storage");
            boolean idb = p.contains("indexeddb");
            boolean snap = p.contains(Snapshot.DIR.toLowerCase());
            boolean take;
            if ("local".equals(which)) take = local;
            else if ("idb".equals(which)) take = idb;
            else take = local || idb || snap;
            if (take) out.add(k);
        }
    }

    private OutputStream openDownload(String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                OutputStream o = ctx.getContentResolver().openOutputStream(uri);
                pending = uri;
                return o;
            }
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            return new FileOutputStream(new File(dir, name));
        } catch (Throwable t) { return null; }
    }

    private Uri pending;

    /** Q 以上写完要把 IS_PENDING 抹掉，不然文件管理器里看不见 */
    @JavascriptInterface
    public void finish() {
        try {
            if (pending != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                ctx.getContentResolver().update(pending, cv, null, null);
            }
        } catch (Throwable t) {}
        pending = null;
    }

    private static String stamp() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d%02d%02d-%02d%02d",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(' ');
            else b.append(ch);
        }
        return b.toString();
    }
}
