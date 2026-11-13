package me.piyaphat.kei.ui.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.piyaphat.kei.R
import me.piyaphat.kei.data.api.Series
import me.piyaphat.kei.data.api.stableImageUrl
import me.piyaphat.kei.ui.common.messageResource
import me.piyaphat.kei.ui.common.formatChapterNumber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onSeries: (Long) -> Unit,
    onReader: (Long, Int?) -> Unit,
    onCover: (Long) -> Unit,
    onUpload: () -> Unit,
    vm: LibraryViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val largeText = LocalDensity.current.fontScale >= 1.6f
    val undoLabel = stringResource(R.string.undo)
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    var selected by remember { mutableStateOf<Series?>(null) }
    var rename by remember { mutableStateOf<Series?>(null) }
    var delete by remember { mutableStateOf<Series?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    LaunchedEffect(vm) {
        vm.events.collect { snackbar.showSnackbar(context.getString(it.messageResource())) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = {
        ExtendedFloatingActionButton(
            expanded = !grid.isScrollInProgress, onClick = onUpload,
            icon = { Icon(Icons.Filled.Add, stringResource(R.string.add_manga)) },
            text = { Text(stringResource(R.string.add_manga)) },
            modifier = Modifier.heightIn(min = 48.dp)
        )
    }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).padding(top = if (largeText) 32.dp else 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(state.query, vm::search, Modifier.weight(1f),
                    label = { Text(stringResource(R.string.library_search)) }, singleLine = true)
                Box {
                    TextButton(onClick = { sortOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(when (state.sort) {
                            LibrarySort.Title -> R.string.sort_title
                            LibrarySort.Progress -> R.string.sort_progress
                            else -> R.string.sort_recent
                        }))
                    }
                    DropdownMenu(sortOpen, { sortOpen = false }) {
                        LibrarySort.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(stringResource(when (item) {
                                LibrarySort.Title -> R.string.sort_title
                                LibrarySort.Progress -> R.string.sort_progress
                                else -> R.string.sort_recent
                            })) }, onClick = { vm.sort(item); sortOpen = false })
                        }
                    }
                }
            }
            if (state.error && state.series.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.offline_message))
                    Button(onClick = vm::refresh) { Text(stringResource(R.string.retry)) }
                }
            } else if (state.series.isEmpty() && state.continueItems.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.titleLarge)
                    Button(onClick = onUpload) { Text(stringResource(R.string.add_first_manga)) }
                }
            } else {
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(GridCells.Adaptive(if (largeText) 260.dp else 150.dp), state = grid,
                        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.continueItems.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text(stringResource(R.string.continue_reading), style = MaterialTheme.typography.titleLarge)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(state.continueItems, key = { it.seriesId }) { item ->
                                        ElevatedCard(onClick = { onReader(item.chapterId, null) },
                                            modifier = Modifier.width(if (largeText) 220.dp else 150.dp)
                                                .heightIn(min = 180.dp)) {
                                            Cover(item.coverUrl, item.seriesTitle)
                                            Text(item.seriesTitle, Modifier.padding(horizontal = 8.dp),
                                                maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis)
                                            Text(stringResource(R.string.chapter_page,
                                                formatChapterNumber(item.chapterNumber), item.page),
                                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                style = MaterialTheme.typography.labelSmall)
                                            LinearProgressIndicator({ item.percent.toFloat().coerceIn(0f, 1f) },
                                                Modifier.fillMaxWidth().padding(8.dp))
                                        }
                                    }
                                }
                            }
                        }
                        items(state.series, key = { it.id }) { series ->
                            val haptic = LocalHapticFeedback.current
                            Card(Modifier.fillMaxWidth().combinedClickable(
                                onClick = { onSeries(series.id) },
                                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selected = series }
                            )) {
                                Box {
                                    Column {
                                        Cover(series.coverUrl, series.title, series.progress?.percent)
                                        Text(series.title, Modifier.padding(8.dp), maxLines = if (largeText) 3 else 2,
                                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    }
                                    if (series.id in state.translating) AssistChip(
                                        onClick = {}, label = { Text(stringResource(R.string.translating)) },
                                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { series ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Text(series.title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { rename = series; selected = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.rename))
            }
            TextButton(onClick = { selected = null; onCover(series.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.change_cover))
            }
            TextButton(onClick = { delete = series; selected = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.delete))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    rename?.let { series ->
        var title by remember(series.id) { mutableStateOf(series.title) }
        AlertDialog(onDismissRequest = { rename = null },
            title = { Text(stringResource(R.string.rename_series)) },
            text = { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.series_title)) }) },
            confirmButton = { TextButton(onClick = { vm.rename(series.id, title); rename = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.cancel)) } })
    }
    delete?.let { series ->
        val deletedMessage = stringResource(R.string.deleted_series, series.title)
        AlertDialog(onDismissRequest = { delete = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.deleted_series, series.title)) },
            confirmButton = { TextButton(onClick = {
                delete = null
                scope.launch {
                    vm.deleteWithUndo(series.id) {
                        val result = CompletableDeferred<Boolean>()
                        val display = launch {
                            result.complete(snackbar.showSnackbar(
                                message = deletedMessage,
                                actionLabel = undoLabel,
                                withDismissAction = true, duration = SnackbarDuration.Indefinite
                            ) == SnackbarResult.ActionPerformed)
                        }
                        val undone = withTimeoutOrNull(5_000) { result.await() } ?: false
                        snackbar.currentSnackbarData?.dismiss()
                        display.cancel()
                        undone
                    }
                }
            }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun Cover(url: String?, title: String, progress: Double? = null) {
    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)
        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) AsyncImage(stableImageUrl(url), contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        if (progress != null) CircularProgressIndicator(
            progress = { progress.toFloat().coerceIn(0f, 1f) },
            Modifier.align(Alignment.BottomEnd).padding(12.dp).size(32.dp),
            strokeWidth = 3.dp
        )
    }
}
