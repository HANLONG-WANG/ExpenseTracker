@file:Suppress("MagicNumber")

package app.ledger.transfer.domain

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import java.time.Instant

enum class ExportContent {
    CURRENT_FILTER,
    FULL_WORKBOOK,
    REPORT,
}

enum class ExportField(val header: String, val sensitiveLocation: Boolean = false) {
    TRANSACTION_ID("transaction_id"),
    TRANSACTION_TYPE("transaction_type"),
    STATE("state"),
    OCCURRED_AT("occurred_at"),
    LOCAL_DATE("local_date"),
    TIME_ZONE("time_zone"),
    AMOUNT_MINOR("amount_minor"),
    CURRENCY("currency"),
    ORIGINAL_AMOUNT_MINOR("original_amount_minor"),
    ORIGINAL_CURRENCY("original_currency"),
    ACCOUNT("account"),
    CARD_DISPLAY_NAME("card_display_name"),
    CATEGORY("category"),
    MERCHANT("merchant"),
    PROJECT("project"),
    SETTLEMENT_ACTIVITY("settlement_activity"),
    PLACE("place"),
    NOTE("note"),
    ATTACHMENT_REFERENCES("attachment_references"),
    SOURCE("source"),
    LATITUDE_E7("latitude_e7", true),
    LONGITUDE_E7("longitude_e7", true),
    ;

    companion object {
        val defaultSelection: Set<ExportField> = entries.filterNot(ExportField::sensitiveLocation).toSet()
        val locationCoordinates: Set<ExportField> = entries.filter(ExportField::sensitiveLocation).toSet()

        init {
            val forbidden = setOf("pan", "card_number", "security_code", "cvc", "cvv", "vault", "password", "ciphertext")
            check(entries.none { field -> forbidden.any { token -> token in field.header.lowercase() } })
        }
    }
}

data class ExportFilter(
    val occurredFrom: Instant? = null,
    val occurredThrough: Instant? = null,
    val createdFrom: Instant? = null,
    val createdThrough: Instant? = null,
    val modifiedFrom: Instant? = null,
    val modifiedThrough: Instant? = null,
    val kinds: Set<Int> = emptySet(),
    val accountIds: Set<StableId> = emptySet(),
    val cardIds: Set<StableId> = emptySet(),
    val categoryIds: Set<StableId> = emptySet(),
    val merchantIds: Set<StableId> = emptySet(),
    val projectIds: Set<StableId> = emptySet(),
    val settlementActivityIds: Set<StableId> = emptySet(),
    val participantIds: Set<StableId> = emptySet(),
    val currencies: Set<String> = emptySet(),
    val statisticalNatures: Set<Int> = emptySet(),
    val lifecycleStates: Set<Int> = emptySet(),
    val sources: Set<Int> = emptySet(),
    val minimumAccountMinor: Long? = null,
    val maximumAccountMinor: Long? = null,
    val amountCurrency: String? = null,
    val centerLatitudeE7: Int? = null,
    val centerLongitudeE7: Int? = null,
    val radiusMeters: Int? = null,
    val hasAttachment: Boolean? = null,
    val isRefund: Boolean? = null,
    val hasInstallment: Boolean? = null,
    val includedInBudget: Boolean? = null,
    val generatedByRecurrence: Boolean? = null,
    val searchText: String? = null,
) {
    init {
        require(occurredFrom == null || occurredThrough == null || occurredThrough >= occurredFrom)
        require(createdFrom == null || createdThrough == null || createdThrough >= createdFrom)
        require(modifiedFrom == null || modifiedThrough == null || modifiedThrough >= modifiedFrom)
        require(kinds.all { it >= 0 } && statisticalNatures.all { it >= 0 } && lifecycleStates.all { it >= 0 } && sources.all { it >= 0 })
        require(currencies.all { it.matches(Regex("[A-Z]{3}")) } && (amountCurrency == null || amountCurrency.matches(Regex("[A-Z]{3}"))))
        require(minimumAccountMinor != null || maximumAccountMinor != null || amountCurrency == null)
        require(minimumAccountMinor == null || maximumAccountMinor == null || maximumAccountMinor >= minimumAccountMinor)
        require((centerLatitudeE7 == null) == (centerLongitudeE7 == null) && (centerLatitudeE7 == null) == (radiusMeters == null))
        require(centerLatitudeE7 == null || centerLatitudeE7 in -900_000_000..900_000_000)
        require(centerLongitudeE7 == null || centerLongitudeE7 in -1_800_000_000..1_800_000_000)
        require(radiusMeters == null || radiusMeters > 0)
        require(searchText == null || searchText.length <= 80)
    }
}

