package app.ledger.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.security.MaintenanceReason
import app.ledger.core.security.RecoveryDiagnosticCode
import app.ledger.feature.onboarding.OnboardingActions
import app.ledger.feature.onboarding.OnboardingLanguage
import app.ledger.feature.onboarding.OnboardingRenderState
import app.ledger.feature.onboarding.OnboardingScreen
import app.ledger.feature.onboarding.OnboardingStep
import app.ledger.feature.onboarding.OnboardingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P11 baselines are captured solely from the Compose/token implementation on the API 36 managed device. */
@RunWith(AndroidJUnit4::class)
class P11GoldenDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @SdkSuppress(minSdkVersion = 36)
    fun frozenGlobalAndOnboardingGoldensMatchEveryPixel() {
        val record = InstrumentationRegistry.getArguments().getString(RECORD_ARGUMENT) == "true"
        val recordDirectory = File(
            requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "p11-goldens",
        )
        if (record) check(recordDirectory.mkdirs() || recordDirectory.isDirectory)
        val active = mutableIntStateOf(0)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(GOLDEN_TAG)) {
                        cases[active.intValue].content()
                    }
                }
            }
        }
        cases.forEachIndexed { index, golden ->
            composeRule.runOnIdle { active.intValue = index }
            composeRule.waitForIdle()
            val actual = composeRule.onNodeWithTag(GOLDEN_TAG).captureToImage().asAndroidBitmap()
            assertEquals(WIDTH, actual.width)
            assertEquals(HEIGHT, actual.height)
            if (record) {
                File(recordDirectory, golden.assetName).outputStream().use { output ->
                    assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } else {
                val expected = InstrumentationRegistry.getInstrumentation().context.assets
                    .open("goldens/${golden.assetName}").use(BitmapFactory::decodeStream)
                assertEquals(golden.assetName, expected.width, actual.width)
                assertEquals(golden.assetName, expected.height, actual.height)
                assertEquals(golden.assetName, 0, changedPixelCount(expected, actual))
                expected.recycle()
            }
            actual.recycle()
        }
        if (record) Thread.sleep(RECORD_PULL_WINDOW_MILLIS)
    }

    private fun changedPixelCount(expected: Bitmap, actual: Bitmap): Int {
        var changed = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                if (expected.getPixel(x, y) != actual.getPixel(x, y)) changed += 1
            }
        }
        return changed
    }

    private val cases = listOf(
        GoldenCase("p11_locked.png") { LockScreen(AppAuthenticationState.CREDENTIAL_ONLY, {}) },
        GoldenCase("p11_maintenance.png") {
            MaintenanceScreen(MaintenanceReason.CONTROLLED_MAINTENANCE, MaintenancePresentation.RUNNING)
        },
        GoldenCase("p11_recovery.png") {
            RecoveryRequiredScreen(
                RecoveryDiagnosticCode.SCHEMA_INVALID,
                RecoveryPresentation.NO_BACKUP,
                {},
                {},
                {},
            )
        },
        GoldenCase("p11_onboarding_backup_error.png") {
            OnboardingScreen(
                OnboardingUiState(
                    step = OnboardingStep.BACKUP,
                    renderState = OnboardingRenderState.VALIDATION_ERROR,
                    language = OnboardingLanguage.ENGLISH,
                    baseCurrency = "JPY",
                    zoneId = "Asia/Tokyo",
                    privacyAccepted = true,
                    errorCode = "RECOVERY_PASSWORD_MISMATCH",
                ),
                noOpActions,
            )
        },
    )

    private val noOpActions = OnboardingActions(
        {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
    )

    private data class GoldenCase(val assetName: String, val content: @Composable () -> Unit)

    private companion object {
        const val RECORD_ARGUMENT = "recordP11Goldens"
        const val GOLDEN_TAG = "p11_golden_root"
        const val WIDTH = 360
        const val HEIGHT = 720
        const val RECORD_PULL_WINDOW_MILLIS = 30_000L
    }
}
