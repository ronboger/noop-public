package com.noop.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP response, reduced to what the update flow actually reads. */
data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String>)

/** Seam over the network so every status-code mapping below is testable without a socket. */
interface Http {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** Distinct failure modes, each mapping to one actionable sentence in the UI (spec §6). */
enum class UpdateError { NO_TOKEN, UNAUTHORIZED, RATE_LIMITED, FORBIDDEN, NOT_FOUND, NO_MANIFEST, NETWORK }

sealed interface FetchResult {
    data class Ok(val manifest: UpdateManifest, val apkAssetId: Long) : FetchResult
    data class Error(val kind: UpdateError) : FetchResult
}

/**
 * Reads the latest release from the PRIVATE repository using a fine-grained token.
 *
 * Two hops: the release JSON names its assets, then the `latest-android.json` asset is downloaded by
 * id. Binary assets on a private repo require `Accept: application/octet-stream`, which makes GitHub
 * answer 302 to a signed CDN URL — see [downloadAsset] for why that redirect is followed by hand
 * (credential hygiene, backend/client resilience, not "the CDN rejects Authorization").
 */
class PrivateReleaseClient(private val http: Http = RealHttp) {

    fun fetchManifest(token: String?): FetchResult {
        if (token.isNullOrBlank()) return FetchResult.Error(UpdateError.NO_TOKEN)
        return try {
            val release = http.get(RELEASE_URL, jsonHeaders(token))
            errorFor(release)?.let { return FetchResult.Error(it) }

            val assets = runCatching { JSONObject(release.body).getJSONArray("assets") }.getOrNull()
                ?: return FetchResult.Error(UpdateError.NO_MANIFEST)

            var manifestId = -1L
            var apkId = -1L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                when {
                    a.optString("name") == MANIFEST_ASSET -> manifestId = a.optLong("id", -1L)
                    a.optString("name").endsWith(".apk") -> apkId = a.optLong("id", -1L)
                }
            }
            if (manifestId <= 0L || apkId <= 0L) return FetchResult.Error(UpdateError.NO_MANIFEST)

            val body = http.get(assetUrl(manifestId), octetHeaders(token))
            errorFor(body)?.let { return FetchResult.Error(it) }

            val manifest = UpdateManifest.parse(body.body)
                ?: return FetchResult.Error(UpdateError.NO_MANIFEST)
            FetchResult.Ok(manifest, apkId)
        } catch (_: Exception) {
            FetchResult.Error(UpdateError.NETWORK)
        }
    }

    /** Non-2xx → the matching error, or null when the response is usable. */
    private fun errorFor(r: HttpResponse): UpdateError? = when {
        r.code in 200..299 -> null
        r.code == 401 -> UpdateError.UNAUTHORIZED
        // An exhausted quota and a scope problem are BOTH 403 but need opposite fixes: wait, versus
        // mint a new token. The remaining-count header is what separates them.
        r.code == 403 -> {
            val remaining = r.headers.entries
                .firstOrNull { it.key.equals("x-ratelimit-remaining", ignoreCase = true) }?.value
            if (remaining == "0") UpdateError.RATE_LIMITED else UpdateError.FORBIDDEN
        }
        r.code == 404 -> UpdateError.NOT_FOUND
        else -> UpdateError.NETWORK
    }

    /**
     * Stream the APK asset to [dest]. Returns true on success.
     *
     * The redirect is followed BY HAND. GitHub answers the asset request with a 302 to a signed
     * CDN URL (`release-assets.githubusercontent.com`) that authenticates via its own query-string
     * signature. The second hop is a fresh connection with no `Authorization` header, for four
     * reasons:
     *
     * 1. Do not hand a repo-scoped bearer token to a CDN host that does not need it.
     *    `release-assets.githubusercontent.com` ignores the header today; sending it anyway is
     *    needless credential exposure.
     * 2. Do not depend on that host staying tolerant. If GitHub moves back to a stricter backend,
     *    an auto-following client silently breaks and this one does not.
     * 3. Do not depend on any HTTP client redirect-header policy, which differs between the JDK
     *    and Android's OkHttp-backed `HttpURLConnection`.
     * 4. It also sidesteps `HttpURLConnection` refusing to auto-follow across an http → https
     *    protocol change.
     *
     * Measured 2026-08-05 against the real API (asset `486328936` on release
     * `android-v8.2.9-private` of private repo `ronboger/noop`):
     * `GET .../releases/assets/486328936` with `Accept: application/octet-stream`, redirects not
     * followed → HTTP 302, `Location` host `release-assets.githubusercontent.com`; following that
     * URL **with** an `Authorization` header → HTTP 206; following it **without** → HTTP 206.
     * Both succeed. An earlier version of this comment claimed the redirect target REJECTS the
     * header; that was true of the older S3-backed host
     * (`github-production-release-asset-*.s3.amazonaws.com`) and was measured false for the current
     * host. Correcting the claim silently would teach nothing, so the measurement is recorded here.
     */
    fun downloadAsset(
        token: String,
        assetId: Long,
        dest: File,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = runCatching {
        var conn = (URL(assetUrl(assetId)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        }
        try {
            if (conn.responseCode in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("no Location")
                conn.disconnect()
                conn = (URL(location).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    // Deliberately NO Authorization header here.
                }
            }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            var written = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            true
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        dest.delete()
        false
    }

    private fun jsonHeaders(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to API_VERSION,
        "User-Agent" to USER_AGENT,
    )

    private fun octetHeaders(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/octet-stream",
        "X-GitHub-Api-Version" to API_VERSION,
        "User-Agent" to USER_AGENT,
    )

    companion object {
        private const val REPO = "ronboger/noop"
        private const val API_VERSION = "2022-11-28"
        private const val USER_AGENT = "NOOP-Android"
        const val MANIFEST_ASSET = "latest-android.json"

        const val RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"

        fun assetUrl(assetId: Long) = "https://api.github.com/repos/$REPO/releases/assets/$assetId"

        /**
         * Headers for the second hop of a redirect: identical to the first, minus Authorization.
         *
         * The signed CDN URL carries its own query-string signature and does not need the
         * repo-scoped bearer token. Stripping Authorization avoids needless credential exposure to
         * the CDN host, survives a future backend that is stricter about dual credentials, and does
         * not depend on any client's redirect-header policy (see [downloadAsset] for the measured
         * 2026-08-05 result: the current host accepts the request both with and without the header).
         */
        fun redirectHeaders(original: Map<String, String>): Map<String, String> =
            original.filterKeys { !it.equals("Authorization", ignoreCase = true) }
    }

    /** The real transport. Small on purpose — everything worth testing lives above it. */
    object RealHttp : Http {
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = false
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            return try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: return readResponse(conn, code)
                    conn.disconnect()
                    val second = (URL(location).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12_000
                        readTimeout = 12_000
                        instanceFollowRedirects = false
                        redirectHeaders(headers).forEach { (k, v) -> setRequestProperty(k, v) }
                    }
                    return try {
                        readResponse(second, second.responseCode)
                    } finally {
                        second.disconnect()
                    }
                }
                readResponse(conn, code)
            } finally {
                conn.disconnect()
            }
        }

        private fun readResponse(conn: HttpURLConnection, code: Int): HttpResponse {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val flat = conn.headerFields
                .filterKeys { it != null }
                .mapValues { it.value.firstOrNull() ?: "" }
            return HttpResponse(code, body, flat)
        }
    }
}
