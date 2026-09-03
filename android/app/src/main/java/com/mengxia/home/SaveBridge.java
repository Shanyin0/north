package com.mengxia.home;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** 让网页能把备份文件真的存到手机「下载」里（WebView 自己下不了 blob）。 */
public class SaveBridge {

    private final Activity act;
    private OutputStream out;
    private String savedTo = "";

    public SaveBridge(Activity a) { this.act = a; }

    @JavascriptInterface
    public void begin(String fileName) {
        close();
        savedTo = "";
        String name = (fileName == null || fileName.length() == 0) ? "mengxia-backup.json" : fileName;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = act.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    out = act.getContentResolver().openOutputStream(uri);
                    pendingUri = uri;
                    savedTo = "手机的「下载」里";
                }
            } else {
                File dir = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, name);
                    out = new FileOutputStream(f);
                    savedTo = f.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            out = null;
            toast("存不下来：" + e.getMessage());
        }
    }

    private Uri pendingUri;

    @JavascriptInterface
    public void chunk(String b64) {
        if (out == null || b64 == null) return;
        try {
            out.write(Base64.decode(b64, Base64.DEFAULT));
        } catch (Exception e) {
            toast("写文件出错：" + e.getMessage());
            close();
        }
    }

    @JavascriptInterface
    public void end() {
        boolean ok = out != null;
        close();
        if (ok) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pendingUri != null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    act.getContentResolver().update(pendingUri, cv, null, null);
                }
            } catch (Exception ignored) {}
            toast("备份已存到 " + savedTo);
        }
        pendingUri = null;
    }

    private void close() {
        if (out != null) {
            try { out.flush(); out.close(); } catch (Exception ignored) {}
            out = null;
        }
    }

    // ---------- 把一张图存进系统相册 ----------
    //
    // WebView 自己下不了 data: 开头的图（没有 DownloadListener 也接不住），
    // 所以网页把整串 data:image/...;base64,xxx 递进来，这边解开写进 MediaStore。
    // 写的是「图片 / 梦匣」那一相册，系统相册里直接看得到。
    @JavascriptInterface
    public void saveImage(String dataUrl, String fileName) {
        if (dataUrl == null || dataUrl.length() == 0) { toast("这张图是空的"); return; }
        String name = (fileName == null || fileName.length() == 0) ? "mengxia.png" : fileName;
        byte[] bytes;
        try {
            int i = dataUrl.indexOf(',');
            String b64 = (i >= 0) ? dataUrl.substring(i + 1) : dataUrl;
            bytes = Base64.decode(b64, Base64.DEFAULT);
        } catch (Exception e) { toast("这张图读不了：" + e.getMessage()); return; }
        if (bytes == null || bytes.length == 0) { toast("这张图读不了"); return; }
        String mime = dataUrl.startsWith("data:image/jpeg") ? "image/jpeg" : "image/png";
        OutputStream os = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, mime);
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + File.separator + "梦匣");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = act.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) { toast("相册写不进去"); return; }
                os = act.getContentResolver().openOutputStream(uri);
                if (os == null) { toast("相册写不进去"); return; }
                os.write(bytes);
                os.flush();
                os.close();
                os = null;
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                act.getContentResolver().update(uri, done, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "梦匣");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                os = new FileOutputStream(f);
                os.write(bytes);
                os.flush();
                os.close();
                os = null;
                // 老系统得知会一声，不然相册里不刷新
                try {
                    android.content.Intent scan = new android.content.Intent(
                            android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    scan.setData(Uri.fromFile(f));
                    act.sendBroadcast(scan);
                } catch (Exception ignored) {}
            }
            toast("存进相册了（图片 / 梦匣）");
        } catch (Exception e) {
            toast("存不下来：" + e.getMessage());
        } finally {
            if (os != null) { try { os.close(); } catch (Exception ignored) {} }
        }
    }

    // ---------- 主动推送 ----------
    @JavascriptInterface
    public void syncPush(String json) {
        try { Pusher.sync(act.getApplicationContext(), json); } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public String takePending() {
        try { return Pusher.takePending(act.getApplicationContext()); } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public void notify(String title, String text) {
        try { Pusher.notify(act.getApplicationContext(), title, text); } catch (Exception ignored) {}
    }

    // ---------- 梦匣从哪里打开 ----------
    @JavascriptInterface
    public String site() {
        try { return MainActivity.siteUrlOf(act); } catch (Exception e) { return ""; }
    }

    @JavascriptInterface
    public String siteDefault() {
        try { return MainActivity.defaultSite(); } catch (Exception e) { return ""; }
    }

    @JavascriptInterface
    public void setSite(String u) {
        try {
            if (act instanceof MainActivity) ((MainActivity) act).switchSite(u);
        } catch (Exception e) { toast("换不过去：" + e.getMessage()); }
    }

    // ---------- 安装包自己是哪一版 ----------
    //
    // 页面那串 __BUILD__ 是网页出炉的时间，它自己会更新，装不装 APK 都会变。
    // 这一串是安装包的版本，只有重新装一次才会变 —— 两个是两回事。
    // 有了它才看得出「壳该不该重装了」。
    //
    // 拿不到就返回空／0，网页那边当成「没装壳，在浏览器里开的」

    /** 安装包版本，形如 1.42 */
    @JavascriptInterface
    public String appVer() {
        try {
            String v = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Throwable t) { return ""; }
    }

    /** 第几次打的包。同一个版本号重打过的话，靠它分先后 */
    @JavascriptInterface
    public int appBuild() {
        try {
            return act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionCode;
        } catch (Throwable t) { return 0; }
    }

    private void toast(final String msg) {
        act.runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(act, msg, Toast.LENGTH_LONG).show(); }
        });
    }
}
