package dev.busung.s25uroot.dfr.stage2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The D2 fix, once per boot.
 *
 * The vault's flag is written here rather than by the app because of who may write it: the vault is a
 * Samsung system service, this APK runs as `android.uid.system`, and the app that owns the switch is an
 * ordinary app that cannot make the call at all. So the app says what it wants and this does it - see
 * [DmcGate] for how that setting gets here when the app cannot be running.
 *
 * ## Two actions, and why both
 *
 * `LOCKED_BOOT_COMPLETED` is the one that matters: the lockdown applies on a phone with a lock screen set,
 * and a person who reboots straight into download mode never reaches `BOOT_COMPLETED` at all - so a fix that
 * only ran on the later one would be absent exactly when it is needed. `BOOT_COMPLETED` is kept as well
 * because it is the later, calmer point in the boot: a vault service that was not up yet on the first
 * delivery gets a second chance here, and the write is a no-op when the flag is already set.
 *
 * ## Why the work leaves the main thread
 *
 * `onReceive` runs on the main thread of the process, which here is `system_server` - the process the whole
 * phone is waiting on during a boot. The write is a binder call into another service, and blocking
 * system_server's main thread during `LOCKED_BOOT_COMPLETED` is not a cost this feature gets to impose. So
 * the receiver takes the async handle and finishes it from a thread of its own, which is also what lets the
 * read-then-write-then-verify sequence happen without a timeout to answer to.
 *
 * ## Off is quiet, on is logged
 *
 * With the fix off the receiver logs one line and does nothing else. The one thing it must not do is write
 * "because it might be wanted": that would be a device change nobody asked for, on a store whose layout was
 * guessed from one chip - which is the same reason [DmcVault] refuses a record it does not recognise.
 */
class DmcBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        Thread {
            try {
                if (!DmcGate.enabled(context)) {
                    Log.i(TAG, "[DMC] $action: the D2 fix is off in the app's settings, so nothing was written")
                    return@Thread
                }
                val line = when (val result = DmcVault.writeAtFlag()) {
                    is DmcVault.Writing.Done ->
                        if (result.wrote) "flag written" else "flag was already set"
                    is DmcVault.Writing.Skipped -> "skipped: ${result.because}"
                    is DmcVault.Writing.Failed -> "failed: ${result.because}"
                }
                // Recorded for the helper's own screen, which is the only place a person can see what the
                // last boot did: this runs at a point in the boot where there is no UI at all.
                DmcGate.record(context, "$action $line")
                Log.i(TAG, "[DMC] $action: $line")
            } catch (error: Throwable) {
                Log.w(TAG, "[DMC] $action failed", error)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "RMGStage2"
    }
}
