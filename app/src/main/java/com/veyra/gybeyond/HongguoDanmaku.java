package com.veyra.gybeyond;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 红果官方弹幕（只读）。
 *
 * 弹幕本质是带时间轴的评论：POST /novel/commentapi/comment/list/{vid}/v1/，
 * comment_type=20，按 30 秒窗口用 start_offset_time 推进。实测匿名即可读取，
 * 不需要账号 Cookie，也不需要发弹幕的写权限。
 *
 * 两条硬约束：
 *  1) 签名是对 rawQuery 字符串算的，最终 URL 必须逐字节一致 —— 用 HttpURLConnection
 *     直接发签名器拼好的 URL，别交给 OkHttp（它会把 %2B 规范化成 +，签名就废了）。
 *  2) vid / series_id / comment_id 都是 19 位大整数，全程 String，绝不转 long/double。
 *
 * 这里只做「取一个窗口 + 缓存 + 失败静默降级」，调度和渲染在 PlayerActivity / DanmakuView。
 * 任何异常都返回空列表，绝不把播放链路拖下水。
 */
final class HongguoDanmaku {
    private static final String TAG = "HongguoDanmaku";
    static final long WINDOW_MS = 30_000L;
    private static final long FAIL_COOLDOWN_MS = 30_000L;
    private static final int MAX_TEXT = 40;
    private static final List<Item> EMPTY = Collections.emptyList();

    static final class Item {
        final String id;
        final String text;
        /** 相对本集的绝对毫秒；不是相对窗口。 */
        final long offsetMs;
        final int digg;

        Item(String id, String text, long offsetMs, int digg) {
            this.id = id;
            this.text = text;
            this.offsetMs = offsetMs;
            this.digg = digg;
        }
    }

