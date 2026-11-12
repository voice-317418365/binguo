package com.veyra.gybeyond;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 的 MP4 拼接（容器级 remux：不解码、不重编码，只搬样本 + 重建时间表）。
 *
 * <p>为什么不用 libffconcat：实测（同一组红果分集，2026-11-12）native concat 产物的视频
 * stts 是 {@code (15,9),(1,57465)} 循环 —— 每个 16 帧的小组里 15 帧被压成 100µs、剩下 1 帧
 * 吃掉 640ms。容器总长、帧数都对，但呈现时间轴被彻底打乱：播放器每 0.64s 只真正出 1 帧，
 * 观感就是「非常卡」，衔接处像花屏。同一批文件用 ffmpeg CLI（{@code -f concat -safe 0 -c copy}）
 * 合并出的 stts 是干净的 {@code (2809,512)(1,589)(4,512)…}，说明源分集没问题，
 * 问题出在 native 的封包循环（对 dts 的「非单调修正」把时间轴压平了）。
 * libffconcat.so 是预编译产物（仓库内没有 NDK / ffmpeg 构建环境），改不了 C 层，
 * 因此在 Java 侧重做一遍 concat：直接读各分集的 stbl 样本表，按「分集顺序 + 累计偏移」
 * 重建 stts/ctts/stss/stsc/stsz/stco，样本字节原样搬运，B 帧的 ctts 完整保留。
 *
 * <p>只依赖 java.io，便于在桌面上用 javac 跑对照测试（见 {@code Mp4ConcatHarness}）。
 */
final class Mp4Concat {

    /**
     * 合并前置条件不满足时抛出。
     * {@link #fatal} = true 表示「换别的引擎只会更糟」（画幅/编码/轨道数不一致）：
     * 调用方应立即放弃合并，不要回退到 libffconcat（硬拼会比例失真 + 花屏）；
     * false 表示只是本解析器读不了（结构异常等），调用方可以退回老引擎再试。
     */
    static final class Unsupported extends IOException {
        final boolean fatal;

        Unsupported(String message) { this(message, false); }

        Unsupported(String message, boolean fatal) {
            super(message);
            this.fatal = fatal;
        }
    }

    private static final int MOVIE_TIMESCALE = 1000;

    // ---- 数据结构 ----

    /** 输入分集里的一个轨道（样本表已展开成逐样本数组）。 */
    static final class Track {
        String handler = "";      // vide / soun
        int timescale;            // mdhd timescale
        byte[] tkhd;              // 原样保留（只改 track_id / duration）
        byte[] mdhd;              // 原样保留（只改 timescale / duration）
        byte[] hdlr;
        byte[] mediaHeader;       // vmhd / smhd
        byte[] stsd;              // 编解码器描述（含 hvcC/avcC/esds）
        int[] size = new int[0];
        int[] delta = new int[0];
        int[] cts = new int[0];
        boolean[] sync = new boolean[0];   // 空 = 全部关键帧
        long[] offset = new long[0];
        long width, height;       // tkhd 的 16.16 显示尺寸

        int count() { return size.length; }

        boolean isSync(int i) { return sync.length == 0 || sync[i]; }
    }

    static final class Movie {
        File file;
        byte[] ftyp;
        Track video;
        Track audio;
    }

    /** 输出里的一段（= 一集在该轨道上的样本区）：绝对偏移 + 逐样本表。 */
    private static final class Seg {
        long offset;
        int[] size;
        int[] delta;
        int[] cts;          // 可为空 = 全部 0
        boolean[] sync;     // 可为空 = 全部关键帧
    }

    /** 输出里的一个轨道：模板 + 拼接后的样本表。 */
    private static final class OutTrack {
        byte[] tkhd, mdhd, hdlr, mediaHeader, stsd;
        int timescale;
        long width, height;
        final List<Seg> segs = new ArrayList<>();
        long totalTicks;
        int sampleCount;
        boolean anyCts;

        long movieDuration() {
            return timescale <= 0 ? 0 : totalTicks * MOVIE_TIMESCALE / timescale;
        }
    }

    // ---- 入口 ----

