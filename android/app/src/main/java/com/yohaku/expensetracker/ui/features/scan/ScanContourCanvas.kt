package com.yohaku.expensetracker.ui.features.scan

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.receipt.scan.*
import com.yohaku.expensetracker.ui.theme.*
import kotlin.math.*

// The accessibility/test action goes through the very same constraint/magnifier path as touch.
internal val MoveScanHandle = SemanticsPropertyKey<AccessibilityAction<(Int, Float, Float, Boolean) -> Boolean>>("MoveScanHandle")
internal val ScanContourValue = SemanticsPropertyKey<ScanContour>("ScanContourValue")
internal val ScanMagnifierVisible = SemanticsPropertyKey<Boolean>("ScanMagnifierVisible")

@Composable internal fun ScanContourCanvas(
    bitmap: Bitmap, frame: ScanEditFrame, contour: ScanContour, onChange: (ScanContour) -> Unit, modifier: Modifier,
) {
    val t = LocalYohaku.current
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val current by rememberUpdatedState(contour)
    val changed by rememberUpdatedState(onChange)
    var active by remember { mutableIntStateOf(-1) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    val description = word("拖动左右边框上的圆点调整小票轮廓", "左右の枠の丸い点をドラッグして輪郭を調整")
    fun move(id: Int, x: Float, y: Float, dragging: Boolean): Boolean {
        if (id !in 0..9) return false
        val next = ScanContourEditor.move(current, frame, id < 5, id % 5, ScanPoint(x.toDouble(), y.toDouble()))
        active = if (dragging) id else -1
        changed(next)
        return true
    }
    Canvas(modifier.testTag("scan-contour-editor").semantics {
        contentDescription = description
        this[ScanContourValue] = contour
        this[ScanMagnifierVisible] = active >= 0
        this[MoveScanHandle] = AccessibilityAction(description, ::move)
    }.pointerInput(frame) {
        fun fit() = min(size.width.toFloat() / frame.width, size.height.toFloat() / frame.height)
        fun origin(scale: Float) = Offset((size.width - frame.width * scale) / 2, (size.height - frame.height * scale) / 2)
        detectDragGestures(onDragStart = { touch ->
            val scale = fit(); val offset = origin(scale)
            val handles = current.left + current.right
            val nearest = handles.indices.minBy { (Offset(handles[it].x.toFloat(), handles[it].y.toFloat()) * scale + offset - touch).getDistance() }
            val distance = (Offset(handles[nearest].x.toFloat(), handles[nearest].y.toFloat()) * scale + offset - touch).getDistance()
            active = if (distance <= 32.dp.toPx()) nearest else -1
            if (active >= 0) dragPosition = Offset(handles[active].x.toFloat(), handles[active].y.toFloat())
        }, onDragEnd = { active = -1 }, onDragCancel = { active = -1 }) { change, delta ->
            if (active >= 0) {
                change.consume()
                dragPosition += delta / fit()
                move(active, dragPosition.x, dragPosition.y, true)
            }
        }
    }) {
        val scale = min(size.width / frame.width, size.height / frame.height)
        val offset = Offset((size.width - frame.width * scale) / 2, (size.height - frame.height * scale) / 2)
        fun project(p: ScanPoint) = Offset(p.x.toFloat(), p.y.toFloat()) * scale + offset
        drawImage(image, dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
            dstSize = IntSize((frame.width * scale).roundToInt(), (frame.height * scale).roundToInt()), filterQuality = FilterQuality.High)
        val outline = contour.outline()
        val path = Path().apply { outline.forEachIndexed { i, p -> val q = project(p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }; close() }
        drawPath(path, t.accent.copy(alpha = .09f))
        // Curves and ends are separate rendering functions so themes can replace each independently.
        scanSide(outline.take(64).map(::project), t.accent)
        scanSide(outline.drop(64).map(::project), t.accent)
        scanEnd(project(contour.top.left), project(contour.top.right), contour.topInFrame, t.accent)
        scanEnd(project(contour.bottom.left), project(contour.bottom.right), contour.bottomInFrame, t.accent)
        (contour.left + contour.right).forEachIndexed { i, p -> scanHandle(project(p), i == active, t.accent, t.surface) }
        if (active >= 0) scanMagnifier(image, project((contour.left + contour.right)[active]), offset,
            Size(frame.width * scale, frame.height * scale), t.accent, t.surface)
    }
}

private fun DrawScope.scanSide(points: List<Offset>, color: Color) {
    val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
    drawPath(path, Color.White.copy(alpha = .8f), style = Stroke(4.dp.toPx()))
    drawPath(path, color, style = Stroke(2.dp.toPx()))
}
private fun DrawScope.scanEnd(a: Offset, b: Offset, inFrame: Boolean, color: Color) {
    drawLine(Color.White.copy(alpha = .8f), a, b, 4.dp.toPx())
    drawLine(color, a, b, 2.dp.toPx(), pathEffect = if (inFrame) null else PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
}
private fun DrawScope.scanHandle(point: Offset, active: Boolean, color: Color, surface: Color) {
    drawCircle(surface, (if (active) 9 else 7).dp.toPx(), point)
    drawCircle(color, (if (active) 9 else 7).dp.toPx(), point, style = Stroke(2.dp.toPx()))
    drawCircle(color, 2.dp.toPx(), point)
}
private fun DrawScope.scanMagnifier(image: ImageBitmap, point: Offset, imageOrigin: Offset, imageSize: Size, color: Color, surface: Color) {
    val radius = min(48.dp.toPx(), min(size.width, size.height) / 4)
    // Prefer above the finger; near the top move below. Clamp horizontally inside the viewport.
    val center = Offset(point.x.coerceIn(radius + 4, size.width - radius - 4),
        if (point.y - 76.dp.toPx() >= radius) point.y - 76.dp.toPx() else min(size.height - radius - 4, point.y + 76.dp.toPx()))
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center - Offset(radius, radius), Size(radius * 2, radius * 2))) }
    drawCircle(surface, radius + 3.dp.toPx(), center)
    clipPath(circle) {
        drawRect(surface)
        val zoom = 2.8f
        val origin = center + (imageOrigin - point) * zoom
        drawImage(image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
            dstSize = IntSize((imageSize.width * zoom).roundToInt(), (imageSize.height * zoom).roundToInt()), filterQuality = FilterQuality.High)
        drawLine(color, center - Offset(12.dp.toPx(), 0f), center + Offset(12.dp.toPx(), 0f), 1.dp.toPx())
        drawLine(color, center - Offset(0f, 12.dp.toPx()), center + Offset(0f, 12.dp.toPx()), 1.dp.toPx())
        drawCircle(color, 4.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
    }
    drawCircle(color, radius, center, style = Stroke(2.dp.toPx()))
}
