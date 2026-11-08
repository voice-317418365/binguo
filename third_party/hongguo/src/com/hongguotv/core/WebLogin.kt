// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** One official website login session; its CSRF cookies survive send/verify/poll. */
class WebLogin(http: OkHttpClient) {
    private val cookies = mutableListOf<Cookie>()
    private val client =
        http
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    @Volatile private var activeCall: Call? = null

    fun cancel() {
        activeCall?.cancel()
    }

    private fun execute(request: Request): Response {
        val matching =
            cookies.filter { it.matches(request.url) && it.expiresAt > System.currentTimeMillis() }
        val scoped =
            request
                .newBuilder()
                .apply {
                    if (matching.isNotEmpty())
                        header("Cookie", matching.joinToString("; ") { "${it.name}=${it.value}" })
                }
                .build()
        val call = client.newCall(scoped)
        activeCall = call
        val response =
            try {
                call.execute()
            } finally {
                if (activeCall === call) activeCall = null
            }
        for (cookie in Cookie.parseAll(request.url, response.headers)) {
            cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) cookies += cookie
        }
        return response
    }

    private var token = ""

    data class Qr(val image: ByteArray, val expiresIn: Int)

    private fun call(
        path: String,
        data: Map<String, String>? = null,
        params: Map<String, String> = emptyMap(),
    ): JSONObject {
        val url = (BASE + path).toHttpUrl().newBuilder()
        (mapOf(
                "aid" to "513755",
                "passport_jssdk_version" to "3.4.10",
                "passport_jssdk_type" to "normal",
                "account_sdk_source" to "web",
                "is_from_ttaccountsdk" to "1",
                "language" to "zh",
            ) + params)
            .forEach { (k, v) -> url.addQueryParameter(k, v) }
        val request =
            Request.Builder()
                .url(url.build())
                .header("User-Agent", UA)
                .header("Referer", "$BASE/")
                .header("Origin", BASE)
                .header("Accept", "application/json, text/javascript")
        cookies
            .firstOrNull { it.name == "passport_csrf_token" && it.matches(url.build()) }
            ?.let { request.header("x-tt-passport-csrf-token", it.value) }
        if (data != null)
            request.post(FormBody.Builder().apply { data.forEach { (k, v) -> add(k, v) } }.build())
        return execute(request.build()).use { JSONObject(cloudResponse(it)) }
    }

    private fun checked(reply: JSONObject): JSONObject {
        val data = reply.optJSONObject("data") ?: throw IOException("官网登录返回格式异常")
        if (reply.optString("message") != "success") {
            val code = data.optInt("error_code", -1)
            val message = data.optString("description").ifBlank { "官网登录失败" }
            throw IOException("$message（错误码 $code）")
        }
        return data
    }

    fun qr(): Qr {
        val data = checked(call("/passport/web/get_qrcode/", params = mapOf("next" to "$BASE/")))
        token = data.getString("token")
        val encoded = data.getString("qrcode").substringAfter("base64,")
        val expiry = data.optLong("expire_time", 180)
        val seconds =
            if (expiry > 1_000_000_000)
                (expiry - System.currentTimeMillis() / 1000)
                    .coerceAtLeast(0)
                    .coerceAtMost(600)
                    .toInt()
            else expiry.coerceIn(0, 600).toInt()
        return Qr(decode64(encoded), seconds)
    }

    /** Returns false while awaiting the user's scan/confirmation. */
    fun poll(): Boolean {
        check(token.isNotBlank()) { "请先获取二维码" }
        val reply =
            call(
                "/passport/web/check_qrconnect/",
                params = mapOf("next" to "$BASE/", "token" to token),
            )
        val data = checked(reply)
        val status = data.optString("status")
        if (status in listOf("5", "expired")) throw IOException("二维码已过期，请重新获取")
        // 只要给了跳转地址就跟，不依赖具体的 status 取值：官方这个字段出现过
        // new / 2 / 3 / confirmed 等多种写法，按枚举判断容易在某一端卡住。
        val redirect = data.optString("redirect_url")
        if (redirect.isNotBlank()) finishRedirect(redirect)
        return hasSession()
    }

    /**
     * Complete only the official site's confirmation redirect, preserving domain-scoped cookies.
     */
    private fun finishRedirect(value: String) {
        var url = BASE.toHttpUrl().resolve(value) ?: throw IOException("登录确认地址无效")
        repeat(5) {
            if (url.scheme != "https" || url.host != "hongguoduanju.com")
                throw IOException("登录确认跳转到不支持的站点")
            val next =
                execute(
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", UA)
                            .header("Referer", "$BASE/")
                            .build()
                    )
                    .use { response ->
                        if (response.isRedirect)
                            response.header("Location")?.let { url.resolve(it) }
                        else {
                            if (!response.isSuccessful)
                                throw IOException("登录确认失败（HTTP ${response.code}）")
                            null
                        }
                    }
            if (next == null) return
            url = next
        }
        throw IOException("登录确认跳转次数过多")
    }

    private fun hasSession() =
        cookies.any {
            it.name in listOf("sessionid", "sessionid_ss") &&
                it.matches(BASE.toHttpUrl()) &&
                it.expiresAt > System.currentTimeMillis()
        }

    fun credentials(): AccountCredentials {
        check(hasSession()) { "官网尚未下发登录态，请先在手机确认登录" }
        // 这里不再调 /passport/account/info/v2/ 做二次校验：那个接口对 UA 与
        // 签名有额外要求，容易在"已经扫码成功"的情况下误判失败。真正的账号验证
        // 交给 CloudAccountClient.profile()（fqnovel 的 reading/user/info），
        // 那才是历史/收藏实际要用的登录态。
        val active =
            cookies.filter {
                it.matches(BASE.toHttpUrl()) && it.expiresAt > System.currentTimeMillis()
            }
        // 只带 passport 的登录凭据：其余域内 cookie（ttwid / msToken / csrf …）
        // 拼给 fqnovel 既用不上，也可能让风控认为是异常请求。
        val essential = active.filter { it.name == "sessionid" || it.name == "sessionid_ss" }
        val picked = if (essential.isEmpty()) active else essential
        return AccountCredentials(
            picked.joinToString("; ") { "${it.name}=${it.value}" }
        )
    }

    companion object {
        const val BASE = "https://hongguoduanju.com"
        private const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}
