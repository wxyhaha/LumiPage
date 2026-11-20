package com.lumipage.feature.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ScannerRoute(
    viewModel: ScannerViewModel,
    onNavigateBack: () -> Unit,
    onScanCompleted: (List<ScannedDraftPage>) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val handleExitScanner = {
        viewModel.discardAllDrafts()
        onNavigateBack()
    }

    BackHandler {
        when (uiState.step) {
            ScanStep.CAMERA -> handleExitScanner()
            ScanStep.CROP -> viewModel.backToCamera()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onGalleryImagesSelected(context.contentResolver, uris)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ScannerViewModel.ScannerEvent.CompletedScan -> onScanCompleted(event.pages)
            }
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val msg = uiState.errorMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    when (uiState.step) {
        ScanStep.CAMERA -> {
            CameraScannerScreen(
                uiState = uiState,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onNavigateBack = handleExitScanner,
                onOpenGallery = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onScanCompleted = onScanCompleted,
            )
        }

        ScanStep.CROP -> {
            CropScreen(
                uiState = uiState,
                onBackToCamera = viewModel::backToCamera,
                onSelectPage = viewModel::selectCropPage,
                onQuadChange = viewModel::updateActiveQuad,
                onAutoDetect = viewModel::applyAutoQuadToActivePage,
                onFullImage = viewModel::applyFullQuadToActivePage,
                onRotate90 = viewModel::rotateActivePage90,
                onDeletePage = viewModel::removeActiveCropPage,
                onConfirm = viewModel::confirmCropAndProceed,
            )
        }
    }
}

@Composable
private fun CameraScannerScreen(
    uiState: ScannerUiState,
    viewModel: ScannerViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onOpenGallery: () -> Unit,
    onScanCompleted: (List<ScannedDraftPage>) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var previewWidth by remember { mutableIntStateOf(0) }
    var previewHeight by remember { mutableIntStateOf(0) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    // 同步闪光灯状态到 CameraX
    LaunchedEffect(uiState.flashMode, boundCamera) {
        val cam = boundCamera ?: return@LaunchedEffect
        when (uiState.flashMode) {
            FlashMode.OFF -> {
                cam.cameraControl.enableTorch(false)
                imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
            }
            FlashMode.AUTO -> {
                cam.cameraControl.enableTorch(false)
                imageCapture.flashMode = ImageCapture.FLASH_MODE_AUTO
            }
            FlashMode.TORCH -> {
                cam.cameraControl.enableTorch(true)
                imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
            }
        }
    }

    fun triggerCapture() {
        if (uiState.isCapturing) return
        viewModel.setCapturing(true)
        val outFile = viewModel.createDraftOriginalFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    viewModel.onPhotoCaptured(outFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    viewModel.setCapturing(false)
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!hasCameraPermission) {
                CameraPermissionRationale(
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    },
                    onOpenGallery = onOpenGallery,
                    onBack = onNavigateBack,
                )
            } else {
                // 标准 3:4 居中取景框（避免 20:9 全面屏左右裁掉近半视野，与专业扫描软件一致）
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .onSizeChanged { size ->
                            previewWidth = size.width
                            previewHeight = size.height
                        },
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener(
                                {
                                    previewView.post {
                                        val cameraProvider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.surfaceProvider = previewView.surfaceProvider
                                        }
                                        val analysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                            .also { imageAnalysis ->
                                                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                                    val bmp = imageProxy.toAnalysisBitmap(maxSide = 480)
                                                    imageProxy.close()
                                                    if (bmp != null && previewWidth > 0 && previewHeight > 0) {
                                                        viewModel.onFrameAnalyzed(bmp, previewWidth, previewHeight)
                                                    } else {
                                                        bmp?.recycle()
                                                    }
                                                }
                                            }

                                        runCatching {
                                            cameraProvider.unbindAll()
                                            val viewPort = previewView.viewPort
                                            if (viewPort != null) {
                                                val useCaseGroup = UseCaseGroup.Builder()
                                                    .setViewPort(viewPort)
                                                    .addUseCase(preview)
                                                    .addUseCase(imageCapture)
                                                    .addUseCase(analysis)
                                                    .build()
                                                boundCamera = cameraProvider.bindToLifecycle(
                                                    lifecycleOwner,
                                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                                    useCaseGroup,
                                                )
                                            } else {
                                                boundCamera = cameraProvider.bindToLifecycle(
                                                    lifecycleOwner,
                                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                                    preview,
                                                    imageCapture,
                                                    analysis,
                                                )
                                            }
                                        }
                                    }
                                },
                                ContextCompat.getMainExecutor(ctx),
                            )
                            previewView
                        },
                    )

                    DocumentQuadOverlay(
                        quad = uiState.previewQuad,
                        showGrid = uiState.showGrid,
                    )
                }

                // 顶部控制栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = viewModel::toggleFlashMode) {
                            Icon(
                                imageVector = when (uiState.flashMode) {
                                    FlashMode.OFF -> Icons.Default.FlashOff
                                    FlashMode.AUTO -> Icons.Default.FlashAuto
                                    FlashMode.TORCH -> Icons.Default.FlashOn
                                },
                                contentDescription = "闪光灯",
                                tint = if (uiState.flashMode == FlashMode.OFF) Color.White else Color(0xFFFACC15),
                            )
                        }
                        IconButton(onClick = viewModel::toggleGrid) {
                            Icon(
                                imageVector = if (uiState.showGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                                contentDescription = "参考网格",
                                tint = Color.White,
                            )
                        }
                    }
                }

                // 底部快门与批量控制栏
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.56f))
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        ModeChip(
                            label = stringResource(R.string.scanner_single_mode),
                            selected = !uiState.isBatchMode,
                            onClick = { if (uiState.isBatchMode) viewModel.toggleBatchMode() },
                        )
                        ModeChip(
                            label = stringResource(R.string.scanner_batch_mode),
                            selected = uiState.isBatchMode,
                            onClick = { if (!uiState.isBatchMode) viewModel.toggleBatchMode() },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // 相册导入
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenGallery() }
                                .padding(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = stringResource(R.string.scanner_import_gallery),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                text = stringResource(R.string.scanner_import_gallery),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }

                        // 快门按钮
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(74.dp)
                                .border(4.dp, Color.White, CircleShape)
                                .padding(6.dp)
                                .clip(CircleShape)
                                .background(if (uiState.isCapturing) Color.Gray else Color.White)
                                .clickable(enabled = !uiState.isCapturing) { triggerCapture() },
                        ) {
                            if (uiState.isCapturing || uiState.isProcessingImport) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp,
                                )
                            }
                        }

                        // 已拍摄队列入口
                        if (uiState.draftPages.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { viewModel.openCropQueue() },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${uiState.draftPages.size} 页",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "编辑",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.width(64.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Color.White else Color.White.copy(alpha = 0.16f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CameraPermissionRationale(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.scanner_permission_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.scanner_permission_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scanner_grant_permission))
        }
        Spacer(modifier = Modifier.height(10.dp))
        FilledTonalButton(onClick = onOpenGallery, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scanner_import_gallery))
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scanner_open_settings))
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}

private fun ImageProxy.toAnalysisBitmap(maxSide: Int): Bitmap? {
    val raw = runCatching { toBitmap() }.getOrNull() ?: return null
    val crop = cropRect
    val cropped = if (
        crop.left >= 0 && crop.top >= 0 &&
        crop.width() > 0 && crop.height() > 0 &&
        crop.right <= raw.width && crop.bottom <= raw.height &&
        (crop.width() != raw.width || crop.height() != raw.height)
    ) {
        Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height()).also {
            if (it !== raw) raw.recycle()
        }
    } else {
        raw
    }

    val rotation = imageInfo.rotationDegrees
    val maxDim = max(cropped.width, cropped.height)
    val scale = if (maxDim > maxSide) maxSide.toFloat() / maxDim.toFloat() else 1f

    if (rotation == 0 && scale >= 1f) return cropped

    val matrix = Matrix().apply {
        if (scale < 1f) postScale(scale, scale)
        if (rotation != 0) postRotate(rotation.toFloat())
    }
    val transformed = Bitmap.createBitmap(
        cropped,
        0,
        0,
        cropped.width,
        cropped.height,
        matrix,
        true,
    )
    if (transformed !== cropped) cropped.recycle()
    return transformed
}
