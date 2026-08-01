package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.Money
import app.ledger.core.time.EffectiveTime
import java.time.Instant
import java.time.LocalDate

enum class LedgerOwnerType {
    USER_ACCOUNT,
    LOAN_TRANCHE,
    SYSTEM,
    SETTLEMENT_POSITION,
}

enum class LedgerAccountClass {
    ASSET,
    LIABILITY,
    INCOME,
    EXPENSE,
    EQUITY,
    SETTLEMENT,
    CLEARING,
}

enum class DebitCredit {
    DEBIT,
    CREDIT,
}

enum class SystemLedgerCode {
    SYSTEM_INCOME_REGULAR,
    SYSTEM_INCOME_NON_RECURRING,
    SYSTEM_EXPENSE_CONSUMPTION,
    SYSTEM_EXPENSE_NON_CONSUMPTION,
    SYSTEM_OPENING_EQUITY,
    SYSTEM_BALANCE_ADJUSTMENT,
    SYSTEM_FX_CLEARING,
    SYSTEM_FX_ROUNDING,
    SYSTEM_FX_COST,
    SYSTEM_FX_GAIN,
}

data class LedgerAccount(
    val id: LedgerAccountId,
    val ownerType: LedgerOwnerType,
    val accountClass: LedgerAccountClass,
    val normalSide: DebitCredit,
    val currency: CurrencyCode,
    val parentId: LedgerAccountId?,
    val systemCode: SystemLedgerCode?,
    val status: EntityStatus,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

enum class UserAccountType {
    CASH,
    BANK,
    CREDIT,
    LOAN,
}

@JvmInline value class AccountNumber(val value: String)

data class UserAccount(
    val id: UserAccountId,
    val ledgerAccountId: LedgerAccountId,
    val type: UserAccountType,
    val name: String,
    val currency: CurrencyCode,
    val status: EntityStatus,
    val institutionName: String?,
    val branchName: String?,
    val accountNumber: AccountNumber?,
    val openedOn: LocalDate?,
    val display: DisplayStyle,
    val lastCommitId: BookCommitId,
    val rowVersion: RowVersion,
    val contentHash: ContentHash,
    val hasFinancialPostings: Boolean,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    fun canChangeCurrency(): Boolean = !hasFinancialPostings

    fun deletionPolicy(): AccountDeletionPolicy = if (hasFinancialPostings) {
        AccountDeletionPolicy.ARCHIVE_ONLY
    } else {
        AccountDeletionPolicy.PERMANENT_DELETE_ALLOWED
    }
}

enum class AccountDeletionPolicy {
    ARCHIVE_ONLY,
    PERMANENT_DELETE_ALLOWED,
}

data class AccountSnapshot(
    val id: UserAccountId,
    val ledgerAccountId: LedgerAccountId,
    val type: UserAccountType,
    val currency: CurrencyCode,
    val status: EntityStatus,
    val rowVersion: RowVersion,
    val hasFinancialPostings: Boolean,
)

enum class CardType {
    DEBIT,
    CREDIT_PRIMARY,
    CREDIT_SUPPLEMENTARY,
}

@ConsistentCopyVisibility
data class PaymentCard private constructor(
    val id: PaymentCardId,
    val accountId: UserAccountId,
    val type: CardType,
    val displayName: String,
    val lastFour: String?,
    val status: EntityStatus,
    val replacementOfId: PaymentCardId?,
    val display: DisplayStyle,
    val lastCommitId: BookCommitId,
    val rowVersion: RowVersion,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    companion object {
        @Suppress("LongParameterList")
        fun create(
            id: PaymentCardId,
            account: AccountSnapshot,
            type: CardType,
            displayName: String,
            lastFour: String?,
            status: EntityStatus,
            replacementOfId: PaymentCardId?,
            display: DisplayStyle,
            lastCommitId: BookCommitId,
            rowVersion: RowVersion,
        ): DomainResult<PaymentCard> {
            val compatible = when (type) {
                CardType.DEBIT -> account.type == UserAccountType.BANK
                CardType.CREDIT_PRIMARY,
                CardType.CREDIT_SUPPLEMENTARY,
                -> account.type == UserAccountType.CREDIT
            }
            if (!compatible || (lastFour != null && !LAST_FOUR.matches(lastFour))) {
                return DomainResult.Failure(DomainViolation.InvalidField("paymentCard.accountOrLastFour"))
            }
            return DomainResult.Success(
                PaymentCard(
                    id = id,
                    accountId = account.id,
                    type = type,
                    displayName = displayName,
                    lastFour = lastFour,
                    status = status,
                    replacementOfId = replacementOfId,
                    display = display,
                    lastCommitId = lastCommitId,
                    rowVersion = rowVersion,
                ),
            )
        }

        private val LAST_FOUR = Regex("[0-9]{4}")
    }
}

class EncryptedField private constructor(bytes: ByteArray) {
    private val stored = bytes.copyOf()

    val bytes: ByteArray
        get() = stored.copyOf()

    override fun equals(other: Any?): Boolean = other is EncryptedField && stored.contentEquals(other.stored)

    override fun hashCode(): Int = stored.contentHashCode()

    companion object {
        fun of(bytes: ByteArray): DomainResult<EncryptedField> = if (bytes.isNotEmpty()) {
            DomainResult.Success(EncryptedField(bytes))
        } else {
            DomainResult.Failure(DomainViolation.InvalidField("encryptedField"))
        }
    }
}

data class CardVaultSecret(
    val cardId: PaymentCardId,
    val holderNameCiphertext: EncryptedField?,
    val panCiphertext: EncryptedField?,
    val expiryCiphertext: EncryptedField?,
    val securityCodeCiphertext: EncryptedField?,
    val customFieldsCiphertext: EncryptedField?,
    val keyVersion: Int,
    val updatedAt: Instant,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(keyVersion > 0)
    }
}

