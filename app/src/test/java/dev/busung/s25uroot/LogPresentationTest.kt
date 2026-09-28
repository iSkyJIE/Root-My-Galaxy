package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run's log on the way to the screen: wrapped, elided, and with the chain's byte ticks counted.
 *
 * The samples here are lines this app has actually produced, because the three things this does are all
 * responses to what our payloads emit rather than to logs in general - the ticks come from the ported chain's
 * writer, the long tokens from vendor paths and release strings, and the levels from the app's own lines.
 *
 * What it must never do is lose the payload's own words: the reconstruction assertions below are the point of
 * the whole file.
 */
class LogPresentationTest {

    @Test
    fun `decoration from the producing process is removed`() {
        val ansi = "\u001B[32m[09:41:22.318] [+] payload: Galaxy S25 series\u001B[0m"
        assertEquals("[+] payload: Galaxy S25 series", formatLogForDisplay(ansi))
    }

    @Test
    fun `the chain's byte ticks are counted, and nothing else is`() {
        val log = listOf(
            "* patch #2 (libbinderdebug.so ← dirtyfrag.ko, 5664 bytes)",
            "0 ...",
            "512 ...",
            "1024 ...",
            "patched 5664 bytes to /vendor/lib64/libbinderdebug.so+0x0",
        ).joinToString("\n")

        val lines = formatLogForDisplay(log, maxColumns = 200)!!.lines()

        assertEquals("the patch line, one counted line, and the patched line: $lines", 3, lines.size)
        assertTrue("the ticks were not counted: $lines", lines[1].contains("3 progress ticks"))
        // A real line that merely *ends* in a byte count is evidence, and the rule is anchored so that it
        // cannot be swallowed as a tick.
        assertTrue("a diagnostic ending in a byte count was collapsed: $lines", lines[0].contains("5664 bytes)"))
        assertTrue("the write's own account was lost: $lines", lines[2].startsWith("patched 5664 bytes"))
    }

    @Test
    fun `a single tick is counted, in the singular`() {
        // One tick is what a write that stalled after its first block looks like, so it is kept rather than
        // dropped - and "1 progress ticks" would read as a fault in the reader.
        assertEquals("  · 1 progress tick", formatLogForDisplay("0 ...", maxColumns = 200))
    }

    @Test
    fun `a wrapped entry is indented, and nothing is lost`() {
        val line = "* patch #1 (crash_dump64 ← splicehelper, 1312 bytes)"
        val lines = formatLogForDisplay(line)!!.lines()

        assertTrue("this line does not wrap at 42 columns any more", lines.size > 1)
        assertTrue("a line is wider than the column count: $lines", lines.all { it.length <= LOG_DISPLAY_COLUMNS })
        assertTrue("the first line starts indented, so an entry does not begin at the margin: $lines", !lines[0].startsWith("  "))
        assertTrue("a continuation is not indented, so it reads as a new entry: $lines", lines.drop(1).all { it.startsWith("  ") })

        // Every word is still there, in order: a continuation is indentation, not omission.
        assertEquals(line, lines.joinToString(" ") { it.trim() })
    }

    @Test
    fun `a word too long for a line keeps both of its ends`() {
        // A release string is one word and longer than the card, which is the case soft wrapping cannot help
        // with: it breaks wherever the pixel edge falls, and the half that is cut is gone.
        val release = "6.6.98-android15-8-pd6ff1cd-abogkiS938USQSCCZF9-4k"
        val shown = formatLogForDisplay(release)!!

        assertEquals(LOG_DISPLAY_COLUMNS, shown.length)
        assertTrue("the middle was not what was removed: $shown", shown.contains("…"))
        assertTrue("the version was lost: $shown", shown.startsWith("6.6.98"))
        assertTrue("the build was lost: $shown", shown.endsWith("-4k"))
    }

    @Test
    fun `a log with nothing in it is nothing, not a blank line`() {
        // The screens ask for null so they can show their own placeholder; an empty string draws an empty box.
        assertNull(formatLogForDisplay(""))
        assertNull(formatLogForDisplay("   \n\n\t\n"))
        assertNull("blank lines around a blank log", formatLogForDisplay("\n\n"))
    }

    @Test
    fun `blank lines inside a log are dropped`() {
        assertEquals("first\nsecond", formatLogForDisplay("first\n\n   \nsecond", maxColumns = 200))
    }

    @Test
    fun `a column count that is not a log is refused rather than obeyed`() {
        val refused = runCatching { formatLogForDisplay("x", maxColumns = 10) }.exceptionOrNull()
        assertTrue("10 columns elides every word to nothing: $refused", refused is IllegalArgumentException)
    }

    @Test
    fun `the screen gets the formatted log and the copy gets the raw one`() {
        val activity = source("InstallActivity.kt")

        assertTrue(
            "the run screen no longer formats its log, so long paths wrap at the pixel edge again",
            activity.contains("formatLogForDisplay(output)"),
        )
        // The copy button is a bug report's payload, and a shortened log is not evidence.
        assertTrue(
            "the copy button no longer takes the raw log, so it would copy the shortened one",
            activity.contains("copyLogToClipboard(context, output)"),
        )

        // And the record keeps the raw text, which is what makes the history a record rather than a rendering.
        assertTrue(
            "the stored log is no longer the payload's own output, so a record cannot be re-read later",
            source("InstallHistory.kt").contains("""put("log", entry.log)"""),
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
