package me.piyaphat.kei.ui.series

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.piyaphat.kei.R
import me.piyaphat.kei.data.api.ChapterSummary
import me.piyaphat.kei.data.api.Series
import me.piyaphat.kei.data.api.SeriesDetail
import me.piyaphat.kei.data.api.stableImageUrl
import me.piyaphat.kei.ui.common.messageResource
import me.piyaphat.kei.ui.common.formatChapterNumber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    id: Long,
    openCoverSheet: Boolean = false,
    vm: SeriesViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onReader: (Long, Int?) -> Unit
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var coverSheet by remember { mutableStateOf(openCoverSheet) }
    var metadata by remember { mutableStateOf(false) }
    var renameSeries by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<ChapterSummary?>(null) }
    var delete by remember { mutableStateOf<ChapterSummary?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmSelectionDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) { vm.load(id) }
    LaunchedEffect(vm) {
        vm.events.collect { snackbar.showSnackbar(context.getString(it.messageResource())) }
    }
    val detail = state.detail
    val series = detail?.series
    val coverDescription = stringResource(R.string.change_cover)
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isEmpty()) LargeTopAppBar(
                title = { Text(series?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.series_back))
                    }
                },
                scrollBehavior = scrollBehavior
            ) else TopAppBar(
                title = { Text(pluralStringResource(R.plurals.selected_count_plural, selected.size, selected.size)) },
                navigationIcon = {
                    IconButton(onClick = { selected = emptySet() }) {
                        Icon(Icons.Default.Close, stringResource(R.string.close_selection))
                    }
                },
                actions = {
                    TextButton(onClick = { selected.forEach(vm::translate); selected = emptySet() }) {
                        Text(stringResource(R.string.translate_selected))
                    }
                    TextButton(onClick = { confirmSelectionDelete = true }) {
                        Text(stringResource(R.string.delete))
                    }
                }
            )
        }
    ) { padding ->
        if (detail == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.error) Text(stringResource(R.string.offline_message))
                else CircularProgressIndicator()
            }
        } else {
            val series = detail.series
            LazyColumn(Modifier.fillMaxSize().padding(padding).nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top) {
                        Box(Modifier.width(120.dp).aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(onClick = { coverSheet = true }, onLongClick = { coverSheet = true })
                        .semantics { contentDescription = coverDescription },
                        contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (series.coverUrl != null) AsyncImage(stableImageUrl(series.coverUrl, 480), series.title,
                                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f)) {
                            TextButton(onClick = { renameSeries = true },
                                contentPadding = PaddingValues(0.dp)) {
                                Text(series.title, style = MaterialTheme.typography.headlineSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { metadata = true }, contentPadding = PaddingValues(0.dp)) {
                                Text(series.author.ifBlank { stringResource(R.string.add_author) },
                                    color = if (series.author.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary)
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                series.tags.forEach { tag ->
                                    AssistChip(onClick = { metadata = true }, label = { Text(tag) })
                                }
                                TextButton(onClick = { metadata = true }) {
                                    Text(stringResource(R.string.edit_details))
                                }
                            }
                        }
                    }
                    val progress = series.progress
                    val continueAction: () -> Unit = {
                        val c = detail.chapters.firstOrNull { it.id == progress?.chapterId }
                            ?: detail.chapters.firstOrNull()
                        c?.let { onReader(it.id, null) }
                    }
                    val translateAction: () -> Unit = {
                        val todo = detail.chapters.filter { it.translatedCount < it.pageCount }
                        vm.translateAll(id, todo.map { it.id to (it.pageCount to it.translatedCount) }) {
                            scope.launch { snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.translate_chapters, it, it)) }
                        }
                    }
                    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ContinueButton(detail, progress?.chapterId, progress?.page ?: 0,
                            continueAction, Modifier.weight(1f))
                        FilledTonalButton(onClick = translateAction,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.translate_all))
                        }
                    } else Column(Modifier.fillMaxWidth()) {
                        ContinueButton(detail, progress?.chapterId, progress?.page ?: 0,
                            continueAction, Modifier.fillMaxWidth())
                        FilledTonalButton(onClick = translateAction,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.translate_all))
                        }
                    }
                    Text(stringResource(R.string.chapter_count_header), style = MaterialTheme.typography.titleLarge)
                }
                items(detail.chapters, key = { it.id }) { chapter ->
                    val job = state.jobs.firstOrNull { it.chapterId == chapter.id }
                    var menu by remember { mutableStateOf(false) }
                    val progressChapter = detail.chapters.firstOrNull { it.id == series.progress?.chapterId }
                    val supporting = when {
                        series.progress?.let { it.chapterId == chapter.id } == true ->
                            stringResource(R.string.read_page_count, series.progress.page, chapter.pageCount)
                        progressChapter != null && chapter.number < progressChapter.number ->
                            stringResource(R.string.status_read)
                        else -> ""
                    }.let { state ->
                        when {
                            chapter.title.isBlank() -> state
                            state.isBlank() -> chapter.title
                            else -> stringResource(R.string.chapter_title_status, chapter.title, state)
                        }
                    }
                    val failed = job?.failed?.isNotEmpty() == true
                    val status = chapterStatus(chapter, failed)
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (selected.isNotEmpty()) selected = if (chapter.id in selected) selected - chapter.id else selected + chapter.id
                                else onReader(chapter.id, null)
                            },
                            onLongClick = { selected = if (chapter.id in selected) selected - chapter.id else selected + chapter.id }
                        ),
                        headlineContent = { Text(stringResource(R.string.chapter_name, formatChapterNumber(chapter.number))) },
                        supportingContent = { if (supporting.isNotBlank()) Text(supporting) },
                        trailingContent = {
                            if (selected.isNotEmpty()) Checkbox(chapter.id in selected, {
                                selected = if (it) selected + chapter.id else selected - chapter.id
                            }) else Row(verticalAlignment = Alignment.CenterVertically) {
                                ChapterStatus(status,
                                    failed = failed,
                                    ready = chapter.translatedCount == chapter.pageCount,
                                    translating = chapter.state == "running",
                                    uploading = chapter.presentCount < chapter.pageCount)
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(Icons.Default.MoreVert, stringResource(R.string.chapter_options))
                                    }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) },
                                            onClick = { rename = chapter; menu = false })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) },
                                            onClick = { delete = chapter; menu = false })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.translate_selected)) },
                                            onClick = { vm.translate(chapter.id); menu = false })
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }
    if (confirmSelectionDelete) AlertDialog(onDismissRequest = { confirmSelectionDelete = false },
        title = { Text(stringResource(R.string.delete)) },
        text = { Text(pluralStringResource(R.plurals.confirm_delete_chapters_plural, selected.size, selected.size)) },
        confirmButton = { TextButton(onClick = {
            selected.forEach(vm::deleteChapter); selected = emptySet(); confirmSelectionDelete = false
        }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmSelectionDelete = false }) { Text(stringResource(R.string.cancel)) } })
    if (coverSheet && detail != null) CoverSheet(id, detail.chapters, vm) { coverSheet = false }
    rename?.let { chapter ->
        var title by remember(chapter.id) { mutableStateOf(chapter.title) }
        var number by remember(chapter.id) { mutableStateOf(formatChapterNumber(chapter.number)) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text(stringResource(R.string.rename)) },
            text = { Column {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.series_title)) })
                OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.chapter_number)) })
            } },
            confirmButton = { TextButton(onClick = {
                vm.renameChapter(chapter.id, title, number.toDoubleOrNull()); rename = null
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.cancel)) } })
    }
    delete?.let { chapter ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text(stringResource(R.string.delete_chapter)) },
            confirmButton = { TextButton(onClick = { vm.deleteChapter(chapter.id); delete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (metadata && series != null) MetadataDialog(series,
        onSave = { title, author, tags -> vm.metadata(id, title, author, tags); metadata = false },
        onDismiss = { metadata = false })
    if (renameSeries && series != null) {
        var title by remember(series.id) { mutableStateOf(series.title) }
        AlertDialog(onDismissRequest = { renameSeries = false },
            title = { Text(stringResource(R.string.rename_series)) },
            text = { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.series_title)) }) },
            confirmButton = { TextButton(onClick = {
                vm.metadata(id, title, series.author, series.tags); renameSeries = false
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renameSeries = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ContinueButton(
    detail: SeriesDetail,
    chapterId: Long?,
    page: Int,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val chapter = detail.chapters.firstOrNull { it.id == chapterId }
        ?: detail.chapters.firstOrNull()
    Button(onClick, modifier.heightIn(min = 48.dp)) {
        Text(if (chapterId == null) stringResource(R.string.start_reading)
        else stringResource(R.string.continue_chapter_page,
            chapter?.number?.let(::formatChapterNumber).orEmpty(), page))
    }
}

@Composable
private fun ChapterStatus(
    text: String,
    failed: Boolean,
    ready: Boolean,
    translating: Boolean,
    uploading: Boolean
) {
    val outlined = !failed && !ready && !translating && !uploading
    val background = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        ready -> MaterialTheme.colorScheme.primaryContainer
        translating -> MaterialTheme.colorScheme.tertiaryContainer
        uploading -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val foreground = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        ready -> MaterialTheme.colorScheme.onPrimaryContainer
        translating -> MaterialTheme.colorScheme.onTertiaryContainer
        uploading -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            else Modifier,
        shape = RoundedCornerShape(50),
        color = background,
        contentColor = foreground
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun chapterStatus(chapter: ChapterSummary, failed: Boolean): String = when {
    failed -> stringResource(R.string.status_failed)
    chapter.presentCount < chapter.pageCount -> stringResource(R.string.status_uploading)
    chapter.translatedCount == chapter.pageCount -> stringResource(R.string.status_ready)
    chapter.state == "running" -> stringResource(R.string.status_translating,
        if (chapter.pageCount == 0) 0 else chapter.translatedCount * 100 / chapter.pageCount)
    chapter.state == "paused" -> stringResource(R.string.status_paused)
    else -> stringResource(R.string.status_not_translated)
}

@Composable
private fun MetadataDialog(
    series: Series,
    onSave: (String, String, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(series.title) }
    var author by remember { mutableStateOf(series.author) }
    var tags by remember { mutableStateOf(series.tags.joinToString(", ")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit_metadata)) },
        text = { Column {
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.series_title)) })
            OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.author)) })
            OutlinedTextField(tags, { tags = it }, label = { Text(stringResource(R.string.tags)) })
        } },
        confirmButton = { TextButton(onClick = { onSave(title, author, tags.split(',').map(String::trim).filter(String::isNotEmpty)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverSheet(id: Long, chapters: List<ChapterSummary>, vm: SeriesViewModel, close: () -> Unit) {
    val context = LocalContext.current
    var chapter by remember(chapters) { mutableStateOf(chapters.firstOrNull()?.id) }
    var chapterMenu by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photo = decodeDownsampled(context, uri)
    }
    if (photo != null) {
        CoverEditor(photo!!, onCancel = { photo = null }) { bytes ->
            vm.uploadCover(id, bytes)
            photo = null
            close()
        }
    } else ModalBottomSheet(onDismissRequest = close) {
        Text(stringResource(R.string.change_cover), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.pick_photo)) }
        Text(stringResource(R.string.choose_pages), Modifier.padding(16.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TextButton(onClick = { chapterMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                val current = chapters.firstOrNull { it.id == chapter }
                Text(current?.let { stringResource(R.string.chapter_name, formatChapterNumber(it.number)) }.orEmpty())
            }
            DropdownMenu(chapterMenu, { chapterMenu = false }) {
                chapters.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chapter_name, formatChapterNumber(item.number))) },
                        onClick = { chapter = item.id; chapterMenu = false }
                    )
                }
            }
        }
        chapters.firstOrNull()?.let { first ->
            val current = chapters.firstOrNull { it.id == chapter } ?: first
            LazyVerticalGrid(GridCells.Adaptive(90.dp), Modifier.heightIn(max = 300.dp)) {
                items(current.pageCount) { page ->
                    AsyncImage(stableImageUrl("/api/chapters/${current.id}/pages/$page/image?view=original", 300),
                        contentDescription = stringResource(R.string.cover_page_description, page + 1),
                        modifier = Modifier.aspectRatio(2f / 3f)
                            .combinedClickable(onClick = {
                                vm.coverFromPage(id, current.id, page); close()
                            }, onLongClick = { }), contentScale = ContentScale.Crop)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CoverEditor(bitmap: Bitmap, onCancel: () -> Unit, onUse: (ByteArray) -> Unit) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val frameWidthPx = with(density) { (config.screenWidthDp.dp - 32.dp).toPx() }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    val frameH = frameWidthPx * 1.5f
                    val rect = cropRectFromTransform(bitmap.width, bitmap.height, frameWidthPx, frameH,
                        zoom, offset.x, offset.y)
                    val x = rect.left.toInt().coerceIn(0, bitmap.width - 1)
                    val y = rect.top.toInt().coerceIn(0, bitmap.height - 1)
                    val w = rect.width.toInt().coerceIn(1, bitmap.width - x)
                    val h = rect.height.toInt().coerceIn(1, bitmap.height - y)
                    val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
                    val targetHeight = minOf(cropped.height, 1200)
                    val scaled = if (targetHeight == cropped.height) cropped else Bitmap.createScaledBitmap(
                        cropped, (cropped.width * targetHeight.toFloat() / cropped.height).toInt(),
                        targetHeight, true)
                    val bytes = java.io.ByteArrayOutputStream().also {
                        scaled.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }.toByteArray()
                    onUse(bytes)
                }) { Text(stringResource(R.string.use_photo), color = Color.White) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val frameW = with(density) { maxWidth.toPx() }
                val frameH = frameW * 1.5f
                val baseScale = maxOf(frameW / bitmap.width, frameH / bitmap.height)
                val drawW = with(density) { (bitmap.width * baseScale).toDp() }
                val drawH = with(density) { (bitmap.height * baseScale).toDp() }
                val transform = rememberTransformableState { zoomChange, panChange, _ ->
                    val nextZoom = (zoom * zoomChange).coerceIn(1f, 8f)
                    val width = bitmap.width * baseScale * nextZoom
                    val height = bitmap.height * baseScale * nextZoom
                    val maxX = ((width - frameW) / 2).coerceAtLeast(0f)
                    val maxY = ((height - frameH) / 2).coerceAtLeast(0f)
                    offset = Offset((offset.x + panChange.x).coerceIn(-maxX, maxX),
                        (offset.y + panChange.y).coerceIn(-maxY, maxY))
                    zoom = nextZoom
                }
                Box(Modifier.width(maxWidth).height(with(density) { frameH.toDp() })
                    .clipToBounds().border(2.dp, Color.White).transformable(transform),
                    contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(bitmap.asImageBitmap(), null,
                        Modifier.requiredSize(drawW, drawH).graphicsLayer {
                            scaleX = zoom; scaleY = zoom
                            translationX = offset.x; translationY = offset.y
                        }, contentScale = ContentScale.FillBounds)
                }
            }
            Text(stringResource(R.string.crop_cover), color = Color.White,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

private fun decodeDownsampled(context: android.content.Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2_400) sample *= 2
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
