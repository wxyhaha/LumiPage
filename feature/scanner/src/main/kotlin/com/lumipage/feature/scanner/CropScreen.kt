package com.lumipage.feature.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumipage.core.common.BitmapLoader
import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.NormalizedPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private enum class DragHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    EDGE_TOP,
    EDGE_RIGHT,
    EDGE_BOTTOM,
    EDGE_LEFT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    uiState: ScannerUiState,
    onBackToCamera: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onQuadChange: (DocumentQuad) -> Unit,
    onAutoDetect: () -> Unit,
    onFullImage: () -> Unit,
    onRotate90: () -> Unit,
    onDeletePage: () -> Unit,
    onConfirm: () -> Unit,
) {
    val activePage = uiState.activeCropPage
    if (activePage == null) {
        onBackToCamera()
        return
    }

    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = activePage.originalPath,
        key2 = activePage.rotationDegrees,
    ) {
        value = withContext(Dispatchers.IO) {
            val raw = BitmapLoader.decodeSampledBitmap(activePage.originalPath, maxDimension = 1800)
                ?: return@withContext null
            if (activePage.rotationDegrees == 0) {
                raw
            } else {
                val matrix = Matrix().apply { postRotate(activePage.rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated !== raw) raw.recycle()
                rotated
            }
        }
    }

    var draggingHandle by remember(activePage.id) { mutableStateOf<DragHandle?>(null) }

    Scaffold(
        containerColor = Color(0xFF0F172A),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "${stringResource(R.string.crop_title)} (${uiState.activeCropIndex + 1}/${uiState.draftPages.size})",
                        color = Color.White,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackToCamera) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回拍摄",
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onDeletePage) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.crop_retake),
                            tint = Color(0xFFFCA5A5),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A)),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (uiState.draftPages.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 10.dp),
                    ) {
                        itemsIndexed(uiState.draftPages) { index, page ->
                            val selected = index == uiState.activeCropIndex
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onAutoDetect) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.crop_auto_detect), color = Color.White)
                        }
                        TextButton(onClick = onFullImage) {
                            Icon(Icons.Default.CropFree, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.crop_full_image), color = Color.White)
                        }
                        IconButton(onClick = onRotate90) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = stringResource(R.string.crop_rotate), tint = Color.White)
                        }
                    }

                    Button(onClick = onConfirm) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.crop_confirm))
                    }
                }
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = previewBitmap
            if (bmp == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                val imgBitmap = remember(bmp) { bmp.asImageBitmap() }
                val density = LocalDensity.current
                val boxWidthPx = with(density) { maxWidth.toPx() }
                val boxHeightPx = with(density) { maxHeight.toPx() }

                val scale = min(boxWidthPx / bmp.width.toFloat(), boxHeightPx / bmp.height.toFloat())
                val renderW = bmp.width * scale
                val renderH = bmp.height * scale
                val renderWDp = with(density) { renderW.toDp() }
                val renderHDp = with(density) { renderH.toDp() }

                Box(
                    modifier = Modifier.size(renderWDp, renderHDp),
                ) {
                    Image(
                        bitmap = imgBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )

                    InteractiveQuadCanvas(
                        quad = activePage.quad,
                        renderWidth = renderW,
                        renderHeight = renderH,
                        draggingHandle = draggingHandle,
                        onDragStart = { draggingHandle = it },
                        onDragEnd = { draggingHandle = null },
                        onQuadChange = onQuadChange,
                    )
                }

                // 拖拽角点时在顶部角落展示局部放大镜 (Loupe)
                val activeHandle = draggingHandle
                if (activeHandle != null) {
                    val normPt = when (activeHandle) {
                        DragHandle.TOP_LEFT -> activePage.quad.topLeft
                        DragHandle.TOP_RIGHT -> activePage.quad.topRight
                        DragHandle.BOTTOM_RIGHT -> activePage.quad.bottomRight
                        DragHandle.BOTTOM_LEFT -> activePage.quad.bottomLeft
                        DragHandle.EDGE_TOP -> NormalizedPoint(
                            (activePage.quad.topLeft.x + activePage.quad.topRight.x) * 0.5f,
                            (activePage.quad.topLeft.y + activePage.quad.topRight.y) * 0.5f,
                        )
                        DragHandle.EDGE_RIGHT -> NormalizedPoint(
                            (activePage.quad.topRight.x + activePage.quad.bottomRight.x) * 0.5f,
                            (activePage.quad.topRight.y + activePage.quad.bottomRight.y) * 0.5f,
                        )
                        DragHandle.EDGE_BOTTOM -> NormalizedPoint(
                            (activePage.quad.bottomRight.x + activePage.quad.bottomLeft.x) * 0.5f,
                            (activePage.quad.bottomRight.y + activePage.quad.bottomLeft.y) * 0.5f,
                        )
                        DragHandle.EDGE_LEFT -> NormalizedPoint(
                            (activePage.quad.bottomLeft.x + activePage.quad.topLeft.x) * 0.5f,
                            (activePage.quad.bottomLeft.y + activePage.quad.topLeft.y) * 0.5f,
                        )
                    }
                    val align = if (normPt.x < 0.5f) Alignment.TopEnd else Alignment.TopStart
                    CornerLoupe(
                        image = imgBitmap,
                        normalizedPoint = normPt,
                        modifier = Modifier
                            .align(align)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveQuadCanvas(
    quad: DocumentQuad,
    renderWidth: Float,
    renderHeight: Float,
    draggingHandle: DragHandle?,
    onDragStart: (DragHandle) -> Unit,
    onDragEnd: () -> Unit,
    onQuadChange: (DocumentQuad) -> Unit,
) {
    var currentQuad by remember(quad) { mutableStateOf(quad) }
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 42.dp.toPx() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(renderWidth, renderHeight) {
                var activeHandle: DragHandle? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        val tl = Offset(currentQuad.topLeft.x * renderWidth, currentQuad.topLeft.y * renderHeight)
                        val tr = Offset(currentQuad.topRight.x * renderWidth, currentQuad.topRight.y * renderHeight)
                        val br = Offset(currentQuad.bottomRight.x * renderWidth, currentQuad.bottomRight.y * renderHeight)
                        val bl = Offset(currentQuad.bottomLeft.x * renderWidth, currentQuad.bottomLeft.y * renderHeight)
                        val handles = listOf(
                            DragHandle.TOP_LEFT to tl,
                            DragHandle.TOP_RIGHT to tr,
                            DragHandle.BOTTOM_RIGHT to br,
                            DragHandle.BOTTOM_LEFT to bl,
                            DragHandle.EDGE_TOP to Offset((tl.x + tr.x) * 0.5f, (tl.y + tr.y) * 0.5f),
                            DragHandle.EDGE_RIGHT to Offset((tr.x + br.x) * 0.5f, (tr.y + br.y) * 0.5f),
                            DragHandle.EDGE_BOTTOM to Offset((br.x + bl.x) * 0.5f, (br.y + bl.y) * 0.5f),
                            DragHandle.EDGE_LEFT to Offset((bl.x + tl.x) * 0.5f, (bl.y + tl.y) * 0.5f),
                        )
                        val nearest = handles.minByOrNull { (_, pos) ->
                            hypot(pos.x - offset.x, pos.y - offset.y)
                        }
                        if (nearest != null && hypot(nearest.second.x - offset.x, nearest.second.y - offset.y) <= hitRadiusPx) {
                            activeHandle = nearest.first
                            onDragStart(nearest.first)
                        }
                    },
                    onDragEnd = {
                        activeHandle = null
                        onDragEnd()
                    },
                    onDragCancel = {
                        activeHandle = null
                        onDragEnd()
                    },
                    onDrag = { change, dragAmount ->
                        val handle = activeHandle ?: return@detectDragGestures
                        change.consume()
                        val dx = dragAmount.x / renderWidth
                        val dy = dragAmount.y / renderHeight
                        val updated = when (handle) {
                            DragHandle.TOP_LEFT -> currentQuad.copy(
                                topLeft = NormalizedPoint(currentQuad.topLeft.x + dx, currentQuad.topLeft.y + dy).coerced(),
                            )
                            DragHandle.TOP_RIGHT -> currentQuad.copy(
                                topRight = NormalizedPoint(currentQuad.topRight.x + dx, currentQuad.topRight.y + dy).coerced(),
                            )
                            DragHandle.BOTTOM_RIGHT -> currentQuad.copy(
                                bottomRight = NormalizedPoint(currentQuad.bottomRight.x + dx, currentQuad.bottomRight.y + dy).coerced(),
                            )
                            DragHandle.BOTTOM_LEFT -> currentQuad.copy(
                                bottomLeft = NormalizedPoint(currentQuad.bottomLeft.x + dx, currentQuad.bottomLeft.y + dy).coerced(),
                            )
                            DragHandle.EDGE_TOP -> currentQuad.copy(
                                topLeft = NormalizedPoint(currentQuad.topLeft.x + dx, currentQuad.topLeft.y + dy).coerced(),
                                topRight = NormalizedPoint(currentQuad.topRight.x + dx, currentQuad.topRight.y + dy).coerced(),
                            )
                            DragHandle.EDGE_RIGHT -> currentQuad.copy(
                                topRight = NormalizedPoint(currentQuad.topRight.x + dx, currentQuad.topRight.y + dy).coerced(),
                                bottomRight = NormalizedPoint(currentQuad.bottomRight.x + dx, currentQuad.bottomRight.y + dy).coerced(),
                            )
                            DragHandle.EDGE_BOTTOM -> currentQuad.copy(
                                bottomRight = NormalizedPoint(currentQuad.bottomRight.x + dx, currentQuad.bottomRight.y + dy).coerced(),
                                bottomLeft = NormalizedPoint(currentQuad.bottomLeft.x + dx, currentQuad.bottomLeft.y + dy).coerced(),
                            )
                            DragHandle.EDGE_LEFT -> currentQuad.copy(
                                bottomLeft = NormalizedPoint(currentQuad.bottomLeft.x + dx, currentQuad.bottomLeft.y + dy).coerced(),
                                topLeft = NormalizedPoint(currentQuad.topLeft.x + dx, currentQuad.topLeft.y + dy).coerced(),
                            )
                        }
                        if (updated.isValidConvexQuad(minArea = 0.01f)) {
                            currentQuad = updated
                            onQuadChange(updated)
                        }
                    },
                )
            },
    ) {
        val pts = listOf(
            Offset(quad.topLeft.x * size.width, quad.topLeft.y * size.height),
            Offset(quad.topRight.x * size.width, quad.topRight.y * size.height),
            Offset(quad.bottomRight.x * size.width, quad.bottomRight.y * size.height),
            Offset(quad.bottomLeft.x * size.width, quad.bottomLeft.y * size.height),
        )

        val quadPath = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
        }

        // 框外半透明暗色遮罩（EvenOdd 挖空选区）
        val scrimPath = Path().apply {
            fillType = PathFillType.EvenOdd
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
        }
        drawPath(
            path = scrimPath,
            color = Color.Black.copy(alpha = 0.45f),
        )

        drawPath(
            path = quadPath,
            color = Color(0xFF22C55E),
            style = Stroke(width = 2.5.dp.toPx()),
        )

        // 绘制 4 个角点
        val corners = listOf(
            DragHandle.TOP_LEFT,
            DragHandle.TOP_RIGHT,
            DragHandle.BOTTOM_RIGHT,
            DragHandle.BOTTOM_LEFT,
        )
        pts.forEachIndexed { index, pos ->
            val isDragging = corners[index] == draggingHandle
            drawCircle(
                color = if (isDragging) Color(0xFF38BDF8) else Color(0xFF22C55E),
                radius = if (isDragging) 14.dp.toPx() else 11.dp.toPx(),
                center = pos,
            )
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = pos,
            )
        }

        // 绘制 4 条边中点控制手柄（支持单边平行微调）
        val edgeHandles = listOf(
            DragHandle.EDGE_TOP to Offset((pts[0].x + pts[1].x) * 0.5f, (pts[0].y + pts[1].y) * 0.5f),
            DragHandle.EDGE_RIGHT to Offset((pts[1].x + pts[2].x) * 0.5f, (pts[1].y + pts[2].y) * 0.5f),
            DragHandle.EDGE_BOTTOM to Offset((pts[2].x + pts[3].x) * 0.5f, (pts[2].y + pts[3].y) * 0.5f),
            DragHandle.EDGE_LEFT to Offset((pts[3].x + pts[0].x) * 0.5f, (pts[3].y + pts[0].y) * 0.5f),
        )
        edgeHandles.forEach { (handle, pos) ->
            val isDragging = handle == draggingHandle
            drawCircle(
                color = if (isDragging) Color(0xFF38BDF8) else Color.White,
                radius = if (isDragging) 9.dp.toPx() else 6.5.dp.toPx(),
                center = pos,
            )
            drawCircle(
                color = Color(0xFF22C55E),
                radius = 3.5.dp.toPx(),
                center = pos,
            )
        }
    }
}

