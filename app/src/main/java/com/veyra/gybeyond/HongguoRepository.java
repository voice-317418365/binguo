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
    private final LruCache<String, Detail> details = new LruCache<>(32);
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
    JSONObject episode(String id, int number) throws Exception {
        Detail detail = details.get(id);
        if (detail == null) { detail = upstream.detail(id); details.put(id, detail); }
        if (number < 1 || number > detail.getEpisodes().size()) throw new IllegalArgumentException("无效集数");
        StreamInfo info = upstream.stream(id, detail.getEpisodes().get(number - 1), 1080);
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
        return result;
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
