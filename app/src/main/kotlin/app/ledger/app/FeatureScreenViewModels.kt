package app.ledger.app

import app.ledger.core.common.StableId
import app.ledger.feature.automation.AutomationLoadState
import app.ledger.feature.journal.JournalLoadState
import app.ledger.feature.liabilities.CreditLoadState
import app.ledger.feature.liabilities.InstallmentLoadState
import app.ledger.feature.liabilities.LoanLoadState
import app.ledger.feature.planning.BudgetLoadState
import app.ledger.feature.planning.ProjectGoalLoadState
import app.ledger.feature.record.OrdinaryRecordLoadState
import app.ledger.feature.record.OrdinaryRecordScreenUiState
import app.ledger.feature.record.RefundLoadState
import app.ledger.feature.record.RefundPickerState
import app.ledger.feature.record.SpecializedTransactionLoadState
import app.ledger.feature.settings.CurrencySettingsState
import app.ledger.feature.settings.SecurityPrivacySettingsState
import app.ledger.feature.settlement.SettlementLoadState
import app.ledger.finance.application.LoanSimulationRequest
import app.ledger.finance.domain.LoanPrepaymentSimulation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Small lifecycle-scoped state owner used by one screen flow, never as an application-wide store. */
internal abstract class ScreenFlowViewModel<S>(initial: S) {
    internal val mutableState = MutableStateFlow(initial)
    val state: StateFlow<S> = mutableState.asStateFlow()
}

internal abstract class SubmittingScreenFlowViewModel<S, U>(
    initial: S,
    scope: CoroutineScope,
    initialUiState: U,
    toUiState: (S, Boolean) -> U,
) : ScreenFlowViewModel<S>(initial) {
    internal val mutablePending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = mutablePending.asStateFlow()
    val uiState: StateFlow<U> = combine(state, pending, toUiState).stateIn(
        scope,
        SharingStarted.Eagerly,
        initialUiState,
    )
}

internal data class ReferenceDataScreenUiState(val loadState: AppReferenceDataState, val submitting: Boolean)
internal class ReferenceDataScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<AppReferenceDataState, ReferenceDataScreenUiState>(
        AppReferenceDataState.Loading,
        scope,
        ReferenceDataScreenUiState(AppReferenceDataState.Loading, false),
        ::ReferenceDataScreenUiState,
    )

internal class SecurityPrivacyScreenViewModel(initial: SecurityPrivacySettingsState) : ScreenFlowViewModel<SecurityPrivacySettingsState>(initial)

internal class CurrencySettingsScreenViewModel : ScreenFlowViewModel<CurrencySettingsState?>(null)

internal class OperationCenterScreenViewModel : ScreenFlowViewModel<OperationCenterLoadState>(OperationCenterLoadState.Loading)

internal class OrdinaryRecordScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<OrdinaryRecordLoadState, OrdinaryRecordScreenUiState>(
        OrdinaryRecordLoadState.Loading,
        scope,
        OrdinaryRecordScreenUiState(OrdinaryRecordLoadState.Loading, false),
        ::OrdinaryRecordScreenUiState,
    )

internal data class RefundScreenUiState(
    val loadState: RefundLoadState,
    val pickerState: RefundPickerState,
    val submitting: Boolean,
)
internal class RefundScreenViewModel(scope: CoroutineScope) : ScreenFlowViewModel<RefundLoadState>(RefundLoadState.Loading) {
    internal val mutablePicker = MutableStateFlow<RefundPickerState>(RefundPickerState.Loading)
    internal val mutablePending = MutableStateFlow(false)
    val picker: StateFlow<RefundPickerState> = mutablePicker.asStateFlow()
    val pending: StateFlow<Boolean> = mutablePending.asStateFlow()
    val uiState: StateFlow<RefundScreenUiState> = combine(state, picker, pending, ::RefundScreenUiState).stateIn(
        scope,
        SharingStarted.Eagerly,
        RefundScreenUiState(RefundLoadState.Loading, RefundPickerState.Loading, false),
    )
}

