@file:Suppress("LongMethod", "MagicNumber")

package app.ledger.feature.transfer

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.transfer.domain.DuplicateResolution
import app.ledger.transfer.domain.ImportWizardStage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** UI evidence is rendered only from production Compose, frozen tokens, and textual/YAML contracts. */
@RunWith(AndroidJUnit4::class)
class ImportUiContractDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun imp001ThroughImp010AndRequiredStatesRenderAcrossAccessibilityMatrix() {
        val cases = cases()
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            val base = LocalContext.current
            val localized = base.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(case.locale)))
                },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, case.fontScale),
                LocalActivityResultRegistryOwner provides composeRule.activity,
            ) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 2_400.dp)) { ImportWizardScreen(case.state, ACTIONS) }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(case.tag).assertExists()
        }
        assertEquals((1..10).map { "IMP-${it.toString().padStart(3, '0')}" }.toSet(), cases.map(Case::tag).toSet())
    }

    @Test
    fun hundredThousandRowPreviewIsVirtualizedAndSensitiveSampleIsAbsentFromSemantics() {
        val secret = "private-account-number-8842"
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides composeRule.activity) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp)) {
                        ImportWizardScreen(
                            base(ImportWizardStage.STRUCTURE).copy(
                                structureState = ImportStructureState.CONTENT,
                                previewRowCount = 100_000,
                                previewRow = { ImportPreviewRowUi(it + 1L, secret, "READY") },
                                totalRows = 100_000L,
                            ),
                            ACTIONS,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("IMP-002").assertExists()
        composeRule.onNodeWithText(secret, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun duplicateCandidateRequiresAndExposesExplicitResolutionControls() {
        var resolution: DuplicateResolution? = null
        val actions: (ImportWizardScreenAction) -> Unit = { action ->
            if (action is ImportWizardScreenAction.DuplicateResolved) resolution = action.resolution
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides composeRule.activity) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 1_200.dp)) {
                        ImportWizardScreen(
                            base(ImportWizardStage.VALIDATION).copy(
                                validationState = ImportValidationState.ERRORS,
                                errorCount = 1,
                                duplicateCount = 1,
                                duplicates = listOf(ImportDuplicateRowUi(99_999L, "CONTENT_HASH", null)),
                            ),
                            actions,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("import_duplicate_row").assertExists()
        composeRule.onNodeWithTag("import_duplicate_skip").performClick()
        composeRule.runOnIdle { assertEquals(DuplicateResolution.SKIP, resolution) }
    }

    @Test
    fun p28ProductionGoldensMatchEveryPixel() {
        val goldens = listOf(
            Golden(ThemeMode.LIGHT, base(ImportWizardStage.SOURCE), EXPECTED_SOURCE_SHA256),
            Golden(
                ThemeMode.DARK,
                base(ImportWizardStage.VALIDATION).copy(
                    validationState = ImportValidationState.ERRORS,
                    errorCount = 1,
                    duplicateCount = 1,
                    duplicates = listOf(ImportDuplicateRowUi(42L, "CONTENT_HASH", null)),
                ),
                EXPECTED_VALIDATION_SHA256,
            ),
        )
        val active = mutableStateOf(goldens.first())
        composeRule.setContent {
            val golden = active.value
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 1f),
                LocalActivityResultRegistryOwner provides composeRule.activity,
            ) {
                LedgerTheme(golden.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) {
                        ImportWizardScreen(golden.state, ACTIONS)
                    }
                }
            }
        }
        goldens.forEach { golden ->
            composeRule.runOnIdle { active.value = golden }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256()
            println("P28_GOLDEN_${golden.state.stage}=$actual")
            assertEquals(golden.expected, actual)
        }
    }

    private fun cases(): List<Case> {
        val states = listOf(
            base(ImportWizardStage.SOURCE),
            base(ImportWizardStage.SOURCE).copy(sourceState = ImportSourceState.PERMISSION_ERROR),
            base(ImportWizardStage.STRUCTURE).copy(structureState = ImportStructureState.PARSING),
            base(ImportWizardStage.STRUCTURE).copy(structureState = ImportStructureState.CONTENT),
            base(ImportWizardStage.STRUCTURE).copy(structureState = ImportStructureState.CORRUPT_FILE),
            base(ImportWizardStage.STRUCTURE).copy(structureState = ImportStructureState.UNSUPPORTED),
            base(ImportWizardStage.FIELD_MAPPING),
            base(ImportWizardStage.FIELD_MAPPING).copy(mappings = listOf(ImportMappingRowUi("amount", null, "10", false))),
            base(ImportWizardStage.FIELD_MAPPING).copy(mappings = listOf(ImportMappingRowUi("amount", "AMOUNT_EXPRESSION", "10", true))),
            base(ImportWizardStage.ENTITY_MAPPING),
            base(ImportWizardStage.ENTITY_MAPPING).copy(entityMappings = listOf(ImportEntityMappingUi("ACCOUNT", 1, false, true))),
            base(ImportWizardStage.ENTITY_MAPPING).copy(entityMappings = listOf(ImportEntityMappingUi("ACCOUNT", 1, true, true))),
            base(ImportWizardStage.FX),
            base(ImportWizardStage.FX).copy(fxRows = listOf(ImportFxRowUi("USD", "JPY", null, true))),
            base(ImportWizardStage.FX).copy(fxRows = listOf(ImportFxRowUi("USD", "JPY", "151.2", false))),
            base(ImportWizardStage.VALIDATION).copy(validationState = ImportValidationState.VALIDATING),
            base(ImportWizardStage.VALIDATION).copy(validationState = ImportValidationState.ERRORS, errorCount = 1),
            base(ImportWizardStage.VALIDATION).copy(validationState = ImportValidationState.WARNINGS, warningCount = 1),
            base(ImportWizardStage.VALIDATION).copy(validationState = ImportValidationState.VALID),
            base(ImportWizardStage.CONFIRMATION),
            base(ImportWizardStage.CONFIRMATION).copy(errorCount = 1),
        ) + ImportExecutionState.entries.map { base(ImportWizardStage.EXECUTION).copy(executionState = it) } + listOf(
            base(ImportWizardStage.RESULT).copy(executionState = ImportExecutionState.SUCCEEDED),
            base(ImportWizardStage.RESULT).copy(executionState = ImportExecutionState.CANCELLED),
            base(ImportWizardStage.RESULT).copy(executionState = ImportExecutionState.FAILED),
            base(ImportWizardStage.RESULT).copy(showHistory = true, history = listOf(ImportHistoryRowUi("CSV", "2026-08-09", 8, true))),
            base(ImportWizardStage.RESULT).copy(showHistory = true),
        )
        return states.mapIndexed { index, state ->
            Case(
                if (state.showHistory) "IMP-010" else "IMP-${(state.stage.ordinal + 1).toString().padStart(3, '0')}",
                state,
                listOf(320, 360, 480)[index % 3],
                listOf(1f, 1.3f, 2f)[index % 3],
                listOf("zh-CN", "en-US", "ja-JP")[index % 3],
                if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
            )
        }
    }

    private fun base(stage: ImportWizardStage) = ImportWizardUiState(
        stage = stage,
        structureState = ImportStructureState.CONTENT,
        sheetNames = listOf("transactions"),
        selectedSheet = "transactions",
        mappings = listOf(ImportMappingRowUi("amount", "AMOUNT_EXPRESSION", "1200", true)),
        entityMappings = listOf(ImportEntityMappingUi("ACCOUNT", 0, true, true)),
        previewRowCount = 1,
        previewRow = { ImportPreviewRowUi(2, "JPY 1,200", "READY") },
        processedRows = 64,
        totalRows = 100,
    )

    private fun Bitmap.pixelSha256(): String {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES * (pixels.size + 2))
        buffer.putInt(width)
        buffer.putInt(height)
        pixels.forEach(buffer::putInt)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }

    private data class Case(
        val tag: String,
        val state: ImportWizardUiState,
        val width: Int,
        val fontScale: Float,
        val locale: String,
        val theme: ThemeMode,
    )
    private data class Golden(val theme: ThemeMode, val state: ImportWizardUiState, val expected: String)

    private companion object {
        const val GOLDEN_TAG = "p28_import_golden_root"
        const val EXPECTED_SOURCE_SHA256 = "12f8bfa6a52add008a6950783b219dafb642fa6fb366790532f2e7423ad09d96"
        const val EXPECTED_VALIDATION_SHA256 = "175eb93bb5588cda9e9eef31bdefc84af52cbce53014942b5aa48d520c7cce53"
        val ACTIONS: (ImportWizardScreenAction) -> Unit = {}
    }
}
