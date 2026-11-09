package com.yohaku.expensetracker.ui.features.scan

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

/** Scanner-specific compositions; typography and controls retain the book's existing roles. */
@Composable internal fun Modifier.scanPaper(): Modifier {
    val t = LocalYohaku.current
    return background(t.bg).drawBehind {
        if (t.id == "journal") {
            var y = 28.dp.toPx()
            while (y < size.height) {
                drawLine(t.line.copy(alpha = .45f), Offset(0f, y), Offset(size.width, y), .5.dp.toPx())
                y += 28.dp.toPx()
            }
            drawLine(t.warm.copy(alpha = .3f), Offset(12.dp.toPx(), 0f), Offset(12.dp.toPx(), size.height), .7.dp.toPx())
        }
    }
}

@Composable internal fun ScanHeading(title: String, step: String, action: String, tag: String, onAction: () -> Unit) {
    val t = LocalYohaku.current
    when (t.id) {
        "journal" -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).drawBehind {
                drawLine(t.warm, Offset.Zero.copy(y = size.height), Offset(size.width, size.height - 2.dp.toPx()), 1.dp.toPx())
            }.padding(bottom = 6.dp)) {
                YText(word("小票手帖", "レシートノート"), 11, color = t.warm)
                YText(title, 22, lineHeightMultiplier = 1.3f)
            }
            YButton(action, onAction, Modifier.testTag(tag))
        }
        "objects" -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScanSymbol(Modifier.size(40.dp), false)
            Column(Modifier.weight(1f)) {
                YText(word("小票收纳", "レシートの整理"), 10, color = t.muted)
                YText(title, 19, lineHeightMultiplier = 1.3f)
            }
            YButton(action, onAction, Modifier.testTag(tag))
        }
        else -> Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                YText(word("YOHAKU / 小票", "YOHAKU / レシート"), 10, letterSpacing = 1.2f)
                YText(step, 12, number = true)
            }
            Row(Modifier.fillMaxWidth().border(1.dp, t.line).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                YText(title, 22, Modifier.weight(1f), lineHeightMultiplier = 1.3f)
                YButton(action, onAction, Modifier.testTag(tag))
            }
        }
    }
}

/** The edge rail belongs to the frame's paper/enamel margin, never to receipt content. */
@Composable internal fun ScanFrame(modifier: Modifier = Modifier, progress: Float = 0f, content: @Composable BoxScope.() -> Unit) {
    val t = LocalYohaku.current
    val shape = when (t.id) {
        "journal" -> RoundedCornerShape(2.dp)
        "objects" -> RoundedCornerShape(26.dp)
        else -> RoundedCornerShape(0.dp)
    }
    Box(modifier.padding(top = if (t.id == "journal") 8.dp else 0.dp)) {
        Box(Modifier.fillMaxSize().then(if (t.id == "objects") Modifier.shadow(3.dp, shape) else Modifier)
            .background(if (t.id == "objects") t.soft else t.surface, shape)
            .border(1.dp, if (t.id == "magazine") t.ink else t.line, shape)
            .padding(if (t.id == "magazine") 7.dp else 12.dp)) {
            Box(Modifier.fillMaxSize().clip(if (t.id == "objects") RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp)), contentAlignment = Alignment.Center, content = content)
        }
        if (t.id == "journal") {
            Box(Modifier.align(Alignment.TopStart).offset(x = 18.dp, y = (-5).dp).rotate(-5f).size(44.dp, 12.dp).background(t.line.copy(alpha = .85f)))
            Box(Modifier.align(Alignment.TopEnd).offset(x = (-18).dp, y = (-5).dp).rotate(5f).size(44.dp, 12.dp).background(t.soft.copy(alpha = .9f)))
        }
        if (progress > 0f) ScanEdgeProgress(progress, Modifier.matchParentSize())
    }
}

@Composable private fun ScanEdgeProgress(progress: Float, modifier: Modifier) {
    val t = LocalYohaku.current
    Canvas(modifier.testTag("scan-auto-progress").semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
        val inset = (if (t.id == "magazine") 3.5f else 6f).dp.toPx()
        val rect = Rect(Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2))
        val path = Path().apply {
            if (t.id == "objects") addRoundRect(RoundRect(rect, CornerRadius(21.dp.toPx())))
            else { moveTo(rect.left, rect.top); lineTo(rect.right, rect.top); lineTo(rect.right, rect.bottom); lineTo(rect.left, rect.bottom); close() }
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val segment = Path().also { measure.getSegment(0f, measure.length * progress, it, true) }
        drawPath(path, t.line, style = Stroke(1.dp.toPx()))
        // Thick enough to read at a glance in the frame margin; it never overlaps the receipt preview.
        drawPath(segment, if (t.id == "journal") t.warm else t.accent, style = Stroke(
            (if (t.id == "objects") 6f else if (t.id == "magazine") 4f else 3f).dp.toPx(), cap = StrokeCap.Round,
            pathEffect = if (t.id == "journal") PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 3.dp.toPx())) else null))
    }
}

