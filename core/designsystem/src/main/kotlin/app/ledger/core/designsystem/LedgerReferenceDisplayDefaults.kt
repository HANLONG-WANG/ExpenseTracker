package app.ledger.core.designsystem

import app.ledger.core.designsystem.tokens.GeneratedLedgerTokenContract

/** Non-Compose defaults for reference rows created before a user opens the full appearance editor. */
public object LedgerReferenceDisplayDefaults {
    public const val ACCOUNT_ICON_KEY: String = "account"
    public const val CATEGORY_ICON_KEY: String = "record"

    public val COLOR_ARGB: Int by lazy {
        requireNotNull(categoryPaletteArgb["slate"])
    }

    public val categoryPaletteArgb: Map<String, Int> by lazy {
        (0 until CATEGORY_COLOR_COUNT).associate { index ->
            val id = requireNotNull(
                GeneratedLedgerTokenContract.scalarValues["color.categoryPalette[$index].id"],
            ).removeSurrounding("\"")
            val encoded = requireNotNull(
                GeneratedLedgerTokenContract.scalarValues["color.categoryPalette[$index].foregroundLight"],
            ).removeSurrounding("\"")
            require(encoded.matches(Regex("#[0-9A-F]{6}")))
            id to (OPAQUE_ALPHA_MASK or encoded.drop(1).toLong(HEX_RADIX)).toInt()
        }
    }

    public fun paletteId(colorArgb: Int): String = categoryPaletteArgb.entries
        .firstOrNull { it.value == colorArgb }
        ?.key ?: "slate"

    private const val OPAQUE_ALPHA_MASK: Long = 0xFF000000L
    private const val HEX_RADIX: Int = 16
    private const val CATEGORY_COLOR_COUNT: Int = 16
}
