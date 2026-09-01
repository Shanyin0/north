package com.mengxia.home;

import android.app.Activity;
import android.webkit.JavascriptInterface;

/**
 * 网页告诉壳「现在在通话」。
 *
 * 壳知道了才能做两件事：
 *   一、立起前台服务，麦克风留着、进程不被回收（切到别的 App 通话还在）
 *   二、按返回键的时候不要把这一屏关掉，改成退到后台
 *
 * 只是一个标记加一根桩子。通话本身、录音、声音，全在网页那边，
 * 这儿一个字都不碰。
 */
public class CallBridge {

    private final Activity act;
    /** 现在在不在通话里。MainActivity 按返回键的时候要看它 */
    static volatile boolean inCall = false;

    CallBridge(Activity a) { this.act = a; }

    /** 接通了 */
    @JavascriptInterface
    public void begin() {
        if (inCall) return;
        inCall = true;
        try { CallService.begin(act.getApplicationContext()); } catch (Throwable ignored) {}
    }

    /** 挂了。一定要叫，不然通知栏那条「通话中」撤不掉 */
    @JavascriptInterface
    public void end() {
        if (!inCall) return;
        inCall = false;
        try { CallService.end(act.getApplicationContext()); } catch (Throwable ignored) {}
    }

    /** 壳这边认为在不在通话里。网页拿它对一下账 */
    @JavascriptInterface
    public boolean on() { return inCall; }
}
