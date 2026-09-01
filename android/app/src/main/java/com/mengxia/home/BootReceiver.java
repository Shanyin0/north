package com.mengxia.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后把闹钟重新挂上 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try { Pusher.schedule(ctx.getApplicationContext()); } catch (Throwable ignored) {}
        // 「该睡了」那个也一起重挂上 —— 不然重启一次就断了
        try { BedBridge.rearm(ctx.getApplicationContext()); } catch (Throwable ignored) {}
    }
}
