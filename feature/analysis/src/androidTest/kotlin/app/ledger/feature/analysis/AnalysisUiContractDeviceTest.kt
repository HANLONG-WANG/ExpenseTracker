@file:Suppress("LongMethod", "MaxLineLength")

package app.ledger.feature.analysis

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.analytics.domain.DrilldownPage
import app.ledger.analytics.domain.IntegritySeverity
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AnalysisUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun everyFrozenRequiredStateRendersAcrossWidthFontLocaleAndThemeMatrix() {
        val cases = cases()
        assertEquals(45, cases.size)
        assertEquals(EXPECTED, cases.groupBy(Case::screen).mapValues { (_, values) -> values.map(Case::stateName).toSet() })
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = base.createConfigurationContext(
                Configuration(configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(case.locale))) },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, case.fontScale),
            ) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 1_700.dp)) {
                        AnalysisDestination(
                            case.screen,
                            AnalysisLoadState.Content(case.state),
                            AnalysisDeviceFixtures.actions,
                            mapContent = { _, _ -> Box(Modifier.size(320.dp, 280.dp).testTag("p27_map_host")) },
                        )
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(case.expectedTag).assertExists()
        }
    }

    @Test
    fun chartHasTextAlternativeAndExactDataTableAtTwoHundredPercentFont() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 1_200.dp)) {
                        AnalysisDestination(
                            "ANA-003",
                            AnalysisLoadState.Content(
                                AnalysisDeviceFixtures.base("ANA-003", AnalysisPresentation.CONTENT),
                            ),
                            AnalysisDeviceFixtures.actions,
                        )
                    }
                }
            }
        }
        val root = composeRule.onNodeWithTag(LedgerTestTags.REPORT_DETAIL)
        root.performScrollToNode(androidx.compose.ui.test.hasTestTag(LedgerTestTags.CHART))
        composeRule.onNodeWithTag(LedgerTestTags.CHART).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
        )
        root.performScrollToNode(androidx.compose.ui.test.hasTestTag(LedgerTestTags.DATA_TABLE))
        composeRule.onNodeWithTag(LedgerTestTags.DATA_TABLE).assertExists()
    }

    @Test
    fun mapFailureHasEquivalentListAndDetailMasksCoordinatesFromAccessibilityAtTwoHundredPercentFont() {
        val activeDetail = mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 1_700.dp)) {
                        if (activeDetail.value) {
                            AnalysisDestination(
                                "ANA-012",
                                AnalysisLoadState.Content(
                                    AnalysisDeviceFixtures.base("ANA-012", AnalysisPresentation.PLACE),
                                ),
                                AnalysisDeviceFixtures.actions,
                            )
                        } else {
                            AnalysisDestination(
                                "ANA-011",
                                AnalysisLoadState.Content(
                                    AnalysisDeviceFixtures.base("ANA-011", AnalysisPresentation.MAP_UNAVAILABLE),
                                ),
                                AnalysisDeviceFixtures.actions,
                                mapContent = { _, _ -> Box(Modifier.size(320.dp, 220.dp).testTag("p27_map_failed_host")) },
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.CONSUMPTION_MAP).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.CONSUMPTION_MAP).performScrollToNode(
            androidx.compose.ui.test.hasText("新宿"),
        )
        composeRule.runOnIdle { activeDetail.value = true }
        composeRule.waitForIdle()
        val detail = composeRule.onNodeWithTag(LedgerTestTags.CONSUMPTION_MAP_DETAIL)
        detail.performScrollToNode(androidx.compose.ui.test.hasTestTag(LedgerTestTags.DATA_TABLE))
        composeRule.onNodeWithTag(LedgerTestTags.DATA_TABLE).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.CONSUMPTION_MAP_LOCATION).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
        )
    }

    @Test
    fun mapFilterBuilderExposesSameDimensionOrAsIndependentlyRemovableChips() {
        var removedStableKey: String? = null
        val state = AnalysisDeviceFixtures.base("ANA-011", AnalysisPresentation.CLUSTERS).copy(
            consumptionMap = AnalysisDeviceFixtures.consumptionMapWithSelectedAccounts(),
            consumptionMapFilterOptions = AnalysisDeviceFixtures.consumptionMapFilterOptions(),
        )
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 1_700.dp)) {
                    AnalysisDestination(
                        "ANA-011",
                        AnalysisLoadState.Content(state),
                        { action ->
                            if (action is AnalysisScreenAction.RemoveMapFilter) removedStableKey = action.key
                        },
                        mapContent = { _, _ -> Box(Modifier.size(320.dp, 220.dp)) },
                    )
                }
            }
        }
        val root = composeRule.onNodeWithTag(LedgerTestTags.CONSUMPTION_MAP)
        root.performScrollToNode(androidx.compose.ui.test.hasText("Account 1"))
        composeRule.onNodeWithText("Account 1").performClick()
        composeRule.runOnIdle {
            assertEquals("account:${AnalysisDeviceFixtures.mapAccountOneId}", removedStableKey)
        }
        root.performScrollToNode(androidx.compose.ui.test.hasText("Account 2"))
        composeRule.onNodeWithText("Account 2").assertExists()
    }

    private fun cases(): List<Case> {
        val raw = listOf(
            Case("ANA-001", "content", LedgerTestTags.ANALYSIS_HOME, AnalysisDeviceFixtures.base("ANA-001", AnalysisPresentation.CONTENT)),
            Case("ANA-001", "noData", LedgerTestTags.ANALYSIS_HOME, AnalysisDeviceFixtures.base("ANA-001", AnalysisPresentation.NO_DATA)),
            Case("ANA-001", "calculating", LedgerTestTags.ANALYSIS_HOME, AnalysisDeviceFixtures.base("ANA-001", AnalysisPresentation.CALCULATING)),
            Case("ANA-001", "error", LedgerTestTags.ANALYSIS_HOME, AnalysisDeviceFixtures.base("ANA-001", AnalysisPresentation.ERROR, failureCode = "ANALYSIS_HOME_FAILED")),
            Case("ANA-002", "content", LedgerTestTags.REPORT_CATALOG, AnalysisDeviceFixtures.base("ANA-002", AnalysisPresentation.CONTENT)),
            Case("ANA-003", "loading", LedgerTestTags.REPORT_DETAIL, AnalysisDeviceFixtures.base("ANA-003", AnalysisPresentation.LOADING)),
            Case("ANA-003", "content", LedgerTestTags.REPORT_DETAIL, AnalysisDeviceFixtures.base("ANA-003", AnalysisPresentation.CONTENT)),
            Case("ANA-003", "empty", LedgerTestTags.REPORT_DETAIL, AnalysisDeviceFixtures.base("ANA-003", AnalysisPresentation.EMPTY, execution = AnalysisDeviceFixtures.emptyExecution())),
            Case("ANA-003", "queryError", LedgerTestTags.REPORT_DETAIL, AnalysisDeviceFixtures.base("ANA-003", AnalysisPresentation.QUERY_ERROR, failureCode = "REPORT_QUERY_FAILED")),
            Case("ANA-003", "staleRebuildRequired", LedgerTestTags.REPORT_DETAIL, AnalysisDeviceFixtures.base("ANA-003", AnalysisPresentation.STALE_REBUILD_REQUIRED, execution = AnalysisDeviceFixtures.staleExecution())),
            Case("ANA-004", "editing", LedgerTestTags.REPORT_FILTER, AnalysisDeviceFixtures.base("ANA-004", AnalysisPresentation.EDITING)),
            Case("ANA-004", "invalid", LedgerTestTags.REPORT_FILTER, AnalysisDeviceFixtures.base("ANA-004", AnalysisPresentation.INVALID)),
            Case("ANA-005", "content", LedgerTestTags.REPORT_DRILLDOWN, AnalysisDeviceFixtures.base("ANA-005", AnalysisPresentation.CONTENT)),
            Case(
                "ANA-005",
                "empty",
                LedgerTestTags.REPORT_DRILLDOWN,
                AnalysisDeviceFixtures.base("ANA-005", AnalysisPresentation.EMPTY, drilldown = DrilldownPage(emptyList(), null)),
            ),
            Case("ANA-005", "expiredQuery", LedgerTestTags.REPORT_DRILLDOWN, AnalysisDeviceFixtures.base("ANA-005", AnalysisPresentation.EXPIRED_QUERY)),
            Case("ANA-006", "content", LedgerTestTags.DASHBOARD_LIST, AnalysisDeviceFixtures.base("ANA-006", AnalysisPresentation.CONTENT)),
            Case("ANA-006", "empty", LedgerTestTags.DASHBOARD_LIST, AnalysisDeviceFixtures.base("ANA-006", AnalysisPresentation.EMPTY).copy(dashboards = emptyList())),
            Case("ANA-007", "create", LedgerTestTags.DASHBOARD_EDITOR, AnalysisDeviceFixtures.base("ANA-007", AnalysisPresentation.CREATE).copy(selectedDashboard = null)),
            Case("ANA-007", "edit", LedgerTestTags.DASHBOARD_EDITOR, AnalysisDeviceFixtures.base("ANA-007", AnalysisPresentation.EDIT)),
            Case("ANA-007", "emptyCanvas", LedgerTestTags.DASHBOARD_EDITOR, AnalysisDeviceFixtures.base("ANA-007", AnalysisPresentation.EMPTY_CANVAS).copy(selectedDashboard = null, dashboardItems = emptyList())),
            Case("ANA-008", "editing", LedgerTestTags.REPORT_BUILDER, AnalysisDeviceFixtures.base("ANA-008", AnalysisPresentation.EDITING)),
            Case("ANA-008", "invalid", LedgerTestTags.REPORT_BUILDER, AnalysisDeviceFixtures.base("ANA-008", AnalysisPresentation.INVALID).copy(draftName = "")),
            Case("ANA-008", "previewing", LedgerTestTags.REPORT_BUILDER, AnalysisDeviceFixtures.base("ANA-008", AnalysisPresentation.PREVIEWING)),
            Case("ANA-009", "content", LedgerTestTags.VISUALIZATION_PICKER, AnalysisDeviceFixtures.base("ANA-009", AnalysisPresentation.CONTENT)),
            Case("ANA-009", "autoFallbackToBar", LedgerTestTags.VISUALIZATION_PICKER, AnalysisDeviceFixtures.base("ANA-009", AnalysisPresentation.AUTO_FALLBACK_TO_BAR)),
            Case("ANA-010", "content", LedgerTestTags.REPORT_EXPORT, AnalysisDeviceFixtures.base("ANA-010", AnalysisPresentation.CONTENT)),
            Case("ANA-011", "loading", LedgerTestTags.CONSUMPTION_MAP, AnalysisDeviceFixtures.base("ANA-011", AnalysisPresentation.LOADING)),
            Case("ANA-011", "clusters", LedgerTestTags.CONSUMPTION_MAP, AnalysisDeviceFixtures.base("ANA-011", AnalysisPresentation.CLUSTERS)),
            Case("ANA-011", "heatmap", LedgerTestTags.CONSUMPTION_MAP, AnalysisDeviceFixtures.base("ANA-011", AnalysisPresentation.HEATMAP)),
            Case("ANA-011", "singlePoints", LedgerTestTags.CONSUMPTION_MAP, AnalysisDeviceFixtures.base("ANA-011", AnalysisPresentation.SINGLE_POINTS)),
            Case("ANA-011", "noLocationData", LedgerTestTags.CONSUMPTION_MAP, AnalysisDeviceFixtures.base("ANA-011", AnalysisPresentation.NO_LOCATION_DATA).copy(consumptionMap = null)),
            Case("ANA-011", "mapUnavailable", LedgerTestTags.CONSUMPTION_MAP, AnalysisDeviceFixtures.base("ANA-011", AnalysisPresentation.MAP_UNAVAILABLE)),
            Case("ANA-012", "place", LedgerTestTags.CONSUMPTION_MAP_DETAIL, AnalysisDeviceFixtures.base("ANA-012", AnalysisPresentation.PLACE)),
            Case("ANA-012", "cluster", LedgerTestTags.CONSUMPTION_MAP_DETAIL, AnalysisDeviceFixtures.base("ANA-012", AnalysisPresentation.CLUSTER)),
            Case("ANA-012", "singleTransaction", LedgerTestTags.CONSUMPTION_MAP_DETAIL, AnalysisDeviceFixtures.base("ANA-012", AnalysisPresentation.SINGLE_TRANSACTION)),
            Case("ANA-013", "content", LedgerTestTags.ANOMALY_RULES, AnalysisDeviceFixtures.base("ANA-013", AnalysisPresentation.CONTENT)),
            Case("ANA-013", "empty", LedgerTestTags.ANOMALY_RULES, AnalysisDeviceFixtures.base("ANA-013", AnalysisPresentation.EMPTY).copy(anomalyRules = emptyList(), anomalyFindings = emptyList())),
            Case("ANA-013", "invalid", LedgerTestTags.ANOMALY_RULES, AnalysisDeviceFixtures.base("ANA-013", AnalysisPresentation.INVALID)),
            Case("ANA-014", "content", LedgerTestTags.FORECAST_DETAIL, AnalysisDeviceFixtures.base("ANA-014", AnalysisPresentation.CONTENT)),
            Case("ANA-014", "insufficientData", LedgerTestTags.FORECAST_DETAIL, AnalysisDeviceFixtures.base("ANA-014", AnalysisPresentation.INSUFFICIENT_DATA).copy(forecast = null)),
            Case("ANA-015", "notRun", LedgerTestTags.INTEGRITY_REPORT, AnalysisDeviceFixtures.base("ANA-015", AnalysisPresentation.NOT_RUN, integrity = null)),
            Case("ANA-015", "running", LedgerTestTags.INTEGRITY_REPORT, AnalysisDeviceFixtures.base("ANA-015", AnalysisPresentation.RUNNING, integrity = null)),
            Case("ANA-015", "passed", LedgerTestTags.INTEGRITY_REPORT, AnalysisDeviceFixtures.base("ANA-015", AnalysisPresentation.PASSED, integrity = AnalysisDeviceFixtures.integrity(IntegritySeverity.PASS))),
            Case("ANA-015", "warnings", LedgerTestTags.INTEGRITY_REPORT, AnalysisDeviceFixtures.base("ANA-015", AnalysisPresentation.WARNINGS, integrity = AnalysisDeviceFixtures.integrity(IntegritySeverity.WARNING))),
            Case("ANA-015", "failed", LedgerTestTags.INTEGRITY_REPORT, AnalysisDeviceFixtures.base("ANA-015", AnalysisPresentation.FAILED, integrity = AnalysisDeviceFixtures.integrity(IntegritySeverity.FAILURE))),
        )
        return raw.mapIndexed { index, case ->
            case.copy(
                width = listOf(320, 360, 480)[index % 3],
                fontScale = listOf(1f, 1.3f, 2f)[index % 3],
                locale = listOf("zh-CN", "ja-JP", "en-US")[index % 3],
                theme = if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
            )
        }
    }

    private data class Case(
        val screen: String,
        val stateName: String,
        val expectedTag: String,
        val state: AnalysisFeatureState,
        val width: Int = 360,
        val fontScale: Float = 1f,
        val locale: String = "zh-CN",
        val theme: ThemeMode = ThemeMode.LIGHT,
    )

    private companion object {
        val EXPECTED = linkedMapOf(
            "ANA-001" to setOf("content", "noData", "calculating", "error"),
            "ANA-002" to setOf("content"),
            "ANA-003" to setOf("loading", "content", "empty", "queryError", "staleRebuildRequired"),
            "ANA-004" to setOf("editing", "invalid"),
            "ANA-005" to setOf("content", "empty", "expiredQuery"),
            "ANA-006" to setOf("content", "empty"),
            "ANA-007" to setOf("create", "edit", "emptyCanvas"),
            "ANA-008" to setOf("editing", "invalid", "previewing"),
            "ANA-009" to setOf("content", "autoFallbackToBar"),
            "ANA-010" to setOf("content"),
            "ANA-011" to setOf("loading", "clusters", "heatmap", "singlePoints", "noLocationData", "mapUnavailable"),
            "ANA-012" to setOf("place", "cluster", "singleTransaction"),
            "ANA-013" to setOf("content", "empty", "invalid"),
            "ANA-014" to setOf("content", "insufficientData"),
            "ANA-015" to setOf("notRun", "running", "passed", "warnings", "failed"),
        )
    }
}
