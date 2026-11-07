package com.noop.update

/**
 * The pure decision at the centre of the updater: given the published manifest and the installed
 * versionCode, is there an update?
 *
 * [Undetermined] is deliberately NOT folded into [UpToDate]. A missing or unreadable manifest means
 * the check FAILED; reporting that as up-to-date is a lie that strands the user on an old build.
 */
sealed interface UpdateDecision {
    data class Available(val manifest: UpdateManifest) : UpdateDecision
    data class UpToDate(val versionName: String) : UpdateDecision
    object Undetermined : UpdateDecision

    companion object {
        fun of(manifest: UpdateManifest?, installedVersionCode: Int): UpdateDecision {
            if (manifest == null) return Undetermined
            // Strictly greater: versionCode is the same rule the package manager applies, so the
            // app's notion of "newer" and Android's cannot disagree.
            return if (manifest.versionCode > installedVersionCode) Available(manifest)
            else UpToDate(manifest.versionName)
        }
    }
}
