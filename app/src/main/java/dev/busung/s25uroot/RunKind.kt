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
