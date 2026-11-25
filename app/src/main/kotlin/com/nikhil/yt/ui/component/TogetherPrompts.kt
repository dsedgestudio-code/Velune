package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.nikhil.yt.R
import com.nikhil.yt.together.TogetherCode
import com.nikhil.yt.together.TogetherController

/**
 * The two moments Listen Together asks before doing anything: opening a join link, and an invite from
 * a saved person. Nobody joins a session without saying yes.
 */
@Composable
fun TogetherPrompts(
    controller: TogetherController,
    linkCode: String?,
    displayName: String,
    onLinkHandled: () -> Unit,
) {
    if (linkCode != null) {
        AlertDialog(
            onDismissRequest = onLinkHandled,
            title = { Text(stringResource(R.string.together_join_title)) },
            text = { Text(stringResource(R.string.together_join_link_body, TogetherCode.display(linkCode))) },
            confirmButton = {
                TextButton(onClick = { controller.join(linkCode, displayName); onLinkHandled() }) {
                    Text(stringResource(R.string.together_join_action))
                }
            },
            dismissButton = { TextButton(onClick = onLinkHandled) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val invite by controller.invite.collectAsState()
    invite?.let { pending ->
        AlertDialog(
            onDismissRequest = {}, // an invite needs a real answer, so the sender hears back
            title = { Text(stringResource(R.string.together_invite_title, pending.person.nickname)) },
            text = { Text(stringResource(R.string.together_invite_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { controller.answerInvite(accept = true, displayName = displayName) }) {
                    Text(stringResource(R.string.together_join_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.answerInvite(accept = false, displayName = displayName) }) {
                    Text(stringResource(R.string.together_decline))
                }
            },
        )
    }

    val recap by controller.recap.collectAsState()
    recap?.let { RecapDialog(it, onDismiss = controller::dismissRecap) }
}

/** When a session ends: the card itself (the same image you'd share), then Share or Done. */
@Composable
private fun RecapDialog(recap: com.nikhil.yt.together.TogetherRecap, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = (com.nikhil.yt.ui.player.ArtworkPalette.colors.firstOrNull()
        ?: androidx.compose.material3.MaterialTheme.colorScheme.primary).toArgb()
    val card by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, recap) {
        value = runCatching { com.nikhil.yt.utils.ShareCards.recap(context, recap, accent) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            card?.let {
                androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.together_recap_title),
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 5f)
                        .clip(RoundedCornerShape(20.dp)),
                )
            } ?: Text(stringResource(R.string.together_recap_title))
        },
        confirmButton = {
            TextButton(onClick = { card?.let { com.nikhil.yt.utils.ShareCards.share(context, it, "spectra_session") } }, enabled = card != null) {
                Text(stringResource(R.string.together_recap_share))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
