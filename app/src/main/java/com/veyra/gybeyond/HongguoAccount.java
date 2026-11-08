package com.veyra.gybeyond;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import com.hongguotv.core.AccountCredentials;
import com.hongguotv.core.CloudAccountClient;
import com.hongguotv.core.CloudFavorite;
import com.hongguotv.core.CloudProfile;
import com.hongguotv.core.CloudProgress;
import com.hongguotv.core.DeviceRegister;
import com.hongguotv.core.WebLogin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 红果账号（本质是字节 passport 账号）的登录与云端数据。
 *
 * <p>登录走官网扫码：{@code hongguoduanju.com} 的 passport 接口返回一张二维码，
 * 用户用抖音 App 扫它确认，我们轮询到 {@code status=3} 后跟一次授权跳转就能拿到
 * {@code sessionid}。手机端自己扫自己做不到，所以前端流程是"保存二维码到相册 →
 * 抖音扫一扫 → 相册选图"。
 *
 * <p>云端数据（历史/收藏）读的却是 {@code api5-normal-sinfonlinea.fqnovel.com}
 * ——红果和番茄共用字节的阅读账号体系，{@code book_types=2} 表示视频类。
 * 注意这里是 {@code sinfonlinea}（账号）而不是 {@code sinfonlineb}（播放），
 * aid 也不同（513755 vs 8662），混用只会得到"用户未登录"之类的误导性报错。
 *
 * <p>凭据（cookie）用 {@link SecureStore} 加密后落盘，不上传任何服务器。
 */
final class HongguoAccount {
    private static final String TAG = "HongguoAccount";
    private static final String KEY_COOKIE = "hongguo.cookie";
    private static final String KEY_DEVICE = "hongguo.device";
    private static final String KEY_NICKNAME = "hongguo.nickname";

    private static HongguoAccount instance;

