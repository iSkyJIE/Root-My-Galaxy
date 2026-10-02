package dev.busung.s25uroot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which post-root repair action is being run.
 *
 * Ordered by what each one costs, which is also the order the settings list shows them in: a module
 * reload changes nothing that is running, a framework restart closes every app, and the reboot ends
 * the session. The cheapest one is first because it is the one worth trying before the others.
 */
internal enum class RecoveryTool {
    ReloadModules,
    RestartZygote,
    SoftReboot,
    RebootAndUnroot,
}

/**
 * The widest shell this device will give the app, which is what decides whether an action can run.
 *
 * The tiers are not interchangeable, and naming them is the point. A root shell can do all four
 * actions. The plain shell a running Shizuku server offers is uid 2000, and the `shell` user may
 * reboot the phone - that is how `adb reboot` works - but may not restart the Android userspace,
 * re-apply the module lifecycle, or ask KernelSU for a soft reboot, because those go through `ctl.*`
 * properties and the daemon's own privileged channel.
 *
 * Before this, "no root" was one answer for all four actions, so the one a shell can do was refused
 * with the advice to reboot the phone by hand - while Shizuku, already running, could have done it.
 */
internal enum class ShellTier {
    Root,
    Unprivileged,
    None,
}

/** Which tier the device offers, from the two transports having been asked. Root wins when both answer. */
internal fun shellTier(rootReachable: Boolean, unprivilegedReachable: Boolean): ShellTier = when {
    rootReachable -> ShellTier.Root
    unprivilegedReachable -> ShellTier.Unprivileged
    else -> ShellTier.None
}

/**
 * Whether this tier can carry out [tool].
 *
 * Only the reboot survives the loss of root, and it survives it intact: the setting that decides
 * whether the phone comes back rooted is this app's own, so it is cleared on disk either way, and what
 * root was needed for was the reboot itself - which the shell user is allowed to ask for.
 */
internal fun ShellTier.canRun(tool: RecoveryTool): Boolean = when (this) {
    ShellTier.Root -> true
    ShellTier.Unprivileged -> tool == RecoveryTool.RebootAndUnroot
    ShellTier.None -> false
}

/**
 * The one place a repair action is actually run.
 *
 * It began as the settings section's own code, and it was pulled out when the run screen wanted the
 * same thing: *Restart userspace* after an install that just loaded KernelSU is the same action as the
 * one in Settings, and two implementations of "ask for a root shell, check the boot, hand it to the
 * child" would be two places for the same three rules to drift apart - which is exactly how the restart
 * ended up with a window shorter than the wait behind it.
 *
 * The root shell is asked for here rather than checked for beforehand: the in-process reading of
 * KernelSU can say no on a device where root is usable, so the refusal has to come from the thing that
 * actually needs it. Which refusal it was is then read from the device rather than from the attempt, so
 * "no root here" and "no root for this app" keep their different fixes.
 *
 * The boot token is part of every action because all three change something the boot owns, and a child
 * that finds the boot has changed under it refuses in its own words.
 */
