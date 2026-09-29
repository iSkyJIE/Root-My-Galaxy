package dev.busung.s25uroot

import androidx.annotation.StringRes

/**
 * Where a run was when it stopped.
 *
 * A message on its own rarely answers "what now?": the same wording can come out of a download,
 * the exploit, or the KernelSU load, and the stage is what tells them apart. It is recorded with
 * every failure, so a run that ended an hour ago still says where it ended.
 */
/**
 * The step a run stopped in, whichever flow it was.
 *
 * Two flows, two vocabularies, and they are not interchangeable. The payload flow's steps are about resolving
 * and carrying a payload - `Transport`, `Target`, `Download` - while the universal root resolves no target and
 * transports nothing: its steps are its own four, and a failure that named one flow's step for the other's run
 * says something that did not happen, which is what the universal root did while it borrowed [RunStage].
 *
 * What everything else needs of a stage is the whole of this interface, and it is only two things: a name to
 * show, and which of the run's four steps it belongs to. The failure card and the history line read the first;
 * the step marks and the progress bar read the second.
 *
 * An interface rather than one enumeration with both sets in it, because the alternative lets a payload
 * failure name a universal step - a compile error here is a wrong sentence on a screen otherwise.
 */
sealed interface FailureStage {
    /**
     * The constant's own name, which is what a record stores.
     *
     * Declared here so a stored stage is written and read through the interface rather than through one
     * implementation: an enum already has this member, so every implementor satisfies it for free - and the two
     * vocabularies' names do not collide (`Transport` against `Support`), which is what lets one field in a
     * record hold either.
     */
    val name: String

    /** The step's name, for the failure card and the history line. */
    val label: Int

    /** Which of the run's four steps this is, counted from zero. */
    val stepIndex: Int

    companion object {
        /**
         * The stage a stored name is, searching both vocabularies.
         *
         * By name rather than by position: the two enumerations are different lengths, and a record written by
         * one build has to be read back by the next. A name in neither is a record from a build that had a
         * step this one does not, which reads as "no stage" rather than as a guess.
         */
        fun fromName(name: String?): FailureStage? = name?.let { sought ->
            RunStage.entries.firstOrNull { it.name == sought }
                ?: UniversalStage.entries.firstOrNull { it.name == sought }
        }
    }
}

/** The payload flow's stages: how it got to the device, and what it carried there. */
enum class RunStage(
    @StringRes override val label: Int,
    override val stepIndex: Int,
) : FailureStage {
    Transport(R.string.stage_transport, 0),
    Target(R.string.stage_target, 0),
    Download(R.string.stage_download, 1),
    Exploit(R.string.stage_exploit, 2),
    KernelSu(R.string.stage_kernel_su, 3),
    Verify(R.string.stage_verify, 3),
}

/**
 * The universal root's stages, which are its four steps and nothing else.
 *
 * There are no more because there is nothing else it does: it checks the phone, resolves and stages a daemon,
 * runs the chain, and loads what the chain installed. No target to resolve, no transport to choose, no payload
 * to verify against a catalog - and a stage for any of those would be a step this flow does not have.
 */
enum class UniversalStage(
    @StringRes override val label: Int,
    override val stepIndex: Int,
) : FailureStage {
    Support(R.string.universal_stage_support, 0),
    Payload(R.string.universal_stage_payload, 1),
    // `Chain` rather than `Exploit`, and the difference is not cosmetic: both vocabularies are stored by name,
    // so a second `Exploit` would make [FailureStage.fromName] resolve a universal failure to the payload
    // flow's step - the same wrong sentence this whole change is about, arriving through the record instead of
    // through a screen. Its label is still the flow's own words for the step.
    Chain(R.string.universal_stage_exploit, 2),
    // The one stage where the two vocabularies agree, and it is not a borrowed word: loading KernelSU is the
    // same step in both flows and [RunStage.KernelSu] already says it. A second string with the same sentence
    // is a second thing to keep in step.
    Load(R.string.stage_kernel_su, 3),
}

/**
 * A run that stopped: the stage it stopped in, the reason the app reports, and the tail of what the
 * payload or helper last said. For an exploit failure the last part is usually the only evidence
 * that says *which* part of the payload gave up, since the payload's own lines are the only account
 * of the kernel race.
 */
