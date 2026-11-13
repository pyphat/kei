package me.piyaphat.kei.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlin.math.abs
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.piyaphat.kei.R
import me.piyaphat.kei.data.api.ReaderPage
import me.piyaphat.kei.data.api.ReaderSettings
import me.piyaphat.kei.data.api.readerTapTarget
import me.piyaphat.kei.data.api.readerSwipePage
import me.piyaphat.kei.data.api.smartReaderFit
import me.piyaphat.kei.ui.common.formatChapterNumber
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    chapterId: Long,
    routePage: Int?,
    onBack: () -> Unit,
    onChapter: (Long, Int) -> Unit,
    vm: ReaderViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var settingsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmReprocess by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(chapterId, routePage) { vm.open(chapterId, routePage) }
    LaunchedEffect(state.controlsVisible, state.page) {
        if (state.controlsVisible) {
            delay(3_000)
            vm.hideControls()
        }
    }
    LaunchedEffect(Unit) {
        vm.remoteJumps.collect { progress ->
            val result = snackbar.showSnackbar(
                context.getString(R.string.reader_jump, progress.progress.page + 1,
                    progress.deviceName ?: context.getString(R.string.reader_another_device)),
                actionLabel = context.getString(R.string.reader_jump_action),
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                onChapter(progress.progress.chapterId, progress.progress.page)
            }
        }
    }
    val activity = remember(context) { context.findActivity() }
    val window = activity?.window
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            vm.saveNow()
        }
    }
    DisposableEffect(window, state.settings.brightness) {
        val oldBrightness = window?.attributes?.screenBrightness ?: -1f
        if (window != null) {
            window.attributes = window.attributes.apply { screenBrightness = state.settings.brightness ?: -1f }
        }
        onDispose {
            if (window != null) window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
        }
    }
    DisposableEffect(window, state.settings.keepScreenOn) {
        val oldKeep = window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (window != null) {
            if (state.settings.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (window != null) {
                if (oldKeep) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    val chapter = state.chapter
    val pages = chapter?.pages.orEmpty()
    val background = when (state.settings.background) {
        "dark" -> Color(0xFF242424)
        "white" -> Color.White
        else -> Color.Black
    }
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    val width = context.resources.displayMetrics.widthPixels.coerceAtMost(2048)
    LaunchedEffect(chapter, state.page, state.settings.view, width) {
        chapter?.pages?.drop(state.page + 1)?.take(3)?.forEach { page ->
            imageLoader.enqueue(ImageRequest.Builder(context).data(vm.imageUrl(page, state.settings.view, width)).build())
        }
    }
    val seriesChapters = state.series?.chapters.orEmpty().sortedBy { it.number }
    val chapterPosition = seriesChapters.indexOfFirst { it.id == chapterId }
    val previous = seriesChapters.getOrNull(chapterPosition - 1)
    val next = seriesChapters.getOrNull(chapterPosition + 1)

    Scaffold(
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.controlsVisible) TopAppBar(
                title = { Text("${state.series?.series?.title.orEmpty()} · Ch ${chapter?.number?.let(::formatChapterNumber).orEmpty()}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.series_back))
                    }
                },
                actions = {
                    if (state.settings.showPageNumber) Text(stringResource(R.string.reader_page_count, state.page + 1, pages.size))
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(Icons.Filled.Search, stringResource(R.string.reader_search))
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.reader_settings))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.reader_more_options)) }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(if (chapter?.state == "running") R.string.reader_pause else R.string.reader_translate)) },
                                onClick = { menuOpen = false; vm.translate(if (chapter?.state == "running") "pause" else "start") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reader_show_cleaned_page)) }, onClick = {
                                menuOpen = false; vm.setSettings(state.settings.copy(view = "clean"))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reader_set_cover)) }, onClick = { menuOpen = false; vm.setCover() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reader_reprocess_page)) }, onClick = {
                                menuOpen = false; confirmReprocess = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reader_edit_text)) }, onClick = { menuOpen = false })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White)
            )
        },
        bottomBar = {
            if (state.controlsVisible && pages.isNotEmpty()) ReaderControls(
                state, pages.size, previous?.id, next?.id,
                onPage = vm::setPage,
                onChapter = onChapter,
                onView = { vm.setSettings(state.settings.copy(view = it)) }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).semantics {
            customActions = listOf(
                CustomAccessibilityAction("Next page") { vm.setPage(state.page + 1); true },
                CustomAccessibilityAction("Previous page") { vm.setPage(state.page - 1); true }
            )
        }) {
            if (state.error != null) Text(state.error.orEmpty(), color = Color.White, modifier = Modifier.align(Alignment.Center))
            else if (pages.isNotEmpty() && chapter != null) {
                if (state.settings.mode == "continuous") {
                    val list = rememberLazyListState(state.page)
                    LaunchedEffect(list) {
                        snapshotFlow { list.firstVisibleItemIndex.coerceAtMost(pages.size - 1) }
                            .distinctUntilChanged().collect(vm::setPage)
                    }
                    LaunchedEffect(state.page) {
                        if (list.firstVisibleItemIndex.coerceAtMost(pages.size - 1) != state.page) list.scrollToItem(state.page)
                    }
                    LazyColumn(Modifier.fillMaxSize().zoomable(rememberZoomableState()), state = list) {
                        itemsIndexed(pages) { index, page ->
                            key(chapterId, page.index) {
                                ReaderImage(chapterId, page, pages.size, state.settings, width, vm::imageUrl,
                                    Modifier.fillMaxWidth().aspectRatio(page.width.toFloat() / page.height.coerceAtLeast(1)),
                                    onTap = { vm.toggleControls() }, onLongClick = { vm.revealControls() })
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.reader_end_chapter, formatChapterNumber(chapter.number)), color = Color.White)
                                next?.let { Button(onClick = { onChapter(it.id, 0) }) { Text(stringResource(R.string.reader_next_chapter)) } }
                                TextButton(onClick = onBack) { Text(stringResource(R.string.reader_back_to_series)) }
                            }
                        }
                    }
                } else {
                    val pager = rememberPagerState(initialPage = state.page, pageCount = { pages.size })
                    var zoomed by remember { mutableStateOf(false) }
                    LaunchedEffect(pager) {
                        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect(vm::setPage)
                    }
                    LaunchedEffect(pager.settledPage) { zoomed = false }
                    LaunchedEffect(state.page) {
                        if (pager.settledPage != state.page) pager.scrollToPage(state.page)
                    }
                    HorizontalPager(
                        state = pager, modifier = Modifier.fillMaxSize().pointerInput(pager, zoomed, state.settings.rtl) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                var end = down.position
                                var pressed: Boolean
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    end = event.changes.first().position
                                    pressed = event.changes.any { it.pressed }
                                    // Claim one-finger horizontal swipes so the image doesn't also report them as a tap-zone click.
                                    if (!zoomed && event.changes.size == 1 && abs(end.x - down.position.x) > viewConfiguration.touchSlop)
                                        event.changes.forEach { it.consume() }
                                } while (pressed)
                                readerSwipePage(pager.settledPage, pages.size, end.x - down.position.x,
                                    state.settings.rtl, zoomed)?.let { target ->
                                    scope.launch { pager.animateScrollToPage(target) }
                                }
                            }
                        },
                        // Pager drag-snap would cancel the swipe animation, so the gesture handler above owns paging.
                        userScrollEnabled = false,
                        reverseLayout = state.settings.rtl, beyondViewportPageCount = 1,
                        key = { it },
                    ) { index ->
                        val page = pages[index]
                        ReaderImage(chapterId, page, pages.size, state.settings, width, vm::imageUrl, Modifier.fillMaxSize(),
                            onTap = { fraction ->
                                when (readerTapTarget(fraction, state.settings.invertTapZones, state.settings.rtl)) {
                                    -1 -> vm.setPage(state.page - 1)
                                    1 -> vm.setPage(state.page + 1)
                                    else -> vm.toggleControls()
                                }
                            }, onLongClick = { vm.revealControls() },
                            onZoomFraction = { if (pager.currentPage == index) zoomed = it > 0.01f })
                    }
                }
                if (state.settings.view == "translated" && pages.getOrNull(state.page)?.status != "translated") {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.reader_translating)) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
                }
            } else CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
    }

    if (settingsOpen) ReaderSettingsSheet(state.settings, { vm.setSeriesSettings(it.mode, it.rtl) },
        { vm.setSettings(it) }, { settingsOpen = false })
    if (searchOpen) ReaderSearchSheet(state.query, state.searchHits, vm::search, { hit ->
        vm.setPage(pages.indexOfFirst { it.index == hit.index }.coerceAtLeast(0))
        searchOpen = false
    }, { searchOpen = false })
    if (confirmReprocess) AlertDialog(
        onDismissRequest = { confirmReprocess = false },
        title = { Text(stringResource(R.string.reader_reprocess_title)) },
        text = { Text(stringResource(R.string.reader_reprocess_warning)) },
        confirmButton = { TextButton(onClick = { confirmReprocess = false; vm.reprocess() }) { Text(stringResource(R.string.reader_reprocess)) } },
        dismissButton = { TextButton(onClick = { confirmReprocess = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun ReaderImage(
    chapterId: Long, page: ReaderPage, pageCount: Int, settings: ReaderSettings, width: Int,
    imageUrl: (ReaderPage, String, Int) -> String, modifier: Modifier,
    onTap: (Float) -> Unit, onLongClick: () -> Unit, onZoomFraction: (Float) -> Unit = {}
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f))
    val imageState = rememberZoomableImageState(zoomableState)
    LaunchedEffect(zoomableState.zoomFraction) { onZoomFraction(zoomableState.zoomFraction ?: 0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val fitMode = when (settings.fit) {
        "width" -> "width"
        "height" -> "height"
        else -> smartReaderFit(page.width, page.height)
    }
    val fit = when (fitMode) {
        "width" -> androidx.compose.ui.layout.ContentScale.FillWidth
        "height" -> androidx.compose.ui.layout.ContentScale.FillHeight
        else -> androidx.compose.ui.layout.ContentScale.Fit
    }
    ZoomableAsyncImage(
        model = ImageRequest.Builder(context).data(imageUrl(page, settings.view, width)).crossfade(250).build(),
        imageLoader = imageLoader,
        state = imageState,
        contentDescription = null,
        modifier = modifier.onSizeChanged { widthPx = it.width.toFloat() }
            .semantics { contentDescription = context.getString(R.string.reader_page_description, page.index + 1, pageCount) },
        contentScale = fit,
        onClick = { onTap(it.x / widthPx) },
        onLongClick = { onLongClick() }
    )
}

@Composable
private fun ReaderControls(
    state: ReaderState, count: Int, previous: Long?, next: Long?,
    onPage: (Int, Boolean) -> Unit, onChapter: (Long, Int) -> Unit, onView: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { previous?.let { onChapter(it, Int.MAX_VALUE) } }, enabled = previous != null) { Text(stringResource(R.string.reader_previous_chapter)) }
            Text(stringResource(R.string.reader_page_count, state.page + 1, count), color = Color.White)
            TextButton(onClick = { next?.let { onChapter(it, 0) } }, enabled = next != null) { Text(stringResource(R.string.reader_next_chapter)) }
        }
        Slider(value = state.page.toFloat(), onValueChange = { onPage(it.toInt(), false) },
            valueRange = 0f..(count - 1).coerceAtLeast(0).toFloat())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.settings.view == "original", { onView("original") }, label = { Text(stringResource(R.string.reader_original)) })
            FilterChip(state.settings.view == "translated", { onView("translated") }, label = { Text(stringResource(R.string.reader_translated)) })
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReaderSettingsSheet(
    settings: ReaderSettings, onSeries: (ReaderSettings) -> Unit,
    onGlobal: (ReaderSettings) -> Unit, dismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.reader_settings), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.reader_mode))
            Row {
                FilterChip(settings.mode == "paged", { onSeries(settings.copy(mode = "paged")) }, label = { Text(stringResource(R.string.reader_paged)) })
                Spacer(Modifier.width(8.dp))
                FilterChip(settings.mode == "continuous", { onSeries(settings.copy(mode = "continuous")) }, label = { Text(stringResource(R.string.reader_continuous)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reader_right_to_left), Modifier.weight(1f)); Switch(settings.rtl, { onSeries(settings.copy(rtl = it)) })
            }
            Text(stringResource(R.string.reader_fit))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("height", "width", "smart").forEach {
                    FilterChip(settings.fit == it, { onGlobal(settings.copy(fit = it)) }, label = {
                        Text(stringResource(when (it) {
                            "height" -> R.string.reader_fit_height
                            "width" -> R.string.reader_fit_width
                            else -> R.string.reader_fit_smart
                        }))
                    })
                }
            }
            Text(stringResource(R.string.reader_background))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("black", "dark", "white").forEach {
                    FilterChip(settings.background == it, { onGlobal(settings.copy(background = it)) }, label = {
                        Text(stringResource(when (it) {
                            "black" -> R.string.reader_background_black
                            "dark" -> R.string.reader_background_dark
                            else -> R.string.reader_background_white
                        }))
                    })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reader_keep_screen_on), Modifier.weight(1f)); Switch(settings.keepScreenOn, { onGlobal(settings.copy(keepScreenOn = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reader_invert_tap_zones), Modifier.weight(1f)); Switch(settings.invertTapZones, { onGlobal(settings.copy(invertTapZones = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reader_show_page_number), Modifier.weight(1f)); Switch(settings.showPageNumber, { onGlobal(settings.copy(showPageNumber = it)) })
            }
            Text(stringResource(R.string.reader_brightness))
            Slider(value = settings.brightness ?: 0.5f, onValueChange = { onGlobal(settings.copy(brightness = it)) })
            TextButton(onClick = { onGlobal(settings.copy(brightness = null)) }) { Text(stringResource(R.string.reader_system_brightness)) }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReaderSearchSheet(
    query: String, hits: List<me.piyaphat.kei.data.api.ReaderSearchHit>,
    search: (String) -> Unit, select: (me.piyaphat.kei.data.api.ReaderSearchHit) -> Unit, dismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(stringResource(R.string.reader_search), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, search, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.reader_search_hint)) },
                singleLine = true)
            if (query.isBlank()) Text(stringResource(R.string.reader_search_empty))
            else if (hits.isEmpty()) Text(stringResource(R.string.reader_no_matches))
            hits.forEach { hit ->
                TextButton(onClick = { select(hit) }) {
                    Text(stringResource(R.string.reader_search_page, hit.index + 1, hit.source))
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
