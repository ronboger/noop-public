package com.noop.ui

internal data class IntervalPlan(val work: Int, val rest: Int, val rounds: Int) {
    val duration = work * rounds + rest * (rounds - 1)
    private val cycle = work + rest

    fun round(elapsed: Int) = (elapsed / cycle + 1).coerceAtMost(rounds)
    fun isRest(elapsed: Int) = elapsed < duration && elapsed % cycle >= work
    fun remaining(elapsed: Int): Int {
        if (elapsed >= duration) return 0
        val boundary = if (isRest(elapsed)) cycle else work
        return boundary - elapsed % cycle
    }
}

internal enum class IntervalMode { Ready, Running, Paused, Finished }

internal data class IntervalTimer(val mode: IntervalMode = IntervalMode.Ready, val elapsed: Int = 0) {
    fun toggle() = when (mode) {
        IntervalMode.Ready, IntervalMode.Finished -> IntervalTimer(IntervalMode.Running)
        IntervalMode.Running -> copy(mode = IntervalMode.Paused)
        IntervalMode.Paused -> copy(mode = IntervalMode.Running)
    }

    fun tick(plan: IntervalPlan): IntervalTimer {
        if (mode != IntervalMode.Running) return this
        val next = elapsed + 1
        return IntervalTimer(if (next == plan.duration) IntervalMode.Finished else mode, next)
    }
}
