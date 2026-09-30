package dev.busung.s25uroot

/**
 * How long a run took to get from working on the kernel to a phone that answers.
 *
 * The interval is the run's own, not the app's: it starts when the exploit starts and stops when root is
 * confirmed, so a slow download or a manager install is not counted against it. That is the number anyone
 * actually compares - "it took two seconds" is about the exploit, not about the whole run.
 *
 * It is **measured rather than parsed**, which is the one place this departs from the reference implementation
 * it comes from: that one reads `Root achieved in N seconds` out of its own payload's log, and neither of our
 * payloads prints anything of the sort - the ported chain ends at `***SUCCESS***` and the feed's payload at its
 * own last line. A parser would find nothing on either path.
 *
 * The clock is injected so the arithmetic can be tested without a device, the way `BootSettle` and
 * `NetworkReach` take their tick as a seam.
 */
internal class RootStopwatch(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private var startedAtMillis: Long? = null

    /**
     * Starts the clock, once.
     *
     * A second call is deliberately nothing: the phase this is called from can be re-entered - a retry
     * re-runs the exploit stage - and a restart there would report the second attempt's time as if it were
     * the run's.
     */
    fun start() {
        if (startedAtMillis == null) startedAtMillis = nowMillis()
    }

    /** The elapsed time, or null when the clock was never started. Never negative, whatever the clock does. */
    fun elapsedMillis(): Long? = startedAtMillis?.let { started -> (nowMillis() - started).coerceAtLeast(0L) }
}

/**
 * A duration as a person reads one.
 *
 * Two units at most, and the smaller one only when it says something: `42 s`, `2 min 07 s`, `1 h 04 min`. The
 * seconds are padded to two digits below an hour so a list of durations lines up, and dropped above it because
 * by then they are noise - a run that takes over an hour is being read for a different reason.
 */
internal fun formatRootDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1_000L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3_600
    return when {
        hours > 0 -> "$hours h ${minutes.toString().padStart(2, '0')} min"
        minutes > 0 -> "$minutes min ${seconds.toString().padStart(2, '0')} s"
        else -> "$seconds s"
    }
}