    /**
     * 把多个 MP4 按顺序拼成一个。输入须为非分片 MP4、单视频轨（可选单音轨）。
     *
     * @param debug 诊断信息（成功/失败都写，供「复制合并日志」查看）
     */
    static void concat(List<File> inputs, File output, StringBuilder debug) throws Exception {
        if (inputs == null || inputs.isEmpty()) throw new Unsupported("没有可合并的分集");
        List<Movie> movies = new ArrayList<>();
        for (File f : inputs) {
            if (f == null || !f.isFile() || f.length() == 0)
                throw new Unsupported("分集文件缺失或为空：" + (f == null ? "null" : f.getName()));
            Movie m = parse(f);
            debug.append("[Mp4Concat] ").append(f.getName()).append(" 视频 ")
                    .append(fmtSize(m.video)).append(' ').append(codecTag(m.video))
                    .append(" ").append(m.video.count()).append(" 帧 ")
                    .append(String.format(java.util.Locale.US, "%.1f", seconds(m.video))).append("s")
                    .append(m.audio == null ? " 无音轨" : " +音频").append('\n');
            movies.add(m);
        }

        Movie first = movies.get(0);
        for (int i = 1; i < movies.size(); i++) {
            Movie m = movies.get(i);
            if (m.video.width != first.video.width || m.video.height != first.video.height)
                throw new Unsupported("第 " + (i + 1) + " 集画幅与第 1 集不一致（"
                        + fmtSize(m.video) + " vs " + fmtSize(first.video)
                        + "），硬拼会导致比例失真／花屏，已停止合并", true);
            if (!codecTag(m.video).equals(codecTag(first.video)))
                throw new Unsupported("第 " + (i + 1) + " 集视频编码与第 1 集不一致（"
                        + codecTag(m.video) + " vs " + codecTag(first.video) + "），无法无损合并", true);
            if ((first.audio == null) != (m.audio == null))
                throw new Unsupported("第 " + (i + 1) + " 集" + (m.audio == null ? "缺少" : "多出")
                        + "音频轨，与第 1 集不一致，合并后会出现某段无声，已停止合并", true);
        }

        OutTrack video = newTrack(first.video);
        OutTrack audio = first.audio == null ? null : newTrack(first.audio);

        long payload = 0;
        for (Movie m : movies) {
            payload += payloadOf(m.video);
            if (m.audio != null) payload += payloadOf(m.audio);
        }

        try (CountingOutput out = new CountingOutput(new BufferedOutputStream(
                new FileOutputStream(output), 1 << 20))) {
            out.write(first.ftyp);
            int headerSize = payload + 8 <= 0xFFFFFFFFL ? 8 : 16;
            writeMdatHeader(out, payload + headerSize, headerSize);
            copyTrack(out, movies, true, video);
            if (audio != null) copyTrack(out, movies, false, audio);
            byte[] moov = buildMoov(video, audio);
            out.write(moov);
            out.flush();
            debug.append("[Mp4Concat] 合并完成 ").append(out.count() / 1024).append("KB，")
                    .append(video.sampleCount).append(" 视频帧，时长 ")
                    .append(String.format(java.util.Locale.US, "%.2f",
                            audio != null ? Math.max(seconds(video), seconds(audio)) : seconds(video)))
                    .append("s\n");
        }
    }

    private static double seconds(OutTrack t) {
        return t.timescale <= 0 ? 0 : (double) t.totalTicks / t.timescale;
    }

    private static double seconds(Track t) {
        long total = 0;
        for (int d : t.delta) total += d;
        return t.timescale <= 0 ? 0 : (double) total / t.timescale;
    }

    private static String fmtSize(Track t) {
        return (t.width >> 16) + "x" + (t.height >> 16);
    }

