package dev.busung.s25uroot

import android.content.Context
import android.net.IpSecAlgorithm
import android.net.IpSecManager
import android.net.IpSecTransform
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/**
 * The universal root: the ported DFRoot chain, driven from this app.
 *
 * ## What makes it different from every other root this app offers
 *
 * Everything else the app does ends in the system-uid helper, and the helper is only there because a first
 * temporary root has already put it there. This path needs none of that. It runs from an ordinary app, with
 * no `sharedUserId`, no `packages.xml` edit and no reboot to re-read one, and it gets at the kernel through
 * an **unprivileged** `IpSecManager` transform — the technique DirtyInit found and DFRoot used. On a phone
 * with no root at all and nothing installed, this is a root path that starts from this APK alone.
 *
 * ## The daemon is DFRoot's own, and that is a measured decision
 *
 * This first carried the daemon out of our payload, and it did not work here — not the KernelSU-Next build
 * and not the KernelSU one. The chain handed each of them the same argv and each started and died in
 * silence, leaving no module and no log line, while DFRoot's own ksud in the same chain, on the same boot,
 * logged a complete late-load and rooted the phone.
 *
 * The reason is what the two daemons are built for. Theirs late-loads a kernel module it carries inside
 * itself, which is what this invocation asks for: `late-load --package-name me.weishu.kernelsu
 * --stage-from /data/system/ksud --ro-partitions`, with no path to a module anywhere in it. Our payload's
 * daemons are built for the *regular* flow, where the app stages files around them first. Feeding one to
 * this chain is asking a daemon to do a job its build was not assembled for, and the failure is silent in
 * the worst way — the chain reports success and the phone is not rooted.
 *
 * So the daemon ships in this APK (`assets/dfroot-ksud`), as DFRoot ships it, and that is the one piece of
 * their app this port carries as bytes. It is recorded in THIRD-PARTY.md with what it is and where it came
 * from, because a kernel-module-carrying binary in an APK is a fact that has to be written down.
 *
 * ## Running it
 *
 * [run] blocks: the native chain waits on the exploit, and this process must stay alive while it runs — the
 * daemon's bytes are handed over through a file descriptor this process owns. It is a worker-thread call,
 * and [report] is called from those worker threads as well as from the native side, so it has to be safe to
 * call from any of them.
 */
internal object UniversalRootRun {

    /**
     * The daemon, as DFRoot builds it: a `ksud` for `me.weishu.kernelsu` that contains its own kernel module.
     *
     * Taken from https://github.com/diabl0w/DFRoot (`app/src/main/assets/ksud`, 5,998,608 bytes) — see
     * THIRD-PARTY.md. It is the daemon their app uses, and the only one measured to complete this chain on
     * this device.
     */
    private const val DAEMON_ASSET = "dfroot-ksud"

    /**
     * Where that copy is put, in this app's own files directory.
     *
     * Private storage and not the temp directory: the app has to read these bytes itself to put them in a
     * memfd, and `/data/local/tmp` is not readable by an ordinary app at all on this platform.
     */
    private const val DAEMON = "universal-ksud"

    /** The exploit's own mutex, which is how a hook that is already in this boot is read. */
    internal const val ARMED_MARKER = "/dev/df"

    /** What a run did, in the words the screen needs. */
    internal sealed interface Outcome {
        /** The chain ran and returned one of its own codes. */
        data class Ran(val code: Int) : Outcome

        /** Nothing was attempted, and here is the reason to show. */
        data class Refused(val because: String) : Outcome
    }

    /** Whether this boot is already hooked, which is the one thing a run cannot start through. */
    internal fun alreadyArmed(): Boolean = runCatching { File(ARMED_MARKER).exists() }.getOrDefault(false)

