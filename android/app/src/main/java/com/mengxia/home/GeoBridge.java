package com.mengxia.home;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
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
     * 最近一次位置。
     *
     * 只读缓存里那一份，不主动开 GPS 去测 —— 开 GPS 费电，而且要等十几秒。
     * 系统里只要有别的 App 用过定位，这份就是新的。
     * 拿不到就返回空字符串，网页那边照常走。
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
            Location best = null;
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
