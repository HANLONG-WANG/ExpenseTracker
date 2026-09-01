@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth", "TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.transfer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.SecureImportStagingAccess
import app.ledger.finance.domain.AttachmentId
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.TransactionId
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.DuplicateMatchKind
import app.ledger.transfer.domain.EncryptedStagingRepository
import app.ledger.transfer.domain.ImportTargetField
import app.ledger.transfer.domain.ImportTransformation
import app.ledger.transfer.domain.PreparedCommandPayload
import app.ledger.transfer.domain.PreparedCommandValidationState
import app.ledger.transfer.domain.RawRowPayload
import app.ledger.transfer.domain.StagingAttachment
import app.ledger.transfer.domain.StagingCounts
import app.ledger.transfer.domain.StagingDuplicateCandidate
import app.ledger.transfer.domain.StagingMapping
import app.ledger.transfer.domain.StagingParsedField
import app.ledger.transfer.domain.StagingParsedRow
import app.ledger.transfer.domain.StagingPreparedCommand
import app.ledger.transfer.domain.StagingRawRow
import app.ledger.transfer.domain.StagingValidationError
import app.ledger.transfer.domain.StagingValue
import app.ledger.transfer.domain.StructuredEntityKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate

class SqlCipherStagingRepository(
    private val bookId: StableId,
    private val operationId: BackgroundOperationId,
    private val access: SecureImportStagingAccess,
) : EncryptedStagingRepository {
    private val databaseName = "import_${operationId.value.bytes.toHex()}.db"

    override suspend fun create(operationId: BackgroundOperationId): DomainResult<Unit> = protect {
        require(operationId == this.operationId)
        access.create(bookId, databaseName)
    }

    override suspend fun clearPreparation(): DomainResult<Unit> = protect {
        access.write(bookId, databaseName) { database ->
            database.execSQL("DELETE FROM staging_validation_error")
            database.execSQL("DELETE FROM staging_duplicate_candidate")
            database.execSQL("DELETE FROM staging_prepared_command")
            database.execSQL("DELETE FROM staging_mapping")
        }
    }

    override suspend fun appendRaw(rows: List<StagingRawRow>): DomainResult<Unit> = protect {
        if (rows.isEmpty()) return@protect
        access.write(bookId, databaseName) { database ->
            database.compileStatement(
                "INSERT OR REPLACE INTO staging_raw_row(row_number,payload,source_hash,ingestion_state) VALUES(?,?,?,?)",
            ).use { statement ->
                rows.forEach { row ->
                    statement.bindLong(1, row.rowNumber)
                    statement.bindBlob(2, row.payload.bytes)
                    statement.bindBlob(3, row.sourceHash.bytes)
                    statement.bindLong(4, RAW_INGESTED.toLong())
                    statement.executeInsert()
                    statement.clearBindings()
                }
            }
        }
    }

    override suspend fun appendParsed(rows: List<StagingParsedRow>): DomainResult<Unit> = protect {
        if (rows.isEmpty()) return@protect
        access.write(bookId, databaseName) { database ->
            database.compileStatement(
                "INSERT OR REPLACE INTO staging_parsed_row(row_number,field_set_blob,parser_version,parsed_hash) VALUES(?,?,?,?)",
            ).use { statement ->
                rows.forEach { row ->
                    val encoded = StagingBinaryCodec.encodeFields(row.fields)
                    statement.bindLong(1, row.rowNumber)
                    statement.bindBlob(2, encoded)
                    statement.bindLong(3, PARSER_VERSION.toLong())
                    statement.bindBlob(4, Hash256.sha256(encoded).bytes)
                    statement.executeInsert()
                    statement.clearBindings()
                }
            }
        }
    }

    override suspend fun saveMappings(mappings: List<StagingMapping>): DomainResult<Unit> = protect {
        access.write(bookId, databaseName) { database ->
            database.execSQL("DELETE FROM staging_validation_error")
            database.execSQL("DELETE FROM staging_duplicate_candidate")
            database.execSQL("DELETE FROM staging_prepared_command")
            database.execSQL("DELETE FROM staging_mapping")
            database.compileStatement(
                "INSERT INTO staging_mapping(source_column,target_field,transformation_type,transformation_blob) VALUES(?,?,?,?)",
            ).use { statement ->
                mappings.forEach { mapping ->
                    val encoded = StagingBinaryCodec.encodeTransformation(mapping.transformation)
                    statement.bindString(1, mapping.sourceColumn)
                    statement.bindLong(2, mapping.targetField.ordinal.toLong())
                    statement.bindLong(3, encoded.first.toLong())
                    encoded.second?.let { statement.bindBlob(4, it) } ?: statement.bindNull(4)
                    statement.executeInsert()
                    statement.clearBindings()
                }
            }
        }
    }

    /** Reads the encrypted, normalized mapping snapshot used to prepare this operation. */
    suspend fun mappings(): DomainResult<List<StagingMapping>> = protectValue {
        access.read(bookId, databaseName) { database ->
            database.query(
                "SELECT source_column,target_field,transformation_type,transformation_blob " +
                    "FROM staging_mapping ORDER BY source_column,target_field",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            StagingMapping(
                                sourceColumn = cursor.getString(0),
                                targetField = ImportTargetField.entries[cursor.getInt(1)],
                                transformation = StagingBinaryCodec.decodeTransformation(
                                    cursor.getInt(2),
                                    if (cursor.isNull(3)) null else cursor.getBlob(3),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Returns durable duplicate candidates so a resumed wizard can require an explicit resolution. */
    suspend fun duplicateCandidates(): DomainResult<List<StagingDuplicateCandidate>> = protectValue {
        access.read(bookId, databaseName) { database ->
            database.query(
                "SELECT row_number,existing_transaction_uid,match_kind,confidence_basis_ciphertext " +
                    "FROM staging_duplicate_candidate ORDER BY row_number",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            StagingDuplicateCandidate(
                                rowNumber = cursor.getLong(0),
                                existingTransactionId = TransactionId(StableId.fromBytes(cursor.getBlob(1)).valueOrThrow()),
                                kind = DuplicateMatchKind.entries[cursor.getInt(2)],
                                confidenceBasis = cursor.getBlob(3).toString(Charsets.UTF_8),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun saveErrors(errors: List<StagingValidationError>): DomainResult<Unit> = protect {
        if (errors.isEmpty()) return@protect
        access.write(bookId, databaseName) { database ->
            database.compileStatement(
                "INSERT OR IGNORE INTO staging_validation_error(row_number,target_field,error_code,error_detail_ciphertext) VALUES(?,?,?,NULL)",
            ).use { statement ->
                errors.forEach { error ->
                    statement.bindLong(1, error.rowNumber)
                    error.field?.let { statement.bindLong(2, it.ordinal.toLong()) } ?: statement.bindNull(2)
                    statement.bindString(3, error.errorCode)
                    statement.executeInsert()
                    statement.clearBindings()
                }
            }
        }
    }

    override suspend fun saveDuplicates(candidates: List<StagingDuplicateCandidate>): DomainResult<Unit> = protect {
        if (candidates.isEmpty()) return@protect
        access.write(bookId, databaseName) { database ->
            database.compileStatement(
                "INSERT OR IGNORE INTO staging_duplicate_candidate(" +
                    "row_number,existing_transaction_uid,match_kind,confidence_basis_ciphertext,resolution) VALUES(?,?,?,?,NULL)",
            ).use { statement ->
                candidates.forEach { candidate ->
                    statement.bindLong(1, candidate.rowNumber)
                    statement.bindBlob(2, candidate.existingTransactionId.value.bytes)
                    statement.bindLong(3, candidate.kind.ordinal.toLong())
                    statement.bindBlob(4, candidate.confidenceBasis.toByteArray(Charsets.UTF_8))
                    statement.executeInsert()
                    statement.clearBindings()
                }
            }
        }
    }

    override suspend fun savePrepared(commands: List<StagingPreparedCommand>): DomainResult<Unit> = protect {
        if (commands.isEmpty()) return@protect
        access.write(bookId, databaseName) { database ->
            database.compileStatement(
                "INSERT OR REPLACE INTO staging_prepared_command(" +
                    "row_number,command_uid,command_type,command_blob,command_hash,validation_state) VALUES(?,?,?,?,?,?)",
            ).use { statement ->
                commands.forEach { command ->
                    statement.bindLong(1, command.rowNumber)
                    statement.bindBlob(2, command.commandId.stableId.bytes)
                    val storedType = command.commandType?.ordinal ?: (STRUCTURED_TYPE_OFFSET + requireNotNull(command.structuredKind).ordinal)
                    statement.bindLong(3, storedType.toLong())
                    statement.bindBlob(4, command.payload.bytes)
                    statement.bindBlob(5, command.payloadHash.bytes)
                    statement.bindLong(6, command.validationState.ordinal.toLong())
                    statement.executeInsert()
                    statement.clearBindings()
                }
            }
        }
    }

    override suspend fun saveAttachments(attachments: List<StagingAttachment>): DomainResult<Unit> = protect {
        if (attachments.isEmpty()) return@protect
        access.write(bookId, databaseName) { database ->
            database.compileStatement(
                "INSERT OR REPLACE INTO staging_attachment(" +
                    "row_number,source_handle_uid,imported_attachment_uid,content_hash,staging_storage_name) VALUES(?,?,?,?,NULL)",
            ).use { statement ->
                attachments.forEach { attachment ->
                    statement.bindLong(1, attachment.rowNumber)
                    statement.bindBlob(2, attachment.sourceHandleId.bytes)
                    attachment.importedAttachmentId?.let { statement.bindBlob(3, it.value.bytes) } ?: statement.bindNull(3)
                    attachment.contentHash?.let { statement.bindBlob(4, it.bytes) } ?: statement.bindNull(4)
                    statement.executeInsert()
                    statement.clearBindings()
                }
            }
        }
    }

    override suspend fun rawRows(offsetExclusive: Long, limit: Int): DomainResult<List<StagingRawRow>> = protectValue {
        requirePage(offsetExclusive, limit)
        access.read(bookId, databaseName) { database ->
            database.query(
                "SELECT row_number,payload,source_hash FROM staging_raw_row WHERE row_number>? ORDER BY row_number LIMIT ?",
                arrayOf(offsetExclusive, limit.toLong()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            StagingRawRow(
                                cursor.getLong(0),
                                RawRowPayload.of(cursor.getBlob(1)).valueOrThrow(),
                                Hash256.fromBytes(cursor.getBlob(2)).valueOrThrow(),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun parsedRows(offsetExclusive: Long, limit: Int): DomainResult<List<StagingParsedRow>> = protectValue {
        requirePage(offsetExclusive, limit)
        access.read(bookId, databaseName) { database ->
            database.query(
                "SELECT row_number,field_set_blob FROM staging_parsed_row WHERE row_number>? ORDER BY row_number LIMIT ?",
                arrayOf(offsetExclusive, limit.toLong()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(StagingParsedRow(cursor.getLong(0), StagingBinaryCodec.decodeFields(cursor.getBlob(1))))
                }
            }
        }
    }

    override suspend fun preparedCommands(offsetExclusive: Long, limit: Int): DomainResult<List<StagingPreparedCommand>> = protectValue {
        requirePage(offsetExclusive, limit)
        access.read(bookId, databaseName) { database ->
            database.query(
                "SELECT row_number,command_uid,command_type,command_blob,command_hash,validation_state " +
                    "FROM staging_prepared_command WHERE row_number>? ORDER BY row_number LIMIT ?",
                arrayOf(offsetExclusive, limit.toLong()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            StagingPreparedCommand(
                                rowNumber = cursor.getLong(0),
                                commandId = CommandId(StableId.fromBytes(cursor.getBlob(1)).valueOrThrow()),
                                commandType = cursor.getInt(2).takeIf { it < STRUCTURED_TYPE_OFFSET }
                                    ?.let { FinancialCommandType.entries[it] },
                                structuredKind = cursor.getInt(2).takeIf { it >= STRUCTURED_TYPE_OFFSET }
                                    ?.let { StructuredEntityKind.entries[it - STRUCTURED_TYPE_OFFSET] },
                                payload = PreparedCommandPayload.of(cursor.getBlob(3)).valueOrThrow(),
                                payloadHash = Hash256.fromBytes(cursor.getBlob(4)).valueOrThrow(),
                                validationState = PreparedCommandValidationState.entries[cursor.getInt(5)],
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Dependency-ordered structured commands, paged by prepared-command ordinal rather than source row. */
    suspend fun structuredPreparedCommands(
        offset: Long,
        limit: Int,
        beforeTransactions: Boolean,
    ): DomainResult<List<StagingPreparedCommand>> = protectValue {
        require(offset >= 0L)
        require(limit in 1..MAX_PAGE_SIZE)
        access.read(bookId, databaseName) { database ->
            database.query(
                "SELECT row_number,command_uid,command_type,command_blob,command_hash,validation_state " +
                    "FROM staging_prepared_command WHERE command_type>=? AND command_type ${if (beforeTransactions) "<" else ">"} ? " +
                    "ORDER BY command_type,row_number LIMIT ? OFFSET ?",
                arrayOf<Any>(
                    STRUCTURED_TYPE_OFFSET,
                    STRUCTURED_TYPE_OFFSET + StructuredEntityKind.TRANSACTION.ordinal,
                    limit.toLong(),
                    offset,
                ),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            StagingPreparedCommand(
                                rowNumber = cursor.getLong(0),
                                commandId = CommandId(StableId.fromBytes(cursor.getBlob(1)).valueOrThrow()),
                                commandType = null,
                                structuredKind = StructuredEntityKind.entries[cursor.getInt(2) - STRUCTURED_TYPE_OFFSET],
                                payload = PreparedCommandPayload.of(cursor.getBlob(3)).valueOrThrow(),
                                payloadHash = Hash256.fromBytes(cursor.getBlob(4)).valueOrThrow(),
                                validationState = PreparedCommandValidationState.entries[cursor.getInt(5)],
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun counts(): DomainResult<StagingCounts> = protectValue {
        access.read(bookId, databaseName) { database ->
            StagingCounts(
                database.count("staging_raw_row"),
                database.count("staging_parsed_row"),
                database.count("staging_validation_error"),
                database.count("staging_duplicate_candidate"),
                database.count("staging_prepared_command"),
            )
        }
    }

    override suspend fun destroy(): DomainResult<Unit> = protect { access.destroy(databaseName) }

    private fun requirePage(offsetExclusive: Long, limit: Int) {
        require(offsetExclusive >= 0L)
        require(limit in 1..MAX_PAGE_SIZE)
    }

    private inline fun protect(block: () -> Unit): DomainResult<Unit> = try {
        block()
        DomainResult.Success(Unit)
    } catch (_: Exception) {
        DomainResult.Failure(StagingPersistenceError.Unavailable)
    }

    private inline fun <T> protectValue(block: () -> T): DomainResult<T> = try {
        DomainResult.Success(block())
    } catch (_: Exception) {
        DomainResult.Failure(StagingPersistenceError.CorruptOrUnavailable)
    }

    private companion object {
        const val RAW_INGESTED: Int = 1
        const val PARSER_VERSION: Int = 1
        const val MAX_PAGE_SIZE: Int = 512
        const val STRUCTURED_TYPE_OFFSET: Int = 1_000
    }
}

sealed interface StagingPersistenceError : DomainError {
    data object Unavailable : StagingPersistenceError {
        override val code: String = "STAGING_DATABASE_UNAVAILABLE"
    }
    data object CorruptOrUnavailable : StagingPersistenceError {
        override val code: String = "STAGING_DATABASE_CORRUPT_OR_UNAVAILABLE"
    }
}

private object StagingBinaryCodec {
    fun encodeFields(fields: List<StagingParsedField>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(fields.size)
            fields.forEach { field ->
                output.writeSizedUtf8(field.sourceColumn)
                when (val value = field.value) {
                    is StagingValue.Text -> output.value(0, value.value)
                    is StagingValue.Integer -> output.value(1, value.value.toString())
                    is StagingValue.Decimal -> output.value(2, value.value.toPlainString())
                    is StagingValue.Date -> output.value(3, value.value.toString())
                    is StagingValue.InstantValue -> output.value(4, value.value.toString())
                    StagingValue.Empty -> output.writeByte(5)
                }
            }
        }
        bytes.toByteArray()
    }

    fun decodeFields(bytes: ByteArray): List<StagingParsedField> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val count = input.readInt()
        require(count in 0..MAX_FIELDS)
        List(count) {
            val name = input.readSizedUtf8()
            val value = when (input.readUnsignedByte()) {
                0 -> StagingValue.Text(input.readSizedUtf8())
                1 -> StagingValue.Integer(input.readSizedUtf8().toLong())
                2 -> StagingValue.Decimal(BigDecimal(input.readSizedUtf8()))
                3 -> StagingValue.Date(LocalDate.parse(input.readSizedUtf8()))
                4 -> StagingValue.InstantValue(Instant.parse(input.readSizedUtf8()))
                5 -> StagingValue.Empty
                else -> error("invalid staging field type")
            }
            StagingParsedField(name, value)
        }.also { require(input.read() == -1) }
    }

    fun encodeTransformation(value: ImportTransformation): Pair<Int, ByteArray?> = when (value) {
        ImportTransformation.Identity -> 0 to null
        is ImportTransformation.DatePattern -> 1 to value.pattern.toByteArray(Charsets.UTF_8)
        is ImportTransformation.DecimalSeparator -> 2 to value.separator.toString().toByteArray(Charsets.UTF_8)
        is ImportTransformation.ClosedValueMap -> 3 to ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(value.entries.size)
                value.entries.toSortedMap().forEach { (key, mapped) ->
                    output.writeSizedUtf8(key)
                    output.writeSizedUtf8(mapped)
                }
            }
            bytes.toByteArray()
        }
    }

    fun decodeTransformation(type: Int, bytes: ByteArray?): ImportTransformation = when (type) {
        0 -> ImportTransformation.Identity
        1 -> ImportTransformation.DatePattern(requireNotNull(bytes).toString(Charsets.UTF_8))
        2 -> ImportTransformation.DecimalSeparator(requireNotNull(bytes).toString(Charsets.UTF_8).single())
        3 -> ImportTransformation.ClosedValueMap(
            DataInputStream(ByteArrayInputStream(requireNotNull(bytes))).use { input ->
                val size = input.readInt()
                require(size in 0..MAX_FIELDS)
                buildMap(size) { repeat(size) { put(input.readSizedUtf8(), input.readSizedUtf8()) } }
                    .also { require(input.read() == -1) }
            },
        )
        else -> error("invalid staging transformation type")
    }

    private fun DataOutputStream.value(type: Int, value: String) {
        writeByte(type)
        writeSizedUtf8(value)
    }

    private fun DataOutputStream.writeSizedUtf8(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_FIELD_BYTES)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readSizedUtf8(): String {
        val size = readInt()
        require(size in 0..MAX_FIELD_BYTES)
        return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8)
    }

    private const val MAX_FIELDS: Int = 4_096
    private const val MAX_FIELD_BYTES: Int = 16 * 1024 * 1024
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun SupportSQLiteDatabase.count(table: String): Long = query("SELECT COUNT(*) FROM $table").use { cursor ->
    check(cursor.moveToFirst())
    cursor.getLong(0)
}

private fun <T> DomainResult<T>.valueOrThrow(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> error(error.code)
}
