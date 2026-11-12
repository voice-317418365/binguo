package com.veyra.gybeyond;

import android.util.Base64;
import com.hongguotv.core.CatalogPage;
import com.hongguotv.core.ContentRepository;
import com.hongguotv.core.ContentType;
import com.hongguotv.core.Detail;
import com.hongguotv.core.RankPosition;
import com.hongguotv.core.Series;
import com.hongguotv.core.StreamInfo;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import android.util.LruCache;

final class HongguoRepository {
    private final ContentRepository upstream = new ContentRepository(new OkHttpClient(), System::currentTimeMillis);
    /*
     * 详情/播放地址缓存必须是「进程级」的：MainActivity 与 PlayerActivity 各会 new 一个
     * HongguoRepository，挂在实例上会让详情页预热好的地址在播放页失效、重新签名取流，
     * 拿到带新签名的 URL 后预热实例也跟着作废。
     */
    private static final LruCache<String, Detail> details = new LruCache<>(32);
    private final HongguoGenres genres = new HongguoGenres(upstream.getHttp());
    private final HongguoSearch search = new HongguoSearch(upstream.getHttp());

    JSONObject tags() throws Exception { return genres.tags(); }
    JSONObject tagListing(String slug, int page) throws Exception { return genres.listing(slug, page); }

    private static JSONObject card(Series item, String category) throws Exception {
        JSONArray tags = new JSONArray();
        for (String tag : item.getTags().split(" · ")) if (!tag.isEmpty()) tags.put(tag);
        return new JSONObject().put("id", item.getId()).put("title", item.getTitle())
                .put("cover", item.getCover()).put("description", item.getDescription()).put("score", "")
                .put("episodeLabel", item.getBadge()).put("category", category).put("tags", tags);
    }
    private static final java.util.List<String> BOARDS =
            java.util.Arrays.asList("hot-drama", "hot-real-drama", "hot-comic-drama", "hot-ai-drama");

