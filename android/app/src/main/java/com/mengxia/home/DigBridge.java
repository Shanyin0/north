package com.mengxia.home;

import android.app.Activity;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 往硬盘底下挖。
 *
 * WebView 的 localStorage 和 IndexedDB 底下都是 LevelDB。LevelDB 是只往后写的：
 * 一个键被改掉之后，旧那份不会立刻抹掉，还躺在 .log / .ldb 里，等下一次合并才清。
 * 所以哪怕网页那一层已经读不到旧的聊天记录了，字节可能还在。
 *
 * 这个类做的事很笨：把 App 自己的数据目录整个走一遍，每个文件按字节扫，
 * 凡是连着一串认得出的中文/标点/英文，就抄下来。不解析结构，不管它原来是
 * 什么格式 —— 先把字捞上来再说。
 *
 * 只读自己的目录，不碰别的地方，也不联网。
 */
public class DigBridge {

    private final Activity act;
    /** 扫出来的东西，切成一块一块交给网页（一次交太大 WebView 会噎住） */
    private List<String> chunks = new ArrayList<String>();
    private int fileN = 0;
    private long byteN = 0;
    private int strN = 0;
    private int snapN = 0;      // 其中有几个是封存下来的旧现场

    private static final int CHUNK = 96 * 1024;     // 一块多大
    private static final long MAX_TOTAL = 24L * 1024 * 1024;  // 最多捞这么多字，别把内存撑爆
    private static final int MIN_RUN = 6;           // 少于这么多字的不要

    public DigBridge(Activity a) { this.act = a; }

    /** 数据目录里有哪些文件、各多大。就算什么都没捞到，这份也说明问题 */
    @JavascriptInterface
    public String list() {
        StringBuilder sb = new StringBuilder("[");
        try {
            List<File> fs = new ArrayList<File>();
            walk(new File(act.getApplicationInfo().dataDir), fs, 0);
            boolean first = true;
            for (File f : fs) {
                if (!first) sb.append(',');
                first = false;
                sb.append('{').append("\"p\":\"").append(esc(rel(f))).append("\",")
                  .append("\"n\":").append(f.length()).append('}');
            }
        } catch (Throwable t) { /* 走不动就算了 */ }
        return sb.append(']').toString();
    }

    /** 挖一遍。返回 {"files":n,"bytes":n,"strings":n,"chunks":n} */
    @JavascriptInterface
    public String scan() {
        chunks = new ArrayList<String>();
        fileN = 0; byteN = 0; strN = 0; snapN = 0;
        StringBuilder bag = new StringBuilder(CHUNK + 4096);
        Set<String> seen = new HashSet<String>();
        long total = 0;
        try {
            List<File> fs = new ArrayList<File>();
            walk(new File(act.getApplicationInfo().dataDir), fs, 0);
            for (File f : fs) {
                if (total > MAX_TOTAL) break;
                // 只挖存东西的地方，别去啃 so 库和图片缓存
                String p = rel(f).toLowerCase();
                // 得是存东西的地方。光看 .log 结尾太松 —— 缓存目录里也全是 .log
                boolean worth = p.contains("leveldb") || p.contains("indexeddb")
                        || p.contains("local storage") || p.contains("session storage")
                        || p.contains("databases") || p.contains(Snapshot.DIR)
                        // 她问「本地缓存里有没有」。这两块我原先写死跳过了 ——
                        // 机会不大（推送那份只存最近十二条、还每五分钟覆盖一次；
                        // 聊天是 POST 发出去的，回复不进网页缓存），但这是最后
                        // 两个没翻过的角落，翻一遍才好回答她「有」还是「没有」
                        || p.contains("shared_prefs") || p.contains("/cache")
                        || p.contains("code_cache") || p.contains("http cache");
                // .so、字体、图片这些别啃
                if (worth && (p.endsWith(".so") || p.endsWith(".png") || p.endsWith(".jpg")
                        || p.endsWith(".webp") || p.endsWith(".ttf") || p.endsWith(".woff2")
                        || p.endsWith(".dex") || p.endsWith(".odex") || p.endsWith(".vdex")
                        || p.endsWith(".art"))) worth = false;
                if (!worth) continue;
                fileN++;
                byteN += f.length();
                if (p.startsWith("/" + Snapshot.DIR) || p.startsWith(Snapshot.DIR)) snapN++;
                List<String> got = eat(f);
                for (String s : got) {
                    if (total > MAX_TOTAL) break;
                    if (seen.contains(s)) continue;
                    seen.add(s);
                    strN++;
                    total += s.length();
                    bag.append(s).append('\n');
                    if (bag.length() >= CHUNK) { chunks.add(bag.toString()); bag.setLength(0); }
                }
            }
        } catch (Throwable t) { /* 挖到哪儿算哪儿 */ }
        if (bag.length() > 0) chunks.add(bag.toString());
        return "{\"files\":" + fileN + ",\"bytes\":" + byteN
             + ",\"strings\":" + strN + ",\"snap\":" + snapN
             + ",\"chunks\":" + chunks.size() + "}";
    }

    @JavascriptInterface
    public int chunkCount() { return chunks.size(); }

    @JavascriptInterface
    public String chunk(int i) {
        if (i < 0 || i >= chunks.size()) return "";
        return chunks.get(i);
    }

