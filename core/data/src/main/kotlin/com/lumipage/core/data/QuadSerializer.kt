package com.lumipage.core.data

import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.NormalizedPoint

internal object QuadSerializer {
    fun encode(quad: DocumentQuad): String = listOf(
        quad.topLeft.x,
        quad.topLeft.y,
        quad.topRight.x,
        quad.topRight.y,
        quad.bottomRight.x,
        quad.bottomRight.y,
        quad.bottomLeft.x,
        quad.bottomLeft.y,
    ).joinToString(",")

    fun decode(raw: String): DocumentQuad {
        val parts = raw.split(",").mapNotNull { it.toFloatOrNull() }
        if (parts.size != 8) return DocumentQuad.DEFAULT_INSET
        return DocumentQuad(
            topLeft = NormalizedPoint(parts[0], parts[1]),
            topRight = NormalizedPoint(parts[2], parts[3]),
            bottomRight = NormalizedPoint(parts[4], parts[5]),
            bottomLeft = NormalizedPoint(parts[6], parts[7]),
        ).coerced()
    }
}