data class ExportReportSnapshot(
    val reportKey: String,
    val periodStart: String,
    val periodEndInclusive: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val localRevision: Long,
    val valuationRevision: Long?,
) {
    init {
        require(reportKey.isNotBlank() && periodStart.isNotBlank() && periodEndInclusive.isNotBlank())
        require(headers.isNotEmpty() && headers.size <= 64 && headers.distinct().size == headers.size)
        require(headers.all { it.isNotBlank() && it.length <= 256 })
        require(rows.size <= 100_000)
        require(rows.all { it.size == headers.size })
        require(rows.flatten().all { it.length <= 8_192 })
        require(localRevision >= 0L && (valuationRevision == null || valuationRevision >= 0L))
    }
}

data class ExportDescriptor(
    val content: ExportContent,
    val format: ExportFormat,
    val fileName: String,
    val fields: Set<ExportField> = ExportField.defaultSelection,
    val includeLocationCoordinates: Boolean = false,
    val filterSummary: String = "All current transactions",
    val filter: ExportFilter = ExportFilter(),
    val report: ExportReportSnapshot? = null,
    val overwriteConfirmed: Boolean = false,
) {
    init {
        require(fileName.isNotBlank() && fileName.length <= 180 && '/' !in fileName && '\\' !in fileName)
        require(filterSummary.isNotBlank() && filterSummary.length <= 500)
        require(fields.isNotEmpty())
        require(includeLocationCoordinates == ExportField.locationCoordinates.all(fields::contains))
        require((content == ExportContent.REPORT) == (report != null))
        require(format != ExportFormat.PORTABLE_BACKUP)
        require(content != ExportContent.FULL_WORKBOOK || format == ExportFormat.XLSX)
        require(content != ExportContent.CURRENT_FILTER || format == ExportFormat.CSV)
    }
}

data class ExportMetadata(
    val schemaVersion: Int,
    val applicationVersion: String,
    val generatedAt: Instant,
    val content: ExportContent,
    val filterSummary: String,
    val localRevision: Long,
    val valuationRevision: Long?,
    val disclaimer: String = "This is an explanatory data export, not a complete backup.",
) {
    init {
        require(schemaVersion > 0 && applicationVersion.isNotBlank() && localRevision >= 0L)
        require(valuationRevision == null || valuationRevision >= 0L)
        require(disclaimer.contains("not a complete backup", ignoreCase = true))
    }
}

data class ExportResult(
    val rows: Long,
    val sheetsOrPages: Int,
    val bytesWritten: Long,
    val mimeType: String,
) {
    init {
        require(rows >= 0L && sheetsOrPages > 0 && bytesWritten >= 0L && mimeType.isNotBlank())
    }
}

data class ExportTablePage(
    val headers: List<String>,
    val rows: List<List<String>>,
    val nextKey: Long?,
) {
    init {
        require(headers.isNotEmpty() && headers.distinct().size == headers.size)
        require(rows.all { it.size == headers.size })
        require(nextKey == null || nextKey >= 0L)
    }
}

interface ExportTabularSource {
    suspend fun metadata(): DomainResult<ExportMetadata>

    suspend fun sheetNames(): DomainResult<List<String>>

    suspend fun page(sheetName: String, afterKey: Long?, limit: Int): DomainResult<ExportTablePage>
}

fun interface ExportProgressObserver {
    suspend fun onProgress(rowsWritten: Long)
}

sealed interface ExportFailure : DomainError {
    data object Cancelled : ExportFailure {
        override val code: String = "EXPORT_CANCELLED"
    }
    data object InsufficientSpace : ExportFailure {
        override val code: String = "EXPORT_INSUFFICIENT_SPACE"
    }
    data object PermissionRevoked : ExportFailure {
        override val code: String = "EXPORT_SAF_PERMISSION_REVOKED"
    }
    data object NameConflict : ExportFailure {
        override val code: String = "EXPORT_NAME_CONFLICT"
    }
    data object DestinationUnavailable : ExportFailure {
        override val code: String = "EXPORT_DESTINATION_UNAVAILABLE"
    }
    data object CorruptCheckpoint : ExportFailure {
        override val code: String = "EXPORT_CORRUPT_CHECKPOINT"
    }
    data object SourceUnavailable : ExportFailure {
        override val code: String = "EXPORT_SOURCE_UNAVAILABLE"
    }
    data object SensitiveFieldRejected : ExportFailure {
        override val code: String = "EXPORT_SENSITIVE_FIELD_REJECTED"
    }
}