    static synchronized HongguoAccount get(Context context) {
        if (instance == null) instance = new HongguoAccount(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final OkHttpClient http;
    private final CloudAccountClient cloud;

    /** 当前扫码会话；每次开始登录都新建一个，cookie 在其中累积。 */
    private WebLogin session;
    /** 已登录凭据；懒加载，登出时清空。 */
    private AccountCredentials credentials;

    private HongguoAccount(Context context) {
        this.context = context;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build();
        this.cloud = new CloudAccountClient(http);
    }

    // ---------------------------------------------------------------- 登录

    /**
     * 请求一张新的登录二维码。
     *
     * @return {@code {qrcode: base64(png), expiresIn: 秒}}
     */
    synchronized JSONObject startLogin() throws Exception {
        cancelLogin();
        WebLogin fresh = new WebLogin(http);
        WebLogin.Qr qr = fresh.qr();
        session = fresh;
        return new JSONObject()
                .put("qrcode", Base64.encodeToString(qr.getImage(), Base64.NO_WRAP))
                .put("expiresIn", qr.getExpiresIn());
    }

    /**
     * 轮询扫码结果。前端每 2 秒调一次。
     *
     * @return 未完成时 {@code {done:false}}；完成且拿到登录态时 {@code {done:true, nickname:"…"}}
     */
    synchronized JSONObject pollLogin() throws Exception {
        if (session == null) throw new IllegalStateException("请先获取二维码");
        if (!session.poll()) return new JSONObject().put("done", false);

        // 扫码确认后官方会下发 sessionid，此时补设备信息再验证一次账号资料
        AccountCredentials fresh = session.credentials();
        Device device = ensureDevice();
        AccountCredentials scoped = new AccountCredentials(
                fresh.getCookie(), device.id, device.installId, java.util.Collections.<String, String>emptyMap());

        CloudProfile profile = cloud.profile(scoped);
        SecureStore.write(context, KEY_COOKIE, scoped.getCookie());
        SecureStore.write(context, KEY_NICKNAME, profile.getNickname());
        credentials = scoped;
        session = null;
        return new JSONObject().put("done", true).put("nickname", profile.getNickname());
    }

    synchronized void cancelLogin() {
        if (session != null) {
            try {
                session.cancel();
            } catch (Exception ignored) {
                // 取消失败无所谓，下一次 startLogin 会换新会话
            }
            session = null;
        }
    }

    boolean isLoggedIn() {
        return storedCredentials() != null;
    }

    void logout() {
        cancelLogin();
        credentials = null;
        SecureStore.remove(context, KEY_COOKIE);
        SecureStore.remove(context, KEY_NICKNAME);
        // 设备号保留：同账号下次登录沿用同一台设备，少一次新设备标记
    }

    // ---------------------------------------------------------------- 云端数据

    /** 账号资料；未登录返回 null 而不是抛错，前端据此渲染"未登录"态。 */
    JSONObject profile() {
        AccountCredentials stored = storedCredentials();
        if (stored == null) return null;
        try {
            CloudProfile profile = cloud.profile(stored);
            SecureStore.write(context, KEY_NICKNAME, profile.getNickname());
            return new JSONObject()
                    .put("id", profile.getId())
                    .put("nickname", profile.getNickname());
        } catch (Exception error) {
            Log.w(TAG, "账号资料拉取失败", error);
            throw new IllegalStateException(readable(error), error);
        }
    }

    /** 观看历史（跨设备）。没有记录时返回空数组，不抛错。 */
    JSONArray history() {
        AccountCredentials stored = requireCredentials();
        try {
            JSONArray rows = new JSONArray();
            for (CloudProgress row : cloud.history(stored)) {
                JSONObject item = row.getSeries().toJson();
                item.put("episodeId", row.getEpisodeId());
                item.put("episodeIndex", row.getEpisodeIndex());
                item.put("position", row.getPosition());
                item.put("duration", row.getDuration());
                item.put("updatedAt", row.getUpdatedAt());
                item.put("episodeCount", row.getEpisodeCount());
                rows.put(item);
            }
            return rows;
        } catch (Exception error) {
            throw new IllegalStateException(readable(error), error);
        }
    }

    /** 云端收藏。 */
    JSONArray favorites() {
        AccountCredentials stored = requireCredentials();
        try {
            JSONArray rows = new JSONArray();
            for (CloudFavorite row : cloud.favorites(stored)) {
                JSONObject item = row.getSeries().toJson();
                item.put("collectedAt", row.getCollectedAt());
                rows.put(item);
            }
            return rows;
        } catch (Exception error) {
            throw new IllegalStateException(readable(error), error);
        }
    }

    /** 收藏/取消收藏某一部剧。 */
    void setFavorite(String seriesId, boolean add) {
        AccountCredentials stored = requireCredentials();
        try {
            cloud.setFavorite(stored, seriesId, add);
        } catch (Exception error) {
            throw new IllegalStateException(readable(error), error);
        }
    }

    /** 本地缓存的昵称，用于"我的"页头像旁边直接显示，不发请求。 */
    String cachedNickname() {
        return SecureStore.read(context, KEY_NICKNAME);
    }

    // ---------------------------------------------------------------- 二维码落盘

    /**
     * 把登录二维码存进系统相册。
     *
     * <p>为什么要存相册：手机端没法自己扫自己，而抖音扫一扫支持从相册选图，
     * 所以"存下来 → 抖音里选图"是一台手机唯一能走通的路。
     *
     * <p>Android 10+ 走 scoped storage，写自己的 Pictures 目录免权限；
     * Android 9 及以下仍要 WRITE_EXTERNAL_STORAGE，没有权限时抛 SecurityException，
     * 由界面层提示并申请。
     */
    static void saveQrToAlbum(Context context, String base64Png) throws Exception {
        byte[] png = Base64.decode(base64Png, Base64.NO_WRAP);
        if (png.length == 0) throw new IllegalStateException("二维码数据为空");
        String name = "binguo-login-" + System.currentTimeMillis() + ".png";
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Binguo");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("相册写入失败，请改用截屏");
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("相册写入失败，请改用截屏");
            out.write(png);
            out.flush();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        }
    }

    // ---------------------------------------------------------------- 内部

    private AccountCredentials requireCredentials() {
        AccountCredentials stored = storedCredentials();
        if (stored == null) throw new IllegalStateException("尚未登录红果账号");
        return stored;
    }

    /** 从加密存储还原凭据；cookie 不在就返回 null。 */
    private AccountCredentials storedCredentials() {
        if (credentials != null) return credentials;
        String cookie = SecureStore.read(context, KEY_COOKIE);
        if (cookie == null || cookie.isEmpty()) return null;
        Device device = ensureDevice();
        credentials = new AccountCredentials(
                cookie, device.id, device.installId, java.util.Collections.<String, String>emptyMap());
        return credentials;
    }

    private static final class Device {
        final String id;
        final String installId;

        Device(String id, String installId) {
            this.id = id;
            this.installId = installId;
        }
    }

    /**
     * 设备号只注册一次并长期复用：频繁换设备号会踩风控，
     * 而红果的接口对"同一台设备读自己的账号"是最宽松的。
     */
    private Device ensureDevice() {
        String raw = SecureStore.read(context, KEY_DEVICE);
        if (raw != null) {
            try {
                JSONObject saved = new JSONObject(raw);
                String id = saved.optString("deviceId");
                String install = saved.optString("installId");
                if (!id.isEmpty() && !install.isEmpty()) return new Device(id, install);
            } catch (Exception ignored) {
                // 存储内容坏掉就重新注册
            }
        }
        try {
            DeviceRegister.Registered registered = cloud.registerDevice();
            if (registered == null) throw new IllegalStateException("设备注册未返回结果");
            String id = registered.getDeviceId();
            String install = registered.getInstallId();
            SecureStore.write(context, KEY_DEVICE,
                    new JSONObject().put("deviceId", id).put("installId", install).toString());
            return new Device(id, install);
        } catch (Exception error) {
            Log.e(TAG, "设备注册失败", error);
            // 注册失败也返回空号：账号接口仍可能通过（只是更容易被风控）
            return new Device("", "");
        }
    }

    /**
     * 把底层异常翻译成用户能看懂的一句话。
     *
     * <p>后端错误码里 {@code 101112} 最常见——它既可能是"这个账号确实没有观看记录"，
     * 也可能是登录态失效，所以文案要覆盖两种情况，别让用户以为是程序坏了。
     */
    private static String readable(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) return "账号接口暂时不可用";
        if (message.contains("101112")) return "没有取到云端记录（可能是账号还没有观看历史，或登录态已失效）";
        if (message.contains("401") || message.contains("未登录")) return "登录态已失效，请重新扫码登录";
        return message;
    }
}