    private static String codecTag(Track t) {
        try {
            // stsd: size(4) type(4) verflags(4) entry_count(4) → 第一个 sample entry 的 type(4)
            return new String(t.stsd, 8 + 4 + 4 + 4, 4, StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return "?";
        }
    }

    private static long payloadOf(Track t) {
        long sum = 0;
        for (int s : t.size) sum += s;
        return sum;
    }

    // ---- 解析 ----

    private static Movie parse(File f) throws Exception {
        Movie m = new Movie();
        m.file = f;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            byte[] head = new byte[16];
            long p = 0;
            byte[] moov = null;
            while (p + 8 <= len) {
                raf.seek(p);
                raf.readFully(head, 0, 8);
                long size = u32(head, 0);
                String type = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
                int hdr = 8;
                if (size == 1) {
                    raf.readFully(head, 8, 8);
                    size = u64(head, 8);
                    hdr = 16;
                } else if (size == 0) {
                    size = len - p;
                }
                if (size < 8 || p + size > len) break;
                if ("ftyp".equals(type)) {
                    byte[] b = new byte[(int) size];
                    raf.seek(p);
                    raf.readFully(b);
                    m.ftyp = b;
                } else if ("moov".equals(type)) {
                    if (size > 64L * 1024 * 1024) throw new Unsupported("moov 过大，疑似文件损坏");
                    moov = new byte[(int) size];
                    raf.seek(p);
                    raf.readFully(moov);
                } else if ("moof".equals(type)) {
                    throw new Unsupported("分集是分片 MP4（moof），不支持无损合并");
                }
                p += size;
            }
            if (moov == null) throw new Unsupported("分集缺少 moov（文件不完整）");
            if (m.ftyp == null) m.ftyp = defaultFtyp();

            int moovBody = u32(moov, 0) == 1 ? 16 : 8;   // moov 自身的盒头
            for (Box trak : children(moov, moovBody, moov.length)) {
                if (!"trak".equals(trak.type)) continue;
                Track t = parseTrack(moov, trak);
                if (t == null) continue;
                if ("vide".equals(t.handler) && m.video == null) m.video = t;
                else if ("soun".equals(t.handler) && m.audio == null) m.audio = t;
            }
            if (m.video == null) throw new Unsupported("分集没有视频轨：" + f.getName());
        }
        return m;
    }

    private static Track parseTrack(byte[] moov, Box trak) throws Exception {
        Track t = new Track();
        Box mdia = null;
        for (Box b : children(moov, trak.dataStart(), trak.end())) {
            if ("tkhd".equals(b.type)) {
                t.tkhd = slice(moov, b);
                int end = b.end();
                t.width = u32(moov, end - 8);
                t.height = u32(moov, end - 4);
            } else if ("mdia".equals(b.type)) {
                mdia = b;
            }
        }
        if (mdia == null) return null;
        Box minf = null, stbl = null;
        for (Box b : children(moov, mdia.dataStart(), mdia.end())) {
            if ("mdhd".equals(b.type)) {
                t.mdhd = slice(moov, b);
                int version = moov[b.dataStart()] & 0xFF;
                t.timescale = (int) u32(moov, b.dataStart() + (version == 1 ? 20 : 12));   // dataStart 已含盒头 8
            } else if ("hdlr".equals(b.type)) {
                t.hdlr = slice(moov, b);
                int p = b.dataStart() + 8;
                if (p + 4 <= b.end()) t.handler = new String(moov, p, 4, StandardCharsets.ISO_8859_1);
            } else if ("minf".equals(b.type)) {
                minf = b;
            }
        }
        if (minf == null) return null;
        for (Box b : children(moov, minf.dataStart(), minf.end())) {
            if ("vmhd".equals(b.type) || "smhd".equals(b.type)) t.mediaHeader = slice(moov, b);
            else if ("stbl".equals(b.type)) stbl = b;
        }
        if (stbl == null) return null;

        Box stsd = null, stts = null, ctts = null, stss = null, stsc = null, stsz = null, stco = null;
        for (Box b : children(moov, stbl.dataStart(), stbl.end())) {
            switch (b.type) {
                case "stsd": stsd = b; break;
                case "stts": stts = b; break;
                case "ctts": ctts = b; break;
                case "stss": stss = b; break;
                case "stsc": stsc = b; break;
                case "stsz": stsz = b; break;
                case "stco": case "co64": stco = b; break;
                default: break;
            }
        }
        if (stsd == null || stts == null || stsc == null || stsz == null || stco == null) return null;
        t.stsd = slice(moov, stsd);

        // stsz
        int p = stsz.dataStart();
        long uniform = u32(moov, p + 4);
        int n = (int) u32(moov, p + 8);
        if (n < 0 || n > 20_000_000) throw new Unsupported("stsz 样本数异常");
        t.size = new int[n];
        if (uniform > 0) {
            for (int i = 0; i < n; i++) t.size[i] = (int) uniform;
        } else {
            int q = p + 12;
            for (int i = 0; i < n; i++) { t.size[i] = (int) u32(moov, q); q += 4; }
        }

        // stts
        p = stts.dataStart();
        int entries = (int) u32(moov, p + 4);
        t.delta = new int[n];
        int idx = 0, q = p + 8;
        for (int i = 0; i < entries && idx < n; i++) {
            int cnt = (int) u32(moov, q);
            int d = (int) u32(moov, q + 4);
            q += 8;
            for (int k = 0; k < cnt && idx < n; k++) t.delta[idx++] = d;
        }
        while (idx < n) t.delta[idx] = idx > 0 ? t.delta[idx - 1] : 0;

        // ctts（可选）
        if (ctts != null) {
            p = ctts.dataStart();
            entries = (int) u32(moov, p + 4);
            t.cts = new int[n];
            idx = 0;
            q = p + 8;
            for (int i = 0; i < entries && idx < n; i++) {
                int cnt = (int) u32(moov, q);
                int off = (int) u32(moov, q + 4);
                q += 8;
                for (int k = 0; k < cnt && idx < n; k++) t.cts[idx++] = off;
            }
        }

        // stss（可选）
        if (stss != null) {
            p = stss.dataStart();
            int cnt = (int) u32(moov, p + 4);
            t.sync = new boolean[n];
            q = p + 8;
            for (int i = 0; i < cnt; i++) {
                long sn = u32(moov, q);
                q += 4;
                if (sn >= 1 && sn <= n) t.sync[(int) (sn - 1)] = true;
            }
        }

        // stsc + stco → 逐样本偏移
        p = stsc.dataStart();
        int scEntries = (int) u32(moov, p + 4);
        int[] firstChunk = new int[scEntries];
        int[] perChunk = new int[scEntries];
        q = p + 8;
        for (int i = 0; i < scEntries; i++) {
            firstChunk[i] = (int) u32(moov, q);
            perChunk[i] = (int) u32(moov, q + 4);
            q += 12;
        }
        boolean co64 = "co64".equals(stco.type);
        p = stco.dataStart();
        int chunks = (int) u32(moov, p + 4);
        long[] chunkOffset = new long[chunks];
        q = p + 8;
        for (int i = 0; i < chunks; i++) {
            chunkOffset[i] = co64 ? u64(moov, q) : u32(moov, q);
            q += co64 ? 8 : 4;
        }
        t.offset = new long[n];
        int sample = 0, si = 0;
        for (int c = 0; c < chunks && sample < n; c++) {
            while (si + 1 < scEntries && firstChunk[si + 1] <= c + 1) si++;
            int spc = scEntries > 0 ? perChunk[si] : 0;
            long pos = chunkOffset[c];
            for (int k = 0; k < spc && sample < n; k++) {
                t.offset[sample] = pos;
                pos += t.size[sample];
                sample++;
            }
        }
        if (sample < n) throw new Unsupported("样本表不完整（" + sample + "/" + n + "）");
        if (t.timescale <= 0) throw new Unsupported("mdhd timescale 异常");
        return t;
    }

