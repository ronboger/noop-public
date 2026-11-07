package com.noop.update

import org.json.JSONObject
import java.io.File

/**
 * The `latest-android.json` asset attached to each signed Android release. It exists so the phone can compare [versionCode] — the value Android itself uses
 * to decide whether an APK is an upgrade — instead of parsing a tag and doing semver arithmetic.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val sha256: String,
    val notes: String,
) {
    companion object {
        /**
         * Parse a manifest, or return null if it is malformed, truncated, or missing any field the
         * update flow depends on. Never throws: a bad manifest must surface as "couldn't determine
         * the latest version", which is a DIFFERENT outcome from "up to date" (spec §6).
         */
        fun parse(json: String): UpdateManifest? = runCatching {
            val o = JSONObject(json)
            // opt* returns a 0/"" default for a missing key, so validate explicitly rather than
            // silently constructing a manifest that claims versionCode 0.
            if (!o.has("versionCode") || !o.has("apk") || !o.has("sha256")) return null
            val code = o.optInt("versionCode", -1)
            if (code <= 0) return null
            val apk = o.optString("apk").trim()
            val sha = o.optString("sha256").trim()
            if (apk.isEmpty() || sha.isEmpty()) return null
            UpdateManifest(
                versionCode = code,
                versionName = o.optString("versionName").trim(),
                apk = apk,
                sha256 = sha,
                notes = o.optString("notes").trim(),
            )
        }.getOrNull()

        /**
         * Reduce a remote-controlled APK path from the update manifest to a safe basename under the
         * local updates cache (audit F3 / CWE-22). `../` and absolute segments are stripped via
         * [File.getName]; anything that is not a plain `.apk` filename after that returns null so
         * the caller can fail soft (a bad manifest is a "couldn't update" UI state, never a crash).
         */
        fun safeApkFileName(apk: String): String? {
            val safeName = File(apk).name
            if (!safeName.endsWith(".apk", ignoreCase = true)) return null
            if (safeName.length == ".apk".length) return null  // bare ".apk" — no stem
            if (safeName.any { it < ' ' }) return null       // no control chars in a file name
            return safeName
        }
    }
}
