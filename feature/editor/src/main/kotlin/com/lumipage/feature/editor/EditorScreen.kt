package com.lumipage.feature.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumipage.core.common.BitmapLoader
import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.EnhancementFilter
import com.lumipage.core.model.NormalizedPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorRoute(
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onSaveCompleted: (List<EditedPageOutput>) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EditorViewModel.EditorEvent.SaveCompleted -> onSaveCompleted(event.outputs)
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

    val activePage = uiState.activePage
    BackHandler {
        if (uiState.isRecropping) {
            viewModel.cancelRecrop()
        } else {
            onNavigateBack()
        }
    }

    if (uiState.isRecropping && activePage != null) {
        RecropScreen(
            originalPath = activePage.originalPath,
            initialQuad = activePage.editState.quad,
            rotationDegrees = activePage.editState.normalizedRotation,
            onCancel = viewModel::cancelRecrop,
            onConfirm = viewModel::confirmRecrop,
        )
        return
    }

    Scaffold(
        containerColor = Color(0xFF0F172A),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.pages.size > 1) {
                            "${stringResource(R.string.editor_title)} (${uiState.activePageIndex + 1}/${uiState.pages.size})"
                        } else {
                            stringResource(R.string.editor_title)
                        },
                        color = Color.White,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    if (uiState.pages.size > 1) {
                        TextButton(onClick = viewModel::applyEnhancementToAllPages) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.editor_apply_all), color = Color(0xFF38BDF8))
                        }
                    }
                    Button(
                        onClick = viewModel::saveAndFinish,
                        enabled = !uiState.isSaving,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.editor_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A)),
            )
        },
        bottomBar = {
            EditorBottomPanel(
                uiState = uiState,
                onSelectPage = viewModel::selectPage,
                onSelectTab = viewModel::selectTab,
                onSelectFilter = viewModel::selectFilter,
                onBrightnessChange = viewModel::updateBrightness,
                onContrastChange = viewModel::updateContrast,
                onSharpnessChange = viewModel::updateSharpness,
                onResetAdjustments = viewModel::resetAdjustments,
                onRotate90 = viewModel::rotateActivePage90,
                onStartRecrop = viewModel::startRecrop,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val displayBitmap = if (uiState.isComparingOriginal) {
                uiState.warpedPreviewBitmap
            } else {
                uiState.enhancedPreviewBitmap ?: uiState.warpedPreviewBitmap
            }

            if (displayBitmap != null) {
                val imageBitmap = remember(displayBitmap) { displayBitmap.asImageBitmap() }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (uiState.isLoadingPreview || uiState.isSaving) {
                CircularProgressIndicator(color = Color.White)
            }

            // 右上角“按住对比原图”按钮
            if (uiState.warpedPreviewBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (uiState.isComparingOriginal) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Black.copy(alpha = 0.6f)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    viewModel.setComparingOriginal(true)
                                    tryAwaitRelease()
                                    viewModel.setComparingOriginal(false)
                                },
                            )
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Compare,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isComparingOriginal) "原图" else stringResource(R.string.editor_compare_original),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorBottomPanel(
    uiState: EditorUiState,
    onSelectPage: (Int) -> Unit,
    onSelectTab: (EditorToolTab) -> Unit,
    onSelectFilter: (EnhancementFilter) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSharpnessChange: (Float) -> Unit,
    onResetAdjustments: () -> Unit,
    onRotate90: () -> Unit,
    onStartRecrop: () -> Unit,
) {
    val activeEnhancement = uiState.activePage?.editState?.enhancement ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E293B))
            .padding(bottom = 12.dp),
    ) {
        if (uiState.pages.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                itemsIndexed(uiState.pages) { index, _ ->
                    val selected = index == uiState.activePageIndex
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF334155),
                        modifier = Modifier.clickable { onSelectPage(index) },
                    ) {
                        Text(
                            text = "第 ${index + 1} 页",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // 工具栏 Tab + 旋转/重裁快捷按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabRow(
                selectedTabIndex = uiState.activeTab.ordinal,
                containerColor = Color.Transparent,
                contentColor = Color.White,
                modifier = Modifier.weight(1f),
            ) {
                Tab(
                    selected = uiState.activeTab == EditorToolTab.FILTERS,
                    onClick = { onSelectTab(EditorToolTab.FILTERS) },
                    text = { Text(stringResource(R.string.editor_tab_filters)) },
                )
                Tab(
                    selected = uiState.activeTab == EditorToolTab.ADJUST,
                    onClick = { onSelectTab(EditorToolTab.ADJUST) },
                    text = { Text(stringResource(R.string.editor_tab_adjust)) },
                )
            }
            IconButton(onClick = onRotate90) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = stringResource(R.string.editor_rotate), tint = Color.White)
            }
            IconButton(onClick = onStartRecrop) {
                Icon(Icons.Default.Crop, contentDescription = stringResource(R.string.editor_recrop), tint = Color.White)
            }
        }

        when (uiState.activeTab) {
            EditorToolTab.FILTERS -> {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    items(uiState.filterThumbnails, key = { it.filter.name }) { item ->
                        val selected = item.filter == activeEnhancement.filter
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(74.dp)
                                .clickable { onSelectFilter(item.filter) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 66.dp, height = 82.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) Color(0xFF38BDF8) else Color(0xFF475569),
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .background(Color(0xFF0F172A)),
                            ) {
                                val thumb = item.thumbnail
                                if (thumb != null) {
                                    Image(
                                        bitmap = remember(thumb) { thumb.asImageBitmap() },
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = filterLabel(item.filter),
                                color = if (selected) Color(0xFF38BDF8) else Color(0xFFCBD5E1),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            EditorToolTab.ADJUST -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SliderRow(
                        label = stringResource(R.string.editor_brightness),
                        value = activeEnhancement.brightness,
                        valueRange = -1f..1f,
                        onValueChange = onBrightnessChange,
                    )
                    SliderRow(
                        label = stringResource(R.string.editor_contrast),
                        value = activeEnhancement.contrast,
                        valueRange = 0.5f..2.0f,
                        onValueChange = onContrastChange,
                    )
                    SliderRow(
                        label = stringResource(R.string.editor_sharpness),
                        value = activeEnhancement.sharpness,
                        valueRange = 0f..1f,
                        onValueChange = onSharpnessChange,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onResetAdjustments) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.editor_reset_adjust), color = Color(0xFF94A3B8))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = String.format("%.2f", value),
            color = Color(0xFF94A3B8),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(42.dp),
        )
    }
}

