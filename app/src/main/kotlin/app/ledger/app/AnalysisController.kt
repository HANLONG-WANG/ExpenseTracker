@file:Suppress("LongParameterList", "ReturnCount", "TooManyFunctions")

package app.ledger.app

import app.ledger.analytics.domain.AnalyticsApplicationPort
import app.ledger.analytics.domain.AnalyticsError
import app.ledger.analytics.domain.DrilldownQueryId
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportCatalog
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportExportFormat
import app.ledger.analytics.domain.ReportExportPayload
import app.ledger.analytics.domain.ReportKey
import app.ledger.analytics.domain.ReportPeriod
import app.ledger.analytics.domain.ReportSpec
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.feature.analysis.AnalysisFeatureState
import app.ledger.feature.analysis.AnalysisLoadState
import app.ledger.feature.analysis.AnalysisPolicy
import app.ledger.feature.analysis.AnalysisPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** Owns P25 presentation state in memory. Report specs and drilldown selections never enter SavedState or routes. */
internal class AnalysisController(private val application: AnalyticsApplicationPort) {
    private val mutableState = MutableStateFlow<AnalysisLoadState>(AnalysisLoadState.Loading)
    val state: StateFlow<AnalysisLoadState> = mutableState.asStateFlow()

    private var bookId: StableId? = null
    private var baseCurrency: CurrencyCode? = null
    private var period: ReportPeriod? = null
    private var screenId: String = "ANA-001"
    private var report: FixedReport? = null
    private var queryId: DrilldownQueryId? = null
    private var appliedSpec: ReportSpec? = null
    private var appliedSpecReport: FixedReport? = null
    private var preparedExport: ReportExportPayload? = null

    suspend fun open(
        bookId: StableId,
        baseCurrency: CurrencyCode,
        today: LocalDate,
        screenId: String,
        reportKey: String?,
        queryId: DrilldownQueryId?,
    ) {
        if (this.bookId != bookId) {
            this.bookId = bookId
            this.period = AnalysisPolicy.initialPeriod(today)
            appliedSpec = null
            appliedSpecReport = null
            preparedExport = null
        }
        this.baseCurrency = baseCurrency
        this.screenId = screenId
        this.report = reportKey?.toFixedReportOrNull()
        this.queryId = queryId
        loadCurrent()
    }

    suspend fun reload() = loadCurrent()

    suspend fun previousPeriod() {
        period = period?.let(AnalysisPolicy::previousPeriod)
        loadCurrent()
    }

    suspend fun nextPeriod() {
        period = period?.let(AnalysisPolicy::nextPeriod)
        loadCurrent()
    }

    fun cycleMeasure() = editDraft(AnalysisPolicy::cycleMeasure)

    fun cycleDimension() = editDraft(AnalysisPolicy::cycleDimension)

    fun cycleGranularity() = editDraft(AnalysisPolicy::cycleGranularity)

    fun cycleComparison() = editDraft(AnalysisPolicy::cycleComparison)

    fun applyFilter(): Boolean {
        val content = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return false
        val fixed = content.fixedReport ?: return false
        val spec = content.draftSpec ?: return false
        appliedSpec = spec
        appliedSpecReport = fixed
        return true
    }

    suspend fun loadNextDrilldown() {
        val id = queryId ?: return
        val current = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return
        val currentPage = current.drilldown ?: return
        val cursor = currentPage.nextCursor ?: return
        val result = application.drillDown(requireBookId(), id, cursor, DRILLDOWN_PAGE_SIZE)
        if (result is DomainResult.Success) {
            mutableState.value = AnalysisLoadState.Content(
                current.copy(
                    drilldown = currentPage.copy(
                        rows = currentPage.rows + result.value.rows,
                        nextCursor = result.value.nextCursor,
                    ),
                ),
            )
        }
    }

    suspend fun runIntegrity(repair: Boolean = false) {
        val current = (mutableState.value as? AnalysisLoadState.Content)?.state
        mutableState.value = AnalysisLoadState.Content(baseState("ANA-015", AnalysisPresentation.RUNNING).copy(integrity = current?.integrity))
        val result = if (repair) application.repairAnalyticsProjections(requireBookId()) else application.integrity(requireBookId())
        mutableState.value = when (result) {
            is DomainResult.Success -> AnalysisLoadState.Content(
                baseState("ANA-015", AnalysisPolicy.integrityPresentation(result.value)).copy(integrity = result.value),
            )
            is DomainResult.Failure -> AnalysisLoadState.Failure("ANA-015", result.error.code)
        }
    }

    fun toggleTechnicalDetails() {
        val content = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return
        mutableState.value = AnalysisLoadState.Content(content.copy(technicalDetailsExpanded = !content.technicalDetailsExpanded))
    }

    fun prepareExport(format: ReportExportFormat = ReportExportFormat.CSV): Boolean {
        val content = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return false
        val execution = content.execution as? ReportExecution.Content ?: return false
        val result = application.prepareExport(execution, content.period, format)
        preparedExport = (result as? DomainResult.Success)?.value
        return preparedExport != null
    }

