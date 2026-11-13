package me.piyaphat.kei.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import me.piyaphat.kei.R
import me.piyaphat.kei.data.api.Kind
import me.piyaphat.kei.data.api.KeiException
import me.piyaphat.kei.data.api.ServerInfo
import me.piyaphat.kei.data.api.ServerRepository
import me.piyaphat.kei.ui.common.messageResource
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: ServerRepository) : ViewModel() {
    val connection = repo.connection
    private val mutable = MutableStateFlow<ServerInfo?>(null)
    val tested = mutable.asStateFlow()
    private val mutableEvents = MutableSharedFlow<Kind>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()
    init { connection.value?.let { test(it.baseUrl) } }
    fun test(url: String) { viewModelScope.launch {
        try { mutable.value = repo.probe(url) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = null; mutableEvents.emit((e as? KeiException)?.kind ?: Kind.Unknown) }
    } }
    fun disconnect() { viewModelScope.launch {
        try { repo.disconnect() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableEvents.emit((e as? KeiException)?.kind ?: Kind.Unknown) }
    } }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val connection by vm.connection.collectAsState()
    val tested by vm.tested.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(context.getString(it.messageResource())) } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.server_section), style = MaterialTheme.typography.headlineSmall)
        Text(connection?.baseUrl.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        Text(tested?.hostname.orEmpty())
        Button(onClick = { connection?.let { vm.test(it.baseUrl) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.test_connection))
        }
        if (tested != null) Text(stringResource(R.string.connection_ok))
        OutlinedButton(onClick = { confirm = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.disconnect))
        }
    }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.disconnect)) },
        text = { Text(stringResource(R.string.disconnect_confirm)) },
        confirmButton = { TextButton(onClick = { vm.disconnect(); confirm = false }) {
            Text(stringResource(R.string.disconnect))
        } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
