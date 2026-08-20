@file:Suppress("MaxLineLength")

package app.ledger.feature.record

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.CardReferenceView
import app.ledger.finance.application.CategoryReferenceView
import app.ledger.finance.application.LocationReferenceView
import app.ledger.finance.application.MerchantReferenceView
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryParticipantView
import app.ledger.finance.application.OrdinaryProjectView
import app.ledger.finance.application.OrdinarySettlementActivityView
import app.ledger.finance.application.OrdinaryTemplateView
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import app.ledger.finance.application.PlaceReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.UserAccountType
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

internal object OrdinaryRecordDeviceFixtures {
    val book = id(1)
    val cash = id(2)
    val bank = id(3)
    val card = id(4)
    val expenseRoot = id(5)
    val expenseChild = id(6)
    val incomeRoot = id(7)
    val merchant = id(8)
    val place = id(9)
    val location = id(10)
    val project = id(11)
    val activity = id(12)
    val self = id(13)
    val friend = id(14)
    val template = id(15)
    val now: Instant = Instant.parse("2026-08-03T03:30:00Z")
    val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    fun snapshot(): OrdinaryTransactionEntrySnapshot {
        val jpy = currency("JPY")
        val accounts = listOf(
            AccountReferenceView(cash, UserAccountType.CASH, "Cash", jpy, EntityStatus.ACTIVE, null, null, null, "account", 0xff006c4c.toInt(), 0, 1, 100_000, 100_000, now, false, 0),
            AccountReferenceView(bank, UserAccountType.BANK, "Bank", jpy, EntityStatus.ACTIVE, "Local bank", null, null, "account", 0xff4f6357.toInt(), 1, 1, 200_000, 200_000, now, false, 1),
        )
        val cards = listOf(CardReferenceView(card, bank, CardType.DEBIT, "Debit", "1234", EntityStatus.ACTIVE, null, "card", 0xff006c4c.toInt(), 0, 1, 0))
        val categories = listOf(
            CategoryReferenceView(expenseRoot, CategoryDirection.EXPENSE, null, 1, "Food", "record", 0xff006c4c.toInt(), 0, CategoryStatus.ACTIVE, StatisticalNature.CONSUMPTION_EXPENSE, bank, card, merchant, 1, 2, 1),
            CategoryReferenceView(expenseChild, CategoryDirection.EXPENSE, expenseRoot, 2, "Lunch", "record", 0xff8d4f00.toInt(), 0, CategoryStatus.ACTIVE, StatisticalNature.CONSUMPTION_EXPENSE, null, null, null, 1, 1, 0),
            CategoryReferenceView(incomeRoot, CategoryDirection.INCOME, null, 1, "Salary", "record", 0xff006874.toInt(), 0, CategoryStatus.ACTIVE, StatisticalNature.REGULAR_INCOME, cash, null, null, 1, 1, 0),
        )
        val merchants = listOf(MerchantReferenceView(merchant, "Cafe", listOf("Coffee"), EntityStatus.ACTIVE, null, 1, 3, 1))
        val places = listOf(PlaceReferenceView(place, "Station", 356_000_000, 1_397_000_000, merchant, EntityStatus.ACTIVE, null, 1, 1))
        val locations = listOf(LocationReferenceView(location, 356_000_000, 1_397_000_000, now, place, 1))
        val references = ReferenceDataSnapshot(book, jpy, 3, accounts, cards, categories, merchants, places, locations, emptyList(), emptyList(), emptyList(), 300_000, 300_000, false)
        return OrdinaryTransactionEntrySnapshot(
            references,
            listOf(OrdinaryProjectView(project, "Trip", true)),
            listOf(OrdinarySettlementActivityView(activity, "Weekend", jpy, listOf(OrdinaryParticipantView(self, "Me", true), OrdinaryParticipantView(friend, "Friend", false)), true)),
            listOf(OrdinaryTemplateView(template, "Quick lunch", OrdinaryDirection.EXPENSE, expenseChild, bank, card, merchant, project, null, "1000", jpy, null, null)),
            emptyList(),
            null,
        )
    }

    fun editor(locale: Locale = Locale.JAPAN): OrdinaryRecordEditorState = OrdinaryRecordPolicy.createEditor(snapshot(), RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, expenseChild, null, now, zone, locale)
    fun content(editor: OrdinaryRecordEditorState = editor()) = OrdinaryRecordLoadState.Content(snapshot(), editor = editor)

    val actions = OrdinaryRecordActions(
        onRetry = {}, onTab = {}, onSearch = {}, onNavigate = { _, _, _ -> }, onOpenEditor = { _, _, _, _ -> },
        onExpression = {}, onOperator = {}, onSelectCategory = {}, onSelectAccount = {}, onSelectCard = {}, onSelectReference = { _, _ -> },
        onNote = {}, onSettlementEnabled = {}, onSettlementActivity = {}, onSettlementPayer = {}, onSettlementSplitMethod = {}, onSettlementChargeDistribution = {}, onSettlementRoundingRule = {},
        onSettlementParticipantIncluded = {}, onSettlementAllocationInput = { _, _ -> }, onSettlementChargeInput = { _, _ -> }, onSettlementTax = {}, onSettlementServiceFee = {},
        onOccurredAt = { _, _, _ -> }, onManualLocation = { _, _ -> }, onAddAttachment = {}, onReuseAttachment = {}, onOpenAttachment = {}, onCancelAttachment = {}, onSave = {}, onUnsavedDiscard = {},
        onUnsavedKeepEditing = {}, onReloadConflict = {}, onCancelConflict = {},
    )

    private fun currency(value: String): CurrencyCode = (CurrencyCode.parse(value) as DomainResult.Success).value
    private fun id(value: Long) = StableId.fromUuid(UUID(0x1300, value))
}