@Composable
private fun filterLabel(filter: EnhancementFilter): String = when (filter) {
    EnhancementFilter.ORIGINAL -> stringResource(R.string.filter_original)
    EnhancementFilter.AUTO_ENHANCE -> stringResource(R.string.filter_auto)
    EnhancementFilter.DOCUMENT_COLOR -> stringResource(R.string.filter_color)
    EnhancementFilter.GRAYSCALE -> stringResource(R.string.filter_grayscale)
    EnhancementFilter.BLACK_AND_WHITE -> stringResource(R.string.filter_bw)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecropScreen(
    originalPath: String,
    initialQuad: DocumentQuad,
    rotationDegrees: Int,
    onCancel: () -> Unit,
    onConfirm: (DocumentQuad) -> Unit,
) {
    var quad by remember(initialQuad) { mutableStateOf(initialQuad) }
    val bitmap by produceState<Bitmap?>(initialValue = null, originalPath, rotationDegrees) {
        value = withContext(Dispatchers.IO) {
            val raw = BitmapLoader.decodeSampledBitmap(originalPath, maxDimension = 1800) ?: return@withContext null
            if (rotationDegrees == 0) {
                raw
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated !== raw) raw.recycle()
                rotated
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F172A),
        topBar = {
            TopAppBar(
                title = { Text("重新裁剪", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消", tint = Color.White)
                    }
                },
                actions = {
                    TextButton(onClick = { quad = DocumentQuad.FULL }) {
                        Text("全图", color = Color.White)
                    }
                    Button(onClick = { onConfirm(quad) }, modifier = Modifier.padding(end = 8.dp)) {
                        Text("应用")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A)),
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = min(boxW / bmp.width.toFloat(), boxH / bmp.height.toFloat())
                val rw = bmp.width * scale
                val rh = bmp.height * scale
                val hitRadiusPx = with(density) { 42.dp.toPx() }

                Box(modifier = Modifier.size(with(density) { rw.toDp() }, with(density) { rh.toDp() })) {
                    Image(
                        bitmap = remember(bmp) { bmp.asImageBitmap() },
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(rw, rh) {
                                var cornerIdx = -1
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val pts = quad.asList().map { Offset(it.x * rw, it.y * rh) }
                                        val best = pts.withIndex().minByOrNull { hypot(it.value.x - offset.x, it.value.y - offset.y) }
                                        if (best != null && hypot(best.value.x - offset.x, best.value.y - offset.y) <= hitRadiusPx) {
                                            cornerIdx = best.index
                                        }
                                    },
                                    onDragEnd = { cornerIdx = -1 },
                                    onDragCancel = { cornerIdx = -1 },
                                    onDrag = { change, dragAmount ->
                                        if (cornerIdx !in 0..3) return@detectDragGestures
                                        change.consume()
                                        val dx = dragAmount.x / rw
                                        val dy = dragAmount.y / rh
                                        val list = quad.asList().toMutableList()
                                        list[cornerIdx] = NormalizedPoint(list[cornerIdx].x + dx, list[cornerIdx].y + dy).coerced()
                                        val next = DocumentQuad(list[0], list[1], list[2], list[3])
                                        if (next.isValidConvexQuad(minArea = 0.01f)) {
                                            quad = next
                                        }
                                    },
                                )
                            },
                    ) {
                        val pts = quad.asList().map { Offset(it.x * size.width, it.y * size.height) }
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            lineTo(pts[1].x, pts[1].y)
                            lineTo(pts[2].x, pts[2].y)
                            lineTo(pts[3].x, pts[3].y)
                            close()
                        }
                        drawPath(path, Color(0xFF38BDF8).copy(alpha = 0.14f))
                        drawPath(path, Color(0xFF38BDF8), style = Stroke(2.5.dp.toPx()))
                        pts.forEach { pt ->
                            drawCircle(Color(0xFF38BDF8), 11.dp.toPx(), pt)
                            drawCircle(Color.White, 5.dp.toPx(), pt)
                        }
                    }
                }
            }
        }
    }
}