    /**
     * KernelSU's own `su`, which the daemon installs on the system partition when it completes.
     *
     * This is the signal that matters on this path, and it was found the hard way: an ordinary app cannot
     * read the kernel at all. Measured on the device with root live - `run-as <app> cat /proc/modules` and
     * `run-as <app> ls /sys/module` both answer nothing about `kernelsu`, so [RootStatusProbe]'s fast
     * reading is blind here however rooted the phone is. `/system/bin/su` is present exactly when the daemon
     * has installed itself and absent on a boot without it, and it is readable from an app.
     */
    private const val SU_PATH = "/system/bin/su"

    /** What this app can actually read about whether the run worked. */
    internal sealed interface Reading {
        /** The kernel says so, through a channel an app can only read once root has been granted. */
        data object Live : Reading

        /** The daemon completed: KernelSU's `su` is on the system partition. */
        data object SuInstalled : Reading

        /** Nothing readable confirms it. */
        data object Nothing : Reading
    }

    /**
     * Whether this boot is rooted, in the strongest terms this app is able to read.
     *
     * The order is the point. [RootStatusProbe] is authoritative and asks the kernel — but only a process
     * with root can, so on a phone where the grant has not happened yet it answers no on a phone that is
     * rooted. The `su` the daemon installs is readable from anywhere and is what is left when the kernel is
     * not: it is a weaker claim and it is named as one, rather than being dressed up as the kernel's answer.
     */
    internal fun read(): Reading = when {
        rootIsLive() -> Reading.Live
        runCatching { File(SU_PATH).exists() }.getOrDefault(false) -> Reading.SuInstalled
        else -> Reading.Nothing
    }

    /** Whether KernelSU is live in this boot, asked of the kernel rather than of the chain. */
    internal fun rootIsLive(): Boolean = runCatching { RootStatusProbe.isActive() }.getOrDefault(false)

    /** What a reading means, said the same way in the log and on the screen. */
    internal fun describeReading(reading: Reading): String = when (reading) {
        Reading.Live -> "KernelSU is live in this boot, read from the kernel"
        Reading.SuInstalled ->
            "KernelSU's su is installed at $SU_PATH, so the daemon completed. An app cannot read the kernel " +
                "itself from here - open the manager to confirm and to grant this app root"
        Reading.Nothing -> "nothing readable on this phone confirms root"
    }

    /**
     * What the chain's own return codes mean, so one place decides rather than three screens.
     *
     * These are the chain's account of *itself* and are worded that way on purpose: `0` is the patches
     * landing and the daemon starting, which is not the same fact as this boot being rooted — see
     * [rootCheck], which is what turns one into the other.
     */
    internal fun describe(code: Int): String = when (code) {
        0 -> "the chain finished: the patches applied and the daemon started"
        1 -> "the daemon exited with an error"
        2 -> "a check failed - the log names it"
        3 -> "the patches did not land"
        else -> "unexpected result code $code"
    }

    /**
     * What the phone says about the run's own answer.
     *
     * The chain's return code is its *own* account of what it did, and this is the one path in the app where
     * that is not good enough: a module the running kernel refuses still leaves a chain that got all the way
     * to the end and returned success. So a run that says it worked is asked again — of the kernel — and the
     * answer is printed beside it rather than instead of it. Nothing here is a verdict about the exploit: it
     * is the difference between "the chain finished" and "this boot is rooted", which are two facts and only
     * one of them is the one a person cares about.
     */
    internal fun rootCheck(code: Int): String {
        if (code != 0) return "nothing to verify: the chain stopped before loading anything"
        return when (read()) {
            Reading.Live -> "checked the phone: KernelSU is live in this boot"
            Reading.SuInstalled -> "checked the phone: KernelSU's su is installed, so the daemon completed"
            Reading.Nothing ->
                "checked the phone: nothing readable confirms root - no $SU_PATH, and an app cannot read " +
                    "the kernel. Read the steps above before believing this failed."
        }
    }

