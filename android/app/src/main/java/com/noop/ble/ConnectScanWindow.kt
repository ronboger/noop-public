package com.noop.ble

internal data class ConnectScanWindow(
    val model: WhoopModel,
    private val deadlineMs: Long,
    val isFallback: Boolean = false,
) {
    fun remainingMs(nowMs: Long) = (deadlineMs - nowMs).coerceAtLeast(0L)

    fun fallback(nowMs: Long): ConnectScanWindow? =
        if (isFallback || nowMs >= deadlineMs) null
        else copy(model = model.fallbackScanModel, isFallback = true)
}
