package dev.busung.s25uroot

/**
 * The run's log, as something that can be read on a phone.
 *
 * What this is for is three things the raw text cannot do on a 360 dp screen, and each of them is a thing our
 * own output actually does rather than a general tidying:
 *
 * - **The ported chain writes about 512 bytes at a time**, so a single patched library arrives as a run of
 *   `0 ...`, `512 ...`, `1024 ...` lines - twenty of them for one write, none of which says anything a person
 *   needs. Consecutive ticks collapse into one line carrying the count, and the count is kept because a write
 *   that *stops* ticking is how a stalled run looks.
 * - **Paths and release strings are longer than the card.** Soft wrapping breaks them wherever the pixel edge
 *   falls, and each wrapped piece begins at the left margin, so a continuation is indistinguishable from a new
 *   entry. These are wrapped at a column instead, with a continuation indent, and a single word too long for a
 *   line is elided in the *middle* so both the directory and the name stay readable.
 * - **Diagnostics carry a timestamp and colour** from whichever process produced them. Both are stripped here.
 *
 * What it deliberately does not do is edit the text itself. The payload's own words are the evidence a log is
 * worth keeping for, so nothing is rewritten, summarised or dropped short of the ticks - and the raw log is
 * what the copy button copies and what a record stores. This runs on the way to the screen and nowhere else.
 *
 * The technique is ported from the SM-S918B fork's `LogPresentation`, which is built around its own payload's
 * vocabulary. That vocabulary is not ours - our chain's markers are `* …` lines, `patched N bytes to PATH` and
 * the byte ticks above, and the feed's payload prints `key value` diagnostics and `[+]`/`[*]`/`[x]` levels - so
 * what is ported is the wrapping, the elision and the collapsing, applied to what our payloads emit.
 */
internal const val LOG_DISPLAY_COLUMNS = 42

/** The indent a wrapped line carries, so a continuation is not read as a new entry. */
private const val CONTINUATION = "  "

private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
private val TIME_PREFIX = Regex("^\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}]\\s*")
private val WHITESPACE = Regex("\\s+")

/**
 * One progress tick from the chain's writer: `0 ...`, `512 ...`, `1024 ...`.
 *
 * Anchored on the whole line after trimming, because the same shape appears inside real sentences -
 * `patch #1 (crash_dump64 ← splicehelper, 1312 bytes)` ends in a byte count too - and collapsing one of those
 * would delete evidence rather than noise.
 */
private val BYTE_TICK = Regex("^\\d+ \\.\\.\\.$")

/** The formatted log, or null when there is nothing to show. */
internal fun formatLogForDisplay(rawLog: String, maxColumns: Int = LOG_DISPLAY_COLUMNS): String? {
    require(maxColumns >= 20) { "a display column count below 20 is not a log, it is a column of letters" }
    if (rawLog.isBlank()) return null

    val lines = mutableListOf<String>()
    var ticks = 0

    fun flushTicks() {
        if (ticks == 0) return
        // Singular-aware: one tick is what a write that stopped after its first block looks like, and "1
        // progress ticks" reads as a bug in the reader rather than as a stalled run.
        lines += "$CONTINUATION· $ticks progress tick${if (ticks == 1) "" else "s"}"
        ticks = 0
    }

    for (raw in rawLog.lineSequence()) {
        val value = cleanLine(raw)
        if (BYTE_TICK.matches(value)) {
            ticks++
            continue
        }
        flushTicks()
        if (value.isEmpty()) continue
        lines += wrapDisplayLine(value, maxColumns)
    }
    flushTicks()

    return lines.joinToString("\n").ifBlank { null }
}

/** One line with the decoration removed, trimmed. A timestamp is a fact about the process, not about the run. */
private fun cleanLine(raw: String): String =
    TIME_PREFIX.replace(ANSI_ESCAPE.replace(raw, "").replace("\r", ""), "").trim()

/**
 * One entry, wrapped at [maxColumns].
 *
 * The first line starts at the margin and every line after it starts with [CONTINUATION], which is the whole
 * of what makes a wrapped entry look like one entry.
 */
private fun wrapDisplayLine(text: String, maxColumns: Int): List<String> {
    val words = text.split(WHITESPACE).filter(String::isNotBlank)
    if (words.isEmpty()) return emptyList()

    val lines = mutableListOf<String>()
    var current = ""
    for (rawWord in words) {
        // A continuation line has less room than the first, so the budget follows the indent.
        val prefix = if (lines.isEmpty()) "" else CONTINUATION
        val word = elideMiddle(rawWord, maxColumns - prefix.length)
        val candidate = if (current.isEmpty()) "$prefix$word" else "$current $word"
        if (candidate.length <= maxColumns) {
            current = candidate
            continue
        }
        if (current.isNotEmpty()) lines += current
        current = "$CONTINUATION${elideMiddle(rawWord, maxColumns - CONTINUATION.length)}"
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

/**
 * A word too long for its line, shortened in the middle: `…/apex/com.android.runtime/bin/cr…p64+0x0`.
 *
 * The middle rather than the end, because both ends of the things this happens to are worth reading - a path's
 * directory and its file name, a release string's version and the build it belongs to - and the end is what
 * `maxLines` would have cut off anyway.
 */
private fun elideMiddle(value: String, limit: Int): String {
    if (value.length <= limit) return value
    if (limit <= 3) return value.take(limit)
    val head = (limit - 1) / 2
    val tail = limit - head - 1
    return value.take(head) + "…" + value.takeLast(tail)
}
