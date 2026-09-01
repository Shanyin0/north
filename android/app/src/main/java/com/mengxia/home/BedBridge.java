package com.mengxia.home;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;

import java.util.Calendar;

/**
 * 该睡了。
 *
 * 到点把她从别的 App 里拽回梦匣来 —— 就这一件事，第一档只做这个。
 * 没有设备管理员，没有无障碍，没有任何能真把手机锁上的权限。
 * 她随手就能划走，这是故意的：先看看「有人到点叫我一声」管不管用，
 * 管用再谈更硬的手段。真锁屏那种要「设备管理员」，
 * 用不好会把自己关在外面，卸载都要先撤权限 —— 那一步得她点头才做。
 *
 * 怎么实现的：AlarmManager 定一个每天的闹钟，
 * 时间到了 PushReceiver 把梦匣拉到前台，网页那边弹一张卡。
 * 跟现在推送用的是同一套东西，没有新权限。
 */
public class BedBridge {

    private final Activity act;
    static final String ACTION_BED = "com.mengxia.home.BEDTIME";
    private static final int REQ = 7301;

    BedBridge(Activity a) { this.act = a; }

    private SharedPreferences sp() {
        return act.getSharedPreferences(MainActivity.PREF, Context.MODE_PRIVATE);
    }

    /** 定在几点几分。hh 0-23，mm 0-59。传 -1 就是关掉 */
    @JavascriptInterface
    public void set(int hh, int mm) {
        AlarmManager am = (AlarmManager) act.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent it = new Intent(act, PushReceiver.class).setAction(ACTION_BED);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(act, REQ, it, flags);

        if (hh < 0 || hh > 23 || mm < 0 || mm > 59) {
            try { am.cancel(pi); } catch (Throwable ignored) {}
            sp().edit().putInt("bed_hh", -1).putInt("bed_mm", -1).apply();
            return;
        }

        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hh);
        c.set(Calendar.MINUTE, mm);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        // 今天这个点已经过了就定明天
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);

        try {
            // 每天一次。用 setRepeating 而不是精确闹钟 ——
            // 精确闹钟在新系统上要单独申请权限，而「睡觉提醒」差几分钟没关系
            am.setRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY, pi);
        } catch (Throwable ignored) {}
        sp().edit().putInt("bed_hh", hh).putInt("bed_mm", mm).apply();
    }

    /** 现在定在几点。没定就返回 -1 */
    @JavascriptInterface
    public int hour() { return sp().getInt("bed_hh", -1); }

    @JavascriptInterface
    public int minute() { return sp().getInt("bed_mm", -1); }

    /** 开机之后把闹钟重新定上（BootReceiver 会叫这个） */
    static void rearm(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREF, Context.MODE_PRIVATE);
        int hh = p.getInt("bed_hh", -1), mm = p.getInt("bed_mm", -1);
        if (hh < 0) return;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent it = new Intent(ctx, PushReceiver.class).setAction(ACTION_BED);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ, it, flags);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hh);
        c.set(Calendar.MINUTE, mm);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        try {
            am.setRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY, pi);
        } catch (Throwable ignored) {}
    }
}
