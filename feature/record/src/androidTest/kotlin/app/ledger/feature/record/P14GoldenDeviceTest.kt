@file:Suppress("MaxLineLength")

package app.ledger.feature.record

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.application.SpecializedFxQuote
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.UserAccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** P14 goldens are generated exclusively from frozen tokens and textual/YAML UI contracts. */
@RunWith(AndroidJUnit4::class)
class P14GoldenDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun transferAdjustmentExchangeAndOpeningGoldensMatchEveryPixel() {
        val record = InstrumentationRegistry.getArguments().getString(RECORD_ARGUMENT) == "true"
        val outputDirectory = File(
            requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "p14-goldens",
        )
        if (record) check(outputDirectory.mkdirs() || outputDirectory.isDirectory)
        val active = mutableIntStateOf(0)
        composeRule.setContent {
            val case = cases[active.intValue]
            LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(GOLDEN_TAG)) {
                    SpecializedTransactionDestination(
                        case.screen,
                        SpecializedTransactionLoadState.Content(case.state),
                        ACTIONS,
                    )
                    LedgerSaveFab({}, Modifier.align(Alignment.BottomEnd).padding(16.dp))
                }
            }
        }
        cases.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap()
            if (record) {
                File(outputDirectory, golden.assetName).outputStream().use {
                    assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } else {
                val expected = InstrumentationRegistry.getInstrumentation().context.assets
                    .open("goldens/${golden.assetName}").use(BitmapFactory::decodeStream)
                assertEquals(golden.assetName, expected.width, actual.width)
                assertEquals(golden.assetName, expected.height, actual.height)
                assertEquals(golden.assetName, 0, changedPixels(expected, actual))
                expected.recycle()
            }
            actual.recycle()
        }
        if (record) Thread.sleep(RECORD_PULL_WINDOW_MILLIS)
    }

    private fun changedPixels(expected: Bitmap, actual: Bitmap): Int {
        var changed = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                if (expected.getPixel(x, y) != actual.getPixel(x, y)) changed += 1
            }
        }
        return changed
    }

    private fun state(kind: SpecializedTransactionKind): SpecializedTransactionEditorState {
        var value = SpecializedTransactionPolicy.create(kind, snapshot, null, NOW, ZONE, Locale.SIMPLIFIED_CHINESE)
        value = SpecializedTransactionPolicy.updateExpression(value, false, if (kind == SpecializedTransactionKind.OPENING_BALANCE) "10000" else "10", Locale.SIMPLIFIED_CHINESE)
        if (kind in setOf(SpecializedTransactionKind.TRANSFER, SpecializedTransactionKind.FX_EXCHANGE)) {
            value = SpecializedTransactionPolicy.updateExpression(value, true, "9", Locale.SIMPLIFIED_CHINESE)
        }
        value = SpecializedTransactionPolicy.withQuote(value, USD, quote(USD, "150"))
        if (kind == SpecializedTransactionKind.FX_EXCHANGE) {
            value = SpecializedTransactionPolicy.withQuote(value, EUR, quote(EUR, "165"))
        }
        return value
    }

    private fun quote(source: CurrencyCode, rate: String): SpecializedFxQuote = SpecializedFxQuote(
        (
            FxEvidence.create(
                FxEvidenceInput(source, JPY, BigDecimal(rate), PROVIDER, NOW, NOW, FxRateSource.CACHE, false),
            ) as DomainResult.Success
            ).value,
        false,
    )

    private val snapshot = ReferenceDataSnapshot(
        BOOK,
        JPY,
        8L,
        listOf(account(USD_ACCOUNT, "USD wallet", USD, 0), account(EUR_ACCOUNT, "EUR wallet", EUR, 1)),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        null,
        null,
        true,
    )

    private val cases = listOf(
        Golden("p14_transfer_light.png", "REC-013", ThemeMode.LIGHT, state(SpecializedTransactionKind.TRANSFER)),
        Golden("p14_adjustment_dark.png", "REC-020", ThemeMode.DARK, state(SpecializedTransactionKind.BALANCE_ADJUSTMENT)),
        Golden("p14_fx_exchange_light.png", "REC-021", ThemeMode.LIGHT, state(SpecializedTransactionKind.FX_EXCHANGE)),
        Golden("p14_opening_dark.png", "REC-022", ThemeMode.DARK, state(SpecializedTransactionKind.OPENING_BALANCE)),
    )

    private fun account(id: StableId, name: String, currency: CurrencyCode, sort: Int): AccountReferenceView = AccountReferenceView(
        id,
        UserAccountType.CASH,
        name,
        currency,
        EntityStatus.ACTIVE,
        null,
        null,
        null,
        "account",
        0,
        sort,
        1L,
        0L,
        null,
        null,
        false,
        0L,
    )

    private data class Golden(
        val assetName: String,
        val screen: String,
        val theme: ThemeMode,
        val state: SpecializedTransactionEditorState,
    )

    private companion object {
        const val RECORD_ARGUMENT = "recordP14Goldens"
        const val GOLDEN_TAG = "p14_golden_root"
        const val WIDTH = 360
        const val HEIGHT = 720
        const val RECORD_PULL_WINDOW_MILLIS = 30_000L
        val ACTIONS: (SpecializedTransactionScreenAction) -> Unit = {}
        val NOW: Instant = Instant.parse("2026-08-03T04:05:06Z")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
        val BOOK: StableId = StableId.fromUuid(UUID(0x1414L, 1))
        val USD_ACCOUNT: StableId = StableId.fromUuid(UUID(0x1414L, 2))
        val EUR_ACCOUNT: StableId = StableId.fromUuid(UUID(0x1414L, 3))
        val USD: CurrencyCode = (CurrencyCode.parse("USD") as DomainResult.Success).value
        val EUR: CurrencyCode = (CurrencyCode.parse("EUR") as DomainResult.Success).value
        val JPY: CurrencyCode = (CurrencyCode.parse("JPY") as DomainResult.Success).value
        val PROVIDER: FxProvider = (FxProvider.of("p14-cache") as DomainResult.Success).value
    }
}
