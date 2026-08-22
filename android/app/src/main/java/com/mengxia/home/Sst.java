package com.mengxia.home;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 LevelDB 的 .ldb 文件拆开、解压。
 *
 * 为什么要有这个：LevelDB 存东西分两步。刚写的进 .log —— 原样的字节，
 * 按字节扫就能读到。攒够了合并进 .ldb —— 那边默认是 Snappy 压缩过的，
 * 压缩之后中文在字节层面就是一团噪声，按字节扫什么都看不见。
 *
 * 她那几天的聊天早就落进 .ldb 了。我前几次挖只捞到零星几句，就是因为
 * 只读得到 .log，压缩过的那一大半从来没打开过。
 *
 * 这里做两件事：
 *   一、按 SSTable 的格式找到每一个数据块（尾部 footer -> 索引块 -> 各数据块）
 *   二、该解压的解压，把解出来的原始字节交回去让人扫
 * 格式认不出来就安静地什么都不返回，绝不影响别的路。
 */
public class Sst {

    /** SSTable 尾部的暗号，认它才敢往下拆 */
    private static final long MAGIC = 0xdb4775248b80fb57L;
    private static final int FOOTER = 48;
    private static final long MAX_FILE = 96L * 1024 * 1024;
    private static final int MAX_BLOCK = 32 * 1024 * 1024;

    /** 一个 .ldb 拆出来的所有数据块（已解压）。拆不动就返回空的 */
    public static List<byte[]> blocks(File f) {
        List<byte[]> out = new ArrayList<byte[]>();
        try {
            long len = f.length();
            if (len < FOOTER + 8 || len > MAX_FILE) return out;
            byte[] all = readAll(f, (int) len);
            if (all == null) return out;

            // 尾部 48 字节：metaindex handle、index handle、补零、8 字节暗号
            long magic = 0;
            for (int i = 0; i < 8; i++) magic |= ((long) (all[all.length - 8 + i] & 0xFF)) << (8 * i);
            if (magic != MAGIC) return out;

            int p = all.length - FOOTER;
            long[] cur = new long[]{ p };
            varint(all, cur);                    // metaindex offset
            varint(all, cur);                    // metaindex size
            long idxOff = varint(all, cur);
            long idxSize = varint(all, cur);
            if (idxOff < 0 || idxSize <= 0) return out;

            byte[] index = block(all, idxOff, idxSize);
            if (index == null) return out;

            // 索引块里每一条的「值」就是一个数据块的位置
            for (byte[] v : values(index)) {
                long[] c2 = new long[]{ 0 };
                long off = varint(v, c2);
                long size = varint(v, c2);
                if (off < 0 || size <= 0) continue;
                byte[] b = block(all, off, size);
                if (b != null && b.length > 0) out.add(b);
                if (out.size() > 4000) break;
            }
        } catch (Throwable t) { /* 拆不动就算了 */ }
        return out;
    }

