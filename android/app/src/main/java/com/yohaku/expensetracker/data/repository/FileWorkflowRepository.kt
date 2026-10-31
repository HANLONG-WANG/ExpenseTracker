package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.reconcile.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.security.MessageDigest

private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
private fun canonical(element: JsonElement): String = when (element) {
    is JsonObject -> element.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonical(element.getValue(it)) }
    is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
    else -> element.toString()
}
/** Excludes generated file/row IDs; preserves true duplicate rows and all supplied source facts. */
internal fun statementFingerprint(file: StatementFile): String {
    val element = FileProtocols.json.encodeToJsonElement(file).jsonObject
    val rows = element.getValue("rows").jsonArray.map { JsonObject(it.jsonObject - "row_id") }.sortedBy { canonical(it) }
    return sha256(canonical(JsonObject((element - "file_id") + ("rows" to JsonArray(rows)))))
}

data class StatementImportPreview(val file: StatementFile, val originalJson: String,
    val fingerprint: String, val duplicateBatchId: String?, val overlappingBatchIds: List<String>,
    val incoming: BigDecimal, val outgoing: BigDecimal)

suspend fun LedgerRepository.importIdentifiers(bookId: String): ImportIdentifiers {
    val book = dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
    return ImportIdentifiers(book_id = bookId, book_name = book.name,
        accounts = dao.accounts(bookId).first().map { ImportAccountIdentifier(it.id, it.name, it.currency) })
}
suspend fun LedgerRepository.previewStatement(bookId: String, originalJson: String): StatementImportPreview {
    val file = FileProtocols.statement(originalJson)
    require(file.book_id == bookId) { "账本不一致 / 帳簿が一致しません" }
    val account = dao.account(bookId, file.account_id) ?: error("账户不存在 / 口座がありません")
    require(account.currency == file.account_currency) { "账户币种不一致 / 口座通貨が一致しません" }
    val fingerprint = statementFingerprint(file)
    val existing = db.fileWorkflowDao().statements(bookId).first()
    val duplicate = existing.find { it.id == file.file_id || it.fingerprint == fingerprint }
    val overlaps = existing.filter { it.accountId == file.account_id }.filter { batch ->
        val other = FileProtocols.statement(batch.originalJson)
        other.from <= file.to && other.to >= file.from
    }.map { it.id }
    fun sum(direction: String) = file.rows.filter { it.direction == direction }.fold(BigDecimal.ZERO) { a, row -> a + row.settlement.amount.toBigDecimal() }
    return StatementImportPreview(file, originalJson, fingerprint, duplicate?.id, overlaps, sum("in"), sum("out"))
}
/** No event/posting DAO is called. Rechecks identity, currency and duplicates in one transaction. */
suspend fun LedgerRepository.saveStatement(bookId: String, preview: StatementImportPreview,
    acceptOverlap: Boolean = false): String = db.withTransaction {
    val fresh = previewStatement(bookId, preview.originalJson)
    require(fresh.duplicateBatchId == null) { "文件已导入 / ファイルは取込済みです" }
    require(fresh.overlappingBatchIds.isEmpty() || acceptOverlap) { "核对区间重叠，请确认保留独立批次 / 照合期間が重複しています。別バッチで保存することを確認してください" }
    db.fileWorkflowDao().insertStatement(StatementBatchEntity(bookId, fresh.file.file_id,
        fresh.file.account_id, fresh.fingerprint, fresh.originalJson, clock.millis()))
    fresh.file.file_id
}

/** Recomputed from current facts on every read, so edits/deletion cannot leave stale exact matches. */
fun BookSnapshot.reconciliationPostings(): List<ReconcilePosting> = postings.mapNotNull { p ->
    if(p.deltaMinor==0L)return@mapNotNull null
    val event = visibleEvents.find { it.id == p.eventId } ?: return@mapNotNull null
    val account = accounts.first { it.id == p.accountId }
    val debt = AccountType.valueOf(account.type).debt
    val out = if (debt) p.deltaMinor > 0 else p.deltaMinor < 0
    ReconcilePosting(book.id, account.id, p.id, event.id, p.currency,
        BigDecimal.valueOf(p.deltaMinor, Currency.valueOf(p.currency).scale).abs(),
        if (out) "out" else "in", p.date, event.date)
}
fun BookSnapshot.matchStatements(batches: List<StatementBatchEntity>): Map<String, StatementMatchingResult> {
    return reviewStatements(batches,emptyList()).associate{it.batch.id to it.result}
}

