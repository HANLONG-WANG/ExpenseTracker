@file:Suppress("TooGenericExceptionCaught")

package app.ledger.finance.data

import android.content.Context
import android.database.Cursor
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.LedgerAccessMode
import app.ledger.core.security.LedgerDatabaseOperationAccess
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.VaultCiphertext
import app.ledger.finance.application.VaultSecretApplicationPort
import app.ledger.finance.application.VaultSecretRecord
import java.time.Instant

/** SQLCipher adapter restricted to card_vault_secret and payment_card identity lookup. */
public class SecureRoomVaultSecretApplicationPort(
    private val databaseAccess: LedgerDatabaseOperationAccess,
) : VaultSecretApplicationPort {

    override suspend fun listCardIds(bookId: StableId): DomainResult<Set<StableId>> = withDatabase(bookId, LedgerAccessMode.READ) { database ->
        database.readLedger { connection ->
            connection.query(
                "SELECT pc.uid FROM card_vault_secret cvs JOIN payment_card pc ON pc.id=cvs.card_id ORDER BY pc.uid",
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(StableId.fromBytes(cursor.getBlob(0)).valueOrAbort()) } }
        }
    }

    override suspend fun read(bookId: StableId, cardId: StableId): DomainResult<VaultSecretRecord?> = withDatabase(bookId, LedgerAccessMode.READ) { database ->
        database.readLedger { connection ->
            connection.query(
                "SELECT pc.uid,cvs.holder_name_ciphertext,cvs.pan_ciphertext,cvs.expiry_ciphertext," +
                    "cvs.security_code_ciphertext,cvs.custom_fields_ciphertext,cvs.key_version,cvs.updated_at " +
                    "FROM card_vault_secret cvs JOIN payment_card pc ON pc.id=cvs.card_id WHERE pc.uid=?",
                arrayOf(cardId.bytes),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }
        }
    }

    override suspend fun save(bookId: StableId, record: VaultSecretRecord): DomainResult<Unit> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection ->
            val cardInternalId = connection.requireInternalId("payment_card", record.cardId)
            connection.execSQL(
                "INSERT INTO card_vault_secret(card_id,holder_name_ciphertext,pan_ciphertext,expiry_ciphertext," +
                    "security_code_ciphertext,custom_fields_ciphertext,key_version,updated_at) VALUES(?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(card_id) DO UPDATE SET holder_name_ciphertext=excluded.holder_name_ciphertext," +
                    "pan_ciphertext=excluded.pan_ciphertext,expiry_ciphertext=excluded.expiry_ciphertext," +
                    "security_code_ciphertext=excluded.security_code_ciphertext,custom_fields_ciphertext=excluded.custom_fields_ciphertext," +
                    "key_version=excluded.key_version,updated_at=excluded.updated_at",
                arrayOf<Any?>(
                    cardInternalId,
                    record.holderName?.copyBytes(),
                    record.primaryNumber?.copyBytes(),
                    record.expiry?.copyBytes(),
                    record.securityCode?.copyBytes(),
                    record.customFields?.copyBytes(),
                    record.keyVersion,
                    record.updatedAt.toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun delete(bookId: StableId, cardId: StableId): DomainResult<Unit> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection ->
            connection.execSQL(
                "DELETE FROM card_vault_secret WHERE card_id=(SELECT id FROM payment_card WHERE uid=?)",
                arrayOf(cardId.bytes),
            )
        }
    }

    private suspend fun <T> withDatabase(
        bookId: StableId,
        mode: LedgerAccessMode = LedgerAccessMode.WRITE,
        block: suspend (LedgerDatabase) -> T,
    ): DomainResult<T> = try {
        DomainResult.Success(databaseAccess.withCurrentDatabase(bookId, mode, block))
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    private fun Cursor.toRecord(): VaultSecretRecord = VaultSecretRecord(
        cardId = StableId.fromBytes(getBlob(CARD_ID_COLUMN)).valueOrAbort(),
        holderName = ciphertext(HOLDER_NAME_COLUMN),
        primaryNumber = ciphertext(PRIMARY_NUMBER_COLUMN),
        expiry = ciphertext(EXPIRY_COLUMN),
        securityCode = ciphertext(SECURITY_CODE_COLUMN),
        customFields = ciphertext(CUSTOM_FIELDS_COLUMN),
        keyVersion = getInt(KEY_VERSION_COLUMN),
        updatedAt = Instant.ofEpochMilli(getLong(UPDATED_AT_COLUMN)),
    )

    private fun Cursor.ciphertext(index: Int): VaultCiphertext? = if (isNull(index)) null else VaultCiphertext.copyOf(getBlob(index))

    private companion object {
        const val CARD_ID_COLUMN = 0
        const val HOLDER_NAME_COLUMN = 1
        const val PRIMARY_NUMBER_COLUMN = 2
        const val EXPIRY_COLUMN = 3
        const val SECURITY_CODE_COLUMN = 4
        const val CUSTOM_FIELDS_COLUMN = 5
        const val KEY_VERSION_COLUMN = 6
        const val UPDATED_AT_COLUMN = 7
    }
}
