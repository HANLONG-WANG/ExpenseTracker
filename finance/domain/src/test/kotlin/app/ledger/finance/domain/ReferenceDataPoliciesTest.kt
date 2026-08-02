package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.money.Money
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReferenceDataPoliciesTest {
    @Test
    fun `used and last accounts remain archivable but are never directly deletable`() {
        val usedLast = ReferenceDataPolicies.accountLifecycle(AccountUsage(1, 0, 1))
        usedLast.canPermanentlyDelete.shouldBeFalse()
        usedLast.canArchive.shouldBeTrue()
        usedLast.requiresLastAccountWarning.shouldBeTrue()

        val empty = ReferenceDataPolicies.accountLifecycle(AccountUsage(0, 0, 4))
        empty.canPermanentlyDelete.shouldBeTrue()
        empty.requiresLastAccountWarning.shouldBeFalse()

        ReferenceDataPolicies.accountLifecycle(AccountUsage(0, 1, 4)).canPermanentlyDelete.shouldBeFalse()
    }

    @Test
    fun `first posting locks currency while harmless same-currency edit remains valid`() {
        val jpy = currency("JPY")
        val used = account(jpy, hasPostings = true)
        ReferenceDataPolicies.validateCurrencyChange(used, jpy) shouldBe DomainResult.Success(Unit)
        ReferenceDataPolicies.validateCurrencyChange(used, currency("USD")) shouldBe
            DomainResult.Failure(ReferenceDataViolation.CurrencyLocked)
        ReferenceDataPolicies.validateCurrencyChange(account(jpy, false), currency("USD")) shouldBe DomainResult.Success(Unit)
    }

    @Test
    fun `physical card compatibility is closed over bank and credit account types`() {
        ReferenceDataPolicies.validateCard(CardType.DEBIT, UserAccountType.BANK) shouldBe DomainResult.Success(Unit)
        ReferenceDataPolicies.validateCard(CardType.CREDIT_PRIMARY, UserAccountType.CREDIT) shouldBe DomainResult.Success(Unit)
        ReferenceDataPolicies.validateCard(CardType.CREDIT_SUPPLEMENTARY, UserAccountType.CREDIT) shouldBe DomainResult.Success(Unit)
        UserAccountType.entries.filterNot { it == UserAccountType.BANK }.forEach {
            ReferenceDataPolicies.validateCard(CardType.DEBIT, it) shouldBe DomainResult.Failure(ReferenceDataViolation.CardAccountIncompatible)
        }
        UserAccountType.entries.filterNot { it == UserAccountType.CREDIT }.forEach {
            ReferenceDataPolicies.validateCard(CardType.CREDIT_PRIMARY, it) shouldBe DomainResult.Failure(ReferenceDataViolation.CardAccountIncompatible)
        }
    }

    @Test
    fun `second level category parent is immutable and directions never mix`() {
        val expenseParent = category(CategoryDirection.EXPENSE, depth = 1, seed = 20)
        ReferenceDataPolicies.validateCategoryParent(CategoryDirection.EXPENSE, expenseParent, expenseParent.id, true) shouldBe DomainResult.Success(Unit)
        ReferenceDataPolicies.validateCategoryParent(CategoryDirection.INCOME, expenseParent, expenseParent.id, true) shouldBe
            DomainResult.Failure(ReferenceDataViolation.CategoryDirectionMismatch)
        ReferenceDataPolicies.validateCategoryParent(CategoryDirection.EXPENSE, null, expenseParent.id, true) shouldBe
            DomainResult.Failure(ReferenceDataViolation.CategoryParentLocked)
    }

    @Test
    fun `checkpoint difference is checked and does not mutate any balance`() {
        val jpy = currency("JPY")
        ReferenceDataPolicies.checkpointDifference(Money(120, jpy), Money(100, jpy)).success().minor shouldBe 20L
        (ReferenceDataPolicies.checkpointDifference(Money(Long.MAX_VALUE, jpy), Money(-1, jpy)) is DomainResult.Failure).shouldBeTrue()
        (ReferenceDataPolicies.checkpointDifference(Money(1, jpy), Money(1, currency("USD"))) is DomainResult.Failure).shouldBeTrue()
    }

    private fun account(currency: app.ledger.core.money.CurrencyCode, hasPostings: Boolean) = UserAccount(
        id = UserAccountId(stableId(1)),
        ledgerAccountId = LedgerAccountId(stableId(2)),
        type = UserAccountType.BANK,
        name = "account",
        currency = currency,
        status = EntityStatus.ACTIVE,
        institutionName = null,
        branchName = null,
        accountNumber = null,
        openedOn = null,
        display = DisplayStyle(IconKey("account-balance"), ColorArgb(0xff006c4c.toInt()), 0),
        lastCommitId = BookCommitId(stableId(3)),
        rowVersion = RowVersion.of(1).success(),
        contentHash = ContentHash(hash(4)),
        hasFinancialPostings = hasPostings,
    )

    private fun category(direction: CategoryDirection, depth: Int, seed: Long): Category {
        val id = CategoryId(stableId(seed))
        return Category(
            id = id,
            direction = direction,
            parentId = if (depth == 1) null else CategoryId(stableId(seed + 1)),
            depth = depth,
            name = "category",
            normalizedName = "category",
            display = DisplayStyle(IconKey("category"), ColorArgb(0xff006c4c.toInt()), 0),
            status = CategoryStatus.ACTIVE,
            statisticalNature = if (direction == CategoryDirection.EXPENSE) StatisticalNature.CONSUMPTION_EXPENSE else StatisticalNature.REGULAR_INCOME,
            defaultAccountId = null,
            defaultCardId = null,
            defaultMerchantId = null,
            lastCommitId = BookCommitId(stableId(seed + 2)),
            rowVersion = RowVersion.of(1).success(),
        )
    }
}
