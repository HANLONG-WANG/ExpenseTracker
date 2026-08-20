@file:Suppress("MaxLineLength")

package app.ledger.feature.record

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.RefundNamedReference
import app.ledger.finance.application.RefundSnapshot
import app.ledger.finance.application.RefundableTransactionView
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionLifecycleState
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

internal object RefundDeviceFixtures {
    val originalId: StableId = id(101)
    val originalRevisionId: StableId = id(102)
    val jpy: CurrencyCode = (CurrencyCode.parse("JPY") as DomainResult.Success).value
    val now: Instant = Instant.parse("2026-08-04T03:00:00Z")

    fun snapshot(empty: Boolean = false): RefundSnapshot {
        val ordinary = OrdinaryRecordDeviceFixtures.snapshot()
        val original = RefundableTransactionView(
            originalId,
            originalRevisionId,
            TransactionLifecycleState.ACTIVE,
            1_000,
            jpy,
            1_000,
            jpy,
            400,
            600,
            0,
            Instant.parse("2026-07-08T03:00:00Z"),
            LocalDate.of(2026, 7, 8),
            OrdinaryRecordDeviceFixtures.bank,
            OrdinaryRecordDeviceFixtures.card,
            OrdinaryRecordDeviceFixtures.expenseChild,
            "Lunch",
            StatisticalNature.CONSUMPTION_EXPENSE,
            OrdinaryRecordDeviceFixtures.merchant,
            "Cafe",
            OrdinaryRecordDeviceFixtures.project,
            "Trip",
            id(103),
            "Camera",
            null,
            emptyList(),
            id(104),
        )
        return RefundSnapshot(
            ordinary.references,
            if (empty) emptyList() else listOf(original),
            listOf(RefundNamedReference(OrdinaryRecordDeviceFixtures.project, "Trip")),
            listOf(RefundNamedReference(id(103), "Camera")),
        )
    }

    fun linked(locale: Locale = Locale.ENGLISH): RefundEditorState {
        var state = RefundPolicy.create(snapshot(), originalId, now, OrdinaryRecordDeviceFixtures.zone, locale)
        state = RefundPolicy.updateExpression(state, "100", locale)
        return state
    }

    fun independent(locale: Locale = Locale.ENGLISH): RefundEditorState {
        var state = RefundPolicy.setIndependent(linked(locale), true, locale)
        state = RefundPolicy.updateExpression(state, "250", locale)
        return state
    }

    fun excess(locale: Locale = Locale.ENGLISH): RefundEditorState = RefundPolicy.updateExpression(linked(locale), "700", locale)

    val actions: (RefundScreenAction) -> Unit = {}
    val pickerActions: (RefundPickerScreenAction) -> Unit = {}

    private fun id(value: Long) = StableId.fromUuid(UUID(0x1617L, value))
}
