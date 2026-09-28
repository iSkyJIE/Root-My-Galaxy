package dev.busung.s25uroot.dfr.stage2

import android.content.Context
import android.content.SharedPreferences

/**
 * The D2 fix's switch, as this helper remembers it.
 *
 * The switch is the **app's**. This APK has no settings screen and no copy of the app's preferences, and the
 * app tells the helper what it decided every time it launches it, on the extra it already sends - so what
 * lives here is only the last value the app said.
 *
 * ## Why a copy exists at all
 *
 * The write this gates has to happen on `LOCKED_BOOT_COMPLETED` - before anybody has unlocked the phone -
 * because a lockdown that is applied at boot is a lockdown that has to be answered at boot. At that moment
 * the app cannot run: it is an ordinary app with no direct-boot components, and its preferences are in
 * credential-encrypted storage. So the only place the decision can still be read from is this APK's own
 * storage, and that is what this is.
 *
 * ## Why device-protected storage, which is the whole trick
 *
 * `createDeviceProtectedStorageContext` and not the process's own storage, because the credential-encrypted
 * copy is *exactly* what is not mounted at `LOCKED_BOOT_COMPLETED`. A receiver that reads the ordinary
 * preferences there gets the default - which would be "off" - and the failure is silent in the worst
 * direction: it looks like the user turned the fix off, on a phone where they turned it on.
 *
 * ## A stale copy is the intended behaviour
 *
 * The value is only refreshed when the app is opened or launches the helper, so a phone whose owner turned
 * the fix on and then did not open the app for a month still has its flag written on every boot. That is the
 * point of storing it here rather than asking: at the moment the answer is needed, there is nobody to ask.
 */
internal object DmcGate {

    /** This APK's own preferences file, separate from anything the app owns. */
    private const val PREFERENCES = "rmgnext_dmc"

    private const val KEY_ENABLED = "enabled"

    private const val KEY_LAST_RESULT = "last_result"

    private fun store(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Whether the app last said the fix is on. Off when it has never said anything. */
    internal fun enabled(context: Context): Boolean = store(context).getBoolean(KEY_ENABLED, false)

    /**
     * What the app just said. Called on the launch that carries the setting, and nowhere else.
     *
     * **Committed and then flushed, not applied.** `apply()` hands the write to a background flush, and the
     * very next thing a launch that carries this setting may do is ask for the restart it has to survive.
     * Losing it is not a lost preference: the reader is the boot receiver, and a value that did not land reads
     * as the default - which is `false` - so the failure is a fix that looks switched off on a phone where it
     * was switched on. That is the exact failure this class exists to avoid, and `apply()` is the one write
     * that can still produce it.
     */
    internal fun setEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
        syncNow()
    }

    /**
     * Asks the kernel to flush what is pending, best effort.
     *
     * Ported from DFReroot's `AutoRoot.syncNow()`, which runs it for the same reason: a value a reboot is about
     * to depend on should be on the disk before the reboot is asked for, and `commit()` fsyncs the file rather
     * than everything around it. It never throws - a `sync` that failed is not a reason to report the setting
     * as unsaved.
     */
    internal fun syncNow() {
        runCatching { Runtime.getRuntime().exec("sync").waitFor() }
    }

    /** What the last boot's write did, for the screen - null until there has been one. */
    internal fun lastResult(context: Context): String? = store(context).getString(KEY_LAST_RESULT, null)

    internal fun record(context: Context, result: String) {
        // Committed for the same reason, though only a screen reads this one back: a value whose whole purpose
        // is to be read after the next boot should not depend on when a background flush happened to run.
        store(context).edit().putString(KEY_LAST_RESULT, result).commit()
    }
}
