@file:Suppress("MaxLineLength")

package app.ledger.feature.journal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.JournalDetailView
import app.ledger.finance.application.JournalTransactionView
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Goldens are rendered only from Compose, frozen tokens and textual/YAML contracts. */
@RunWith(AndroidJUnit4::class)
class P15GoldenDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun journalListAndDetailGoldensMatchEveryPixel() {
        val record = InstrumentationRegistry.getArguments().getString(RECORD_ARGUMENT) == "true"
        val outputDirectory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "p15-goldens")
        if (record) check(outputDirectory.mkdirs() || outputDirectory.isDirectory)
        val active = mutableIntStateOf(0)
        val pages = MutableStateFlow(PagingData.from(ROWS))
        composeRule.setContent {
            val case = CASES[active.intValue]
            LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                    JournalDestination(case.screen, emptyMap<String, String>(), case.state, pages, ACTIONS)
                }
            }
        }
        CASES.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap()
            if (record) {
                File(outputDirectory, golden.name).outputStream().use { assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } else {
                val expected = InstrumentationRegistry.getInstrumentation().context.assets.open("goldens/${golden.name}").use(BitmapFactory::decodeStream)
                assertEquals(golden.name, expected.width, actual.width)
                assertEquals(golden.name, expected.height, actual.height)
                assertEquals(golden.name, 0, changedPixels(expected, actual))
                expected.recycle()
            }
            actual.recycle()
        }
        if (record) Thread.sleep(30_000L)
    }

    private fun changedPixels(expected: Bitmap, actual: Bitmap): Int {
        var changed = 0
        for (y in 0 until actual.height) for (x in 0 until actual.width) if (expected.getPixel(x, y) != actual.getPixel(x, y)) changed += 1
        return changed
    }

    private data class Golden(val name: String, val screen: String, val theme: ThemeMode, val state: JournalLoadState.Content)

    private companion object {
        const val RECORD_ARGUMENT = "recordP15Goldens"
        const val GOLDEN_TAG = "p15_golden_root"
        val NOW = Instant.parse("2026-08-03T04:05:06Z")
        val JPY = (CurrencyCode.parse("JPY") as DomainResult.Success).value
        fun id(value: Long) = StableId.fromUuid(UUID(0x1516L, value))
        val ROWS = listOf(
            JournalTransactionView(id(1), id(11), TransactionKind.EXPENSE, TransactionLifecycleState.ACTIVE, NOW, LocalDate.of(2026, 8, 3), "Meals", "Lunch", "Cash", 1280, JPY, null, null, listOf("attachment"), null, TransactionSource.MANUAL),
            JournalTransactionView(id(2), id(12), TransactionKind.INCOME, TransactionLifecycleState.ACTIVE, NOW.minusSeconds(3600), LocalDate.of(2026, 8, 3), "Salary", "August", "Bank", 320000, JPY, null, null, emptyList(), null, TransactionSource.MANUAL),
            JournalTransactionView(id(3), id(13), TransactionKind.TRANSFER, TransactionLifecycleState.ACTIVE, NOW.minusSeconds(90000), LocalDate.of(2026, 8, 2), "Transfer", "Cash to bank", "Cash · Bank", 5000, JPY, null, null, emptyList(), null, TransactionSource.MANUAL),
        )
        val DETAIL = JournalDetailView(ROWS.first(), NOW.minusSeconds(120), NOW, "Asia/Tokyo", "1000+280", "Lunch with team", "Local shop", "August travel", "Station", listOf("receipt.pdf"), "included", "CONSUMPTION_EXPENSE", emptyList(), emptyList(), listOf("Cash:credit:1280 JPY"), "MANUAL", null, 0)
        val ACTIONS = JournalActions(
            onNavigate = { _, _ -> }, onSearch = {}, onApplyFilter = {}, onRemoveFilter = {}, onRetry = {}, onLoadDetail = {},
            onSelect = {}, onSelectAllMatching = {}, onClearSelection = {}, onBulkEdit = {}, onSaveFilter = {}, onApplyPreset = {},
            onCopyPreset = {}, onSetDefaultPreset = {}, onDeletePreset = {}, onReorderPresets = {}, onResolveDependency = { _, _ -> }, onMoveToTrash = { _, _, _ -> },
            onRestore = { _, _ -> }, onCompareRevisions = { _, _, _ -> }, onRestoreRevision = { _, _, _, _ -> }, onVerifyPurge = {}, onPurgeRequested = {},
        )
        val CASES = listOf(
            Golden("p15_journal_list_light.png", "JRN-001", ThemeMode.LIGHT, JournalLoadState.Content()),
            Golden("p15_journal_detail_dark.png", "JRN-007", ThemeMode.DARK, JournalLoadState.Content(detail = DETAIL)),
        )
    }
}
