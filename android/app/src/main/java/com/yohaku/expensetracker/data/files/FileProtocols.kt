package com.yohaku.expensetracker.data.files

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import com.yohaku.expensetracker.domain.model.Currency

@Serializable
data class FileMoney(val currency: String, val amount: String)
@Serializable
data class StatementRow(
    val row_id: String, val date: String, val direction: String, val settlement: FileMoney,
    val summary: String, val source_ref: String? = null, val original: FileMoney? = null,
    val occurred_date: String? = null, val posted_date: String? = null,
    val occurred_at: String? = null, val time_zone: String? = null,
    val fees: FileMoney? = null, val exchange_rate: String? = null,
    val event_hint: String? = null, val note: String? = null,
)
@Serializable
data class StatementInfo(val principal: FileMoney? = null, val fees: FileMoney? = null,
    val total: FileMoney? = null, val payment_date: String? = null)
@Serializable
data class StatementFile(
    val kind: String, val version: Int, val file_id: String, val book_id: String,
    val account_id: String, val account_currency: String, val from: String, val to: String,
    val date_basis: String, val complete: Boolean, val source: String, val issues: List<String>,
    val rows: List<StatementRow>, val statement_info: StatementInfo? = null,
)
@Serializable
data class ReceiptBundleImage(val image_id: String, val position: Int, val path: String,
    val sha256: String, val mime_type: String, val size_bytes: Long)
@Serializable
data class ReceiptBundleRecord(val record_id: String, val receipt_id: String,
    val record_revision: Int, val receipt_revision: Int, val date: String,
    val original: FileMoney, val images: List<ReceiptBundleImage>,
    val merchant: String? = null, val settlement: FileMoney? = null)
@Serializable
data class ReceiptBundleFile(val kind: String, val version: Int,
    val bundle_id: String, val book_id: String, val part: Int, val parts: Int,
    val records: List<ReceiptBundleRecord>)
@Serializable
data class ReceiptItem(val item_id: String, val image_ids: List<String>,
    val uncertain_fields: List<String>, val name: String? = null, val quantity: String? = null,
    val unit: String? = null, val unit_price: String? = null, val line_amount: String? = null,
    val note: String? = null)
@Serializable
data class ReceiptAdjustment(val type: String, val amount: String,
    val included_in_items: Boolean, val note: String? = null)
@Serializable
data class ReceiptItemsRecord(val record_id: String, val receipt_id: String,
    val record_revision: Int, val receipt_revision: Int, val currency: String,
    val image_ids: List<String>, val items: List<ReceiptItem>,
    val adjustments: List<ReceiptAdjustment>, val complete: Boolean, val issues: List<String>,
    val receipt_total: String? = null)
@Serializable
data class ReceiptItemsFile(val kind: String, val version: Int, val bundle_id: String,
    val book_id: String, val records: List<ReceiptItemsRecord>)
@Serializable
data class ImportAccountIdentifier(val account_id: String, val name: String, val currency: String)
@Serializable
data class ImportIdentifiers(val kind: String = "yohaku.import-identifiers", val version: Int = 1,
    val book_id: String, val book_name: String, val accounts: List<ImportAccountIdentifier>)

data class FileProblem(val path: String, val message: String)
class FileValidationException(val problems: List<FileProblem>) : IllegalArgumentException(
    problems.joinToString("\n") { "${it.path}: ${it.message}" })

