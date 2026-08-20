@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "MagicNumber", "MaxLineLength")

package app.ledger.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp

public object LedgerIconRegistry {
    public val all: Set<LedgerIcon> = LedgerIcon.entries.toSet()
}

/** The single icon registry. Feature modules select a semantic key and never import another icon family. */
public enum class LedgerIconTone { DEFAULT, MUTED, PRIMARY, DANGER, ON_PRIMARY }

@Composable
public fun LedgerIconView(
    icon: LedgerIcon,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tone: LedgerIconTone = LedgerIconTone.DEFAULT,
    size: Dp = LedgerTheme.dimensions.iconMd,
) {
    val tint = when (tone) {
        LedgerIconTone.DEFAULT -> LedgerTheme.colors.material.onSurface
        LedgerIconTone.MUTED -> LedgerTheme.colors.material.onSurfaceVariant
        LedgerIconTone.PRIMARY -> LedgerTheme.colors.material.primary
        LedgerIconTone.DANGER -> LedgerTheme.colors.danger.base
        LedgerIconTone.ON_PRIMARY -> LedgerTheme.colors.material.onPrimary
    }
    LedgerIconCanvas(icon, modifier, contentDescription, tint, size)
}

@Composable
internal fun LedgerIconView(
    icon: LedgerIcon,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color,
    size: Dp = LedgerTheme.dimensions.iconMd,
) = LedgerIconCanvas(icon, modifier, contentDescription, tint, size)

@Composable
private fun LedgerIconCanvas(
    icon: LedgerIcon,
    modifier: Modifier,
    contentDescription: String?,
    tint: Color,
    size: Dp,
) {
    val semanticModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    }
    Canvas(semanticModifier.size(size)) {
        val stroke = size.toPx() / 11f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        fun line(startX: Float, startY: Float, endX: Float, endY: Float) {
            drawLine(
                color = tint,
                start = Offset(startX * this.size.width, startY * this.size.height),
                end = Offset(endX * this.size.width, endY * this.size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        when (icon) {
            LedgerIcon.BACK -> {
                line(.72f, .18f, .30f, .50f)
                line(.30f, .50f, .72f, .82f)
            }
            LedgerIcon.CLOSE, LedgerIcon.CLEAR -> {
                line(.24f, .24f, .76f, .76f)
                line(.76f, .24f, .24f, .76f)
            }
            LedgerIcon.MORE -> listOf(.25f, .5f, .75f).forEach { y ->
                drawCircle(tint, stroke / 1.4f, Offset(center.x, y * this.size.height))
            }
            LedgerIcon.ADD -> {
                line(.20f, .50f, .80f, .50f)
                line(.50f, .20f, .50f, .80f)
            }
            LedgerIcon.CHECK, LedgerIcon.SAVE -> {
                line(.18f, .52f, .42f, .76f)
                line(.42f, .76f, .84f, .24f)
            }
            LedgerIcon.SEARCH -> {
                drawCircle(tint, this.size.width * .24f, Offset(this.size.width * .43f, this.size.height * .43f), style = Stroke(stroke))
                line(.61f, .61f, .83f, .83f)
            }
            LedgerIcon.ERROR, LedgerIcon.WARNING, LedgerIcon.INFO -> {
                drawCircle(tint, this.size.width * .34f, center, style = Stroke(stroke))
                line(.50f, .30f, .50f, .58f)
                drawCircle(tint, stroke / 1.5f, Offset(this.size.width * .5f, this.size.height * .72f))
            }
            LedgerIcon.CHEVRON -> {
                line(.36f, .22f, .66f, .50f)
                line(.66f, .50f, .36f, .78f)
            }
            LedgerIcon.TRANSFER -> {
                line(.18f, .36f, .80f, .36f)
                line(.66f, .22f, .80f, .36f)
                line(.80f, .64f, .18f, .64f)
                line(.32f, .78f, .18f, .64f)
            }
            LedgerIcon.REFUND -> {
                line(.78f, .32f, .35f, .32f)
                line(.35f, .32f, .20f, .48f)
                line(.20f, .48f, .35f, .64f)
                line(.35f, .64f, .70f, .64f)
            }
            LedgerIcon.ATTACHMENT -> {
                val path = Path().apply {
                    moveTo(this@Canvas.size.width * .66f, this@Canvas.size.height * .24f)
                    cubicTo(
                        this@Canvas.size.width * .88f,
                        this@Canvas.size.height * .38f,
                        this@Canvas.size.width * .65f,
                        this@Canvas.size.height * .84f,
                        this@Canvas.size.width * .40f,
                        this@Canvas.size.height * .68f,
                    )
                    lineTo(this@Canvas.size.width * .62f, this@Canvas.size.height * .42f)
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            LedgerIcon.IMAGE -> {
                drawRect(tint, Offset(this.size.width * .18f, this.size.height * .22f), androidx.compose.ui.geometry.Size(this.size.width * .64f, this.size.height * .56f), style = Stroke(stroke))
                drawCircle(tint, this.size.width * .07f, Offset(this.size.width * .62f, this.size.height * .38f), style = Stroke(stroke))
                line(.24f, .70f, .42f, .50f)
                line(.42f, .50f, .54f, .62f)
                line(.54f, .62f, .68f, .48f)
                line(.68f, .48f, .78f, .60f)
            }
            LedgerIcon.DOCUMENT -> {
                val path = Path().apply {
                    moveTo(this@Canvas.size.width * .26f, this@Canvas.size.height * .16f)
                    lineTo(this@Canvas.size.width * .62f, this@Canvas.size.height * .16f)
                    lineTo(this@Canvas.size.width * .78f, this@Canvas.size.height * .32f)
                    lineTo(this@Canvas.size.width * .78f, this@Canvas.size.height * .84f)
                    lineTo(this@Canvas.size.width * .26f, this@Canvas.size.height * .84f)
                    close()
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
                line(.36f, .48f, .68f, .48f)
                line(.36f, .62f, .68f, .62f)
            }
            LedgerIcon.LOCATION -> {
                drawCircle(tint, this.size.width * .28f, Offset(center.x, this.size.height * .40f), style = Stroke(stroke))
                line(.34f, .61f, .50f, .86f)
                line(.66f, .61f, .50f, .86f)
            }
            LedgerIcon.RECORD -> {
                drawCircle(tint, this.size.width * .32f, center, style = Stroke(stroke))
                line(.50f, .28f, .50f, .72f)
                line(.28f, .50f, .72f, .50f)
            }
            LedgerIcon.JOURNAL -> {
                listOf(.30f, .50f, .70f).forEach { y -> line(.20f, y, .80f, y) }
            }
            LedgerIcon.ACCOUNT -> {
                drawCircle(tint, this.size.width * .14f, Offset(center.x, this.size.height * .34f), style = Stroke(stroke))
                line(.24f, .78f, .76f, .78f)
                line(.24f, .78f, .34f, .58f)
                line(.76f, .78f, .66f, .58f)
            }
            LedgerIcon.BUDGET -> {
                drawRect(tint, Offset(this.size.width * .20f, this.size.height * .25f), androidx.compose.ui.geometry.Size(this.size.width * .60f, this.size.height * .55f), style = Stroke(stroke))
                line(.34f, .20f, .34f, .34f)
                line(.66f, .20f, .66f, .34f)
            }
            LedgerIcon.ANALYSIS -> {
                line(.22f, .78f, .22f, .56f)
                line(.50f, .78f, .50f, .30f)
                line(.78f, .78f, .78f, .44f)
            }
        }
    }
}
