package com.mengxia.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 闹钟到点：在后台线程里跑一遍推送决策 */
public class PushReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try { Pusher.run(app); } catch (Throwable ignored) {}
                finally { try { pr.finish(); } catch (Throwable ignored) {} }
            }
        }).start();
    }
}