suspend fun LedgerRepository.receiptArchiveSources(bookId: String, receiptIds: Set<String>): List<ReceiptArchiveSource> {
    require(receiptIds.isNotEmpty())
    val book = dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
    val s = snapshot(book).first()
    return receiptIds.map { receiptId ->
        val receipt = s.receipts.find { it.id == receiptId } ?: error("小票不存在 / レシートがありません")
        require(receipt.status != "SORTED") { "只能导出未整理小票 / 未整理レシートのみ出力できます" }
        val event = s.visibleEvents.firstOrNull { it.id == receipt.eventId } ?: error("原记账不存在 / 元の記録がありません")
        val files = s.entryReceiptImages(event.id)
        require(files.isNotEmpty()) { "小票没有图片 / レシートに画像がありません" }
        val images = files.mapIndexed { i, file -> ReceiptBundleImage(file.id, i,
            "images/${receipt.id}/${file.id}", file.checksum, file.mime, file.size) }
        val settlement = s.settlements.find { it.eventId == event.id }
        val actual = settlement?.takeIf { it.confirmed }?.let { FileMoney(it.accountCurrency, Money(Currency.valueOf(it.accountCurrency), it.accountMinor).decimal.toPlainString()) }
        ReceiptArchiveSource(ReceiptBundleRecord(event.id, receipt.id, event.revision, receipt.revision,
            event.date, FileMoney(event.currency, Money(Currency.valueOf(event.currency), event.amountMinor).decimal.toPlainString()),
            images, event.merchant.ifBlank { null }, actual), files.associate { it.id to mediaFile(it) })
    }
}

/** Call after a user-chosen destination is saved and closed, or after confirmed complete cloud submission. */
suspend fun LedgerRepository.recordSavedReceiptExport(manifest: ReceiptBundleFile) = db.withTransaction {
    val text = FileProtocols.json.encodeToString(manifest)
    FileProtocols.receiptBundle(text)
    require(dao.book(manifest.book_id) != null)
    val existing=db.fileWorkflowDao().exportPart(manifest.book_id,manifest.bundle_id,manifest.part)
    require(existing==null||existing.manifestJson==text){"导出批次内容冲突 / 出力バッチの内容が矛盾しています"}
    if(existing==null)db.fileWorkflowDao().insertExport(ReceiptExportEntity(manifest.book_id, manifest.bundle_id,
        manifest.part, text, clock.millis()))
}

data class ReceiptBackfillTarget(val record: ReceiptItemsRecord, val currentRecordRevision: Int,
    val currentReceiptRevision: Int, val existingItemsRevision: Int?, val addedImageIds: Set<String>,
    val totals: ReceiptTotals, val staleExport: Boolean)
data class ReceiptBackfillPreview(val file: ReceiptItemsFile, val originalJson: String,
    val targets: List<ReceiptBackfillTarget>)

suspend fun LedgerRepository.previewReceiptItems(bookId: String, originalJson: String): ReceiptBackfillPreview {
    val file = FileProtocols.receiptItems(originalJson)
    require(file.book_id == bookId) { "账本不一致 / 帳簿が一致しません" }
    val book = dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
    val s = snapshot(book).first()
    val exports = db.fileWorkflowDao().exports(bookId).first().filter { it.bundleId == file.bundle_id }
        .flatMap { FileProtocols.receiptBundle(it.manifestJson).records }
    require(exports.isNotEmpty()) { "找不到已保存的导出批次 / 保存済みの出力バッチがありません" }
    val targets = file.records.map { record ->
        val exported = exports.find { it.record_id == record.record_id && it.receipt_id == record.receipt_id }
            ?: error("小票不属于此导出批次 / レシートが出力バッチに属していません")
        require(record.record_revision == exported.record_revision && record.receipt_revision == exported.receipt_revision) {
            "导出修订号不符 / 出力リビジョンが一致しません"
        }
        val receipt = s.receipts.find { it.id == record.receipt_id && it.eventId == record.record_id }
            ?: error("原小票关联不存在 / 元のレシート関連がありません")
        val event = s.visibleEvents.find { it.id == record.record_id } ?: error("原记账不存在 / 元の記録がありません")
        val currentImages = s.entryReceiptImages(event.id).map { it.id }.toSet()
        val exportedImages = exported.images.map { it.image_id }.toSet()
        require(record.image_ids.all { it in exportedImages && it in currentImages }) {
            "来源图片与原小票不符 / 画像がレシートに一致しません"
        }
        val existing = db.fileWorkflowDao().itemsForReceipt(bookId, receipt.id)
        require(record.currency==event.currency){"商品与原消费币种不同 / 商品と元の支出の通貨が異なります"}
        val totals = receiptTotals(record, FileMoney(event.currency, Money(Currency.valueOf(event.currency), event.amountMinor).decimal.toPlainString()))
        ReceiptBackfillTarget(record, event.revision, receipt.revision, existing?.revision,
            currentImages - record.image_ids.toSet(), totals,
            event.revision != exported.record_revision || receipt.revision != exported.receipt_revision)
    }
    return ReceiptBackfillPreview(file, originalJson, targets)
}

