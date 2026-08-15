package com.mengxia.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

/** 梦匣 —— 一个只做一件事的壳：把网页版装进 App 里。 */
public class MainActivity extends Activity {

    private static final int FILE_PICK = 4001;
    private static final String PREF = "mengxia";
    private static final String KEY_URL = "site_url";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean loadFailed = false;
    private PermissionRequest pendingMic;

    private String siteUrl() {
        SharedPreferences sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getString(KEY_URL, BuildConfig.SITE_URL);
    }

    private void saveSiteUrl(String u) {
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_URL, u).apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#F6F0E4"));
            getWindow().setNavigationBarColor(Color.parseColor("#FCF7EC"));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#F6F0E4"));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " MengxiaApp");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false; // 一切都留在 App 里
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    loadFailed = true;
                    askForUrl(true);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(intent, "选一张"), FILE_PICK);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        boolean wantMic = false;
                        String[] want = request.getResources();
                        for (String r : want) {
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantMic = true;
                        }
                        if (wantMic && !hasMic()) {
                            pendingMic = request;
                            askMic();
                            return;   // 等系统那关过了再放行
                        }
                        request.grant(want);
                    }
                });
            }
        });

        // 下载（导出备份用）
        web.setDownloadListener((url, ua, disposition, mime, size) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
        });

        web.addJavascriptInterface(new SaveBridge(this), "MengxiaNative");

        // 主动推送：挂上定时闹钟，并要一次通知权限
        Pusher.schedule(getApplicationContext());
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001);
                }
            } catch (Exception ignored) {}
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(siteUrl());
    }

    private boolean hasMic() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return checkSelfPermission("android.permission.RECORD_AUDIO")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) { return false; }
    }

    private void askMic() {
        if (Build.VERSION.SDK_INT < 23) return;
        try { requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 9002); } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != 9002) return;
        boolean ok = results != null && results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        final PermissionRequest req = pendingMic;
        pendingMic = null;
        if (req == null) return;
        if (ok) req.grant(req.getResources());
        else {
            req.deny();
            android.widget.Toast.makeText(this, "没有麦克风权限，录不了音", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 网址打不开时，让她自己改一个 */
    private void askForUrl(boolean failed) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(siteUrl());
        new AlertDialog.Builder(this)
                .setTitle(failed ? "打不开这个网址" : "梦匣的网址")
                .setMessage(failed ? "检查一下网络；要是网址不对，可以在这里改。" : "")
                .setView(input)
                .setPositiveButton("保存并打开", (d, w) -> {
                    String u = input.getText().toString().trim();
                    if (u.length() > 0) {
                        if (!u.startsWith("http")) u = "https://" + u;
                        saveSiteUrl(u);
                        loadFailed = false;
                        web.loadUrl(u);
                    }
                })
                .setNegativeButton("重试", (d, w) -> {
                    loadFailed = false;
                    web.loadUrl(siteUrl());
                })
                .setCancelable(true)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_PICK) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
