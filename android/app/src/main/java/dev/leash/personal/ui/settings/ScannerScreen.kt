package dev.leash.personal.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.leash.personal.R
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashDesignTheme
import dev.leash.personal.ui.design.components.BackButton
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.components.TopBar
import dev.leash.personal.core.PairingInfo
import dev.leash.personal.core.parsePairingLink
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Reads the pairing QR code with CameraX and ML Kit's bundled barcode model (no Google Play services involved). */
@Composable
fun ScannerScreen(onResult: (PairingInfo) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; asked = true }
    var failure by remember { mutableStateOf<String?>(null) }

    // the camera fills the screen in both themes: light status bar icons while it shows, the theme's again after
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = Leash.colors.dark
    androidx.compose.runtime.DisposableEffect(view, dark) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = false
        onDispose { bars?.isAppearanceLightStatusBars = !dark }
    }
    // the top bar floats over the camera with the glass back circle
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
            when {
                !hasCamera(context) -> Info(stringResource(R.string.camera_unavailable), null, null)
                !granted -> {
                    val openSettings = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    if (asked) Info(stringResource(R.string.camera_denied), stringResource(R.string.open_app_settings), openSettings)
                    else Info(stringResource(R.string.camera_needed), stringResource(R.string.grant_camera)) { launcher.launch(Manifest.permission.CAMERA) }
                }
                else -> {
                    CameraPreview(onResult, onFailure = { failure = it })
                    Text(
                        failure ?: stringResource(R.string.scan_hint),
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp), color = Color.White, textAlign = TextAlign.Center, style = Leash.type.subheadline,
                    )
                }
            }
        }
        LeashDesignTheme(dark = true, systemBars = false) {
            TopBar(title = stringResource(R.string.scan_title), navigation = { BackButton(stringResource(R.string.back), onBack) }, edgeVisible = false)
        }
    }
}

@Composable
private fun Info(text: String, action: String?, onAction: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.White, textAlign = TextAlign.Center, style = Leash.type.body)
        if (action != null && onAction != null) LeashDesignTheme(dark = true, systemBars = false) { LeashButton(action, onAction, Modifier.padding(top = 20.dp)) }
    }
}

@Composable
private fun CameraPreview(onResult: (PairingInfo) -> Unit, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || done.get()) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val info = codes.firstNotNullOfOrNull { c -> c.rawValue?.let { parsePairingLink(it) } }
                            if (info != null && done.compareAndSet(false, true)) onResult(info)
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                onFailure(e.message ?: e.toString())
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView({ view }, Modifier.fillMaxSize())
}
