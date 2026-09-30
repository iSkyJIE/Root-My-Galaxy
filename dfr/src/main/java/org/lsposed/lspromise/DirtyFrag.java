package org.lsposed.lspromise;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * DirtyFrag (CVE-2026-43284, xfrm-ESP page-cache write) JNI bridge.
 *
 * Taken verbatim from LSPromise (LSPosed/LSPromise) with only the log
 * TAG decoupled: the original referenced Shellcode.TAG (the Telecom-trigger
 * stage, not needed here since our code already runs inside system_server).
 * The Java package name is INTENTIONALLY unchanged: exp.c registers
 * Java_org_lsposed_lspromise_DirtyFrag_* natives and JNI_OnLoad looks up
 * "org/lsposed/lspromise/DirtyFrag". Renaming either side breaks linkage.
 *
 * Native lib: app/src/main/jni/libexp (exp.c + stage1.S (arm64-only) +
 * elf_parser.c + versioned dirtyfrag-android*.ko payloads, selected at
 * runtime by kernel version).
 */
public class DirtyFrag {

    private static final String TAG = "DirtyFrag";

    private final IBinder reporterBinder;

    public DirtyFrag(IBinder b) {
        reporterBinder = b;
    }

    public native int runAll();

    public void report(String msg) {
        var p = Parcel.obtain();
        try {
            Log.i(TAG, "Report: " + msg);
            p.writeString(msg);
            reporterBinder.transact(1, p, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            Log.e(TAG, "report failed (" + msg + ")", t);
        } finally {
            p.recycle();
        }
    }
}
