package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
internal fun workoutElapsedSeconds(workout: AppViewModel.ActiveWorkout): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(System.currentTimeMillis(), lifecycle, workout.startMs, workout.pausedAtMs) {
        if (workout.pausedAtMs == null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1_000)
                }
            }
        }
    }
    return ActiveWorkoutClock.activeElapsedSeconds(
        workout.startMs, workout.pausedAtMs, workout.pausedDurationMs, now,
    )
}