data class RunFailure(
    val stage: FailureStage,
    val reason: String,
    val evidence: List<String> = emptyList(),
    /**
     * True when the protection this run set up is what refused the write that ended it.
     *
     * Carried on the failure rather than worked out where it is shown, because the evidence on the
     * card is only the last few lines and the rule needs the whole log. The screen also has to be able
     * to offer the fix, and this is the one failure whose fix is a switch in this app.
     */
    val readOnlyWall: Boolean = false,
    /**
     * Why a retry in this boot cannot be offered, when it cannot.
     *
     * Carried on the failure because it changes what can be offered next, and it is a fact about the
     * run rather than about its message: an attempt in this boot either would put a second payload on
     * top of one that may still be running, or would be refused the pipe pages the exploit needs. The
     * two have the same consequence - the only answer that clears the boot is a restart - and naming
     * the cause is what lets the screen say which one it is instead of refusing for no stated reason.
     */
    val inBootRetryBlocked: InBootRetryBlock? = null,
) {
    companion object {
        /**
         * A failure whose reason is reduced to one short line by [failureSummary].
         *
         * Going through here rather than through the constructor is what keeps the card readable:
         * a message that arrives carrying a log is turned into a cause, not rendered as one.
         */
        fun of(
            stage: FailureStage,
            reason: String,
            evidence: List<String> = emptyList(),
            readOnlyWall: Boolean = false,
            inBootRetryBlocked: InBootRetryBlock? = null,
        ): RunFailure =
            RunFailure(stage, failureSummary(reason), evidence, readOnlyWall, inBootRetryBlocked)
    }
}

/**
 * Why a failed run cannot offer a retry in the boot it failed in.
 *
 * Each carries the notice the screen shows, because the screen's question is "why is there no retry
 * here?" and the answer differs: one is a payload that may still be writing to the kernel, the other
 * is a budget the kernel has already given away until the next boot.
 */
enum class InBootRetryBlock(@StringRes val notice: Int) {
    /** The payload could not be confirmed stopped, so a retry would be a second payload. */
    PayloadMayStillRun(R.string.install_payload_may_still_run),

    /** The boot's pipe page budget is spent, and a restart is what refills it. */
    PipeBudgetSpent(R.string.install_pipe_budget_spent),
}

/**
 * The meaningful tail of a run log. Blank lines are dropped and long lines are clipped, because
 * payload output is printed to a narrow monospace view and an unclipped line there pushes the rest
 * out of sight.
 */
internal fun failureEvidence(log: String, maxLines: Int = 4, maxLength: Int = 160): List<String> =
    log.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
        .takeLast(maxLines)
        .map { line -> if (line.length <= maxLength) line else line.take(maxLength - 1) + "\u2026" }

/**
 * A reason as one short line.
 *
 * The reason is rendered as the cause under the failed stage, in a card, so anything long enough to
 * be a log belongs in the log instead: a message that carried a payload's whole output turned the
 * failure card into a page of text with the stage nowhere in sight. Anything after the first line is
 * dropped here rather than there, so a message can never do that again, and the payload's own lines
 * still reach the card as [failureEvidence] and the log.
 */
internal fun failureSummary(reason: String, maxLength: Int = 240): String {
    val line = reason.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    return if (line.length <= maxLength) line else line.take(maxLength - 1) + "\u2026"
}

/** Signal names for the codes a process killed by a signal reports through its exit status. */
private val SIGNAL_NAMES = mapOf(
    4 to "SIGILL",
    6 to "SIGABRT",
    7 to "SIGBUS",
    8 to "SIGFPE",
    9 to "SIGKILL",
    11 to "SIGSEGV",
    13 to "SIGPIPE",
    15 to "SIGTERM",
)

/**
 * What a shell reports as a signal rather than an exit code, read back.
 *
 * `128 + n` is how a killed process is reported by a shell, which the app then sees as an exit code.
 * Saying `137` tells nobody anything; saying that the payload was killed by signal 9 is the single
 * most useful thing a run can report about a payload that died without choosing to.
 */
/**
 * The codes that mean a command did not *run*, rather than running and failing.
 *
 * These are the shell's own, and they are a different class from a program's: `1` is a program that ran and
 * said no, while these are the ways it never started. `255` is the one that matters in practice - it is what a
 * refused or absent privileged spawn returns - and until now it was the only common failure the summary could
 * not explain, so it read as a mystery instead of as "nothing was executed". 126 and 127 come along because
 * they are the same class and were in the same blind spot.
 */
private val SHELL_EXIT = mapOf(
    126 to "the command was found but could not be executed",
    127 to "the command was not found",
    255 to "the command could not be run: a refused or unavailable privilege, or nothing to execute",
)

internal fun exitCodeSummary(exitCode: Int): String? {
    // Asked before the signal range, because these codes can never be signals and the range check below is the
    // wrong question for them.
    SHELL_EXIT[exitCode]?.let { return it }
    val signal = exitCode - 128
    if (exitCode !in 129..192 || signal <= 0) return null
    val name = SIGNAL_NAMES[signal]
    return if (name != null) "signal $signal ($name)" else "signal $signal"
}

/**
 * The short account of a payload that stopped, for the exit message.
 *
 * The payload's whole output used to be inlined here, which is what made a failed run unreadable.
 * Only the reading of the status survives, because the payload's lines are in the log and, clipped,
 * on the failure card. Kept in the shape the message already expects, so every translation stays
 * valid.
 */
internal fun payloadExitDetail(exitCode: Int): String =
    exitCodeSummary(exitCode)?.let { " \u2014 $it" }.orEmpty()
