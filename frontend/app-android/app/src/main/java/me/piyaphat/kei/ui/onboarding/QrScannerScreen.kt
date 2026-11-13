package me.piyaphat.kei.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import me.piyaphat.kei.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.camera_rationale), style = MaterialTheme.typography.titleMedium)
        if (granted) CameraPreview(onResult, Modifier.weight(1f).fillMaxWidth())
        else {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.camera_denied))
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.allow_camera))
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.type_pin))
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val sent = remember { AtomicBoolean(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    AndroidView(factory = { PreviewView(it).also { view -> previewView = view } }, modifier = modifier)
    LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val image = proxy.image
                if (image == null) proxy.close() else {
                    scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val value = codes.firstOrNull()?.rawValue
                            if (value != null && sent.compareAndSet(false, true)) onResult(value)
                        }.addOnCompleteListener { proxy.close() }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
            previewView?.let { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}
