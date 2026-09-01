@file:Suppress("MaxLineLength")

package app.ledger.feature.record

import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.navigation.EnumMaskArgument
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.ScreenId
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.CardReferenceView
import app.ledger.finance.application.CategoryReferenceView
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryParticipantView
import app.ledger.finance.application.OrdinaryRecentDefaultView
import app.ledger.finance.application.OrdinarySettlementActivityView
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.SettlementChargeDistribution
import app.ledger.finance.domain.SettlementSplitMethod
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.UserAccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class OrdinaryRecordPolicyTest {
    @Test
    fun `ordinary account picker route mask includes every selectable account type`() {
        assertEquals((1 shl UserAccountType.entries.size) - 1, ORDINARY_ACCOUNT_TYPE_MASK)
        val destination = LedgerRouteContract.destination(
            ScreenId("REC-005"),
            mapOf("allowedTypes" to EnumMaskArgument.fromBits(ORDINARY_ACCOUNT_TYPE_MASK)),
        )
        assertEquals("record/account-picker/$ORDINARY_ACCOUNT_TYPE_MASK", destination.path)
    }

    @Test
    fun categoryDefaultBeatsRecentAndAccountChangeClearsIncompatibleCard() {
        val snapshot = snapshot()
        var editor = OrdinaryRecordPolicy.createEditor(snapshot, RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, CATEGORY_DEFAULT, null, NOW, ZONE, Locale.JAPAN)
        assertEquals(BANK, editor.draft.accountId)
        assertEquals(BANK_CARD, editor.draft.cardId)
        assertEquals(RecordDefaultSource.CATEGORY, editor.draft.origins[RecordField.ACCOUNT]?.source)

        editor = OrdinaryRecordPolicy.selectAccount(editor, CASH, Locale.JAPAN)
        assertEquals(CASH, editor.draft.accountId)
        assertNull(editor.draft.cardId)
        assertEquals(RecordDefaultSource.MANUAL, editor.draft.origins[RecordField.ACCOUNT]?.source)
    }

    @Test
    fun invalidSaveIsClickableValidationThenExpressionBecomesExactIntegerMinor() {
        var editor = OrdinaryRecordPolicy.createEditor(snapshot(), RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, CATEGORY_DEFAULT, null, NOW, ZONE, Locale.JAPAN)
        editor = OrdinaryRecordPolicy.validate(editor)
        assertTrue(editor.errors.any { it.field == RecordField.AMOUNT })
        assertEquals(RecordEditorPresentation.VALIDATING, editor.presentation)

        editor = OrdinaryRecordPolicy.changeExpression(editor, "1000+250", Locale.JAPAN)
        assertEquals(1_250L, editor.draft.resultMinor)
        assertEquals("1000+250", editor.draft.normalizedExpression)
        assertTrue(OrdinaryRecordPolicy.validate(editor).errors.isEmpty())
        assertTrue(editor.draft.dirty)
    }

    @Test
    fun settlementIsCollapsedAndBalancedAllocationUsesOnePayer() {
        val editor = OrdinaryRecordPolicy.createEditor(snapshot(), RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, CATEGORY_DEFAULT, null, NOW, ZONE, Locale.JAPAN)
        assertFalse(editor.draft.settlementEnabled)
        assertTrue(editor.draft.settlementShares.isEmpty())
    }

    @Test
    fun closedSettlementModesStayExactAndExternalPayerNeedsNoLocalAccount() {
        var editor = OrdinaryRecordPolicy.createEditor(snapshot(), RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, CATEGORY_DEFAULT, null, NOW, ZONE, Locale.JAPAN)
        editor = OrdinaryRecordPolicy.changeExpression(editor, "1001", Locale.JAPAN)
        editor = OrdinaryRecordPolicy.setSettlementEnabled(editor, true)
        editor = OrdinaryRecordPolicy.selectSettlementActivity(editor, ACTIVITY)

        SettlementSplitMethod.entries.forEach { method ->
            editor = OrdinaryRecordPolicy.selectSettlementSplitMethod(editor, method)
            assertEquals(1_001L, editor.draft.settlementShares.sumOf { it.paidMinor })
            assertEquals(1_001L, editor.draft.settlementShares.sumOf { it.owedMinor })
            assertEquals(1, editor.draft.settlementShares.count { it.paidMinor > 0L })
        }

        editor = OrdinaryRecordPolicy.selectSettlementPayer(editor, FRIEND)
        assertNull(editor.draft.accountId)
        assertNull(editor.draft.cardId)
        assertTrue(OrdinaryRecordPolicy.validate(editor).errors.none { it.field == RecordField.ACCOUNT })

        editor = OrdinaryRecordPolicy.selectSettlementSplitMethod(editor, SettlementSplitMethod.EQUAL)
        editor = OrdinaryRecordPolicy.toggleSettlementParticipant(editor, OTHER)
        assertEquals(0L, editor.draft.settlementShares.single { it.participantId == OTHER }.owedMinor)
        assertEquals(1_001L, editor.draft.settlementShares.sumOf { it.owedMinor })
    }

    @Test
    fun taxAndServiceFeeSpecifiedDistributionMustBalanceExactly() {
        var editor = OrdinaryRecordPolicy.createEditor(snapshot(), RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, CATEGORY_DEFAULT, null, NOW, ZONE, Locale.JAPAN)
        editor = OrdinaryRecordPolicy.changeExpression(editor, "1000", Locale.JAPAN)
        editor = OrdinaryRecordPolicy.selectSettlementActivity(OrdinaryRecordPolicy.setSettlementEnabled(editor, true), ACTIVITY)
        editor = OrdinaryRecordPolicy.updateSettlementTax(editor, "11")
        editor = OrdinaryRecordPolicy.updateSettlementServiceFee(editor, "10")
        editor = OrdinaryRecordPolicy.selectSettlementChargeDistribution(editor, SettlementChargeDistribution.SPECIFIED)
        editor = OrdinaryRecordPolicy.updateSettlementChargeInput(editor, SELF, "7")
        editor = OrdinaryRecordPolicy.updateSettlementChargeInput(editor, FRIEND, "7")
        editor = OrdinaryRecordPolicy.updateSettlementChargeInput(editor, OTHER, "7")

        assertEquals(1_000L, editor.draft.settlementShares.sumOf { it.paidMinor })
        assertEquals(1_000L, editor.draft.settlementShares.sumOf { it.owedMinor })
        assertTrue(OrdinaryRecordPolicy.validate(editor).errors.isEmpty())
    }

    @Test
    fun dateTimePickerPreservesOriginalZoneAndAttachmentImportBlocksSubmission() {
        var editor = OrdinaryRecordPolicy.createEditor(snapshot(), RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, CATEGORY_DEFAULT, null, NOW, ZONE, Locale.JAPAN)
        editor = OrdinaryRecordPolicy.updateOccurredAt(editor, Instant.parse("2026-08-10T00:00:00Z").toEpochMilli(), 1, 15)
        assertEquals(Instant.parse("2026-08-09T16:15:00Z"), editor.draft.occurredAt)
        assertEquals(ZONE, editor.draft.zoneId)
        assertTrue(RecordField.OCCURRED_AT in editor.draft.touched)

        editor = OrdinaryRecordPolicy.changeExpression(editor, "1000", Locale.JAPAN).copy(attachmentImporting = true)
        assertTrue(OrdinaryRecordPolicy.validate(editor).errors.any { it.code == "ATTACHMENT_IMPORTING" })
    }

    @Test
    fun initialAmountAutoFocusCanOnlyBeConsumedOncePerEditor() {
        val editor = OrdinaryRecordPolicy.createEditor(snapshot(), RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, CATEGORY_DEFAULT, null, NOW, ZONE, Locale.JAPAN)

        val consumed = OrdinaryRecordPolicy.consumeAmountAutoFocus(editor)

        assertTrue(consumed.amountAutoFocusConsumed)
        assertTrue(OrdinaryRecordPolicy.consumeAmountAutoFocus(consumed) === consumed)
    }

    @Test
    fun everyEntryOriginHasTheFrozenReturnBehavior() {
        assertEquals(RecordReturnTarget.CategoryGrid(OrdinaryDirection.EXPENSE), RecordReturnPolicy.afterSuccess(RecordEntryOrigin.CATEGORY_GRID, OrdinaryDirection.EXPENSE, TX, null))
        assertEquals(RecordReturnTarget.CategoryGrid(OrdinaryDirection.INCOME), RecordReturnPolicy.afterSuccess(RecordEntryOrigin.TEMPLATE, OrdinaryDirection.INCOME, TX, TEMPLATE))
        assertEquals(RecordReturnTarget.TransactionDetail(TX), RecordReturnPolicy.afterSuccess(RecordEntryOrigin.EDIT_DETAIL, OrdinaryDirection.EXPENSE, TX, null))
        assertEquals(RecordReturnTarget.CandidateList, RecordReturnPolicy.afterSuccess(RecordEntryOrigin.CANDIDATE, OrdinaryDirection.EXPENSE, TX, TEMPLATE))
        assertEquals(RecordReturnTarget.BatchRow(BATCH_ROW), RecordReturnPolicy.afterSuccess(RecordEntryOrigin.BATCH_ROW, OrdinaryDirection.EXPENSE, TX, BATCH_ROW))
    }

    private fun snapshot(): OrdinaryTransactionEntrySnapshot {
        val jpy = CurrencyCode.parse("JPY").let { it as app.ledger.core.common.DomainResult.Success }.value
        val accounts = listOf(
            account(CASH, UserAccountType.CASH, "Cash", 0, jpy),
            account(BANK, UserAccountType.BANK, "Bank", 1, jpy),
        )
        val cards = listOf(CardReferenceView(BANK_CARD, BANK, CardType.DEBIT, "Debit", "1234", EntityStatus.ACTIVE, null, "card", 0, 0, 1, 0))
        val categories = listOf(
            CategoryReferenceView(CATEGORY_DEFAULT, CategoryDirection.EXPENSE, null, 1, "Food", "record", 0, 0, CategoryStatus.ACTIVE, StatisticalNature.CONSUMPTION_EXPENSE, BANK, BANK_CARD, null, 1, 0, 0),
        )
        val references = ReferenceDataSnapshot(BOOK, jpy, 3, accounts, cards, categories, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, false)
        val activity = OrdinarySettlementActivityView(
            ACTIVITY,
            "Weekend",
            jpy,
            listOf(
                OrdinaryParticipantView(SELF, "Me", true),
                OrdinaryParticipantView(FRIEND, "Friend", false),
                OrdinaryParticipantView(OTHER, "Other", false),
            ),
            true,
        )
        return OrdinaryTransactionEntrySnapshot(references, emptyList(), listOf(activity), emptyList(), listOf(OrdinaryRecentDefaultView(OrdinaryDirection.EXPENSE, CATEGORY_DEFAULT, CASH, null, NOW.minusSeconds(60))), null)
    }

    private fun account(id: StableId, type: UserAccountType, name: String, sort: Int, currency: CurrencyCode) = AccountReferenceView(id, type, name, currency, EntityStatus.ACTIVE, null, null, null, "account", 0, sort, 1, 0, 0, null, false, 0)
    private fun id(value: Long) = StableId.fromUuid(UUID(0x13, value))

    private companion object {
        val BOOK = StableId.fromUuid(UUID(0x13, 1))
        val CASH = StableId.fromUuid(UUID(0x13, 2))
        val BANK = StableId.fromUuid(UUID(0x13, 3))
        val BANK_CARD = StableId.fromUuid(UUID(0x13, 4))
        val CATEGORY_DEFAULT = StableId.fromUuid(UUID(0x13, 5))
        val TX = StableId.fromUuid(UUID(0x13, 6))
        val TEMPLATE = StableId.fromUuid(UUID(0x13, 7))
        val BATCH_ROW = StableId.fromUuid(UUID(0x13, 8))
        val ACTIVITY = StableId.fromUuid(UUID(0x13, 9))
        val SELF = StableId.fromUuid(UUID(0x13, 10))
        val FRIEND = StableId.fromUuid(UUID(0x13, 11))
        val OTHER = StableId.fromUuid(UUID(0x13, 12))
        val NOW: Instant = Instant.parse("2026-08-03T03:30:00Z")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
