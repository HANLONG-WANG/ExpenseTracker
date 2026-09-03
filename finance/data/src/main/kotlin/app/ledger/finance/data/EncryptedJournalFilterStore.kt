@file:Suppress("TooManyFunctions")

package app.ledger.finance.data

import android.content.Context
import android.util.AtomicFile
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.LedgerSecureSettings
import app.ledger.finance.application.JournalSavedFilter
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.GeoPoint
import app.ledger.finance.domain.GeoRadiusFilter
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.SettlementActivityId
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionAmountRange
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Private, AEAD-protected persistence for search/filter text that must never enter SavedState. */
internal class EncryptedJournalFilterStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "journal_filters").also { require(it.exists() || it.mkdirs()) }

    fun read(bookId: StableId, secureSettings: LedgerSecureSettings): List<JournalSavedFilter> {
        val atomic = AtomicFile(file(bookId))
        if (!atomic.baseFile.exists()) return emptyList()
        val encrypted = atomic.readFully()
        val associatedData = associatedData(bookId)
        val clear = try {
            secureSettings.decryptSecureSettings(encrypted, associatedData)
        } finally {
            encrypted.fill(0)
            associatedData.fill(0)
        }
        return try {
            decode(clear)
        } finally {
            clear.fill(0)
        }
    }

    fun write(
        bookId: StableId,
        secureSettings: LedgerSecureSettings,
        filters: List<JournalSavedFilter>,
    ) {
        require(filters.size <= MAX_PRESETS)
        val clear = encode(filters)
        val associatedData = associatedData(bookId)
        val encrypted = try {
            secureSettings.encryptSecureSettings(clear, associatedData)
        } finally {
            clear.fill(0)
            associatedData.fill(0)
        }
        val atomic = AtomicFile(file(bookId))
        val output = atomic.startWrite()
        var finished = false
        try {
            output.write(encrypted)
            output.fd.sync()
            atomic.finishWrite(output)
            finished = true
        } finally {
            if (!finished) atomic.failWrite(output)
            encrypted.fill(0)
        }
    }

    private fun encode(filters: List<JournalSavedFilter>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(filters.size)
            filters.sortedBy(JournalSavedFilter::sortOrder).forEach { preset ->
                out.write(preset.id.bytes)
                out.writeBoundedUtf(preset.name, MAX_NAME_BYTES)
                out.writeBoundedUtf(preset.naturalLanguageSummary, MAX_SUMMARY_BYTES)
                out.writeBoolean(preset.isDefault)
                out.writeInt(preset.sortOrder)
                out.writeFilter(preset.filter)
            }
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): List<JournalSavedFilter> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == MAGIC)
        require(input.readInt() == VERSION)
        val count = input.readInt().also { require(it in 0..MAX_PRESETS) }
        List(count) {
            JournalSavedFilter(
                id = StableId.fromBytes(ByteArray(STABLE_ID_BYTES).also(input::readFully)).valueOrAbort(),
                name = input.readBoundedUtf(MAX_NAME_BYTES),
                naturalLanguageSummary = input.readBoundedUtf(MAX_SUMMARY_BYTES),
                isDefault = input.readBoolean(),
                sortOrder = input.readInt(),
                filter = input.readFilter(),
            )
        }.also { decoded ->
            require(input.read() == -1)
            require(decoded.map(JournalSavedFilter::id).toSet().size == decoded.size)
            require(decoded.count(JournalSavedFilter::isDefault) <= 1)
        }
    }

    private fun DataOutputStream.writeFilter(value: TransactionFilter) {
        writeInstant(value.occurredFrom)
        writeInstant(value.occurredThrough)
        writeInstant(value.createdFrom)
        writeInstant(value.createdThrough)
        writeInstant(value.modifiedFrom)
        writeInstant(value.modifiedThrough)
        writeEnums(value.kinds)
        writeIds(value.accountIds.map { it.value })
        writeIds(value.cardIds.map { it.value })
        writeIds(value.categoryIds.map { it.value })
        writeIds(value.merchantIds.map { it.value })
        writeIds(value.projectIds.map { it.value })
        writeIds(value.settlementActivityIds.map { it.value })
        writeIds(value.participantIds.map { it.value })
        writeStrings(value.currencies.map { it.value })
        writeEnums(value.statisticalNatures)
        writeBoolean(value.amountRange != null)
        value.amountRange?.let { range ->
            writeNullableLong(range.minimumAccountMinor)
            writeNullableLong(range.maximumAccountMinor)
            writeNullableString(range.currency?.value)
        }
        writeBoolean(value.geoRadius != null)
        value.geoRadius?.let { geo ->
            writeInt(geo.center.latitudeE7)
            writeInt(geo.center.longitudeE7)
            writeInt(geo.radiusMeters)
        }
        writeNullableBoolean(value.hasAttachment)
        writeNullableBoolean(value.isRefund)
        writeNullableBoolean(value.hasInstallment)
        writeNullableBoolean(value.includedInBudget)
        writeNullableBoolean(value.generatedByRecurrence)
        writeEnums(value.sources)
        writeEnums(value.lifecycleStates)
        writeNullableString(value.searchText)
    }

    private fun DataInputStream.readFilter(): TransactionFilter = TransactionFilter(
        occurredFrom = readInstant(), occurredThrough = readInstant(), createdFrom = readInstant(), createdThrough = readInstant(),
        modifiedFrom = readInstant(), modifiedThrough = readInstant(), kinds = readEnums<TransactionKind>().toSet(),
        accountIds = readIds().map(::UserAccountId).toSet(), cardIds = readIds().map(::PaymentCardId).toSet(),
        categoryIds = readIds().map(::CategoryId).toSet(), merchantIds = readIds().map(::MerchantId).toSet(),
        projectIds = readIds().map(::ProjectId).toSet(), settlementActivityIds = readIds().map(::SettlementActivityId).toSet(),
        participantIds = readIds().map(::ParticipantId).toSet(), currencies = readStrings().map { CurrencyCode.parse(it).valueOrAbort() }.toSet(),
        statisticalNatures = readEnums<StatisticalNature>().toSet(),
        amountRange = if (readBoolean()) {
            TransactionAmountRange(readNullableLong(), readNullableLong(), readNullableString()?.let { CurrencyCode.parse(it).valueOrAbort() })
        } else {
            null
        },
        geoRadius = if (readBoolean()) GeoRadiusFilter(GeoPoint.create(readInt(), readInt()).valueOrAbort(), readInt()) else null,
        hasAttachment = readNullableBoolean(), isRefund = readNullableBoolean(), hasInstallment = readNullableBoolean(),
        includedInBudget = readNullableBoolean(), generatedByRecurrence = readNullableBoolean(), sources = readEnums<TransactionSource>().toSet(),
        lifecycleStates = readEnums<TransactionLifecycleState>().toSet(), searchText = readNullableString(),
    )

    private fun DataOutputStream.writeInstant(value: Instant?) = writeNullableLong(value?.toEpochMilli())
    private fun DataInputStream.readInstant(): Instant? = readNullableLong()?.let(Instant::ofEpochMilli)
    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        value?.let(::writeLong)
    }
    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null
    private fun DataOutputStream.writeNullableBoolean(value: Boolean?) {
        writeByte(
            if (value == null) {
                -1
            } else if (value) {
                1
            } else {
                0
            },
        )
    }
    private fun DataInputStream.readNullableBoolean(): Boolean? = when (val value = readByte().toInt()) {
        -1 -> null
        0 -> false
        1 -> true
        else -> error("invalid boolean $value")
    }
    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        value?.let { writeBoundedUtf(it, MAX_FILTER_TEXT_BYTES) }
    }
    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readBoundedUtf(MAX_FILTER_TEXT_BYTES) else null
    private fun DataOutputStream.writeIds(values: Collection<StableId>) {
        writeInt(values.size)
        values.sorted().forEach { write(it.bytes) }
    }
    private fun DataInputStream.readIds(): List<StableId> = List(readCount()) { StableId.fromBytes(ByteArray(STABLE_ID_BYTES).also(::readFully)).valueOrAbort() }
    private fun DataOutputStream.writeStrings(values: Collection<String>) {
        writeInt(values.size)
        values.sorted().forEach { writeBoundedUtf(it, MAX_FILTER_TEXT_BYTES) }
    }
    private fun DataInputStream.readStrings(): List<String> = List(readCount()) { readBoundedUtf(MAX_FILTER_TEXT_BYTES) }
    private inline fun <reified T : Enum<T>> DataOutputStream.writeEnums(values: Collection<T>) {
        writeInt(values.size)
        values.sortedBy(Enum<*>::ordinal).forEach { writeInt(it.ordinal) }
    }
    private inline fun <reified T : Enum<T>> DataInputStream.readEnums(): List<T> = List(readCount()) { enumValues<T>()[readInt()] }
    private fun DataInputStream.readCount(): Int = readInt().also { require(it in 0..MAX_FILTER_VALUES) }
    private fun DataOutputStream.writeBoundedUtf(value: String, maximum: Int) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= maximum)
        writeInt(encoded.size)
        write(encoded)
    }
    private fun DataInputStream.readBoundedUtf(maximum: Int): String {
        val size = readInt().also { require(it in 0..maximum) }
        return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun file(bookId: StableId): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(bookId.bytes).joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.aead")
    }

    private fun associatedData(bookId: StableId): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.write("journal-filter-presets".toByteArray())
            out.write(bookId.bytes)
        }
        bytes.toByteArray()
    }

    private companion object {
        const val MAGIC = 0x4a465052
        const val VERSION = 1
        const val MAX_PRESETS = 100
        const val MAX_FILTER_VALUES = 10_000
        const val MAX_NAME_BYTES = 160
        const val MAX_SUMMARY_BYTES = 2_000
        const val MAX_FILTER_TEXT_BYTES = 2_000
        const val STABLE_ID_BYTES = 16
    }
}
