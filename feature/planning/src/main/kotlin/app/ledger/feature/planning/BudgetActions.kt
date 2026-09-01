package app.ledger.feature.planning

import app.ledger.core.common.StableId
import app.ledger.finance.domain.BudgetAdjustmentKind
import java.time.YearMonth

public sealed interface BudgetScreenAction {
    public data object Retry : BudgetScreenAction
    public data class MonthSelected(val month: YearMonth) : BudgetScreenAction
    public data class Navigate(val screenId: String, val id: StableId?, val adjustmentKind: BudgetAdjustmentKind?) : BudgetScreenAction
    public data object Edit : BudgetScreenAction
    public data object Operations : BudgetScreenAction
    public data class TotalChanged(val value: String) : BudgetScreenAction
    public data class CategoryChanged(val categoryId: StableId, val value: String) : BudgetScreenAction
    public data object SaveMonth : BudgetScreenAction
    public data class ApplyTemplate(val templateId: StableId) : BudgetScreenAction
    public data class TemplateNameChanged(val value: String) : BudgetScreenAction
    public data object SaveTemplate : BudgetScreenAction
    public data class AdjustmentAmountChanged(val value: String) : BudgetScreenAction
    public data class AdjustmentSource(val categoryId: StableId) : BudgetScreenAction
    public data class AdjustmentTarget(val categoryId: StableId) : BudgetScreenAction
    public data class SaveAdjustment(val kind: BudgetAdjustmentKind) : BudgetScreenAction
}

internal class BudgetActions(
    val navigation: BudgetNavigationActions,
    val editor: BudgetEditorActions,
    val adjustment: BudgetAdjustmentActions,
) {
    val onRetry: () -> Unit get() = navigation.onRetry
    val onMonth: (YearMonth) -> Unit get() = navigation.onMonth
    val onNavigate: (String, StableId?, BudgetAdjustmentKind?) -> Unit get() = navigation.onNavigate
    val onEdit: () -> Unit get() = navigation.onEdit
    val onOperations: () -> Unit get() = navigation.onOperations
    val onTotalChanged: (String) -> Unit get() = editor.onTotalChanged
    val onCategoryChanged: (StableId, String) -> Unit get() = editor.onCategoryChanged
    val onSaveMonth: () -> Unit get() = editor.onSaveMonth
    val onApplyTemplate: (StableId) -> Unit get() = editor.onApplyTemplate
    val onTemplateNameChanged: (String) -> Unit get() = editor.onTemplateNameChanged
    val onSaveTemplate: () -> Unit get() = editor.onSaveTemplate
    val onAdjustmentAmountChanged: (String) -> Unit get() = adjustment.onAmountChanged
    val onAdjustmentSource: (StableId) -> Unit get() = adjustment.onSource
    val onAdjustmentTarget: (StableId) -> Unit get() = adjustment.onTarget
    val onSaveAdjustment: (BudgetAdjustmentKind) -> Unit get() = adjustment.onSave
}

internal class BudgetNavigationActions(
    val onRetry: () -> Unit,
    val onMonth: (YearMonth) -> Unit,
    val onNavigate: (String, StableId?, BudgetAdjustmentKind?) -> Unit,
    val onEdit: () -> Unit,
    val onOperations: () -> Unit,
)

internal class BudgetEditorActions(
    val onTotalChanged: (String) -> Unit,
    val onCategoryChanged: (StableId, String) -> Unit,
    val onSaveMonth: () -> Unit,
    val onApplyTemplate: (StableId) -> Unit,
    val onTemplateNameChanged: (String) -> Unit,
    val onSaveTemplate: () -> Unit,
)

internal class BudgetAdjustmentActions(
    val onAmountChanged: (String) -> Unit,
    val onSource: (StableId) -> Unit,
    val onTarget: (StableId) -> Unit,
    val onSave: (BudgetAdjustmentKind) -> Unit,
)

internal fun budgetActions(onAction: (BudgetScreenAction) -> Unit): BudgetActions = BudgetActions(
    navigation = BudgetNavigationActions(
        onRetry = { onAction(BudgetScreenAction.Retry) },
        onMonth = { onAction(BudgetScreenAction.MonthSelected(it)) },
        onNavigate = { screenId, id, kind -> onAction(BudgetScreenAction.Navigate(screenId, id, kind)) },
        onEdit = { onAction(BudgetScreenAction.Edit) },
        onOperations = { onAction(BudgetScreenAction.Operations) },
    ),
    editor = BudgetEditorActions(
        onTotalChanged = { onAction(BudgetScreenAction.TotalChanged(it)) },
        onCategoryChanged = { id, value -> onAction(BudgetScreenAction.CategoryChanged(id, value)) },
        onSaveMonth = { onAction(BudgetScreenAction.SaveMonth) },
        onApplyTemplate = { onAction(BudgetScreenAction.ApplyTemplate(it)) },
        onTemplateNameChanged = { onAction(BudgetScreenAction.TemplateNameChanged(it)) },
        onSaveTemplate = { onAction(BudgetScreenAction.SaveTemplate) },
    ),
    adjustment = BudgetAdjustmentActions(
        onAmountChanged = { onAction(BudgetScreenAction.AdjustmentAmountChanged(it)) },
        onSource = { onAction(BudgetScreenAction.AdjustmentSource(it)) },
        onTarget = { onAction(BudgetScreenAction.AdjustmentTarget(it)) },
        onSave = { onAction(BudgetScreenAction.SaveAdjustment(it)) },
    ),
)
