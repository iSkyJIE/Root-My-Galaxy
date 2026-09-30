package dev.busung.s25uroot

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chain's boot gate, as two rules that can be read without a phone.
 *
 * Both are the kind that fail silently in a diff rather than loudly on a build. **Precedence**: a boot where an
 * armed retry and the standing switch both ask has to run the plan the tap named, and the wrong answer here is
 * not a crash - it is a boot that stages one daemon while its notification names another. **The file already on
 * the phone**: a boot has nothing to download, so it stages what is there, and what is there can be another
 * flavour's or another tier's from a run since.
 *
 * Neither rule is a preference read: [universalBootPlan] takes the three things the gate would have read, and
 * [universalCacheRefusalReason] takes the file, so these are the decisions themselves rather than a description
 * of where they are made.
 */
class UniversalBootRootTest {

    private val armed = UniversalPlan(KernelSuFlavor.KernelSuNext, PayloadTier.Generic)
    private val recorded = UniversalPlan(KernelSuFlavor.ReSukiSU, PayloadTier.Device)

    @Test
    fun `an armed retry answers for the boot whatever the switch says`() {
        // The retry is a tap for one boot and one flavour/tier; the switch is a standing answer about every
        // boot. So the retry wins whether the switch is on or off - and the case worth having here is the one
        // where they disagree about the *plan*, because a boot that let the switch answer would stage the
        // daemon it staged last time while reporting the one the user asked to retry.
        assertEquals(armed, universalBootPlan(armedRetry = armed, bootRootEnabled = true, recorded = recorded))
        assertEquals(armed, universalBootPlan(armedRetry = armed, bootRootEnabled = false, recorded = recorded))
    }

    @Test
    fun `the switch runs what this phone last staged`() {
        assertEquals(
            recorded,
            universalBootPlan(armedRetry = null, bootRootEnabled = true, recorded = recorded),
        )
    }

    @Test
    fun `a boot nothing asked about runs nothing`() {
        assertNull(universalBootPlan(armedRetry = null, bootRootEnabled = false, recorded = recorded))
    }

    @Test
    fun `the switch with nothing staged is nothing to run, not an error to hide`() {
        // The phone where the switch has been turned on and no run has ever staged a daemon. Null is the answer
        // the gate turns into "needs one run first" - and it must not fall back to resolving a feed, which is
        // the thing a boot may not have.
        assertNull(universalBootPlan(armedRetry = null, bootRootEnabled = true, recorded = null))
    }

    @Test
    fun `a staged daemon is refused for another flavour or another tier`() {
        withStagedDaemon { file, artifact ->
            val staged = CachedUniversalDaemon(
                flavor = KernelSuFlavor.KernelSu,
                tier = PayloadTier.Device,
                version = "3.3.0",
                artifact = artifact,
            )

            assertNull(
                "the daemon this phone staged is not usable for the run it was staged by",
                universalCacheRefusalReason(staged, KernelSuFlavor.KernelSu, PayloadTier.Device, file),
            )
            // Both refusals name what differs rather than saying "wrong daemon": the sentence is the only
            // account a boot run has, since nobody is watching it happen.
            val otherFlavor =
                universalCacheRefusalReason(staged, KernelSuFlavor.KernelSuNext, PayloadTier.Device, file)
            assertNotNull(otherFlavor)
            assertTrue(
                "the refusal for another flavour does not name it: $otherFlavor",
                otherFlavor!!.contains(KernelSuFlavor.KernelSuNext.label),
            )
            val otherTier = universalCacheRefusalReason(staged, KernelSuFlavor.KernelSu, PayloadTier.Generic, file)
            assertNotNull(otherTier)
            assertTrue(
                "the refusal for another tier does not name it: $otherTier",
                otherTier!!.contains("generic"),
            )
        }
    }

    @Test
    fun `the file on the phone is held to the digest the feed declared`() {
        withStagedDaemon { file, artifact ->
            val staged = CachedUniversalDaemon(
                flavor = KernelSuFlavor.KernelSu,
                tier = PayloadTier.Device,
                version = null,
                artifact = artifact,
            )

            // A run since replaced it - another flavour's daemon, or the same flavour's from the other tier,
            // which share one path - or a killed run left it half-written. Either way the bytes are not the
            // ones the record describes, and staging them is staging a daemon nothing verified.
            file.writeBytes(ByteArray(12) { 9 })
            assertNotNull(
                "a file that is no longer the recorded artifact is staged anyway",
                universalCacheRefusalReason(staged, KernelSuFlavor.KernelSu, PayloadTier.Device, file),
            )
        }
    }

    @Test
    fun `a record survives the round trip a boot run reads it through`() {
        // The record is what a boot run has instead of a feed, so what it must not do is lose a fact on the way
        // through the file: the tier is what the run is asked for again, and the digest is the whole check.
        val daemon = CachedUniversalDaemon(
            flavor = KernelSuFlavor.ReSukiSU,
            tier = PayloadTier.Generic,
            version = "4.2.0-rc2",
            artifact = RemoteArtifact("https://example.invalid/ksud", 4096, sha256 = "a".repeat(64)),
        )

        assertEquals(daemon, CachedUniversalDaemon.parse(daemon.toJson()))
    }

    @Test
    fun `a record for a flavour this build does not know is refused rather than defaulted`() {
        // The same rule the payload feed's entries are held to: a name that is close is not the name, and
        // reading it as the default would stage one KernelSU's daemon for another's run.
        val daemon = CachedUniversalDaemon(
            flavor = KernelSuFlavor.KernelSu,
            tier = PayloadTier.Device,
            version = null,
            artifact = RemoteArtifact("https://example.invalid/ksud", 8, sha256 = "b".repeat(64)),
        )
        val typo = daemon.toJson().replace("\"${KernelSuFlavor.KernelSu.id}\"", "\"kernel-su\"")

        assertTrue(
            "a record naming an unknown flavour is read as the default instead of being refused",
            runCatching { CachedUniversalDaemon.parse(typo) }.isFailure,
        )
    }

    /** A real file with a real digest, since the check under test is a digest and a length. */
    private fun withStagedDaemon(block: (java.io.File, RemoteArtifact) -> Unit) {
        val file = Files.createTempFile("ksud", ".bin").toFile()
        try {
            file.writeBytes(ByteArray(12) { 5 })
            block(file, RemoteArtifact(url = "u", size = 12, sha256 = sha256Of(file)))
        } finally {
            file.delete()
        }
    }
}
