package dev.busung.s25uroot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing about the DFRoot port that can silently come apart.
 *
 * A JNI symbol is not a name anybody chooses: it is derived from the declaring class,
 * so `dev.busung.s25uroot.UniversalRoot` has to be the class exp.c exports for. The
 * two live in different languages in different directories with nothing tying them
 * together, and the failure when they disagree is a run that dies at
 * `System.loadLibrary` with an `UnsatisfiedLinkError` naming a symbol - which reads
 * like a broken build rather than a rename, and would be found on a phone rather
 * than here.
 *
 * So the symbol is *derived* from the Kotlin source and then looked for in the C
 * one. Deriving it is the point: a test that spelled the expected symbol out would
 * pass on a rename of the Kotlin class, which is exactly the change it exists to
 * catch.
 */
class UniversalRootContractTest {

    @Test
    fun `the native symbol names the class the bridge declares on`() {
        // Comments stripped first, and anchored to a line start: the declaration is found in prose otherwise -
        // the file's own KDoc says "the object handed in", which a loose pattern reads as the declaration and
        // then derives a symbol from the word `handed`.
        val bridge = code(source("app/src/main/java/dev/busung/s25uroot/UniversalRoot.kt"))
        val packageName = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
            .find(bridge)
            ?.groupValues
            ?.get(1)
            ?: error("the bridge has no package declaration")
        val className = Regex("""(?m)^object (\w+)""")
            .find(bridge)
            ?.groupValues
            ?.get(1)
            ?: error("the bridge no longer declares the object the symbol is derived from")

        // What the JVM would mangle: dots become underscores, and so does the
        // separator between the class and the method.
        val expected = "Java_" + (packageName + "." + className).replace('.', '_') + "_nativeRunAll"

        val native = source("app/src/main/cpp/dfroot/exp.c")
        assertTrue(
            "the native chain exports no symbol for $packageName.$className, so the ported exploit would " +
                "fail to link on the phone - the exported name has to be remangled whenever that class moves",
            native.contains(expected),
        )
        assertTrue(
            "the bridge no longer loads the library the native chain builds",
            bridge.contains("""System.loadLibrary("dfroot")"""),
        )
        assertTrue(
            "the native chain no longer builds a library by that name",
            source("app/src/main/cpp/dfroot/CMakeLists.txt").contains("add_library(dfroot SHARED"),
        )
        // `internal` is not a visibility choice here, it is a rename. Kotlin appends the module name to an
        // internal function's JVM name, so the entry point becomes `nativeRunAll$app` and JNI looks for
        // `Java_..._nativeRunAll_00024app` - a symbol no C file has. That was a real failure on the first
        // run: the library loaded, the payload downloaded, and the run stopped at the last step with an
        // `UnsatisfiedLinkError` naming something that looked like a typo.
        assertFalse(
            "the JNI entry point is `internal` again, which renames it to nativeRunAll$<module> and leaves " +
                "the exported symbol unreachable",
            bridge.contains("internal external fun nativeRunAll"),
        )
    }

    @Test
    fun `the chain resolves its callback from the object, not from a class name`() {
        // This one is here because it happened. Upstream looked the reporter's interface up as
        // `FindClass("df/root/IReporter")`, which is a copy of a Java declaration living in C - and the port
        // renamed the class without renaming this. The failure was not a missing callback: FindClass threw,
        // the GetMethodID after it ran with an exception pending, and the JVM aborted inside
        // System.loadLibrary on the first tap. A class name here has to be kept in step with Kotlin by hand;
        // asking the object for its own class cannot go stale at all.
        // Comments and code are separated first: the fix explains itself by naming what it removed, and a
        // test that failed because a comment mentioned the thing it forbids would be a test nobody could
        // write a comment next to.
        // Both files, because the fix moved: upstream keeps the reporter's plumbing in exp.c, and their new
        // revision moved that plumbing into reporter.h - so the lookup is now looked for where it lives, and
        // the thing it must not do is looked for in both.
        val files = listOf("exp.c", "reporter.h").associateWith { code(source("app/src/main/cpp/dfroot/$it")) }
        assertTrue(
            "the reporter's method is no longer resolved from the object the chain was handed, so it is " +
                "resolved from a name that a rename elsewhere can break",
            files.values.any { it.contains("GetObjectClass(env, obj)") },
        )
        assertFalse(
            "the chain looks a class up by name again: if that name and the Kotlin class ever disagree, the " +
                "process is aborted by JNI rather than reporting nothing",
            files.values.any { it.contains("FindClass(") },
        )
        assertFalse(
            "the chain caches its reporter at load time again, which is the point in the run where nothing " +
                "has been handed to it yet",
            files.values.any { it.contains("JNI_OnLoad") },
        )
    }

