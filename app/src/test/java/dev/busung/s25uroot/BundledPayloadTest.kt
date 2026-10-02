package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledPayloadTest {
    @Test
    fun q7qBundleKeepsItsDeviceAndArtifactIdentity() {
        val profile = BundledPayload.profile
        assertEquals("q7q-F966USQU9BZDN", profile.profileId)
        assertEquals(setOf("SM-F966U", "SM-F966U1"), profile.models)
        assertEquals(setOf("6.6.98"), profile.kernelVersions)
        assertEquals(126_352L, profile.exploit.size)
        assertEquals(6_407_096L, profile.kernelSu.size)
        assertTrue(profile.exploit.url.startsWith("asset://payloads/q7q-F966USQU9BZDN/"))
        assertTrue(profile.kernelSu.url.startsWith("asset://payloads/q7q-F966USQU9BZDN/"))
        assertTrue(isSha256(requireNotNull(profile.exploit.sha256)))
        assertTrue(isSha256(requireNotNull(profile.kernelSu.sha256)))
    }
}
