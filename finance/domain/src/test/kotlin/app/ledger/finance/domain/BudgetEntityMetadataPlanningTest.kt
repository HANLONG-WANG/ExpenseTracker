package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth

class BudgetEntityMetadataPlanningTest {
    @Test
    fun `budget month and template plans emit current entity changes`() {
        val commitId = BookCommitId(stableId(40_001))
        val operation = PlanningOperationContext(commitId, Instant.ofEpochSecond(1), DeviceInstanceId(stableId(40_002)))
        val snapshot = planningSnapshot(book()).copy(operationContext = operation)

        val monthRevisionId = BudgetMonthRevisionId(stableId(40_003))
        val monthMutation = BudgetMonthMutation(
            BudgetMonth(BudgetMonthId(stableId(40_004)), YearMonth.of(2026, 8), monthRevisionId),
            BudgetMonthRevision(
                monthRevisionId,
                BudgetMonthId(stableId(40_004)),
                1,
                100_000L,
                emptyList(),
                null,
                commitId,
            ),
            null,
        )
        val monthDraft = ConfigureBudgetMonthCommand(CommandId(stableId(40_005)), hash(0), monthMutation)
        val monthCommand = monthDraft.copy(payloadHash = CanonicalFinancialHash.command(monthDraft))
        val monthPlan = DeterministicFinancialPlanner.plan(monthCommand, snapshot).success()
        monthPlan.entityChanges.single().asserts(
            EntityType.BUDGET,
            monthMutation.month.id.value,
            monthCommand.payloadHash,
            monthMutation.revision.id.value,
        )

        val templateRevisionId = BudgetTemplateRevisionId(stableId(40_006))
        val templateMutation = BudgetTemplateMutation(
            BudgetTemplate(
                BudgetTemplateId(stableId(40_007)),
                "Essentials",
                templateRevisionId,
                EntityStatus.ACTIVE,
            ),
            BudgetTemplateRevision(
                templateRevisionId,
                BudgetTemplateId(stableId(40_007)),
                1,
                80_000L,
                emptyList(),
                commitId,
            ),
            null,
        )
        val templateDraft = SaveBudgetTemplateCommand(CommandId(stableId(40_008)), hash(0), templateMutation)
        val templateCommand = templateDraft.copy(payloadHash = CanonicalFinancialHash.command(templateDraft))
        val templatePlan = DeterministicFinancialPlanner.plan(templateCommand, snapshot).success()
        templatePlan.entityChanges.single().asserts(
            EntityType.BUDGET_TEMPLATE,
            templateMutation.template.id.value,
            templateCommand.payloadHash,
            templateMutation.revision.id.value,
        )
    }

    private fun EntityChange.asserts(
        type: EntityType,
        uid: app.ledger.core.common.StableId,
        hash: Hash256,
        revisionId: app.ledger.core.common.StableId,
    ) {
        entity shouldBe StableEntityReference(type, uid)
        operation shouldBe EntityChangeOperation.CREATE
        beforeHash shouldBe null
        afterHash shouldBe ContentHash(hash)
        entityRevisionId shouldBe EntityRevisionId(revisionId)
    }
}
