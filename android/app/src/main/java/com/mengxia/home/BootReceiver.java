package com.mengxia.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后把闹钟重新挂上 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try { Pusher.schedule(ctx.getApplicationContext()); } catch (Throwable ignored) {}
    }
}
