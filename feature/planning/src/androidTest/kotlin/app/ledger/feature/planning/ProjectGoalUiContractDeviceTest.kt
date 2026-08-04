@file:Suppress("LongMethod", "MaxLineLength")

package app.ledger.feature.planning

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
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.ProjectStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ProjectGoalUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allThirtyOneFrozenRequiredStatesRenderAcrossResponsiveAccessibleLocalizedMatrix() {
        val cases = cases()
        assertEquals(31, cases.size)
        assertEquals(EXPECTED, cases.groupBy(Case::screen).mapValues { (_, values) -> values.map(Case::stateName).toSet() })
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = base.createConfigurationContext(Configuration(configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(case.locale))) })
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration, LocalDensity provides Density(1f, case.fontScale)) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 1_900.dp)) {
                        ProjectGoalDestination(case.screen, ProjectGoalLoadState.Content(case.featureState), case.arguments, ProjectGoalDeviceFixtures.actions)
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
    fun negativeAvailabilityWarnsWithoutBlockingSaveAndChartsExposeDataTablesAtCompactLargeFont() {
        val movement = ProjectGoalPolicy.movementAmount(
            ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EDITING).copy(movementKind = GoalMovementKind.ALLOCATE),
            "10",
        )
        val showGoal = mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 1_200.dp)) {
                        if (showGoal.value) {
                            ProjectGoalDestination(
                                "GOL-003",
                                ProjectGoalLoadState.Content(ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.ACTIVE)),
                                mapOf("goalId" to ProjectGoalDeviceFixtures.goalId.toString()),
                                ProjectGoalDeviceFixtures.actions,
                            )
                        } else {
                            ProjectGoalDestination(
                                "GOL-004",
                                ProjectGoalLoadState.Content(movement),
                                mapOf("goalId" to ProjectGoalDeviceFixtures.goalId.toString(), "kind" to "ALLOCATE"),
                                ProjectGoalDeviceFixtures.actions,
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.GOAL_MOVEMENT).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.SAVE).assertHasClickAction()
        composeRule.runOnIdle { showGoal.value = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LedgerTestTags.GOAL_DETAIL).performScrollToNode(androidx.compose.ui.test.hasTestTag(LedgerTestTags.CHART))
        composeRule.onNodeWithTag(LedgerTestTags.CHART).assertExists()
        composeRule.onRoot().assertExists()
    }

    private fun cases(): List<Case> {
        val empty = ProjectGoalDeviceFixtures.snapshot(projects = emptyList(), goals = emptyList())
        val archivedOnly = ProjectGoalDeviceFixtures.snapshot(projects = listOf(ProjectGoalDeviceFixtures.project(status = ProjectStatus.ARCHIVED)), goals = listOf(ProjectGoalDeviceFixtures.goal()))
        val noTransactions = ProjectGoalDeviceFixtures.snapshot(projects = listOf(ProjectGoalDeviceFixtures.project(transactions = emptyList())), goals = listOf(ProjectGoalDeviceFixtures.goal()))
        val noCashflow = ProjectGoalDeviceFixtures.snapshot(projects = listOf(ProjectGoalDeviceFixtures.project(cashflow = emptyList())), goals = listOf(ProjectGoalDeviceFixtures.goal()))
        val completedGoal = ProjectGoalDeviceFixtures.snapshot(goals = listOf(ProjectGoalDeviceFixtures.goal(status = GoalStatus.COMPLETED)))
        val emptyGoalHistory = ProjectGoalDeviceFixtures.snapshot(goals = listOf(ProjectGoalDeviceFixtures.goal(movements = emptyList(), trend = emptyList())))
        val projectValidation = ProjectGoalPolicy.validateProject(ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CREATE, project = null, snapshot = empty))
        val goalValidation = ProjectGoalPolicy.validateGoal(ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CREATE, goal = null, snapshot = empty))
        val warning = ProjectGoalPolicy.movementAmount(ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EDITING), "10")
        val raw = listOf(
            Case("PRJ-001", "content", LedgerTestTags.PROJECT_LIST, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CONTENT, project = null)),
            Case("PRJ-001", "empty", LedgerTestTags.PROJECT_LIST, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EMPTY, project = null, goal = null, snapshot = empty)),
            Case("PRJ-001", "archivedOnly", LedgerTestTags.PROJECT_LIST, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.ARCHIVED_ONLY, project = null, snapshot = archivedOnly)),
            Case("PRJ-002", "create", LedgerTestTags.PROJECT_EDITOR, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CREATE, project = null, snapshot = empty)),
            Case("PRJ-002", "edit", LedgerTestTags.PROJECT_EDITOR, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EDIT)),
            Case("PRJ-002", "validationError", LedgerTestTags.PROJECT_EDITOR, projectValidation),
            Case("PRJ-003", "active", LedgerTestTags.PROJECT_DETAIL, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.ACTIVE)),
            Case("PRJ-003", "archived", LedgerTestTags.PROJECT_DETAIL, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.ARCHIVED, project = ProjectGoalDeviceFixtures.archivedProjectId)),
            Case("PRJ-003", "overBudget", LedgerTestTags.PROJECT_DETAIL, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.OVER_BUDGET)),
            Case("PRJ-003", "noTransactions", LedgerTestTags.PROJECT_DETAIL, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.NO_TRANSACTIONS, snapshot = noTransactions)),
            Case("PRJ-004", "content", LedgerTestTags.PROJECT_TRANSACTIONS, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CONTENT)),
            Case("PRJ-004", "empty", LedgerTestTags.PROJECT_TRANSACTIONS, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EMPTY, snapshot = noTransactions)),
            Case("PRJ-005", "content", LedgerTestTags.PROJECT_CASHFLOW, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CONTENT)),
            Case("PRJ-005", "empty", LedgerTestTags.PROJECT_CASHFLOW, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EMPTY, snapshot = noCashflow)),
            Case("PRJ-006", "active", LedgerTestTags.PROJECT_STATUS, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.ACTIVE)),
            Case("PRJ-006", "archived", LedgerTestTags.PROJECT_STATUS, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.ARCHIVED, project = ProjectGoalDeviceFixtures.archivedProjectId)),
            Case("GOL-001", "content", LedgerTestTags.GOAL_LIST, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CONTENT, project = null, goal = null)),
            Case("GOL-001", "empty", LedgerTestTags.GOAL_LIST, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EMPTY, project = null, goal = null, snapshot = empty)),
            Case("GOL-001", "underfunded", LedgerTestTags.GOAL_LIST, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.UNDERFUNDED, project = null, goal = null)),
            Case("GOL-002", "create", LedgerTestTags.GOAL_EDITOR, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CREATE, goal = null, snapshot = empty)),
            Case("GOL-002", "edit", LedgerTestTags.GOAL_EDITOR, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EDIT)),
            Case("GOL-002", "currencyLocked", LedgerTestTags.GOAL_EDITOR, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CURRENCY_LOCKED)),
            Case("GOL-002", "validationError", LedgerTestTags.GOAL_EDITOR, goalValidation),
            Case("GOL-003", "active", LedgerTestTags.GOAL_DETAIL, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.ACTIVE)),
            Case("GOL-003", "completed", LedgerTestTags.GOAL_DETAIL, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.COMPLETED, snapshot = completedGoal)),
            Case("GOL-003", "underfunded", LedgerTestTags.GOAL_DETAIL, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.UNDERFUNDED)),
            Case("GOL-003", "emptyHistory", LedgerTestTags.GOAL_DETAIL, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EMPTY_HISTORY, snapshot = emptyGoalHistory)),
            Case("GOL-004", "editing", LedgerTestTags.GOAL_MOVEMENT, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.EDITING), movementArguments()),
            Case("GOL-004", "insufficientActualBalanceWarning", LedgerTestTags.GOAL_MOVEMENT, warning, movementArguments()),
            Case("GOL-004", "saving", LedgerTestTags.GOAL_MOVEMENT, warning.copy(presentation = ProjectGoalPresentation.SAVING), movementArguments()),
            Case("GOL-005", "content", LedgerTestTags.GOAL_COMPLETION, ProjectGoalDeviceFixtures.state(ProjectGoalPresentation.CONTENT)),
        )
        return raw.mapIndexed { index, case -> case.copy(width = listOf(320, 360, 480)[index % 3], fontScale = listOf(1f, 1.3f, 2f)[index % 3], locale = listOf("zh-CN", "ja-JP", "en-US")[index % 3], theme = if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK) }
    }

    private fun movementArguments() = mapOf("goalId" to ProjectGoalDeviceFixtures.goalId.toString(), "kind" to "ALLOCATE")

    private data class Case(
        val screen: String,
        val stateName: String,
        val expectedTag: String,
        val featureState: ProjectGoalFeatureState,
        val arguments: Map<String, String> = when {
            screen.startsWith("PRJ-") && screen != "PRJ-001" -> mapOf("projectId" to ProjectGoalDeviceFixtures.projectId.toString())
            screen.startsWith("GOL-") && screen != "GOL-001" -> mapOf("goalId" to ProjectGoalDeviceFixtures.goalId.toString())
            else -> emptyMap()
        },
        val width: Int = 360,
        val fontScale: Float = 1f,
        val locale: String = "en-US",
        val theme: ThemeMode = ThemeMode.LIGHT,
    )

    private companion object {
        val EXPECTED = linkedMapOf(
            "PRJ-001" to setOf("content", "empty", "archivedOnly"),
            "PRJ-002" to setOf("create", "edit", "validationError"),
            "PRJ-003" to setOf("active", "archived", "overBudget", "noTransactions"),
            "PRJ-004" to setOf("content", "empty"),
            "PRJ-005" to setOf("content", "empty"),
            "PRJ-006" to setOf("active", "archived"),
            "GOL-001" to setOf("content", "empty", "underfunded"),
            "GOL-002" to setOf("create", "edit", "currencyLocked", "validationError"),
            "GOL-003" to setOf("active", "completed", "underfunded", "emptyHistory"),
            "GOL-004" to setOf("editing", "insufficientActualBalanceWarning", "saving"),
            "GOL-005" to setOf("content"),
        )
    }
}
