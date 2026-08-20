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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportField
import app.ledger.transfer.domain.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ExportUiContractDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun exp001ThroughExp004AndAllRequiredStatesRenderInThreeLanguages() {
        val cases = cases()
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            val base = LocalContext.current
            val localized = base.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.forLanguageTag(case.locale))) },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, case.fontScale),
                LocalActivityResultRegistryOwner provides composeRule.activity,
            ) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 2_600.dp)) { ExportFlowScreen(case.state, ACTIONS) }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(case.state.rootTestTag()).assertExists()
        }
        assertEquals(setOf("EXP-001", "EXP-002", "EXP-003", "EXP-004"), cases.map { it.state.screenId }.toSet())
        assertEquals(setOf("zh-CN", "en-US", "ja-JP"), cases.map(Case::locale).toSet())
    }

    @Test
    fun sensitiveVaultFieldsAreAbsentAndCoordinatesAreOffByDefault() {
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides composeRule.activity) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 2_400.dp)) { ExportFlowScreen(base().copy(screenId = "EXP-002"), ACTIONS) }
                }
            }
        }
        listOf("完整卡号", "安全码", "PAN", "CVC", "CVV", "保险库值").forEach { forbidden ->
            composeRule.onNodeWithText(forbidden, substring = false, useUnmergedTree = true).assertDoesNotExist()
        }
        assertEquals(false, base().includeLocationCoordinates)
        assertEquals(false, ExportField.LATITUDE_E7 in base().selectedFields)
        assertEquals(false, ExportField.LONGITUDE_E7 in base().selectedFields)
    }

    @Test
    fun p29ProductionGoldensMatchEveryPixel() {
        val goldens = listOf(
            Golden(ThemeMode.LIGHT, base().copy(screenId = "EXP-001"), EXPECTED_CONTENT_SHA256),
            Golden(
                ThemeMode.DARK,
                base().copy(
                    screenId = "EXP-004",
                    executionPresentation = ExportExecutionPresentation.SUCCEEDED,
                    processedRows = 100_000,
                    totalRows = 100_000,
                    canOpen = true,
                    canShare = true,
                    canViewLocation = true,
                ),
                EXPECTED_RESULT_SHA256,
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
                        ExportFlowScreen(golden.state, ACTIONS)
                    }
                }
            }
        }
        val actuals = goldens.map { golden ->
            composeRule.runOnIdle { active.value = golden }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256().also {
                println("P29_GOLDEN_${golden.state.screenId}=$it")
            }
        }
        assertEquals(goldens.map(Golden::expected), actuals)
    }

    private fun cases(): List<Case> = listOf(
        base().copy(screenId = "EXP-001"),
        base().copy(screenId = "EXP-002", selectedFields = setOf(ExportField.TRANSACTION_ID)),
        base().copy(screenId = "EXP-002"),
        base().copy(screenId = "EXP-003", destinationPresentation = ExportDestinationPresentation.CONTENT),
        base().copy(screenId = "EXP-003", destinationPresentation = ExportDestinationPresentation.PERMISSION_REVOKED),
        base().copy(screenId = "EXP-003", destinationPresentation = ExportDestinationPresentation.NAME_CONFLICT),
        base().copy(screenId = "EXP-004", executionPresentation = ExportExecutionPresentation.RUNNING, processedRows = 50, totalRows = 100),
        base().copy(screenId = "EXP-004", executionPresentation = ExportExecutionPresentation.CANCEL_REQUESTED),
        base().copy(
            screenId = "EXP-004",
            executionPresentation = ExportExecutionPresentation.FAILED,
            failureCode = "EXPORT_INSUFFICIENT_SPACE",
            temporaryCleanupComplete = true,
        ),
        base().copy(
            screenId = "EXP-004",
            executionPresentation = ExportExecutionPresentation.SUCCEEDED,
            processedRows = 100,
            canOpen = true,
            canShare = true,
            canViewLocation = true,
        ),
        base().copy(
            screenId = "EXP-004",
            executionPresentation = ExportExecutionPresentation.SUCCEEDED,
            processedRows = 100,
            externalApplicationUnavailable = true,
        ),
    ).mapIndexed { index, state ->
        Case(
            state,
            listOf("zh-CN", "en-US", "ja-JP")[index % 3],
            listOf(320, 360, 480)[index % 3],
            listOf(1f, 1.3f, 2f)[index % 3],
            if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
        )
    }

    private fun base() = ExportFlowUiState(
        content = ExportContent.CURRENT_FILTER,
        format = ExportFormat.CSV,
        filterSummary = "2026-01-01 — 2026-08-09 · two accounts",
        workbookSheets = listOf("accounts", "cards", "transactions", "locations"),
        fileName = "transactions.csv",
    )

    private fun ExportFlowUiState.rootTestTag(): String = when (screenId) {
        "EXP-001" -> "export_type"
        "EXP-002" -> "export_fields"
        "EXP-003" -> "export_destination"
        "EXP-004" -> "export_progress"
        else -> error("unknown export screen")
    }

    private data class Case(val state: ExportFlowUiState, val locale: String, val width: Int, val fontScale: Float, val theme: ThemeMode)
    private data class Golden(val theme: ThemeMode, val state: ExportFlowUiState, val expected: String)

    private fun Bitmap.pixelSha256(): String {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES * (pixels.size + 2))
        buffer.putInt(width)
        buffer.putInt(height)
        pixels.forEach(buffer::putInt)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val GOLDEN_TAG = "p29_export_golden_root"
        const val EXPECTED_CONTENT_SHA256 = "a487270b1501caa3751747c2db458be0e0ef85f8352209daf76a8059ec5cc3a2"
        const val EXPECTED_RESULT_SHA256 = "93d8c476896c3d7f087cbbd80e47a86db0b156ae75b1024239e89505349d225b"
        val ACTIONS: (ExportFlowScreenAction) -> Unit = {}
    }
}
