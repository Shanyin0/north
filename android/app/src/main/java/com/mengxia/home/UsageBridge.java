package com.mengxia.home;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 她最近在用什么 App。
 *
 * 走的是安卓自带的「使用情况访问」这个权限 —— 她要在系统设置里亲手给梦匣打开，
 * 打开之后这边才读得到，随时能关。读到的东西一步都不出这台手机：
 * 只交给 WebView 里的网页，网页存在自己的 localStorage 里。
 *
 * 读不到内容，只知道「几点打开了哪个 App」。看不见屏幕，看不见里面的字。
 */
public class UsageBridge {

    private final Activity act;

    public UsageBridge(Activity a) { this.act = a; }

    /** 她给过权限没有 */
    @JavascriptInterface
    public boolean granted() { return granted(act); }

    static boolean granted(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), ctx.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), ctx.getPackageName());
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return ctx.checkCallingOrSelfPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 把她送到那个设置页去开。开不开是她的事 */
    @JavascriptInterface
    public void ask() {
        try {
            Intent it = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
        } catch (Exception e) {
            try {
                Intent it = new Intent(Settings.ACTION_SETTINGS);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(it);
            } catch (Exception e2) { /* 认了 */ }
        }
    }

    /**
     * 最近这些小时里，她开过哪些 App，几点开的，各待了多久。
     * 回一串 JSON：[{p:包名, n:名字, t:第一次打开的时间戳, last:最后一次, ms:大概待了多久, n2:开了几次}]
     */
    @JavascriptInterface
    public String recent(int hours) { return collect(act, hours).toString(); }

    static JSONArray collect(Context ctx, int hours) {
        JSONArray out = new JSONArray();
        if (!granted(ctx)) return out;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return out;
        int h = (hours <= 0 || hours > 72) ? 12 : hours;
        long now = System.currentTimeMillis();
        long from = now - h * 3600L * 1000L;
        try {
            UsageStatsManager um = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (um == null) return out;
            UsageEvents ev = um.queryEvents(from, now);
            if (ev == null) return out;

            // 一个包一条：第一次、最后一次、开了几次、前台待了多久
            java.util.LinkedHashMap<String, long[]> agg = new java.util.LinkedHashMap<>();
            java.util.HashMap<String, Long> fgSince = new java.util.HashMap<>();
            UsageEvents.Event e = new UsageEvents.Event();
            String me = ctx.getPackageName();

            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                String p = e.getPackageName();
                if (p == null || p.equals(me)) continue;
                int type = e.getEventType();
                boolean toFg = (type == UsageEvents.Event.MOVE_TO_FOREGROUND)
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && type == UsageEvents.Event.ACTIVITY_RESUMED);
                boolean toBg = (type == UsageEvents.Event.MOVE_TO_BACKGROUND)
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && type == UsageEvents.Event.ACTIVITY_PAUSED);
                if (!toFg && !toBg) continue;

                long[] a = agg.get(p);
                if (a == null) { a = new long[]{ e.getTimeStamp(), e.getTimeStamp(), 0, 0 }; agg.put(p, a); }
                a[1] = e.getTimeStamp();

                if (toFg) {
                    Long had = fgSince.get(p);
                    if (had == null) { fgSince.put(p, e.getTimeStamp()); a[3]++; }
                } else {
                    Long since = fgSince.remove(p);
                    if (since != null) {
                        long d = e.getTimeStamp() - since;
                        if (d > 0 && d < 6L * 3600 * 1000) a[2] += d;
                    }
                }
            }
            // 还开着的那个，算到现在
            for (java.util.Map.Entry<String, Long> en : fgSince.entrySet()) {
                long[] a = agg.get(en.getKey());
                if (a == null) continue;
                long d = now - en.getValue();
                if (d > 0 && d < 6L * 3600 * 1000) a[2] += d;
            }

            PackageManager pm = ctx.getPackageManager();
            java.util.HashSet<String> real = launchable(pm);
            for (java.util.Map.Entry<String, long[]> en : agg.entrySet()) {
                long[] a = en.getValue();
                if (a[2] < 8000 && a[3] < 2) continue;      // 划过去一下不算
                // 桌面上没有图标的一律不算：权限控制器、软件包安装程序、
                // 安全认证服务、IntentResolver 那一类，都是系统自己在动
                if (!real.isEmpty() && !real.contains(en.getKey())) continue;
                JSONObject o = new JSONObject();
                o.put("p", en.getKey());
                o.put("n", label(pm, en.getKey()));
                o.put("t", a[0]);
                o.put("last", a[1]);
                o.put("ms", a[2]);
                o.put("n2", a[3]);
                out.put(o);
            }
        } catch (Exception ex) { /* 读不到就当没有 */ }
        return out;
    }

    /**
     * 给后台推送用的那一段。壳自己在后台醒过来的时候，网页多半没在跑，
     * 所以这里现读一遍，拼成人话塞进上下文里。
     * muteCsv 是她挑出来不给看的那几个包名，逗号隔开。
     */
    static String brief(Context ctx, int hours, String muteCsv) {
        JSONArray arr = collect(ctx, hours);
        if (arr.length() == 0) return "";
        java.util.HashSet<String> mute = new java.util.HashSet<>();
        if (muteCsv != null) {
            for (String s : muteCsv.split(",")) {
                String t = s.trim();
                if (t.length() > 0) mute.add(t);
            }
        }
        java.util.ArrayList<JSONObject> rows = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || mute.contains(o.optString("p"))) continue;
            rows.add(o);
        }
        if (rows.isEmpty()) return "";
        java.util.Collections.sort(rows, new java.util.Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                long x = a.optLong("last"), y = b.optLong("last");
                return (y < x) ? -1 : ((y == x) ? 0 : 1);
            }
        });
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JSONObject o : rows) {
            if (n++ >= 10) break;
            long t = o.optLong("t"), last = o.optLong("last");
            if (sb.length() > 0) sb.append("\n");
            sb.append(clock(t));
            if (last - t > 120000) sb.append("–").append(clock(last));
            sb.append(" ").append(o.optString("n")).append("，").append(span(o.optLong("ms")));
        }
        return sb.toString();
    }

    private static String clock(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    private static String span(long ms) {
        long m = Math.round(ms / 60000.0);
        if (m < 1) return "一下下";
        if (m < 60) return m + " 分钟";
        long h = m / 60, r = m % 60;
        return h + " 小时" + (r > 0 ? " " + r + " 分" : "");
    }

    /** 现在停在哪个 App 上（她刚从别处切回梦匣的时候能问一句） */
    @JavascriptInterface
    public String now() {
        if (!granted()) return "";
        try {
            UsageStatsManager um = (UsageStatsManager) act.getSystemService(Context.USAGE_STATS_SERVICE);
            if (um == null) return "";
            long t = System.currentTimeMillis();
            UsageEvents ev = um.queryEvents(t - 10 * 60 * 1000L, t);
            String last = "";
            UsageEvents.Event e = new UsageEvents.Event();
            String me = act.getPackageName();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int type = e.getEventType();
                boolean toFg = (type == UsageEvents.Event.MOVE_TO_FOREGROUND)
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && type == UsageEvents.Event.ACTIVITY_RESUMED);
                if (toFg && e.getPackageName() != null && !e.getPackageName().equals(me)) {
                    last = e.getPackageName();
                }
            }
            if (last.length() == 0) return "";
            return label(act.getPackageManager(), last);
        } catch (Exception ex) {
            return "";
        }
    }

    /** 桌面上真有一个图标能点进去的那些包 */
    private static java.util.HashSet<String> launchable(PackageManager pm) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(it, 0);
            if (list != null) {
                for (android.content.pm.ResolveInfo ri : list) {
                    if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                        out.add(ri.activityInfo.packageName);
                    }
                }
            }
        } catch (Exception e) { /* 问不到就当没这回事，宁可多显示也不要一片空白 */ }
        return out;
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Exception e) { /* 卸载了就退回包名 */ }
        return pkg;
    }
}
