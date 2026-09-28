package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where `packages.xml`'s mode and owner come from, and that they landed.
 *
 * This is the whole class of bug that this file has already had once. The values were read off the **backup**
 * instead of the original: the backup is created first and is a fresh file, and a fresh file in a root process
 * is `root:root 0644` - so the phone's real `system:system 0660 u:object_r:system_data_file:s0` came back
 * `root:root 0644`, and the next boot is PMS reading a file it cannot use. One identifier apart, and nothing
 * about it is visible in a diff: both forms stat something, both succeed, and the only difference is on a
 * device after a reboot.
 *
 * Upstream fixed it in one commit along with the read-back this also holds - see DFReroot's
 * `Apply permissions from original file permissions`. Both halves are here because either alone is not enough:
 * getting the source right does not prove the `chmod` took.
 */
class PackagesXmlWriteOrderTest {

    @Test
    fun `the metadata comes from the original, and never from the backup`() {
        val source = source()

        assertTrue(
            "the original is no longer stat'd, so the mode and owner written onto packages.xml are guesses",
            source.contains("Os.stat(xmlPath)"),
        )
        assertFalse(
            "the metadata is read off the backup again. A backup is a freshly created file, so this writes " +
                "root:root 0644 onto the phone's packages.xml - the fix upstream made and this repo then " +
                "reintroduced",
            source.contains("Os.stat(xmlPath + BACKUP_SUFFIX)"),
        )
    }

    @Test
    fun `the original is read before the backup is made`() {
        // Not decoration: the ordering is what makes the read the *original's* metadata. Stat after the
        // copyTo and the file being read may already be the backup's - which is the same bug wearing a
        // different line number.
        val source = source()
        val read = source.indexOf("Os.stat(xmlPath)")
        val backup = source.indexOf("copyTo(bak, overwrite = false)")

        assertTrue("nothing stats the original any more", read > 0)
        assertTrue("nothing creates the backup any more", backup > 0)
        assertTrue(
            "the backup is created before the original's metadata is read",
            read < backup,
        )
    }

    @Test
    fun `what was applied is read back, and a mismatch is refused`() {
        val source = source()
        val call = source.indexOf("verifyPerms(xmlPath, wantMode, wantUid, wantGid, log)")
        val definition = source.indexOf("private fun verifyPerms(")

        assertTrue("nothing checks that the permissions it asked for are the ones on disk", call > 0)
        assertTrue("no such check exists", definition > 0)
        // Throws rather than logging: a wrong owner on this file is a phone that may not boot, and a line in a
        // log is not a refusal. Upstream made the same call.
        val body = source.substring(definition, source.indexOf("\n    private fun applyPerms"))
        assertTrue(
            "a permission mismatch on packages.xml is only logged, so a run can report success over a file " +
                "the next boot cannot use",
            body.contains("throw RuntimeException"),
        )
    }

    /** The `writeBack` function, which is the part of this file that touches the real thing. */
    private fun writeBack(): String {
        val source = source()
        return source.substringAfter("private fun writeBack(").substringBefore("private fun applyPerms(")
    }

    private fun source(): String {
        val file = candidateRoots()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == "PackagesXml.kt" }.toList() }
            .firstOrNull()
        requireNotNull(file) { "PackagesXml.kt was not found; the scan is looking at the wrong directory" }
        return file.readText()
    }

    private fun candidateRoots(): List<File> = listOf(
        File("src/main/java"),
        File("app/src/main/java"),
    ).filter(File::isDirectory)
}
