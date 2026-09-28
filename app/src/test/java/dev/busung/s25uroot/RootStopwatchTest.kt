package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one measurement a run leaves behind: how long it took to root the phone.
 *
 * It is measured rather than parsed, because neither of our payloads prints a duration - so the arithmetic is
 * the whole of it, and the arithmetic is what these hold. The clock is a parameter for that reason: a test that
 * slept would be testing the platform.
 */
class RootStopwatchTest {

    @Test
    fun `it measures from the start to the reading`() {
        var now = 1_000L
        val stopwatch = RootStopwatch { now }

        stopwatch.start()
        now += 2_400L
        assertEquals(2_400L, stopwatch.elapsedMillis())
    }

    @Test
    fun `reading it before it is started says nothing rather than zero`() {
        // Null and zero are different answers, and only one of them is true here: a run that never started the
        // clock has no measurement, and recording 0 would put "Rooted in 0 s" on a run that never rooted.
        assertNull(RootStopwatch { 1_000L }.elapsedMillis())
    }

    @Test
    fun `starting it twice does not restart it`() {
        // The phase it starts from can be re-entered - a retry re-runs the exploit stage - and a restart there
        // would report the last attempt's time as if it were the whole run's.
        var now = 5_000L
        val stopwatch = RootStopwatch { now }

        stopwatch.start()
        now += 3_000L
        stopwatch.start()
        now += 1_000L

        assertEquals(4_000L, stopwatch.elapsedMillis())
    }

    @Test
    fun `a clock that goes backwards does not make a negative duration`() {
        // `System.currentTimeMillis` is a wall clock and can step. A run that reports a negative duration would
        // be rendered as one, and "-3 s" in a history list is worse than the truth of zero.
        var now = 10_000L
        val stopwatch = RootStopwatch { now }

        stopwatch.start()
        now -= 4_000L
        assertEquals(0L, stopwatch.elapsedMillis())
    }

    @Test
    fun `a duration reads as two units at most`() {
        assertEquals("0 s", formatRootDuration(0L))
        assertEquals("42 s", formatRootDuration(42_000L))
        // Truncated rather than rounded up, so "1 s" is never a run that has not finished a second.
        assertEquals("59 s", formatRootDuration(59_999L))
        // Padded below the hour, so a column of durations lines up.
        assertEquals("1 min 00 s", formatRootDuration(60_000L))
        assertEquals("2 min 07 s", formatRootDuration(127_000L))
        assertEquals("59 min 59 s", formatRootDuration(3_599_000L))
        // And dropped above it, where seconds are noise.
        assertEquals("1 h 00 min", formatRootDuration(3_600_000L))
        assertEquals("1 h 05 min", formatRootDuration(3_930_000L))
        assertEquals("0 s", formatRootDuration(-1_000L))
    }

    @Test
    fun `both flows record it, from the two phases they share`() {
        val viewModel = source("InstallViewModel.kt")

        assertTrue(
            "nothing starts the clock, so no run has a duration",
            viewModel.contains("InstallPhase.Exploiting -> rootStopwatch.start()"),
        )
        // Both terminal phases: `RootOnly` is a run whose KernelSU load was switched off, and the root the
        // exploit won is still the root it took that long to win.
        assertTrue(
            "the clock is stopped for one flow's ending and not the other's",
            viewModel.contains("InstallPhase.Installed, InstallPhase.RootOnly -> rootStopwatch.elapsedMillis()"),
        )
        assertTrue(
            "the measurement is not written into the record",
            viewModel.contains("entry.copy(rootedInMillis = rooted)"),
        )

        val history = source("InstallHistory.kt")
        assertTrue("the record cannot hold a duration", history.contains("val rootedInMillis: Long? = null"))
        assertTrue("the record's codec does not write it", history.contains("""put("rootedInMillis""""))
        assertTrue("the record's codec does not read it", history.contains("""getLong("rootedInMillis")"""))
    }

    @Test
    fun `the history list shows the duration beside the verdict`() {
        val main = source("MainActivity.kt")
        val row = main.substringAfter("private fun HistoryEntryCard(").substringBefore("private fun HistoryDetail(")

        assertTrue(
            "the list row does not show how long a run took, so two runs can only be compared by opening them " +
                "one at a time",
            row.contains("R.string.history_rooted_in"),
        )
        assertTrue(
            "the row draws a duration unconditionally, which would put a number on a run that rooted nothing",
            row.contains("entry.rootedInMillis?.let"),
        )
        assertTrue(
            "the verdict is not the thing that yields when the row is tight, so a long duration would cut off " +
                "the verdict instead",
            row.contains("modifier = Modifier.weight(1f)"),
        )
        // And the detail card keeps its own line, where there is room to name what the number means.
        val detail = main.substringAfter("private fun HistoryResultCard(")
        assertTrue(
            "the run's detail card lost the measurement this is meant to mirror",
            detail.contains("R.string.history_rooted_in"),
        )
    }

    private fun source(name: String): String {
        val file = candidateRoots()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == name }.toList() }
            .firstOrNull()
        requireNotNull(file) { "$name was not found; the scan is looking at the wrong directory" }
        return file.readText()
    }

    private fun candidateRoots(): List<File> = listOf(
        File("src/main/java"),
        File("app/src/main/java"),
    ).filter(File::isDirectory)
}
