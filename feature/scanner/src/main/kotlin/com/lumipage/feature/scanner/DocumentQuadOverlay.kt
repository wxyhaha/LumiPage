package com.lumipage.feature.scanner

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lumipage.core.model.DocumentQuad

@Composable
fun DocumentQuadOverlay(
    quad: DocumentQuad?,
    showGrid: Boolean,
    modifier: Modifier = Modifier,
) {
    // 保留上次有效四边形，避免丢锁时角点向 Offset.Zero 飞回产生闪动
    var lastValidQuad by remember { mutableStateOf(quad) }
    if (quad != null) {
        lastValidQuad = quad
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (quad != null) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "quadAlpha",
    )

    val active = quad ?: lastValidQuad
    val animSpec = spring<Offset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val tl by animateOffsetAsState(
        targetValue = active?.topLeft?.let { Offset(it.x, it.y) } ?: Offset(0.1f, 0.1f),
        animationSpec = animSpec,
        label = "tl",
    )
    val tr by animateOffsetAsState(
        targetValue = active?.topRight?.let { Offset(it.x, it.y) } ?: Offset(0.9f, 0.1f),
        animationSpec = animSpec,
        label = "tr",
    )
    val br by animateOffsetAsState(
        targetValue = active?.bottomRight?.let { Offset(it.x, it.y) } ?: Offset(0.9f, 0.9f),
        animationSpec = animSpec,
        label = "br",
    )
    val bl by animateOffsetAsState(
        targetValue = active?.bottomLeft?.let { Offset(it.x, it.y) } ?: Offset(0.1f, 0.9f),
        animationSpec = animSpec,
        label = "bl",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (showGrid) {
            val gridColor = Color.White.copy(alpha = 0.22f)
            val stroke = 1.dp.toPx()
            for (i in 1..2) {
                val x = size.width * (i / 3f)
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                val y = size.height * (i / 3f)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
        }

        if (active != null && overlayAlpha > 0.01f) {
            val pts = listOf(
                Offset(tl.x * size.width, tl.y * size.height),
                Offset(tr.x * size.width, tr.y * size.height),
                Offset(br.x * size.width, br.y * size.height),
                Offset(bl.x * size.width, bl.y * size.height),
            )

            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                lineTo(pts[1].x, pts[1].y)
                lineTo(pts[2].x, pts[2].y)
                lineTo(pts[3].x, pts[3].y)
                close()
            }

            val borderColor = Color(0xFF22C55E).copy(alpha = overlayAlpha)

            drawPath(
                path = path,
                color = borderColor.copy(alpha = 0.14f * overlayAlpha),
            )
            drawPath(
                path = path,
                color = borderColor,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            pts.forEach { corner ->
                drawCircle(
                    color = borderColor,
                    radius = 7.dp.toPx(),
                    center = corner,
                )
                drawCircle(
                    color = Color.White.copy(alpha = overlayAlpha),
                    radius = 3.5.dp.toPx(),
                    center = corner,
                )
            }
        }
    }
}
