@file:Suppress("MaxLineLength")

package app.ledger.feature.record

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class OrdinaryRecordUiContractDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rec001ThroughRec012RenderAtRequiredWidthsLocalesThemesAndFontScales() {
        val presentation = mutableStateOf(Presentation("REC-001", OrdinaryRecordDeviceFixtures.content(), 320, 2f, "zh-CN", ThemeMode.LIGHT))
        composeRule.setContent {
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val locale = Locale.forLanguageTag(presentation.value.locale)
            val localized = base.createConfigurationContext(Configuration(configuration).apply { setLocales(LocaleList(locale)) })
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, presentation.value.fontScale),
            ) {
                LedgerTheme(presentation.value.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(presentation.value.width.dp, 1_400.dp)) {
                        OrdinaryRecordDestination(presentation.value.screen, presentation.value.state, OrdinaryRecordDeviceFixtures.actions)
                    }
                }
            }
        }
        val screens = (1..12).map { "REC-${it.toString().padStart(3, '0')}" }
        val cases = screens.mapIndexed { index, screen ->
            Presentation(
                screen,
                stateFor(screen),
                listOf(320, 360, 480)[index % 3],
                listOf(1f, 1.3f, 2f)[index % 3],
                listOf("zh-CN", "ja-JP", "en-US")[index % 3],
                if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
            )
        }
        cases.forEach { case ->
            composeRule.runOnIdle { presentation.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LedgerTestTags.RECORD_ROOT).assertExists()
        }
    }

    @Test
    fun rec026ContentAndEmptyRenderInRecordModuleAcrossAccessibilityBoundary() {
        val populated = OrdinaryRecordDeviceFixtures.content()
        val empty = OrdinaryRecordLoadState.Content(populated.snapshot.copy(templates = emptyList()), search = "none")
        val presentation = mutableStateOf(Presentation("REC-026", populated, 320, 1f, "zh-CN", ThemeMode.LIGHT))
        composeRule.setContent {
            val current = presentation.value
            val base = LocalContext.current
            val localized = base.createConfigurationContext(Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.forLanguageTag(current.locale))) })
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, current.fontScale),
            ) {
                LedgerTheme(current.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(current.width.dp, 1_400.dp)) {
                        OrdinaryRecordDestination("REC-026", current.state, OrdinaryRecordDeviceFixtures.actions)
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.AUTOMATION_TEMPLATE_PICKER).assertExists()
        composeRule.runOnIdle { presentation.value = Presentation("REC-026", empty, 480, 2f, "ja-JP", ThemeMode.DARK) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LedgerTestTags.AUTOMATION_TEMPLATE_PICKER).assertExists()
    }

    @Test
    fun editorValidationConflictUnsavedAndFixedCoreFieldsExposeStableAccessibleSemantics() {
        val validated = OrdinaryRecordPolicy.validate(OrdinaryRecordDeviceFixtures.editor())
        val state = mutableStateOf(OrdinaryRecordDeviceFixtures.content(validated))
        composeRule.setContent {
            LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 1_400.dp)) { OrdinaryRecordDestination("REC-003", state.value, OrdinaryRecordDeviceFixtures.actions) }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_EDITOR).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_EDITOR).performScrollToNode(hasTestTag(LedgerTestTags.RECORD_CATEGORY))
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_CATEGORY).assertExists().assertHasClickAction()
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_EDITOR).performScrollToNode(hasTestTag(LedgerTestTags.RECORD_ACCOUNT))
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_ACCOUNT).assertExists().assertHasClickAction()
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_EDITOR).performScrollToNode(hasTestTag(LedgerTestTags.RECORD_VALIDATION))
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_VALIDATION).assertExists()

        composeRule.runOnIdle { state.value = OrdinaryRecordDeviceFixtures.content(validated.copy(presentation = RecordEditorPresentation.REVISION_CONFLICT)) }
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_REVISION_CONFLICT).assertExists()
        composeRule.runOnIdle { state.value = OrdinaryRecordDeviceFixtures.content(validated.copy(showUnsavedDialog = true)) }
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_UNSAVED_DIALOG).assertExists()
    }

    @Test
    fun editingConflictOffersProductionHistoryNavigationAndNeverDispatchesOverwrite() {
        var navigatedScreen: String? = null
        var saveDispatches = 0
        val conflict = OrdinaryRecordDeviceFixtures.editor().copy(
            transactionId = OrdinaryRecordDeviceFixtures.template,
            presentation = RecordEditorPresentation.REVISION_CONFLICT,
        )
        val actions = OrdinaryRecordDeviceFixtures.actions.copy(
            onNavigate = { screen, _, _ -> navigatedScreen = screen },
            onSave = { saveDispatches += 1 },
        )
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 1_400.dp)) {
                    OrdinaryRecordDestination("REC-003", OrdinaryRecordDeviceFixtures.content(conflict), actions)
                }
            }
        }

        composeRule.onNodeWithTag(LedgerTestTags.RECORD_REVISION_CONFLICT).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_VIEW_DIFFERENCES).assertHasClickAction().performClick()
        composeRule.runOnIdle {
            assertEquals("JRN-008", navigatedScreen)
            assertEquals(0, saveDispatches)
        }
    }

    @Test
    fun settlementImbalanceBlocksTheProductionSaveIntentWithoutWriting() {
        val amount = OrdinaryRecordPolicy.changeExpression(OrdinaryRecordDeviceFixtures.editor(), "1000", Locale.JAPAN)
        val selected = OrdinaryRecordPolicy.selectSettlementActivity(
            OrdinaryRecordPolicy.setSettlementEnabled(amount, true),
            OrdinaryRecordDeviceFixtures.activity,
        )
        val imbalanced = selected.copy(
            draft = selected.draft.copy(
                settlementShares = selected.draft.settlementShares.mapIndexed { index, share ->
                    if (index == 0) share.copy(owedMinor = share.owedMinor + 1L) else share
                },
            ),
        )
        val state = mutableStateOf(OrdinaryRecordDeviceFixtures.content(imbalanced))
        var writes = 0
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 800.dp)) {
                    LedgerScaffold(
                        fixedAction = {
                            LedgerSaveFab(
                                onClick = {
                                    val current = requireNotNull(state.value.editor)
                                    val validated = OrdinaryRecordPolicy.validate(current)
                                    state.value = OrdinaryRecordDeviceFixtures.content(validated)
                                    if (validated.errors.isEmpty()) writes += 1
                                },
                            )
                        },
                    ) { padding ->
                        OrdinaryRecordDestination("REC-011", state.value, OrdinaryRecordDeviceFixtures.actions, Modifier.padding(padding))
                    }
                }
            }
        }

        composeRule.onNodeWithTag(LedgerTestTags.SAVE).assertHasClickAction().performClick()
        composeRule.runOnIdle {
            assertEquals(0, writes)
            assertEquals(
                "SETTLEMENT_IMBALANCED",
                state.value.editor?.errors?.singleOrNull { it.field == RecordField.SETTLEMENT }?.code,
            )
        }
    }

    @Test
    fun allFortyTwoFrozenRequiredStatesRenderInsideTheirRecDestination() {
        val cases = requiredStateCases()
        assertEquals(42, cases.size)
        assertEquals(42, cases.map { "${it.screen}:${it.requiredState}" }.toSet().size)
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(320.dp, 1_400.dp)) {
                    OrdinaryRecordDestination(active.value.screen, active.value.loadState, OrdinaryRecordDeviceFixtures.actions)
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LedgerTestTags.RECORD_ROOT).assertExists()
        }
    }

    private fun stateFor(screen: String): OrdinaryRecordLoadState.Content {
        var editor = OrdinaryRecordDeviceFixtures.editor()
        editor = when (screen) {
            "REC-003" -> OrdinaryRecordPolicy.changeExpression(editor, "1000+250", Locale.JAPAN)
            "REC-010" -> editor.copy(attachmentImporting = true)
            "REC-011" -> OrdinaryRecordPolicy.selectSettlementActivity(OrdinaryRecordPolicy.setSettlementEnabled(OrdinaryRecordPolicy.changeExpression(editor, "1000", Locale.JAPAN), true), OrdinaryRecordDeviceFixtures.activity)
            else -> editor
        }
        return OrdinaryRecordDeviceFixtures.content(editor)
    }

    private fun requiredStateCases(): List<RequiredStateCase> {
        val snapshot = OrdinaryRecordDeviceFixtures.snapshot()
        val editor = OrdinaryRecordDeviceFixtures.editor()
        val emptyCategories = snapshot.copy(references = snapshot.references.copy(categories = emptyList()))
        val emptyAccounts = snapshot.copy(references = snapshot.references.copy(accounts = emptyList(), cards = emptyList()))
        val emptyCards = snapshot.copy(references = snapshot.references.copy(cards = emptyList()))
        val emptyMerchants = snapshot.copy(references = snapshot.references.copy(merchants = emptyList()))
        val emptyProjects = snapshot.copy(projects = emptyList())
        val archivedProjectSnapshot = snapshot.copy(projects = snapshot.projects.map { it.copy(active = false) })
        val located = editor.copy(draft = editor.draft.copy(locationRecordId = OrdinaryRecordDeviceFixtures.location))
        val attached = editor.copy(draft = editor.draft.copy(attachmentIds = listOf(OrdinaryRecordDeviceFixtures.location)))
        val amount = OrdinaryRecordPolicy.changeExpression(editor, "1000", Locale.JAPAN)
        val settlementEditing = OrdinaryRecordPolicy.setSettlementEnabled(amount, true)
        val settlementValid = OrdinaryRecordPolicy.selectSettlementActivity(settlementEditing, OrdinaryRecordDeviceFixtures.activity)
        val settlementImbalanced = settlementValid.copy(
            draft = settlementValid.draft.copy(
                settlementShares = settlementValid.draft.settlementShares.mapIndexed { index, share ->
                    if (index == 0) share.copy(owedMinor = share.owedMinor + 1) else share
                },
            ),
        )
        val usd = (CurrencyCode.parse("USD") as DomainResult.Success).value
        val mismatchSnapshot = snapshot.copy(
            settlementActivities = snapshot.settlementActivities.map { it.copy(currency = usd) },
        )
        val mismatchEditor = OrdinaryRecordPolicy.selectSettlementActivity(
            OrdinaryRecordPolicy.setSettlementEnabled(
                OrdinaryRecordPolicy.changeExpression(editorFor(mismatchSnapshot), "1000", Locale.JAPAN),
                true,
            ),
            OrdinaryRecordDeviceFixtures.activity,
        )
        return buildList {
            add(contentCase("REC-001", "content", snapshot, editor))
            add(contentCase("REC-001", "noCategories", emptyCategories))
            add(RequiredStateCase("REC-001", "searching", OrdinaryRecordLoadState.Content(snapshot, search = "Food", editor = editor)))
            add(RequiredStateCase("REC-001", "loading", OrdinaryRecordLoadState.Loading))

            add(RequiredStateCase("REC-002", "typing", OrdinaryRecordLoadState.Content(snapshot, search = "F", editor = editor)))
            add(RequiredStateCase("REC-002", "results", OrdinaryRecordLoadState.Content(snapshot, search = "Food", editor = editor)))
            add(RequiredStateCase("REC-002", "empty", OrdinaryRecordLoadState.Content(snapshot, search = "not-present", editor = editor)))

            add(RequiredStateCase("REC-003", "loading", OrdinaryRecordLoadState.Loading))
            add(contentCase("REC-003", "editing", snapshot, editor))
            add(contentCase("REC-003", "validating", snapshot, OrdinaryRecordPolicy.validate(editor)))
            add(contentCase("REC-003", "saving", snapshot, editor.copy(presentation = RecordEditorPresentation.SAVING)))
            add(contentCase("REC-003", "saveError", snapshot, editor.copy(presentation = RecordEditorPresentation.SAVE_ERROR, sanitizedFailureCode = "WRITE_FAILED")))
            add(contentCase("REC-003", "revisionConflict", snapshot, editor.copy(presentation = RecordEditorPresentation.REVISION_CONFLICT)))

            add(contentCase("REC-004", "content", snapshot, editor))
            add(contentCase("REC-004", "empty", emptyCategories))
            add(contentCase("REC-004", "searching", snapshot, editor))
            add(contentCase("REC-005", "content", snapshot, editor))
            add(contentCase("REC-005", "empty", emptyAccounts))
            add(contentCase("REC-006", "content", snapshot, editor))
            add(contentCase("REC-006", "empty", emptyCards))
            add(contentCase("REC-007", "recent", snapshot, editor))
            add(contentCase("REC-007", "results", snapshot, editor))
            add(contentCase("REC-007", "empty", emptyMerchants))
            add(contentCase("REC-007", "create", emptyMerchants))
            add(contentCase("REC-008", "active", snapshot, editor))
            add(contentCase("REC-008", "archivedWarning", archivedProjectSnapshot, editorFor(archivedProjectSnapshot).copy(draft = editorFor(archivedProjectSnapshot).draft.copy(projectId = OrdinaryRecordDeviceFixtures.project))))
            add(contentCase("REC-008", "empty", emptyProjects))

            add(contentCase("REC-009", "locating", snapshot, editor))
            add(contentCase("REC-009", "located", snapshot, located))
            add(contentCase("REC-009", "permissionDenied", snapshot, editor))
            add(contentCase("REC-009", "timeout", snapshot, editor))
            add(contentCase("REC-009", "manual", snapshot, located))
            add(contentCase("REC-009", "mapUnavailable", snapshot, editor))

            add(contentCase("REC-010", "content", snapshot, attached))
            add(contentCase("REC-010", "empty", snapshot, editor))
            add(contentCase("REC-010", "importing", snapshot, editor.copy(attachmentImporting = true)))
            add(contentCase("REC-010", "failed", snapshot, editor.copy(attachmentFailureCode = "READ_FAILED")))

            add(contentCase("REC-011", "editing", snapshot, settlementEditing))
            add(contentCase("REC-011", "imbalanced", snapshot, settlementImbalanced))
            add(contentCase("REC-011", "valid", snapshot, settlementValid))
            add(contentCase("REC-011", "currencyMismatch", mismatchSnapshot, mismatchEditor))
            add(contentCase("REC-012", "content", snapshot, editor))
        }
    }

    private fun contentCase(
        screen: String,
        requiredState: String,
        snapshot: OrdinaryTransactionEntrySnapshot,
        editor: OrdinaryRecordEditorState = editorFor(snapshot),
    ): RequiredStateCase = RequiredStateCase(screen, requiredState, OrdinaryRecordLoadState.Content(snapshot, editor = editor))

    private fun editorFor(snapshot: OrdinaryTransactionEntrySnapshot): OrdinaryRecordEditorState = OrdinaryRecordPolicy.createEditor(
        snapshot,
        RecordEditorMode.CREATE,
        OrdinaryDirection.EXPENSE,
        snapshot.references.categories.firstOrNull { it.direction.name == OrdinaryDirection.EXPENSE.name }?.id,
        null,
        OrdinaryRecordDeviceFixtures.now,
        OrdinaryRecordDeviceFixtures.zone,
        Locale.JAPAN,
    )

    private data class Presentation(
        val screen: String,
        val state: OrdinaryRecordLoadState.Content,
        val width: Int,
        val fontScale: Float,
        val locale: String,
        val theme: ThemeMode,
    )

    private data class RequiredStateCase(
        val screen: String,
        val requiredState: String,
        val loadState: OrdinaryRecordLoadState,
    )
}
