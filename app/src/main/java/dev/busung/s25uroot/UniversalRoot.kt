package dev.busung.s25uroot

/**
 * The JNI bridge to the ported DFRoot chain.
 *
 * This declares the boundary and nothing else. The exploit itself is
 * `app/src/main/cpp/dfroot/`, ported from https://github.com/diabl0w/DFRoot, and the
 * reason it is here rather than in the `:dfr` helper is the whole point of that
 * project: this chain runs from an **ordinary app**, with no `sharedUserId`, no
 * `packages.xml` inject and no first temporary root - it drives the kernel through
 * an unprivileged `IpSecManager` transform. Everything the helper exists for is the
 * *other* chain.
 *
 * ## The name is the contract
 *
 * A JNI symbol is derived from the declaring class, so the class this declares on
 * has to be the same one exp.c exports, and the native symbol is
 * `Java_dev_busung_s25uroot_UniversalRoot_nativeRunAll` - the package and the
 * class, with the dots turned into underscores. Rename this class and the exploit
 * stops being findable with an `UnsatisfiedLinkError` that reads like a build
 * problem rather than a rename; `UniversalRootContractTest`
 * holds the two together.
 *
 * ## Why nothing here is `internal`
 *
 * Kotlin appends the *module name* to the JVM name of an `internal` function - this module is `app`, so an
 * `internal fun nativeRunAll` is really `nativeRunAll$app`, and JNI looks for a symbol mangled from that:
 * `Java_dev_busung_s25uroot_UniversalRoot_nativeRunAll_00024app`. Nothing in C is called that, so the
 * library loads, the first tap gets a payload, and the run dies at the last step with
 * `UnsatisfiedLinkError` naming a symbol that looks like a typo. The declarations below are therefore
 * public, and the only thing in this file that may ever be `internal` again is prose.
 *
 * ## The reporter
 *
 * The native side calls back on every step it takes, through a `report(String)`
 * method looked up on the object handed in - that is how a run's progress is
 * visible at all, because the interesting part of this chain happens in init and in
 * `vendor_modprobe`, where nothing of ours is running to log it.
 */
object UniversalRoot {

    init {
        // The library's name, not its path: `libdfroot.so` is loaded from this APK's
        // own lib directory, which is the one place the loader will take it from.
        System.loadLibrary("dfroot")
    }

    /** Where the exploit's progress goes. One call per step, in order. */
    fun interface Reporter {
        fun report(message: String)
    }

    /**
     * Runs the chain, and blocks until it has finished.
     *
     * [ksudPath] is a **path on this device**, not an asset: the app stages a daemon
     * wherever our payload pipeline resolved it and hands the path down, and the
     * native side copies those bytes into a memfd and patches that anonymous fd's
     * `/proc/self/fd/<n>` path into the libc shellcode. That is why nothing is
     * written into `/data/local/tmp`: the daemon is never a file the root side can
     * name by path, only a descriptor held by this process - which is also why this
     * process has to stay alive until the call returns.
     *
     * [softReboot] is the caller's setting rather than a decision made here: a soft
     * reboot after the module loads is what finishes KernelSU's own start-up, and
     * whether one happens is the app's `restartAfterRoot`, not this function's.
     *
     * Returns the native chain's own code: 0 success, 1 the daemon exited with an
     * error, 2 a check failed and the log names it, 3 the patches did not land.
     */
    external fun nativeRunAll(
        reporter: Reporter,
        encapPort: Int,
        spi: Int,
        aesCbcKey: ByteArray,
        hmacKey: ByteArray,
        icvLen: Int,
        senderPort: Int,
        ksudPath: String,
        softReboot: Boolean,
    ): Int
}
