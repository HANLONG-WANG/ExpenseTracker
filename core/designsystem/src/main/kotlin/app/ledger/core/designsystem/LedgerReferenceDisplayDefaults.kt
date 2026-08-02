package app.ledger.core.designsystem

import app.ledger.core.designsystem.tokens.GeneratedLedgerTokenContract

/** Non-Compose defaults for reference rows created before a user opens the full appearance editor. */
public object LedgerReferenceDisplayDefaults {
    public const val ACCOUNT_ICON_KEY: String = "account"
    public const val CATEGORY_ICON_KEY: String = "record"

    public val COLOR_ARGB: Int by lazy {
        val encoded = requireNotNull(
            GeneratedLedgerTokenContract.scalarValues["color.categoryPalette[12].foregroundLight"],
        ).removeSurrounding("\"")
        require(encoded.matches(Regex("#[0-9A-F]{6}")))
        (OPAQUE_ALPHA_MASK or encoded.drop(1).toLong(HEX_RADIX)).toInt()
    }

    private const val OPAQUE_ALPHA_MASK: Long = 0xFF000000L
    private const val HEX_RADIX: Int = 16
}