/** Replaces one document per original receipt. Never modifies the original transaction or images. */
suspend fun LedgerRepository.saveReceiptItems(bookId: String, preview: ReceiptBackfillPreview,
    acceptStale: Boolean = false, acceptReplacement: Boolean = false) = db.withTransaction {
    val fresh = previewReceiptItems(bookId, preview.originalJson)
    require(fresh == preview) { "预览后资料已变化，请重新检查 / プレビュー後に情報が変わりました。再確認してください" }
    require(acceptStale || fresh.targets.none { it.staleExport }) { "导出后原记录已变化，请确认旧修订 / 出力後に元記録が変わりました。旧リビジョンを確認してください" }
    require(acceptReplacement || fresh.targets.none { it.existingItemsRevision != null }) { "已有商品，请确认替换 / 商品明細が存在します。置換を確認してください" }
    fresh.targets.forEach { target ->
        db.fileWorkflowDao().putItems(ReceiptItemDocumentEntity(bookId, target.record.receipt_id,
            fresh.file.bundle_id, FileProtocols.json.encodeToString(target.record),
            (target.existingItemsRevision ?: 0) + 1, clock.millis()))
        val receipt = dao.receipt(bookId, target.record.record_id)!!
        // Sorted means every current image has been through the cloud; incomplete details stay visible on the sorted receipt.
        dao.putReceipt(receipt.copy(status = if (target.addedImageIds.isEmpty()) "SORTED" else "UNSORTED"))
    }
}

/** Optimistic revision protects edits made while an assistant result is being previewed. */
suspend fun LedgerRepository.editReceiptItems(bookId: String, receiptId: String, expectedRevision: Int,
    edited: ReceiptItemsRecord) = db.withTransaction {
    val existing = db.fileWorkflowDao().itemsForReceipt(bookId, receiptId)
        ?: error("尚无商品明细 / 商品明細がありません")
    require(existing.revision == expectedRevision) { "商品已变化，请重新打开 / 商品が変わりました。開き直してください" }
    val previous = FileProtocols.json.decodeFromString<ReceiptItemsRecord>(existing.recordJson)
    require(edited.record_id == previous.record_id && edited.receipt_id == receiptId &&
        edited.currency == previous.currency && edited.record_revision == previous.record_revision &&
        edited.receipt_revision == previous.receipt_revision && edited.image_ids == previous.image_ids) {
        "商品编辑不能更换原小票关联 / 商品編集では元のレシート関連を変更できません"
    }
    FileProtocols.receiptItems(FileProtocols.json.encodeToString(ReceiptItemsFile("yohaku.receipt-items",
        1, existing.bundleId, bookId, listOf(edited))))
    val book = dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
    val s = snapshot(book).first()
    val receipt = s.receipts.first { it.id == receiptId }
    val event = s.visibleEvents.firstOrNull { it.id == receipt.eventId } ?: error("原记账不存在 / 元の記録がありません")
    val currentImages = s.entryReceiptImages(event.id).map { it.id }.toSet()
    require(currentImages.containsAll(edited.image_ids)) { "来源图片已变化 / 画像が変わりました" }
    db.fileWorkflowDao().putItems(existing.copy(recordJson = FileProtocols.json.encodeToString(edited),
        revision = existing.revision + 1, updatedAt = clock.millis()))
    dao.putReceipt(receipt.copy(status = if (currentImages == edited.image_ids.toSet()) "SORTED" else "UNSORTED"))
}
