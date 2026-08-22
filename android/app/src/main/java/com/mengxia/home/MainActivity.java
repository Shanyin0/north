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
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

/** 梦匣 —— 一个只做一件事的壳：把网页版装进 App 里。 */
public class MainActivity extends Activity {

    private static final int FILE_PICK = 4001;
    static final String PREF = "mengxia";
    static final String KEY_URL = "site_url";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean pickerOpen = false;   // 选图的窗口是不是还开着
    private boolean loadFailed = false;
    private PermissionRequest pendingMic;

    private String siteUrl() {
        SharedPreferences sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getString(KEY_URL, BuildConfig.SITE_URL);
    }

    /** 网页那边（序章里那一格）要读的当前地址 */
    static String siteUrlOf(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_URL, BuildConfig.SITE_URL);
    }

    static String defaultSite() { return BuildConfig.SITE_URL; }

    /** 没写 http/https 的时候补一个：像 IP 的补 http，像域名的补 https */
    static String fixScheme(String u) {
        String x = u == null ? "" : u.trim();
        if (x.length() == 0) return BuildConfig.SITE_URL;
        if (x.startsWith("http://") || x.startsWith("https://")) return x;
        return (x.charAt(0) >= '0' && x.charAt(0) <= '9') ? ("http://" + x) : ("https://" + x);
    }

    /** 序章里点「换过去」走这儿 */
    void switchSite(final String u) {
        runOnUiThread(new Runnable() {
            public void run() {
                String x = fixScheme(u);
                saveSiteUrl(x);
                loadFailed = false;
                try { web.clearCache(true); } catch (Exception ignored) {}
                web.loadUrl(x);
            }
        });
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

        // 在 WebView 起来之前先把现场封存一份。
        // App 一跑起来就会写东西（光存一份新页面就是八兆），那种写入最容易触发
        // LevelDB 合并，一合并旧聊天记录的字节就真没了。只做一次
        try { Snapshot.once(this); } catch (Throwable ignored) {}
        // 换了新 APK 就把下载存的旧页面丢掉，不然装了新版还是开出旧页面
        try { PageCache.dropIfUpgraded(this); } catch (Throwable ignored) {}

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

            // 主页面：手边有什么就先开什么，一秒都不等网。
            // 手边的东西 = 装 APK 时带进来的那一份，或者之前下载存下的那一份。
            // 所以梯子开不开、WiFi 还是流量、甚至断网，都进得去。
            // 新页面交给网页自己去换（它开起来会对版本号）。
            // 网址始终不变（换成 file:// 的话她的东西会全丢）
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    if (req == null || !req.isForMainFrame()) return null;
                    if (!"GET".equalsIgnoreCase(req.getMethod())) return null;
                    String u = req.getUrl() == null ? "" : req.getUrl().toString();
                    if (!PageCache.isMain(u, siteUrl())) return null;
                    if (!PageCache.hasAny(MainActivity.this)) return null;   // 手边真的什么都没有，交给 WebView
                    return new WebResourceResponse("text/html", "utf-8", PageCache.open(MainActivity.this));
                } catch (Exception e) { return null; }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    // 手边有存货就不算失败 —— 那一份已经把页面撑起来了
                    if (PageCache.hasAny(MainActivity.this)) return;
                    loadFailed = true;
                    askForUrl(true);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                // 上一次没收尾的先收掉。不收的话 WebView 会一直以为选图还没结束，
                // 之后再点任何一个"选图片"都没反应 —— 相册加不进去照片就是卡在这儿
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = cb;

                boolean many = false;
                try { many = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE; } catch (Exception e) {}

                // 一、先按 WebView 自己给的意图开。别拿 createChooser 包 ——
                //     有些手机的相册被包一层之后只肯回一张，甚至什么都不回
                try {
                    Intent it = params.createIntent();
                    if (it != null) {
                        it.addCategory(Intent.CATEGORY_OPENABLE);
                        if (many) it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        pickerOpen = true;
                        startActivityForResult(it, FILE_PICK);
                        return true;
                    }
                } catch (Exception e) { /* 换下一种 */ }

                // 二、退一步用系统文档选择器。这个在国产系统上最稳
                try {
                    Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    it.setType("image/*");
                    if (many) it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    pickerOpen = true;
                    startActivityForResult(it, FILE_PICK);
                    return true;
                } catch (Exception e) { /* 再退一步 */ }

                // 三、再退一步用取内容。老系统只认这个
                try {
                    Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    it.setType("image/*");
                    if (many) it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    pickerOpen = true;
                    startActivityForResult(Intent.createChooser(it, "选照片"), FILE_PICK);
                    return true;
                } catch (Exception e) { /* 认了 */ }

                // 全都开不起来，也得把话说完，不然下一次点还是没反应
                pickerOpen = false;
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
                return false;
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
        web.addJavascriptInterface(new UsageBridge(this), "MengxiaUsage");
        web.addJavascriptInterface(new XBridge(this), "MengxiaX");
        // 聊天记录被盖掉之后，去 LevelDB 的旧文件里按字节捞。只读 App 自己的目录
        web.addJavascriptInterface(new DigBridge(this), "MengxiaDig");
        // 把原始 LevelDB 文件原样打包到「下载」——没 root 也不用电脑
        web.addJavascriptInterface(new Pack(this), "MengxiaPack");
        // 她自己想强行去拿一份最新的页面时用：把存的那份丢掉，再从网上下一次
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void fresh() {
                // 以前这儿是「删掉存的那份 + 整个 WebView 重新加载」。那是错的：
                // 一重新加载又走拦截，下载的那份刚被删了，于是退到装 APK 时
                // 一起装进来的 assets/page.html —— 比原来还旧。她点一次退一次。
                //
                // 现在只做一件事：后台去拉一份新的存起来。当前这一屏不动
                // （网页那边自己会把新页面换上去），下次开机手边就是新的了。
                PageCache.refreshLater(MainActivity.this, siteUrl());
            }

            /**
             * 拿别的 App 打开一个网址（推特、浏览器之类）。
             * 走系统的 ACTION_VIEW，装了推特就直接进推特。
             * 不这么做的话链接会在梦匣自己的 WebView 里打开，把她挤出去。
             */
            @android.webkit.JavascriptInterface
            public void openUrl(final String u) {
                if (u == null || u.length() == 0) return;
                web.post(new Runnable() {
                    public void run() {
                        try {
                            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(it);
                        } catch (Exception ignored) {}
                    }
                });
            }
        }, "MengxiaShell");

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

        // 页面已经用手边那份开起来了。这会儿再悄悄去把存货换成新的，
        // 下次开更快。这一趟的新页面不靠它 —— 网页自己会去对版本号。
        web.postDelayed(new Runnable() {
            public void run() { PageCache.refreshLater(MainActivity.this, siteUrl()); }
        }, 3000);
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
                        u = fixScheme(u);
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
            pickerOpen = false;
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(pickedFrom(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 多选回来的在 ClipData 里，单选在 data 上。parseResult 有的机型接不住，自己拆一遍 */
    private Uri[] pickedFrom(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return null;
        try {
            android.content.ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                Uri[] out = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) out[i] = clip.getItemAt(i).getUri();
                return out;
            }
        } catch (Exception e) {}
        Uri one = data.getData();
        if (one != null) return new Uri[]{ one };
        try {
            return WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        } catch (Exception e) { return null; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 选图那一趟没走完就回来了（返回键、系统把相册杀了、内存不够把这个页面回收了）。
        // 这时候必须把话说完，不然 WebView 会一直卡在"还在选"，
        // 之后不管点多少次加照片都毫无反应
        if (filePathCallback != null && !pickerOpen) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
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