    // ---- 写出 ----

    private static OutTrack newTrack(Track src) {
        OutTrack t = new OutTrack();
        t.tkhd = src.tkhd.clone();
        t.mdhd = src.mdhd.clone();
        t.hdlr = src.hdlr;
        t.mediaHeader = src.mediaHeader;
        t.stsd = src.stsd;
        t.timescale = src.timescale;
        t.width = src.width;
        t.height = src.height;
        return t;
    }

    /** 按分集顺序搬样本字节，同时累积输出样本表。 */
    private static void copyTrack(CountingOutput out, List<Movie> movies, boolean video, OutTrack dst)
            throws IOException {
        byte[] buf = new byte[1 << 20];
        for (Movie m : movies) {
            Track t = video ? m.video : m.audio;
            if (t == null) continue;
            Seg seg = new Seg();
            seg.offset = out.count();
            seg.size = t.size;
            seg.delta = t.delta;
            seg.cts = t.cts;
            seg.sync = t.sync;
            dst.segs.add(seg);
            if (t.cts.length > 0) {
                for (int c : t.cts) if (c != 0) { dst.anyCts = true; break; }
            }
            for (int d : t.delta) dst.totalTicks += d;
            dst.sampleCount += t.count();

            try (RandomAccessFile raf = new RandomAccessFile(m.file, "r")) {
                int i = 0;
                while (i < t.count()) {
                    long pos = t.offset[i];
                    int runSamples = 1;
                    long runBytes = t.size[i];
                    while (i + runSamples < t.count()
                            && t.offset[i + runSamples] == pos + runBytes) {
                        runBytes += t.size[i + runSamples];
                        runSamples++;
                    }
                    raf.seek(pos);
                    long left = runBytes;
                    while (left > 0) {
                        int want = (int) Math.min(buf.length, left);
                        raf.readFully(buf, 0, want);
                        out.write(buf, 0, want);
                        left -= want;
                    }
                    i += runSamples;
                }
            }
        }
    }

