@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package app.ledger.feature.automation

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.AutomationSnapshot
import app.ledger.finance.application.BlueprintView
import app.ledger.finance.application.CandidateView
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import app.ledger.finance.application.RecurrenceSeriesView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.PlannedRecurrenceOccurrence
import app.ledger.finance.domain.RecurrenceCandidateStatus
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceModificationScope
import app.ledger.finance.domain.RecurrenceRule
import app.ledger.finance.domain.RecurrenceStatus
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.WeekendAdjustment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allTwentyThreeAutomationStatesRenderAcrossFrozenAccessibilityMatrix() {
        val cases = cases()
        assertEquals(23, cases.size)
        assertEquals(EXPECTED, cases.groupBy(Case::screen).mapValues { (_, value) -> value.map(Case::stateName).toSet() })
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            val base = LocalContext.current
            val localized = base.createConfigurationContext(Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.forLanguageTag(case.locale))) })
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, case.fontScale),
            ) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 2_300.dp)) {
                        AutomationDestination(case.screen, AutomationLoadState.Content(case.state), ACTIONS)
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(case.tag).assertExists()
            composeRule.onRoot().assertExists()
        }
    }

    /** Pixel digests come only from production Compose plus textual tokens/YAML; no visual draft is a baseline. */
    @Test
    fun p23ProductionGoldensMatchEveryPixel() {
        val cases = listOf(
            Golden("AUT-001", ThemeMode.LIGHT, EXPECTED_HUB_SHA256),
            Golden("AUT-008", ThemeMode.DARK, EXPECTED_CANDIDATE_SHA256),
        )
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val current = active.value
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(current.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        AutomationDestination(current.screen, AutomationLoadState.Content(state(current.screen, snapshot())), ACTIONS)
                    }
                }
            }
        }
        val actual = cases.map { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256().also {
                println("P23_GOLDEN_${case.screen}=$it")
            }
        }
        assertEquals(cases.map(Golden::expected), actual)
    }

    @Test
    fun candidateConfirmationOpensFullEditorWithoutChangingFormalMetrics() {
        val activeScreen = mutableStateOf("AUT-008")
        val candidateState = state("AUT-009", snapshot(), candidateId = CANDIDATE_ID)
        var formalMetricMinor = 0L
        var fullEditorLaunches = 0
        val actions = ACTIONS.copy(
            onCandidateSelected = { candidateId ->
                assertEquals(CANDIDATE_ID, candidateId)
                activeScreen.value = "AUT-009"
            },
            onConfirmCandidate = { fullEditorLaunches += 1 },
        )
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 800.dp)) {
                    val current = if (activeScreen.value == "AUT-008") state("AUT-008", snapshot()) else candidateState
                    AutomationDestination(activeScreen.value, AutomationLoadState.Content(current), actions)
                }
            }
        }

        composeRule.onNodeWithText("Rent").performClick()
        composeRule.onNodeWithTag(LedgerTestTags.AUTOMATION_CANDIDATE_EDITOR).assertExists()
        val confirm = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.automation_review_and_edit)
        composeRule.onNodeWithText(confirm).performClick()
        composeRule.runOnIdle {
            assertEquals(1, fullEditorLaunches)
            assertEquals(0L, formalMetricMinor)
        }
    }

    private fun Bitmap.pixelSha256(): String {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES * (pixels.size + 2))
        buffer.putInt(width)
        buffer.putInt(height)
        pixels.forEach(buffer::putInt)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }

    private fun cases(): List<Case> {
        val populated = snapshot()
        val empty = snapshot(empty = true)
        val base = listOf(
            Case("AUT-001", "content", LedgerTestTags.AUTOMATION_HUB, state("AUT-001", populated)),
            Case("AUT-002", "content", LedgerTestTags.AUTOMATION_TEMPLATE_LIST, state("AUT-002", populated)),
            Case("AUT-002", "empty", LedgerTestTags.AUTOMATION_TEMPLATE_LIST, state("AUT-002", empty)),
            Case("AUT-003", "create", LedgerTestTags.AUTOMATION_TEMPLATE_EDITOR, state("AUT-003", empty)),
            Case("AUT-003", "edit", LedgerTestTags.AUTOMATION_TEMPLATE_EDITOR, state("AUT-003", populated, blueprintId = BLUEPRINT_ID)),
            Case("AUT-003", "validationError", LedgerTestTags.AUTOMATION_TEMPLATE_EDITOR, state("AUT-003", empty).copy(presentation = AutomationPresentation.VALIDATION_ERROR, validationFields = setOf("name"))),
            Case("AUT-004", "content", LedgerTestTags.AUTOMATION_SERIES_LIST, state("AUT-004", populated)),
            Case("AUT-004", "empty", LedgerTestTags.AUTOMATION_SERIES_LIST, state("AUT-004", empty)),
            Case("AUT-004", "paused", LedgerTestTags.AUTOMATION_SERIES_LIST, state("AUT-004", populated).copy(presentation = AutomationPresentation.PAUSED)),
            Case("AUT-005", "create", LedgerTestTags.AUTOMATION_SERIES_EDITOR, state("AUT-005", populated)),
            Case("AUT-005", "edit", LedgerTestTags.AUTOMATION_SERIES_EDITOR, state("AUT-005", populated, seriesId = SERIES_ID)),
            Case("AUT-005", "invalid", LedgerTestTags.AUTOMATION_SERIES_EDITOR, state("AUT-005", populated).copy(presentation = AutomationPresentation.INVALID, validationFields = setOf("blueprint"))),
            Case("AUT-006", "editing", LedgerTestTags.AUTOMATION_RULE_EDITOR, state("AUT-006", populated, seriesId = SERIES_ID)),
            Case("AUT-006", "invalid", LedgerTestTags.AUTOMATION_RULE_EDITOR, state("AUT-006", populated, seriesId = SERIES_ID).copy(presentation = AutomationPresentation.INVALID, validationFields = setOf("startDate"))),
            Case("AUT-007", "content", LedgerTestTags.AUTOMATION_PREVIEW, state("AUT-007", populated, seriesId = SERIES_ID)),
            Case("AUT-007", "empty", LedgerTestTags.AUTOMATION_PREVIEW, state("AUT-007", empty)),
            Case("AUT-008", "content", LedgerTestTags.AUTOMATION_CANDIDATES, state("AUT-008", populated)),
            Case("AUT-008", "empty", LedgerTestTags.AUTOMATION_CANDIDATES, state("AUT-008", empty)),
            Case("AUT-008", "selection", LedgerTestTags.AUTOMATION_CANDIDATES, state("AUT-008", populated).copy(presentation = AutomationPresentation.SELECTION, selectedCandidateIds = setOf(CANDIDATE_ID))),
            Case("AUT-009", "editing", LedgerTestTags.AUTOMATION_CANDIDATE_EDITOR, state("AUT-009", populated, candidateId = CANDIDATE_ID)),
            Case("AUT-009", "validationError", LedgerTestTags.AUTOMATION_CANDIDATE_EDITOR, state("AUT-009", populated, candidateId = CANDIDATE_ID).copy(presentation = AutomationPresentation.VALIDATION_ERROR)),
            Case("AUT-009", "invalidSource", LedgerTestTags.AUTOMATION_CANDIDATE_EDITOR, state("AUT-009", empty, candidateId = CANDIDATE_ID)),
            Case("AUT-010", "content", LedgerTestTags.AUTOMATION_SCOPE, state("AUT-010", populated, seriesId = SERIES_ID).copy(modificationScope = RecurrenceModificationScope.THIS_AND_FUTURE)),
        )
        return base.mapIndexed { index, case -> case.copy(width = listOf(320, 360, 480)[index % 3], fontScale = listOf(1f, 1.3f, 2f)[index % 3], locale = listOf("zh-CN", "ja-JP", "en-US")[index % 3], theme = if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK) }
    }

    private fun state(screen: String, snapshot: AutomationSnapshot, blueprintId: StableId? = null, seriesId: StableId? = null, candidateId: StableId? = null): AutomationFeatureState = AutomationPolicy.create(snapshot, entry(), screen, blueprintId, seriesId, candidateId, ZONE, LocalDate.of(2026, 8, 6))

    private fun snapshot(empty: Boolean = false): AutomationSnapshot {
        if (empty) return AutomationSnapshot(BOOK_ID, 5, emptyList(), emptyList(), emptyList(), emptyList())
        val blueprint = BlueprintView(BLUEPRINT_ID, id(3), 1, "Rent", "record", 0xff006c4c.toInt(), EntityStatus.ACTIVE, TransactionKind.EXPENSE, id(4), id(5), null, null, null, null, null, null, "1200", CURRENCY, null, null)
        val rule = RecurrenceRule(RecurrenceFrequency.MONTHLY_DAY, 1, emptySet(), 1, null, null, MissingDayPolicy.MOVE_TO_MONTH_END, WeekendAdjustment.NONE)
        val series = RecurrenceSeriesView(SERIES_ID, id(7), 1, BLUEPRINT_ID, "Rent", RecurrenceStatus.ACTIVE, rule, LocalDate.of(2026, 8, 1), null, null, LocalTime.of(9, 0), ZONE, RecurrenceGenerationMode.CANDIDATE, null, true, listOf(PlannedRecurrenceOccurrence(LocalDate.of(2026, 8, 1), Instant.parse("2026-08-01T00:00:00Z"), null)))
        val candidate = CandidateView(CANDIDATE_ID, id(9), SERIES_ID, blueprint, Instant.parse("2026-08-01T00:00:00Z"), LocalDate.of(2026, 8, 1), RecurrenceCandidateStatus.PENDING_CONFIRMATION, null)
        return AutomationSnapshot(BOOK_ID, 5, listOf(blueprint), listOf(series), listOf(candidate), emptyList())
    }

    private fun entry() = OrdinaryTransactionEntrySnapshot(ReferenceDataSnapshot(BOOK_ID, CURRENCY, 5, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, false), emptyList(), emptyList(), emptyList(), emptyList(), null)
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x23, value))

    private data class Case(val screen: String, val stateName: String, val tag: String, val state: AutomationFeatureState, val width: Int = 360, val fontScale: Float = 1f, val locale: String = "en-US", val theme: ThemeMode = ThemeMode.LIGHT)
    private data class Golden(val screen: String, val theme: ThemeMode, val expected: String)

    private companion object {
        val BOOK_ID = StableId.fromUuid(UUID(0x23, 1))
        val BLUEPRINT_ID = StableId.fromUuid(UUID(0x23, 2))
        val SERIES_ID = StableId.fromUuid(UUID(0x23, 6))
        val CANDIDATE_ID = StableId.fromUuid(UUID(0x23, 8))
        val ZONE = ZoneId.of("Asia/Tokyo")
        val CURRENCY = (CurrencyCode.parse("JPY") as DomainResult.Success).value
        const val GOLDEN_TAG = "p23_automation_golden_root"
        const val EXPECTED_HUB_SHA256 = "963be8946dd193f24e6b7636e4a254a7b982a34e051682783154dc08a487f734"
        const val EXPECTED_CANDIDATE_SHA256 = "d12a84ecb979589c23329dfbc8b271303daded57d36c85b11e07e2b59b8570e9"
        val ACTIONS = AutomationActions(
            onRetry = {}, onNavigate = { _, _ -> }, onSearch = {}, onTemplateFilter = {}, onTemplateSort = {}, onArchiveBlueprint = {}, onBlueprintField = { _, _ -> }, onBlueprintKind = {},
            onBlueprintReference = { _, _ -> }, onSaveBlueprint = {}, onRecurrenceField = { _, _ -> }, onRecurrenceBlueprint = {},
            onFrequency = {}, onWeekday = {}, onNthWeekday = {}, onMissingDay = {}, onWeekend = {}, onGenerationMode = {}, onNotifyCandidate = {}, onFixedPlace = {},
            onSaveRecurrence = {}, onApplyRule = {}, onSeriesFilter = {}, onTemplateSelected = {}, onCandidateSelected = {}, onCandidateToggle = {}, onReviewSelectedCandidates = {}, onSkipSelectedCandidates = {}, onConfirmCandidate = {},
            onSkipCandidate = {}, onCancelCandidate = {}, onScope = {}, onApplyScope = {},
        )
        val EXPECTED = linkedMapOf(
            "AUT-001" to setOf("content"), "AUT-002" to setOf("content", "empty"),
            "AUT-003" to setOf("create", "edit", "validationError"), "AUT-004" to setOf("content", "empty", "paused"),
            "AUT-005" to setOf("create", "edit", "invalid"), "AUT-006" to setOf("editing", "invalid"), "AUT-007" to setOf("content", "empty"),
            "AUT-008" to setOf("content", "empty", "selection"), "AUT-009" to setOf("editing", "validationError", "invalidSource"), "AUT-010" to setOf("content"),
        )
    }
}
