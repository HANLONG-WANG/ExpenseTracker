package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.domain.InstallmentFeeRateType
import app.ledger.finance.domain.InstallmentRefundPolicy

public data class InstallmentActions(
    val onRetry: () -> Unit,
    val onNavigate: (String, StableId?) -> Unit,
    val onFieldChanged: (InstallmentField, String) -> Unit,
    val onFeeModelChanged: (InstallmentFeeRateType) -> Unit,
    val onRefundPolicyChanged: (InstallmentRefundPolicy) -> Unit,
    val onSelectPurchase: (StableId) -> Unit,
    val onPreview: () -> Unit,
    val onCalculateSettlement: () -> Unit,
    val onApplySettlement: () -> Unit,
)
