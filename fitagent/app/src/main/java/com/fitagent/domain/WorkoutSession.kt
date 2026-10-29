package com.fitagent.domain

/** Work/rest interval programme for HIIT sessions. */
data class IntervalConfig(
    val workSeconds: Int = 40,
    val restSeconds: Int = 20,
    val rounds: Int = 8,
) {
    val totalSeconds: Int get() = rounds * workSeconds + (rounds - 1) * restSeconds

    companion object {
        val TABATA = IntervalConfig(20, 10, 8)
        val CLASSIC = IntervalConfig(40, 20, 10)
        val ENDURANCE = IntervalConfig(60, 30, 8)
    }
}

enum class PhaseKind { WORK, REST, DONE }

data class IntervalPhase(val kind: PhaseKind, val round: Int, val secondsLeft: Int, val phaseSeconds: Int)

/** Which interval phase is active after [elapsedSeconds] of active (unpaused) time. */
fun intervalPhaseAt(config: IntervalConfig, elapsedSeconds: Long): IntervalPhase {
    if (elapsedSeconds >= config.totalSeconds) return IntervalPhase(PhaseKind.DONE, config.rounds, 0, 0)
    val cycle = config.workSeconds + config.restSeconds
    val round = (elapsedSeconds / cycle).toInt() + 1
    val intoCycle = (elapsedSeconds % cycle).toInt()
    return if (intoCycle < config.workSeconds) {
        IntervalPhase(PhaseKind.WORK, round, config.workSeconds - intoCycle, config.workSeconds)
    } else {
        IntervalPhase(PhaseKind.REST, round, cycle - intoCycle, config.restSeconds)
    }
}

/**
 * A workout in progress, stored as wall-clock timestamps so it survives the app being backgrounded
 * or killed — elapsed time is always recomputed, never counted.
 */
data class ActiveSession(
    val type: WorkoutType,
    val startedAtMs: Long,
    val pausedAtMs: Long? = null,
    val pausedTotalMs: Long = 0,
    val interval: IntervalConfig? = null,
) {
    val isPaused: Boolean get() = pausedAtMs != null

    fun activeMs(nowMs: Long): Long = ((pausedAtMs ?: nowMs) - startedAtMs - pausedTotalMs).coerceAtLeast(0)

    fun pause(nowMs: Long): ActiveSession = if (isPaused) this else copy(pausedAtMs = nowMs)

    fun resume(nowMs: Long): ActiveSession =
        pausedAtMs?.let { copy(pausedAtMs = null, pausedTotalMs = pausedTotalMs + (nowMs - it)) } ?: this
}
