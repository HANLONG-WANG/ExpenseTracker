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
import app.ledger.transfer.domain.MergeConflictKind
import app.ledger.transfer.domain.MergeResolution
import app.ledger.transfer.domain.RestoreMode
import app.ledger.transfer.domain.RestoreState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RestoreUiContractDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rst001ThroughRst007AndClr002StatesRenderAcrossThreeLanguagesAndAccessibilitySizes() {
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
                    Box(Modifier.size(case.width.dp, 3_200.dp)) { RestoreFlowScreen(case.state, ACTIONS) }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("restore_flow_root").assertExists()
        }
        assertEquals(
            setOf("RST-001", "RST-002", "RST-003", "RST-004", "RST-005", "RST-006", "RST-007", "CLR-002"),
            cases.map { it.state.screenId }.toSet(),
        )
        assertEquals(setOf("zh-CN", "en-US", "ja-JP"), cases.map(Case::locale).toSet())
    }

    @Test
    fun passwordNeverEntersSemanticsAndPurgeConflictCannotOfferResurrection() {
        val secret = "restore-password-never-in-semantics-31"
        val active = mutableStateOf(base("RST-002").copy(password = RestorePasswordInput.copyOf(secret)))
        composeRule.setContent {
            val baseContext = LocalContext.current
            val localized = baseContext.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.US)) },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalActivityResultRegistryOwner provides composeRule.activity,
            ) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 1_600.dp)) { RestoreFlowScreen(active.value, ACTIONS) }
                }
            }
        }
        composeRule.onNodeWithText(secret, useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnIdle {
            active.value = base("RST-005").copy(conflicts = listOf(purgeConflict()))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("an old version cannot be restored", substring = true).assertExists()
        composeRule.onNodeWithText("Current version:", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Backup version:", substring = true).assertDoesNotExist()
    }

    @Test
    fun mergeRestorePurgeTombstoneWinsThroughTheApplyAction() {
        val purge = purgeConflict()
        var resolutionChanges = 0
        var mergeApplications = 0
        val actions = ACTIONS.copy(
            onResolveConflict = { _, _ -> resolutionChanges += 1 },
            onApplyMerge = {
                assertEquals(MergeResolution.KeepPurgeTombstone, purge.resolution)
                assertEquals(true, purge.purgeTombstoneWins)
                mergeApplications += 1
            },
        )
        composeRule.setContent {
            val localized = LocalContext.current.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.US)) },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalActivityResultRegistryOwner provides composeRule.activity,
            ) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 800.dp)) {
                        RestoreFlowScreen(base("RST-005").copy(conflicts = listOf(purge)), actions)
                    }
                }
            }
        }

        composeRule.onNodeWithText("Apply merge").performClick()
        composeRule.runOnIdle {
            assertEquals(0, resolutionChanges)
            assertEquals(1, mergeApplications)
        }
    }

    @Test
    fun contractDerivedRestoreScreenshotsMatchPixelBaselines() {
        val states = listOf(
            base("RST-004").copy(mode = RestoreMode.REPLACE, mergeAvailable = true),
            base("RST-006").copy(
                phase = RestoreState.EXCHANGING,
                completedBytes = 75,
                totalBytes = 100,
            ),
        )
        val active = mutableStateOf(states.first())
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 1f),
                LocalActivityResultRegistryOwner provides composeRule.activity,
            ) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) { RestoreFlowScreen(active.value, ACTIONS) }
                }
            }
        }
        val actual = states.map { state ->
            composeRule.runOnIdle { active.value = state }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap().pixelSha256().also {
                println("P31_GOLDEN_${state.screenId}=$it")
            }
        }
        assertEquals(EXPECTED_GOLDENS, actual)
    }

    private fun cases(): List<Case> {
        val normalConflict = RestoreConflictUi(
            "conflict-1",
            MergeConflictKind.TRANSACTION_REVISION_FORK,
            "TRANSACTION:opaque-id",
            "ancestor hash",
            "local hash",
            "incoming hash",
            null,
            false,
        )
        val states = RestoreSourcePresentation.entries.map { base("RST-001").copy(sourcePresentation = it) } +
            RestorePasswordPresentation.entries.map {
                base("RST-002").copy(
                    passwordPresentation = it,
                    password = RestorePasswordInput.copyOf("safe-test-value"),
                )
            } +
            RestoreInspectPresentation.entries.map { base("RST-003").copy(inspectPresentation = it) } + listOf(
                base("RST-004").copy(mode = RestoreMode.REPLACE, mergeAvailable = true),
                base("RST-004").copy(mode = RestoreMode.MERGE, mergeAvailable = true),
                base("RST-004").copy(mode = RestoreMode.REPLACE, mergeAvailable = false),
                base("RST-005").copy(conflicts = listOf(normalConflict)),
                base("RST-005").copy(conflicts = listOf(normalConflict.copy(resolution = MergeResolution.KeepLocal), purgeConflict())),
            ) + RestoreState.entries.map { base("RST-006").copy(phase = it, completedBytes = 1, totalBytes = 2) } + listOf(
                base("RST-006").copy(progressPresentation = RestoreProgressPresentation.FAILED_ROLLBACK, failureCode = "RESTORE_ROLLED_BACK"),
            ) + RestoreResultPresentation.entries.map { base("RST-007").copy(resultPresentation = it) } +
            CloudClearPresentation.entries.map {
                base("CLR-002").copy(
                    cloudClearPresentation = it,
                    cloudAuthenticated = it != CloudClearPresentation.AUTH_REQUIRED,
                    cloudSnapshots = listOf(
                        RestoreSnapshotUi("opaque-snapshot-1", "2026-08-20 10:00", app.ledger.transfer.domain.BackupRepositoryKind.GOOGLE_DRIVE, true, true),
                        RestoreSnapshotUi("opaque-snapshot-2", "2026-08-19 10:00", app.ledger.transfer.domain.BackupRepositoryKind.GOOGLE_DRIVE, true, false),
                    ),
                    selectedCloudSnapshots = setOf("opaque-snapshot-1"),
                )
            }
        return states.mapIndexed { index, state ->
            Case(
                state,
                listOf("zh-CN", "en-US", "ja-JP")[index % 3],
                listOf(320, 360, 480)[index % 3],
                listOf(1f, 1.3f, 2f)[index % 3],
                if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
            )
        }
    }

    private fun base(screen: String) = RestoreFlowUiState(
        screenId = screen,
        sourceLabel = "verified-backup",
        bookIdentity = "opaque-book-id",
        sourceVersion = "schema 1",
        baseCurrency = "JPY",
        restoredObjectCount = 42,
        restoredLogicalBytes = 8_388_608,
        attachmentCount = 3,
        includesVault = true,
        integrityChecks = RestoreIntegrityCheck.entries.map { RestoreIntegrityCheckUi(it, true) },
        mergeAvailable = true,
        safetySnapshotLabel = "verified safety snapshot",
        verificationSummary = "live ledger verified",
    )

    private fun purgeConflict() = RestoreConflictUi(
        "purge-1",
        MergeConflictKind.PURGED_ENTITY,
        "TRANSACTION:opaque-purged-id",
        "",
        "",
        "",
        MergeResolution.KeepPurgeTombstone,
        true,
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
        val state: RestoreFlowUiState,
        val locale: String,
        val width: Int,
        val fontScale: Float,
        val theme: ThemeMode,
    )

    private companion object {
        const val GOLDEN_TAG = "p31_restore_golden_root"
        val EXPECTED_GOLDENS = listOf(
            "339869d466d26e53fb877e81270de09a6894e8d2eee1610784bf1881653d9fda",
            "ec6e99eac7c059e541b19ec577e264ca75351b5d0f0d28c8b69199454da421b8",
        )
        val ACTIONS = RestoreFlowActions(
            onBack = {}, onPortableSource = {}, onRepositorySource = {}, onDriveSource = {},
            onSnapshotSourceSelected = {},
            onPasswordChanged = {}, onVerifyPassword = {}, onModeSelected = {}, onHighRiskPhraseChanged = {},
            onStartRestore = {}, onResolveConflict = { _, _ -> }, onApplyToSimilarChanged = {}, onApplyMerge = {}, onCancel = {}, onRetry = {},
            onOpenApp = {}, onCloudSnapshotSelected = {}, onCloudConfirmationChanged = {},
            onAuthenticateCloudDelete = {}, onDeleteCloudBackups = {},
        )
    }
}