    @Test
    fun `the bridge and the entry point agree on their parameters, in order`() {
        // JNI passes parameters positionally, so a Kotlin declaration and a C signature that disagree by one
        // argument - or by their order - do not fail to compile, link, or load: the run reads a port out of a
        // key array and dies somewhere that looks like the exploit. The two live in different languages, so the
        // names are compared rather than trusted, normalised for the only two spellings that differ by design.
        val bridge = code(source("app/src/main/java/dev/busung/s25uroot/UniversalRoot.kt"))
        val native = code(source("app/src/main/cpp/dfroot/exp.c"))

        // Names and types both, because a match on one alone is not enough: the same JNI entry point exists
        // once, so what has to agree is the order of its parameters *and* what each one is.
        val kotlinParams = kotlinParameters(
            bridge.substringAfter("external fun nativeRunAll(").substringBefore("): Int"),
        )
        val nativeParams = cParameters(
            native.substringAfter("nativeRunAll(JNIEnv").substringBefore(") {"),
        ).mapNotNull { (type, name) ->
            // JNI's own leading parameters are not the bridge's, and two names differ by design: C says what
            // the value *is* - a path, an object - where Kotlin says what it means. Everything else has to
            // match exactly, which is what makes this a check on the order rather than on the vocabulary.
            C_TYPE_OF[type]?.let { (C_NAME_OF[name] ?: name) to it }
        }

        assertEquals(
            "the Kotlin bridge and the C entry point no longer take the same arguments in the same order, so " +
                "one of them is reading the other's value - which JNI cannot catch: it passes them positionally",
            kotlinParams,
            nativeParams,
        )
    }

    /** The `name to type` of a Kotlin parameter list, in order. */
    private fun kotlinParameters(text: String): List<Pair<String, String>> = text
        .split(',')
        .mapNotNull { part ->
            val name = part.trim().substringBefore(':').trim()
            val type = part.trim().substringAfter(':').trim()
            if (name.isEmpty() || type.isEmpty()) null else name to type
        }

    /** The `type to name` of a C parameter list, in order, with the JNI headers among them. */
    private fun cParameters(text: String): List<Pair<String, String>> = text
        .split(',')
        .mapNotNull { part ->
            val tokens = part.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            val name = tokens.getOrNull(1)?.takeIf { it.all { c -> c.isLetterOrDigit() || c == '_' } }
            if (tokens.size < 2 || name == null) null else tokens[0] to name
        }

    /**
     * What each JNI type is on the bridge's side, for the comparison above.
     *
     * A type that is not in here is one of JNI's own leading parameters, which the Kotlin declaration does not
     * have: `JNIEnv *` and `jclass`. Everything else has to be named, because a parameter this map does not
     * know is a parameter nobody has said the Kotlin side's counterpart of.
     */
    private val C_TYPE_OF = mapOf(
        "jobject" to "Reporter",
        "jstring" to "String",
        "jint" to "Int",
        "jbyteArray" to "ByteArray",
        "jboolean" to "Boolean",
    )

    /** The two names the two sides spell differently, and no others. */
    private val C_NAME_OF = mapOf(
        "koTargetPath" to "koTarget",
        "reporter_obj" to "reporter",
    )

