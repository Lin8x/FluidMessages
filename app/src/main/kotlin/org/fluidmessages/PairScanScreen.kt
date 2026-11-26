package org.fluidmessages

import android.content.SharedPreferences
import android.util.Size
import android.widget.LinearLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.function.Consumer
import java.util.function.IntConsumer

/** Tries a scanned pairing code's addresses in order and saves the first that answers (MainActivity.pairFromCode). */
fun interface PairTrier { fun pair(payload: CompanionPairing.Payload, done: Consumer<String?>) }

/**
 * Scanning FluidMessages Web's pairing QR code, as a full page (Settings → FluidMessages Web → Scan QR code, and the
 * step-by-step guide's "Enter it here"). CameraX shows the camera and ZXing reads the code, all on this phone. The
 * camera permission is asked for only when Scan QR code is tapped; without it, this page says so and offers typing
 * the address instead.
 */
object PairScanScreen {
    const val NOT_OURS = "That’s not a FluidMessages Web pairing code. In FluidMessages Web, open Settings → Phone and " +
        "other devices → Connect your phone."

    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, owner: LifecycleOwner, recolor: IntConsumer, cameraAllowed: Boolean,
              cameraProblem: String?, askCamera: Runnable, back: Runnable, typeInstead: Runnable, trier: PairTrier) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            SideEffect { recolor.accept(appearance.canvas) }
            AppTheme(appearance) {
                val scheme = MaterialTheme.colorScheme
                var status by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                // A code that just failed isn't tried again until Try again, so it doesn't loop while it's in view.
                var failed by remember { mutableStateOf<String?>(null) }
                val scanned: (String) -> Unit = handle@{ text ->
                    if (busy || text == failed) return@handle
                    val payload = CompanionPairing.parse(text)
                    if (payload == null) { status = NOT_OURS; return@handle }
                    busy = true
                    status = "Connecting…"
                    trier.pair(payload) { problem ->
                        busy = false
                        if (problem != null) { failed = text; status = problem }
                    }
                }
                Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
                    Row(Modifier.fillMaxWidth()) { TextButton(onClick = back::run) { Text("Back") } }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("Scan QR code", fontSize = 32.sp, lineHeight = 36.sp, color = scheme.onSurface, fontWeight = FontWeight.Bold)
                                Text("In FluidMessages Web, open Settings → Phone and other devices → Connect your phone. Then point this camera at the code.",
                                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (cameraAllowed) item {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(scheme.surfaceContainer)
                                .semantics { contentDescription = "Camera view" }) {
                                CameraScanner(owner) { text -> scanned(text) }
                            }
                        } else item {
                            Text(cameraProblem ?: "FluidMessages uses the camera only to read the code. Nothing is recorded or saved.",
                                color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(14.dp)).padding(14.dp))
                        }
                        status?.let { line ->
                            item {
                                Text(line, color = if (busy || line == "Connecting…") scheme.onSurface else scheme.error,
                                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (failed != null && !busy) item {
                            OutlinedButton(onClick = { failed = null; status = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("Try again")
                            }
                        }
                    }
                    if (!cameraAllowed) {
                        AppButton(onClick = askCamera::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) { Text("Allow camera") }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(onClick = typeInstead::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) {
                        Text("Type them in instead")
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

/** The camera, reading QR codes as it goes. [found] gets each code's text on the main thread. */
@Composable
private fun CameraScanner(owner: LifecycleOwner, found: (String) -> Unit) {
    val latest by rememberUpdatedState(found)
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val analyzer = remember { Executors.newSingleThreadExecutor() }
    // Leaving the page before the camera is ready: it must not turn on afterwards.
    val gone = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            gone.set(true)
            provider?.unbindAll()
            analyzer.shutdown()
        }
    }
    AndroidView(factory = { context ->
        val preview = PreviewView(context)
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameras = try { future.get() } catch (error: Exception) { return@addListener }
            if (gone.get()) return@addListener
            provider = cameras
            val shown = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val reader = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
            }
            analysis.setAnalyzer(analyzer) { image ->
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                    val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                    context.mainExecutor.execute { if (!gone.get()) latest(text) }
                } catch (error: Exception) {
                    // No code in this frame.
                } finally {
                    reader.reset()
                    image.close()
                }
            }
            try {
                cameras.unbindAll()
                cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis)
            } catch (error: Exception) {
                // No back camera: try the front one.
                try { cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, shown, analysis) } catch (ignored: Exception) {}
            }
        }, context.mainExecutor)
        preview
    }, modifier = Modifier.fillMaxSize())
}
