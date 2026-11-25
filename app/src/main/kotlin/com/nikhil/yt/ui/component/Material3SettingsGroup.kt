/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A Material 3 Expressive style settings group component
 * @param title The title of the settings group
 * @param items List of settings items to display
 */
@Composable
fun Material3SettingsGroup(
    title: String? = null,
    items: List<Material3SettingsItem>
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Section title
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp, top = 20.dp)
            )
        }
        
        // Settings card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    Material3SettingsItemRow(
                        item = item,
                        showDivider = index < items.size - 1
                    )
                }
            }
        }
    }
}

/**
 * Individual settings item row with Material 3 styling
 */
@Composable
private fun Material3SettingsItemRow(
    item: Material3SettingsItem,
    showDivider: Boolean
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = item.onClick != null,
                    onClick = { item.onClick?.invoke() }
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item.icon?.let { icon ->
                val tint = if (item.isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                BadgedBox(badge = { if (item.showBadge) Badge(containerColor = MaterialTheme.colorScheme.error) }) {
                    Icon(painter = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(20.dp))
            }

            // Title and description
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Title content (can be Text or custom composable)
                item.title()
                
                // Description if provided
                item.description?.let { desc ->
                    Spacer(modifier = Modifier.height(2.dp))
                    desc()
                }
            }
            
            // Trailing content
            item.trailingContent?.let { trailing ->
                Spacer(modifier = Modifier.width(8.dp))
                trailing()
            }
        }
        
        // Divider
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(
                    start = if (item.icon != null) 64.dp else 20.dp,
                    end = 20.dp
                ),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * Data class for Material 3 settings item
 */
data class Material3SettingsItem(
    val icon: Painter? = null,
    val title: @Composable () -> Unit,
    val description: (@Composable () -> Unit)? = null,
    val trailingContent: (@Composable () -> Unit)? = null,
    val showBadge: Boolean = false,
    val isHighlighted: Boolean = false,
    val onClick: (() -> Unit)? = null
)