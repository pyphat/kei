package me.piyaphat.kei.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import me.piyaphat.kei.R
import me.piyaphat.kei.data.api.Kind
import me.piyaphat.kei.discovery.api.DiscoveredServer
import me.piyaphat.kei.ui.common.messageResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    pairUri: String? = null,
    onPairUriHandled: (String) -> Unit = {},
    vm: OnboardingViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.events.collect {
            val message = when (it) {
                OnboardingEvent.BadCode -> context.getString(R.string.bad_pair_code)
                is OnboardingEvent.Failure -> context.getString(it.kind.messageResource())
            }
            snackbar.showSnackbar(message)
        }
    }
    LaunchedEffect(pairUri) {
        pairUri?.let {
            vm.pairSecret(it)
            onPairUriHandled(it)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { if (state.page == OnboardingPage.Find) TopAppBar(
            title = { Text(stringResource(R.string.find_server)) }
        ) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.page) {
                OnboardingPage.Welcome -> Welcome(vm)
                OnboardingPage.Find -> FindServer(state, vm)
                OnboardingPage.Pair -> PairPhone(state, vm)
                OnboardingPage.Scan -> QrScannerScreen(onResult = vm::pairSecret, onBack = vm::back)
            }
        }
    }
}

@Composable
private fun Welcome(vm: OnboardingViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.MenuBook, null, Modifier.size(112.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(32.dp))
        Button(onClick = vm::find, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.find_server))
        }
        TextButton(onClick = vm::scan, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.scan_pair_qr))
        }
    }
}

@Composable
private fun FindServer(state: OnboardingState, vm: OnboardingViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (state.searching && state.servers.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.searching))
        }
        if (state.showHint) Text(stringResource(R.string.not_finding_server),
            Modifier.fillMaxWidth().padding(vertical = 12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(state.servers, key = { it.name + it.host + it.port }) { server ->
                ServerCard(server) { vm.connect(server.baseUrl) }
            }
        }
        TextButton(onClick = vm::manual, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.enter_address))
        }
        if (state.manual) {
            OutlinedTextField(state.address, vm::address, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.server_address)) }, singleLine = true)
            Button(onClick = { vm.connect() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.connect))
            }
        }
        state.error?.let { Text(errorText(it, state.pairError), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ServerCard(server: DiscoveredServer, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp).heightIn(min = 88.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(server.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.server_address_value, server.host, server.port))
        }
    }
}

@Composable
private fun PairPhone(state: OnboardingState, vm: OnboardingViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineSmall)
        Text(state.info?.hostname.orEmpty())
        Spacer(Modifier.height(16.dp))
        Button(onClick = vm::scan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.scan_qr))
        }
        OutlinedTextField(state.pin, vm::pin, Modifier.fillMaxWidth().padding(vertical = 8.dp),
            label = { Text(stringResource(R.string.pin_code)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Monospace, letterSpacing = 8.sp))
        OutlinedTextField(state.deviceName, vm::name, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.device_name)) }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.pair_helper), style = MaterialTheme.typography.bodyMedium)
        if (state.pairError) Text(stringResource(R.string.bad_pair_code),
            Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
        state.error?.takeUnless { state.pairError }?.let {
            Text(errorText(it, false), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun errorText(kind: Kind, pairing: Boolean) =
    stringResource(if (pairing) R.string.bad_pair_code else kind.messageResource())
