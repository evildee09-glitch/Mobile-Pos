package ph.sarisari.mpos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** CameraX preview + ML Kit (bundled model, works offline). Same code within 1.5 s is ignored. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreviewScanner(modifier: Modifier, onCode: (String) -> Unit) {
    val ctx = LocalContext.current; val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onCode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_QR_CODE).build())
    }
    var lastCode by remember { mutableStateOf("") }; var lastAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) { onDispose { try { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } catch (_: Exception) {}; scanner.close(); executor.shutdown() } }
    AndroidView(modifier = modifier, factory = { c ->
        val view = PreviewView(c)
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val img = proxy.image
                if (img == null) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { list ->
                        val code = list.firstNotNullOfOrNull { it.rawValue }
                        val now = System.currentTimeMillis()
                        if (code != null && (code != lastCode || now - lastAt > 1500)) { lastCode = code; lastAt = now; latest(code) }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            try { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(c))
        view
    })
}

/**
 * Full-screen scanner. [onCode] returns a status line to show (e.g. "✓ Coke added"), or null to ignore.
 * Set [closeOnFirst] to close after the first accepted scan (used when filling a barcode field).
 */
@Composable
fun ScannerDialog(title: String, onCode: suspend (String) -> String?, onClose: () -> Unit, closeOnFirst: Boolean = false, footer: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var status by remember { mutableStateOf("Point the camera at a barcode") }
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
    DisposableEffect(Unit) { onDispose { tone.release() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    TextButton(onClose) { Text("Done") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                    if (granted) {
                        CameraPreviewScanner(Modifier.fillMaxSize()) { code ->
                            scope.launch {
                                val msg = onCode(code)
                                if (msg != null) { status = msg; tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120); if (closeOnFirst) onClose() }
                            }
                        }
                        Box(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(140.dp).border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)))
                    } else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Camera permission is needed to scan barcodes.")
                        Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    }
                }
                Column(Modifier.padding(16.dp)) { Text(status, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold); footer() }
            }
        }
    }
}
