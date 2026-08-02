package app.ledger.feature.accounts

import app.ledger.core.common.StableId
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.UserAccountType
import java.time.LocalDate

public enum class AccountsRequiredState(public val screenId: String, public val contractName: String) {
    ACC_001_CONTENT("ACC-001", "content"),
    ACC_001_NO_ACCOUNTS("ACC-001", "noAccounts"),
    ACC_001_VALUATION_STALE("ACC-001", "valuationStale"),
    ACC_001_ERROR("ACC-001", "error"),
    ACC_002_CONTENT("ACC-002", "content"),
    ACC_003_CREATE("ACC-003", "create"),
    ACC_003_EDIT("ACC-003", "edit"),
    ACC_003_CURRENCY_LOCKED("ACC-003", "currencyLocked"),
    ACC_003_VALIDATION_ERROR("ACC-003", "validationError"),
    ACC_003_SAVING("ACC-003", "saving"),
    ACC_004_EDITING("ACC-004", "editing"),
    ACC_004_SAVING("ACC-004", "saving"),
    ACC_005_ACTIVE("ACC-005", "active"),
    ACC_005_ARCHIVED("ACC-005", "archived"),
    ACC_005_EMPTY_TRANSACTIONS("ACC-005", "emptyTransactions"),
    ACC_005_VALUATION_UNAVAILABLE("ACC-005", "valuationUnavailable"),
    ACC_006_CONTENT("ACC-006", "content"),
    ACC_006_EMPTY("ACC-006", "empty"),
    ACC_006_ERROR("ACC-006", "error"),
    ACC_007_EDITING("ACC-007", "editing"),
    ACC_007_MATCH("ACC-007", "match"),
    ACC_007_DIFFERENCE("ACC-007", "difference"),
    ACC_007_SAVING("ACC-007", "saving"),
    ACC_008_CONTENT("ACC-008", "content"),
    ACC_009_CONTENT("ACC-009", "content"),
    ACC_009_EMPTY("ACC-009", "empty"),
    ACC_010_CREATE("ACC-010", "create"),
    ACC_010_EDIT("ACC-010", "edit"),
    ACC_010_VALIDATION_ERROR("ACC-010", "validationError"),
    ACC_010_SAVING("ACC-010", "saving"),
    ACC_011_ACTIVE("ACC-011", "active"),
    ACC_011_ARCHIVED("ACC-011", "archived"),
    ACC_011_REPLACEMENT("ACC-011", "replacement"),
    ACC_012_UNUSED_DELETABLE("ACC-012", "unusedDeletable"),
    ACC_012_USED_ARCHIVE_ONLY("ACC-012", "usedArchiveOnly"),
    ACC_012_LAST_ACCOUNT_WARNING("ACC-012", "lastAccountWarning"),
}

public sealed interface AccountsDataState {
    public data object Loading : AccountsDataState
    public data class Content(public val snapshot: ReferenceDataSnapshot) : AccountsDataState
    public data class Error(public val code: String) : AccountsDataState
}

public data class AccountEditorSubmission(
    val accountId: StableId?,
    val type: UserAccountType,
    val name: String,
    val currencyCode: String,
    val institutionName: String?,
    val branchName: String?,
    val accountNumber: String?,
    val openedOn: LocalDate?,
    val iconKey: String,
    val colorArgb: Int,
)

public data class CardEditorSubmission(
    val cardId: StableId?,
    val accountId: StableId,
    val type: CardType,
    val displayName: String,
    val lastFour: String?,
    val replacementOfId: StableId?,
)

public data class CheckpointSubmission(
    val accountId: StableId,
    val localDate: LocalDate,
    val observedMinor: Long,
    val note: String?,
)

public data class OpeningBalanceSubmission(
    val accountId: StableId,
    val balanceDate: LocalDate,
    val accountMinor: Long,
    val baseMinor: Long?,
)

public data class AccountsActions(
    val onNavigate: (screenId: String, arguments: Map<String, StableId>) -> Unit,
    val onSelectAccountType: (UserAccountType) -> Unit,
    val onSaveAccount: (AccountEditorSubmission) -> Unit,
    val onArchiveAccount: (StableId, Long) -> Unit,
    val onDeleteEmptyAccount: (StableId, Long) -> Unit,
    val onSaveCard: (CardEditorSubmission) -> Unit,
    val onArchiveCard: (StableId, Long) -> Unit,
    val onSaveCheckpoint: (CheckpointSubmission) -> Unit,
    val onSaveOpeningBalance: (OpeningBalanceSubmission) -> Unit,
    val onRetry: () -> Unit,
)
