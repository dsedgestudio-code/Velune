/*
 * Velune - by Nikhil
 * Spectra - Listen Together rebuild
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.TogetherAllowGuestsToAddTracksKey
import com.nikhil.yt.constants.TogetherAllowGuestsToControlPlaybackKey
import com.nikhil.yt.constants.TogetherDisplayNameKey
import com.nikhil.yt.constants.TogetherRequireHostApprovalToJoinKey
import com.nikhil.yt.together.SavedPerson
import com.nikhil.yt.together.TogetherCode
import com.nikhil.yt.together.TogetherController
import com.nikhil.yt.together.TogetherParticipant
import com.nikhil.yt.together.TogetherRoomSettings
import com.nikhil.yt.together.TogetherSessionState
import com.nikhil.yt.ui.component.PreferenceGroupTitle
import com.nikhil.yt.ui.component.SwitchPreference
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicTogetherScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val controller = LocalPlayerConnection.current?.service?.together
    val (name, setName) = rememberPreference(TogetherDisplayNameKey, Build.MODEL ?: "")
    val (allowAdd, setAllowAdd) = rememberPreference(TogetherAllowGuestsToAddTracksKey, true)
    val (allowControl, setAllowControl) = rememberPreference(TogetherAllowGuestsToControlPlaybackKey, false)
    val (requireApproval, setRequireApproval) = rememberPreference(TogetherRequireHostApprovalToJoinKey, false)
    val settings = TogetherRoomSettings(allowAdd, allowControl, requireApproval)

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        if (controller == null) {
            Text(
                stringResource(R.string.together_needs_player),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            val state by controller.state.collectAsState()
            val people by controller.people.collectAsState()
            when (val current = state) {
                TogetherSessionState.Idle, is TogetherSessionState.Error -> StartSession(
                    controller = controller,
                    error = (current as? TogetherSessionState.Error)?.message,
                    name = name,
                    onNameChange = setName,
                    people = people,
                    settings = settings,
                )
                TogetherSessionState.Starting -> Working(stringResource(R.string.together_starting))
                is TogetherSessionState.Joining -> Working(stringResource(R.string.together_finding, TogetherCode.display(current.code)))
                is TogetherSessionState.Hosting -> Hosting(controller, current, people, name, settings)
                is TogetherSessionState.Joined -> Joined(controller, current, people)
            }

            PreferenceGroupTitle(stringResource(R.string.together_session_rules))
            SwitchPreference(
                title = { Text(stringResource(R.string.together_allow_guests_add)) },
                checked = allowAdd,
                onCheckedChange = { setAllowAdd(it); controller.updateSettings(settings.copy(allowGuestsToAddTracks = it)) },
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.together_allow_guests_control)) },
                checked = allowControl,
                onCheckedChange = { setAllowControl(it); controller.updateSettings(settings.copy(allowGuestsToControlPlayback = it)) },
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.together_ask_before_joining)) },
                description = stringResource(R.string.together_ask_before_joining_desc),
                checked = requireApproval,
                onCheckedChange = { setRequireApproval(it); controller.updateSettings(settings.copy(requireHostApprovalToJoin = it)) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.together_title)) },
        navigationIcon = {
            com.nikhil.yt.ui.component.IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.back_button_desc))
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun StartSession(
    controller: TogetherController,
    error: String?,
    name: String,
    onNameChange: (String) -> Unit,
    people: List<SavedPerson>,
    settings: TogetherRoomSettings,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val parsed = TogetherCode.parse(code)
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned -> TogetherCode.parse(scanned)?.let { controller.join(it, name) } }
    }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(stringResource(R.string.together_hero_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.together_hero_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = controller::dismissError) {
                        Icon(painterResource(R.drawable.close), stringResource(R.string.dismiss), tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SoftField(value = name, onValueChange = onNameChange, label = stringResource(R.string.together_display_name))
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { controller.startHosting(name, settings) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.together_start_session), style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.together_join_with_code), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftField(
                value = code,
                onValueChange = { code = it.take(24) },
                label = stringResource(R.string.together_code_label),
                placeholder = "XXXX-XXXX",
                capitalize = true,
                onDone = { parsed?.let { controller.join(it, name) } },
                modifier = Modifier.weight(1f),
                trailing = {
                    IconButton(onClick = {
                        scan.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    }) {
                        Icon(painterResource(R.drawable.qr_code_scanner), stringResource(R.string.together_scan_qr))
                    }
                },
            )
            FilledTonalButton(onClick = { parsed?.let { controller.join(it, name) } }, enabled = parsed != null, modifier = Modifier.height(56.dp)) {
                Text(stringResource(R.string.together_join_action))
            }
        }
    }

    if (people.isNotEmpty()) {
        PreferenceGroupTitle(stringResource(R.string.together_saved_people))
        people.forEach { person ->
            PersonRow(
                person = person,
                controller = controller,
                action = stringResource(R.string.together_invite),
                onAction = { controller.invite(person, name, settings) },
            )
        }
    }
}

@Composable
private fun Hosting(
    controller: TogetherController,
    hosting: TogetherSessionState.Hosting,
    people: List<SavedPerson>,
    name: String,
    settings: TogetherRoomSettings,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val link = TogetherCode.link(hosting.code)
    val qr = remember(link) { runCatching { BarcodeEncoder().encodeBitmap(link, BarcodeFormat.QR_CODE, 560, 560) }.getOrNull() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(
                when {
                    hosting.lan && hosting.online -> R.string.together_reach_both
                    hosting.online -> R.string.together_reach_online
                    else -> R.string.together_reach_lan
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        qr?.let { QrCode(it) }
        Spacer(Modifier.height(20.dp))
        Text(
            TogetherCode.display(hosting.code),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Spectra", TogetherCode.display(hosting.code)))) }
            }) { Text(stringResource(R.string.together_copy_code)) }
            Button(onClick = {
                val text = context.getString(R.string.together_share_text, TogetherCode.display(hosting.code), link)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }) { Text(stringResource(R.string.share)) }
        }
    }

    val participants = hosting.roomState?.participants.orEmpty()
    PreferenceGroupTitle(stringResource(R.string.together_listening_now, participants.size.coerceAtLeast(1)))
    participants.forEach { participant ->
        ParticipantRow(participant, people, controller, isHostView = true)
    }

    val inSession = participants.mapNotNull { it.clientId }.toSet()
    val invitable = people.filter { it.clientId !in inSession }
    if (invitable.isNotEmpty()) {
        PreferenceGroupTitle(stringResource(R.string.together_invite_saved))
        invitable.forEach { person ->
            PersonRow(person, controller, stringResource(R.string.together_invite)) { controller.invite(person, name, settings) }
        }
    }

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = controller::leave, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.together_end_session), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Joined(
    controller: TogetherController,
    joined: TogetherSessionState.Joined,
    people: List<SavedPerson>,
) {
    val host = joined.hostName ?: stringResource(R.string.together_role_host)
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            stringResource(if (joined.isPending) R.string.together_waiting_for else R.string.together_listening_with, host),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (joined.overLan) R.string.together_via_lan else R.string.together_via_online),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!joined.isPending) {
        PreferenceGroupTitle(stringResource(R.string.together_listening_now, joined.roomState.participants.size.coerceAtLeast(1)))
        joined.roomState.participants.forEach { participant ->
            ParticipantRow(participant, people, controller, isHostView = false, isSelf = participant.id == joined.selfParticipantId)
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = controller::leave, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.together_leave_session), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ParticipantRow(
    participant: TogetherParticipant,
    people: List<SavedPerson>,
    controller: TogetherController,
    isHostView: Boolean,
    isSelf: Boolean = false,
) {
    val saved = people.firstOrNull { it.clientId == participant.clientId }
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val canSave = !isSelf && saved == null && !participant.clientId.isNullOrBlank() && !(isHostView && participant.isHost)

    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(saved?.nickname ?: participant.name)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(saved?.nickname ?: participant.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = when {
                participant.isHost -> stringResource(R.string.together_role_host)
                participant.isPending -> stringResource(R.string.together_wants_in)
                isSelf -> stringResource(R.string.together_you)
                saved != null -> participant.name
                else -> null
            }
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        when {
            isHostView && participant.isPending -> {
                TextButton(onClick = { controller.approve(participant.id, false) }) { Text(stringResource(R.string.together_decline)) }
                FilledTonalButton(onClick = { controller.approve(participant.id, true) }) { Text(stringResource(R.string.together_let_in)) }
            }
            canSave || (isHostView && !participant.isHost) -> Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.more_vert), stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (canSave) DropdownMenuItem(text = { Text(stringResource(R.string.together_save_person)) }, onClick = { menu = false; naming = true })
                    if (isHostView && !participant.isHost) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.together_remove)) }, onClick = { menu = false; controller.kick(participant.id) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.together_block)) }, onClick = { menu = false; controller.ban(participant.id) })
                    }
                }
            }
        }
    }

    if (naming) {
        NicknameDialog(
            initial = participant.name,
            onDismiss = { naming = false },
            onSave = { nickname -> controller.savePerson(participant.clientId.orEmpty(), nickname, participant.name); naming = false },
        )
    }
}

@Composable
private fun PersonRow(
    person: SavedPerson,
    controller: TogetherController,
    action: String,
    onAction: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(person.nickname)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(person.nickname, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (person.name.isNotBlank() && person.name != person.nickname) {
                Text(person.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        FilledTonalButton(onClick = onAction) { Text(action) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.more_vert), stringResource(R.string.more_options)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.together_rename)) }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.together_forget)) }, onClick = { menu = false; controller.forgetPerson(person.clientId) })
            }
        }
    }
    if (renaming) {
        NicknameDialog(
            initial = person.nickname,
            onDismiss = { renaming = false },
            onSave = { controller.renamePerson(person.clientId, it); renaming = false },
        )
    }
}

@Composable
private fun NicknameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var nickname by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.together_nickname_title)) },
        text = {
            Column {
                Text(stringResource(R.string.together_nickname_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                SoftField(value = nickname, onValueChange = { nickname = it.take(40) }, label = stringResource(R.string.together_nickname_label), onDone = { onSave(nickname) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(nickname) }, enabled = nickname.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Working(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun QrCode(bitmap: Bitmap) {
    // White quiet zone so any camera reads it, whatever the theme.
    Box(
        Modifier
            .size(216.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .padding(12.dp),
    ) {
        Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.together_qr_description), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Avatar(name: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SoftField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    capitalize: Boolean = false,
    onDone: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        trailingIcon = trailing,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = if (capitalize) KeyboardCapitalization.Characters else KeyboardCapitalization.Words,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier,
    )
}
