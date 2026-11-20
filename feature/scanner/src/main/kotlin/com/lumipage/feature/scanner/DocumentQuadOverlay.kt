package com.lumipage.feature.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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

        if (quad != null) {
            val pts = listOf(
                Offset(quad.topLeft.x * size.width, quad.topLeft.y * size.height),
                Offset(quad.topRight.x * size.width, quad.topRight.y * size.height),
                Offset(quad.bottomRight.x * size.width, quad.bottomRight.y * size.height),
                Offset(quad.bottomLeft.x * size.width, quad.bottomLeft.y * size.height),
            )

            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                lineTo(pts[1].x, pts[1].y)
                lineTo(pts[2].x, pts[2].y)
                lineTo(pts[3].x, pts[3].y)
                close()
            }

            val borderColor = Color(0xFF22C55E)

            drawPath(
                path = path,
                color = borderColor.copy(alpha = 0.14f),
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
                    color = Color.White,
                    radius = 3.5.dp.toPx(),
                    center = corner,
                )
            }
        }
    }
}