internal suspend fun runRecoveryAction(context: Context, tool: RecoveryTool): RecoveryOutcome =
    // Every shell here is a real process that is waited on, and two of the actions hold the channel
    // open until the child acknowledges them, so none of it may run on the UI thread.
    withContext(Dispatchers.IO) {
        val bootToken = kernelBootToken()
        // Asked for rather than assumed, and shared with the reboot menu - which has to answer the same
        // question before anything is pressed, so that a row it greys out is greyed out for a reason.
        val tier = currentShellTier()
        val refusalDetail by lazy {
            context.getString(
                when (recoveryRefusalFor(tier, tool, KernelSuRuntime.loadedInThisBoot())) {
                    RecoveryRefusal.RootMissing -> R.string.recovery_root_required
                    RecoveryRefusal.ShellMissing -> R.string.recovery_shell_unavailable
                    RecoveryRefusal.ShizukuNeedsRoot -> R.string.recovery_shizuku_needs_root
                },
            )
        }
        // What this boot actually protected, not what the setting says it would: a refusal can only be
        // the app's doing in a boot where a run set devices read-only, and those are different questions
        // on a phone whose protection is on and whose last run was before the last reboot.
        val protectedDevices = AppPreferences.readOnlyProtectedDevices(context, bootToken)
        val outcome = when {
            !tier.canRun(tool) -> RecoveryOutcome(accepted = false, detail = refusalDetail)
            bootToken == null -> RecoveryOutcome(
                accepted = false,
                detail = context.getString(R.string.error_boot_id),
            )
            // The one action a shell that is not root can still do. Nothing is downgraded about it: the
            // app's own root-on-boot setting is what decides whether the phone comes back rooted, and it
            // is cleared before the request exactly as it is on the root path.
            tier == ShellTier.Unprivileged -> {
                val shell: (String) -> ShizukuController.ShellResult = { command ->
                    KernelSuRuntime.unprivilegedShell(command) ?: ShizukuController.ShellResult(
                        NO_ROOT_SHELL_EXIT,
                        refusalDetail,
                    )
                }
                rebootAndUnrootKeepingTheGates(
                    context = context,
                    shell = shell,
                    bootToken = bootToken,
                    requiresRoot = false,
                )
            }
            else -> {
                val rootShell: (String) -> ShizukuController.ShellResult = { command ->
                    KernelSuRuntime.rootShell(command) ?: ShizukuController.ShellResult(
                        NO_ROOT_SHELL_EXIT,
                        refusalDetail,
                    )
                }
                // Read once for both actions that depend on it: the daemon is the same binary
                // either way, and asking it twice would be two answers to one question.
                val capabilities by lazy {
                    RootRecovery.capabilities(rootShell) ?: KsudCapabilities()
                }
                when (tool) {
                    // A reload is the same shape of change as a load, and the manager that was open
                    // while it happened is showing the state from before it: this is the screen
                    // someone reaches for when the manager says the modules are not there.
                    RecoveryTool.ReloadModules -> {
                        val reload = RootRecovery.reloadModules(
                            shell = rootShell,
                            bootToken = bootToken,
                            capabilities = capabilities,
                        )
                        if (reload.accepted) KernelSuManagerRefresh.afterLoad(context)
                        reload
                    }
                    // The one action whose result the app cannot report when it happens: the restart
                    // ends this process, so the child checks the framework that replaced it and leaves
                    // its record in the app's own files directory for the next run to read.
                    RecoveryTool.RestartZygote -> RootRecovery.restartZygote(
                        shell = rootShell,
                        bootToken = bootToken,
                        reportPath = ZygoteRestartReport.file(context).absolutePath,
                    )
                    RecoveryTool.SoftReboot -> RootRecovery.softReboot(
                        shell = rootShell,
                        bootToken = bootToken,
                        capabilities = capabilities,
                    )
                    RecoveryTool.RebootAndUnroot -> rebootAndUnrootKeepingTheGates(
                        context = context,
                        shell = rootShell,
                        bootToken = bootToken,
                        requiresRoot = true,
                    )
                }
            }
        }
        // The refusal is attributed here rather than by the caller, and only to a wall this boot put
        // up: an EROFS the device would have answered anyway is somebody else's, and naming this switch
        // for it would talk someone out of a protection that is doing its job.
        if (outcome.accepted || protectedDevices <= 0) {
            outcome
        } else if (PartitionReadOnly.refusedByReadOnly(outcome.detail)) {
            outcome.copy(readOnlyWall = true)
        } else {
            outcome
        }
    }

