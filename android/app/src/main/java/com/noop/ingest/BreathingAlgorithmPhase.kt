package com.noop.ingest

import android.content.Context
import com.noop.analytics.Whoop4BreathingDisturbances
import com.noop.analytics.Whoop4DisturbanceResearch

/**
 * Registry of WHOOP 4 breathing-research algorithm phases that must wire into:
 *
 * 1. **Sleep UI** — compact [BreathingValidationSnapshotStore] for [com.noop.ui.BreathingValidationCard]
 * 2. **Google Drive** — [ValidationDriveUpload] artifact identity (algorithm version + bundle version
 *    + app version code + input fingerprint)
 *
 * When shipping a new algorithm version:
 * - Add a [BreathingAlgorithmPhase] entry here with the frozen `algorithmVersion` / `bundleVersion`
 * - Decide its [Role]: primary phases drive the Sleep card + Drive session gate; supplements are
 *   optional ZIP entries that must never block the primary path
 * - Hook export so a successful primary analysis always calls [BreathingValidationUiBridge.publish]
 *   **before** optional supplement ZIP work
 * - Stamp [algorithmVersion] into Drive filenames / [ValidationDriveUpload.artifactKey] so a phase
 *   change re-uploads the same night
 *
 * The primary phase remains v6 (respiratory burden). v7 is a fail-soft ZIP supplement (cross-device
 * correspondence) layered on the same night's v6 export.
 */
enum class BreathingAlgorithmPhase(
    val algorithmVersion: String,
    val bundleVersion: Int,
    val role: Role,
) {
    /** Sleep card + Drive identity. Detector: [Whoop4DisturbanceResearch]. */
    V6_PRIMARY(
        algorithmVersion = ValidationBundleV6.ALGORITHM_VERSION,
        bundleVersion = ValidationBundleV6.BUNDLE_VERSION,
        role = Role.UI_AND_DRIVE_PRIMARY,
    ),

    /**
     * Optional ZIP-only correspondence layer. Never the Sleep card source of truth.
     * Analyze + serialize failures must be swallowed so the v6 bundle and UI snapshot still ship.
     */
    V7_CORRESPONDENCE(
        algorithmVersion = ValidationBundleV7.ALGORITHM_VERSION,
        bundleVersion = ValidationBundleV7.BUNDLE_VERSION,
        role = Role.ZIP_SUPPLEMENT,
    ),

    /**
     * Apple-shaped breathing-disturbance labels derived from locked v6 events.
     * Sleep snapshot fields only; never replaces the v6 UI/Drive primary.
     */
    V8_BREATHING_DISTURBANCES(
        algorithmVersion = Whoop4BreathingDisturbances.ALGORITHM_VERSION,
        bundleVersion = Whoop4BreathingDisturbances.BUNDLE_VERSION,
        role = Role.ZIP_SUPPLEMENT,
    );

    enum class Role {
        /** Drives Sleep snapshot + Drive filename / session gate. */
        UI_AND_DRIVE_PRIMARY,

        /** Optional ZIP entries; failure must not block primary snapshot or Drive. */
        ZIP_SUPPLEMENT,
    }

    companion object {
        /** The phase the Sleep card and Drive gate currently publish. */
        val primary: BreathingAlgorithmPhase = V6_PRIMARY

        fun all(): List<BreathingAlgorithmPhase> = entries.toList()

        fun primaryOrNull(algorithmVersion: String): BreathingAlgorithmPhase? =
            entries.firstOrNull {
                it.role == Role.UI_AND_DRIVE_PRIMARY && it.algorithmVersion == algorithmVersion
            }

        /** Contract pin: exactly one UI/Drive primary is registered. */
        fun requireSinglePrimary(): BreathingAlgorithmPhase {
            val primaries = entries.filter { it.role == Role.UI_AND_DRIVE_PRIMARY }
            check(primaries.size == 1) {
                "BreathingAlgorithmPhase must declare exactly one UI_AND_DRIVE_PRIMARY; found $primaries"
            }
            return primaries.single()
        }
    }
}

/**
 * Single publish seam from a completed primary analysis → Sleep UI snapshot.
 *
 * Export paths (manual share, USB, Drive worker) must call this after the primary detector
 * succeeds and must not skip it when a ZIP supplement (v7) fails.
 */
object BreathingValidationUiBridge {
    fun publish(
        context: Context,
        analysis: Whoop4DisturbanceResearch.Result,
        sessionStartTs: Long,
        sessionEndTs: Long,
        appVersionCode: Int,
        phase: BreathingAlgorithmPhase = BreathingAlgorithmPhase.primary,
        accel: AccelBreathingDisturbanceExport.Output? = null,
    ) {
        check(phase.role == BreathingAlgorithmPhase.Role.UI_AND_DRIVE_PRIMARY) {
            "UI snapshot publish requires a UI_AND_DRIVE_PRIMARY phase; got $phase"
        }
        check(analysis.provenance.algorithmVersion == phase.algorithmVersion) {
            "Analysis algorithm ${analysis.provenance.algorithmVersion} does not match phase " +
                phase.algorithmVersion
        }
        BreathingValidationSnapshotStore.save(
            context,
            BreathingValidationSnapshot.fromAnalysis(
                analysis = analysis,
                sessionStartTs = sessionStartTs,
                sessionEndTs = sessionEndTs,
                bundleVersion = phase.bundleVersion,
                appVersionCode = appVersionCode,
                accel = accel,
            ),
        )
    }
}
