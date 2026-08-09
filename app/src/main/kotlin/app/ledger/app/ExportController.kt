@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package app.ledger.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecureTransferHandleStore
import app.ledger.feature.transfer.ExportDestinationPresentation
import app.ledger.feature.transfer.ExportExecutionPresentation
import app.ledger.feature.transfer.ExportFlowUiState
import app.ledger.transfer.data.SqlCipherBackgroundOperationRepository
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportDescriptor
import app.ledger.transfer.domain.ExportField
import app.ledger.transfer.domain.ExportFilter
import app.ledger.transfer.domain.ExportFormat
import app.ledger.transfer.domain.ExportReportSnapshot
import app.ledger.transfer.domain.OperationParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ExportController(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val runtime: AppRuntimeSources,
) {
    private val applicationContext = context.applicationContext
    private val mutableState = MutableStateFlow(ExportFlowUiState())
    val state: StateFlow<ExportFlowUiState> = mutableState.asStateFlow()
    private var bookId: StableId? = null
    private var filter = ExportFilter()
    private var report: ExportReportSnapshot? = null
    private var operationId: BackgroundOperationId? = null
    private var destinationHandleId: StableId? = null
    private var destinationTreeUri: Uri? = null
    private var publishedUri: Uri? = null
    private var overwriteConfirmed = false

    fun currentOperationId(): StableId? = operationId?.value

    fun beginCurrentFilter(activeBookId: StableId, currentFilter: ExportFilter, summary: String) {
        reset(activeBookId)
        filter = currentFilter
        mutableState.value = baseState(
            ExportContent.CURRENT_FILTER,
            ExportFormat.CSV,
            summary,
            "transactions.csv",
        )
    }

    fun beginFullWorkbook(activeBookId: StableId) {
        reset(activeBookId)
        mutableState.value = baseState(
            ExportContent.FULL_WORKBOOK,
            ExportFormat.XLSX,
            "Complete business-data workbook",
            "ledger-data.xlsx",
        )
    }

    fun beginReport(activeBookId: StableId, snapshot: ExportReportSnapshot, preferredFormat: ExportFormat) {
        reset(activeBookId)
        report = snapshot
        val format = preferredFormat.takeIf { it in REPORT_FORMATS } ?: ExportFormat.PDF
        mutableState.value = baseState(
            ExportContent.REPORT,
            format,
            "${snapshot.reportKey}: ${snapshot.periodStart} — ${snapshot.periodEndInclusive}",
            "report.${format.extension()}",
        ).copy(availableContents = setOf(ExportContent.REPORT))
    }

    fun selectContent(content: ExportContent) {
        if (content !in mutableState.value.availableContents) return
        if (content == ExportContent.REPORT && report == null) return
        val format = when (content) {
            ExportContent.CURRENT_FILTER -> ExportFormat.CSV
            ExportContent.FULL_WORKBOOK -> ExportFormat.XLSX
            ExportContent.REPORT -> mutableState.value.format.takeIf { it in REPORT_FORMATS } ?: ExportFormat.PDF
        }
        mutableState.value = mutableState.value.copy(
            content = content,
            format = format,
            fileName = defaultName(content, format),
        )
    }

    fun selectFormat(format: ExportFormat) {
        val allowed = when (mutableState.value.content) {
            ExportContent.CURRENT_FILTER -> setOf(ExportFormat.CSV)
            ExportContent.FULL_WORKBOOK -> setOf(ExportFormat.XLSX)
            ExportContent.REPORT -> REPORT_FORMATS
        }
        if (format !in allowed) return
        mutableState.value = mutableState.value.copy(format = format, fileName = replaceExtension(mutableState.value.fileName, format.extension()))
    }

    fun next(): String {
        val next = when (mutableState.value.screenId) {
            "EXP-001" -> "EXP-002"
            "EXP-002" -> "EXP-003"
            else -> mutableState.value.screenId
        }
        mutableState.value = mutableState.value.copy(screenId = next)
        return next
    }

    fun setScreen(screenId: String) {
        if (screenId in setOf("EXP-001", "EXP-002", "EXP-003", "EXP-004")) mutableState.value = mutableState.value.copy(screenId = screenId)
    }

    fun toggleField(field: ExportField) {
        if (field.sensitiveLocation) return
        val current = mutableState.value.selectedFields
        val updated = if (field in current) current - field else current + field
        if (updated.isNotEmpty()) mutableState.value = mutableState.value.copy(selectedFields = updated)
    }

    fun setCoordinates(enabled: Boolean) {
        val fields = if (enabled) mutableState.value.selectedFields + ExportField.locationCoordinates else mutableState.value.selectedFields - ExportField.locationCoordinates
        mutableState.value = mutableState.value.copy(selectedFields = fields, includeLocationCoordinates = enabled)
    }

    fun changeFileName(value: String) {
        val clean = value.filterNot { it == '/' || it == '\\' || it.code < 0x20 }.take(180)
        mutableState.value = mutableState.value.copy(fileName = clean)
    }

    suspend fun selectDestination(uri: Uri): Boolean {
        val activeBook = bookId ?: return false
        if (mutableState.value.fileName.isBlank()) return false
        return try {
            applicationContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val root = DocumentFile.fromTreeUri(applicationContext, uri)
            if (root == null || !root.exists()) {
                mutableState.value = mutableState.value.copy(destinationPresentation = ExportDestinationPresentation.PERMISSION_REVOKED)
                false
            } else {
                destinationTreeUri = uri
                mutableState.value = mutableState.value.copy(destinationLabel = uri.authority ?: "Document provider")
                if (root.findFile(normalizedFileName()) != null && !overwriteConfirmed) {
                    mutableState.value = mutableState.value.copy(destinationPresentation = ExportDestinationPresentation.NAME_CONFLICT)
                    false
                } else {
                    launch(activeBook, uri)
                    true
                }
            }
        } catch (_: SecurityException) {
            mutableState.value = mutableState.value.copy(destinationPresentation = ExportDestinationPresentation.PERMISSION_REVOKED)
            false
        }
    }

    suspend fun confirmOverwrite(): Boolean {
        overwriteConfirmed = true
        val uri = destinationTreeUri ?: return false
        mutableState.value = mutableState.value.copy(destinationPresentation = ExportDestinationPresentation.CONTENT)
        return selectDestination(uri)
    }

    fun cancel() {
        operationId?.value?.let(ExportRunControlRegistry::cancel)
        mutableState.value = mutableState.value.copy(executionPresentation = ExportExecutionPresentation.CANCEL_REQUESTED)
    }

    suspend fun retry(): Boolean {
        val activeBook = bookId ?: return false
        val uri = destinationTreeUri ?: return false
        overwriteConfirmed = true
        launch(activeBook, uri)
        return true
    }

    suspend fun awaitCurrent() {
        val operation = operationId ?: return
        val activeBook = bookId ?: return
        val handle = destinationHandleId ?: return
        await(operation, activeBook, handle)
    }

    fun open(): Boolean = launchExternal(
        Intent(Intent.ACTION_VIEW).apply {
            val uri = publishedUri ?: return false
            setDataAndType(uri, mutableState.value.format.mimeType())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(applicationContext.contentResolver, "export", uri)
        },
    )

    fun share(): Boolean = launchExternal(
        Intent(Intent.ACTION_SEND).apply {
            val uri = publishedUri ?: return false
            type = mutableState.value.format.mimeType()
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(applicationContext.contentResolver, "export", uri)
        },
    )

    fun viewLocation(): Boolean = launchExternal(
        Intent(Intent.ACTION_VIEW).apply {
            data = destinationTreeUri ?: return false
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        },
    )

    private suspend fun launch(activeBook: StableId, treeUri: Uri) {
        val operation = BackgroundOperationId(runtime.stableIds.nextStableId())
        val handle = runtime.stableIds.nextStableId()
        operationId = operation
        destinationHandleId = handle
        publishedUri = null
        val descriptor = descriptor()
        SecureTransferHandleStore(applicationContext, keyProvider).save(activeBook, handle, treeUri.toString())
        operations(activeBook).save(
            BackgroundOperation.queued(
                operation,
                BackgroundOperationType.EXPORT,
                runtime.clock.now(),
                OperationParameters.Export(handle, descriptor),
            ),
        ).requireSuccess()
        mutableState.value = mutableState.value.copy(
            screenId = "EXP-004",
            executionPresentation = ExportExecutionPresentation.RUNNING,
            processedRows = 0L,
            totalRows = report?.rows?.size?.toLong(),
            failureCode = null,
            externalApplicationUnavailable = false,
            temporaryCleanupComplete = false,
        )
        ExportWorkScheduler.enqueue(applicationContext, operation.value, isRemoteProvider(treeUri))
    }

    private suspend fun await(operationId: BackgroundOperationId, activeBook: StableId, handle: StableId) {
        val repository = operations(activeBook)
        while (true) {
            val operation = (repository.get(operationId) as? DomainResult.Success)?.value
            if (operation == null) {
                mutableState.value = mutableState.value.copy(
                    executionPresentation = ExportExecutionPresentation.FAILED,
                    failureCode = "EXPORT_OPERATION_MISSING",
                )
                return
            }
            mutableState.value = mutableState.value.copy(processedRows = operation.progress.current, totalRows = operation.progress.total)
            when (operation.state) {
                BackgroundOperationState.SUCCEEDED -> {
                    val persisted = runCatching { SecureTransferHandleStore(applicationContext, keyProvider).read(activeBook, handle) }.getOrNull()
                    publishedUri = persisted?.substringAfter('\n', "")?.takeIf(String::isNotBlank)?.let(Uri::parse)
                    mutableState.value = mutableState.value.copy(
                        executionPresentation = ExportExecutionPresentation.SUCCEEDED,
                        canOpen = publishedUri != null,
                        canShare = publishedUri != null,
                        canViewLocation = destinationTreeUri != null,
                        temporaryCleanupComplete = true,
                    )
                    return
                }
                BackgroundOperationState.FAILED_FINAL, BackgroundOperationState.FAILED_RETRYABLE -> {
                    mutableState.value = mutableState.value.copy(
                        executionPresentation = ExportExecutionPresentation.FAILED,
                        failureCode = operation.errorCode,
                        temporaryCleanupComplete = true,
                    )
                    return
                }
                BackgroundOperationState.CANCEL_REQUESTED, BackgroundOperationState.ROLLING_BACK -> {
                    mutableState.value = mutableState.value.copy(executionPresentation = ExportExecutionPresentation.CANCEL_REQUESTED)
                }
                else -> Unit
            }
            delay(POLL_MILLIS)
        }
    }

    private fun descriptor(): ExportDescriptor = ExportDescriptor(
        content = mutableState.value.content,
        format = mutableState.value.format,
        fileName = normalizedFileName(),
        fields = mutableState.value.selectedFields,
        includeLocationCoordinates = mutableState.value.includeLocationCoordinates,
        filterSummary = mutableState.value.filterSummary,
        filter = filter,
        report = report.takeIf { mutableState.value.content == ExportContent.REPORT },
        overwriteConfirmed = overwriteConfirmed,
    )

    private fun normalizedFileName(): String = replaceExtension(mutableState.value.fileName.trim(), mutableState.value.format.extension())

    private fun reset(activeBookId: StableId) {
        bookId = activeBookId
        report = null
        filter = ExportFilter()
        operationId = BackgroundOperationId(runtime.stableIds.nextStableId())
        destinationHandleId = null
        destinationTreeUri = null
        publishedUri = null
        overwriteConfirmed = false
    }

    private fun baseState(content: ExportContent, format: ExportFormat, summary: String, name: String) = ExportFlowUiState(
        content = content,
        format = format,
        filterSummary = summary,
        fileName = name,
        workbookSheets = WORKBOOK_SHEETS,
    )

    private fun launchExternal(intent: Intent): Boolean {
        val resolved = applicationContext.packageManager.resolveActivity(intent, 0) != null
        if (resolved) applicationContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        mutableState.value = mutableState.value.copy(externalApplicationUnavailable = !resolved)
        return resolved
    }

    private fun operations(activeBook: StableId) = SqlCipherBackgroundOperationRepository(
        activeBook,
        SecurePrimaryLedgerAccess(applicationContext, keyProvider),
    )

    private fun <T> DomainResult<T>.requireSuccess(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private fun isRemoteProvider(uri: Uri): Boolean = uri.authority !in LOCAL_DOCUMENT_AUTHORITIES

    private companion object {
        const val POLL_MILLIS = 200L
        val REPORT_FORMATS = setOf(ExportFormat.CSV, ExportFormat.XLSX, ExportFormat.PDF, ExportFormat.IMAGE)
        val LOCAL_DOCUMENT_AUTHORITIES = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents", "com.android.providers.media.documents")
        val WORKBOOK_SHEETS = listOf(
            "accounts", "cards", "categories", "merchants", "places", "projects", "settlements", "transactions",
            "credit_statements", "installments", "loans", "budgets", "goals", "recurrences", "locations",
        )
    }
}

private fun ExportFormat.extension(): String = when (this) {
    ExportFormat.CSV -> "csv"
    ExportFormat.XLSX -> "xlsx"
    ExportFormat.PDF -> "pdf"
    ExportFormat.IMAGE -> "png"
    ExportFormat.PORTABLE_BACKUP -> error("backup is not an ordinary export format")
}

private fun ExportFormat.mimeType(): String = when (this) {
    ExportFormat.CSV -> "text/csv"
    ExportFormat.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ExportFormat.PDF -> "application/pdf"
    ExportFormat.IMAGE -> "image/png"
    ExportFormat.PORTABLE_BACKUP -> "application/octet-stream"
}

private fun replaceExtension(name: String, extension: String): String {
    val base = name.substringBeforeLast('.', name).trim().ifBlank { "export" }.take(170)
    return "$base.$extension"
}

private fun defaultName(content: ExportContent, format: ExportFormat): String = when (content) {
    ExportContent.CURRENT_FILTER -> "transactions.csv"
    ExportContent.FULL_WORKBOOK -> "ledger-data.xlsx"
    ExportContent.REPORT -> "report.${format.extension()}"
}
