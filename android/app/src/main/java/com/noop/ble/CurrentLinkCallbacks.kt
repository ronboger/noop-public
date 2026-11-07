package com.noop.ble

/** The same strap address can have two successive GATT handles with callbacks in flight. */
internal class CurrentLinkCallbacks<T : Any>(
    private val current: () -> T?,
    private val dispatch: (() -> Unit) -> Unit,
) {
    fun deliver(link: T, action: () -> Unit) {
        dispatch {
            if (link === current()) action()
        }
    }
}
