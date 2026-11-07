package com.noop.ingest

import com.noop.analytics.Whoop4CrossDeviceCorrespondenceV7

/** Export-only v7 contract. It does not mutate or re-map the v6 WSRB product. */
object ValidationBundleV7 {
    const val ALGORITHM_VERSION = Whoop4CrossDeviceCorrespondenceV7.ALGORITHM_VERSION
    const val BUNDLE_VERSION = Whoop4CrossDeviceCorrespondenceV7.BUNDLE_VERSION
    const val INPUT_ALGORITHM_VERSION = Whoop4CrossDeviceCorrespondenceV7.INPUT_ALGORITHM_VERSION
    const val INPUT_BUNDLE_VERSION = Whoop4CrossDeviceCorrespondenceV7.INPUT_BUNDLE_VERSION
    const val BASELINE_WINDOW = "-60:-20"
    const val POST_RECOVERY_WINDOW = "recovery:+30"
    const val FIVE_MINUTE_BIN_SECONDS = Whoop4CrossDeviceCorrespondenceV7.FIVE_MINUTE_BIN_SECONDS
    const val MIN_BIN_COVERAGE = Whoop4CrossDeviceCorrespondenceV7.MIN_BIN_COVERAGE
    const val WSRB_UNWEIGHTED_FIELD = "wsrb_unweighted"
    const val WEIGHTED_BURDEN_FIELD = "cross_device_weighted_burden"

    fun requirePhaseIdentity(export: Whoop4CrossDeviceCorrespondenceV7.Export) {
        require(export.phaseIdentity.inputAlgorithmVersion == INPUT_ALGORITHM_VERSION)
        require(export.phaseIdentity.inputBundleVersion == INPUT_BUNDLE_VERSION)
        require(export.phaseIdentity.outputAlgorithmVersion == ALGORITHM_VERSION)
        require(export.phaseIdentity.outputBundleVersion == BUNDLE_VERSION)
    }
}