    private static JSONObject listing(CatalogPage page, int number, String category) throws Exception {
        JSONArray items = new JSONArray();
        // 只有榜单页带 positions（名次 + 热度）；普通分类这里是 null
        java.util.Map<String, RankPosition> positions =
                page.getRanking() == null ? null : page.getRanking().getPositions();
        for (Series item : page.getItems()) {
            JSONObject card = card(item, category);
            RankPosition position = positions == null ? null : positions.get(item.getId());
            if (position != null) {
                if (position.getRank() != null) card.put("rank", position.getRank());
                if (!position.getHeat().isEmpty()) card.put("heat", position.getHeat());
                // 「最多收藏」按这个数排；文本保留站点原文给列表卡片显示
                if (position.getCollect() > 0) card.put("collect", position.getCollect());
                if (!position.getCollectText().isEmpty()) card.put("collectText", position.getCollectText());
            }
            items.put(card);
        }
        return new JSONObject().put("items", items).put("nextPage", page.getHasMore() ? number + 1 : JSONObject.NULL);
    }
    JSONObject category(String id, int page) throws Exception {
        if (BOARDS.contains(id)) return listing(upstream.ranking(id, page), page, id);
        if (!"short".equals(id) && !"comic".equals(id)) throw new IllegalArgumentException("无效分类");
        return listing(upstream.home(page, "comic".equals(id) ? ContentType.COMIC : ContentType.SHORT), page, id);
    }
    /**
     * 「全部类型」页的三个排序类别。数据全部取自红果官方总热播榜（前 3 页），
     * 卡片自带名次、热度、收藏数：
     *  hot     = 官方综合热度榜原顺序；collect = 按站点公布的收藏数降序；
     *  latest  = 按 series_id 倒序（字节雪花 ID 随时间递增，等价于上架时间从新到旧）。
     */
    JSONObject sortedBoard(String kind) throws Exception {
        if (!"latest".equals(kind) && !"hot".equals(kind) && !"collect".equals(kind))
            throw new IllegalArgumentException("无效榜单类别");
        JSONArray merged = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int page = 1; page <= 3; page++) {
            CatalogPage board = upstream.ranking("hot-drama", page);
            JSONArray rows = listing(board, page, "short").getJSONArray("items");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.getJSONObject(i);
                if (seen.add(item.optString("id"))) merged.put(item);
            }
            if (!board.getHasMore()) break;
        }
        return new JSONObject().put("items", sortBoardItems(merged, kind)).put("nextPage", JSONObject.NULL);
    }

    /** 排序逻辑单独抽出：hot 保持官方榜单顺序，其余两种按真实数值重排。 */
    static JSONArray sortBoardItems(JSONArray merged, String kind) throws Exception {
        java.util.List<JSONObject> items = new java.util.ArrayList<>();
        for (int i = 0; i < merged.length(); i++) items.add(merged.getJSONObject(i));
        if ("collect".equals(kind)) {
            items.sort((a, b) -> Double.compare(b.optDouble("collect", 0), a.optDouble("collect", 0)));
        } else if ("latest".equals(kind)) {
            items.sort((a, b) -> {
                String x = a.optString("id", ""), y = b.optString("id", "");
                if (x.length() != y.length()) return Integer.compare(y.length(), x.length());
                return y.compareTo(x);
            });
        } // hot 保持官方榜单顺序
        JSONArray out = new JSONArray();
        for (JSONObject item : items) out.put(item);
        return out;
    }

    JSONObject home() throws Exception {
        JSONObject shorts = category("short", 1), comics = category("comic", 1);
        JSONArray featured = new JSONArray(), items = shorts.getJSONArray("items");
        for (int i = 0; i < Math.min(5, items.length()); i++) featured.put(items.getJSONObject(i));
        return new JSONObject().put("featured", featured).put("items", items)
                .put("recommend", shorts).put("newest", comics);
    }
    JSONObject recommend(int page, boolean comics) throws Exception { return category(comics ? "comic" : "short", page); }
    JSONObject search(String keyword) throws Exception {
        return search.search(keyword);
    }
    JSONObject searchSequels(String keyword) throws Exception { return search.search(keyword, true); }
    JSONObject detail(String id) throws Exception {
        Detail detail = upstream.detail(id);
        details.put(id, detail);
        JSONObject result = card(detail.getSeries(), "");
        JSONArray episodes = new JSONArray();
        for (int i = 0; i < detail.getEpisodes().size(); i++)
            episodes.put(new JSONObject().put("number", i + 1).put("videoId", detail.getEpisodes().get(i)));
        return result.put("episodes", episodes).put("episodeLabel", "共 " + episodes.length() + " 集");
    }
    /*
     * 播放地址缓存：同一集反复点开不该每次都重新签名取流（一次 resolve = 拼设备参数 +
     * 签名 + 一次 HTTPS 往返）。红果地址带时效，只留 8 分钟，过期重新解析。
     *
     * 取流顺序对齐上游 0.28.15：本机签名直连优先，失败才落回 RelayPlayback。
     * 我方 RelayPlayback 第一步是后端 /api/web/hongguo/stream，而后端要 spawn ffmpeg
     * 把整集转封装完才 sendFile（一集几十秒，且只有同名临时文件存在时才快）；直连拿到
     * 的是 CENC 密文，RemoteVideo 边读边解，地址到手就能出画，首播不用等转封装。
     */
    private static final long EPISODE_TTL_MS = 8 * 60 * 1000L;
    private static final LruCache<String, JSONObject> episodes = new LruCache<>(32);
    private static final java.util.Map<String, Long> episodeAt = new java.util.concurrent.ConcurrentHashMap<>();

    JSONObject episode(String id, int number) throws Exception {
        final String cacheKey = id + "#" + number;
        JSONObject cached = episodes.get(cacheKey);
        Long at = episodeAt.get(cacheKey);
        if (cached != null && at != null && System.currentTimeMillis() - at < EPISODE_TTL_MS) return cached;
        Detail detail = details.get(id);
        if (detail == null) { detail = upstream.detail(id); details.put(id, detail); }
        if (number < 1 || number > detail.getEpisodes().size()) throw new IllegalArgumentException("无效集数");
        String vid = detail.getEpisodes().get(number - 1);
        StreamInfo info = resolveStream(id, vid);
        HongguoPlayback.register(info);
        JSONObject result = new JSONObject().put("id", id).put("episode", number)
                // vid 是这一集的视频 id，进度回传要用；顺手透出来省得播放器再查一次
                .put("vid", detail.getEpisodes().get(number - 1))
                .put("source", info.getUrl())
                .put("mimeType", "video/mp4").put("quality", info.getQuality())
                .put("title", detail.getSeries().getTitle()).put("cover", detail.getSeries().getCover());
        byte[] key = info.getKey();
        if (key != null && key.length == 16) {
            StringBuilder hex = new StringBuilder();
            for (byte b : key) hex.append(String.format("%02x", b));
            result.put("key", hex.toString());
        }
        episodes.put(cacheKey, result);
        episodeAt.put(cacheKey, System.currentTimeMillis());
        return result;
    }

    /*
     * 直连优先取流（对齐上游 0.28.15 的 resolveStream）。
     *
     * HongguoSigner 在本机完成 device_register + X-Argus/X-Ladon/X-Gorgon 签名，直接拿
     * fqnovel 的 CDN 直链（CENC 密文），地址到手就能出画，首播不用等后端转封装。
     *
     * 放到独立线程里做是为了给「空响应风控」设 6 秒上限：签名器内部本身有重试与换设备
     * 轮换，风控时可能拖到十几秒，不能让它卡住详情页/播放页的请求线程。
     * 连续两次失败就冷却 5 分钟、期间直接走后端，避免每一集都白等 6 秒。
     */
    private static final java.util.concurrent.ExecutorService RESOLVE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "hongguo-resolve");
                thread.setDaemon(true);
                return thread;
            });
    private static final java.util.concurrent.atomic.AtomicInteger DIRECT_MISSES =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long directHoldUntil = 0L;
    private static final long DIRECT_COOLDOWN_MS = 5 * 60 * 1000L;
    private static final long DIRECT_TIMEOUT_MS = 6000L;

    private StreamInfo resolveStream(String id, String vid) throws Exception {
        if (System.currentTimeMillis() >= directHoldUntil) {
            java.util.concurrent.Callable<StreamInfo> call =
                    () -> com.hongguotv.core.HongguoSigner.INSTANCE.resolve(vid);
            java.util.concurrent.Future<StreamInfo> task = RESOLVE_POOL.submit(call);
            try {
                StreamInfo direct = task.get(DIRECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (direct != null && direct.getUrl() != null && direct.getUrl().startsWith("http")) {
                    DIRECT_MISSES.set(0);
                    return direct;
                }
            } catch (Exception error) {
                task.cancel(true);
                // 第一次失败不立刻判死：偶发空响应重试一次还是很快的
                if (DIRECT_MISSES.incrementAndGet() >= 2) {
                    directHoldUntil = System.currentTimeMillis() + DIRECT_COOLDOWN_MS;
                    DIRECT_MISSES.set(0);
                }
            }
        }
        return upstream.stream(id, vid, 1080);
    }

    /*
     * 预热点播：用户从「看到详情页」到「点播放」之间通常有好几秒，这段时间在后台把
     * 播放地址解析好、RemoteVideo prepare 好、首片铺进内存，点下去时 open() 直接命中。
     */
    private static final java.util.concurrent.ExecutorService PREWARM_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "hongguo-prewarm");
                thread.setDaemon(true);
                return thread;
            });
    private static final java.util.Set<String> PREWARMING =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    void prewarm(String id, int number) {
        if (id == null || id.isEmpty() || number < 1) return;
        final String key = id + "#" + number;
        if (!PREWARMING.add(key)) return;
        try {
            PREWARM_POOL.execute(() -> {
                try {
                    JSONObject data = episode(id, number);
                    HongguoPlayback.warm(data.optString("source"));
                } catch (Exception ignored) {
                    // 预热失败无所谓：真点播时还会走一次完整流程
                } finally {
                    PREWARMING.remove(key);
                }
            });
        } catch (Throwable error) {
            PREWARMING.remove(key);
        }
    }

    String coverData(String url) throws Exception {
        // 云端历史的 thumb_url 有三种历史形态：http://、协议相对 //host/…、以及只给
        // 资源路径（tos-cn-i-…/…）。红果的 CDN 都支持 https，所以统一转过去；
        // 不转会被判成非法地址，表现就是"云端列表有剧名但海报全空"。
        String raw = url == null ? "" : url.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("无效封面地址");
        java.util.List<String> candidates = new java.util.ArrayList<>(3);
        // HEIC 一律先换成公开 CDN 的 JPEG：Android WebView 不支持 HEIC 解码，
        // 这类地址即使下载成功也显示不出来（云端历史的 thumb_url 就是 .heic）。
        String jpeg = heicToJpeg(raw);
        if (jpeg != null) {
            candidates.add("https://p6-novel.byteimg.com/" + jpeg);
            candidates.add("https://p3-novel.byteimg.com/" + jpeg);
        }
        if (raw.startsWith("//")) {
            candidates.add("https:" + raw);
        } else if (raw.startsWith("http://")) {
            candidates.add("https://" + raw.substring(7));
            candidates.add(raw);   // https 不通时退回原协议
        } else if (raw.startsWith("https://")) {
            candidates.add(raw);
        } else {
            candidates.add("https://p3-novel-sign.byteimg.com/" + (raw.startsWith("/") ? raw.substring(1) : raw));
        }
        Exception failure = null;
        for (String target : candidates) {
            try {
                return fetchCover(target);
            } catch (Exception error) {
                failure = error;
            }
        }
        throw failure == null ? new IllegalStateException("封面加载失败") : failure;
    }

    /**
     * 从 {@code .../novel-pic/{id}~tplv-xxx.heic?...} 里剥出 {@code novel-pic/{id}~tplv-shrink:640:0.image}。
     * 不是 HEIC 形态时返回 null。
     */
    private static String heicToJpeg(String url) {
        if (url.indexOf(".heic") < 0) return null;
        int at = url.indexOf("/novel-pic/");
        if (at < 0) return null;
        int start = at + 1;               // 从 novel-pic/ 开始，不要前导斜杠
        int end = url.indexOf('~', start);
        if (end < 0) end = url.indexOf('?', start);
        if (end < 0) end = url.length();
        String id = url.substring(start, end);
        if (id.isEmpty()) return null;
        return id + "~tplv-shrink:640:0.image";
    }

    private String fetchCover(String url) throws Exception {
        // 这里刻意不用 OkHttp：它的 HttpUrl 会把 query 里的 %2B 规范化成字面 "+"，
        // 而字节的图片签名把 "+" 当空格解析，签名就对不上，返回 403。
        // HttpURLConnection 直接按字符串发，%2B 原样保留，才能下到图。
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("封面加载失败（HTTP " + code + "）");
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                    if (buffer.size() > 5 * 1024 * 1024) throw new IllegalStateException("封面过大");
                }
            }
            String type = connection.getContentType();
            String mime = type == null ? "image/jpeg" : type.split(";")[0];
            if (!mime.startsWith("image/")) mime = "image/jpeg";
            return "data:" + mime + ";base64," + Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
        } finally {
            connection.disconnect();
        }
    }
}
