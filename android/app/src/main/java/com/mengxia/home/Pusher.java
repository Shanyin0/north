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
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
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
    // 频道一旦建好，setShowBadge 之类的就改不动了 —— 想改只能换个新 id。
    // 这里从 mengxia_him 换成 mengxia_him2，为的是把桌面角标关掉
    static final String CH_ID = "mengxia_him2";
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
            long at = SystemClock.elapsedRealtime() + INTERVAL;
            // 以前用的是 setInexactRepeating：手机打瞌睡（Doze）的时候，
            // 系统会把这种闹钟攒起来等醒了一起放，能拖一两个钟头 ——
            // 她说「并没有说可以随时通知」，多半就是这么被拖的。
            // setAndAllowWhileIdle 是打瞌睡也照响的那种，还不用申请
            // SCHEDULE_EXACT_ALARM 那个权限。代价是一次只能订一个，
            // 所以每响一次，PushReceiver 都得再订下一次。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
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

    /** 通知里的头像要方的：先居中裁成正方形，再缩到 512。
     *  512 不是随手写的 —— 下面 adaptiveFace 会把它放进内圈，
     *  只剩三分之二能看见，从 256 起步的话缩完就糊了。 */
    private static Bitmap square(Bitmap src) {
        if (src == null) return null;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            int side = Math.min(w, h);
            Bitmap cut = Bitmap.createBitmap(src, (w - side) / 2, (h - side) / 2, side, side);
            if (side <= 512) return cut;
            return Bitmap.createScaledBitmap(cut, 512, 512, true);
        } catch (Exception e) {
            return src;
        }
    }

    /**
     * 把他的脸垫成「自适应图标」那种尺寸。
     *
     * 自适应图标只保证中间那 72/108（约三分之二）不会被裁掉，外面一圈系统随便切。
     * 直接把一张脸交上去，切完就只剩鼻子了。所以先摊到 1.5 倍的画布上、
     * 脸放正中间，切完正好是完整的一张脸。
     * 外面那一圈填成 app 的米色，万一哪个系统把整块都画出来，看着也是有意的。
     */
    private static Bitmap adaptiveFace(Bitmap face) {
        if (face == null) return null;
        try {
            int inner = face.getWidth();
            if (inner <= 0) return face;
            int side = Math.round(inner * 1.5f);          // 108 / 72 = 1.5
            Bitmap out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(0xFFF6F0E4);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setFilterBitmap(true);
            int at = (side - inner) / 2;
            c.drawBitmap(face, at, at, p);
            return out;
        } catch (Exception e) {
            return face;
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
        // 不打扰的那一段，序章里她自己改。默认还是原来那两段
        int qf = weekend ? cfg.optInt("quietWeFrom", 2) : cfg.optInt("quietWdFrom", 0);
        int qt = weekend ? cfg.optInt("quietWeTo", 12) : cfg.optInt("quietWdTo", 8);
        if (inQuiet(h, qf, qt)) return "sleep";

        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long last = sp.getLong("lastMsgTs", 0);
        int cd = sp.getInt("cooldown", 0);
        // 她把区间改小了，之前掷出来那个大数还留在 prefs 里 —— 落到区间外就重掷
        if (cd < gapLo(cfg) || cd > gapHi(cfg)) {
            cd = rollGap(cfg);
            sp.edit().putInt("cooldown", cd).apply();
        }
        if (System.currentTimeMillis() - last < cd * 60000L) return "cooldown";

        String today = today();
        String day = sp.getString("day", "");
        int used = day.equals(today) ? sp.getInt("usedToday", 0) : 0;
        if (used >= cfg.optInt("pushMax", 5)) return "limit";
        return "";
    }

    /** 两头填一样＝不设。跨零点也算数，比如 23 到 7 */
    static boolean inQuiet(int h, int from, int to) {
        if (from == to) return false;
        if (from < to) return h >= from && h < to;
        return h >= from || h < to;
    }

    static int gapLo(JSONObject cfg) {
        return Math.max(5, cfg.optInt("gapMin", 120));
    }

    static int gapHi(JSONObject cfg) {
        return Math.max(gapLo(cfg), cfg.optInt("gapMax", 210));
    }

    static int rollGap(JSONObject cfg) {
        int lo = gapLo(cfg), hi = gapHi(cfg);
        return lo + new Random().nextInt(hi - lo + 1);
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
            e.putInt("cooldown", rollGap(cfg));
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
                ch.setShowBadge(false);                 // 桌面图标上不要那个小红点
                nm.createNotificationChannel(ch);
                try { nm.deleteNotificationChannel("mengxia_him"); } catch (Exception ignored) {}
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(ctx, CH_ID);
            else b = new Notification.Builder(ctx);
            Bitmap av = avatar(ctx);

            // 左边那一格要的是先生的头像。系统会在头像角上再盖一个 smallIcon ——
            // 就是她说的"角标"。有他的脸就换成 ic_noti_blank：一个极小的单色点。
            // 以前那个是全透明的 —— 有的系统把全透明当成无效图标，转头拿软件图标
            // 顶上，反而更大。留一个很小但确实存在的点，它就没理由替换了。
            // 实在没有头像才退回那颗小心心，不然通知连个图标都没有
            b.setSmallIcon(av != null ? R.drawable.ic_noti_blank : R.drawable.ic_noti)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { b.setBadgeIconType(Notification.BADGE_ICON_NONE); } catch (Exception ignored) {}
            }
            // 对话通知：他的头像在最前面，后面才是话 —— 她要的就是这个排版。
            //
            // 头像右下角那个角标是从哪来的 —— 查了三轮，结论记在这儿：
            //
            // 它不是我们画的，是「会话通知」这个系统模板自带的。
            // Android 11+ 的会话布局左边固定两层：底下会话头像，
            // 右下角强制盖一个代表 app 的小徽标。大小、透明度、画什么，
            // 一个接口都没有 —— 原生上它画 smallIcon，MIUI 上直接画桌面图标，
            // 所以上一轮把 smallIcon 换成极小的点，在她手机上一点效果都没有。
            //
            // 而这个布局是 pushConversation() 登记的会话快捷方式 +
            // setShortcutId() 触发的。那就是源头。不登记快捷方式，
            // 系统就不按会话布局排，那一格徽标压根不会被画出来。
            //
            // MessagingStyle 留着 —— 它自己就会把发言人的头像画在每条消息左边，
            // 一条通知一句话、能往下堆，这些都不变。变的只是不再被排进
            // 「会话」那一栏，于是没有那个徽标。
            dropConversation(ctx);
            boolean asChat = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    // 会话头像要的是「自适应」那种图标。以前用的是 createWithBitmap，
                    // 有的系统不认，不认就当作没有头像 —— 于是整格退回软件图标，
                    // 看着就是「一个很大的应用图标压住了他的脸」。
                    Icon face = null;
                    if (av != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            face = Icon.createWithAdaptiveBitmap(adaptiveFace(av));
                        } else {
                            face = Icon.createWithBitmap(av);
                        }
                    }
                    Person.Builder pb = new Person.Builder().setKey("sir").setName(title).setImportant(true);
                    if (face != null) pb.setIcon(face);
                    Person sir = pb.build();
                    Person her = new Person.Builder().setKey("me").setName("我").build();

                    // 一条通知里只放这一句 —— 她要的是「一句就是一条」，
                    // 不是把几句攒在一条里
                    Notification.MessagingStyle st = new Notification.MessagingStyle(her);
                    st.addMessage(new Notification.MessagingStyle.Message(
                            text, System.currentTimeMillis(), sir));
                    b.setStyle(st);
                    b.addPerson(sir);
                    // MessagingStyle 正常会忽略 largeIcon。还是挂上 ——
                    // 有的系统在头像取不到的时候会拿它兜底，
                    // 拿他的脸兜底，总好过拿软件图标兜底
                    if (av != null) b.setLargeIcon(av);
                    // 这儿原来还有一句 setShortcutId(SHORTCUT_ID) —— 就是它把这条
                    // 通知推进「会话」那一栏，右下角那个 app 徽标才跟着来的。去掉了。
                    // MessagingStyle 排上了就算数，不再登记会话快捷方式。
                    asChat = true;
                } catch (Exception ignored) {}
            }

            // 排不上 MessagingStyle（安卓太老、或者上面那段抛了）才退回普通通知：
            // 至少把他的脸挂上，别只剩一个软件图标
            if (!asChat) {
                b.setStyle(new Notification.BigTextStyle().bigText(text));
                if (av != null) b.setLargeIcon(av);
            }

            notiSeq = (notiSeq + 1) % 20;
            nm.notify(NOTI_ID + notiSeq, b.build());
        } catch (Exception ignored) {}
    }

    /**
     * 把以前登记过的那个"先生"会话快捷方式撤掉。
     *
     * 原来这儿是 pushConversation()：登记一个长期会话快捷方式，
     * 让系统按会话通知排版（头像在最前）。代价是会话布局强制在头像
     * 右下角盖一个 app 徽标 —— 大小、透明度、画什么都没有接口，
     * 原生画 smallIcon，MIUI 直接画桌面图标。她要的就是这个徽标消失。
     *
     * 光是不再 setShortcutId 还不够：那条快捷方式一直存在系统里，
     * 系统仍然可能凭它把这条通知认成会话。所以主动删掉。
     * 删的只是通知用的那条动态快捷方式，桌面图标、长按菜单都不受影响。
     */
    private static void dropConversation(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;
        try {
            ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            java.util.List<String> one = java.util.Collections.singletonList(SHORTCUT_ID);
            try { sm.removeDynamicShortcuts(one); } catch (Exception ignored) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { sm.removeLongLivedShortcuts(one); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }
}
