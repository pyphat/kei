package me.piyaphat.kei.ui.upload

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import me.piyaphat.kei.R
import me.piyaphat.kei.work.api.UploadTask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    onOpenSeries: (Long) -> Unit,
    vm: UploadViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    var showNotificationRationale by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.upload()
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbars.showSnackbar(it); vm.clearMessage() }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.scanFolder(it)
        }
    }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.scanArchive(it)
        }
    }
    val imagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.scanImages(uris)
        }
    }
    val uploadClick = {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) showNotificationRationale = true else vm.upload()
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            if (state.preview != null) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = vm::cancelPreview, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.upload_cancel_preview))
                    }
                    Button(onClick = uploadClick, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Upload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.upload_action))
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.upload_heading), Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineMedium)
            if (state.tasks.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.tasks.forEach { task ->
                        UploadTaskCard(task, vm::cancelTask, vm::retry, vm::removeTask,
                            onOpenSeries, vm::translateNow)
                    }
                }
            }
            if (state.scanning) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.upload_scanning, state.scannedFiles), Modifier.padding(16.dp))
                    TextButton(onClick = vm::cancelScan) { Text(stringResource(R.string.upload_cancel_scan)) }
                }
            } else if (state.preview == null) {
                Text(stringResource(R.string.upload_instruction), Modifier.padding(horizontal = 20.dp))
                SourceCard(stringResource(R.string.upload_folder), Icons.Filled.FolderOpen,
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { folderPicker.launch(null) }
                SourceCard(stringResource(R.string.upload_archive), Icons.Filled.Archive,
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    archivePicker.launch(arrayOf("application/zip", "application/x-cbz",
                        "application/vnd.comicbook+zip", "application/octet-stream"))
                }
                SourceCard(stringResource(R.string.upload_images), Icons.Filled.Image,
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    imagesPicker.launch(arrayOf("image/*"))
                }
            } else {
                PreviewContent(state, vm, Modifier.weight(1f))
            }
            state.error?.let {
                Text(it, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showNotificationRationale) AlertDialog(
        onDismissRequest = { showNotificationRationale = false; vm.upload() },
        text = { Text(stringResource(R.string.upload_notification_rationale)) },
        confirmButton = { TextButton(onClick = {
            showNotificationRationale = false
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) { Text(stringResource(R.string.upload_notifications_continue)) } },
        dismissButton = { TextButton(onClick = { showNotificationRationale = false; vm.upload() }) {
            Text(stringResource(R.string.upload_cancel_preview))
        } }
    )
}

@Composable
private fun SourceCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 88.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PreviewContent(state: UploadUiState, vm: UploadViewModel, modifier: Modifier = Modifier) {
    val preview = state.preview ?: return
    val includeChapterLabel = stringResource(R.string.upload_include_chapter)
    val included = state.chapters.filter { it.included }
    val pages = included.sumOf { it.chapter.files.size }
    val bytes = included.sumOf { entry -> entry.chapter.files.sumOf { it.size } }
    var targetMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.upload_target), style = MaterialTheme.typography.labelLarge)
        Box {
            TextButton(onClick = { targetMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(state.targetSeriesId?.let { id -> state.series.firstOrNull { it.id == id }?.title }
                    ?: stringResource(R.string.upload_new_series))
            }
            DropdownMenu(targetMenu, { targetMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.upload_new_series)) },
                    onClick = { vm.setTarget(null); targetMenu = false })
                state.series.forEach { series ->
                    DropdownMenuItem(text = { Text(series.title) },
                        onClick = { vm.setTarget(series.id); targetMenu = false })
                }
            }
        }
        if (state.targetSeriesId == null) OutlinedTextField(
            state.seriesTitle, vm::setSeriesTitle, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.series_title)) }, singleLine = true
        )
        Text(stringResource(R.string.upload_chapters), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
        state.chapters.forEachIndexed { index, entry ->
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(entry.included, { vm.toggleChapter(entry.key) },
                            Modifier.semantics {
                                contentDescription = includeChapterLabel
                            })
                        Text(stringResource(R.string.upload_file_pages, entry.chapter.files.size),
                            Modifier.weight(1f))
                        IconButton(onClick = { vm.moveChapter(entry.key, -1) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.upload_move_up))
                        }
                        IconButton(onClick = { vm.moveChapter(entry.key, 1) },
                            enabled = index < state.chapters.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.upload_move_down))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(entry.chapter.number.toString(),
                            { vm.setChapterNumber(entry.key, it) }, Modifier.weight(0.35f),
                            label = { Text(stringResource(R.string.upload_number)) }, singleLine = true)
                        OutlinedTextField(entry.chapter.title,
                            { vm.setChapterTitle(entry.key, it) }, Modifier.weight(0.65f),
                            label = { Text(stringResource(R.string.upload_chapter_title)) }, singleLine = true)
                    }
                    if (index == 0 && state.targetSeriesId == null) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            entry.chapter.files.take(10).forEachIndexed { page, file ->
                                Card(onClick = { vm.chooseCover(page) },
                                    border = BorderStroke(2.dp, if (state.coverPageIndex == page)
                                        MaterialTheme.colorScheme.primary else Color.Transparent)) {
                                    AsyncImage(Uri.parse(file.location), null,
                                        Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(6.dp)))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (preview.skipped.isNotEmpty()) {
            Text(stringResource(R.string.upload_skipped), Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            preview.skipped.forEach {
                Text(stringResource(R.string.upload_skipped_file, it.path, it.reason),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(stringResource(R.string.upload_summary, included.size, pages, bytes / 1_000_000f),
            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun UploadTaskCard(
    task: UploadTask,
    cancel: (Long) -> Unit,
    retry: (Long) -> Unit,
    remove: (Long) -> Unit,
    open: (Long) -> Unit,
    translate: (UploadTask) -> Unit
) {
    val status = when (task.state) {
        "hashing" -> stringResource(R.string.upload_checking_files)
        "uploading" -> stringResource(R.string.upload_progress, task.seriesTitle, task.done, task.total)
        "waiting" -> stringResource(R.string.upload_waiting_network)
        "queued" -> stringResource(R.string.upload_queued)
        "done" -> stringResource(R.string.upload_done_state)
        "failed" -> task.error ?: stringResource(R.string.upload_failed_generic)
        else -> stringResource(R.string.upload_cancel_task)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(task.seriesTitle, style = MaterialTheme.typography.titleMedium)
            Text(status)
            if (task.state == "uploading" || task.state == "hashing") {
                LinearProgressIndicator(
                    progress = { if (task.total == 0) 0f else task.done.toFloat() / task.total },
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.state) {
                    "done" -> {
                        task.seriesId?.let { Button(onClick = { open(it) }) { Text(stringResource(R.string.upload_open_series)) } }
                        TextButton(onClick = { translate(task) }) { Text(stringResource(R.string.upload_translate_now)) }
                    }
                    "failed" -> {
                        Button(onClick = { retry(task.id) }) { Text(stringResource(R.string.upload_retry)) }
                        TextButton(onClick = { remove(task.id) }) { Text(stringResource(R.string.upload_remove)) }
                    }
                    "cancelled" -> TextButton(onClick = { remove(task.id) }) {
                        Text(stringResource(R.string.upload_remove))
                    }
                    else -> TextButton(onClick = { cancel(task.id) }) { Text(stringResource(R.string.upload_cancel_task)) }
                }
            }
        }
    }
}
