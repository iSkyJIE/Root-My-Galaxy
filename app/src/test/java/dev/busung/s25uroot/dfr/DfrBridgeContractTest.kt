package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the ported bridge: one native, one transaction code, and no way back to the four that went.
 *
 * `exp.c`, `DirtyFrag.java` and `StageReceiver.kt` are vendored from DFReroot and are not a build of one
 * thing: the C file registers natives by name, the Java file declares them, and the receiver is the only
 * caller of either. A rename or a re-vendoring can bring back a symbol nothing calls, so the three are
 * compared with each other here rather than each being assumed.
 *
 * The four per-step entry points are the reason this exists. Upstream deleted them (`a9a81bd`, "Remove
 * unused codes") and this copy carried them for several days after that, because nothing fails when they
 * are present: they compile, they link, and no caller reaches them. What they are is a second way to drive
 * the same kernel writes in a different order, which is a way to make a run that stops half-patched look
 * like a run that was never started.
 */
class DfrBridgeContractTest {

    @Test
    fun `the bridge declares one native and the C file exports exactly that one`() {
        val bridge = source("dfr/src/main/java/org/lsposed/lspromise/DirtyFrag.java")
        val declared = Regex("""public native int (\w+)\(\);""").findAll(bridge).map { it.groupValues[1] }.toList()
        assertEquals(
            "the bridge declares more than one native again, which is how the four per-step entry points " +
                "came back after upstream deleted them",
            1,
            declared.size,
        )
        val native = declared.single()
        assertEquals(
            "the one native the bridge declares is no longer the one transaction the receiver makes",
            native,
            Regex("""DirtyFrag\(\w+\)\.(\w+)\(\)""")
                .find(receiverSource())
                ?.groupValues
                ?.get(1),
        )

        val nativeSource = source("dfr/src/main/jni/exp.c")
        val exported = Regex("""Java_org_lsposed_lspromise_DirtyFrag_(\w+)\(""").findAll(nativeSource)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "the C file exports a different set of DirtyFrag symbols than the bridge declares: $exported",
            declared,
            exported,
        )
        assertTrue(
            "the export is no longer a JNI entry point, so nothing would link it",
            nativeSource.contains("JNIEXPORT jint JNICALL\nJava_org_lsposed_lspromise_DirtyFrag_$native("),
        )
    }

    @Test
    fun `the receiver offers the one code and no per-step ones`() {
        val receiver = receiverSource()
        assertEquals(
            "the receiver declares more than one code, so a caller could drive the exploit in pieces",
            1,
            Regex("""private const val CODE_\w+ = \d+""").findAll(receiver).count(),
        )
        assertFalse(
            "a per-step transaction is back in the receiver, which upstream deleted from its own",
            Regex("""DirtyFrag\.(patchMod|patchLibc|patchCxx|createOrphanProcess)\(\)""").containsMatchIn(receiver),
        )
    }

    @Test
    fun `the two helpers upstream dropped are still gone`() {
        // Neither was called by anything for a release, and both are free to mislead a later reader: one
        // reads a typed ABX attribute, the other recovers this package's own key out of packages.xml.
        assertFalse(
            "Abx's typed attribute probes are back, and nothing here reads a typed attribute",
            source("app/src/main/java/dev/busung/s25uroot/dfr/Abx.kt").contains("fun probeInt(") ||
                source("app/src/main/java/dev/busung/s25uroot/dfr/Abx.kt").contains("fun probeBytesHex("),
        )
        assertFalse(
            "PackagesXml.findInstalledKey is back, and the inject reads the key it needs from elsewhere",
            source("app/src/main/java/dev/busung/s25uroot/dfr/PackagesXml.kt").contains("fun findInstalledKey("),
        )
    }

    private fun receiverSource(): String =
        source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/StageReceiver.kt")

    private fun source(path: String): String {
        val direct = File(path)
        val fromRoot = File("../$path")
        return when {
            direct.isFile -> direct.readText()
            fromRoot.isFile -> fromRoot.readText()
            else -> error("no such file from the test's working directory: $path")
        }
    }
}
