package dev.busung.s25uroot

import java.io.File
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
        val native = code(source("app/src/main/cpp/dfroot/exp.c"))
        assertTrue(
            "the reporter's method is no longer resolved from the object the chain was handed, so it is " +
                "resolved from a name that a rename elsewhere can break",
            native.contains("GetObjectClass(env, obj)"),
        )
        assertFalse(
            "the chain looks a class up by name again: if that name and the Kotlin class ever disagree, the " +
                "process is aborted by JNI rather than reporting nothing",
            native.contains("FindClass("),
        )
        assertFalse(
            "the chain caches its reporter at load time again, which is the point in the run where nothing " +
                "has been handed to it yet",
            native.contains("JNI_OnLoad"),
        )
    }

    /** The file's code, with its comments removed. */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")

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
