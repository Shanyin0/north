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
                finally {
                    // 闹钟改成了「打瞌睡也照响」的那种，那种一次只订一个，
                    // 所以每响一次都得在这儿把下一次订上，不然就断了
                    try { Pusher.schedule(app); } catch (Throwable ignored) {}
                    try { pr.finish(); } catch (Throwable ignored) {}
                }
            }
        }).start();
    }
}
