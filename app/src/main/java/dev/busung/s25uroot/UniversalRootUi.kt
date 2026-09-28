package dev.busung.s25uroot

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The universal root's one entry on the home screen.
 *
 * This is an **entry point and nothing else**. The run itself is the install screen — the same four steps,
 * progress bar, log and Stop button as a payload run — because it is the same kind of thing to a person and
 * a second screen beside it would be a second thing to learn. Tapping here asks two questions and the run's
 * own account is on the screen that already knows how to give one.
 *
 * ## The two questions, and why they are not one
 *
 * **Which KernelSU** decides which root this phone ends up running: three projects, three daemons, three
 * managers, and only one of them can be in the kernel at a time. Asked every time rather than read from the
 * setting, because a one-off install from a phone with nothing on it is a different question with a
 * different right answer; the setting is marked as the recommended one.
 *
 * **Which payload** decides which *build* of that KernelSU is staged — see [PayloadTier]. This one the app
 * genuinely cannot answer for the user, so the dialog shows what each tier actually resolves to before they
 * pick: the entry for this phone if the sources carry one, or the generic daemon and the KMIs it covers.
 * A tier that cannot serve this phone is shown with the reason and is not pressable, which is the point of
 * asking — the alternative is a run that fails after the download, or worse, one that loads a module for
 * somebody else's kernel.
 *
 * Neither answer is a preference the app remembers: both are consumed by the run on the install screen, and
 * what the run resolved is in its log.
 */
@Composable
internal fun UniversalRootCard() {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var flavorChosen by remember { mutableStateOf<KernelSuFlavor?>(null) }
    var confirming by remember { mutableStateOf<Confirmation?>(null) }
    // What the app is set to, only so the picker can mark it as the recommended answer. The run does not read
    // this value - the person's choice does.
    val configured = remember { AppPreferences.kernelsuFlavor(context) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { picking = true },
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.universal_root_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.universal_root_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Which KernelSU, asked every time rather than inherited from the setting.
    //
    // The setting is a statement about the phone - which root it is set up to run - and the regular flow reads
    // it, so it is the right *default* here and it is marked as such. But a one-off install from a phone with
    // nothing on it is a different question with a different right answer, and a run that installed whichever
    // flavour was configured last week, without asking, was a surprise that costs a reboot to undo: only one
    // of the three can be in the kernel at a time.
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.universal_root_pick)) },
            text = { Text(stringResource(R.string.universal_root_pick_body)) },
            confirmButton = {
                AppDialogActions(
                    FLAVOURS.map { flavor ->
                        AppAction(
                            flavor.nameRes,
                            if (flavor == configured) AppActionRole.Priority else AppActionRole.Standard,
                        ) {
                            picking = false
                            flavorChosen = flavor
                        }
                    } + listOf(AppAction(R.string.action_cancel) { picking = false }),
                )
            },
        )
    }

    flavorChosen?.let { flavor ->
        // Which payload, and what each one would actually be. The two resolutions are network reads - the
        // catalog and the generic feed - so they are done once, off the main thread, when the question is
        // asked rather than on every recomposition. Nothing is downloaded here: [UniversalRootRun.plan]
        // resolves and stops, which is what makes it safe to ask twice.
        var probe by remember(flavor) { mutableStateOf<TierProbe?>(null) }
        LaunchedEffect(flavor) {
            probe = TierProbe(
                device = resolveTier(context, flavor, PayloadTier.Device, R.string.universal_tier_device_missing),
                generic = resolveTier(context, flavor, PayloadTier.Generic, R.string.universal_tier_generic_missing),
            )
        }

        val deviceReady = probe?.device is TierAnswer.Ready
        AlertDialog(
            onDismissRequest = { flavorChosen = null },
            title = { Text(stringResource(R.string.universal_tier_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.universal_tier_intro))
                    val current = probe
                    if (current == null) {
                        Text(stringResource(R.string.universal_tier_probing))
                    } else {
                        Text(stringResource(R.string.universal_tier_device_line, current.device.text))
                        Text(stringResource(R.string.universal_tier_generic_line, current.generic.text))
                    }
                }
            },
            confirmButton = {
                AppDialogActions(
                    listOf(
                        AppAction(
                            R.string.universal_tier_device,
                            // The device entry is the recommendation when it exists: its module was built for
                            // the kernel this phone runs, which the generic one cannot promise. When it does
                            // not exist the generic one becomes the loud answer, because it is then the only
                            // one - and a dialog with no pressable loud answer reads as broken.
                            if (deviceReady) AppActionRole.Priority else AppActionRole.Standard,
                            enabled = deviceReady,
                        ) {
                            val answer = probe?.device
                            if (answer is TierAnswer.Ready) {
                                flavorChosen = null
                                confirming = Confirmation(flavor, PayloadTier.Device, answer.description)
                            }
                        },
                        AppAction(
                            R.string.universal_tier_generic,
                            if (deviceReady) AppActionRole.Standard else AppActionRole.Priority,
                            enabled = probe?.generic is TierAnswer.Ready,
                        ) {
                            val answer = probe?.generic
                            if (answer is TierAnswer.Ready) {
                                flavorChosen = null
                                confirming = Confirmation(flavor, PayloadTier.Generic, answer.description)
                            }
                        },
                        AppAction(R.string.action_cancel) { flavorChosen = null },
                    ),
                )
            },
        )
    }

    confirming?.let { chosen ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.universal_root_confirm_title, chosen.flavor.label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The payload that will be staged, named here rather than left to the log: the two tiers
                    // produce runs that look alike on the next screen, and this is the last place before the
                    // phone is patched where the difference is still just a sentence.
                    Text(stringResource(R.string.universal_confirm_payload, chosen.payload))
                    Text(stringResource(R.string.universal_root_confirm_body, chosen.flavor.label))
                    Text(stringResource(R.string.universal_root_confirm_steps))
                }
            },
            // The shared action set, not two buttons of this dialog's own: every question in this app is
            // answered from the same few slots in the same order, and a dialog that lays its own answers out
            // is the one place a person has to read the buttons instead of recognising them.
            confirmButton = {
                AppDialogActions(
                    listOf(
                        AppAction(R.string.action_confirm, AppActionRole.Priority) {
                            confirming = null
                            // The run screen, asked for with the flavour and the tier on the intent. The run
                            // resolves the payload for both, which is what sets the app's flavour - the one
                            // writer this project allows - so the daemon, the package the daemon serves and
                            // the manager installed are all the chosen ones.
                            context.startActivity(
                                Intent(context, InstallActivity::class.java)
                                    .putExtra(InstallActivity.EXTRA_UNIVERSAL, true)
                                    .putExtra(InstallActivity.EXTRA_UNIVERSAL_FLAVOR, chosen.flavor.id)
                                    .putExtra(InstallActivity.EXTRA_UNIVERSAL_TIER, chosen.tier.name),
                            )
                        },
                        AppAction(R.string.action_cancel) { confirming = null },
                    ),
                )
            },
        )
    }
}