data class AccountBalanceCheckpoint(
    val id: app.ledger.core.common.StableId,
    val accountId: UserAccountId,
    val asOf: EffectiveTime,
    val observedAmount: Money,
    val calculatedAmount: Money,
    val differenceAmount: Money,
    val createdCommitId: BookCommitId,
    val adjustmentTransactionId: TransactionId?,
    val note: String?,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

enum class CategoryDirection {
    EXPENSE,
    INCOME,
}

enum class StatisticalNature {
    CONSUMPTION_EXPENSE,
    NON_CONSUMPTION_EXPENSE,
    REGULAR_INCOME,
    NON_RECURRING_INCOME,
}

enum class CategoryStatus {
    ACTIVE,
    ARCHIVED,
    DELETED_TOMBSTONE,
}

data class Category(
    val id: CategoryId,
    val direction: CategoryDirection,
    val parentId: CategoryId?,
    val depth: Int,
    val name: String,
    val normalizedName: String,
    val display: DisplayStyle,
    val status: CategoryStatus,
    val statisticalNature: StatisticalNature,
    val defaultAccountId: UserAccountId?,
    val defaultCardId: PaymentCardId?,
    val defaultMerchantId: MerchantId?,
    val lastCommitId: BookCommitId,
    val rowVersion: RowVersion,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require((depth == 1 && parentId == null) || (depth == 2 && parentId != null))
        require(
            (direction == CategoryDirection.EXPENSE && statisticalNature in EXPENSE_NATURES) ||
                (direction == CategoryDirection.INCOME && statisticalNature in INCOME_NATURES),
        )
    }

    private companion object {
        val EXPENSE_NATURES = setOf(StatisticalNature.CONSUMPTION_EXPENSE, StatisticalNature.NON_CONSUMPTION_EXPENSE)
        val INCOME_NATURES = setOf(StatisticalNature.REGULAR_INCOME, StatisticalNature.NON_RECURRING_INCOME)
    }
}

data class CategoryAssignment(
    val categoryId: CategoryId,
    val direction: CategoryDirection,
    val statisticalNatureSnapshot: StatisticalNature,
)

data class Merchant(
    val id: MerchantId,
    val name: String,
    val normalizedName: String,
    val aliases: Set<String>,
    val status: EntityStatus,
    val mergedIntoId: MerchantId?,
    val lastCommitId: BookCommitId,
    val rowVersion: RowVersion,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

data class Place(
    val id: PlaceId,
    val name: String,
    val center: GeoPoint,
    val merchantId: MerchantId?,
    val status: EntityStatus,
    val mergedIntoId: PlaceId?,
    val lastCommitId: BookCommitId,
    val rowVersion: RowVersion,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

enum class LocationSource {
    DEVICE,
    MANUAL_PIN,
    FIXED_PLACE,
    IMPORT,
}

enum class LocationProvider {
    GPS,
    NETWORK,
    PASSIVE,
    USER,
    IMPORT,
}

data class LocationRecord(
    val id: LocationRecordId,
    val point: GeoPoint,
    val accuracyMillimeters: Int?,
    val capturedAt: Instant?,
    val source: LocationSource,
    val provider: LocationProvider?,
    val placeId: PlaceId?,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(accuracyMillimeters == null || accuracyMillimeters >= 0)
    }
}
