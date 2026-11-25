/*
 * Velune - by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.layout.Column
import com.nikhil.yt.ui.component.PreferenceGroupTitle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.BuildConfig
import com.nikhil.yt.R
import com.nikhil.yt.viewmodels.HomeViewModel
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VeluneSettingsScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val viewModel: HomeViewModel = hiltViewModel(context as androidx.activity.ComponentActivity)
    val accountName by viewModel.accountName.collectAsState()
    val accountImageUrl by viewModel.accountImageUrl.collectAsState()
    val isLoggedIn = accountName != "Guest" && !accountName.isNullOrEmpty()
    fun go(route: String): () -> Unit = { navController.navigate(route) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_spectra_ghost),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Spectra", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { PreferenceGroupTitle("You") }
            item {
                SettingsRow(
                    title = "Account",
                    description = if (isLoggedIn) accountName.orEmpty() else "Sign in to sync your library",
                    leading = {
                        if (isLoggedIn && accountImageUrl != null) {
                            AsyncImage(model = accountImageUrl, contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
                        } else {
                            SettingsIcon(R.drawable.account)
                        }
                    },
                    onClick = go("settings/account"),
                )
            }
            item { SettingsRow("Listen Together", "Share what you hear, live", { SettingsIcon(R.drawable.multi_user) }, go("settings/music_together")) }

            item { PreferenceGroupTitle("Listening") }
            item { SettingsRow("Player and audio", "Quality, equalizer, crossfade", { SettingsIcon(R.drawable.play) }, go("settings/player")) }
            item { SettingsRow("Appearance", "Theme, colours, player style", { SettingsIcon(R.drawable.palette) }, go("settings/appearance")) }
            item { SettingsRow("Content", "Language, region, explicit songs", { SettingsIcon(R.drawable.language) }, go("settings/content")) }

            item { PreferenceGroupTitle("Connections") }
            item { SettingsRow("Discord", "Show what you play on your profile", { SettingsIcon(R.drawable.discord) }, go("settings/discord")) }
            item { SettingsRow("Integration", "Last.fm, ListenBrainz", { SettingsIcon(R.drawable.integration) }, go("settings/integration")) }

            item { PreferenceGroupTitle("Your data") }
            item { SettingsRow("Privacy", "History and search tracking", { SettingsIcon(R.drawable.security) }, go("settings/privacy")) }
            item { SettingsRow("Storage", "Downloads and cache", { SettingsIcon(R.drawable.storage) }, go("settings/storage")) }
            item { SettingsRow("Backup and restore", "Move your library to a new phone", { SettingsIcon(R.drawable.backup) }, go("settings/backup_restore")) }

            item { Spacer(Modifier.height(8.dp)) }
            item { SettingsRow("About", null, { SettingsIcon(R.drawable.info) }, go("settings/about")) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SettingsIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
}

@Composable
private fun SettingsRow(
    title: String,
    description: String?,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