@Composable
private fun CornerLoupe(
    image: ImageBitmap,
    normalizedPoint: NormalizedPoint,
    modifier: Modifier = Modifier,
) {
    val loupeSize = 108.dp
    Box(
        modifier = modifier
            .size(loupeSize)
            .clip(CircleShape)
            .background(Color.Black)
            .border(2.5.dp, Color(0xFF38BDF8), CircleShape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cropRadiusPx = (min(image.width, image.height) * 0.08f).roundToInt().coerceAtLeast(32)
            val cx = (normalizedPoint.x * image.width).roundToInt()
            val cy = (normalizedPoint.y * image.height).roundToInt()

            val srcLeft = (cx - cropRadiusPx).coerceIn(0, (image.width - cropRadiusPx * 2).coerceAtLeast(0))
            val srcTop = (cy - cropRadiusPx).coerceIn(0, (image.height - cropRadiusPx * 2).coerceAtLeast(0))
            val srcSize = min(cropRadiusPx * 2, min(image.width, image.height))

            drawImage(
                image = image,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize(srcSize, srcSize),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )

            // 准星
            val midX = size.width * 0.5f
            val midY = size.height * 0.5f
            drawLine(
                color = Color(0xFF22C55E),
                start = Offset(midX - 14.dp.toPx(), midY),
                end = Offset(midX + 14.dp.toPx(), midY),
                strokeWidth = 2.dp.toPx(),
            )
            drawLine(
                color = Color(0xFF22C55E),
                start = Offset(midX, midY - 14.dp.toPx()),
                end = Offset(midX, midY + 14.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}
