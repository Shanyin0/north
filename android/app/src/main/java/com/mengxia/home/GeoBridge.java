package com.mengxia.home;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.List;

/**
 * 她在哪儿。
 *
 * 用的是安卓自带的定位，不经过任何第三方 —— 拿到的就是一对经纬度，
 * 存在她自己手机的网页里。要变成「在哪条街」得靠地图服务反查地址，
 * 那一步在网页那边做，用哪家（高德、百度、腾讯…）由她自己填 key 决定，
 * 这儿一概不管。
 *
 * 权限是她在系统里亲手给的，随时能收回。没给就返回空，别的功能照跑。
 *
 * 为什么不用 Google 的 FusedLocationProvider：那个要 Play 服务，
 * 国行机上多半没有。LocationManager 是系统自带的，哪台机器都有。
 */
public class GeoBridge {

    private final Activity act;
    /** locate() 主动测到的那一份。比系统缓存新，last() 优先给它 */
    private volatile Location fresh;
    private volatile boolean measuring;
    private volatile long measureAt;

    GeoBridge(Activity a) { this.act = a; }

    private boolean has(String p) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /** 有没有给定位权限 */
    @JavascriptInterface
    public boolean granted() {
        return has(Manifest.permission.ACCESS_COARSE_LOCATION)
                || has(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    /** 去要权限。系统会弹一次，她点了才算 */
    @JavascriptInterface
    public void ask() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            act.requestPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION }, 4101);
        } catch (Throwable ignored) {}
    }

    /** 定位服务本身开着没有（有权限但她把 GPS 关了，也是拿不到的） */
    @JavascriptInterface
    public boolean on() {
        try {
            LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) { return false; }
    }

    /**
     * 主动测一次。
     *
     * 只读缓存那份是不够的 —— 半天没开过地图的话，
     * 系统里存的可能是几小时前、几公里外的位置。
     * 想知道「她此刻在哪儿」就必须自己去测。
     *
     * 会开 GPS，十几秒，费一点电，所以只在她点「看一眼」的时候测，
     * 不后台自己测。测到的那一份存在 fresh 字段里，last() 优先给它。
     *
     * 测完自己撤掉监听。四十秒还没结果也撤 —— 室内经常测不到 GPS，
     * 挂着不撤就一直在耗电
     */
    @JavascriptInterface
    public void locate() {
        if (!granted()) return;
        if (measuring) return;
        try {
            final LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            measuring = true;
            measureAt = System.currentTimeMillis();
            final LocationListener[] box = new LocationListener[1];
            final LocationListener li = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    if (l == null) return;
                    fresh = l;
                    measuring = false;
                    try { lm.removeUpdates(box[0]); } catch (Throwable ignored) {}
                }
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
                @Override public void onStatusChanged(String p, int st, android.os.Bundle ex) {}
            };
            box[0] = li;
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        // 网络定位（基站／WiFi）先来，通常几秒就有，室内也管用；
                        // GPS 一起挂着，它准，但慢，而且室内多半没有
                        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, li);
                        }
                        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, li);
                        }
                    } catch (Throwable ignored) { measuring = false; }
                }
            });
            // 四十秒还没测到就撤，别一直耗着电
            act.getWindow().getDecorView().postDelayed(new Runnable() {
                public void run() {
                    if (!measuring) return;
                    measuring = false;
                    try { lm.removeUpdates(li); } catch (Throwable ignored) {}
                }
            }, 40000);
        } catch (Throwable t) { measuring = false; }
    }

    /** 正在测吗。网页拿它决定要不要显示「在测…」 */
    @JavascriptInterface
    public boolean measuring() {
        // 挂太久的当成不在测了
        if (measuring && System.currentTimeMillis() - measureAt > 45000) measuring = false;
        return measuring;
    }

    /**
     * 最近一次位置。
     *
     * 先给刚测到的那一份（locate() 拿到的）；没有才退回系统缓存 ——
     * 缓存那份是别的 App 用定位时顺手留下的，可能很旧。
     *
     * maxAgeMin：多久以内的才算数，默认 30 分钟
     */
    @JavascriptInterface
    public String last(int maxAgeMin) {
        if (!granted()) return "";
        int mins = (maxAgeMin <= 0 || maxAgeMin > 1440) ? 30 : maxAgeMin;
        try {
            LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return "";
            List<String> ps = lm.getProviders(true);
            if (ps == null || ps.isEmpty()) return "";
            Location best = fresh;      // 刚测到的那份最靠得住
            for (String p : ps) {
                Location l;
                try { l = lm.getLastKnownLocation(p); }
                catch (SecurityException e) { continue; }
                if (l == null) continue;
                if (best == null) { best = l; continue; }
                // 新的优先；一样新就要准的
                if (l.getTime() > best.getTime() + 60000) best = l;
                else if (Math.abs(l.getTime() - best.getTime()) <= 60000
                        && l.getAccuracy() > 0 && l.getAccuracy() < best.getAccuracy()) best = l;
            }
            if (best == null) return "";
            long age = System.currentTimeMillis() - best.getTime();
            if (age > mins * 60000L) return "";
            JSONObject o = new JSONObject();
            o.put("lat", best.getLatitude());
            o.put("lng", best.getLongitude());
            o.put("acc", Math.round(best.getAccuracy()));      // 大概几米内
            o.put("at", best.getTime());
            o.put("ageSec", age / 1000);
            o.put("src", best.getProvider() == null ? "" : best.getProvider());
            return o.toString();
        } catch (Throwable t) { return ""; }
    }
}
