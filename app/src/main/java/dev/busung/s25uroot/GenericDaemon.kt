package dev.busung.s25uroot

import org.json.JSONArray
import org.json.JSONObject

/**
 * A daemon that carries a kernel module for several KMIs instead of one.
 *
 * ## Why this is a separate tier rather than another payload
 *
 * A payload entry in `targets-v3.json` is a *device*: it names the models and kernel releases it was built
 * and tested for, and its `kernelsu` artifact is a daemon embedding the module built for that one kernel
 * release. That is the strongest pairing available and it stays the first choice.
 *
 * This is the other half of the story. `ksud` loads its kernel module out of its **own asset directory**,
 * asking for `<kmi>_kernelsu.ko` after working out the running kernel's KMI - so one daemon carrying a
 * module per KMI serves every device whose KMI is in that set, whatever model it is and whatever exact
 * kernel release it runs. The payload repository builds one per flavour
 * (`support/kernelsu-generic.json`) and this is what the app reads.
 *
 * ## What it does not replace
 *
 * The per-device entry, and never silently: a device entry's module was built for the release that device
 * runs, and the generic one was not - it relies on the daemon rewriting `vermagic` to whatever the kernel
 * requires before it retries `init_module`. So the two are offered as a choice, with what each one is, and
 * neither is used for the other's device behind the user's back. See [PayloadTier].
 *
 * ## Why the KMI is matched rather than assumed
 *
 * [kmis] is read out of the daemon's own asset table by the payload repository when it is published, so it
 * is what the binary actually carries rather than what a file name says. A phone whose KMI is not in it is
 * refused with that said out loud, because the alternative - handing it a module for another kernel - is
 * refused by the loader with nothing anywhere to explain why the run did nothing.
 */
data class GenericDaemon(
    val flavor: KernelSuFlavor,
    /** The KMIs this daemon carries a module for, as its own asset table lists them. */
    val kmis: Set<String>,
    val daemon: RemoteArtifact,
    /** The KernelSU release the daemon was built from, when the feed says. */
    val version: String?,
    val sourceId: String = "",
    val sourceLabel: String = "",
    val sourceCommit: String = "",
) {
    /** Whether this daemon has a module for [kmi]. A null KMI is never covered. */
    fun covers(kmi: String?): Boolean = kmi != null && kmi in kmis

    /** What this covers, for a log line or a dialog. */
    val coverage: String
        get() = kmis.sorted().joinToString(", ")
}

/**
 * The generic tier, as the payload repository publishes it.
 *
 * One entry per flavour. Separate from [SupportManifest] on purpose: that one is a released client
 * contract whose entries name a tested artifact per device, and this is a fallback that is allowed to be
 * less specific. Nothing here can change what a device's own entry points at.
 */
data class GenericDaemonFeed(
    val schemaVersion: Int,
    val entries: List<GenericDaemon>,
    /** Flavour ids this build does not know, dropped rather than read as the default. */
    val ignored: List<String> = emptyList(),
) {
    companion object {
        /** Where a payload repository keeps it, beside the manifest it is read from. */
        const val PATH = "support/kernelsu-generic.json"

        /** The only shape this build reads. A different one is a feed written for a different app. */
        const val SUPPORTED_SCHEMA = 1

        /**
         * Reads a feed.
         *
         * An entry naming an unknown flavour is dropped, not defaulted - the same rule the device manifest
         * follows, and for the same reason: installing the other project's KernelSU because a name looked
         * close is the one outcome nobody can explain afterwards. What was dropped is returned so the
         * caller can say so.
         */
        fun parse(bytes: ByteArray): GenericDaemonFeed {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val schemaVersion = root.optInt("schemaVersion", 0)
            require(schemaVersion == SUPPORTED_SCHEMA) {
                "Unsupported generic daemon feed schema $schemaVersion"
            }

            val entriesJson = root.optJSONArray("generic") ?: JSONArray()
            val ignored = mutableListOf<String>()
            val entries = buildList {
                for (index in 0 until entriesJson.length()) {
                    val entry = entriesJson.getJSONObject(index)
                    val flavor = entry.flavorOrNull()
                    if (flavor == null) {
                        ignored += entry.optString("flavor").trim()
                        continue
                    }
                    // A daemon with no KMI covers nothing, so there is no device it could be offered to.
                    val kmis = entry.optJSONArray("kmis").strings()
                    if (kmis.isEmpty()) continue
                    val daemon = entry.getJSONObject("daemon")
                    add(
                        GenericDaemon(
                            flavor = flavor,
                            kmis = kmis,
                            daemon = daemon.artifact(),
                            version = daemon.declaredVersion(),
                        ),
                    )
                }
            }
            return GenericDaemonFeed(schemaVersion, entries, ignored)
        }

        private fun JSONArray?.strings(): Set<String> = buildSet {
            if (this@strings == null) return@buildSet
            for (index in 0 until length()) add(getString(index).trim())
        }
    }
}

/**
 * The generic daemon for [flavor] that carries a module for [kmi], or null when there is none.
 *
 * A phone whose KMI no entry covers gets null and is told which KMIs there were, rather than being handed
 * a daemon that would fail at `finit_module`.
 */
internal fun List<GenericDaemon>.covering(flavor: KernelSuFlavor, kmi: String?): GenericDaemon? =
    firstOrNull { it.flavor == flavor && it.covers(kmi) }