    /** 挖完了就撒手，别一直占着内存 */
    @JavascriptInterface
    public void done() { chunks = new ArrayList<String>(); }

    // ===== 底下是干活的 =====

    private String rel(File f) {
        String d = act.getApplicationInfo().dataDir;
        String p = f.getAbsolutePath();
        return p.startsWith(d) ? p.substring(d.length()) : p;
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    private void walk(File dir, List<File> out, int depth) {
        if (dir == null || depth > 8 || out.size() > 4000) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) walk(k, out, depth + 1);
            else if (k.length() > 0) out.add(k);
        }
    }

    /** 一个文件，按字节扫两遍：UTF-16LE 一遍，UTF-8 一遍 */
    private List<String> eat(File f) {
        List<String> out = new ArrayList<String>();

        // .ldb 是压缩过的 —— 不先解开，按字节扫只能看到一团噪声。
        // 她那几天的聊天早就从 .log 落进 .ldb 了，前几次挖只捞到零星几句
        // 就是因为这一大半从来没被打开过
        try {
            String nm = f.getName().toLowerCase();
            if (nm.endsWith(".ldb") || nm.endsWith(".sst")) {
                for (byte[] b : Sst.blocks(f)) {
                    utf16(b, b.length, out);
                    utf8(b, b.length, out);
                    if (out.size() > 60000) return out;
                }
            }
        } catch (Throwable t) { /* 拆不动就按原样扫 */ }

        InputStream in = null;
        try {
            in = new FileInputStream(f);
            final int SIZE = 1 << 20;
            final int LAP = 4096;                 // 两块之间叠一段，别把一句话从中间切断
            byte[] buf = new byte[SIZE + LAP];
            int carry = 0;
            while (true) {
                int n = in.read(buf, carry, SIZE);
                if (n <= 0) break;
                int have = carry + n;
                utf16(buf, have, out);
                utf8(buf, have, out);
                if (out.size() > 60000) break;
                carry = Math.min(LAP, have);
                System.arraycopy(buf, have - carry, buf, 0, carry);
            }
        } catch (Throwable t) {
            /* 读不动就跳过这个文件 */
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
        }
        return out;
    }

    /** 认不认得出是「字」 */
    private static boolean texty(int c) {
        if (c == '\n' || c == '\t') return true;
        if (c >= 0x20 && c <= 0x7E) return true;             // 英文数字标点
        if (c >= 0x3000 && c <= 0x303F) return true;         // 。、「」
        if (c >= 0x4E00 && c <= 0x9FFF) return true;         // 汉字
        if (c >= 0xFF00 && c <= 0xFFEF) return true;         // 全角，
        if (c >= 0x2010 && c <= 0x203B) return true;         // — … ※
        if (c >= 0x3040 && c <= 0x30FF) return true;         // 假名（语言角用得上）
        return false;
    }

    /** 值不值得留：得有几个中日文，纯英文数字多半是代码和键名 */
    private static boolean worthKeeping(String s) {
        if (s.length() < MIN_RUN) return false;
        int cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF)) cjk++;
            if (cjk >= 4) return true;
        }
        return false;
    }

    /**
     * Chrome 的 localStorage 值就是这么存的：UTF-16 小端，一个字两字节。
     *
     * 要紧的是「从第几个字节开始数」：一条记录前面的头长几个字节是不定的，
     * 只从偶数位数的话，落在奇数位上的那些记录会被拆成乱码，整段丢掉。
     * 我一开始就是只数偶数位，一百五十句只捞回八十二句。
     * 所以两个起点各扫一遍 —— 重复的后面会去掉。
     */
    private void utf16(byte[] b, int len, List<String> out) {
        utf16From(b, len, 0, out);
        utf16From(b, len, 1, out);
    }

    private void utf16From(byte[] b, int len, int start, List<String> out) {
        StringBuilder run = new StringBuilder();
        for (int i = start; i + 1 < len; i += 2) {
            int c = (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
            if (texty(c)) {
                run.append((char) c);
                if (run.length() > 20000) keep(run, out);
            } else keep(run, out);
        }
        keep(run, out);
    }

    /** IndexedDB 里的字符串有不少是 UTF-8 存的 */
    private void utf8(byte[] b, int len, List<String> out) {
        StringBuilder run = new StringBuilder();
        int i = 0;
        while (i < len) {
            int c0 = b[i] & 0xFF;
            int c, adv;
            if (c0 < 0x80) { c = c0; adv = 1; }
            else if ((c0 & 0xE0) == 0xC0 && i + 1 < len && (b[i + 1] & 0xC0) == 0x80) {
                c = ((c0 & 0x1F) << 6) | (b[i + 1] & 0x3F); adv = 2;
            } else if ((c0 & 0xF0) == 0xE0 && i + 2 < len
                    && (b[i + 1] & 0xC0) == 0x80 && (b[i + 2] & 0xC0) == 0x80) {
                c = ((c0 & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F); adv = 3;
            } else { keep(run, out); i++; continue; }
            if (texty(c)) {
                run.append((char) c);
                if (run.length() > 20000) keep(run, out);
            } else keep(run, out);
            i += adv;
        }
        keep(run, out);
    }

    private void keep(StringBuilder run, List<String> out) {
        if (run.length() >= MIN_RUN) {
            String s = run.toString().trim();
            if (worthKeeping(s)) out.add(s);
        }
        run.setLength(0);
    }
}