    /**
     * Copies the bundled daemon into this app's own storage and makes it executable.
     *
     * `.tmp` then rename, so a kill in the middle cannot leave a half-written daemon where the next run
     * would take it for a whole one. Overwritten every run, so a changed APK cannot leave the previous
     * daemon behind.
     */
    internal fun stageDaemon(context: Context): File {
        val destination = File(context.filesDir, DAEMON)
        val temporary = File(destination.path + ".tmp")
        context.assets.open(DAEMON_ASSET).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("could not move the daemon into place")
        }
        destination.setExecutable(true, false)
        return destination
    }

    /**
     * Hands the daemon to the chain and runs it.
     *
     * [softReboot] is the caller's setting rather than a decision made here: a soft reboot after the module
     * loads is what finishes KernelSU's own start-up, and whether one happens is the app's `restartAfterRoot`.
     */
    internal fun run(
        context: Context,
        daemon: File,
        softReboot: Boolean,
        report: (String) -> Unit,
    ): Outcome {
        if (alreadyArmed()) {
            return Outcome.Refused(
                "This boot is already hooked ($ARMED_MARKER exists). Reboot before running again.",
            )
        }
        val code = try {
            drive(context, daemon, softReboot, report)
        } catch (error: Throwable) {
            return Outcome.Refused("The chain could not start: ${error.javaClass.simpleName}: ${error.message}")
        }
        report(describe(code))
        report(rootCheck(code))
        return Outcome.Ran(code)
    }

    /**
     * The `IpSecManager` half: the transform that makes the kernel do the writing.
     *
     * Ported from DFRoot's own driver, and unchanged in the parts that matter — an unprivileged UDP
     * encapsulation socket, an SPI allocated on the loopback, one AES-CBC key and one HMAC key, and a
     * transport-mode transform whose ESP packets the kernel decrypts into the page cache of files the
     * exploit has opened for `splice()`. The keys are generated per run and never leave this process: the
     * native side needs them to compute the IVs, and nothing else does.
     *
     * Every handle is closed in a `finally`, because a leaked encapsulation socket is a port held on a phone
     * that is about to reboot into a different kernel.
     */
    private fun drive(
        context: Context,
        daemon: File,
        softReboot: Boolean,
        report: (String) -> Unit,
    ): Int {
        val ipsec = context.getSystemService(Context.IPSEC_SERVICE) as IpSecManager
        val encapsulation = ipsec.openUdpEncapsulationSocket()
        val loopback = InetAddress.getByName("127.0.0.1")
        val spi = ipsec.allocateSecurityParameterIndex(loopback)
        val random = SecureRandom()
        val aesKey = ByteArray(32).also(random::nextBytes)
        val macKey = ByteArray(32).also(random::nextBytes)

        // A port from a socket that is immediately closed: the encapsulation socket is the one that stays
        // open, and this only has to be a number the transform can address the sender with.
        val senderPort = DatagramSocket().let { socket ->
            val port = socket.localPort
            socket.close()
            port
        }

        val transform = IpSecTransform.Builder(context)
            .setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, aesKey))
            .setAuthentication(IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, macKey, ICV_BITS))
            .setIpv4Encapsulation(encapsulation, senderPort)
            .buildTransportModeTransform(loopback, spi)

        return try {
            report("chain: starting (encap port ${encapsulation.port}, spi ${spi.spi})")
            UniversalRoot.nativeRunAll(
                reporter = UniversalRoot.Reporter { line -> report(line.trim()) },
                encapPort = encapsulation.port,
                spi = spi.spi,
                aesCbcKey = aesKey,
                hmacKey = macKey,
                icvLen = ICV_BITS / 8,
                senderPort = senderPort,
                ksudPath = daemon.absolutePath,
                softReboot = softReboot,
            )
        } finally {
            runCatching { transform.close() }
            runCatching { spi.close() }
            runCatching { encapsulation.close() }
        }
    }

    /** The truncation the transform and the native side have to agree on, in bits. */
    private const val ICV_BITS = 128
}