    private static byte[] readAll(File f, int len) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[len];
            int got = 0;
            while (got < len) {
                int n = in.read(b, got, len - got);
                if (n <= 0) break;
                got += n;
            }
            return got == len ? b : null;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
        }
    }

    /** 取一个块：内容 size 字节，后面跟 1 字节压缩类型 + 4 字节校验 */
    private static byte[] block(byte[] all, long off, long size) {
        if (off < 0 || size <= 0 || off + size + 1 > all.length || size > MAX_BLOCK) return null;
        int o = (int) off, s = (int) size;
        int type = all[o + s] & 0xFF;
        if (type == 0) {                          // 没压缩
            byte[] b = new byte[s];
            System.arraycopy(all, o, b, 0, s);
            return b;
        }
        if (type == 1) return snappy(all, o, s);  // Snappy
        return null;                              // zstd 之类的，认不了
    }

    /** 一个 LevelDB 块里所有条目的「值」。索引块用得上 */
    private static List<byte[]> values(byte[] b) {
        List<byte[]> out = new ArrayList<byte[]>();
        try {
            if (b.length < 4) return out;
            int nRestart = u32(b, b.length - 4);
            if (nRestart < 0 || nRestart > 100000) return out;
            int end = b.length - 4 - nRestart * 4;
            if (end <= 0 || end > b.length) return out;
            long[] c = new long[]{ 0 };
            while (c[0] < end) {
                long shared = varint(b, c);
                long nonShared = varint(b, c);
                long vLen = varint(b, c);
                if (shared < 0 || nonShared < 0 || vLen < 0) break;
                long need = c[0] + nonShared + vLen;
                if (need > end) break;
                c[0] += nonShared;                // 键不关心，跳过
                byte[] v = new byte[(int) vLen];
                System.arraycopy(b, (int) c[0], v, 0, (int) vLen);
                c[0] += vLen;
                out.add(v);
                if (out.size() > 4000) break;
            }
        } catch (Throwable t) {}
        return out;
    }

    private static int u32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
             | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    /** LevelDB 到处用的变长整数 */
    private static long varint(byte[] b, long[] cur) {
        long v = 0;
        int shift = 0;
        int p = (int) cur[0];
        while (p < b.length && shift <= 63) {
            int x = b[p++] & 0xFF;
            v |= ((long) (x & 0x7F)) << shift;
            if ((x & 0x80) == 0) { cur[0] = p; return v; }
            shift += 7;
        }
        cur[0] = p;
        return -1;
    }

    /**
     * Snappy 解压。格式很小：开头一个变长的「原始长度」，
     * 然后一串标记 —— 要么照抄一段（literal），要么从前面已经写出来的地方
     * 往回拷一段（copy）。
     */
    public static byte[] snappy(byte[] in, int off, int len) {
        try {
            int p = off, shift = 0, end = off + len;
            long ulen = 0;
            while (p < end) {
                int x = in[p++] & 0xFF;
                ulen |= ((long) (x & 0x7F)) << shift;
                if ((x & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) return null;
            }
            if (ulen <= 0 || ulen > MAX_BLOCK) return null;
            byte[] out = new byte[(int) ulen];
            int o = 0;
            while (p < end && o < out.length) {
                int tag = in[p++] & 0xFF;
                int t = tag & 0x03;
                if (t == 0) {                                  // 照抄一段
                    int l = tag >> 2;
                    if (l < 60) l += 1;
                    else {
                        int n = l - 59;
                        if (p + n > end) return null;
                        int v = 0;
                        for (int i = 0; i < n; i++) v |= (in[p + i] & 0xFF) << (8 * i);
                        p += n;
                        l = v + 1;
                    }
                    if (l < 0 || p + l > end || o + l > out.length) return null;
                    System.arraycopy(in, p, out, o, l);
                    p += l; o += l;
                } else {                                       // 往回拷一段
                    int l, back;
                    if (t == 1) {
                        if (p + 1 > end) return null;
                        l = 4 + ((tag >> 2) & 0x07);
                        back = (((tag >> 5) & 0x07) << 8) | (in[p++] & 0xFF);
                    } else if (t == 2) {
                        if (p + 2 > end) return null;
                        l = (tag >> 2) + 1;
                        back = (in[p] & 0xFF) | ((in[p + 1] & 0xFF) << 8);
                        p += 2;
                    } else {
                        if (p + 4 > end) return null;
                        l = (tag >> 2) + 1;
                        back = (in[p] & 0xFF) | ((in[p + 1] & 0xFF) << 8)
                             | ((in[p + 2] & 0xFF) << 16) | ((in[p + 3] & 0xFF) << 24);
                        p += 4;
                    }
                    if (back <= 0 || back > o || o + l > out.length) return null;
                    for (int i = 0; i < l; i++) { out[o] = out[o - back]; o++; }
                }
            }
            if (o == out.length) return out;
            byte[] cut = new byte[o];
            System.arraycopy(out, 0, cut, 0, o);
            return cut;
        } catch (Throwable t) {
            return null;
        }
    }
}
