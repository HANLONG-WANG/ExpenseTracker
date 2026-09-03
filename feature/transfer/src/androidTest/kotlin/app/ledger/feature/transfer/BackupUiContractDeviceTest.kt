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
import app.ledger.transfer.domain.BackupPhase
import app.ledger.transfer.domain.BackupRepositoryKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class BackupUiContractDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bkp001ThroughBkp007AndSys003RequiredStatesRenderInThreeLanguages() {
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
                    Box(Modifier.size(case.width.dp, 3_200.dp)) { BackupFlowScreen(case.state, ACTIONS) }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("backup_flow_root").assertExists()
        }
        assertEquals(setOf("BKP-001", "BKP-002", "BKP-003", "BKP-004", "BKP-005", "BKP-006", "BKP-007", "SYS-003"), cases.map { it.state.screenId }.toSet())
        assertEquals(setOf("zh-CN", "en-US", "ja-JP"), cases.map(Case::locale).toSet())
    }

    @Test
    fun recoveryPasswordIsAbsentFromSemanticsAndVaultRequiresWrappedRecoveryKey() {
        val secret = "NeverExpose12345"
        val active = mutableStateOf(base().copy(screenId = "BKP-003", recoveryPassword = secret, recoveryPasswordConfirmation = secret))
        composeRule.setContent {
            val baseContext = LocalContext.current
            val localized = baseContext.createConfigurationContext(
                Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) },
            )
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides composeRule.activity,
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
            ) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 1_600.dp)) { BackupFlowScreen(active.value, ACTIONS) }
                }
            }
        }
        composeRule.onNodeWithText(secret, useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnIdle {
            active.value = base().copy(screenId = "BKP-004", recoveryPasswordConfigured = false, vaultBackupReady = false, includeVault = false)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("必须先设置恢复密码，才能包含保险库。", substring = true).assertExists()
    }

    @Test
    fun fiveVisibleBackupPhasesAndGeneratedScreenshotsAreStable() {
        val states = listOf(
            base().copy(screenId = "BKP-001", homePresentation = BackupHomePresentation.CONFIGURED),
            base().copy(
                screenId = "BKP-007",
                execution = BackupExecutionPresentation.RUNNING,
                phase = BackupPhase.PUBLISHING_MANIFEST,
                completedBytes = 75,
                totalBytes = 100,
            ),
        )
        val active = mutableStateOf(states.first())
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalActivityResultRegistryOwner provides composeRule.activity) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(360.dp, 720.dp).testTag(GOLDEN_TAG)) { BackupFlowScreen(active.value, ACTIONS) }
                }
            }
        }
        val actual = states.map { state ->
            composeRule.runOnIdle { active.value = state }
            composeRule.waitForIdle()
            val bitmap = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap()
            bitmap.pixelSha256().also { println("P30_GOLDEN_${state.screenId}=$it") }
        }
        assertEquals(EXPECTED_GOLDENS, actual)
        assertEquals(
            listOf(
                BackupPhase.DATABASE_SNAPSHOT,
                BackupPhase.OBJECT_PROCESSING,
                BackupPhase.WRITING_OR_UPLOADING,
                BackupPhase.VERIFYING,
                BackupPhase.PUBLISHING_MANIFEST,
            ),
            BackupPhase.entries.take(5),
        )
    }

    private fun cases(): List<Case> {
        val snapshot = BackupSnapshotUi(
            "01234567-89ab-cdef-0123-456789abcdef",
            "2026-08-09T12:30:00",
            "1.25 GiB · 84 objects",
            "12.50 MiB",
            BackupRepositoryKind.GOOGLE_DRIVE,
            "",
            BackupIntegrityPresentation.VERIFIED,
            true,
        )
        val states = BackupHomePresentation.entries.map { base().copy(screenId = "BKP-001", homePresentation = it) } +
            BackupRepositoryKind.entries.map { base().copy(screenId = "BKP-002", repositoryKind = it) } +
            listOf(
                base().copy(screenId = "BKP-003"),
                base().copy(screenId = "BKP-003", recoveryPasswordConfigured = true, recoveryPasswordError = true),
                base().copy(screenId = "BKP-003", recoveryPasswordConfigured = true, execution = BackupExecutionPresentation.RUNNING),
                base().copy(screenId = "BKP-004", recoveryPasswordConfigured = false),
                base().copy(screenId = "BKP-004", recoveryPasswordConfigured = true, vaultBackupReady = true, includeVault = true),
                base().copy(screenId = "BKP-005"),
                base().copy(screenId = "BKP-005", loadingRemote = true, snapshots = listOf(snapshot)),
                base().copy(screenId = "BKP-006", selectedSnapshot = snapshot),
                base().copy(screenId = "BKP-006", selectedSnapshot = snapshot.copy(integrity = BackupIntegrityPresentation.CORRUPT)),
                base().copy(screenId = "BKP-007"),
                base().copy(screenId = "BKP-007", execution = BackupExecutionPresentation.RUNNING, completedBytes = 1, totalBytes = 2),
                base().copy(screenId = "BKP-007", execution = BackupExecutionPresentation.FAILED, failureCode = "BACKUP_INSUFFICIENT_SPACE", temporaryCleanupComplete = true),
                base().copy(screenId = "BKP-007", execution = BackupExecutionPresentation.SUCCEEDED),
            ) + DriveAuthorizationPresentation.entries.map { base().copy(screenId = "SYS-003", driveAuthorization = it) }
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

    private fun base() = BackupFlowUiState(
        repositoryKind = BackupRepositoryKind.APP_PRIVATE,
        repositoryLabel = "App-private encrypted repository",
        recoveryPasswordConfigured = true,
        vaultBackupReady = true,
        retentionCount = "30",
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

    private data class Case(val state: BackupFlowUiState, val locale: String, val width: Int, val fontScale: Float, val theme: ThemeMode)

    private companion object {
        const val GOLDEN_TAG = "p30_backup_golden_root"
        val EXPECTED_GOLDENS = listOf(
            "661c06bb923ad64340b8f4a429eaea80e110853a0527ebd290547ad6e7fc4f9b",
            "acf7f5cfa33ff53b492b86843ea71c4a620c6e51b52da97b8e0b0c85a5d58a8d",
        )
        val ACTIONS = BackupFlowActions(
            onBack = {},
            onNavigate = {},
            onRepositoryKindSelected = {},
            onDirectorySelected = {},
            onAuthorizeDrive = {},
            onDisconnectDrive = {},
            onRecoveryPasswordChanged = {},
            onRecoveryPasswordConfirmationChanged = {},
            onRecoveryPasswordChangeModeChanged = {},
            onSaveRecoveryPassword = {},
            onAutomaticBackupChanged = {},
            onRetentionCountChanged = {},
            onRetentionDaysChanged = {},
            onIncludeVaultChanged = {},
            onNetworkPolicyChanged = {},
            onSaveSettings = {},
            onSnapshotSelected = {},
            onPortableChanged = {},
            onPortableFileNameChanged = {},
            onStartBackup = {},
            onCancel = {},
            onRetry = {},
            onOperations = {},
        )
    }
}
