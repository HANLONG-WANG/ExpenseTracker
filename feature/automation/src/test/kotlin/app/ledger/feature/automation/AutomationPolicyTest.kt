@file:Suppress("MaxLineLength")

package app.ledger.feature.automation

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.AutomationSnapshot
import app.ledger.finance.application.BlueprintView
import app.ledger.finance.application.CandidateView
import app.ledger.finance.application.OccurrenceView
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import app.ledger.finance.application.RecurrenceSeriesView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.RecurrenceCandidateStatus
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceRule
import app.ledger.finance.domain.RecurrenceStatus
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.WeekendAdjustment
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AutomationPolicyTest {
    @Test
    fun `all target screens derive their specified normal and empty states`() {
        val empty = snapshot()
        create(empty, "AUT-002").presentation shouldBe AutomationPresentation.EMPTY
        create(empty, "AUT-004").presentation shouldBe AutomationPresentation.EMPTY
        create(empty, "AUT-008").presentation shouldBe AutomationPresentation.EMPTY

        val populated = snapshot(blueprints = listOf(blueprint()), series = listOf(series()), candidates = listOf(candidate()))
        create(populated, "AUT-001").presentation shouldBe AutomationPresentation.CONTENT
        create(populated, "AUT-002").presentation shouldBe AutomationPresentation.CONTENT
        create(populated, "AUT-004").presentation shouldBe AutomationPresentation.CONTENT
        create(populated, "AUT-008").presentation shouldBe AutomationPresentation.CONTENT
    }

    @Test
    fun `editor validation refuses missing typed references and invalid dates`() {
        var template = create(snapshot(), "AUT-003")
        template = AutomationPolicy.validateBlueprint(template)
        template.presentation shouldBe AutomationPresentation.VALIDATION_ERROR
        setOf("name", "category", "account").all(template.validationFields::contains) shouldBe true

        var recurrence = create(snapshot(), "AUT-005")
        recurrence = recurrence.copy(recurrenceDraft = requireNotNull(recurrence.recurrenceDraft).copy(startDate = "not-a-date", maxOccurrences = "0"))
        recurrence = AutomationPolicy.validateRecurrence(recurrence)
        recurrence.presentation shouldBe AutomationPresentation.INVALID
        setOf("blueprint", "startDate", "maxOccurrences").all(recurrence.validationFields::contains) shouldBe true
    }

    @Test
    fun `archived source and invalid candidate are distinct auditable states`() {
        val archived = blueprint(status = EntityStatus.ARCHIVED)
        val archivedCandidate = candidate(blueprint = archived)
        val invalid = candidate(status = RecurrenceCandidateStatus.INVALID, validation = "recurrence.amount")
        create(snapshot(blueprints = listOf(archived), candidates = listOf(archivedCandidate)), "AUT-009", candidateId = archivedCandidate.id).presentation shouldBe AutomationPresentation.INVALID_SOURCE
        create(snapshot(blueprints = listOf(invalid.blueprint), candidates = listOf(invalid)), "AUT-009", candidateId = invalid.id).presentation shouldBe AutomationPresentation.VALIDATION_ERROR
    }

    private fun create(snapshot: AutomationSnapshot, screen: String, candidateId: StableId? = null): AutomationFeatureState = AutomationPolicy.create(
        snapshot,
        entrySnapshot(),
        screen,
        snapshot.blueprints.firstOrNull()?.id.takeIf { screen == "AUT-003" },
        snapshot.series.firstOrNull()?.id.takeIf { screen in setOf("AUT-005", "AUT-006", "AUT-007", "AUT-010") },
        candidateId,
        ZONE,
        LocalDate.of(2026, 8, 6),
    )

    private fun snapshot(
        blueprints: List<BlueprintView> = emptyList(),
        series: List<RecurrenceSeriesView> = emptyList(),
        candidates: List<CandidateView> = emptyList(),
        occurrences: List<OccurrenceView> = emptyList(),
    ) = AutomationSnapshot(id(1), 4, blueprints, series, candidates, occurrences)

    private fun blueprint(status: EntityStatus = EntityStatus.ACTIVE) = BlueprintView(
        id(2), id(3), 1, "Rent", "category", 0xff445566.toInt(), status, TransactionKind.EXPENSE,
        id(4), id(5), null, null, null, null, null, null, "100", CURRENCY, null, null,
    )

    private fun series() = RecurrenceSeriesView(
        id(6), id(7), 1, id(2), "Rent", RecurrenceStatus.ACTIVE,
        RecurrenceRule(RecurrenceFrequency.MONTHLY_DAY, 1, emptySet(), 1, null, null, MissingDayPolicy.MOVE_TO_MONTH_END, WeekendAdjustment.NONE),
        LocalDate.of(2026, 1, 1), null, null, LocalTime.of(9, 0), ZONE, RecurrenceGenerationMode.CANDIDATE, null, true, emptyList(),
    )

    private fun candidate(
        blueprint: BlueprintView = blueprint(),
        status: RecurrenceCandidateStatus = RecurrenceCandidateStatus.PENDING_CONFIRMATION,
        validation: String? = null,
    ) = CandidateView(id(8), id(9), id(6), blueprint, Instant.parse("2026-01-01T00:00:00Z"), LocalDate.of(2026, 1, 1), status, validation)

    private fun entrySnapshot(): OrdinaryTransactionEntrySnapshot = OrdinaryTransactionEntrySnapshot(
        ReferenceDataSnapshot(id(1), CURRENCY, 4, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, false),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        null,
    )

    private fun id(seed: Int): StableId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT) { index -> (seed + index).toByte() }).let { result ->
        when (result) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error(result.error.code)
        }
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
        val CURRENCY: CurrencyCode = (CurrencyCode.parse("JPY") as DomainResult.Success).value
    }
}
