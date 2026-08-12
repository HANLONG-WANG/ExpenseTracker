package app.ledger.app

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.feature.settings.RemainingSettingsActions
import app.ledger.feature.settings.RemainingSettingsDestination
import app.ledger.feature.settings.RemainingSettingsState
import app.ledger.feature.settings.SettingsDateFormat
import app.ledger.feature.settings.SettingsThemeMode
import app.ledger.feature.settings.SettingsWeekStart
import app.ledger.feature.transfer.TransferHubActions
import app.ledger.feature.transfer.TransferHubScreen
import app.ledger.feature.transfer.TransferHubState
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.MaintenanceKind
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.OperationProgress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class P33UiContractDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allP33GlobalTransferSettingsAndNotificationStatesRender() {
        val targets = buildList {
            MorePresentation.entries.forEach { add(Target.More(it)) }
            add(Target.Operations(OperationCenterLoadState.Loading))
            add(Target.Operations(OperationCenterLoadState.Failure("OPERATION_STORE_UNAVAILABLE")))
            add(Target.Operations(OperationCenterLoadState.Content(emptyList())))
            add(Target.Operations(OperationCenterLoadState.Content(BackgroundOperationState.entries.mapIndexed(::operation))))
            add(Target.Help("widgets"))
            add(Target.Help("not-allowlisted"))
            add(Target.Transfer(false, true))
            add(Target.Transfer(true, false))
            listOf("SETG-001", "SETG-002", "SETG-003", "SETG-005", "SETG-012").forEach { add(Target.Settings(it)) }
            NotificationPermissionPresentation.entries.forEach { add(Target.Notification(it)) }
        }
        assertEquals(19, targets.size)
        val active = mutableStateOf(targets.first())
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                Box(Modifier.testTag(HOST_TAG)) { render(active.value) }
            }
        }
        targets.forEach { target ->
            composeRule.runOnIdle { active.value = target }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HOST_TAG).assertExists()
        }
    }

    @Test
    fun moreTransferSettingsHelpAndPermissionUseAllThreeLocales() {
        val locale = mutableStateOf(Locale.SIMPLIFIED_CHINESE)
        composeRule.setContent {
            val context = localizedContext(locale.value)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                    Column {
                        MoreContent(
                            MorePresentation.CONTENT,
                            {},
                            {},
                            modifier = Modifier.testTag(MORE_LIST_TAG),
                        )
                        TransferHubScreen(TransferHubState(true, false), noOpTransferActions)
                        NotificationPermissionContent(NotificationPermissionPresentation.DENIED, {}, {}, {})
                    }
                }
            }
        }
        listOf(
            Locale.SIMPLIFIED_CHINESE to "数据传输中心",
            Locale.JAPANESE to "データ転送センター",
            Locale.ENGLISH to "Data transfer center",
        ).forEach { (target, expected) ->
            composeRule.runOnIdle { locale.value = target }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(MORE_LIST_TAG).performScrollToNode(hasText(expected))
            composeRule.onNodeWithText(expected).assertExists()
        }
    }

    @androidx.compose.runtime.Composable
    private fun render(target: Target) {
        when (target) {
            is Target.More -> MoreScreen(target.presentation, {}, {}, {})
            is Target.Operations -> DurableOperationCenterContent(target.state, {}, {}, {})
            is Target.Help -> HelpScreen(target.topic, {})
            is Target.Transfer -> TransferHubScreen(
                TransferHubState(target.active, target.notifications),
                noOpTransferActions,
            )
            is Target.Settings -> RemainingSettingsDestination(settings(target.screenId), noOpSettingsActions)
            is Target.Notification -> NotificationPermissionContent(target.presentation, {}, {}, {})
        }
    }

    private fun operation(index: Int, state: BackgroundOperationState): BackgroundOperation = BackgroundOperation.restore(
        id = BackgroundOperationId(StableId.fromUuid(UUID(0x33L, index.toLong() + 1L))),
        type = BackgroundOperationType.DATABASE_MAINTENANCE,
        state = state,
        createdAt = Instant.EPOCH,
        startedAt = Instant.EPOCH.takeIf { state != BackgroundOperationState.QUEUED },
        updatedAt = Instant.ofEpochSecond(index.toLong() + 1L),
        progress = OperationProgress(index.toLong(), 20L),
        checkpointVersion = index.toLong(),
        errorCode = "OPERATION_FAILED".takeIf {
            state == BackgroundOperationState.FAILED_RETRYABLE || state == BackgroundOperationState.FAILED_FINAL
        },
        cancelRequested = state in setOf(BackgroundOperationState.CANCEL_REQUESTED, BackgroundOperationState.ROLLING_BACK),
        parameters = OperationParameters.DatabaseMaintenance(MaintenanceKind.INTEGRITY_AUDIT),
    )

    private fun settings(screenId: String) = RemainingSettingsState(
        screenId,
        SettingsThemeMode.FOLLOW_SYSTEM,
        dynamicColor = false,
        defaultAmountsHidden = true,
        reduceMotion = false,
        languageTag = "zh-CN",
        dateFormat = SettingsDateFormat.LOCALE_DEFAULT,
        numberFormatSummary = "12,345.67",
        zoneId = "Asia/Tokyo",
        availableZoneIds = listOf("Asia/Tokyo", "UTC"),
        weekStart = SettingsWeekStart.LOCALE_DEFAULT,
        datePreview = "2026-08-11",
        appVersion = "0.2.0",
        licenses = listOf("AndroidX · Apache License 2.0"),
    )

    private fun localizedContext(locale: Locale): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return target.createConfigurationContext(
            Configuration(target.resources.configuration).apply { setLocales(LocaleList(locale)) },
        )
    }

    private sealed interface Target {
        data class More(val presentation: MorePresentation) : Target
        data class Operations(val state: OperationCenterLoadState) : Target
        data class Help(val topic: String?) : Target
        data class Transfer(val active: Boolean, val notifications: Boolean) : Target
        data class Settings(val screenId: String) : Target
        data class Notification(val presentation: NotificationPermissionPresentation) : Target
    }

    private val noOpTransferActions = TransferHubActions({}, {}, {}, {}, {})
    private val noOpSettingsActions = RemainingSettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private companion object {
        const val HOST_TAG = "p33_state_host"
        const val MORE_LIST_TAG = "p33_more_locale_list"
    }
}
