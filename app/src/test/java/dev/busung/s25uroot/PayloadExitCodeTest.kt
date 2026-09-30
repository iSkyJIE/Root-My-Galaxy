package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exit codes a failed payload can leave, and what the app says about them.
 *
 * `255` is why this exists: it is what a refused or absent privileged spawn returns, it was the most common
 * failure with the least explanation, and the summary's range check (`129..192`, i.e. `128 + signal`) could
 * never reach it - so the screen showed a bare number for the one code that meant "nothing was executed".
 * A number nobody can act on is the same as no message at all.
 */
class PayloadExitCodeTest {

    @Test
    fun `255 says the command never ran`() {
        val detail = exitCodeSummary(255)
        assertTrue("255 is still unexplained: $detail", detail != null)
        assertTrue(
            "255 no longer says the command did not run, which is the whole difference between it and a " +
                "payload that ran and failed: $detail",
            detail!!.contains("could not be run"),
        )
    }

    @Test
    fun `the other shell codes come with it`() {
        assertEquals("the command was not found", exitCodeSummary(127))
        assertEquals("the command was found but could not be executed", exitCodeSummary(126))
    }

    @Test
    fun `a signal is still read as a signal`() {
        // The range the summary was written for, and it must not have been displaced: 137 is 128 + 9.
        val killed = exitCodeSummary(137)
        assertTrue("a killed payload is no longer named: $killed", killed!!.startsWith("signal 9"))
        assertTrue("SIGKILL stopped being named: $killed", killed.contains("SIGKILL"))
    }

    @Test
    fun `a plain failure is left to the payload's own account`() {
        // Not a shell code and not a signal: the payload ran and failed, so there is nothing to add.
        assertNull(exitCodeSummary(1))
        assertNull("above the signal range and not a shell code", exitCodeSummary(0))
    }

    @Test
    fun `the detail is the summary with the separator the message expects`() {
        assertTrue(
            "the message's shape changed, so every translation of it is now wrong",
            payloadExitDetail(255).startsWith(" \u2014 "),
        )
        assertEquals("", payloadExitDetail(1))
    }
}