/** Pure parsing/validation. Never writes ledger facts or substitutes guessed values. */
object FileProtocols {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val decimal = Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?")
    private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private inline fun <reified T> decode(text: String): T = try { json.decodeFromString<T>(text.removePrefix("\uFEFF")) }
        catch (e: SerializationException) { throw FileValidationException(listOf(FileProblem("$", "JSON 字段/类型错误 / JSONフィールド・型エラー: ${e.message}"))) }
    fun statement(text: String): StatementFile = decode<StatementFile>(text).also { file -> validate {
        header(file.kind, file.version, "statement")
        id("$.file_id", file.file_id); id("$.book_id", file.book_id); id("$.account_id", file.account_id)
        currency("$.account_currency", file.account_currency)
        date("$.from", file.from); date("$.to", file.to)
        check("$.to", file.from <= file.to, "区间结束早于开始 / 期間の終了が開始より前です")
        check("$.date_basis", file.date_basis in listOf("posted_date", "occurred_date"), "日期语义无效 / 日付の基準が無効です")
        unique("$.rows.row_id", file.rows.map { it.row_id })
        file.rows.forEachIndexed { i, row ->
            val p = "$.rows[$i]"
            id("$p.row_id", row.row_id); date("$p.date", row.date)
            check("$p.date", row.date >= file.from && row.date <= file.to, "日期不在区间内 / 期間外の日付です")
            check("$p.direction", row.direction in listOf("in", "out"), "方向应为 in/out / 方向はin/outです")
            money("$p.settlement", row.settlement)
            check("$p.settlement.currency", row.settlement.currency == file.account_currency, "账户币种不符 / 口座通貨と一致しません")
            row.original?.let { money("$p.original", it) }; row.fees?.let { money("$p.fees", it) }
            row.occurred_date?.let { date("$p.occurred_date", it) }; row.posted_date?.let { date("$p.posted_date", it) }
            val basis = if (file.date_basis == "posted_date") row.posted_date else row.occurred_date
            check("$p.date", basis == null || basis == row.date, "基准日期冲突 / 基準日が矛盾しています")
            row.occurred_at?.let { value -> check("$p.occurred_at", runCatching { OffsetDateTime.parse(value) }.isSuccess, "需要带偏移的ISO时间 / オフセット付きISO時刻が必要です") }
            row.time_zone?.let { value -> check("$p.time_zone", runCatching { ZoneId.of(value) }.isSuccess, "时区无效 / タイムゾーンが無効です") }
            row.exchange_rate?.let { number("$p.exchange_rate", it, positive = true) }
            check("$p.event_hint", row.event_hint == null || row.event_hint in listOf("expense", "income", "transfer", "repayment", "refund", "borrowing"), "辅助类型无效 / 補助種別が無効です")
        }
        file.statement_info?.let { info ->
            info.principal?.let { money("$.statement_info.principal", it) }
            info.fees?.let { money("$.statement_info.fees", it) }
            info.total?.let { money("$.statement_info.total", it) }
            info.payment_date?.let { date("$.statement_info.payment_date", it) }
        }
    } }
    fun receiptItems(text: String): ReceiptItemsFile = decode<ReceiptItemsFile>(text).also { file -> validate {
        header(file.kind, file.version, "receipt-items")
        id("$.bundle_id", file.bundle_id); id("$.book_id", file.book_id)
        check("$.records", file.records.isNotEmpty(), "没有小票 / レシートがありません")
        unique("$.records.receipt_id", file.records.map { it.receipt_id })
        file.records.forEachIndexed { i, record ->
            val p = "$.records[$i]"
            id("$p.record_id", record.record_id); id("$p.receipt_id", record.receipt_id)
            revisions(p, record.record_revision, record.receipt_revision); currency("$p.currency", record.currency)
            check("$p.image_ids", record.image_ids.isNotEmpty(), "需要来源图片 / 画像が必要です")
            ids("$p.image_ids", record.image_ids); unique("$p.items.item_id", record.items.map { it.item_id })
            record.items.forEachIndexed { j, item ->
                val q = "$p.items[$j]"
                id("$q.item_id", item.item_id); ids("$q.image_ids", item.image_ids)
                check("$q.image_ids", record.image_ids.containsAll(item.image_ids), "商品来源图片不属于小票 / 商品の画像がレシートに属していません")
                item.quantity?.let { number("$q.quantity", it) }; item.unit_price?.let { number("$q.unit_price", it) }
                item.line_amount?.let { money(q, FileMoney(record.currency, it)) }
            }
            record.adjustments.forEachIndexed { j, a ->
                val q = "$p.adjustments[$j]"
                check("$q.type", a.type in listOf("tax", "discount", "shipping", "other"), "调整类型无效 / 調整種別が無効です")
                money(q, FileMoney(record.currency, a.amount))
            }
            record.receipt_total?.let { money("$p.receipt_total", FileMoney(record.currency, it)) }
        }
    } }
    fun receiptBundle(text: String): ReceiptBundleFile = decode<ReceiptBundleFile>(text).also { file -> validate {
        header(file.kind, file.version, "receipt-bundle"); id("$.bundle_id", file.bundle_id); id("$.book_id", file.book_id)
        check("$.part", file.part >= 1 && file.part <= file.parts, "分包编号无效 / 分割番号が無効です")
        check("$.records", file.records.isNotEmpty(), "没有小票 / レシートがありません")
        unique("$.records.receipt_id", file.records.map { it.receipt_id })
        file.records.forEachIndexed { i, record ->
            val p = "$.records[$i]"
            id("$p.record_id", record.record_id); id("$p.receipt_id", record.receipt_id)
            revisions(p, record.record_revision, record.receipt_revision); date("$p.date", record.date)
            money("$p.original", record.original); record.settlement?.let { money("$p.settlement", it) }
            check("$p.images", record.images.isNotEmpty(), "没有图片 / 画像がありません")
            unique("$p.images.image_id", record.images.map { it.image_id })
            check("$p.images.position", record.images.map { it.position } == record.images.indices.toList(), "图序须从0连续排列 / 画像順は0から連続させてください")
            record.images.forEachIndexed { j, image ->
                val q = "$p.images[$j]"
                id("$q.image_id", image.image_id)
                check("$q.path", image.path == "images/${record.receipt_id}/${image.image_id}", "图片路径无效 / 画像パスが無効です")
                check("$q.sha256", Regex("[0-9a-f]{64}").matches(image.sha256), "SHA-256无效 / SHA-256が無効です")
                check("$q.mime_type", image.mime_type.startsWith("image/"), "需要图片类型 / 画像形式が必要です")
                check("$q.size_bytes", image.size_bytes > 0, "图片大小无效 / 画像サイズが無効です")
            }
        }
    } }
    private fun validate(block: Validation.() -> Unit) { Validation().apply(block).finish() }
    private class Validation {
        val problems = mutableListOf<FileProblem>()
        fun check(path: String, valid: Boolean, message: String) { if (!valid) problems += FileProblem(path, message) }
        fun finish() { if (problems.isNotEmpty()) throw FileValidationException(problems) }
        fun header(kind: String, version: Int, expected: String) {
            check("$.kind", kind == "yohaku.$expected", "文件用途不符 / ファイル種別が一致しません")
            check("$.version", version == 1, "不支持此协议版本 / 未対応の形式バージョンです")
        }
        fun id(path: String, value: String) { check(path, uuid.matches(value) && runCatching { UUID.fromString(value) }.isSuccess, "需要稳定UUID / 安定したUUIDが必要です") }
        fun unique(path: String, values: List<String>) { check(path, values.distinct().size == values.size, "标识重复 / IDが重複しています") }
        fun ids(path: String, values: List<String>) { unique(path, values); values.forEachIndexed { i, v -> id("$path[$i]", v) } }
        fun currency(path: String, value: String) { check(path, Currency.entries.any { it.name == value }, "币种须为JPY/CNY/USD / 通貨はJPY/CNY/USDです") }
        fun date(path: String, value: String) { check(path, datePattern.matches(value) && runCatching { LocalDate.parse(value) }.isSuccess, "需要有效YYYY-MM-DD日期 / 有効なYYYY-MM-DD日付が必要です") }
        fun number(path: String, value: String, positive: Boolean = false) { check(path, decimal.matches(value) && (!positive || value.toBigDecimalOrNull()?.signum() == 1), "需要十进制字符串 / 10進数文字列が必要です") }
        fun revisions(path: String, record: Int, receipt: Int) { check("$path.record_revision", record > 0, "修订号须大于0 / リビジョンは正数です"); check("$path.receipt_revision", receipt > 0, "修订号须大于0 / リビジョンは正数です") }
        fun money(path: String, value: FileMoney) {
            currency("$path.currency", value.currency)
            val scale = Currency.entries.find { it.name == value.currency }?.scale ?: return
            val pattern = if (scale == 0) Regex("(0|[1-9][0-9]*)") else Regex("(0|[1-9][0-9]*)\\.[0-9]{2}")
            check("$path.amount", pattern.matches(value.amount) && runCatching { value.amount.toBigDecimal().movePointRight(scale).longValueExact() }.isSuccess,
                "金额精度或范围无效 / 金額の精度または範囲が無効です")
        }
    }
}
