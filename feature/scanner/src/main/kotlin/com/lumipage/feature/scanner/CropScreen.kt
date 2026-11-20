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

private enum class DragCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
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

    var draggingCorner by remember(activePage.id) { mutableStateOf<DragCorner?>(null) }

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
                        draggingCorner = draggingCorner,
                        onDragStart = { draggingCorner = it },
                        onDragEnd = { draggingCorner = null },
                        onQuadChange = onQuadChange,
                    )
                }

                // 拖拽角点时在顶部角落展示局部放大镜 (Loupe)
                val activeCorner = draggingCorner
                if (activeCorner != null) {
                    val normPt = when (activeCorner) {
                        DragCorner.TOP_LEFT -> activePage.quad.topLeft
                        DragCorner.TOP_RIGHT -> activePage.quad.topRight
                        DragCorner.BOTTOM_RIGHT -> activePage.quad.bottomRight
                        DragCorner.BOTTOM_LEFT -> activePage.quad.bottomLeft
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
    draggingCorner: DragCorner?,
    onDragStart: (DragCorner) -> Unit,
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
                var activeCorner: DragCorner? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        val corners = listOf(
                            DragCorner.TOP_LEFT to Offset(currentQuad.topLeft.x * renderWidth, currentQuad.topLeft.y * renderHeight),
                            DragCorner.TOP_RIGHT to Offset(currentQuad.topRight.x * renderWidth, currentQuad.topRight.y * renderHeight),
                            DragCorner.BOTTOM_RIGHT to Offset(currentQuad.bottomRight.x * renderWidth, currentQuad.bottomRight.y * renderHeight),
                            DragCorner.BOTTOM_LEFT to Offset(currentQuad.bottomLeft.x * renderWidth, currentQuad.bottomLeft.y * renderHeight),
                        )
                        val nearest = corners.minByOrNull { (_, pos) ->
                            hypot(pos.x - offset.x, pos.y - offset.y)
                        }
                        if (nearest != null && hypot(nearest.second.x - offset.x, nearest.second.y - offset.y) <= hitRadiusPx) {
                            activeCorner = nearest.first
                            onDragStart(nearest.first)
                        }
                    },
                    onDragEnd = {
                        activeCorner = null
                        onDragEnd()
                    },
                    onDragCancel = {
                        activeCorner = null
                        onDragEnd()
                    },
                    onDrag = { change, dragAmount ->
                        val corner = activeCorner ?: return@detectDragGestures
                        change.consume()
                        val dx = dragAmount.x / renderWidth
                        val dy = dragAmount.y / renderHeight
                        val updated = when (corner) {
                            DragCorner.TOP_LEFT -> currentQuad.copy(
                                topLeft = NormalizedPoint(currentQuad.topLeft.x + dx, currentQuad.topLeft.y + dy).coerced(),
                            )
                            DragCorner.TOP_RIGHT -> currentQuad.copy(
                                topRight = NormalizedPoint(currentQuad.topRight.x + dx, currentQuad.topRight.y + dy).coerced(),
                            )
                            DragCorner.BOTTOM_RIGHT -> currentQuad.copy(
                                bottomRight = NormalizedPoint(currentQuad.bottomRight.x + dx, currentQuad.bottomRight.y + dy).coerced(),
                            )
                            DragCorner.BOTTOM_LEFT -> currentQuad.copy(
                                bottomLeft = NormalizedPoint(currentQuad.bottomLeft.x + dx, currentQuad.bottomLeft.y + dy).coerced(),
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

        drawPath(
            path = quadPath,
            color = Color(0xFF38BDF8).copy(alpha = 0.14f),
        )
        drawPath(
            path = quadPath,
            color = Color(0xFF38BDF8),
            style = Stroke(width = 2.5.dp.toPx()),
        )

        val corners = listOf(
            DragCorner.TOP_LEFT,
            DragCorner.TOP_RIGHT,
            DragCorner.BOTTOM_RIGHT,
            DragCorner.BOTTOM_LEFT,
        )
        pts.forEachIndexed { index, pos ->
            val isDragging = corners[index] == draggingCorner
            drawCircle(
                color = if (isDragging) Color(0xFF22C55E) else Color(0xFF38BDF8),
                radius = if (isDragging) 14.dp.toPx() else 11.dp.toPx(),
                center = pos,
            )
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
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
