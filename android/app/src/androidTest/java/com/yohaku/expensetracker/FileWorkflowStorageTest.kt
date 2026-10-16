package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileWorkflowStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: YohakuDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var root: File
    private lateinit var book: String
    private lateinit var account: String
    private lateinit var category: String
    @Before fun open() = runBlocking {
        root = File(context.cacheDir, "file-workflow-" + UUID.randomUUID()).apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, YohakuDatabase::class.java).build()
        repo = LedgerRepository(db, mediaRoot = root)
        book = repo.createBook("文件", Currency.JPY)
        account = repo.saveAccount(book, "现金", AccountType.CASH, Currency.JPY, Money(Currency.JPY, 10000), "2026-10-01")
        category = repo.saveCategory(book, "生活", EntryKind.EXPENSE, null)
    }
    @After fun close() { db.close(); root.deleteRecursively() }
    private suspend fun snapshot() = repo.snapshot(repo.dao.book(book)!!).first()
    private fun draft(id: String? = null) = EntryDraft(id = id, accountId = account, categoryId = category,
        amount = "1000", date = "2026-10-14", mediaInitialized = true)
    private fun statement() = StatementFile("yohaku.statement", 1, repo.id(), book, account, "JPY",
        "2026-10-01", "2026-10-31", "posted_date", true, "銀行", emptyList(),
        listOf(StatementRow(repo.id(), "2026-10-14", "out", FileMoney("JPY", "1000"), "原文")))
    @Test fun previewCancelImportDuplicateAndDeletionNeverWriteLedgerFacts() = runBlocking {
        val event = repo.saveEntry(book, draft())
        val original = statement()
        val raw = FileProtocols.json.encodeToString(original)
        val preview = repo.previewStatement(book, raw)
        assertTrue(db.fileWorkflowDao().statements(book).first().isEmpty())
        assertEquals(1, snapshot().events.size)
        repo.saveStatement(book, preview)
        val batches = db.fileWorkflowDao().statements(book).first()
        assertEquals(raw, batches.single().originalJson)
        assertEquals(1, snapshot().matchStatements(batches).values.single().exact.size)
        val changedIds = original.copy(file_id = repo.id(), rows = original.rows.map { it.copy(row_id = repo.id()) })
        val duplicate = repo.previewStatement(book, FileProtocols.json.encodeToString(changedIds))
        assertEquals(original.file_id, duplicate.duplicateBatchId)
        try { repo.saveStatement(book, duplicate); fail("duplicate imported") } catch (_: IllegalArgumentException) { }
        val different = original.copy(file_id = repo.id(), rows = original.rows.map { it.copy(row_id = repo.id(), settlement = FileMoney("JPY", "900")) })
        val overlap = repo.previewStatement(book, FileProtocols.json.encodeToString(different))
        assertEquals(listOf(original.file_id), overlap.overlappingBatchIds)
        try { repo.saveStatement(book, overlap); fail("overlap not confirmed") } catch (_: IllegalArgumentException) { }
        repo.saveStatement(book, overlap, acceptOverlap = true)
        // Editing the original forces a recomputation; an old exact match cannot survive.
        repo.saveEntry(book, draft(event).copy(amount = "1100"))
        assertTrue(snapshot().matchStatements(batches).values.single().exact.isEmpty())
        db.fileWorkflowDao().deleteStatement(book, original.file_id)
        assertEquals(1, snapshot().events.size)
        assertEquals(8900L, snapshot().balance(snapshot().accounts.single()))
    }
    @Test fun invalidTargetIsRejectedAndCreditDirectionFollowsAccountCashFlow() = runBlocking {
        val other = repo.createBook("別帳簿", Currency.JPY)
        try { repo.previewStatement(other, FileProtocols.json.encodeToString(statement())); fail("wrong book") } catch (_: IllegalArgumentException) { }
        assertTrue(db.fileWorkflowDao().statements(other).first().isEmpty())
        val credit = repo.saveAccount(book, "信用卡", AccountType.CREDIT, Currency.JPY, Money(Currency.JPY, 0), "2026-10-01")
        repo.saveEntry(book, draft().copy(accountId = credit))
        val posting = snapshot().reconciliationPostings().single()
        assertEquals("out", posting.direction)
        assertEquals("1000", posting.amount.toPlainString())
    }
    @Test fun exportedReceiptBackfillPreservesLedgerAndFlagsReplacementAndNewImages() = runBlocking {
        val d = draft(); repo.saveDraft(book, d)
        val image = repo.importMedia(book, d.mediaSession, "receipt.png", "image/png", true) { byteArrayOf(1, 2, 3).inputStream() }
        val stored = repo.json.decodeFromString<EntryDraft>(repo.dao.storedDraft(book)!!.json)
        val event = repo.saveEntry(book, stored)
        val receipt = snapshot().receipts.single()
        val sources = repo.receiptArchiveSources(book, setOf(receipt.id))
        val bundle = ReceiptBundleFile("yohaku.receipt-bundle", 1, repo.id(), book, 1, 1, sources.map { it.record })
        assertTrue(db.fileWorkflowDao().exports(book).first().isEmpty())
        val zip = writeReceiptArchives(bundle.bundle_id, book, sources, File(root, "export"))
        assertTrue(zip.single().isFile)
        repo.recordSavedReceiptExport(bundle)
        assertEquals("UNSORTED", snapshot().receipts.single().status)
        val record = ReceiptItemsRecord(event, receipt.id, 1, 1, "JPY", listOf(image.id),
            listOf(ReceiptItem(repo.id(), listOf(image.id), emptyList(), "商品", "1", "個", "950", "950")),
            emptyList(), true, emptyList(), "950")
        val returned = ReceiptItemsFile("yohaku.receipt-items", 1, bundle.bundle_id, book, listOf(record))
        val raw = FileProtocols.json.encodeToString(returned)
        val preview = repo.previewReceiptItems(book, raw)
        assertEquals("-50", preview.targets.single().totals.differenceFromOriginal!!.toPlainString())
        assertTrue(db.fileWorkflowDao().items(book).first().isEmpty())
        repo.saveReceiptItems(book, preview)
        assertEquals("SORTED", snapshot().receipts.single().status)
        val replacement = repo.previewReceiptItems(book, raw)
        try { repo.saveReceiptItems(book, replacement); fail("replacement not confirmed") } catch (_: IllegalArgumentException) { }
        repo.saveReceiptItems(book, replacement, acceptReplacement = true)
        assertEquals(2, db.fileWorkflowDao().itemsForReceipt(book, receipt.id)!!.revision)
        val beforeEdit = repo.previewReceiptItems(book, raw)
        repo.editReceiptItems(book, receipt.id, 2, record.copy(items = record.items.map { it.copy(name = "手工修改") }))
        try { repo.saveReceiptItems(book, beforeEdit, acceptReplacement = true); fail("preview overwrote newer edit") } catch (_: IllegalArgumentException) { }
        assertEquals(3, db.fileWorkflowDao().itemsForReceipt(book, receipt.id)!!.revision)
        val edit = draft(event).copy(receiptFileIds = listOf(image.id)); repo.saveDraft(book, edit)
        val added = repo.importMedia(book, edit.mediaSession, "new.png", "image/png", true) { byteArrayOf(4, 5).inputStream() }
        val updated = repo.json.decodeFromString<EntryDraft>(repo.dao.storedDraft(book)!!.json)
        repo.saveEntry(book, updated)
        val old = repo.previewReceiptItems(book, raw)
        assertTrue(old.targets.single().staleExport)
        assertEquals(setOf(added.id), old.targets.single().addedImageIds)
        try { repo.saveReceiptItems(book, old, acceptReplacement = true); fail("stale not confirmed") } catch (_: IllegalArgumentException) { }
        repo.saveReceiptItems(book, old, acceptStale = true, acceptReplacement = true)
        assertEquals("UNSORTED", snapshot().receipts.single().status)
        assertEquals(1, snapshot().events.size)
        assertEquals(1000L, snapshot().events.single().amountMinor)
        assertEquals(9000L, snapshot().balance(snapshot().accounts.single()))
        assertEquals(listOf(image.id, added.id), snapshot().entryReceiptImages(event).map { it.id })
    }
    @Test fun version23MigratesPreservesExistingDataAndReopensNewDocuments() = runBlocking {
        val name = "files-upgrade-" + repo.id() + ".db"
        var disk = Room.databaseBuilder(context, YohakuDatabase::class.java, name).build()
        try {
            var repository = LedgerRepository(disk)
            val b = repository.createBook("升级", Currency.JPY)
            val a = repository.saveAccount(b, "银行", AccountType.BANK, Currency.JPY, Money(Currency.JPY, 5000), "2026-10-01")
            disk.close()
            val old = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
            old.use {
                it.execSQL("DROP TABLE statement_batches"); it.execSQL("DROP TABLE receipt_exports"); it.execSQL("DROP TABLE receipt_item_documents")
                val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v23-schema.json").bufferedReader().readText()).getJSONObject("database")
                it.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42", arrayOf(schema.getString("identityHash")))
                restoreBookLifecycleFixture(it);it.version = 23
            }
            disk = Room.databaseBuilder(context, YohakuDatabase::class.java, name).addMigrations(MIGRATION_23_24,com.yohaku.expensetracker.data.db.MIGRATION_24_25,com.yohaku.expensetracker.data.db.MIGRATION_25_26,com.yohaku.expensetracker.data.db.MIGRATION_26_27,com.yohaku.expensetracker.data.db.MIGRATION_27_28).build()
            repository = LedgerRepository(disk)
            assertEquals(5000L, repository.snapshot(repository.dao.book(b)!!).first().balance(repository.dao.account(b, a)!!))
            val file = statement().copy(book_id = b, account_id = a)
            repository.saveStatement(b, repository.previewStatement(b, FileProtocols.json.encodeToString(file)))
            disk.close()
            disk = Room.databaseBuilder(context, YohakuDatabase::class.java, name).build()
            assertEquals(file.file_id, disk.fileWorkflowDao().statements(b).first().single().id)
            assertEquals(0, disk.dao().eventCount(b))
        } finally { disk.close(); context.deleteDatabase(name) }
    }
}
