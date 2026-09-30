package dev.busung.s25uroot

/**
 * Which flow a run is.
 *
 * The app has two ways to obtain root and they are not variations of one another. A **payload run** resolves
 * a device-specific payload, goes through the helper or Shizuku, and stages what the feed carries for this
 * phone; the **universal root** uses none of that - it carries its own chain in this APK, needs no helper and
 * downloads only a daemon. What they share is a shape: four steps, a progress bar, a log and one Stop. What
 * they do not share is any of the words, and none of the answers.
 *
 * So the flow is a value, carried by the run and written into its record, and everything flow-specific reads
 * it rather than assuming:
 *
 * - the step list, because "Download / Load the support list" describes a run that fetches a payload;
 * - the notification's chip, which names the stage in the shade;
 * - the bar's terminal answers, because the payload flow's Retry starts **a payload run** - which on a
 *   universal failure is the one thing that must not happen, and did;
 * - a run read back from its record, which has to be described as the run it was.
 *
 * It is deliberately not a boolean named after one of the two flows. `universal: Boolean` was that, and it
 * made the payload flow the unstated default of every branch - which is how a universal run came to be
 * offered the other flow's retry, and how its record came to have nothing to be read back as.
 */
enum class RunKind {
    /** A payload run: the helper or Shizuku, a device-specific payload, and the regular four steps. */
    Payload,

    /** The universal root: the ported chain, no helper and no Shizuku, and a daemon from the feed. */
    Universal,
    ;

    /**
     * What this flow is called wherever one has to be named.
     *
     * The exploit and its CVE, not a description of the flow. "Universal root" was the description, and it
     * claimed something about the phone that is not true: this chain needs a payload like every other run here
     * - what it does not need is a helper. The CVE says what the run actually spent its time on, in words no
     * language has to translate and no one has to invent, and it cannot drift the way a second name for the same
     * thing does.
     *
     * Kept on the flow, rather than in a string resource per surface, because three surfaces now show it and
     * they have to agree: the history row a run writes, the subtext of its notification, and the group it was
     * picked from in the sheet. It is deliberately not localized - a CVE number and an exploit's name are the
     * same in every language, which is most of why they are the names.
     *
     * The helper flow is *not* named here and keeps its own words ("System UID install"), even though it roots
     * through the same DirtyFrag bug: it is not a [RunKind] - it is a stage inside a payload run - and a name
     * that said "DirtyFrag" about both would leave two very different flows sharing one label.
     */
    val flowName: String
        get() = when (this) {
            Payload -> KernelVulnerability.CVE
            Universal -> "${UniversalRootRun.EXPLOIT_NAME} (${UniversalRootRun.CVE})"
        }

    companion object {
        /**
         * The kind a name is, defaulting to [Payload].
         *
         * The default is what a record written before this field existed becomes, and it is the right one for
         * those: every run the app had recorded until then was a payload run. An unreadable name is treated the
         * same way, because the alternative - refusing the record - would hide a run that happened.
         */
        fun fromName(name: String?): RunKind = entries.firstOrNull { it.name == name } ?: Payload
    }
}

/**
 * What a universal root run is set to do: which KernelSU, and which payload tier.
 *
 * The two answers the card asks for, kept as one value because they are only meaningful together - a retry
 * armed for one flavour and the other tier's daemon would install a root nobody chose. It is what the run
 * records when it resolves, and therefore what an armed retry runs on the following boot: the run that
 * resolved it is over, and often in a different boot, by the time anything wants to repeat it.
 */
data class UniversalPlan(val flavor: KernelSuFlavor, val tier: PayloadTier)
