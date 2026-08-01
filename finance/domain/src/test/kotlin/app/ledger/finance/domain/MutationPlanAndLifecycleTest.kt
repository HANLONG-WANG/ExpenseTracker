package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class MutationPlanAndLifecycleTest {
    @Test
    fun `valid plan aligns command commit and projection revision`() {
        val book = book()
        val command = budgetCommand()
        val plan = budgetPlan(command, book)

        FinancialMutationPlanValidator.validate(command, planningSnapshot(book), plan).success() shouldBe plan
    }

    @Test
    fun `empty write plan is rejected instead of reporting fake success`() {
        val book = book()
        val command = budgetCommand()
        val plan = budgetPlan(command, book).copy(budgetAdjustments = emptyList())

        (FinancialMutationPlanValidator.validate(command, planningSnapshot(book), plan) is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `purge eligibility is fail closed and tombstone wins by generation`() {
        val transactionId = TransactionId(stableId(70))
        val eligibility = PurgeEligibility(
            transactionId = transactionId,
            lifecycleState = TransactionLifecycleState.TRASHED,
            purgeAfter = Instant.ofEpochSecond(10),
            evaluatedAt = Instant.ofEpochSecond(11),
            accountCurrencyNetZero = true,
            baseCurrencyNetZero = true,
            effectsNetZero = true,
            dependenciesClosed = true,
            referencedByOperation = false,
            attachmentsReadByBackup = false,
        )
        eligibility.eligible.shouldBeTrue()
        eligibility.copy(referencedByOperation = true).eligible.shouldBeFalse()

        val tombstone = PurgeTombstone(
            entity = StableEntityReference(EntityType.TRANSACTION, transactionId.value),
            purgeCommitId = BookCommitId(stableId(71)),
            purgedAt = Instant.ofEpochSecond(11),
            purgeGeneration = 3L,
        )
        tombstone.supersedes(2L).shouldBeTrue()
        tombstone.supersedes(4L).shouldBeFalse()
    }

    @Test
    fun `book currency freezes after first financial commit`() {
        book().canChangeBaseCurrency().shouldBeFalse()
        book().copy(firstFinancialCommitAt = null).canChangeBaseCurrency().shouldBeTrue()
    }

    @Test
    fun `account history permits archive only while unused account permits delete`() {
        val used = userAccount(hasPostings = true)
        used.deletionPolicy() shouldBe AccountDeletionPolicy.ARCHIVE_ONLY
        used.canChangeCurrency().shouldBeFalse()
        val unused = userAccount(hasPostings = false)
        unused.deletionPolicy() shouldBe AccountDeletionPolicy.PERMANENT_DELETE_ALLOWED
        unused.canChangeCurrency().shouldBeTrue()
    }

    private fun userAccount(hasPostings: Boolean): UserAccount = UserAccount(
        id = UserAccountId(stableId(80)),
        ledgerAccountId = LedgerAccountId(stableId(81)),
        type = UserAccountType.BANK,
        name = "bank",
        currency = currency("JPY"),
        status = EntityStatus.ACTIVE,
        institutionName = null,
        branchName = null,
        accountNumber = null,
        openedOn = null,
        display = DisplayStyle(IconKey("bank"), ColorArgb(0), 0),
        lastCommitId = BookCommitId(stableId(82)),
        rowVersion = rowVersion(),
        contentHash = ContentHash(hash(82)),
        hasFinancialPostings = hasPostings,
    )
}