/** The two questions' answers, kept together so one screen cannot carry half of a choice. */
private data class Confirmation(
    val flavor: KernelSuFlavor,
    val tier: PayloadTier,
    /** What the tier resolved to, in the words [UniversalRootRun.Plan] describes itself with. */
    val payload: String,
)

/** What one tier resolved to when the question was asked. */
private sealed interface TierAnswer {
    /** What to show for this answer: what the tier is, or why it cannot serve this phone. */
    val text: String

    /** It can serve this phone, and this is what it is. */
    data class Ready(val description: String) : TierAnswer {
        override val text: String get() = description
    }

    /** It cannot, and this is why - the resolution's own sentence, which names what it did find. */
    data class Missing(val because: String) : TierAnswer {
        override val text: String get() = because
    }
}

private data class TierProbe(val device: TierAnswer, val generic: TierAnswer)

/**
 * Resolves one tier, without downloading anything, and turns a refusal into an answer rather than a crash.
 *
 * A tier that cannot serve this phone is the normal case for one of the two on most devices - a phone nobody
 * has ported has no device entry, and a phone on a kernel the generic daemons do not cover has no generic one
 * - so this is a question with an answer, not an error. The message the refusal carries is kept: it names
 * what the feed did have, which is what tells a person whether to wait for a port or pick the other tier.
 */
private suspend fun resolveTier(
    context: Context,
    flavor: KernelSuFlavor,
    tier: PayloadTier,
    @androidx.annotation.StringRes fallback: Int,
): TierAnswer = withContext(Dispatchers.IO) {
    runCatching { UniversalRootRun.plan(context, flavor, tier) }.fold(
        onSuccess = { TierAnswer.Ready(it.description) },
        onFailure = { TierAnswer.Missing(it.message ?: context.getString(fallback)) },
    )
}

/** The three this app offers, in the order the picker shows them. */
private val FLAVOURS = listOf(
    KernelSuFlavor.KernelSu,
    KernelSuFlavor.KernelSuNext,
    KernelSuFlavor.ReSukiSU,
)

/** The name alone, for a button - the flavour's own `label` is prose for a log line, not a resource. */
private val KernelSuFlavor.nameRes: Int
    get() = when (this) {
        KernelSuFlavor.KernelSu -> R.string.flavor_kernelsu_name
        KernelSuFlavor.KernelSuNext -> R.string.flavor_kernelsu_next_name
        KernelSuFlavor.ReSukiSU -> R.string.flavor_resukisu_name
    }
