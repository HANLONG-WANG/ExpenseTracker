@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.feature.planning.BudgetActions
import app.ledger.feature.planning.BudgetAdjustmentActions
import app.ledger.feature.planning.BudgetDestination
import app.ledger.feature.planning.BudgetEditorActions
import app.ledger.feature.planning.BudgetNavigationActions
import app.ledger.finance.domain.BudgetAdjustmentKind
import java.time.YearMonth
import app.ledger.feature.planning.R as PlanningR

@Composable
internal fun budgetDestinationTitleOrNull(screenId: String): String? = when (screenId) {
    "BUD-001" -> stringResource(PlanningR.string.budget_remaining)
    "BUD-002" -> stringResource(PlanningR.string.budget_edit)
    "BUD-003" -> stringResource(PlanningR.string.budget_category_limit)
    "BUD-004", "BUD-005" -> stringResource(PlanningR.string.budget_adjustments)
    "BUD-006" -> stringResource(PlanningR.string.budget_history)
    "BUD-007", "BUD-008" -> stringResource(PlanningR.string.budget_templates)
    else -> null
}

@Composable
internal fun BudgetRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
    onOperations: () -> Unit,
) {
    val month = encodedArguments["yearMonth"]?.toBudgetYearMonthOrNull() ?: viewModel.currentBudgetMonth()
    val templateId = encodedArguments["templateId"]?.let { StableId.parse(it).getOrNull() }
    val budgetState by viewModel.budget.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, month, templateId) { viewModel.loadBudget(month, templateId, screenId) }
    BudgetDestination(
        screenId,
        budgetState,
        encodedArguments,
        BudgetActions(
            navigation = BudgetNavigationActions(
                onRetry = { viewModel.loadBudget(month, templateId, screenId) },
                onMonth = { selected ->
                    viewModel.navigateBudget("BUD-001", selected, null, null)
                    onNavigationChanged()
                },
                onNavigate = { target, stable, kind ->
                    viewModel.navigateBudget(target, month, stable, kind)
                    onNavigationChanged()
                },
                onEdit = {
                    viewModel.navigateBudget("BUD-002", month, null, null)
                    onNavigationChanged()
                },
                onOperations = onOperations,
            ),
            editor = BudgetEditorActions(
                onTotalChanged = viewModel::updateBudgetTotal,
                onCategoryChanged = viewModel::updateBudgetCategory,
                onSaveMonth = viewModel::saveBudgetMonth,
                onTemplateNameChanged = viewModel::updateBudgetTemplateName,
                onSaveTemplate = viewModel::saveBudgetTemplate,
            ),
            adjustment = BudgetAdjustmentActions(
                onAmountChanged = viewModel::updateBudgetAdjustmentAmount,
                onSource = viewModel::selectBudgetAdjustmentSource,
                onTarget = viewModel::selectBudgetAdjustmentTarget,
                onSave = viewModel::saveBudgetAdjustment,
            ),
        ),
    )
}

private fun String.toBudgetYearMonthOrNull(): YearMonth? = runCatching {
    require(length == BUDGET_MONTH_ROUTE_LENGTH && all(Char::isDigit))
    YearMonth.of(
        substring(0, BUDGET_MONTH_YEAR_END).toInt(),
        substring(BUDGET_MONTH_YEAR_END, BUDGET_MONTH_ROUTE_LENGTH).toInt(),
    )
}.getOrNull()

private const val BUDGET_MONTH_ROUTE_LENGTH = 6
private const val BUDGET_MONTH_YEAR_END = 4
