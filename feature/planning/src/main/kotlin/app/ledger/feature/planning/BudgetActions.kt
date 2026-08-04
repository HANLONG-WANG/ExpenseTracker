package app.ledger.feature.planning

import app.ledger.core.common.StableId
import app.ledger.finance.domain.BudgetAdjustmentKind
import java.time.YearMonth

public data class BudgetActions(
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
    val onTemplateNameChanged: (String) -> Unit get() = editor.onTemplateNameChanged
    val onSaveTemplate: () -> Unit get() = editor.onSaveTemplate
    val onAdjustmentAmountChanged: (String) -> Unit get() = adjustment.onAmountChanged
    val onAdjustmentSource: () -> Unit get() = adjustment.onSource
    val onAdjustmentTarget: () -> Unit get() = adjustment.onTarget
    val onSaveAdjustment: (BudgetAdjustmentKind) -> Unit get() = adjustment.onSave
}

public data class BudgetNavigationActions(
    val onRetry: () -> Unit,
    val onMonth: (YearMonth) -> Unit,
    val onNavigate: (String, StableId?, BudgetAdjustmentKind?) -> Unit,
    val onEdit: () -> Unit,
    val onOperations: () -> Unit,
)

public data class BudgetEditorActions(
    val onTotalChanged: (String) -> Unit,
    val onCategoryChanged: (StableId, String) -> Unit,
    val onSaveMonth: () -> Unit,
    val onTemplateNameChanged: (String) -> Unit,
    val onSaveTemplate: () -> Unit,
)

public data class BudgetAdjustmentActions(
    val onAmountChanged: (String) -> Unit,
    val onSource: () -> Unit,
    val onTarget: () -> Unit,
    val onSave: (BudgetAdjustmentKind) -> Unit,
)
