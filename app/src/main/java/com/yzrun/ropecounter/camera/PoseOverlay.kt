package com.yzrun.ropecounter.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.yzrun.ropecounter.vision.PoseFrame
import kotlin.math.max

@Composable
fun PoseOverlay(
    frame: PoseFrame?,
    mirrorHorizontally: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val current = frame ?: return@Canvas
        if (current.imageWidth <= 0 || current.imageHeight <= 0) return@Canvas

        val scale = max(
            size.width / current.imageWidth.toFloat(),
            size.height / current.imageHeight.toFloat(),
        )
        val drawnWidth = current.imageWidth * scale
        val drawnHeight = current.imageHeight * scale
        val offsetX = (size.width - drawnWidth) / 2f
        val offsetY = (size.height - drawnHeight) / 2f

        fun mapped(index: Int): Offset? {
            val point = current[index] ?: return null
            if (point.visibility < MIN_VISIBILITY) return null
            val x = if (mirrorHorizontally) 1f - point.x else point.x
            return Offset(
                x = offsetX + x * current.imageWidth * scale,
                y = offsetY + point.y * current.imageHeight * scale,
            )
        }

        CONNECTIONS.forEach { (startIndex, endIndex) ->
            val start = mapped(startIndex) ?: return@forEach
            val end = mapped(endIndex) ?: return@forEach
            drawLine(
                color = SkeletonColor.copy(alpha = 0.86f),
                start = start,
                end = end,
                strokeWidth = 5f,
                cap = StrokeCap.Round,
            )
        }
        IMPORTANT_POINTS.forEach { index ->
            mapped(index)?.let { point ->
                drawCircle(color = Color.White, radius = 6.5f, center = point)
                drawCircle(color = SkeletonColor, radius = 4f, center = point)
            }
        }
    }
}

private val SkeletonColor = Color(0xFFB8F35A)
private const val MIN_VISIBILITY = 0.35f
private val IMPORTANT_POINTS = intArrayOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
private val CONNECTIONS = arrayOf(
    11 to 12,
    11 to 13,
    13 to 15,
    12 to 14,
    14 to 16,
    11 to 23,
    12 to 24,
    23 to 24,
    23 to 25,
    25 to 27,
    24 to 26,
    26 to 28,
)
