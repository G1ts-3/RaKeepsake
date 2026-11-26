package id.rakeepsake.net

import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class TextWithCookies(val body: String, val cookies: String, val finalUrl: String)

    /** GET halaman sebagai teks dengan User-Agent web biasa. */
    fun getText(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): String =
        client.newCall(buildGet(url, referer, headers)).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} untuk $url" }
            resp.body?.string() ?: error("Respons kosong dari $url")
        }

    /** GET halaman dan kumpulkan semua Set-Cookie di sepanjang rantai pengalihan (diperlukan CDN TikTok). */
    fun getTextWithCookies(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): TextWithCookies =
        client.newCall(buildGet(url, referer, headers)).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} untuk $url" }
            val body = resp.body?.string() ?: error("Respons kosong dari $url")
            val collected = LinkedHashMap<String, String>()
            val chain = mutableListOf<okhttp3.Response>()
            var cur: okhttp3.Response? = resp
            while (cur != null) {
                chain.add(cur)
                cur = cur.priorResponse
            }
            for (hop in chain.asReversed()) {
                val setCookies = hop.networkResponse?.headers("Set-Cookie")?.takeIf { it.isNotEmpty() }
                    ?: hop.headers("Set-Cookie")
                for (sc in setCookies) {
                    val kv = sc.substringBefore(';').trim()
                    val eq = kv.indexOf('=')
                    if (eq > 0) {
                        val name = kv.substring(0, eq).trim()
                        val value = kv.substring(eq + 1).trim()
                        if (name.isNotEmpty()) collected[name] = value
                    }
                }
            }
            val cookieStr = collected.entries.joinToString("; ") { "${it.key}=${it.value}" }
            TextWithCookies(body = body, cookies = cookieStr, finalUrl = resp.request.url.toString())
        }

    fun parseCookiePairs(cookieHeader: String?): Map<String, String> {
        if (cookieHeader.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (part in cookieHeader.split(";")) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                val k = part.substring(0, idx).trim()
                val v = part.substring(idx + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    fun mergeCookies(vararg cookieStrings: String?): String {
        val map = LinkedHashMap<String, String>()
        for (s in cookieStrings) {
            if (s.isNullOrBlank()) continue
            map.putAll(parseCookiePairs(s))
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String =
        client.newCall(
            okhttp3.Request.Builder()
                .url(url)
                .post(
                    okhttp3.RequestBody.create(
                        "application/json; charset=utf-8".toMediaTypeOrNull(),
                        json,
                    ),
                )
                .apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }
                .build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} untuk $url" }
            resp.body?.string() ?: error("Respons kosong dari $url")
        }

    fun buildGet(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): okhttp3.Request =
        okhttp3.Request.Builder()
            .url(url)
            .apply {
                header("User-Agent", UA_WEB)
                referer?.let { header("Referer", it) }
                headers.forEach { (k, v) -> header(k, v) }
            }
            .build()

    const val UA_WEB = "Mozilla/5.0 (Linux; Android 13) dyrecord/1.0"

    const val UA_MOBILE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
}