internal data class SpecializedTransactionScreenUiState(val loadState: SpecializedTransactionLoadState, val submitting: Boolean)
internal class SpecializedTransactionScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<SpecializedTransactionLoadState, SpecializedTransactionScreenUiState>(
        SpecializedTransactionLoadState.Loading,
        scope,
        SpecializedTransactionScreenUiState(SpecializedTransactionLoadState.Loading, false),
        ::SpecializedTransactionScreenUiState,
    )

internal data class JournalScreenUiState(val loadState: JournalLoadState)
internal class JournalScreenViewModel(scope: CoroutineScope) : ScreenFlowViewModel<JournalLoadState>(JournalLoadState.Loading) {
    internal val pagingRequest = MutableStateFlow<JournalPagingRequest?>(null)
    val uiState: StateFlow<JournalScreenUiState> = state.map(::JournalScreenUiState)
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            JournalScreenUiState(JournalLoadState.Loading),
        )
}

internal data class BudgetScreenUiState(val loadState: BudgetLoadState, val submitting: Boolean)
internal class BudgetScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<BudgetLoadState, BudgetScreenUiState>(
        BudgetLoadState.Loading,
        scope,
        BudgetScreenUiState(BudgetLoadState.Loading, false),
        ::BudgetScreenUiState,
    )

internal data class ProjectGoalScreenUiState(val loadState: ProjectGoalLoadState, val submitting: Boolean)
internal class ProjectGoalScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<ProjectGoalLoadState, ProjectGoalScreenUiState>(
        ProjectGoalLoadState.Loading,
        scope,
        ProjectGoalScreenUiState(ProjectGoalLoadState.Loading, false),
        ::ProjectGoalScreenUiState,
    ) {
    internal val pagingRequest = MutableStateFlow<ProjectTransactionPagingRequest?>(null)
}

internal data class CreditScreenUiState(val loadState: CreditLoadState, val submitting: Boolean)
internal class CreditScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<CreditLoadState, CreditScreenUiState>(
        CreditLoadState.Loading,
        scope,
        CreditScreenUiState(CreditLoadState.Loading, false),
        ::CreditScreenUiState,
    ) {
    var currentScreenId: String = "CRD-001"
    var currentTransactionId: StableId? = null
}

internal data class InstallmentScreenUiState(val loadState: InstallmentLoadState, val submitting: Boolean)
internal class InstallmentScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<InstallmentLoadState, InstallmentScreenUiState>(
        InstallmentLoadState.Loading,
        scope,
        InstallmentScreenUiState(InstallmentLoadState.Loading, false),
        ::InstallmentScreenUiState,
    ) {
    var currentScreenId: String = "INS-001"
}

internal data class LoanScreenUiState(val loadState: LoanLoadState, val submitting: Boolean)
internal class LoanScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<LoanLoadState, LoanScreenUiState>(
        LoanLoadState.Loading,
        scope,
        LoanScreenUiState(LoanLoadState.Loading, false),
        ::LoanScreenUiState,
    ) {
    var currentScreenId: String = "LIA-001"
    var currentSimulationRequest: LoanSimulationRequest? = null
    var currentSimulation: LoanPrepaymentSimulation? = null
}

internal data class SettlementScreenUiState(val loadState: SettlementLoadState, val submitting: Boolean)
internal class SettlementScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<SettlementLoadState, SettlementScreenUiState>(
        SettlementLoadState.Loading,
        scope,
        SettlementScreenUiState(SettlementLoadState.Loading, false),
        ::SettlementScreenUiState,
    ) {
    var currentScreenId: String = "SET-001"
}

internal data class AutomationScreenUiState(val loadState: AutomationLoadState, val submitting: Boolean)
internal class AutomationScreenViewModel(scope: CoroutineScope) :
    SubmittingScreenFlowViewModel<AutomationLoadState, AutomationScreenUiState>(
        AutomationLoadState.Loading,
        scope,
        AutomationScreenUiState(AutomationLoadState.Loading, false),
        ::AutomationScreenUiState,
    ) {
    var currentScreenId: String = "AUT-001"
    var pendingCandidateId: StableId? = null
}