    private static byte[] buildMoov(OutTrack video, OutTrack audio) {
        Buf moov = new Buf();
        moov.u32(0);                 // size 占位
        moov.ascii("moov");
        moov.raw(mvhd(Math.max(video.movieDuration(),
                audio == null ? 0 : audio.movieDuration())));

        Buf trak = new Buf();
        trak.raw(trak(video, 1));
        moov.raw(trak.bytes());
        if (audio != null) moov.raw(trak(audio, 2));

        byte[] out = moov.bytes();
        patchSize(out);
        return out;
    }

    private static byte[] mvhd(long duration) {
        Buf b = new Buf();
        b.raw(new byte[4]);           // version 0 + flags
        b.u32(0);                     // creation
        b.u32(0);                     // modification
        b.u32(MOVIE_TIMESCALE);
        b.u32(duration);
        b.u32(0x00010000);            // rate 1.0
        b.u16(0x0100);                // volume 1.0
        b.zeros(10);
        b.raw(MATRIX);
        b.zeros(24);
        b.u32(3);                     // next_track_ID
        return box("mvhd", b.bytes());
    }

    private static final byte[] MATRIX = {
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00
    };

    private static byte[] trak(OutTrack t, int trackId) {
        Buf b = new Buf();
        b.raw(patchTkhd(t.tkhd, trackId, t.movieDuration()));

        Buf mdia = new Buf();
        mdia.raw(patchMdhd(t.mdhd, t.timescale, t.totalTicks));
        mdia.raw(t.hdlr);

        Buf minf = new Buf();
        minf.raw(t.mediaHeader != null ? t.mediaHeader : defaultMediaHeader(t));
        minf.raw(dinf());
        minf.raw(stbl(t));
        mdia.raw(box("minf", minf.bytes()));

        b.raw(box("mdia", mdia.bytes()));
        return box("trak", b.bytes());
    }

    private static byte[] defaultMediaHeader(OutTrack t) {
        Buf b = new Buf();
        b.raw(new byte[4]);
        if ("soun".equals(handlerOf(t))) {
            b.u16(0);      // balance
            b.u16(0);      // reserved
            return box("smhd", b.bytes());
        }
        b.u16(0x0001);     // graphics mode: copy
        b.zeros(6);
        return box("vmhd", b.bytes());
    }

    /** 由 stsd 猜轨道类型（模板里 hdlr 一定在，这里只作兜底）。 */
    private static String handlerOf(OutTrack t) {
        try {
            String tag = new String(t.stsd, 20, 4, StandardCharsets.ISO_8859_1);
            if ("mp4a".equals(tag) || "ac-3".equals(tag) || "ec-3".equals(tag)) return "soun";
        } catch (Exception ignored) { }
        return "vide";
    }

    private static byte[] dinf() {
        Buf url = new Buf();
        url.raw(new byte[4]);
        url.u8(1);          // self-contained，不需要外部数据
        Buf dref = new Buf();
        dref.raw(new byte[4]);
        dref.u32(1);
        dref.raw(box("url ", url.bytes()));
        Buf dinf = new Buf();
        dinf.raw(box("dref", dref.bytes()));
        return box("dinf", dinf.bytes());
    }

    private static byte[] stbl(OutTrack t) {
        Buf b = new Buf();
        b.raw(t.stsd);
        b.raw(stts(t));
        if (t.anyCts) b.raw(ctts(t));
        byte[] stss = stss(t);
        if (stss != null) b.raw(stss);
        b.raw(stsc(t));
        b.raw(stsz(t));
        b.raw(stco(t));
        return box("stbl", b.bytes());
    }

    /** stts：按 delta 做 RLE。 */
    private static byte[] stts(OutTrack t) {
        Rle rle = new Rle();
        for (Seg seg : t.segs) for (int d : seg.delta) rle.add(d);
        rle.flush();
        Buf b = new Buf();
        b.raw(new byte[4]);
        b.u32(rle.counts.size());
        for (int i = 0; i < rle.counts.size(); i++) {
            b.u32(rle.counts.get(i));
            b.u32(rle.values.get(i));
        }
        return box("stts", b.bytes());
    }