    @Test
    fun `the eight modules we ship still name this app's daemon`() {
        // The one string in this chain that cannot be derived, and the most expensive kind of drift.
        //
        // It no longer lives in the shellcode: the module's `late-load` command names the daemon, because the
        // privileged half moved into the kernel. So the check moved with it, and it reads the bytes the app
        // actually embeds rather than a source file beside them - which is the version of this test that cannot
        // be fooled by a source tree that was edited without being rebuilt.
        //
        // The module is built in the payload repository, and that repository's workflow guards the same three
        // facts at build time. This holds the *artifacts*, which is what the phone runs.
        val appId = Regex("""applicationId = "([^"]+)"""")
            .find(source("app/build.gradle.kts"))
            ?.groupValues
            ?.get(1)
            ?: error("no applicationId in the app's build file")
        val expected = "/data/user_de/0/$appId/ksud".toByteArray(Charsets.UTF_8)

        modules().forEach { module ->
            val bytes = module.readBytes()
            assertTrue(
                "${module.name} does not name this app's daemon, so a run with it would stage a daemon nothing " +
                    "execs - and the phone is left with a patched kernel and no root",
                bytes.containsBytes(expected),
            )
            listOf("--ro-partitions", "--soft-reboot", "df.root").forEach { forbidden ->
                assertFalse(
                    "${module.name} carries $forbidden, which is upstream's package or an option the daemons " +
                        "built for this project do not accept - their daemon would refuse its own command line",
                    bytes.containsBytes(forbidden.toByteArray(Charsets.UTF_8)),
                )
            }
        }
    }

    /**
     * Whether [needle] appears in this array, byte for byte.
     *
     * `ByteArray.contains` takes a single element, not a run of them, so a module's strings are searched by
     * hand - which is also what makes this a check on the artifact rather than on the source that built it.
     */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    /** The eight module images the chain embeds, by KMI. */
    private fun modules(): List<File> {
        val directory = listOf(File("app/src/main/cpp/dfroot/ko"), File("../app/src/main/cpp/dfroot/ko"))
            .firstOrNull(File::isDirectory)
            ?: error("no module directory from ${File(".").absolutePath}")
        return directory.listFiles { f -> f.name.endsWith(".ko") }
            ?.sortedBy(File::getName)
            ?.takeIf { it.size == 8 }
            ?: error("the chain no longer carries eight kernel modules")
    }

    @Test
    fun `the vendor library the chain patches through is chosen per device`() {
        // Which vendor file gets patched differs by device, and upstream's latest revision is exactly the change
        // that made this a choice: a chain with one hardcoded path fails to patch on the phones whose vendor set
        // differs, and the failure reads as the exploit's. The order is the part worth holding, because it is
        // what decides which file a device is written through - and the list itself is upstream's to maintain.
        assertEquals(
            "a phone with none of the candidates no longer falls back to the first, which is upstream's own " +
                "choice: a phone the chain has never been run on gets an attempt that names what it could not " +
                "patch, rather than a refusal that never tried",
            KO_TARGET_CANDIDATES.first(),
            chooseKoTarget { false },
        )
        assertEquals(
            "the second candidate is not preferred over the first when both are present",
            KO_TARGET_CANDIDATES[1],
            chooseKoTarget { it != KO_TARGET_CANDIDATES.first() },
        )
        assertEquals(KO_TARGET_CANDIDATES[2], chooseKoTarget { it == KO_TARGET_CANDIDATES[2] })
        assertEquals(
            "the candidates are no longer the three vendor libraries, in upstream's order",
            listOf(
                "/vendor/lib64/libbinderdebug.so",
                "/vendor/lib64/libstagefrighthw.so",
                "/vendor/lib64/libstagefright_aidl_bufferpool2.so",
            ),
            KO_TARGET_CANDIDATES,
        )
    }

    /** The file's code, with its comments removed. */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")

    @Test
    fun `the shellcode names no daemon path of its own any more`() {
        // The counterpart of the test above, and a guard on where the path lives: it is named by the module and
        // by the app, and *not* by the shellcode. A path added back here would be a third copy - one the module
        // does not read and nothing stages - which is exactly the shape of the bug that put the check in the
        // module's bytes in the first place.
        val shellcode = code(source("app/src/main/cpp/dfroot/libcxx.S"))
        assertFalse(
            "the shellcode names a path under /data again, so the chain has a second idea of where the daemon is",
            shellcode.contains("/data/"),
        )
        assertTrue(
            "the shellcode no longer passes the manager to the module, so the daemon is told to serve whatever " +
                "the module was built with",
            shellcode.contains("package_name=me.weishu.kernelsu"),
        )
        assertFalse(
            "the shellcode passes soft_reboot=1 again, which the module built for this project does not declare " +
                "- insmod would refuse the parameter and load nothing",
            shellcode.contains("soft_reboot"),
        )
    }

    @Test
    fun `a universal run asks for the restart itself`() {
        // This one is here because it was wrong for as long as the flag existed. *Auto soft restart* is the
        // app's setting, and the chain used to be handed it: the shellcode wrote the flag into its data blob and
        // nothing read it, so a universal run with the setting on left the modules loaded with nothing asked to
        // pick them up. The payload flow has always performed this itself, which is the shape to compare
        // against - so the check is that the universal path does what that one does, and no longer passes the
        // decision to native code.
        val viewModel = code(source("app/src/main/java/dev/busung/s25uroot/InstallViewModel.kt"))
        val universal = viewModel.substringAfter("fun startUniversalRun(").substringBefore("private fun")

        assertTrue(
            "a universal run no longer asks the app for the soft restart when the setting is on, so the setting " +
                "does nothing after a chain run",
            universal.contains("AppPreferences.restartAfterRoot(app)") &&
                universal.contains("RecoveryTool.SoftReboot"),
        )
        assertFalse(
            "the restart decision is handed to the chain again, which has no way to act on it",
            viewModel.contains("UniversalRootRun.run(") &&
                Regex("UniversalRootRun\\.run\\([^)]*restartAfterRoot").containsMatchIn(viewModel),
        )
    }

    @Test
    fun `the CVE the payload sheet lists is the one the chain's own bridge names`() {
        // The number is now stated in two files and derived in neither: the sheet prints it in the header
        // over the universal rows, and the helper's DirtyFrag bridge names it as the bug its JNI natives
        // belong to. A CVE is exactly the kind of fact that gets copied and then edited in one place, so it is
        // taken from the helper's own sentence and looked for in the app's constant - the direction that
        // catches an edit rather than agreeing with itself.
        val helper = source("dfr/src/main/java/org/lsposed/lspromise/DirtyFrag.java")
        val stated = Regex("""CVE-\d{4}-\d{4,}""").find(helper)?.value
            ?: error("the helper's DirtyFrag bridge no longer names the CVE it belongs to")
        assertEquals(
            "the payload sheet lists a CVE the chain's own bridge does not name, so one of the two was " +
                "edited without the other - and the sheet would be labelling six rows with a number that is " +
                "not this chain's",
            stated,
            UniversalRootRun.CVE,
        )
    }

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
