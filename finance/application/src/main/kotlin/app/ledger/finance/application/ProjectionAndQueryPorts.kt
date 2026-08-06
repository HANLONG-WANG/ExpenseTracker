package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.finance.domain.CurrentTransactionProjection
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionId

data class CurrentTransactionCursor(
    val occurredAtEpochMillis: Long,
    val transactionId: TransactionId,
)

data class CurrentTransactionPage(
    val items: List<CurrentTransactionProjection>,
    val nextCursor: CurrentTransactionCursor?,
)

fun interface CurrentTransactionQueryPort {
    suspend fun page(
        filter: TransactionFilter,
        limit: Int,
        cursor: CurrentTransactionCursor?,
    ): DomainResult<CurrentTransactionPage>
}

data class GeoTransactionCandidate(
    val transactionId: TransactionId,
    val distanceMeters: Int,
)

fun interface GeoTransactionQueryPort {
    suspend fun withinRadius(
        filter: TransactionFilter,
        limit: Int,
    ): DomainResult<List<GeoTransactionCandidate>>
}

enum class ProjectionFamily {
    CURRENT_TRANSACTION,
    ACCOUNT_BALANCE,
    ACCOUNT_DAILY,
    REFUND,
    BUDGET,
    PROJECT,
    GOAL,
    CREDIT,
    INSTALLMENT,
    LOAN,
    SETTLEMENT,
    SEARCH,
    GEOGRAPHY,
    ANALYTICS,
    WIDGET,
}

data class ProjectionVersion(
    val localRevision: LocalRevision,
    val valuationRevision: LocalRevision,
)

data class ProjectionAuditResult(
    val liveHash: String,
    val rebuiltHash: String,
    val version: ProjectionVersion,
    val mismatchedFamilies: Set<ProjectionFamily>,
) {
    val isConsistent: Boolean = liveHash == rebuiltHash && mismatchedFamilies.isEmpty()
}

enum class StartupDisposition {
    READY,
    MAINTENANCE_REQUIRED,
    RECOVERY_REQUIRED,
}

data class StartupIntegrityResult(
    val disposition: StartupDisposition,
    val reasonCodes: Set<String>,
)

interface ProjectionMaintenancePort {
    suspend fun audit(): DomainResult<ProjectionAuditResult>

    suspend fun rebuild(): DomainResult<ProjectionAuditResult>

    suspend fun startupCheck(): DomainResult<StartupIntegrityResult>

    suspend fun enterMaintenance(reasonCode: String): DomainResult<Unit>
}
