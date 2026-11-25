package com.nikhil.yt.ui.component

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.constants.CONTENT_TYPE_SONG
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.ui.utils.ItemWrapper
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/** A list's saved "Custom order", stored as song ids. Songs not in it (newly added) go to the end. */
class CustomSongOrder(private val prefs: SharedPreferences, private val key: String) {
    fun <T> sort(items: List<T>, id: (T) -> String): List<T> {
        val saved = prefs.getString(key, null)?.split(',') ?: return items
        val position = saved.withIndex().associate { (index, songId) -> songId to index }
        return items.sortedBy { position[id(it)] ?: Int.MAX_VALUE }
    }

    fun save(ids: List<String>) = prefs.edit().putString(key, ids.joinToString(",")).apply()
}

/** [key] must be unique per list, or two screens overwrite each other's order. */
@Composable
fun rememberCustomSongOrder(key: String): CustomSongOrder {
    val context = LocalContext.current
    return remember(key) {
        CustomSongOrder(context.getSharedPreferences("custom_song_order", Context.MODE_PRIVATE), key)
    }
}

/**
 * Drag-to-reorder for a LazyColumn whose song rows are keyed by song id (see [reorderableSongItems]).
 * Moves are matched by key, so header rows above the songs don't matter. Saves when the drag ends.
 */
@Composable
fun rememberSongReorderState(
    lazyListState: LazyListState,
    songs: MutableList<ItemWrapper<Song>>,
    order: CustomSongOrder,
): ReorderableLazyListState {
    var moved by remember { mutableStateOf(false) }
    val state = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scrollThresholdPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    ) { from, to ->
        val fromIndex = songs.indexOfFirst { it.item.id == from.key }
        val toIndex = songs.indexOfFirst { it.item.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            songs.add(toIndex, songs.removeAt(fromIndex))
            moved = true
        }
    }
    LaunchedEffect(state.isAnyItemDragging) {
        if (!state.isAnyItemDragging && moved) {
            order.save(songs.map { it.item.id })
            moved = false
        }
    }
    return state
}

/** Song rows shared by the song lists: drag handle in Custom order, overflow menu, tap and long-press selection. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.reorderableSongItems(
    songs: List<ItemWrapper<Song>>,
    reorderState: ReorderableLazyListState,
    canDrag: Boolean,
    selection: Boolean,
    activeId: String?,
    isPlaying: Boolean,
    onClick: (ItemWrapper<Song>) -> Unit,
    onLongClick: (ItemWrapper<Song>) -> Unit,
    onMenu: (Song) -> Unit,
) {
    items(songs, key = { it.item.id }, contentType = { CONTENT_TYPE_SONG }) { wrapper ->
        val haptic = LocalHapticFeedback.current
        ReorderableItem(reorderState, key = wrapper.item.id, enabled = canDrag) {
            SongListItem(
                song = wrapper.item,
                showInLibraryIcon = true,
                isActive = wrapper.item.id == activeId,
                isPlaying = isPlaying,
                isSelected = wrapper.isSelected && selection,
                trailingContent = {
                    if (canDrag) {
                        IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                            Icon(painterResource(R.drawable.drag_handle), contentDescription = null)
                        }
                    }
                    IconButton(onClick = { onMenu(wrapper.item) }) {
                        Icon(painterResource(R.drawable.more_vert), contentDescription = null)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onClick(wrapper) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick(wrapper)
                        },
                    )
                    .animateItem(),
            )
        }
    }
}