@Composable internal fun ScanCaption(text: String, modifier: Modifier = Modifier, attention: Boolean = false) {
    val t = LocalYohaku.current
    val frame = when (t.id) {
        "journal" -> Modifier.drawBehind { drawLine(t.warm, Offset(0f, 4.dp.toPx()), Offset(0f, size.height - 4.dp.toPx()), 2.dp.toPx()) }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
        "objects" -> Modifier.background(t.soft, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 7.dp)
        else -> Modifier.drawBehind { drawLine(t.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }.padding(top = 7.dp)
    }
    YText(text, if (t.id == "journal") 14 else 12, modifier.then(frame), color = if (attention) t.warm else t.ink, lineHeightMultiplier = 1.4f)
}

/** Small camera/paper symbol also anchors permission, processing and error states. */
@Composable private fun ScanSymbol(modifier: Modifier, processing: Boolean) {
    val t = LocalYohaku.current
    Canvas(modifier) {
        val w = size.width; val h = size.height
        when (t.id) {
            "journal" -> {
                rotate(-5f) {
                    drawRect(t.surface, Offset(w * .2f, h * .12f), Size(w * .6f, h * .76f))
                    drawRect(t.accent, Offset(w * .2f, h * .12f), Size(w * .6f, h * .76f), style = Stroke(1.dp.toPx()))
                    for (i in 0..2) drawLine(t.line, Offset(w * .3f, h * (.35f + i * .15f)), Offset(w * .68f, h * (.35f + i * .15f)), 1.dp.toPx())
                }
                drawArc(t.warm, -70f, if (processing) 270f else 330f, false, Offset(w * .05f, h * .05f), Size(w * .9f, h * .9f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            }
            "objects" -> {
                drawRoundRect(t.line, Offset(0f, h * .18f), Size(w, h * .72f), CornerRadius(w * .2f))
                drawRoundRect(t.soft, Offset(0f, h * .1f), Size(w, h * .72f), CornerRadius(w * .2f))
                drawCircle(t.surface, w * .25f, Offset(w * .5f, h * .46f))
                drawCircle(t.accent, w * .17f, Offset(w * .5f, h * .46f), style = Stroke(2.dp.toPx()))
                drawCircle(t.warm, w * .045f, Offset(w * .82f, h * .28f))
            }
            else -> {
                drawRect(t.ink, Offset(w * .18f, h * .08f), Size(w * .64f, h * .84f), style = Stroke(1.dp.toPx()))
                for (i in 0..3) drawLine(t.line, Offset(w * .3f, h * (.28f + i * .14f)), Offset(w * .7f, h * (.28f + i * .14f)), 1.dp.toPx())
                drawLine(t.accent, Offset(0f, h * .55f), Offset(w, h * .55f), 2.dp.toPx())
                drawRect(t.accent, Offset(w * .88f, h * .51f), Size(w * .08f, h * .08f))
            }
        }
    }
}

@Composable internal fun ScanStatus(text: String, modifier: Modifier = Modifier, processing: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val t = LocalYohaku.current
    Column(modifier.semantics(mergeDescendants = true) {}.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ScanSymbol(Modifier.size(76.dp), processing)
        YText(text, 17, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeightMultiplier = 1.5f)
        if (processing) {
            // Indeterminate: these moving marks deliberately do not imply a percentage of inference.
            val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "scan-processing")
            val phase = transition.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1400), androidx.compose.animation.core.RepeatMode.Restart), label = "scan-processing-phase")
            Canvas(Modifier.size(96.dp, 8.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
                for (i in 0..4) {
                    val color = (if (t.id == "journal") t.warm else t.accent).copy(alpha = if (i == (phase.value * 5).toInt()) 1f else .22f)
                    val p = Offset(size.width * (i + .5f) / 5, center.y)
                    if (t.id == "magazine") drawRect(color, p - Offset(3.dp.toPx(), 2.dp.toPx()), Size(6.dp.toPx(), 4.dp.toPx()))
                    else drawCircle(color, 3.dp.toPx(), p, style = if (t.id == "journal") Stroke(1.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill)
                }
            }
        }
        action?.invoke()
    }
}