/**
 * Which gates are on after this action has asked the phone to reboot.
 *
 * Pure, because the rule is the whole of what can go wrong here and it is one line otherwise: what was on
 * comes back **only** if the reboot was refused. An earlier version of this cleared the payload gate and
 * restored `true` unconditionally, which meant a phone whose root on boot was off had it switched back on by
 * a *refused* reboot - a refusal being the likely outcome on the device that reaches for this action, since
 * it needs a root shell that a failed run usually does not have. The next boot then rooted a phone nobody had
 * asked to root, with nothing on screen to say why.
 *
 * An accepted request leaves [RootGates.none] rather than "whatever the phone now says", because the gate that
 * was cleared is cleared: the reboot is the point, and the whole promise of this action is that the phone
 * comes back unrooted.
 */
internal fun gatesAfterRebootRequest(was: RootGates, accepted: Boolean): RootGates =
    if (accepted) RootGates.none else was

/**
 * Reboots with every root gate cleared, and puts them back if the reboot was refused.
 *
 * The previous values are read before anything is written, and that is the whole point of this being a
 * function rather than two lines at each call site: they are cleared so a reboot that happened first cannot
 * come back rooted, not so the user's choices can be thrown away.
 *
 * **All three**, which is the half that was missing: the action's promise is that the phone comes back
 * unrooted, and it cleared only the payload gate - so a phone whose root was armed by the helper's gate or by
 * the chain's gate ran this action, rebooted, and came back rooted. The three are written together for the
 * same reason: a gate added later cannot be forgotten here without this function changing, and the restore
 * does not go through the setters because each of those turns the other two off (see [RootGates]).
 */
private suspend fun rebootAndUnrootKeepingTheGates(
    context: Context,
    shell: (String) -> ShizukuController.ShellResult,
    bootToken: String,
    requiresRoot: Boolean,
): RecoveryOutcome {
    val was = AppPreferences.rootGates(context)
    AppPreferences.setRootGates(context, RootGates.none)
    val outcome = RootRecovery.rebootAndUnroot(shell, bootToken, requiresRoot)
    // Written either way, not only on a refusal: an accepted reboot leaves them off, and writing that is what
    // makes the state on disk the state this function decided rather than whatever a screen happened to leave.
    AppPreferences.setRootGates(context, gatesAfterRebootRequest(was, outcome.accepted))
    return outcome
}

/**
 * Asks the phone to reboot through whatever shell this device will give us, and says whether the
 * request was made.
 *
 * A failed run usually has no root to reboot with - that is often the whole reason it failed - and
 * for a long time that meant saying so and leaving the reboot to the user. It does not have to: a
 * running Shizuku server is a shell, the `shell` user may reboot the phone, and that is what
 * [KernelSuRuntime.unprivilegedShell] supplies. So the request is made through a root shell when there
 * is one and through Shizuku's own shell when there is not.
 *
 * False is not a failure of the caller: the retry the caller armed is stored on disk and does not
 * depend on this, and the screen that offers it says what to do by hand when nothing could ask. What
 * must not happen is arming a retry and then reporting a reboot that never happened.
 */
internal suspend fun requestReboot(): Boolean = withContext(Dispatchers.IO) {
    // `svc power reboot` after `reboot`: some builds ship one and not the other, and both mean the
    // same thing to the user. A non-zero exit is not retried beyond that - a reboot that has already
    // been asked for does not need asking twice, and the caller reports what happened either way.
    val commands = listOf("/system/bin/reboot", "/system/bin/svc power reboot")
    fun reboot(shell: (String) -> ShizukuController.ShellResult?): Boolean = commands.any { command ->
        // A reboot takes the transport down with it, so the answer to the command that caused it is not
        // always readable. That is what the detached script above exists for; here the request is made
        // best-effort and the caller reports what it managed.
        runCatching { shell(command)?.exitCode == 0 }.getOrDefault(false)
    }
    reboot { command -> KernelSuRuntime.rootShell(command) } ||
        reboot { command -> KernelSuRuntime.unprivilegedShell(command) }
}
