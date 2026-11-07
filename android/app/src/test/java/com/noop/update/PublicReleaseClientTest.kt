package com.noop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicReleaseClientTest {
    private val root = "https://github.com/ronboger/noop-public/releases/download/android-v1.2.3"
    private val manifestUrl = "$root/latest-android.json"
    private val apkUrl = "$root/NOOP-v1.2.3.apk"

    private fun release(apkDownloadUrl: String) = """
        {
          "tag_name":"android-v1.2.3",
          "assets":[
            {"name":"latest-android.json","browser_download_url":"$manifestUrl"},
            {"name":"NOOP-v1.2.3.apk","browser_download_url":"$apkDownloadUrl"}
          ]
        }
    """.trimIndent()

    private val manifest = """
        {"versionCode":123,"versionName":"1.2.3","apk":"NOOP-v1.2.3.apk","sha256":"${"a".repeat(64)}"}
    """.trimIndent()

    @Test fun resolvesOnlyTheApkNamedByTheManifest() {
        val client = PublicReleaseClient { url ->
            200 to if (url == manifestUrl) manifest else release(apkUrl)
        }
        val result = client.fetchManifest()
        assertTrue(result is FetchResult.Ok)
        assertEquals(apkUrl, (result as FetchResult.Ok).apkUrl)
    }

    @Test fun rejectsAnAssetOutsideThePublicRelease() {
        val client = PublicReleaseClient { url ->
            200 to if (url == manifestUrl) manifest else release("https://example.invalid/update.apk")
        }
        assertEquals(FetchResult.Error(UpdateError.NO_MANIFEST), client.fetchManifest())
    }
}
