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
 * an **unprivileged** `IpSecManager` transform the technique DirtyInit found and DFRoot used. On a phone
 * with no root at all and nothing installed, this is a root path that starts from this APK alone.
 *
 * ## The daemon comes from the payload, in one of two tiers
 *
 * The daemon is not bundled: it is downloaded from the payload repository for the flavour the run asks for,
 * and placed where the shellcode reads it. That is what keeps the argv and the daemon a matched pair the
 * chain passes `late-load --package-name <that flavour's manager>` and nothing else, so the daemon has to be
 * one built for that flavour's line.
 *
 * And because the KernelSU half of a payload is the one half that does **not** have to be device-specific,
 * there are two things it can be. They are offered as a choice rather than guessed at ([PayloadTier]):
 *
 * - **Device** the feed's entry for this exact phone, whose module was built for the kernel release it
 *   runs. The strongest pairing available, so it is the default.
 * - **Generic** the KMI-generic daemon for the same flavour, carrying a module per KMI and so covering a
 *   whole family of phones. What it has instead of a device-specific module is the daemon's own `vermagic`
 *   rewrite: `load_module()` replaces the module's vermagic with the value the running kernel requires and
 *   retries `init_module`, which is what lets one artifact cover a KMI family.
 *
 * Neither is used for the other's device behind anyone's back, and a tier that cannot serve this phone says
 * so *before* anything is downloaded: the device tier refuses when the feed has no entry for this phone, and
 * the generic tier refuses when its entry carries no module for this phone's KMI.
 *
 * ## Running it
 *
 * [run] blocks: the native chain waits on the exploit, and this process must stay alive while it runs the
 * daemon's bytes are handed over through a file descriptor this process owns. It is a worker-thread call,
 * and [report] is called from those worker threads as well as from the native side, so it has to be safe to
 * call from any of them.
 */
internal object UniversalRootRun {

    /**
     * The daemon's name, under the app's own data directory.
     *
     * A `ksud` for `me.weishu.kernelsu` that loads its kernel module itself, built by **our payload
     * repository** for this device's kernel rather than bundled in this APK. Owning the bytes is what makes
     * this path work: the daemon installs itself from the file the app stages, so nothing depends on a
     * manager APK's `libksud.so` being present - which is what left `/system/bin/su` at zero bytes when the
     * manager happened not to carry one.
     *
     * Upstream builds one daemon and their fork adds two options to it (`--ro-partitions`, `--soft-reboot`);
     * both of those things the app does itself, so the upstream build is what this drives, and `libc.S`
     * passes the four arguments it accepts and nothing more.
     */
    /**
     * The bug this chain uses, and what the project calls the technique.
     *
     * Named here once because two things outside this file read them: the payload sheet, which lists a row per
     * KernelSU per tier and has to say which chain that row is, and a test. The number is not this app's
     * finding both root paths here reach the kernel the same way, an unprivileged `IpSecManager` transform
     * whose ESP packets the kernel decrypts into the page cache of a file the exploit holds open, and the
     * helper's own DirtyFrag bridge states the CVE the technique is filed under. That statement is where this
     * comes from, and `UniversalRootContractTest` holds the two together: a CVE is a fact copied between files,
     * and a copy that drifts is a worse label than none at all.
     */
    internal const val CVE = "CVE-2026-43284"

    /** The same technique's name, for a row that has to say what it is without claiming a phone it does not own. */
    internal const val EXPLOIT_NAME = "DirtyFrag"

