@file:Suppress("MagicNumber", "MaxLineLength")

package app.ledger.feature.settlement

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.SettlementAccountOption
import app.ledger.finance.application.SettlementActivityView
import app.ledger.finance.application.SettlementParticipantView
import app.ledger.finance.application.SettlementPaymentView
import app.ledger.finance.application.SettlementPositionView
import app.ledger.finance.application.SettlementProjectOption
import app.ledger.finance.application.SettlementSnapshot
import app.ledger.finance.application.SettlementTransactionView
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.SettlementActivityStatus
import app.ledger.finance.domain.SettlementTransferSuggestion
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal object SettlementDeviceFixtures {
    val bookId = id(1)
    val activityId = id(2)
    val selfId = id(3)
    val friendId = id(4)
    val colleagueId = id(5)
    val accountId = id(6)
    private val jpy = currency("JPY")

    val actions = SettlementActions(
        onRetry = {},
        onNavigate = { _, _, _ -> },
        onFieldChanged = { _, _ -> },
        onSelectActivity = {},
        onSelectPayer = {},
        onSelectPayee = {},
        onSelectAccount = {},
        onSelectProject = {},
        onSplitMethod = {},
        onChargeDistribution = {},
        onRoundingRule = {},
        onToggleParticipant = {},
        onMoveParticipant = { _, _ -> },
        onAddParticipant = {},
        onSave = {},
        onRebuild = {},
    )

    fun state(screen: String, presentation: SettlementPresentation): SettlementFeatureState {
        val empty = presentation == SettlementPresentation.EMPTY
        val status = when (presentation) {
            SettlementPresentation.SETTLED -> SettlementActivityStatus.SETTLED
            SettlementPresentation.REQUIRES_ADDITIONAL_SETTLEMENT, SettlementPresentation.REQUIRED -> SettlementActivityStatus.REQUIRES_ADDITIONAL_SETTLEMENT
            else -> SettlementActivityStatus.ACTIVE
        }
        val selfNet = when (presentation) {
            SettlementPresentation.PAYABLE -> -4_000L
            SettlementPresentation.ZERO, SettlementPresentation.SETTLED, SettlementPresentation.RESOLVED -> 0L
            else -> 4_000L
        }
        val includePayments = screen == "SET-007" && !empty
        val snapshot = snapshot(
            activities = if (empty) emptyList() else listOf(activity(status, selfNet, includePayments)),
        )
        val selected = if (empty || (screen == "SET-002" && presentation == SettlementPresentation.CREATE)) null else activityId
        val created = SettlementPolicy.create(snapshot, screen, selected).copy(presentation = presentation)
        val payer = when (presentation) {
            SettlementPresentation.SELF_RECEIVES -> friendId
            SettlementPresentation.EXTERNAL_TO_EXTERNAL -> colleagueId
            else -> selfId
        }
        val payee = when (presentation) {
            SettlementPresentation.SELF_RECEIVES -> selfId
            SettlementPresentation.EXTERNAL_TO_EXTERNAL -> friendId
            else -> friendId
        }
        return created.copy(
            draft = created.draft.copy(
                name = if (presentation == SettlementPresentation.CREATE) "" else "Summer trip",
                startDate = "2026-08-01",
                total = "2000",
                note = "Partial settlement",
                payerParticipantId = payer,
                payeeParticipantId = payee,
                accountId = if (presentation == SettlementPresentation.EXTERNAL_TO_EXTERNAL) null else accountId,
            ),
            validationFields = if (presentation == SettlementPresentation.VALIDATION_ERROR) setOf("name", "participants") else emptySet(),
        )
    }

    fun snapshot(activities: List<SettlementActivityView> = listOf(activity())) = SettlementSnapshot(
        bookId,
        jpy,
        localRevision(12),
        participants(),
        activities,
        listOf(SettlementAccountOption(accountId, "Wallet", jpy, true)),
        listOf(SettlementProjectOption(id(7), "Summer project", true)),
    )

    private fun activity(
        status: SettlementActivityStatus = SettlementActivityStatus.ACTIVE,
        selfNet: Long = 4_000L,
        includePayments: Boolean = true,
    ): SettlementActivityView {
        val friendNet = Math.negateExact(selfNet)
        val people = participants()
        return SettlementActivityView(
            activityId,
            "Summer trip",
            "Shared local expenses",
            jpy,
            null,
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 31),
            status,
            status == SettlementActivityStatus.REQUIRES_ADDITIONAL_SETTLEMENT,
            id(20),
            people,
            listOf(
                SettlementPositionView(selfId, 20_000L, 10_000L, 0L, 6_000L, selfNet),
                SettlementPositionView(friendId, 0L, 6_000L, 6_000L, 0L, friendNet),
                SettlementPositionView(colleagueId, 0L, 4_000L, 4_000L, 0L, 0L),
            ),
            if (includePayments) {
                listOf(
                    SettlementPaymentView(id(30), friendId, selfId, 6_000L, Instant.parse("2026-08-04T03:00:00Z"), id(31), null),
                    SettlementPaymentView(id(32), colleagueId, friendId, 4_000L, Instant.parse("2026-08-03T03:00:00Z"), null, null),
                )
            } else {
                emptyList()
            },
            listOf(SettlementTransactionView(id(40), id(41), Instant.parse("2026-08-02T03:00:00Z"), 20_000L, 10_000L, selfId, mapOf(selfId to 10_000L, friendId to 6_000L, colleagueId to 4_000L))),
            if (selfNet == 0L) {
                emptyList()
            } else {
                listOf(
                    if (selfNet > 0L) {
                        SettlementTransferSuggestion(ParticipantId(friendId), ParticipantId(selfId), selfNet)
                    } else {
                        SettlementTransferSuggestion(ParticipantId(selfId), ParticipantId(friendId), Math.negateExact(selfNet))
                    },
                )
            },
        )
    }

    private fun participants() = listOf(
        SettlementParticipantView(selfId, "Me", true, true),
        SettlementParticipantView(friendId, "Friend", false, true),
        SettlementParticipantView(colleagueId, "Colleague", false, true),
    )

    private fun currency(code: String): CurrencyCode = (CurrencyCode.parse(code) as DomainResult.Success).value
    private fun localRevision(value: Long): LocalRevision = (LocalRevision.of(value) as DomainResult.Success).value
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x2200L, value))
}
