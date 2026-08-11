package app.ledger.widget

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.finance.application.WidgetAccountSnapshot
import app.ledger.finance.application.WidgetBookSnapshot
import app.ledger.finance.application.WidgetCreditSnapshot
import app.ledger.finance.application.WidgetGoalSnapshot
import app.ledger.finance.application.WidgetQuickDirection
import app.ledger.finance.application.WidgetQuickTarget
import app.ledger.finance.application.WidgetQuickTargetKind
import app.ledger.finance.application.WidgetSnapshotApplicationPort
import app.ledger.finance.application.WidgetSnapshotBundle
import app.ledger.finance.domain.LocalRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class P33WidgetConfigurationDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wgt001ThroughWgt003ConfigureEligibleDataAndDefaultToHiddenAmounts() {
        install(bundle())
        var saved: LedgerWidgetConfiguration? = null
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                WidgetConfigurationFlow(33, {}, { saved = it })
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithText(text(R.string.widget_choose_type)).fetchSemanticsNodes().isNotEmpty() }
        LedgerWidgetType.entries.forEach { composeRule.onNodeWithText(text(it.titleResource())).assertExists() }
        composeRule.onNodeWithText(text(R.string.widget_account)).performClick()
        composeRule.onNodeWithText("账户 A").performClick()
        composeRule.onNodeWithText(text(R.string.widget_next)).performClick()
        composeRule.onNodeWithText(text(R.string.widget_reveal_amounts)).assertIsOff()
        composeRule.onNodeWithText("••••").assertExists()
        assertEquals(null, saved)
        composeRule.onNodeWithText(text(R.string.widget_save)).performClick()
        composeRule.runOnIdle {
            assertEquals(LedgerWidgetType.ACCOUNT, saved?.type)
            assertEquals(ACCOUNT_ID, saved?.selectedId)
            assertFalse(requireNotNull(saved).revealAmounts)
        }
    }

    @Test
    fun wgt002NoEligibleDataReturnsToTypeSelection() {
        install(bundle().copy(accounts = emptyList()))
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                WidgetConfigurationFlow(34, {}, {})
            }
        }
        composeRule.waitUntil { composeRule.onAllNodesWithText(text(R.string.widget_account)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(text(R.string.widget_account)).performClick()
        composeRule.onNodeWithText(text(R.string.widget_no_eligible_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.widget_back)).performClick()
        composeRule.onNodeWithText(text(R.string.widget_choose_type)).assertExists()
    }

    @Test
    fun widgetConfigurationRendersSimplifiedChineseJapaneseAndEnglishResources() {
        install(bundle())
        val locale = mutableStateOf(Locale.SIMPLIFIED_CHINESE)
        composeRule.setContent {
            val context = localizedContext(locale.value)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                    WidgetConfigurationFlow(35, {}, {})
                }
            }
        }
        listOf(
            Locale.SIMPLIFIED_CHINESE to "选择小组件",
            Locale.JAPANESE to "ウィジェットを選択",
            Locale.ENGLISH to "Choose widget",
        ).forEach { (target, expected) ->
            composeRule.runOnIdle { locale.value = target }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(expected).assertExists()
        }
    }

    private fun install(snapshot: WidgetSnapshotBundle) {
        LedgerWidgetRuntime.install(
            object : WidgetSnapshotApplicationPort {
                override suspend fun read(bookId: StableId) = DomainResult.Success(snapshot)
                override suspend fun quickTargets(bookId: StableId) = DomainResult.Success(
                    listOf(WidgetQuickTarget(CATEGORY_ID, WidgetQuickTargetKind.CATEGORY, WidgetQuickDirection.EXPENSE, "食費")),
                )
            },
            object : LedgerWidgetConfigurationRepository {
                override suspend fun activeBookId(): StableId = BOOK_ID
                override suspend fun read(appWidgetId: Int): LedgerWidgetConfiguration? = null
                override suspend fun save(configuration: LedgerWidgetConfiguration) = Unit
                override suspend fun delete(appWidgetIds: Set<Int>) = Unit
            },
            localDate = { LocalDate.of(2026, 8, 11) },
        )
    }

    private fun bundle(): WidgetSnapshotBundle = WidgetSnapshotBundle(
        WidgetBookSnapshot(100L, 90L, "JPY", REVISION, REVISION, 20260811, 202608, 10L, 9L, 80L, 20L, 3L, 95L),
        listOf(WidgetAccountSnapshot(ACCOUNT_ID, "账户 A", 100L, 100L, "JPY", REVISION)),
        listOf(WidgetCreditSnapshot(CREDIT_ID, "信用账户", 10L, 90L, 10L, 20260825, "JPY", REVISION)),
        listOf(WidgetGoalSnapshot(GOAL_ID, "目标", 10L, 100L, "JPY", REVISION)),
    )

    private fun text(resource: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)

    private fun localizedContext(locale: Locale): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return target.createConfigurationContext(
            Configuration(target.resources.configuration).apply { setLocales(LocaleList(locale)) },
        )
    }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0x33L, 1L))
        val ACCOUNT_ID: StableId = StableId.fromUuid(UUID(0x33L, 2L))
        val CREDIT_ID: StableId = StableId.fromUuid(UUID(0x33L, 3L))
        val GOAL_ID: StableId = StableId.fromUuid(UUID(0x33L, 4L))
        val CATEGORY_ID: StableId = StableId.fromUuid(UUID(0x33L, 5L))
        val REVISION: LocalRevision = (LocalRevision.of(1L) as DomainResult.Success).value
    }
}
