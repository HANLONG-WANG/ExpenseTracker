@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength")

package app.ledger.feature.record

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.BatchSummaryRowUiModel
import app.ledger.core.designsystem.BatchSummaryTable
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.finance.application.BatchEntryField
import app.ledger.finance.application.BatchValidationIssue
import app.ledger.finance.application.BatchValidationReport
import app.ledger.finance.application.BatchValidationSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BatchRecordUiContractDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allTenYamlStatesRenderAcrossWidthsLocalesThemesAndFontScales() {
        val active = mutableStateOf(CASES.first())
        composeRule.setContent {
            val current = active.value
            val base = LocalContext.current
            val localized = base.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(current.locale)))
                },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, current.fontScale),
            ) {
                LedgerTheme(current.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(current.width.dp, 1_600.dp)) {
                        BatchRecordDestination(current.screen, current.state(), ACTIONS)
                    }
                }
            }
        }
        assertEquals(10, CASES.size)
        assertEquals(10, CASES.map { "${it.screen}:${it.requiredState}" }.toSet().size)
        CASES.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            val tag = when (case.screen) {
                "REC-023" -> LedgerTestTags.BATCH_RECORD_ROOT
                "REC-024" -> LedgerTestTags.BATCH_ROW_EDITOR
                else -> LedgerTestTags.BATCH_VALIDATION
            }
            composeRule.onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun summaryAndValidationExposeStableActionsAndLargeTableComposesOnlyVisibleRows() {
        val composed = AtomicInteger()
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(320.dp, 720.dp)) {
                    BatchSummaryTable(
                        rowCount = 100_000,
                        rowAt = { index ->
                            composed.incrementAndGet()
                            BatchSummaryRowUiModel(
                                "virtual_row_${index.toString().padStart(6, '0')}",
                                (index + 1).toString(),
                                "Category",
                                "Amount",
                                "Account",
                                "Merchant",
                                "Date",
                                "Project",
                                "0",
                                "Ready",
                                "Row ${index + 1}, ready",
                            )
                        },
                        headers = List(9) { "Header" },
                        onRowClick = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.BATCH_SUMMARY_TABLE).assertExists()
        assertTrue("LazyColumn must not compose 100k rows", composed.get() < 100)
        val clickCount = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        assertTrue("visible semantics remain bounded", clickCount < 100)
    }

    @Test
    fun tokenAndYamlDerivedPixelGoldensRemainStable() {
        val active = mutableIntStateOf(0)
        val mismatches = mutableListOf<String>()
        composeRule.setContent {
            val golden = GOLDENS[active.intValue]
            LedgerTheme(golden.theme, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                    BatchRecordDestination(golden.screen, golden.state(), ACTIONS)
                }
            }
        }
        GOLDENS.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256()
            println("P24_GOLDEN_${golden.screen}_${golden.theme}=$actual")
            if (golden.expectedSha256 != actual) mismatches += "${golden.screen}:${golden.theme}:$actual"
        }
        assertTrue("P24 golden mismatches: $mismatches", mismatches.isEmpty())
    }

    private fun Bitmap.pixelSha256(): String {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
        pixels.forEach(buffer::putInt)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }

    private data class RequiredCase(
        val screen: String,
        val requiredState: String,
        val width: Int,
        val fontScale: Float,
        val locale: String,
        val theme: ThemeMode,
        val state: () -> BatchRecordState,
    )

    private data class Golden(
        val screen: String,
        val theme: ThemeMode,
        val expectedSha256: String,
        val state: () -> BatchRecordState,
    )

    companion object {
        private const val GOLDEN_TAG = "p24_batch_golden_root"
        private val ROW_ID = id(100)
        private val ERROR = BatchValidationIssue(ROW_ID, BatchEntryField.AMOUNT, "AMOUNT_INVALID", BatchValidationSeverity.ERROR)
        private val WARNING = BatchValidationIssue(ROW_ID, BatchEntryField.ATTACHMENTS, "MANY_ATTACHMENTS", BatchValidationSeverity.WARNING)
        private val ACTIONS = BatchRecordActions(
            onOpenRow = {}, onAdd = {}, onCopy = {}, onDelete = {}, onMove = { _, _ -> },
            onSort = {}, onPaste = {}, onRowChange = {}, onCycleReference = { _, _ -> },
            onAddAttachment = {}, onValidate = {}, onConfirmWarnings = {}, onCommit = {}, onUndo = {},
            onDiscard = {}, onKeepEditing = {}, onJumpToIssue = {},
        )

        private fun baseState(): BatchRecordState {
            val snapshot = OrdinaryRecordDeviceFixtures.snapshot()
            val row = BatchRecordPolicy.newRow(ROW_ID, snapshot, OrdinaryRecordDeviceFixtures.now, OrdinaryRecordDeviceFixtures.zone).copy(
                categoryId = OrdinaryRecordDeviceFixtures.expenseChild,
                amountExpression = "1250",
                userMinor = 1_250L,
                accountMinor = 1_250L,
                baseMinor = 1_250L,
                accountId = OrdinaryRecordDeviceFixtures.bank,
                cardId = OrdinaryRecordDeviceFixtures.card,
                merchantId = OrdinaryRecordDeviceFixtures.merchant,
                projectId = OrdinaryRecordDeviceFixtures.project,
                locationRecordId = OrdinaryRecordDeviceFixtures.location,
            )
            return BatchRecordState(snapshot, listOf(row))
        }

        private fun withPresentation(presentation: BatchRecordPresentation) = baseState().copy(presentation = presentation)
        private fun errorState() = baseState().copy(presentation = BatchRecordPresentation.ERRORS, validation = BatchValidationReport(listOf(ERROR)))
        private fun warningState() = baseState().copy(presentation = BatchRecordPresentation.READY_TO_COMMIT, validation = BatchValidationReport(listOf(WARNING)))
        private fun validState() = baseState().copy(presentation = BatchRecordPresentation.READY_TO_COMMIT, validation = BatchValidationReport(emptyList()))
        private fun editorState(error: Boolean = false) = baseState().copy(editingRowId = ROW_ID, validation = BatchValidationReport(if (error) listOf(ERROR) else emptyList()))

        private val CASES = listOf(
            RequiredCase("REC-023", "editing", 320, 1f, "zh-CN", ThemeMode.LIGHT) { withPresentation(BatchRecordPresentation.EDITING) },
            RequiredCase("REC-023", "validating", 360, 1.3f, "ja-JP", ThemeMode.DARK) { withPresentation(BatchRecordPresentation.VALIDATING) },
            RequiredCase("REC-023", "errors", 480, 2f, "en-US", ThemeMode.LIGHT, ::errorState),
            RequiredCase("REC-023", "readyToCommit", 320, 2f, "zh-CN", ThemeMode.DARK, ::validState),
            RequiredCase("REC-023", "committing", 360, 1f, "ja-JP", ThemeMode.LIGHT) { withPresentation(BatchRecordPresentation.COMMITTING) },
            RequiredCase("REC-024", "editing", 480, 1.3f, "en-US", ThemeMode.DARK) { editorState(false) },
            RequiredCase("REC-024", "validationError", 320, 2f, "zh-CN", ThemeMode.LIGHT) { editorState(true) },
            RequiredCase("REC-025", "errors", 360, 1f, "ja-JP", ThemeMode.DARK, ::errorState),
            RequiredCase("REC-025", "warnings", 480, 1.3f, "en-US", ThemeMode.LIGHT, ::warningState),
            RequiredCase("REC-025", "valid", 320, 2f, "zh-CN", ThemeMode.DARK, ::validState),
        )

        private val GOLDENS = listOf(
            Golden("REC-023", ThemeMode.LIGHT, "a207b0736bfcd848e9ab6f22d64bff60a78a6c4ca2199d0f6c2d8d459e61e044", ::validState),
            Golden("REC-024", ThemeMode.DARK, "8ed41793fa25efcf05748f91db243563438f3b1e73139d8102429364bf0c6745") { editorState(true) },
            Golden("REC-025", ThemeMode.LIGHT, "a2b7abdb5d31b650f3ff52d3352be6a7e2476c2c6ca2128ff54fed58cfc61c96", ::warningState),
        )

        private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x2400L, value))
    }
}
