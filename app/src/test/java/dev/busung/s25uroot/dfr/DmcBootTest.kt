package dev.busung.s25uroot.dfr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The D2 fix, held to the three things that make it either work or quietly not.
 *
 * The write itself cannot be exercised here - it is a reflection call into a Samsung system service that
 * only exists on a device - so what is tested is everything around it, which is where this feature is
 * actually able to fail:
 *
 * - **The receiver has to be woken at all**, and at the right moment. A manifest without `directBootAware`
 *   compiles, installs and never runs at `LOCKED_BOOT_COMPLETED`, which is the only delivery that matters
 *   for a lockdown applied at boot. Nothing on the phone reports that: the receiver is simply not called.
 * - **The write has to stay behind the shape check.** A vault write is not a change that can be taken back,
 *   and the record's layout was confirmed on one chip - so the check that refuses an unrecognised record is
 *   the difference between an unsupported device and a written-to one.
 * - **Off has to mean off.** The switch is the app's and the write is the helper's, so the two are joined by
 *   an extra and a stored copy; a default that drifted to `true` would put a device change on every phone
 *   that installed this app, which is the one outcome this feature must not have.
 */
class DmcBootTest {

    @Test
    fun `the boot receiver is registered where the platform can wake it`() {
        val manifest = source("dfr/src/main/AndroidManifest.xml")
        assertTrue(
            "the D2 fix's receiver is gone from the helper's manifest, so the vault flag is never written " +
                "however the setting is set",
            manifest.contains(".DmcBootReceiver"),
        )
        assertTrue(
            "the receiver is no longer direct-boot aware, so it is not woken at LOCKED_BOOT_COMPLETED - " +
                "which is the one delivery that happens before somebody reaches download mode",
            manifest.contains("android:directBootAware=\"true\""),
        )
        assertTrue(
            "the receiver no longer listens for LOCKED_BOOT_COMPLETED, so a phone rebooted straight into " +
                "download mode never gets its flag written",
            manifest.contains("android.intent.action.LOCKED_BOOT_COMPLETED"),
        )
        assertTrue(
            "the receiver no longer listens for BOOT_COMPLETED, so a vault service that was not up for the " +
                "first delivery never gets a second chance",
            manifest.contains("android.intent.action.BOOT_COMPLETED"),
        )
        assertTrue(
            "RECEIVE_BOOT_COMPLETED is not declared, and the platform will not deliver a boot broadcast to " +
                "an app that does not ask for it",
            manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"),
        )
    }

    @Test
    fun `the write stays behind the shape check and is verified by re-reading`() {
        val vault = source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcVault.kt")
        assertTrue(
            "the vault's record size is gone, so there is nothing left that can refuse a layout this code " +
                "was not written for",
            vault.contains("BLOB_SIZE = 32"),
        )
        assertTrue(
            "nothing gates the write on the record's shape any more, so a different firmware's layout " +
                "would be written to on a guess",
            vault.contains("understood(blob)"),
        )
        assertTrue(
            "the write no longer verifies itself by reading the record back, so a write that reported " +
                "success and did not land would be reported as the fix working",
            vault.contains("the flag did not read back"),
        )
        // One byte, and only one: the other twenty-nine are not documented anywhere, so anything that
        // touched more than the AT flag would be inventing meaning for fields nobody has defined.
        assertTrue("the AT flag is no longer named", vault.contains("INDEX_AT"))
        assertFalse(
            "the vault is now written through a constant that is not the one the read side reports, so the " +
                "screen and the write could disagree about which byte decides Odin",
            vault.contains("blob[INDEX_LOCK] = ") || vault.contains("blob[INDEX_MAINT] = "),
        )
    }

    @Test
    fun `a boot writes only when the app said so`() {
        val receiver = source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcBootReceiver.kt")
        assertTrue(
            "the boot receiver no longer asks whether the fix is on, so every phone that installed the " +
                "helper would have its vault written",
            receiver.contains("DmcGate.enabled(context)"),
        )
        val gate = source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcGate.kt")
        assertTrue(
            "the switch is read from the process's own storage, which is credential-encrypted - so at " +
                "LOCKED_BOOT_COMPLETED it reads as the default and the fix silently does nothing",
            gate.contains("createDeviceProtectedStorageContext()"),
        )
        assertTrue(
            "the stored switch no longer defaults to off, so a phone acquires a device write by having " +
                "this app installed",
            gate.contains("getBoolean(KEY_ENABLED, false)"),
        )
        assertTrue(
            "the app's own switch no longer defaults to off",
            source("app/src/main/java/dev/busung/s25uroot/AppPreferences.kt")
                .contains("getBoolean(DFR_DMC_FIX, false)"),
        )
    }

    @Test
    fun `the app hands the switch over on the launches the helper can be told by`() {
        // Two moments and no more: the app's own screen opening the helper, and the boot reroot starting it.
        // A launch that does not carry the setting is a launch after which the stored value is whatever it
        // was - which is correct, and is why the extra is only sent when the app has a value to give.
        assertTrue(
            "the app's screen no longer hands the D2 switch to the helper, so turning it on in settings " +
                "would not reach the vault until a boot reroot happened to run",
            source("app/src/main/java/dev/busung/s25uroot/DfrUi.kt")
                .contains("dmcFix = AppPreferences.dmcFix(context)"),
        )
        assertTrue(
            "the boot rerun no longer hands the D2 switch over, so a phone whose owner never opens the app " +
                "would keep writing the value it was last told",
            source("app/src/main/java/dev/busung/s25uroot/DfrBootService.kt")
                .contains("dmcFix = AppPreferences.dmcFix(this)"),
        )
        assertEquals(
            "the extra the app sets and the extra the helper reads are not the same name, so the setting " +
                "would never arrive",
            DfrInstall.STAGE_TWO_DMC_EXTRA,
            constantIn(
                source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Stage2Activity.kt"),
                "EXTRA_DMC_FIX",
            ),
        )
    }

    @Test
    fun `the helper's own screen says what the vault found`() {
        val screen = source("dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Stage2Activity.kt")
        assertTrue(
            "the helper's screen no longer reads the vault, which is the only place a person can see that " +
                "the fix is being refused rather than working",
            screen.contains("DmcVault.read()"),
        )
        assertTrue(
            "the screen no longer says whether the fix is armed, so `Odin allowed` right now and `Odin " +
                "allowed after the next reboot` would read the same",
            screen.contains("DmcGate.enabled(this)"),
        )
        assertTrue(
            "the vault is read on the drawing thread again, and the read is a binder call into a system " +
                "service inside system_server",
            screen.contains("refreshDmc()"),
        )
    }

    // ------------------------------------------------------------------------------ reading the sources

    private fun source(path: String): String {
        val direct = File(path)
        val fromRoot = File("../$path")
        return when {
            direct.isFile -> direct.readText()
            fromRoot.isFile -> fromRoot.readText()
            else -> error("no such file from the test's working directory: $path")
        }
    }

    private fun constantIn(text: String, name: String): String =
        Regex("""const val $name = "([^"]*)"""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: error("$name is not declared in the helper's screen")
}
