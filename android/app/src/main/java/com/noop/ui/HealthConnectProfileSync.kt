package com.noop.ui

import android.content.Context
import com.noop.data.ImportSummary
import com.noop.data.WhoopRepository
import com.noop.ingest.HealthConnectImporter

/** Import Health Connect history and keep the body profile aligned with its latest scale readings. */
internal suspend fun importHealthConnectAndSyncProfile(
    context: Context,
    repo: WhoopRepository,
): ImportSummary {
    val profile = ProfileStore.from(context.applicationContext)
    return HealthConnectImporter.import(
        context = context,
        repo = repo,
        heightCm = profile.heightCm,
        onLatestProfile = { weightKg, heightCm ->
            weightKg?.let { profile.weightKg = it }
            heightCm?.let { profile.heightCm = it }
        },
    )
}
