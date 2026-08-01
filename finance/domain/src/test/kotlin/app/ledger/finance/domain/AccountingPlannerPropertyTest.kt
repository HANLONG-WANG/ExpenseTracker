package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

class AccountingPlannerPropertyTest {
    @Test
    fun `one thousand generated expenses always produce balanced journals without floating point`() = runTest {
        checkAll(iterations = 1_000, Arb.long(1L, 1_000_000_000_000L)) { minor ->
            val command = PlannerFixtures.expenseCommand(minor, commandSeed = 100_000L)
            val snapshot = PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(
                        AmountRole.PRIMARY,
                        minor,
                        PlannerFixtures.bankJpyId,
                    ),
                ),
                seed = 100_100L,
            )
            val plan = DeterministicFinancialPlanner.plan(command, snapshot).success()
            plan.journalBundles.forEach { journal ->
                journal.entry.baseDebitTotalMinor shouldBe journal.entry.baseCreditTotalMinor
            }
            plan.revisionAmounts.filter { it.role == AmountRole.PRIMARY }.map { it.componentIndex }.toSet() shouldBe
                setOf(0)
        }
    }

    @Test
    fun `one thousand generated transfers never change net user financial assets`() = runTest {
        val references = PlannerFixtures.references()
        checkAll(iterations = 1_000, Arb.long(1L, 1_000_000_000_000L)) { minor ->
            val payload = TransferPayload(
                PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, minor, references),
                PlannerFixtures.accountAmount(PlannerFixtures.bankJpyTwoId, minor, references),
                null,
            )
            val unsigned = RecordTransferCommand(
                CommandId(stableId(110_000L)),
                hash(0),
                NewTransactionInput(PlannerFixtures.inputContext(), payload),
            )
            val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
            val plan = DeterministicFinancialPlanner.plan(
                command,
                PlannerFixtures.snapshot(
                    listOf(
                        PlannerFixtures.sameCurrencyEvidence(
                            AmountRole.OUTGOING,
                            minor,
                            PlannerFixtures.bankJpyId,
                        ),
                        PlannerFixtures.sameCurrencyEvidence(
                            AmountRole.INCOMING,
                            minor,
                            PlannerFixtures.bankJpyTwoId,
                        ),
                    ),
                    seed = 110_100L,
                ),
            ).success()
            val userLedgerIds = references.accounts.map { it.ledger.id }.toSet()
            val userPostings = plan.journalBundles.flatMap { it.postings }.filter {
                it.ledgerAccountId in userLedgerIds
            }
            val signed = userPostings.map { posting ->
                if (posting.side == DebitCredit.DEBIT) {
                    posting.baseAmount.minor.value
                } else {
                    Math.negateExact(posting.baseAmount.minor.value)
                }
            }
            app.ledger.core.common.CheckedArithmetic.sum(signed).success() shouldBe 0L
            plan.economicEffects.isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `generated trash reversals exactly net every posting to zero`() = runTest {
        checkAll(iterations = 500, Arb.long(1L, 10_000_000_000L)) { minor ->
            val amount = PlannerFixtures.sameCurrencyEvidence(
                AmountRole.PRIMARY,
                minor,
                PlannerFixtures.bankJpyId,
            )
            val createSnapshot = PlannerFixtures.snapshot(listOf(amount), seed = 120_000L)
            val create = DeterministicFinancialPlanner.plan(
                PlannerFixtures.expenseCommand(minor, commandSeed = 120_500L),
                createSnapshot,
            ).success()
            val unsignedTrash = MoveTransactionToTrashCommand(
                CommandId(stableId(121_000L)),
                create.revisions.single().id,
                hash(0),
                create.transactions.single().id,
                Instant.ofEpochSecond(200_000L),
                emptyList(),
            )
            val trash = unsignedTrash.copy(payloadHash = CanonicalFinancialHash.command(unsignedTrash))
            val currentFacts = PlannerFixtures.currentFacts(create)
            val trashPlan = DeterministicFinancialPlanner.plan(
                trash,
                PlannerFixtures.snapshot(
                    listOf(amount),
                    seed = 121_100L,
                    currentTransaction = create.transactions.single(),
                    currentRevision = create.revisions.single(),
                    currentFacts = currentFacts,
                    sourceBook = PlannerFixtures.nextBook(create, createSnapshot.book),
                ),
            ).success()
            val net = FinancialFactNetting.postings(currentFacts.journalBundles + trashPlan.journalBundles).success()
            net.all { it.accountDebitMinusCreditMinor == 0L && it.baseDebitMinusCreditMinor == 0L }
                .shouldBeTrue()
        }
    }

    @Test
    fun `long maximum is accepted per line while overflowing totals are rejected`() {
        val command = PlannerFixtures.expenseCommand(Long.MAX_VALUE, commandSeed = 130_000L)
        val plan = DeterministicFinancialPlanner.plan(
            command,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(
                        AmountRole.PRIMARY,
                        Long.MAX_VALUE,
                        PlannerFixtures.bankJpyId,
                    ),
                ),
                seed = 130_100L,
            ),
        ).success()
        plan.journalBundles.single().entry.baseDebitTotalMinor shouldBe Long.MAX_VALUE

        val entryId = JournalEntryId(stableId(131_000L))
        val references = PlannerFixtures.references()
        val asset = references.account(PlannerFixtures.bankJpyId)!!.ledger
        val postings = listOf(
            posting(entryId, PostingId(stableId(131_001L)), 1, DebitCredit.DEBIT, asset, Long.MAX_VALUE),
            posting(entryId, PostingId(stableId(131_002L)), 2, DebitCredit.DEBIT, asset, Long.MAX_VALUE),
            posting(entryId, PostingId(stableId(131_003L)), 3, DebitCredit.CREDIT, asset, Long.MAX_VALUE),
        )
        val result = JournalEntry.create(
            entryId,
            TransactionRevisionId(stableId(131_010L)),
            TransactionRevisionId(stableId(131_010L)),
            JournalEntryRole.APPLY,
            null,
            effective(),
            PlannerFixtures.jpy,
            postings,
            ruleSetVersion(),
            BookCommitId(stableId(131_011L)),
            ContentHash(hash(13)),
        )
        result shouldBe DomainResult.Failure(DomainViolation.NumericOverflow("journalEntry.baseTotal"))
    }

    @Suppress("LongParameterList")
    private fun posting(
        entryId: JournalEntryId,
        postingId: PostingId,
        line: Int,
        side: DebitCredit,
        ledger: LedgerAccountSnapshot,
        minor: Long,
    ): Posting = Posting.create(
        postingId,
        entryId,
        line,
        ledger,
        side,
        positive(minor, PlannerFixtures.jpy),
        positive(minor, PlannerFixtures.jpy),
        PlannerFixtures.jpy,
        null,
        PostingRole.ASSET,
        null,
    ).success()
}
