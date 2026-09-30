package dev.busung.s25uroot

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * The daemon the universal root last staged, and what it was.
 *
 * ## Why this exists at all
 *
 * Every other boot path in this app runs something already on the device: the payload flow's boot run uses
 * [KnownGoodPayloadStore] because "there is no network at boot to rely on", and the helper's is a package the
 * system installed. The chain's boot run had no such thing. It resolved its daemon from the feed and downloaded
 * it - [PayloadRepository.downloadDaemon] via `downloadArtifact`, which never reuses a file it already has - so
 * a boot without connectivity had nothing to stage, and the whole point of a boot run is that nobody is there
 * to notice.
 *
 * What makes it cheap here, and why this is a record rather than a cache of files: **the exploit is compiled
 * into this APK**. The payload flow has to keep a profile, an exploit and a daemon to run offline; this flow
 * needs the daemon and nothing else, and the daemon it staged on the last run is still sitting in the
 * device-protected directory the shellcode reads.
 *
 * ## What this claims, and what it does not
 *
 * It claims the bytes are *the ones the enabled sources published* for a flavour and a tier, because they were
 * downloaded and verified against the feed's own digest at the time. It does **not** claim they rooted the
 * phone: that is [KnownGoodPayloadStore]'s stronger statement, and it is deliberately not made here. The daemon
 * a boot retry stages is often the one a *failed* run left behind - that is what a retry is - so requiring a
 * verified install would leave the retry with nothing to run, which is exactly the case this was built for.
 *
 * Everything is checked again on the way out: a record whose flavour or tier is not the one asked for, or whose
 * file no longer hashes to what the feed declared, is refused rather than staged.
 */
internal data class CachedUniversalDaemon(
    /** Which KernelSU's daemon this is. */
    val flavor: KernelSuFlavor,
    /** Which tier staged it, because a boot run has to ask the same way it was resolved. */
    val tier: PayloadTier,
    /** The KernelSU release the feed said it was built from, when it said. */
    val version: String?,
    /** What the feed declared the artifact was, which is what the staged file is checked against. */
    val artifact: RemoteArtifact,
) {
    fun toJson(): String = JSONObject().apply {
        put("flavor", flavor.id)
        put("tier", tier.name)
        version?.let { put("version", it) }
        put("url", artifact.url)
        put("size", artifact.size)
        put("verifySize", artifact.verifySize)
        artifact.sha256?.let { put("sha256", it) }
    }.toString()

    companion object {
        fun parse(text: String): CachedUniversalDaemon {
            val json = JSONObject(text)
            val flavor = KernelSuFlavor.fromId(json.optString("flavor"))
                ?: error("a staged daemon was recorded for a flavour this build does not know")
            val tier = PayloadTier.fromId(json.optString("tier"))
                ?: error("a staged daemon was recorded for a payload tier this build does not know")
            return CachedUniversalDaemon(
                flavor = flavor,
                tier = tier,
                version = json.optString("version").trim().takeIf(String::isNotEmpty),
                artifact = RemoteArtifact(
                    url = json.getString("url"),
                    size = json.getLong("size"),
                    verifySize = json.optBoolean("verifySize", false),
                    sha256 = json.optString("sha256").trim().takeIf(String::isNotEmpty),
                ),
            )
        }
    }
}

/**
 * Why a recorded daemon may not be staged for [flavor] and [tier], or null when it may.
 *
 * Pure, because this is the decision a boot run rests on and it has to be reviewable without a device - the
 * same reason [cacheRejectionReason] is a function rather than a branch inside the store. Three ways it says no,
 * and they are different answers to the person reading a log:
 *
 * - the record is for another **flavour**: a daemon built for KernelSU-Next staged at a KernelSU run is the
 *   mix-up the flavour exists to prevent, and the chain's argv names one manager, so a mismatched pair fails
 *   somewhere that looks like the exploit's fault;
 * - the record is from another **tier**: the tiers differ in the module the daemon carries, so a boot that asked
 *   for the device tier and got the generic one is running a different experiment than the one it reported;
 * - the staged file is **not the bytes the feed declared** - replaced by another run's download, or truncated by
 *   one that was killed mid-write.
 */
internal fun universalCacheRefusalReason(
    cached: CachedUniversalDaemon,
    flavor: KernelSuFlavor,
    tier: PayloadTier,
    staged: File,
): String? = when {
    cached.flavor != flavor ->
        "the daemon staged on this phone is ${cached.flavor.label}'s, not ${flavor.label}'s"
    cached.tier != tier ->
        "the daemon staged on this phone came from the ${cached.tier.name.lowercase()} tier, not the " +
            "${tier.name.lowercase()} one"
    !fileMatchesArtifact(staged, cached.artifact) ->
        "the daemon staged on this phone is not the one the sources published, so it cannot be staged again"
    else -> null
}

/**
 * The record of the daemon the universal root last staged.
 *
 * A record and not a copy: the bytes stay where the run put them - the download directory the run reads from
 * and the device-protected path the shellcode reads - so this holds only the four facts that make those bytes
 * identifiable. Written by every run that stages a daemon, so what it names is always the daemon this phone
 * would run today, and read by a boot run in place of a feed it cannot reach.
 *
 * The record is stored as JSON in this app's own preferences because it is a handful of fields with no array in
 * it; the payload flow's cache needed a file beside its artifacts because it describes a profile.
 */
internal object UniversalDaemonStore {
    private const val PREFERENCES = "universal_daemon"
    private const val STAGED = "staged_daemon"

    /** What was staged, or null when nothing has been, or when the record cannot be read. */
    fun describe(context: Context): CachedUniversalDaemon? = runCatching {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(STAGED, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        CachedUniversalDaemon.parse(stored)
    }.getOrNull()

    /**
     * Records the daemon a run has just staged.
     *
     * Called after the file is in place and has been verified against what the feed declared, so this can only
     * ever name bytes that were downloaded from a source - never a partially written file, and never a
     * throwaway a run made for itself.
     */
    fun publish(context: Context, plan: UniversalRootRun.Plan, staged: File) {
        val artifact = plan.artifact
        // The feed does not always declare a digest, and a record without one would make every later check a
        // size comparison. The bytes are on disk here, so the digest is simply read off them.
        val recorded = if (artifact.sha256 != null) artifact else artifact.copy(sha256 = sha256Of(staged))
        val daemon = CachedUniversalDaemon(
            flavor = plan.flavor,
            tier = plan.tier,
            version = plan.version,
            artifact = recorded,
        )
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(STAGED, daemon.toJson())
            .commit()
    }

    /** Forgets it, so the next boot run refuses rather than staging something this phone no longer holds. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .remove(STAGED)
            .commit()
    }
}
