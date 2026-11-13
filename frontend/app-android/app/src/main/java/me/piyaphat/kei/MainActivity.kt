package me.piyaphat.kei

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import me.piyaphat.kei.ui.nav.KeiNavHost
import me.piyaphat.kei.ui.theme.KeiTheme
import me.piyaphat.kei.data.api.resolvePairLink

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pairUri by mutableStateOf<String?>(null)
    private var seriesUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptPairIntent(intent)
        enableEdgeToEdge()
        setContent {
            KeiTheme {
                KeiNavHost(
                    pairUri = pairUri,
                    seriesUri = seriesUri,
                    onPairUriHandled = { handled -> if (pairUri == handled) pairUri = null },
                    onSeriesUriHandled = { handled -> if (seriesUri == handled) seriesUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptPairIntent(intent)
    }

    private fun acceptPairIntent(intent: android.content.Intent?) {
        pairUri = intent?.dataString?.takeIf { resolvePairLink(it, false) != null }
        seriesUri = intent?.dataString?.takeIf {
            val uri = android.net.Uri.parse(it)
            uri.scheme == "kei" && uri.host == "series" && uri.lastPathSegment?.toLongOrNull() != null
        }
    }
}