    /** 窗口缓存：key = vid + ":" + 窗口起点；只保留最近 16 个窗口。 */
    private static final LinkedHashMap<String, List<Item>> CACHE =
            new LinkedHashMap<String, List<Item>>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Item>> eldest) {
                    return size() > 16;
                }
            };
    private static final Map<String, Long> FAILURES = new HashMap<>();

    private HongguoDanmaku() { }

    /** 同步网络请求，必须在后台线程调用。startMs 应为 30000 的整数倍。 */
    static List<Item> fetch(String vid, String seriesId, long startMs, long durationMs) {
        if (vid == null || !vid.matches("\\d{10,}")) return EMPTY;
        long window = (startMs / WINDOW_MS) * WINDOW_MS;
        String key = vid + ":" + window;
        synchronized (CACHE) {
            if (CACHE.containsKey(key)) return CACHE.get(key);
            Long failedAt = FAILURES.get(key);
            if (failedAt != null && System.currentTimeMillis() - failedAt < FAIL_COOLDOWN_MS) return EMPTY;
        }
        try {
            List<Item> items = request(vid, seriesId, window, durationMs);
            synchronized (CACHE) {
                CACHE.put(key, items);
                FAILURES.remove(key);
            }
            return items;
        } catch (Exception error) {
            Log.w(TAG, "window " + window + " failed: " + error.getMessage());
            synchronized (CACHE) { FAILURES.put(key, System.currentTimeMillis()); }
            return EMPTY;
        }
    }

    /** 换集 / seek 时用不到，但开关关闭再打开想强制重取时可以清空。 */
    static void clear() {
        synchronized (CACHE) { CACHE.clear(); FAILURES.clear(); }
    }

    private static List<Item> request(String vid, String seriesId, long window, long durationMs)
            throws Exception {
        JSONObject business = new JSONObject()
                .put("book_id", seriesId == null ? "" : seriesId)
                .put("start_offset_time", window)
                .put("playlet_item_duration", durationMs > 0 ? durationMs : 120_000L)
                .put("need_danmaku_guide_type", new JSONArray().put(1).put(3).put(4).put(2));
        JSONObject body = new JSONObject()
                .put("comment_source", 601)
                .put("server_channel", 1000)
                .put("group_id", vid)
                .put("group_type", 30)
                .put("comment_type", 20)
                .put("sort", 1)
                .put("business_param", business)
                .put("count", 90)
                .put("cursor", "")
                .put("aid", 8662)
                .put("compliance_status", 0);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        String path = "/novel/commentapi/comment/list/" + vid + "/v1/";

        // 主机轮换：签名只覆盖 path + rawQuery + body，不含 host，换主机不用重签。
        // 另外服务端按设备/session 随机采样，偶发只回 5~7 条；命中稀疏分片时换设备再取一次，
        // 取条数多的那份。正常情况一轮就够，不会给接口造成持续压力。
        List<Item> best = EMPTY;
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) com.hongguotv.core.HongguoSigner.INSTANCE.rotateDanmakuDevice();
            com.hongguotv.core.HongguoSigner.SignedPost signed =
                    com.hongguotv.core.HongguoSigner.INSTANCE.signDanmakuRequest(path, payload);
            String[] targets = { signed.getUrl(), signed.getUrl().replace("sinfonlineb", "sinfonlinea") };
            List<Item> got = null;
            for (String target : targets) {
                try { got = post(target, signed, payload); break; }
                catch (Exception error) { last = error; }
            }
            if (got == null) continue;
            if (got.size() > best.size()) best = got;
            if (best.size() >= 12) break;
        }
        if (best.isEmpty() && last != null) throw last;
        return best;
    }

    private static List<Item> post(String url, com.hongguotv.core.HongguoSigner.SignedPost signed,
                                   byte[] payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", signed.getUserAgent());
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("comment-source", "601");
            connection.setRequestProperty("server-channel", "1000");
            connection.setRequestProperty("Sdk-Version", "2");
            connection.setRequestProperty("X-XS-From-Web", "0");
            for (Map.Entry<String, String> header : signed.getHeaders().entrySet()) {
                // 弹幕请求按官方 App 行为留空 stub；实测带签名 stub / 全不带都能通，
                // 但只留一个值，避免大小写不敏感的 header 被发两遍。
                if ("X-SS-STUB".equalsIgnoreCase(header.getKey())) continue;
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setRequestProperty("X-SS-STUB", "");
            connection.getOutputStream().write(payload);

            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            InputStream input = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding()))
                input = new GZIPInputStream(input);
            String text = readAll(input);
            if (text.isEmpty()) throw new IllegalStateException("空响应");
            JSONObject root = new JSONObject(text);
            if (root.optInt("code", -1) != 0)
                throw new IllegalStateException("接口返回 " + root.optInt("code", -1) + " " + root.optString("message"));
            return parse(root);
        } finally {
            connection.disconnect();
        }
    }

    private static List<Item> parse(JSONObject root) throws Exception {
        JSONObject data = root.optJSONObject("data");
        JSONArray rows = data == null ? null : data.optJSONArray("data_list");
        if (rows == null || rows.length() == 0) return EMPTY;
        List<Item> items = new ArrayList<>(rows.length());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            JSONObject comment = row == null ? null : row.optJSONObject("comment");
            if (comment == null) continue;
            String id = comment.optString("comment_id", "");
            if (id.isEmpty() || !seen.add(id)) continue;
            JSONObject common = comment.optJSONObject("common");
            JSONObject content = common == null ? null : common.optJSONObject("content");
            String text = content == null ? "" : content.optString("text", "");
            if (text == null) text = "";
            text = text.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
            if (text.isEmpty()) continue;
            if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT) + "…";
            JSONObject expand = comment.optJSONObject("expand");
            long offset = expand == null ? -1 : expand.optLong("offset_time", -1);
            if (offset < 0) continue;
            JSONObject stat = comment.optJSONObject("stat");
            int digg = stat == null ? 0 : stat.optInt("digg_count", 0);
            items.add(new Item(id, text, offset, digg));
        }
        Collections.sort(items, (a, b) -> Long.compare(a.offsetMs, b.offsetMs));
        return items;
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 2 * 1024 * 1024) throw new IllegalStateException("响应过大");
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
