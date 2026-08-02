package app.ledger.finance.application

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.UserAccountType
import java.time.Instant
import java.time.ZoneId

public data class LedgerGenesisIds(
    val bookId: StableId,
    val commitId: StableId,
    val bookRevisionId: StableId,
    val deviceInstanceId: StableId,
    val systemLedgerIds: Map<SystemLedgerCode, StableId>,
) {
    init {
        require(systemLedgerIds.keys == SystemLedgerCode.entries.toSet())
        require(systemLedgerIds.values.toSet().size == systemLedgerIds.size)
    }
}

public data class InitializeLedgerCommand(
    val ids: LedgerGenesisIds,
    val baseCurrency: CurrencyCode,
    val defaultZoneId: ZoneId,
    val createdAt: Instant,
)

public data class InitialAccountCommand(
    val accountId: StableId,
    val ledgerAccountId: StableId,
    val commitId: StableId,
    val revisionId: StableId,
    val deviceInstanceId: StableId,
    val createdAt: Instant,
    val type: UserAccountType,
    val name: String,
    val currency: CurrencyCode,
    val iconKey: String,
    val colorArgb: Int,
) {
    init {
        require(type == UserAccountType.CASH || type == UserAccountType.BANK)
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
        require(iconKey.matches(ICON_KEY))
    }

    private companion object {
        const val MAX_NAME_LENGTH = 80
        val ICON_KEY = Regex("[a-z][a-z0-9_]{1,31}")
    }
}

public data class InitialCategoryCommand(
    val categoryId: StableId,
    val commitId: StableId,
    val revisionId: StableId,
    val deviceInstanceId: StableId,
    val createdAt: Instant,
    val direction: CategoryDirection,
    val name: String,
    val normalizedName: String,
    val statisticalNature: StatisticalNature,
    val iconKey: String,
    val colorArgb: Int,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
        require(normalizedName.isNotBlank())
        require(iconKey.matches(ICON_KEY))
        require(
            direction == CategoryDirection.EXPENSE && statisticalNature in EXPENSE_NATURES ||
                direction == CategoryDirection.INCOME && statisticalNature in INCOME_NATURES,
        )
    }

    private companion object {
        const val MAX_NAME_LENGTH = 80
        val ICON_KEY = Regex("[a-z][a-z0-9_]{1,31}")
        val EXPENSE_NATURES = setOf(
            StatisticalNature.CONSUMPTION_EXPENSE,
            StatisticalNature.NON_CONSUMPTION_EXPENSE,
        )
        val INCOME_NATURES = setOf(
            StatisticalNature.REGULAR_INCOME,
            StatisticalNature.NON_RECURRING_INCOME,
        )
    }
}

public data class EmptyLedgerState(
    val hasUserAccount: Boolean,
    val hasCategory: Boolean,
    val hasTransaction: Boolean,
)

public data class UpdateBookLocaleCommand(
    val baseCurrency: CurrencyCode,
    val defaultZoneId: ZoneId,
    val commitId: StableId,
    val revisionId: StableId,
    val deviceInstanceId: StableId,
    val changedAt: Instant,
)

public sealed interface LedgerInitializationError : DomainError {
    public data object AlreadyInitializedWithDifferentBook : LedgerInitializationError {
        override val code: String = "LEDGER_ALREADY_INITIALIZED"
    }

    public data object BookNotInitialized : LedgerInitializationError {
        override val code: String = "LEDGER_NOT_INITIALIZED"
    }

    public data object BaseCurrencyLocked : LedgerInitializationError {
        override val code: String = "BASE_CURRENCY_LOCKED"
    }

    public data object DuplicateReference : LedgerInitializationError {
        override val code: String = "DUPLICATE_INITIAL_REFERENCE"
    }

    public data object InvalidReference : LedgerInitializationError {
        override val code: String = "INVALID_INITIAL_REFERENCE"
    }

    public data object ClearLocalBookFailed : LedgerInitializationError {
        override val code: String = "CLEAR_LOCAL_BOOK_FAILED"
    }
}

/** Application-owned non-financial reference-data bootstrap boundary. */
public interface LedgerInitializationPort {
    public suspend fun initialize(command: InitializeLedgerCommand): DomainResult<Unit>

    public suspend fun updateBookLocale(
        bookId: StableId,
        command: UpdateBookLocaleCommand,
    ): DomainResult<Unit>

    public suspend fun createFirstAccount(bookId: StableId, command: InitialAccountCommand): DomainResult<Unit>

    public suspend fun createFirstCategory(bookId: StableId, command: InitialCategoryCommand): DomainResult<Unit>

    public suspend fun emptyLedgerState(bookId: StableId): DomainResult<EmptyLedgerState>

    /** Irreversibly removes the local encrypted database and its device-only key hierarchy. */
    public suspend fun clearLocalBook(bookId: StableId): DomainResult<Unit>
}