    internal fun preparedExportForTransfer(): ReportExportPayload? = preparedExport

    private suspend fun loadCurrent() {
        mutableState.value = AnalysisLoadState.Loading
        mutableState.value = when (screenId) {
            "ANA-001" -> loadOverview()
            "ANA-002" -> AnalysisLoadState.Content(baseState(screenId, AnalysisPresentation.CONTENT))
            "ANA-003" -> loadReport()
            "ANA-004" -> loadFilter()
            "ANA-005" -> loadDrilldown()
            "ANA-015" -> AnalysisLoadState.Content(baseState(screenId, AnalysisPresentation.NOT_RUN))
            else -> AnalysisLoadState.Failure(screenId, "ANALYSIS_SCREEN_UNKNOWN")
        }
    }

    private suspend fun loadOverview(): AnalysisLoadState = when (val result = application.overview(requireBookId(), requirePeriod())) {
        is DomainResult.Success -> AnalysisLoadState.Content(
            baseState(
                "ANA-001",
                if (result.value.transactionCount == 0L) AnalysisPresentation.NO_DATA else AnalysisPresentation.CONTENT,
            ).copy(overview = result.value),
        )
        is DomainResult.Failure -> AnalysisLoadState.Failure("ANA-001", result.error.code)
    }

    private suspend fun loadReport(): AnalysisLoadState {
        val fixed = report ?: return AnalysisLoadState.Failure("ANA-003", "REPORT_KEY_INVALID")
        val override = appliedSpec.takeIf { appliedSpecReport == fixed }
        val result = if (override == null) {
            application.executeFixed(requireBookId(), fixed, requirePeriod())
        } else {
            application.executeCustom(
                requireBookId(),
                override,
                requirePeriod(),
                FixedReportCatalog.definition(fixed).defaultVisualization,
            )
        }
        return when (result) {
            is DomainResult.Success -> {
                val execution = result.value.attachFixedReport(fixed)
                AnalysisLoadState.Content(
                    baseState("ANA-003", AnalysisPolicy.reportPresentation(execution)).copy(
                        fixedReport = fixed,
                        execution = execution,
                    ),
                )
            }
            is DomainResult.Failure -> AnalysisLoadState.Failure("ANA-003", result.error.code)
        }
    }

    private fun loadFilter(): AnalysisLoadState {
        val fixed = report ?: return AnalysisLoadState.Failure("ANA-004", "REPORT_KEY_INVALID")
        val spec = appliedSpec.takeIf { appliedSpecReport == fixed } ?: FixedReportCatalog.definition(fixed).spec
        return AnalysisLoadState.Content(
            baseState("ANA-004", AnalysisPresentation.EDITING).copy(fixedReport = fixed, draftSpec = spec),
        )
    }

    private suspend fun loadDrilldown(): AnalysisLoadState {
        val id = queryId ?: return AnalysisLoadState.Content(baseState("ANA-005", AnalysisPresentation.EXPIRED_QUERY))
        return when (val result = application.drillDown(requireBookId(), id, null, DRILLDOWN_PAGE_SIZE)) {
            is DomainResult.Success -> {
                AnalysisLoadState.Content(
                    baseState(
                        "ANA-005",
                        if (result.value.rows.isEmpty()) AnalysisPresentation.EMPTY else AnalysisPresentation.CONTENT,
                    ).copy(drilldown = result.value),
                )
            }
            is DomainResult.Failure -> AnalysisLoadState.Content(
                baseState(
                    "ANA-005",
                    if (result.error == AnalyticsError.ExpiredDrilldown) AnalysisPresentation.EXPIRED_QUERY else AnalysisPresentation.ERROR,
                ).copy(failureCode = result.error.code),
            )
        }
    }

    private fun editDraft(transform: (ReportSpec) -> ReportSpec) {
        val content = (mutableState.value as? AnalysisLoadState.Content)?.state ?: return
        val draft = content.draftSpec ?: return
        mutableState.value = AnalysisLoadState.Content(content.copy(draftSpec = transform(draft), presentation = AnalysisPresentation.EDITING))
    }

    private fun baseState(screen: String, presentation: AnalysisPresentation): AnalysisFeatureState = AnalysisFeatureState(
        screen,
        presentation,
        requirePeriod(),
        application.fixedReports(),
        requireNotNull(baseCurrency),
    )

    private fun String.toFixedReportOrNull(): FixedReport? = runCatching {
        FixedReportCatalog.definition(ReportKey(this))?.report
    }.getOrNull()

    private fun ReportExecution.attachFixedReport(fixed: FixedReport): ReportExecution = when (this) {
        is ReportExecution.Content -> copy(fixedReport = fixed)
        is ReportExecution.Empty -> copy(fixedReport = fixed)
        is ReportExecution.StaleProjection -> this
    }

    private fun requireBookId(): StableId = requireNotNull(bookId)

    private fun requirePeriod(): ReportPeriod = requireNotNull(period)

    private companion object {
        const val DRILLDOWN_PAGE_SIZE = 50
    }
}
