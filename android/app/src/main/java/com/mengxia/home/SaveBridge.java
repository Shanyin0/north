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

    private void toast(final String msg) {
        act.runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(act, msg, Toast.LENGTH_LONG).show(); }
        });
    }
}
