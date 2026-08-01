@file:Suppress("MagicNumber")

package app.ledger.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

public object LedgerContrast {
    public fun ratio(first: Color, second: Color): Double {
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) / (min(firstLuminance, secondLuminance) + 0.05)
    }

    public fun accessibleContent(background: Color): Color = if (ratio(Color.Black, background) >= ratio(Color.White, background)) Color.Black else Color.White

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val normalized = value.toDouble()
            return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
