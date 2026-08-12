@file:Suppress("TooManyFunctions")

package app.ledger.finance.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.ProjectionFamily
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.ContentHash
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

internal class FinancialPersistenceAbort(val domainError: DomainError) : RuntimeException()

internal fun abort(error: DomainError): Nothing = throw FinancialPersistenceAbort(error)

internal fun StableId.internalId(): Long {
    val candidate = ByteBuffer.wrap(bytes).getLong(StableId.BYTE_COUNT - Long.SIZE_BYTES) and Long.MAX_VALUE
    return if (candidate == 0L) 1L else candidate
}

internal fun SupportSQLiteDatabase.allocateInternalId(table: String, uid: StableId): Long {
    if (queryOne("SELECT id FROM $table WHERE uid = ?", arrayOf(uid.bytes)) { it.getLong(0) } != null) {
        abort(FinanceDataError.CorruptData)
    }
    val preferred = uid.internalId()
    val collision = queryOne("SELECT COUNT(*) FROM $table WHERE id = ?", arrayOf(preferred)) { it.getLong(0) } ?: 0L
    if (collision == 0L) return preferred
    val maximum = queryOne("SELECT COALESCE(MAX(id), 0) FROM $table") { it.getLong(0) } ?: 0L
    return try {
        Math.addExact(maximum, 1L).takeIf { it > 0L } ?: abort(FinanceDataError.NumericRangeExceeded)
    } catch (_: ArithmeticException) {
        abort(FinanceDataError.NumericRangeExceeded)
    }
}

internal fun stableIdFromInternal(value: Long): StableId = StableId.fromBytes(
    ByteBuffer.allocate(StableId.BYTE_COUNT)
        .putLong(INTERNAL_ID_PREFIX)
        .putLong(value)
        .array(),
).valueOrAbort()

internal fun Cursor.stableId(column: String): StableId = StableId.fromBytes(getBlob(getColumnIndexOrThrow(column))).valueOrAbort()

internal fun Cursor.hash256(column: String): Hash256 = Hash256.fromBytes(getBlob(getColumnIndexOrThrow(column))).valueOrAbort()

internal fun Cursor.contentHash(column: String): ContentHash = ContentHash(hash256(column))

internal fun Cursor.nullableLong(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}

internal fun Cursor.nullableString(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

internal fun Cursor.nullableStableId(column: String): StableId? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else StableId.fromBytes(getBlob(index)).valueOrAbort()
}

internal fun Instant.toStorageEpochMillis(): Long = toEpochMilli()

internal fun Long.toStoredInstant(): Instant = Instant.ofEpochMilli(this)

internal fun LocalDate.toStorageInt(): Int = year * YEAR_DATE_MULTIPLIER + monthValue * MONTH_DATE_MULTIPLIER + dayOfMonth

internal fun Int.toStoredLocalDate(): LocalDate = LocalDate.of(
    this / YEAR_DATE_MULTIPLIER,
    (this / MONTH_DATE_MULTIPLIER) % MONTH_MODULUS,
    this % MONTH_DATE_MULTIPLIER,
)

internal fun YearMonth.toStorageInt(): Int = year * YEAR_MONTH_MULTIPLIER + monthValue

internal fun Int.toStoredYearMonth(): YearMonth = YearMonth.of(this / YEAR_MONTH_MULTIPLIER, this % YEAR_MONTH_MULTIPLIER)

internal fun Boolean.toSqlInt(): Int = if (this) 1 else 0

internal inline fun <T> SupportSQLiteDatabase.queryOne(
    sql: String,
    args: Array<out Any?> = emptyArray(),
    mapper: (Cursor) -> T,
): T? = query(sql, args).use { cursor -> if (cursor.moveToFirst()) mapper(cursor) else null }

internal inline fun <T> SupportSQLiteDatabase.queryList(
    sql: String,
    args: Array<out Any?> = emptyArray(),
    mapper: (Cursor) -> T,
): List<T> = query(sql, args).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(mapper(cursor))
    }
}

internal fun SupportSQLiteDatabase.requireInternalId(table: String, uid: StableId): Long = queryOne("SELECT id FROM $table WHERE uid = ?", arrayOf(uid.bytes)) { it.getLong(0) }
    ?: abort(FinanceDataError.CorruptData)

internal fun SupportSQLiteDatabase.optionalInternalId(table: String, uid: StableId?): Long? = uid?.let { requireInternalId(table, it) }

internal fun SupportSQLiteDatabase.commitId(uid: BookCommitId): Long = requireInternalId("book_commit", uid.value)

internal fun SupportSQLiteDatabase.isProjectionFamilyCurrent(
    family: ProjectionFamily,
    revision: LocalRevision,
): Boolean = queryOne(
    "SELECT COUNT(*) FROM projection_family_state WHERE family=? AND as_of_local_revision=?",
    arrayOf<Any>(family.ordinal, revision.value),
) { it.getLong(0) } == 1L

internal fun SupportSQLiteDatabase.commandReceipt(commandId: CommandId): StoredCommandReceipt? = queryOne(
    "SELECT cr.command_uid, cr.command_type, cr.payload_hash, bc.uid AS commit_uid, cr.primary_entity_uid, cr.executed_at " +
        "FROM command_receipt cr JOIN book_commit bc ON bc.id = cr.commit_id WHERE cr.command_uid = ?",
    arrayOf(commandId.stableId.bytes),
) { cursor ->
    StoredCommandReceipt(
        commandId = CommandId(cursor.stableId("command_uid")),
        commandTypeOrdinal = cursor.getInt(cursor.getColumnIndexOrThrow("command_type")),
        payloadHash = cursor.hash256("payload_hash"),
        commitId = BookCommitId(cursor.stableId("commit_uid")),
        primaryEntityId = cursor.nullableStableId("primary_entity_uid"),
        executedAt = cursor.getLong(cursor.getColumnIndexOrThrow("executed_at")).toStoredInstant(),
    )
}

internal data class StoredCommandReceipt(
    val commandId: CommandId,
    val commandTypeOrdinal: Int,
    val payloadHash: Hash256,
    val commitId: BookCommitId,
    val primaryEntityId: StableId?,
    val executedAt: Instant,
)

internal fun <T> DomainResult<T>.valueOrAbort(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> abort(error)
}

private const val INTERNAL_ID_PREFIX: Long = 0x4C45444745524641L
private const val YEAR_DATE_MULTIPLIER = 10_000
private const val MONTH_DATE_MULTIPLIER = 100
private const val MONTH_MODULUS = 100
private const val YEAR_MONTH_MULTIPLIER = 100
