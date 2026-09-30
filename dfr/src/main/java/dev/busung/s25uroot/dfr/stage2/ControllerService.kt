package dev.busung.s25uroot.dfr.stage2

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock

/**
 * The second way the controller comes back from `network_stack`, and the more reliable one.
 *
 * The hop hands this process's exploit an execution context in network_stack, which loads `libexp` and builds
 * a binder whose `onTransact` is the exploit itself - so that binder has to travel back here, where the wait
 * for it is. It has always travelled as a **broadcast** carrying the binder in its extras, and that is what
 * [StageReceiver] still sends.
 *
 * Why a service as well: DFReroot moved its own equivalent from a broadcast to a bound service with the note
 * *"Broadcast seems to be restricted on boot completed receiver"* - and this helper is started by exactly that
 * path. An unattended run begins at `LOCKED_BOOT_COMPLETED`/`BOOT_COMPLETED`, the hop is made from there, and
 * a broadcast sent from a boot context is the one kind that can be dropped. The failure is quiet: no error,
 * no log line, just a waiter that runs out its timeout and reports that the controller never arrived.
 *
 * ## Both, deliberately
 *
 * The broadcast is **kept**, and both paths are live. The binder is the same object either way, so the two
 * cannot disagree about what arrived - whichever reaches [arrive] first wakes the waiter, and the second one
 * replaces it with the identical reference. That makes this additive: a device where the bind fails behaves
 * exactly as it did before, and a device where the broadcast is refused now has a working path.
 *
 * ## Why the slot lives here
 *
 * The wait is in [Stage2Activity], and a `Service` cannot reach an activity instance. DFReroot solves this with
 * a process-wide object (`AutoRoot`) that its service and its waiter both use; this is the same shape, sized
 * to the one fact it carries.
 *
 * `exported="true"` is required and not a lapse: the sender is network_stack, a different process with a
 * different uid, so nothing narrower can reach it.
 */
class ControllerService : Service() {

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == CODE_CONTROLLER) {
                val controller = try {
                    data.readStrongBinder()
                } catch (error: Throwable) {
                    null
                }
                arrive(controller)
                // Answered so the sender can tell this worked rather than guessing from the silence: a bind
                // that returned false or a transact that threw is a failed path, and the sender falls back.
                reply?.writeInt(if (controller != null) 1 else 0)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    companion object {
        /** The one transaction code: here is the controller. */
        const val CODE_CONTROLLER = 1

        /**
         * The wait, in one place.
         *
         * `wait` with a real deadline rather than a spin, and it returns null on its own timeout so the caller
         * reports "the controller never arrived" instead of hanging. Both deliveries land here.
         */
        private val lock = Object()
        private var controller: IBinder? = null

        /** Called from the service's transaction and from [Stage2Activity]'s receiver. */
        fun arrive(binder: IBinder?) {
            synchronized(lock) {
                controller = binder
                lock.notifyAll()
            }
        }

        /**
         * What is in the slot without waiting.
         *
         * For [Stage2Activity], which waits on its own lock so it can report the countdown and must not be
         * moved onto a second one: it re-reads every few seconds anyway, and this is what makes a delivery from
         * the service visible to that loop even though nothing notified *its* lock.
         */
        fun controllerNow(): IBinder? = synchronized(lock) { controller }

        /** The controller, or null when it had not arrived within [timeoutMs]. */
        fun await(timeoutMs: Long): IBinder? {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            synchronized(lock) {
                while (controller == null) {
                    val left = deadline - SystemClock.uptimeMillis()
                    if (left <= 0L) return null
                    try {
                        lock.wait(left)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
                return controller
            }
        }
    }
}