    /** ctts：PTS = DTS + offset。有负偏移时用 version 1（有符号）。 */
    private static byte[] ctts(OutTrack t) {
        boolean negative = false;
        Rle rle = new Rle();
        for (Seg seg : t.segs) {
            for (int i = 0; i < seg.delta.length; i++) {
                int c = seg.cts.length == 0 ? 0 : seg.cts[i];
                if (c < 0) negative = true;
                rle.add(c);
            }
        }
        rle.flush();
        Buf b = new Buf();
        b.u8(negative ? 1 : 0);
        b.zeros(3);
        b.u32(rle.counts.size());
        for (int i = 0; i < rle.counts.size(); i++) {
            b.u32(rle.counts.get(i));
            b.u32(rle.values.get(i) & 0xFFFFFFFFL);
        }
        return box("ctts", b.bytes());
    }

    /** stss：全关键帧则不写该盒（规范默认全部同步）。 */
    private static byte[] stss(OutTrack t) {
        List<Integer> list = new ArrayList<>();
        int index = 0;
        boolean allSync = true;
        for (Seg seg : t.segs) {
            for (int i = 0; i < seg.delta.length; i++) {
                index++;
                boolean s = seg.sync.length == 0 || seg.sync[i];
                if (s) list.add(index);
                else allSync = false;
            }
        }
        if (allSync) return null;
        Buf b = new Buf();
        b.raw(new byte[4]);
        b.u32(list.size());
        for (int n : list) b.u32(n);
        return box("stss", b.bytes());
    }

    private static byte[] stsc(OutTrack t) {
        Buf b = new Buf();
        b.raw(new byte[4]);
        b.u32(t.segs.size());
        for (int i = 0; i < t.segs.size(); i++) {
            b.u32(i + 1);
            b.u32(t.segs.get(i).delta.length);
            b.u32(1);                    // sample description index（stsd 只有一条）
        }
        return box("stsc", b.bytes());
    }

    private static byte[] stsz(OutTrack t) {
        Buf b = new Buf();
        b.raw(new byte[4]);
        b.u32(0);                        // 非定长
        b.u32(t.sampleCount);
        for (Seg seg : t.segs) for (int s : seg.size) b.u32(s);
        return box("stsz", b.bytes());
    }

    private static byte[] stco(OutTrack t) {
        boolean wide = false;
        for (Seg seg : t.segs) if (seg.offset > 0xFFFFFFFFL) { wide = true; break; }
        Buf b = new Buf();
        b.raw(new byte[4]);
        b.u32(t.segs.size());
        for (Seg seg : t.segs) {
            if (wide) b.u64(seg.offset); else b.u32(seg.offset);
        }
        return box(wide ? "co64" : "stco", b.bytes());
    }

    // ---- 盒级 patch ----

    private static byte[] patchSize(byte[] box) {
        long size = box.length;
        box[0] = (byte) (size >> 24);
        box[1] = (byte) (size >> 16);
        box[2] = (byte) (size >> 8);
        box[3] = (byte) size;
        return box;
    }

    /** tkhd：盒头 8 + ver/flags 4，v0 依次 creation(4) modification(4) track_id(4)
     *  reserved(4) duration(4)；v1 的时间字段是 8 字节，故 track_id@28、duration@36。 */
    private static byte[] patchTkhd(byte[] src, int trackId, long movieDuration) {
        byte[] b = src.clone();
        int version = b[8] & 0xFF;
        if (version == 1) {
            putU32(b, 28, trackId);
            putU64(b, 36, movieDuration);
        } else {
            putU32(b, 20, trackId);
            putU32(b, 28, movieDuration);
        }
        b[11] = 0x03;   // flags: enabled + in movie
        return b;
    }

    /** mdhd：盒头 8 + ver/flags 4，v0 creation(4) modification(4) timescale(4) duration(4)；
     *  v1 时间字段 8 字节 → timescale@28、duration@32。 */
    private static byte[] patchMdhd(byte[] src, int timescale, long duration) {
        byte[] b = src.clone();
        int version = b[8] & 0xFF;
        if (version == 1) {
            putU32(b, 28, timescale);
            putU64(b, 32, duration);
        } else {
            putU32(b, 20, timescale);
            putU32(b, 24, (int) duration);
        }
        return b;
    }

    private static void putU32(byte[] d, int p, long v) {
        d[p] = (byte) (v >> 24);
        d[p + 1] = (byte) (v >> 16);
        d[p + 2] = (byte) (v >> 8);
        d[p + 3] = (byte) v;
    }

