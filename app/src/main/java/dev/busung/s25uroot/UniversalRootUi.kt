package dev.busung.s25uroot

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The universal root's one entry on the home screen.
 *
 * This is an **entry point and nothing else**. The run itself is the install screen — the same four steps,
 * progress bar, log and Stop button as a payload run — because it is the same kind of thing to a person and
 * a second screen beside it would be a second thing to learn. Tapping here asks one question ("start it?"),
 * and the run's own account is on the screen that already knows how to give one.
 *
 * It is a path of its own rather than a mode of the install card above it, because the two start from
 * opposite ends of the phone: that card's run needs the system-uid helper, and the helper needs a first
 * temporary root to have put it there. This one needs none of that — it runs from this app through an
 * unprivileged `IpSecManager` transform, so it is the one thing here that works on a phone with nothing
 * installed at all. See [UniversalRootRun] for the chain and `app/src/main/cpp/dfroot/` for the native half.
 */
@Composable
internal fun UniversalRootCard() {
    val context = LocalContext.current
    var confirming by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { confirming = true },
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

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.universal_root_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.universal_root_confirm_body))
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
                            confirming = false
                            // The run screen, asked for with one extra. Nothing else travels: the run reads
                            // the phone itself, and the daemon it needs is in this APK.
                            context.startActivity(
                                Intent(context, InstallActivity::class.java)
                                    .putExtra(InstallActivity.EXTRA_UNIVERSAL, true),
                            )
                        },
                        AppAction(R.string.action_cancel) { confirming = false },
                    ),
                )
            },
        )
    }
}
