package com.noop.update

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateError { RATE_LIMITED, NOT_FOUND, NO_MANIFEST, NETWORK }

sealed interface FetchResult {
    data class Ok(val manifest: UpdateManifest, val apkUrl: String) : FetchResult
    data class Error(val kind: UpdateError) : FetchResult
}

class PublicReleaseClient(
    private val get: (String) -> Pair<Int, String> = ::readResponse,
) {
    fun fetchManifest(): FetchResult = try {
        fetchRelease()
    } catch (_: Exception) {
        FetchResult.Error(UpdateError.NETWORK)
    }

    private fun fetchRelease(): FetchResult {
        val (status, body) = get(RELEASE_URL)
        errorFor(status)?.let { return FetchResult.Error(it) }
        val release = runCatching { JSONObject(body) }.getOrNull()
            ?: return FetchResult.Error(UpdateError.NO_MANIFEST)
        val assets = release.optJSONArray("assets")
            ?: return FetchResult.Error(UpdateError.NO_MANIFEST)
        val tag = release.optString("tag_name")
        if (!safeComponent.matches(tag)) return FetchResult.Error(UpdateError.NO_MANIFEST)
        val assetsByName = (0 until assets.length()).mapNotNull(assets::optJSONObject)
            .associateBy { it.optString("name") }

        fun urlFor(name: String): String? {
            if (!safeComponent.matches(name)) return null
            val expected = "$DOWNLOAD_ROOT/$tag/$name"
            return expected.takeIf { assetsByName[name]?.optString("browser_download_url") == expected }
        }

        val manifestUrl = urlFor("latest-android.json")
            ?: return FetchResult.Error(UpdateError.NO_MANIFEST)
        val (manifestStatus, manifestBody) = get(manifestUrl)
        errorFor(manifestStatus)?.let { return FetchResult.Error(it) }
        val manifest = UpdateManifest.parse(manifestBody)
            ?: return FetchResult.Error(UpdateError.NO_MANIFEST)
        val apkUrl = urlFor(manifest.apk)
        if (apkUrl == null || !manifest.apk.endsWith(".apk") || !sha256.matches(manifest.sha256)) {
            return FetchResult.Error(UpdateError.NO_MANIFEST)
        }
        return FetchResult.Ok(manifest, apkUrl)
    }

    fun downloadAsset(
        url: String,
        dest: File,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Boolean = runCatching {
        require(url.startsWith("$DOWNLOAD_ROOT/"))
        val connection = connect(url)
        try {
            check(connection.responseCode == 200)
            val total = connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        onProgress(written, total)
                    }
                }
            }
            true
        } finally {
            connection.disconnect()
        }
    }.getOrElse {
        dest.delete()
        false
    }

    private fun errorFor(status: Int): UpdateError? = when (status) {
        in 200..299 -> null
        403, 429 -> UpdateError.RATE_LIMITED
        404 -> UpdateError.NOT_FOUND
        else -> UpdateError.NETWORK
    }

    companion object {
        private const val REPO = "ronboger/noop-public"
        private const val RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val DOWNLOAD_ROOT = "https://github.com/$REPO/releases/download"
        private val safeComponent = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
        private val sha256 = Regex("[0-9A-Fa-f]{64}")

        private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NOOP-Android")
        }

        private fun readResponse(url: String): Pair<Int, String> {
            val connection = connect(url)
            return try {
                val status = connection.responseCode
                val body = if (status in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else ""
                status to body
            } finally {
                connection.disconnect()
            }
        }
    }
}
