package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.domain.InstallmentFeeRateType
import app.ledger.finance.domain.InstallmentRefundPolicy
import java.math.RoundingMode

public sealed interface InstallmentScreenAction {
    public data object Retry : InstallmentScreenAction
    public data class Navigate(val screenId: String, val id: StableId?) : InstallmentScreenAction
    public data class FieldChanged(val field: InstallmentField, val value: String) : InstallmentScreenAction
    public data class FeeModelChanged(val type: InstallmentFeeRateType) : InstallmentScreenAction
    public data class RefundPolicyChanged(val policy: InstallmentRefundPolicy) : InstallmentScreenAction
    public data class SelectPurchase(val transactionId: StableId) : InstallmentScreenAction
    public data object Preview : InstallmentScreenAction
    public data object CalculateSettlement : InstallmentScreenAction
    public data object ApplySettlement : InstallmentScreenAction
}

internal class InstallmentActions(
    val onRetry: () -> Unit,
    val onNavigate: (String, StableId?) -> Unit,
    val onFieldChanged: (InstallmentField, String) -> Unit,
    val onFeeModelChanged: (InstallmentFeeRateType) -> Unit,
    val onRefundPolicyChanged: (InstallmentRefundPolicy) -> Unit,
    val onRoundingModeChanged: (RoundingMode) -> Unit,
    val onSelectPurchase: (StableId) -> Unit,
    val onPreview: () -> Unit,
    val onCalculateSettlement: () -> Unit,
    val onApplySettlement: () -> Unit,
)

internal fun installmentActions(onAction: (InstallmentScreenAction) -> Unit): InstallmentActions = InstallmentActions(
    onRetry = { onAction(InstallmentScreenAction.Retry) },
    onNavigate = { screenId, id -> onAction(InstallmentScreenAction.Navigate(screenId, id)) },
    onFieldChanged = { field, value -> onAction(InstallmentScreenAction.FieldChanged(field, value)) },
    onFeeModelChanged = { onAction(InstallmentScreenAction.FeeModelChanged(it)) },
    onRefundPolicyChanged = { onAction(InstallmentScreenAction.RefundPolicyChanged(it)) },
    onSelectPurchase = { onAction(InstallmentScreenAction.SelectPurchase(it)) },
    onPreview = { onAction(InstallmentScreenAction.Preview) },
    onCalculateSettlement = { onAction(InstallmentScreenAction.CalculateSettlement) },
    onApplySettlement = { onAction(InstallmentScreenAction.ApplySettlement) },
)
