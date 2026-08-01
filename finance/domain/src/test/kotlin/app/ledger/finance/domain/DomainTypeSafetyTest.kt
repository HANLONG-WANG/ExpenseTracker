package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.money.Money
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class DomainTypeSafetyTest {
    @Test
    fun `account amount admits only currency checked positive money`() {
        val jpy = currency("JPY")
        val usd = currency("USD")
        val account = AccountSnapshot(
            id = UserAccountId(stableId(20)),
            ledgerAccountId = LedgerAccountId(stableId(21)),
            type = UserAccountType.BANK,
            currency = jpy,
            status = EntityStatus.ACTIVE,
            rowVersion = rowVersion(),
            hasFinancialPostings = true,
        )

        (AccountAmount.create(account, Money(100L, usd)) is DomainResult.Failure).shouldBeTrue()
        (AccountAmount.create(account, Money(0L, jpy)) is DomainResult.Failure).shouldBeTrue()
        AccountAmount.create(account, Money(100L, jpy)).success().accountId shouldBe account.id
        AccountAmount::class.java.declaredConstructors.any { constructor ->
            Modifier.isPrivate(constructor.modifiers)
        }.shouldBeTrue()
        AccountAmount::class.java.declaredMethods
            .filter { method -> method.name.startsWith("copy") && !method.name.endsWith("default") }
            .all { method -> Modifier.isPrivate(method.modifiers) }
            .shouldBeTrue()
    }

    @Test
    fun `ordinary transaction types require exactly one classification and one payer`() {
        val constructor = ExpensePayload::class.java.declaredConstructors.first { candidate ->
            candidate.parameterTypes.lastOrNull()?.name != "kotlin.jvm.internal.DefaultConstructorMarker"
        }
        constructor.parameterTypes.count { it == CategoryAssignment::class.java } shouldBe 1
        constructor.parameterTypes.count { it == ExpensePayer::class.java } shouldBe 1
        constructor.parameterTypes.none { it == Set::class.java }.shouldBeTrue()
        constructor.parameterTypes.count { it == List::class.java } shouldBe 1

        IncomePayload::class.java.declaredConstructors.first { candidate ->
            candidate.parameterTypes.lastOrNull()?.name != "kotlin.jvm.internal.DefaultConstructorMarker"
        }.parameterTypes
            .count { it == CategoryAssignment::class.java } shouldBe 1
        TransactionContextInput::class.java.declaredFields.count { it.name == "projectId" } shouldBe 1
        TransactionContextInput::class.java.declaredFields.count { it.name == "goalId" } shouldBe 1
    }

    @Test
    fun `formal transaction kinds have closed payload implementations`() {
        val payloadTypes = listOf(
            ExpensePayload::class,
            IncomePayload::class,
            TransferPayload::class,
            RefundPayload::class,
            CreditPaymentPayload::class,
            LoanDisbursementPayload::class,
            LoanPaymentPayload::class,
            BalanceAdjustmentPayload::class,
            FxExchangePayload::class,
            SettlementPaymentPayload::class,
            OpeningBalancePayload::class,
        )

        payloadTypes.size shouldBe TransactionKind.entries.size
        TransactionPayload::class.java.isSealed.shouldBeTrue()
        TransactionPayload::class.java.permittedSubclasses.toSet() shouldBe payloadTypes.map { it.java }.toSet()
    }

    @Test
    fun `candidate is not a command or immutable financial fact`() {
        FinancialCommand::class.java.isAssignableFrom(RecurrenceCandidate::class.java).shouldBeFalse()
        RecurrenceCandidate::class.java.interfaces.contains(FinancialCommand::class.java).shouldBeFalse()
    }
}
