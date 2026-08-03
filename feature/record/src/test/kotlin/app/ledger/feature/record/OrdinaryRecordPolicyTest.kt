@file:Suppress("MaxLineLength")

package app.ledger.feature.record

import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.CardReferenceView
import app.ledger.finance.application.CategoryReferenceView
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryRecentDefaultView
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.EntityStatus
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
        return OrdinaryTransactionEntrySnapshot(references, emptyList(), emptyList(), emptyList(), listOf(OrdinaryRecentDefaultView(OrdinaryDirection.EXPENSE, CATEGORY_DEFAULT, CASH, null, NOW.minusSeconds(60))), null)
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
        val NOW: Instant = Instant.parse("2026-08-03T03:30:00Z")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
