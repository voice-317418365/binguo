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
    /*
     * 详情兜底专用的 HTTP 客户端。
     *
     * upstream.getHttp() 是 new OkHttpClient()，读超时只有 10 秒；而自建后端的
     * /api/web/hongguo/detail 要跑真 so 签名，上游一忙就要十几秒才回。
     * 超时后兜底返回 null，前端只能抛「分集数据异常」——看起来像站方数据不对，
     * 实际是本地等不及。这里单独放宽到 60 秒读 / 75 秒整通，别和网页请求混用。
     */
    private static final OkHttpClient BACKEND_HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(75, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    /** 网页详情 404 时的兜底接口（不走网页，直接用 App 协议拿分集）。 */
    private final HongguoAppApi appApi = new HongguoAppApi();

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
        Detail detail = loadDetail(id);
        JSONObject result = card(detail.getSeries(), "");
        JSONArray episodes = new JSONArray();
        for (int i = 0; i < detail.getEpisodes().size(); i++)
            episodes.put(new JSONObject().put("number", i + 1).put("videoId", detail.getEpisodes().get(i)));
        result.put("episodes", episodes);
        if (episodes.length() == 0) {
            /* 一部剧一集都没有：站方还没上线（video_detail 回 episode_cnt=0），
               或者片源全下线。带上 unavailable，前端据此显示说明而不是空选集。 */
            String label = detail.getSeries().getBadge();
            result.put("episodeLabel", label == null || label.isEmpty() ? "暂无剧集" : label)
                    .put("unavailable", true);
        } else {
            result.put("episodeLabel", "共 " + episodes.length() + " 集");
        }
        return result;
    }

    /**
     * 网页详情拿不到时（HTTP 404）改用 App 详情接口凑一份。
     *
     * 2026-11 起站方网页版只收录了一部分片源：App 搜索能搜到、能播，但
     * https://hongguoduanju.com/detail?series_id=… 直接 404，表现就是「搜到点不进详情页」。
     * App 详情接口（video_detail）不走网页，返回同样的分集列表，用它兜底至少能进页面、能播。
     * 代价是封面/简介/相关推荐这些只有网页版才有的东西会缺，所以只作为兜底。
     */
    private Detail loadDetail(String id) throws Exception {
        Detail cached = details.get(id);
        if (cached != null) return cached;
        Detail detail;
        try {
            detail = upstream.detail(id);
        } catch (Exception error) {
            /* 2026-11 站方网页分集改成 v1_ 开头的不透明 id，本地解析会抛「分集数据异常」；
               App 接口又被风控成 200+0B。再把后端详情作为第三层兜底：后端有真 so 签名，
               能拿到数字 vid（实测同一部剧可正常返回）。 */
            android.util.Log.w("VeyraDetail", "网页详情失败 id=" + id + "：" + error);
            detail = detailFromApp(id);
            if (detail == null || detail.getEpisodes().isEmpty()) {
                Detail backend = detailFromBackend(id);
                if (backend != null && (!backend.getEpisodes().isEmpty() || !backend.getSeries().getTitle().isEmpty())) {
                    detail = backend;
                }
            }
            android.util.Log.w("VeyraDetail", "兜底结果 id=" + id + " -> "
                    + (detail == null ? "null" : detail.getEpisodes().size() + " 集 / " + detail.getSeries().getTitle()));
            if (detail == null) {
                /* 三层都拿不到（网页分集异常 + App 风控 + 后端不可达）：把原错误抛回去。
                   不返回「暂无剧集」占位——那会把网络故障伪装成「剧没上线」，更难排查。
                   真正的待播剧会让 App 返回标题但 0 集，走到上面的 detailFromApp 分支。 */
                throw error;
            }
        }
        details.put(id, detail);
        return detail;
    }

    /**
     * UGC 合集（个人创作者上传的多集视频）的 series id。
     *
     * 这类内容的分集取流要按 PUGC 分组走，和普通短剧不是一套语义：
     * 用短剧语义请求站方只会回 101000 或 200 + 空正文，表现就是「有 10 集，点哪集都不播」。
     * 详情走过 album 兜底就记下来，取流时告诉后端换语义。
     */
    private static final java.util.Set<String> ALBUM_SERIES =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** App 详情接口兜底；连元信息都拿不到返回 null，由调用方退回占位详情。 */
    private Detail detailFromApp(String id) {
        JSONObject app = appApi.fetch(id);
        /* video_detail 被风控成 200+0B 时 app 为 null；UGC 合集的分集其实在 album_detail 里，
           单独一个接口，不能因为 video_detail 空就直接放弃。 */
        java.util.List<String> episodes = app == null
                ? new java.util.ArrayList<String>(0) : vidsOf(app);
        String title = app == null ? "" : app.optString("series_title", "");
        String cover = app == null ? "" : app.optString("series_cover", "");
        String intro = app == null ? "" : app.optString("series_intro", "");
        String label = app == null ? "" : app.optString("episode_label", "");
        if (episodes.isEmpty()) {
            /* video_detail 是个空壳时（content_type 1003、episode_cnt 0、video_list 空），
               多半是 UGC 合集——个人创作者上传的多集视频。它的分集挂在 album_detail 下，
               album_id 就是搜索给的那个 series_id，每集的 series_id 就是取流用的 vid。
               以前只看 video_detail，于是这类合集被当成「一集都没有」。 */
            JSONObject album = appApi.album(id);
            if (album != null) {
                episodes = vidsOf(album);
                if (!episodes.isEmpty()) ALBUM_SERIES.add(id);
                if (title.isEmpty()) title = album.optString("title", "");
                if (cover.isEmpty()) cover = album.optString("cover", "");
                if (intro.isEmpty()) intro = album.optString("intro", "");
                if (label.isEmpty()) label = album.optString("label", "");
            }
        }
        if (episodes.isEmpty() && title.isEmpty()) return null;
        if (title.isEmpty()) title = "剧集 " + id;
        if (label.isEmpty()) label = episodes.isEmpty() ? "暂无剧集" : "共 " + episodes.size() + " 集";
        return new Detail(new Series(id, title, cover, intro, label, ""), episodes);
    }

    /**
     * 第三层兜底：问我们自己的后端要详情。
     *
     * 2026-11 站方网页把分集 id 换成 v1_ 开头的不透明串，本地解析会抛「分集数据异常」；
     * 红果 App 的 video_detail 又对第三方回 200+0B。后端有真 so 签名，仍能拿到数字 vid。
     * 标题/封面再尽量从网页补一次（网页分集不可用，但元信息还在）。
     */
    private Detail detailFromBackend(String id) {
        String base;
        try {
            base = ServerConfig.get(HongguoDirect.context());
        } catch (Throwable ignored) {
            return null;
        }
        if (base == null || base.trim().isEmpty()) {
            /* 没配服务器地址时这一层形同不存在。老版本默认地址是空的（通用版让用户自己填），
               所以这里必须留痕，否则排查时会误以为「后端也拿不到」而不是「根本没问过后端」。 */
            android.util.Log.w("VeyraDetail", "后端详情跳过 id=" + id + "：未配置服务器地址");
            return null;
        }
        try {
            String endpoint = base.trim().replaceAll("/+$", "") + "/api/web/hongguo/detail?id=" + id;
            Request request = new Request.Builder().url(endpoint)
                    .header("Accept", "application/json").get().build();
            JSONObject json;
            try (Response response = BACKEND_HTTP.newCall(request).execute()) {
                String text = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful() || text.trim().isEmpty()) {
                    android.util.Log.w("VeyraDetail", "后端详情失败 id=" + id
                            + "：HTTP " + response.code() + " / " + text.length() + "B");
                    return null;
                }
                json = new JSONObject(text);
            }
            JSONArray arr = json.optJSONArray("vids");
            java.util.List<String> episodes = new java.util.ArrayList<>();
            int skipped = 0;
            for (int i = 0; arr != null && i < arr.length(); i++) {
                String vid = arr.optString(i, "");
                /* 单个 vid 形态不对不该让整批作废：站方偶发会混一两个占位值进来，
                   以前这里 return null，等于「75 集里有一个不合法就整部剧打不开」。 */
                if (!vid.matches("[0-9]{1,30}")) { skipped++; continue; }
                episodes.add(vid);
            }
            if (skipped > 0) {
                android.util.Log.w("VeyraDetail", "后端详情 id=" + id + "：跳过 " + skipped + " 个非法 vid");
            }
            String title = "", cover = "", intro = "", label = "";
            JSONObject raw = json.optJSONObject("raw");
            JSONObject page = raw == null ? null : raw.optJSONObject("detail_page");
            JSONObject series = page == null ? null : page.optJSONObject("seriesDetail");
            if (series != null) {
                title = series.optString("series_name", "");
                cover = series.optString("series_cover", "");
                intro = series.optString("series_intro", "");
                label = series.optString("episode_right_text", "");
            }
            if (title.isEmpty() || cover.isEmpty()) {
                String html = fetchSite("https://hongguoduanju.com/detail?series_id=" + id);
                if (html != null) {
                    try {
                        JSONObject data = ContentRepository.Companion.extractRouter(html);
                        JSONObject ld = data.optJSONObject("loaderData");
                        JSONObject dp = ld == null ? null : ld.optJSONObject("detail_page");
                        JSONObject sd = dp == null ? null : dp.optJSONObject("seriesDetail");
                        if (sd != null) {
                            if (title.isEmpty()) title = sd.optString("series_name", "");
                            if (cover.isEmpty()) cover = sd.optString("series_cover", "");
                            if (intro.isEmpty()) intro = sd.optString("series_intro", "");
                            if (label.isEmpty()) label = sd.optString("episode_right_text", "");
                        }
                    } catch (Exception ignored) { /* 元信息拿不到就留空 */ }
                }
            }
            if (episodes.isEmpty() && title.isEmpty()) return null;
            if (title.isEmpty()) title = "剧集 " + id;
            if (label.isEmpty()) label = episodes.isEmpty() ? "暂无剧集" : "共 " + episodes.size() + " 集";
            return new Detail(new Series(id, title, cover, intro, label, ""), episodes);
        } catch (Throwable error) {
            /* 以前这里是空的 catch，网络/解析异常全被吞掉，日志里只剩「兜底结果 null」，
               分不清是后端没数据还是请求根本没成功。 */
            android.util.Log.w("VeyraDetail", "后端详情异常 id=" + id + "：" + error);
            return null;
        }
    }

    private String fetchSite(String url) {
        try {
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; "
                            + "25053RT47C; Build/BP2A.250605.031.A3)")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .get().build();
            try (Response response = upstream.getHttp().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                return response.body().string();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读接口里的 vids 数组；中间有空洞就当整份不可用（播放要按集序对齐）。 */
    private static java.util.List<String> vidsOf(JSONObject source) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONArray vids = source.optJSONArray("vids");
        for (int i = 0; vids != null && i < vids.length(); i++) {
            String vid = vids.optString(i, "");
            if (vid.isEmpty()) return new java.util.ArrayList<>(0);
            out.add(vid);
        }
        return out;
    }
    /*
     * 播放地址缓存：同一集反复点开不该每次都重新签名取流（一次 resolve = 拼设备参数 +
     * 签名 + 一次 HTTPS 往返）。红果地址带时效，只留 8 分钟，过期重新解析。
     *
     * 取流顺序对齐上游 0.28.60+：v5 匿名注册直连优先，失败才落回 RelayPlayback。
     * 直连拿到的是 CENC 密文，RemoteVideo 边读边解，地址到手就能出画，首播不用等后端
     * 转封装（后端 spawn ffmpeg 把整集转封装完才 sendFile，一集几十秒）。
     */
    private static final long EPISODE_TTL_MS = 8 * 60 * 1000L;
    private static final LruCache<String, JSONObject> episodes = new LruCache<>(32);
    private static final java.util.Map<String, Long> episodeAt = new java.util.concurrent.ConcurrentHashMap<>();

    JSONObject episode(String id, int number) throws Exception {
        return episode(id, number, false);
    }

    /** @param background 详情页预热：取址失败不占冷却、不重试，少给上游添无效请求。 */
    JSONObject episode(String id, int number, boolean background) throws Exception {
        final String cacheKey = id + "#" + number;
        JSONObject cached = episodes.get(cacheKey);
        Long at = episodeAt.get(cacheKey);
        if (cached != null && at != null && System.currentTimeMillis() - at < EPISODE_TTL_MS) return cached;
        Detail detail = loadDetail(id);
        if (detail.getEpisodes().isEmpty()) throw new IllegalArgumentException("这部剧暂时没有可播放的剧集");
        if (number < 1 || number > detail.getEpisodes().size()) throw new IllegalArgumentException("无效集数");
        String vid = detail.getEpisodes().get(number - 1);
        StreamInfo info = resolveStream(id, vid, background);
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
     * 取流对齐上游 0.28.60+：单路「匿名注册 + Android v5 签名」（HongguoDirect）。
     *
     * 2026-10-27 站方关掉了 551 Argus/Ladon（固定 HTTP 200 + 空正文），2026-11 又要求
     * v5 Medusa/Helios，旧的 HongguoSigner（551）已经拿不到地址。现在直连走 HongguoDirect，
     * 单路 9 秒硬预算，失败立刻报原因。
     *
     * 与上游唯一差别：v5 失败时仍退回我方后端 RelayPlayback 兜底（upstream.stream）。
     * 上游把这层删了（注明后端永久 502），但保留它不影响 v5 成功路径，只是多一道保险，
     * 避免 v5 在我方环境异常时红果彻底播不出来。
     */
    private static final java.util.concurrent.ExecutorService RESOLVE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "hongguo-resolve");
                thread.setDaemon(true);
                return thread;
            });
    private static final long FRESH_TIMEOUT_MS = 9000L;

    private StreamInfo resolveStream(String id, String vid, boolean background) throws Exception {
        final boolean album = ALBUM_SERIES.contains(id);
        java.util.concurrent.Future<StreamInfo> task =
                RESOLVE_POOL.submit(() -> HongguoDirect.fetch(vid, background, album));
        try {
            StreamInfo info = task.get(FRESH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (info != null && info.getUrl() != null && info.getUrl().startsWith("http")) {
                return info;
            }
            android.util.Log.w("Veyra", "红果 v5 未返回地址，退回后端兜底");
        } catch (java.util.concurrent.TimeoutException error) {
            task.cancel(true);
            android.util.Log.w("Veyra", "红果 v5 取址超时，退回后端兜底");
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            android.util.Log.w("Veyra", "红果 v5 取址失败，退回后端兜底: " + cause);
        } catch (InterruptedException error) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.io.IOException("取址被取消");
        }
        /* v5 这条路不通时的最后降级：保持我方原有后端链路，不让红果直接变成播不了。 */
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
                    JSONObject data = episode(id, number, true);
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
