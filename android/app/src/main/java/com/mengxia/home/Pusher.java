package com.mengxia.home;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Random;

/**
 * 主动推送：闹钟叫醒 → 决策层 → 影子路由调模型 → 本地通知 + 存进待收队列。
 * 网页那边下次打开时把队列里的消息接走，放进聊天记录。
 */
public class Pusher {

    static final String PREF = "mengxia_push";
    static final String CH_ID = "mengxia_him";
    static final int NOTI_ID = 8801;
    private static int notiSeq = 0;
    static final String SHORTCUT_ID = "mengxia_sir";
    static final int ALARM_ID = 8802;
    static final long INTERVAL = 15 * 60 * 1000L;   // 每 15 分钟看一眼

    // ---------- 闹钟 ----------
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ctx, PushReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, ALARM_ID, i, flags);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL, INTERVAL, pi);
        } catch (Exception ignored) {}
    }

    // ---------- 网页交给壳的东西 ----------
    public static void sync(Context ctx, String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            e.putString("cfg", json);
            e.putLong("lastMsgTs", o.optLong("lastMsgTs", System.currentTimeMillis()));
            e.putInt("usedToday", o.optInt("usedToday", 0));
            e.putString("day", o.optString("day", ""));
            e.apply();
            String avatar = o.optString("avatar", "");
            if (avatar.length() > 0 && avatar.startsWith("data:image")) saveAvatar(ctx, avatar);
        } catch (Exception ignored) {}
    }

    private static void saveAvatar(Context ctx, String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return;
            byte[] b = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            File f = new File(ctx.getFilesDir(), "sir_avatar.png");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(b);
            fo.close();
        } catch (Exception ignored) {}
    }

    private static Bitmap avatar(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "sir_avatar.png");
            if (f.exists()) return square(BitmapFactory.decodeFile(f.getAbsolutePath()));
        } catch (Exception ignored) {}
        // 没有他的脸就宁可什么都不放 —— 别拿软件图标顶上
        return null;
    }

    /** 通知里的头像要方的、要小的：先居中裁成正方形，再缩到 256。 */
    private static Bitmap square(Bitmap src) {
        if (src == null) return null;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            int side = Math.min(w, h);
            Bitmap cut = Bitmap.createBitmap(src, (w - side) / 2, (h - side) / 2, side, side);
            if (side <= 256) return cut;
            return Bitmap.createScaledBitmap(cut, 256, 256, true);
        } catch (Exception e) {
            return src;
        }
    }

    // ---------- 待收队列 ----------
    public static String takePending(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String s = sp.getString("pending", "[]");
        sp.edit().putString("pending", "[]").apply();
        return s;
    }

    private static void addPending(Context ctx, String text) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("pending", "[]"));
            JSONObject o = new JSONObject();
            o.put("text", text);
            o.put("ts", System.currentTimeMillis());
            arr.put(o);
            sp.edit().putString("pending", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ---------- 决策层 ----------
    private static String blocked(Context ctx, JSONObject cfg) {
        if (!cfg.optBoolean("pushOn", true)) return "off";
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        int d = c.get(Calendar.DAY_OF_WEEK);           // 1=周日 7=周六
        boolean weekend = (d == 1 || d == 7);
        if (weekend ? (h >= 2 && h < 12) : (h >= 0 && h < 8)) return "sleep";

        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long last = sp.getLong("lastMsgTs", 0);
        int cd = sp.getInt("cooldown", 0);
        if (cd <= 0) { cd = 120 + new Random().nextInt(91); sp.edit().putInt("cooldown", cd).apply(); }
        if (System.currentTimeMillis() - last < cd * 60000L) return "cooldown";

        String today = today();
        String day = sp.getString("day", "");
        int used = day.equals(today) ? sp.getInt("usedToday", 0) : 0;
        if (used >= cfg.optInt("pushMax", 5)) return "limit";
        return "";
    }

    private static String today() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    private static String userStatus() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        int d = c.get(Calendar.DAY_OF_WEEK);
        boolean weekend = (d == 1 || d == 7);
        if (weekend) {
            if (h >= 2 && h < 12) return "她在睡觉（周末晚睡晚起）";
            if (h < 14) return "她可能刚起床";
            if (h < 18) return "她可能在出门或者窝着休息";
            return "她在放松，或者在刷手机";
        }
        if (h < 8) return "她在睡觉";
        if (h < 10) return "她可能刚起床，或者在路上";
        if (h < 12) return "上午，她大概在忙";
        if (h < 14) return "午间，她可能在吃饭或午休";
        if (h < 19) return "下午，她大概在忙";
        if (h < 22) return "她该到家了，在休息";
        return "她可能准备睡了，或者还在刷手机";
    }

    // ---------- 主流程 ----------
    public static void run(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String cfgStr = sp.getString("cfg", "");
            if (cfgStr.length() == 0) return;
            JSONObject cfg = new JSONObject(cfgStr);
            if (blocked(ctx, cfg).length() > 0) return;

            String reply = callModel(ctx, cfg);
            if (reply == null) return;
            reply = strip(reply);
            if (reply.trim().length() == 0) return;

            // 他想分几条就是几条 —— 一行一条，别糊成一整段
            String[] lines = reply.split("\n");
            int sent = 0;
            for (String raw : lines) {
                String one = clean(raw);
                if (one.length() == 0) continue;
                addPending(ctx, one);
                if (sent > 0) { try { Thread.sleep(700); } catch (InterruptedException ig) {} }
                notify(ctx, cfg.optString("sirName", "先生"), one);
                if (++sent >= 5) break;
            }
            if (sent == 0) return;
            SharedPreferences.Editor e = sp.edit();
            e.putLong("lastMsgTs", System.currentTimeMillis());
            e.putInt("cooldown", 120 + new Random().nextInt(91));
            String today = today();
            int used = today.equals(sp.getString("day", "")) ? sp.getInt("usedToday", 0) : 0;
            e.putString("day", today);
            e.putInt("usedToday", used + 1);
            e.apply();

        } catch (Exception ignored) {}
    }

    /** 只把标签和代码块去掉，换行留着 —— 换行就是"他分了几条" */
    private static String strip(String t) {
        if (t == null) return "";
        return t.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<thinking>.*?</thinking>", "")
                .replaceAll("(?s)<status>.*?</status>", "")
                .replaceAll("(?s)<photo>.*?</photo>", "")
                .replaceAll("(?s)<draw>.*?</draw>", "")
                .replaceAll("(?s)<mark>.*?</mark>", "")
                .replaceAll("(?s)<moment>.*?</moment>", "")
                .replaceAll("(?s)<diary>.*?</diary>", "")
                .replaceAll("(?s)```.*?```", "")
                .trim();
    }

    private static String clean(String t) {
        if (t == null) return "";
        String s = t.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<thinking>.*?</thinking>", "")
                .replaceAll("(?s)<status>.*?</status>", "")
                .replaceAll("(?s)<photo>.*?</photo>", "")
                .replaceAll("(?s)```.*?```", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    // ---------- 调模型（影子路由） ----------
    private static String callModel(Context ctx, JSONObject cfg) {
        try {
            String mode = cfg.optString("apiMode", "");
            String key = cfg.optString("apiKey", "");
            if (key.length() == 0) return null;

            // 她要是把「余光」开着，就现读一遍这几个小时她在手机上干了什么。
            // 网页那边存的是她自己的一份，这里是壳在后台自己读的 —— 后台醒来的时候网页多半没在跑。
            String eye = "";
            if (cfg.optBoolean("eyeOn", false)) {
                try { eye = UsageBridge.brief(ctx, 6, cfg.optString("eyeMute", "")); } catch (Exception ig) {}
            }
            String shadow = "<system_trigger>\n现在是 " + timeStr() + "。\n她此刻大概："
                    + userStatus() + "。\n\n"
                    + (eye.length() > 0
                        ? "[她这几个小时在手机上干嘛]\n" + eye
                          + "\n这是背景，不是话题。别拿它盘问她，也别每次都说「我看见你在刷」——大多数时候它只该影响你说话的语气。\n\n"
                        : "")
                    + cfg.optString("shadowBody", "") + "\n</system_trigger>";

            JSONArray recent = cfg.optJSONArray("messages");
            if (recent == null) recent = new JSONArray();

            if ("anthropic".equals(mode)) {
                JSONArray msgs = new JSONArray();
                for (int i = 0; i < recent.length(); i++) msgs.put(recent.getJSONObject(i));
                JSONObject sh = new JSONObject();
                sh.put("role", "user");
                sh.put("content", shadow);
                msgs.put(sh);
                JSONObject body = new JSONObject();
                body.put("model", cfg.optString("apiModel", "claude-3-5-haiku-latest"));
                body.put("max_tokens", 400);
                body.put("system", cfg.optString("sysPrompt", ""));
                body.put("messages", msgs);
                String out = post("https://api.anthropic.com/v1/messages", body.toString(), new String[][]{
                        {"Content-Type", "application/json"},
                        {"x-api-key", key},
                        {"anthropic-version", "2023-06-01"}
                });
                if (out == null) return null;
                JSONObject j = new JSONObject(out);
                JSONArray content = j.optJSONArray("content");
                if (content == null || content.length() == 0) return null;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < content.length(); i++) sb.append(content.getJSONObject(i).optString("text", ""));
                return sb.toString();
            }

            String base = cfg.optString("apiBase", "");
            if (base.length() == 0) return null;
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", cfg.optString("sysPrompt", ""));
            msgs.put(sys);
            for (int i = 0; i < recent.length(); i++) msgs.put(recent.getJSONObject(i));
            JSONObject sh = new JSONObject();
            sh.put("role", "user");
            sh.put("content", shadow);
            msgs.put(sh);
            JSONObject body = new JSONObject();
            body.put("model", cfg.optString("apiModel", "gpt-4o-mini"));
            body.put("max_tokens", 400);
            body.put("messages", msgs);
            String out = post(base + "/v1/chat/completions", body.toString(), new String[][]{
                    {"Content-Type", "application/json"},
                    {"Authorization", "Bearer " + key}
            });
            if (out == null) return null;
            JSONObject j = new JSONObject(out);
            JSONArray choices = j.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            return choices.getJSONObject(0).getJSONObject("message").optString("content", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String timeStr() {
        Calendar c = Calendar.getInstance();
        String[] wd = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 "
                + String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
                + "（" + wd[c.get(Calendar.DAY_OF_WEEK) - 1] + "）";
    }

    private static String post(String url, String body, String[][] headers) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(20000);
            con.setReadTimeout(60000);
            con.setDoOutput(true);
            for (String[] h : headers) con.setRequestProperty(h[0], h[1]);
            OutputStream os = con.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            int code = con.getResponseCode();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? con.getErrorStream() : con.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            if (code >= 400) return null;
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ---------- 通知 ----------
    public static void notify(Context ctx, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "他来找你", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("他自己浮上来说话的时候");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(ctx, CH_ID);
            else b = new Notification.Builder(ctx);
            b.setSmallIcon(R.drawable.ic_noti)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            Bitmap av = avatar(ctx);
            boolean asChat = false;

            // 安卓 9 以上：走"对话通知"——最前面那一格是他的头像，右边不再挂第二张图。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    Icon face = (av != null) ? Icon.createWithBitmap(av) : null;
                    Person.Builder pb = new Person.Builder().setKey("sir").setName(title).setImportant(true);
                    if (face != null) pb.setIcon(face);
                    Person sir = pb.build();
                    Person her = new Person.Builder().setKey("me").setName("我").build();

                    Notification.MessagingStyle st = new Notification.MessagingStyle(her);
                    st.addMessage(new Notification.MessagingStyle.Message(
                            text, System.currentTimeMillis(), sir));
                    b.setStyle(st);
                    b.addPerson(sir);
                    // 只有快捷方式登记成功，系统才会按对话通知排版（头像在最前）
                    if (pushConversation(ctx, title, face, sir)) {
                        b.setShortcutId(SHORTCUT_ID);
                        asChat = true;
                    }
                } catch (Exception ignored) {}
            }

            // 排不上对话样式就退回去：至少把头像挂上，别只剩一个软件图标。
            if (!asChat) {
                b.setStyle(new Notification.BigTextStyle().bigText(text));
                if (av != null) b.setLargeIcon(av);
            }

            notiSeq = (notiSeq + 1) % 20;
            nm.notify(NOTI_ID + notiSeq, b.build());
        } catch (Exception ignored) {}
    }

    /**
     * 把"先生"注册成一个长期存在的对话快捷方式。
     * 系统认出它之后，通知就会按聊天的样子排：头像在最前，内容跟在后面。
     */
    private static boolean pushConversation(Context ctx, String name, Icon face, Person sir) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        try {
            ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
            if (sm == null) return false;
            Intent open = new Intent(ctx, MainActivity.class);
            open.setAction(Intent.ACTION_VIEW);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ShortcutInfo.Builder sb = new ShortcutInfo.Builder(ctx, SHORTCUT_ID)
                    .setShortLabel(name)
                    .setLongLabel(name)
                    .setLongLived(true)
                    .setPerson(sir)
                    .setIntent(open);
            if (face != null) sb.setIcon(face);
            ShortcutInfo si = sb.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) sm.pushDynamicShortcut(si);
            else sm.addDynamicShortcuts(java.util.Collections.singletonList(si));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
