package dev.busung.s25uroot

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline Fold 7 payload is part of this fork, not disposable build output.
 *
 * This test exists specifically to stop a source merge from silently dropping the two binaries or
 * leaving them in assets while removing the catalog entry that makes the app able to use them.
 */
class BundledPayloadIntegrityTest {

    @Test
    fun `the bundled Fold 7 payload is present with the bytes the catalog pins`() {
        val root = moduleRoot()
        val payload = File(root, "src/main/assets/payloads/q7q-F966USQU9BZDN")
        val exploit = File(payload, "cve-2026-43499-app.so")
        val daemon = File(payload, "ksud-s25u-kdp")

        assertTrue("bundled exploit is missing", exploit.isFile)
        assertTrue("bundled KernelSU daemon is missing", daemon.isFile)

        assertEquals(126_352L, exploit.length())
        assertEquals(6_407_096L, daemon.length())
        assertEquals(
            "92f1959c4944fae2825f094715b9f89e4b06b5ae141b1f6a3f4cb59f1ab84904",
            sha256(exploit),
        )
        assertEquals(
            "fa3edcc7d168637394877b30cb1f909d762dda788ec14051f4ae79edd6562d63",
            sha256(daemon),
        )
    }

    @Test
    fun `the bundled files are still wired into PayloadRepository`() {
        val root = moduleRoot()
        val source = File(root, "src/main/java/dev/busung/s25uroot/PayloadRepository.kt").readText()

        assertTrue(source.contains("profileId = \"q7q-F966USQU9BZDN\""))
        assertTrue(source.contains("BUNDLED_PAYLOAD_PATH = \"payloads/q7q-F966USQU9BZDN\""))
        assertTrue(source.contains("ASSET_PREFIX = \"asset://\""))
        assertTrue(source.contains("context.assets.open(assetPath)"))
    }

    private fun moduleRoot(): File =
        listOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main").isDirectory }
            ?: error("app module root not found")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
