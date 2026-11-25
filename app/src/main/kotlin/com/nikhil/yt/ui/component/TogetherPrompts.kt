package com.nikhil.yt.ui.component

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
}
