package com.jepongdevxyz.remote.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.jepongdevxyz.remote.core.DeviceIdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private class QrAnalyzer(private val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()
    override fun analyze(image: ImageProxy) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val source = PlanarYUVLuminanceSource(
                bytes, image.width, image.height, 0, 0, image.width, image.height, false
            )
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)))
            onQr(result.text)
        } catch (e: Exception) {
            // no QR in this frame
        } finally {
            image.close()
        }
    }
}

@Composable
fun QrScanScreen(navController: NavController) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(ctx) }
    val found = remember { AtomicBoolean(false) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }
    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    fun onQr(text: String) {
        if (!found.compareAndSet(false, true)) return
        if (DeviceIdManager.isValidDeviceId(text)) {
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.set("scanned_id", DeviceIdManager.formatInput(text))
            navController.popBackStack()
        } else {
            found.set(false)
        }
    }

    LaunchedEffect(hasPermission, previewView) {
        val pv = previewView ?: return@LaunchedEffect
        if (!hasPermission) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val p = ProcessCameraProvider.getInstance(ctx).get()
            val preview = Preview.Builder().build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(mainExecutor, QrAnalyzer(::onQr))
            withContext(Dispatchers.Main) {
                preview.setSurfaceProvider(pv.surfaceProvider)
                p.unbindAll()
                camera = p.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                provider = p
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll() }
    }

    Box(Modifier.fillMaxSize()) {
        if (hasPermission) {
            AndroidView(
                factory = { c ->
                    PreviewView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        previewView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // viewfinder overlay
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * 0.7f
                val h = w
                val left = (size.width - w) / 2f
                val top = (size.height - h) / 2f
                drawRect(Color.Black.copy(alpha = 0.55f))
                drawRect(
                    Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    blendMode = BlendMode.Clear
                )
                drawRoundRect(
                    Color(0xFF00BFFF),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(24f, 24f),
                    style = Stroke(width = 6f)
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Camera permission is needed to scan QR codes.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }

        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        TextButton(
            onClick = {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Text(if (torchOn) "Torch off" else "Torch on", color = Color.White)
        }
    }
}
