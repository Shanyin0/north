package com.mengxia.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 通话的时候在后台立着的那根桩子。
 *
 * 没有它的话：她一切走，安卓就把麦克风收回去（安卓 9 以后不给后台
 * App 用麦），过一会儿连整个进程都可能被回收 —— 通话就这么断了。
 *
 * 有它就不一样：这是个前台服务，声明了 microphone 类型，
 * 系统会当成「正在打电话」那一类，麦克风留着，进程也不回收。
 * 代价是通知栏里会挂一条「通话中」—— 这是安卓强制的，撤不掉，
 * 但点它能直接回到通话那一屏，反而好用。
 *
 * 挂断了一定要 stop()，不然那条通知会一直杵在那儿。
 */
public class CallService extends Service {

    private static final String CH_ID = "mengxia_call";
    private static final int NOTI_ID = 8801;

    static void begin(Context ctx) {
        try {
            Intent it = new Intent(ctx, CallService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(it);
            else ctx.startService(it);
        } catch (Throwable ignored) {}
    }

    static void end(Context ctx) {
        try { ctx.stopService(new Intent(ctx, CallService.class)); } catch (Throwable ignored) {}
    }

    @Override
    public IBinder onBind(Intent it) { return null; }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                // 通话中这条不该响、不该震 —— 她正在打电话
                NotificationChannel ch = new NotificationChannel(CH_ID, "通话中", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }

            Intent back = new Intent(this, MainActivity.class);
            back.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int pf = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pf |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, back, pf);

            // 用系统自带的 Notification.Builder，跟 Pusher 那边一个路子。
            // 这个项目里没有 androidx，别引 NotificationCompat
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(this, CH_ID);
            else b = new Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_noti)
                    .setContentTitle("通话中")
                    .setContentText("点一下回到通话")
                    .setOngoing(true)                 // 划不掉 —— 通话还在
                    .setShowWhen(false)
                    .setContentIntent(pi);
            // 安卓 8 以前没有频道，轻重缓急写在通知自己身上
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_LOW);
            Notification n = b.build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 安卓 14 起必须说清楚这个前台服务是干什么的，
                // 说了 microphone 才留得住麦克风
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Throwable t) {
            // 立不起来就算了 —— 通话本身照跑，只是切走可能会断。
            // 不能因为这根桩子没立住就把通话弄崩
            try { stopSelf(); } catch (Throwable ignored) {}
        }
        // 被系统杀掉之后不要自己复活 —— 那时候通话早没了，
        // 复活只会留一条撤不掉的「通话中」
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { stopForeground(true); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