    private const val DAEMON = "ksud"

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
     * The order is the point. [RootStatusProbe] is authoritative and asks the kernel but only a process
     * with root can, so on a phone where the grant has not happened yet it answers no on a phone that is
     * rooted. The `su` the daemon installs is readable from anywhere and is what is left when the kernel is
     * not: it is a weaker claim and it is named as one, rather than being dressed up as the kernel's answer.
     */
    internal fun read(): Reading = when {
        rootIsLive() -> Reading.Live
        // Bytes, not existence. A run whose daemon found nothing to install itself from left a 0-byte
        // `/system/bin/su` on this device, and an existence test called that a completed daemon and reported
        // the boot as rooted.
        runCatching { File(SU_PATH).length() > 0 }.getOrDefault(false) -> Reading.SuInstalled
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
     * landing and the daemon starting, which is not the same fact as this boot being rooted see
     * [rootCheck], which is what turns one into the other.
     */
    internal fun describe(code: Int): String = when (code) {
        0 -> "the chain finished: the patches applied and the daemon started"
        1 -> "the daemon exited with an error"
        2 -> "the chain stopped waiting for the daemon to exit, so it cannot say - the phone decides"
        3 -> "the patches did not land"
        else -> "unexpected result code $code"
    }

    /**
     * What the phone says about the run's own answer.
     *
     * The chain's return code is its *own* account of what it did, and this is the one path in the app where
     * that is not good enough: a module the running kernel refuses still leaves a chain that got all the way
     * to the end and returned success. So a run that says it worked is asked again of the kernel and the
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
     * Which daemon a run stages, resolved but not yet downloaded.
     *
     * Resolving and downloading are two steps because the manager sits between them. The daemon is what says
     * which KernelSU release this run is about to load, and the manager has to be in place - and be the one
     * that matches - *before* the kernel starts answering: after that there is no app on the phone to grant
     * this one anything, so not even the reading at the end can ask the kernel. A plan answers "which
     * KernelSU" without a download, so the manager step is right even when the download is slow or fails.
     *
     * The type is sealed rather than a nullable profile plus flags, so a caller cannot stage a plan while
     * forgetting to record the flavour it resolved, or describe a generic daemon as a device one.
     */
    internal sealed class Plan {
        /** The root this run will load, which decides the manager. */
        abstract val flavor: KernelSuFlavor

        /** The KernelSU release the daemon was built from, when the feed declares one. */
        abstract val version: String?

        /** The artifact to fetch. The only difference between the tiers at this level. */
        abstract val artifact: RemoteArtifact

        /**
     * Which tier resolved this, which is what a boot run has to ask for again.
     *
     * Carried rather than inferred from the class by the caller, because a boot run has to name a tier to
     * [bootPlan] and the class is not something a preference can hold. For the two resolved tiers it is a
     * constant; [Cached] reads the one its record was written with.
         */
        abstract val tier: PayloadTier

        /** One line naming exactly what will be staged, for the log and the confirmation. */
        abstract val description: String

        /** What a person has to know about this choice before it is used, or null when there is nothing. */
        abstract val caveat: String?

        /** The feed's entry for this phone: a module built for the kernel release it runs. */
        class Device(val profile: TargetProfile) : Plan() {
            override val flavor: KernelSuFlavor get() = profile.flavor
            override val version: String? get() = profile.kernelSuVersion
            override val artifact: RemoteArtifact get() = profile.kernelSu
            override val tier: PayloadTier get() = PayloadTier.Device
            override val description: String
                get() = "device payload: ${profile.displayName} (${profile.flavor.label})"

            override val caveat: String?
                get() = when (profile.kernelMatch(DeviceSnapshot.current())) {
                    // Matched on the three-part version alone, so the entry documents this kernel *version*
                    // rather than this build: its module may be the one for a regional sibling. Worth saying,
                    // because it is exactly the case where the device tier is less specific than it sounds.
                    KernelMatch.Version ->
                        "this entry lists the kernel version rather than this build's exact release, so its " +
                            "module may be built for a sibling build of the same version"
                    else -> null
                }
        }

        /** A daemon carrying a module per KMI, so it covers a family of kernels rather than this phone. */
        class Generic(val daemon: GenericDaemon) : Plan() {
            override val flavor: KernelSuFlavor get() = daemon.flavor
            override val version: String? get() = daemon.version
            override val artifact: RemoteArtifact get() = daemon.daemon
            override val tier: PayloadTier get() = PayloadTier.Generic
            override val description: String
                get() = "generic payload: ${daemon.flavor.label}, carrying ${daemon.coverage}"
            override val caveat: String
                get() = "a shared build for every ${daemon.flavor.label} kernel in ${daemon.coverage}: the " +
                    "module it loads is chosen by the running kernel's KMI rather than built for this phone"
        }

        /**
         * The daemon this phone already staged, for a boot that has no feed to resolve against.
         *
         * A third kind rather than a flag on the other two, because what it describes is genuinely a different
         * thing: the other two are *resolutions* - a reading of what the sources publish right now, pinned to
         * the revision they were read at - and this is a memory of one that has already been used. A caller that
         * could not tell them apart could report "the sources publish X" about a daemon no source was asked
         * about on this boot.
         */
        class Cached(val remembered: CachedUniversalDaemon) : Plan() {
            override val flavor: KernelSuFlavor get() = remembered.flavor
            override val version: String? get() = remembered.version
            override val artifact: RemoteArtifact get() = remembered.artifact
            override val tier: PayloadTier get() = remembered.tier
            override val description: String
                get() = "staged payload: the ${remembered.flavor.label} ${remembered.tier.name.lowercase()} " +
                    "daemon this phone already has"
            override val caveat: String
                get() = "the daemon this device staged on an earlier run rather than one read from the sources " +
                    "just now, which is what a boot run can use: nothing here resolves a feed"
        }
    }

    /**
     * Resolves which daemon this run will stage. Downloads nothing.
     *
     * A tier that cannot serve this phone throws with the reason, before the manager step and before a byte is
     * fetched - so a refusal costs nothing but the sentence explaining it. The refusals are deliberately
     * different sentences: "the feed has no entry for this phone" and "its generic daemon carries no module
     * for this kernel" call for different things from whoever reads them.
     */
    internal fun plan(context: Context, flavor: KernelSuFlavor, tier: PayloadTier): Plan {
        val repository = PayloadRepository(context)
        val snapshot = DeviceSnapshot.current()
        return when (tier) {
            PayloadTier.Device -> {
                val profile = repository.loadTargets().resolveFor(snapshot, flavor)
                if (profile == null) {
                    throw IllegalStateException(
                        "no ${flavor.label} payload for ${snapshot.model} on kernel " +
                            "${snapshot.kernelVersion} in the enabled sources - choose the generic payload, or " +
                            "add a payload for this build to a source",
                    )
                }
                Plan.Device(profile)
            }

            PayloadTier.Generic -> {
                val loaded = repository.loadGenericDaemons()
                val covering = loaded.daemons.covering(flavor, snapshot.kmi)
                if (covering == null) {
                    val published = loaded.daemons.firstOrNull { it.flavor == flavor }
                    val because = when {
                        published != null ->
                            "the generic ${flavor.label} daemon carries ${published.coverage}, and this " +
                                "phone's kernel is ${snapshot.kmi ?: snapshot.kernelRelease}, so it has no " +
                                "module for this phone"
                        loaded.failures.isNotEmpty() -> loaded.failures.joinToString("\n")
                        else -> "no generic ${flavor.label} daemon is published by the enabled sources"
                    }
                    throw IllegalStateException("No generic payload: $because")
                }
                Plan.Generic(covering)
            }
        }
    }

    /**
     * The plan a boot run uses: the daemon this phone already staged, or the sources when it has none.
     *
     * The whole reason a boot can run this flow offline. [plan] reads the sources - the catalog for the device
     * tier, the generic feed for the other - and then downloads the daemon it chose, and neither half of that
     * works on a phone that has just restarted with no connectivity. This names the file the last run left in the
     * device-protected directory the shellcode reads, and asks nothing of the network.
     *
     * Two different noes, and the difference matters to whoever reads the log:
     *
     * - **nothing recorded** - a phone that has never staged a daemon, which is every phone whose last run was
     *   on a build before this record existed - falls back to [plan], because that is exactly what a boot run did
     *   before there was anything to stage. Resolving the sources there is not a regression, it is the previous
     *   behaviour; a phone with no network fails with the resolution's own sentence, as it always could.
     * - **recorded and wrong for this run** - another flavour's, another tier's, or a file that is no longer what
     *   was verified - is thrown, and not papered over with a download: it means the record and the run disagree,
     *   and the sentence names which of the three it is.
     */
    internal fun bootPlan(context: Context, flavor: KernelSuFlavor, tier: PayloadTier): Plan {
        val remembered = UniversalDaemonStore.describe(context) ?: return plan(context, flavor, tier)
        universalCacheRefusalReason(remembered, flavor, tier, daemonPath(context))?.let { reason ->
            throw IllegalStateException("No staged payload: $reason")
        }
        return Plan.Cached(remembered)
    }

    /**
     * Where the daemon the chain reads lives, which is also where a boot run's daemon already is.
     *
     * The app's own data directory rather than its `files` directory, and device-protected rather than
     * credential-encrypted: `/data/data` is not mounted until the user unlocks, and a boot run of this flow is
     * one of the two reasons the path is what it is. Named here rather than inline in [stage] because three
     * things now have to agree about it - the staging, the record a boot run verifies against, and `libc.S`.
     */
    internal fun daemonPath(context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir.parentFile, DAEMON)

    /**
     * Downloads the plan's daemon and puts it where the chain reads it, and makes it executable.
     *
     * The one path the shellcode knows is this app's own data directory, and placing the file there is the
     * app's job and only the app's: the chain cannot create a file under `/data` at all, which is what every
     * earlier attempt to have it stage its own daemon ran into.
     *
     * `.tmp` then rename, so a kill in the middle cannot leave a half-written daemon where the next run would
     * take it for a whole one. Overwritten every run.
     */
    internal fun stage(context: Context, plan: Plan, report: (String) -> Unit): File {
        // The chain's own path: the app's data directory, not its `files` directory - where the regular
        // flow's copies go - and not the temp directory, which no app may write on this platform. See
        // [daemonPath] for why it is that directory and that storage.
        val destination = daemonPath(context)

        // A boot run stages what is already here. Nothing is downloaded and nothing is copied - the file is
        // the destination - but it is still checked against the digest the feed declared, because the copy on
        // the phone could be another flavour's from a run since, and staging a daemon built for a different
        // manager is the mix-up the flavour exists to prevent.
        if (plan is Plan.Cached) {
            require(fileMatchesArtifact(destination, plan.artifact)) {
                context.getString(R.string.universal_cached_daemon_stale)
            }
            destination.setExecutable(true, false)
            report("daemon: the copy this phone staged earlier (${plan.artifact.sha256?.take(12) ?: "no digest"})")
            return destination
        }

        val repository = PayloadRepository(context)
        // The daemon alone, for both tiers. This path's exploit is the chain compiled into this APK, so a
        // device entry's exploit artifact would be fetched, verified and thrown away - and a failure in that
        // download would stop a run that never uses it.
        val source = repository.downloadDaemon(plan.artifact, plan.flavor) { line -> report(line) }
        val temporary = File(destination.path + ".tmp")
        source.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("could not move the daemon into place")
        }
        destination.setExecutable(true, false)
        // Recorded so the next boot can stage this same daemon without a feed. After the rename, so a record can
        // never name a file a killed run left half-written, and only from a plan that came from a source - this
        // branch - so the record can only ever describe bytes the enabled sources published.
        UniversalDaemonStore.publish(context, plan, destination)
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
        flavor: KernelSuFlavor,
        softReboot: Boolean,
        report: (String) -> Unit,
    ): Outcome {
        if (alreadyArmed()) {
            return Outcome.Refused(
                "This boot is already hooked ($ARMED_MARKER exists). Reboot before running again.",
            )
        }
        val code = try {
            drive(context, daemon, flavor, softReboot, report)
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
     * Ported from DFRoot's own driver, and unchanged in the parts that matter an unprivileged UDP
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
        flavor: KernelSuFlavor,
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
                // The manager this flavour's daemon serves. One library, so it travels as a value - see
                // [UniversalRoot.nativeRunAll].
                packageName = flavor.managerPackage,
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