    private static void putU64(byte[] d, int p, long v) {
        putU32(d, p, v >>> 32);
        putU32(d, p + 4, v & 0xFFFFFFFFL);
    }

    // ---- 字节工具 ----

    static final class Box {
        final String type;
        final int start;
        final int headerSize;
        final int size;

        Box(String type, int start, int headerSize, int size) {
            this.type = type;
            this.start = start;
            this.headerSize = headerSize;
            this.size = size;
        }

        int dataStart() { return start + headerSize; }
        int end() { return start + size; }
    }

    private static List<Box> children(byte[] d, int from, int to) {
        List<Box> out = new ArrayList<>();
        int p = from;
        while (p + 8 <= to) {
            long size = u32(d, p);
            String type = new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
            int hdr = 8;
            if (size == 1) {
                if (p + 16 > to) break;
                size = u64(d, p + 8);
                hdr = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < 8 || p + size > to) break;
            out.add(new Box(type, p, hdr, (int) size));
            p += (int) size;
        }
        return out;
    }

    private static byte[] slice(byte[] d, Box b) {
        byte[] out = new byte[b.size];
        System.arraycopy(d, b.start, out, 0, b.size);
        return out;
    }

    private static byte[] defaultFtyp() {
        Buf b = new Buf();
        b.ascii("isom");
        b.u32(512);
        b.ascii("isom");
        b.ascii("iso2");
        b.ascii("hvc1");
        b.ascii("mp41");
        return box("ftyp", b.bytes());
    }

    static byte[] box(String type, byte[] payload) {
        Buf b = new Buf();
        b.u32(8 + payload.length);
        b.ascii(type);
        b.raw(payload);
        return b.bytes();
    }

    private static void writeMdatHeader(CountingOutput out, long size, int headerSize) throws IOException {
        Buf b = new Buf();
        if (headerSize == 8) {
            b.u32(size);
            b.ascii("mdat");
        } else {
            b.u32(1);
            b.ascii("mdat");
            b.u64(size);
        }
        out.write(b.bytes());
    }

    static long u32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    static long u64(byte[] d, int p) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (d[p + i] & 0xFF);
        return v;
    }

    /** 可增长字节缓冲（大端）。 */
    static final class Buf {
        private byte[] data = new byte[256];
        private int len;

        private void ensure(int extra) {
            if (len + extra <= data.length) return;
            int cap = data.length;
            while (cap < len + extra) cap <<= 1;
            byte[] n = new byte[cap];
            System.arraycopy(data, 0, n, 0, len);
            data = n;
        }

        void u8(int v) { ensure(1); data[len++] = (byte) v; }

        void u16(int v) { u8(v >> 8); u8(v); }

        void u32(long v) { u8((int) (v >> 24)); u8((int) (v >> 16)); u8((int) (v >> 8)); u8((int) v); }

        void u64(long v) { u32(v >>> 32); u32(v & 0xFFFFFFFFL); }

        void ascii(String s) { for (int i = 0; i < s.length(); i++) u8(s.charAt(i)); }

        void raw(byte[] b) { ensure(b.length); System.arraycopy(b, 0, data, len, b.length); len += b.length; }

        void zeros(int n) { ensure(n); len += n; }

        byte[] bytes() {
            byte[] out = new byte[len];
            System.arraycopy(data, 0, out, 0, len);
            return out;
        }
    }

    /** 游程编码累加器。 */
    static final class Rle {
        final List<Long> counts = new ArrayList<>();
        final List<Integer> values = new ArrayList<>();
        private int lastValue;
        private long lastCount;
        private boolean started;

        void add(int value) {
            if (started && value == lastValue) { lastCount++; return; }
            flush();
            started = true;
            lastValue = value;
            lastCount = 1;
        }

        void flush() {
            if (!started || lastCount <= 0) return;
            counts.add(lastCount);
            values.add(lastValue);
            lastCount = 0;
        }
    }

    /** 带写入计数的流。 */
    static final class CountingOutput implements Closeable {
        private final OutputStream out;
        private long count;

        CountingOutput(OutputStream out) { this.out = out; }

        long count() { return count; }

        void write(byte[] b) throws IOException { write(b, 0, b.length); }

        void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        void flush() throws IOException { out.flush(); }

        @Override public void close() throws IOException { out.close(); }
    }
}
