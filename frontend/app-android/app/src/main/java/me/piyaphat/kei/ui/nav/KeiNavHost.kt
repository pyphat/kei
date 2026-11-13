package me.piyaphat.kei.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import me.piyaphat.kei.ui.downloads.DownloadsScreen
import me.piyaphat.kei.ui.library.LibraryScreen
import me.piyaphat.kei.ui.onboarding.ConnectionViewModel
import me.piyaphat.kei.ui.onboarding.OnboardingScreen
import me.piyaphat.kei.ui.queue.QueueScreen
import me.piyaphat.kei.ui.series.SeriesDetailScreen
import me.piyaphat.kei.ui.settings.SettingsScreen
import me.piyaphat.kei.ui.upload.UploadScreen
import me.piyaphat.kei.ui.reader.ReaderScreen
import me.piyaphat.kei.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.piyaphat.kei.data.api.Kind
import me.piyaphat.kei.data.api.resolvePairLink
import me.piyaphat.kei.ui.common.messageResource

@Composable
fun KeiNavHost(
    pairUri: String? = null,
    seriesUri: String? = null,
    onPairUriHandled: (String) -> Unit = {},
    onSeriesUriHandled: (String) -> Unit = {}
) {
    val session: ConnectionViewModel = hiltViewModel()
    val connection by session.connection.collectAsState()
    var pendingPairUri by remember { mutableStateOf<String?>(null) }
    var pairError by remember { mutableStateOf<Kind?>(null) }
    val requestedPairUri = pendingPairUri ?: pairUri
    if (connection == null) {
        OnboardingScreen(pairUri = requestedPairUri, onPairUriHandled = { handled ->
            if (pendingPairUri == handled) pendingPairUri = null
            onPairUriHandled(handled)
        })
        return
    }
    val pairRoute = resolvePairLink(requestedPairUri, connected = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navLabelStyle = androidx.compose.material3.MaterialTheme.typography.labelSmall.let {
        it.copy(fontSize = if (LocalDensity.current.fontScale >= 1.6f) 9.sp else it.fontSize)
    }
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    fun navigate(route: Any) {
        navController.navigate(route) {
            if (route !is Reader) popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = route !is Reader
            restoreState = route !is Reader
        }
    }
    LaunchedEffect(seriesUri) {
        val uri = seriesUri?.let(android.net.Uri::parse)
        val id = uri?.lastPathSegment?.toLongOrNull()
        if (uri?.scheme == "kei" && uri.host == "series" && id != null) {
            navigate(Series(id))
            onSeriesUriHandled(seriesUri)
        }
    }

    val graph: @Composable () -> Unit = { MainNavGraph(navController, ::navigate) }
    if (entry?.destination?.hasRoute<Reader>() == true) {
        graph()
    } else {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                item(entry?.destination?.hasRoute<Library>() == true, { navigate(Library) },
                    icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_library), style = navLabelStyle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) })
                item(entry?.destination?.hasRoute<Queue>() == true, { navigate(Queue) },
                    icon = { Icon(Icons.Filled.Translate, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_queue), style = navLabelStyle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) })
                item(entry?.destination?.hasRoute<Downloads>() == true, { navigate(Downloads) },
                    icon = { Icon(Icons.Filled.DownloadForOffline, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_downloads), style = navLabelStyle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) })
                item(entry?.destination?.hasRoute<Settings>() == true, { navigate(Settings) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings), style = navLabelStyle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        ) { graph() }
    }
    pairRoute?.takeIf { it.requiresConfirmation }?.let { route ->
        val raw = requestedPairUri ?: return@let
        val host = java.net.URI(route.target.baseUrl).host.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingPairUri = null; pairError = null; onPairUriHandled(raw) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.pair_replace_confirmation, host))
                    pairError?.let { Text(stringResource(it.messageResource())) }
                }
            },
            confirmButton = { TextButton(onClick = {
                pendingPairUri = raw
                scope.launch {
                    try {
                        session.disconnect()
                        onPairUriHandled(raw)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        pairError = (e as? me.piyaphat.kei.data.api.KeiException)?.kind ?: Kind.Unknown
                    }
                }
            }) { Text(stringResource(R.string.pair_now)) } },
            dismissButton = { TextButton(onClick = {
                pendingPairUri = null; pairError = null; onPairUriHandled(raw)
            }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun MainNavGraph(navController: NavHostController, navigate: (Any) -> Unit) {
    NavHost(navController, startDestination = Library) {
        composable<Library> {
            LibraryScreen(onSeries = { navigate(Series(it)) },
                onReader = { chapter, page -> navigate(Reader(chapter, page)) },
                onCover = { navigate(Series(it, true)) },
                onUpload = { navigate(Upload) })
        }
        composable<Queue> { QueueScreen() }
        composable<Downloads> { DownloadsScreen() }
        composable<Settings> { SettingsScreen() }
        composable<Series> { screen ->
            val route = screen.toRoute<Series>()
            SeriesDetailScreen(
                route.id,
                route.changeCover,
                onBack = { navController.popBackStack() },
                onReader = { chapter, page -> navigate(Reader(chapter, page)) }
            )
        }
        composable<Reader> { screen ->
            val route = screen.toRoute<Reader>()
            ReaderScreen(route.chapterId, route.page,
                onBack = { navController.popBackStack() },
                onChapter = { chapterId, page -> navigate(Reader(chapterId, page)) })
        }
        composable<Upload> { UploadScreen(onOpenSeries = { navigate(Series(it)) }) }
    }
}
