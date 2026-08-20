@file:Suppress(
    "LargeClass",
    "LongMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package app.ledger.app

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.SecureImportSourceHandleStore
import app.ledger.core.security.SecureImportStagingAccess
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.feature.transfer.ImportDuplicateRowUi
import app.ledger.feature.transfer.ImportEntityMappingUi
import app.ledger.feature.transfer.ImportEntityValueMappingUi
import app.ledger.feature.transfer.ImportExecutionState
import app.ledger.feature.transfer.ImportFxPolicyUi
import app.ledger.feature.transfer.ImportFxRowUi
import app.ledger.feature.transfer.ImportHistoryRowUi
import app.ledger.feature.transfer.ImportMappingRowUi
import app.ledger.feature.transfer.ImportModeUi
import app.ledger.feature.transfer.ImportPreviewRowUi
import app.ledger.feature.transfer.ImportResultCountsUi
import app.ledger.feature.transfer.ImportResultOutcomeUi
import app.ledger.feature.transfer.ImportStructureState
import app.ledger.feature.transfer.ImportValidationState
import app.ledger.feature.transfer.ImportValidationIssueUi
import app.ledger.feature.transfer.ImportValidationSeverityUi
import app.ledger.feature.transfer.ImportWizardUiState
import app.ledger.finance.application.ImportCommitMetadata
import app.ledger.finance.application.ImportFinancialApplicationPort
import app.ledger.finance.application.ImportFinancialCommitRequest
import app.ledger.finance.application.ImportFinancialUndoRequest
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.StructuredImportApplicationPort
import app.ledger.finance.application.StructuredImportCommitRequest
import app.ledger.finance.application.StructuredImportEntityType
import app.ledger.finance.application.StructuredImportPageSource
import app.ledger.finance.application.StructuredImportPhase
import app.ledger.finance.application.StructuredImportRow
import app.ledger.finance.application.StructuredImportValues
import app.ledger.finance.domain.Hash256
import app.ledger.transfer.data.ImportControlAction
import app.ledger.transfer.data.ImportPreparationService
import app.ledger.transfer.data.PrimaryLedgerDuplicateMatcher
import app.ledger.transfer.data.SqlCipherBackgroundOperationRepository
import app.ledger.transfer.data.SqlCipherStagingRepository
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.DuplicateResolution
import app.ledger.transfer.domain.EntityMappingDecision
import app.ledger.transfer.domain.FxImportDecision
import app.ledger.transfer.domain.ImportCommitParameters
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportFormat
import app.ledger.transfer.domain.ImportPreparationRequest
import app.ledger.transfer.domain.ImportTargetField
import app.ledger.transfer.domain.ImportTransformation
import app.ledger.transfer.domain.ImportWizardStage
import app.ledger.transfer.domain.MissingFxPolicy
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.StagingMapping
import app.ledger.transfer.domain.StagingParsedRow
import app.ledger.transfer.domain.StagingValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

internal class ImportController(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val references: ReferenceDataManagementPort,
    private val financial: ImportFinancialApplicationPort,
    private val structured: StructuredImportApplicationPort,
    private val runtime: AppRuntimeSources,
    private val formatImportedAt: (Instant) -> String,
    private val formatPreviewValue: (StagingValue) -> String,
) {
    private val applicationContext = context.applicationContext
    private val mutableState = MutableStateFlow(ImportWizardUiState())
    val state: StateFlow<ImportWizardUiState> = mutableState.asStateFlow()
    private var bookId: StableId? = null
    private var operationId: BackgroundOperationId? = null
    private var sourceHandleId: StableId? = null
    private var format: ImportFormat = ImportFormat.CSV
    private var mappings: List<StagingMapping> = emptyList()
    private var samples: Map<String, String> = emptyMap()
    private var distinctValues: Map<String, Set<String>> = emptyMap()
    private var entityDecisions: List<EntityMappingDecision> = emptyList()
    private var allEntityDecisions: List<EntityMappingDecision> = emptyList()
    private var fxDecisions: List<FxImportDecision> = emptyList()
    private var duplicateResolutions: Map<Long, DuplicateResolution> = emptyMap()
    private var entityCreationEnabled: Map<ImportTargetField, Boolean> = emptyMap()
    private var preparedRows: Long = 0L
    private var preparedTransactionRows: Long = 0L
    private var baseCurrency: CurrencyCode? = null
    private var zoneId: ZoneId = ZoneId.of("UTC")
    private var batchId: StableId? = null
    private var importRecordId: StableId? = null
    private var useStructuredUndo: Boolean = false
    private var firstSourceRowNumber: Long = 1L
    private var appliedEncoding: String? = null
    private var appliedHeaderRowNumber: Long = 1L
    private var referenceSnapshot: app.ledger.finance.application.ReferenceDataSnapshot? = null
    private var samplesBySheet: Map<String, Map<String, String>> = emptyMap()
    private var distinctBySheet: Map<String, Map<String, Set<String>>> = emptyMap()
    private var previewsBySheet: Map<String, List<ImportPreviewRowUi>> = emptyMap()
    private var rowCountsBySheet: Map<String, Long> = emptyMap()

    fun currentOperationId(): StableId? = operationId?.value

    fun selectMode(mode: ImportModeUi) {
        mutableState.value = mutableState.value.copy(mode = mode)
    }

    fun selectSheet(name: String) {
        if (name in mutableState.value.sheetNames) applySelectedSheet(name)
    }

    fun changeEncoding(value: String) {
        mutableState.value = mutableState.value.copy(encoding = value.trim().uppercase().take(MAX_ENCODING_LENGTH))
    }

    fun changeHeaderRow(value: String) {
        mutableState.value = mutableState.value.copy(headerRowNumber = value.filter(Char::isDigit).take(MAX_HEADER_DIGITS))
    }

    fun cycleFieldMapping(source: String) {
        if (source !in samples) return
        val current = mappings.singleOrNull { it.sourceColumn == source }?.targetField
        val currentIndex = MAPPABLE_FIELDS.indexOf(current)
        val next = MAPPABLE_FIELDS[(currentIndex + 1).mod(MAPPABLE_FIELDS.size)]
        mappings = mappings.filterNot { it.sourceColumn == source || next != null && it.targetField == next } +
            listOfNotNull(next?.let { StagingMapping(source, it, ImportTransformation.Identity) })
        duplicateResolutions = emptyMap()
        referenceSnapshot?.let { rebuildEntityDecisions(it, resetCreation = false) }
        refreshMappingUi()
    }

    fun setCreateMissing(typeName: String, enabled: Boolean) {
        val type = runCatching { ImportTargetField.valueOf(typeName) }.getOrNull() ?: return
        if (type !in CREATABLE_ENTITY_FIELDS) return
        entityCreationEnabled = entityCreationEnabled + (type to enabled)
        duplicateResolutions = emptyMap()
        refreshDecisionUi()
    }

    fun cycleEntityMapping(typeName: String, sourceValue: String) {
        val field = runCatching { ImportTargetField.valueOf(typeName) }.getOrNull() ?: return
        val options = entityOptions(field)
        if (options.isEmpty()) return
        val current = allEntityDecisions.singleOrNull { it.targetField == field && it.sourceValue == sourceValue }
        val currentIndex = options.indexOfFirst { it.first == current?.existingEntityId }
        val target = options[(currentIndex + 1).mod(options.size)].first
        allEntityDecisions = allEntityDecisions.filterNot { it.targetField == field && it.sourceValue == sourceValue } +
            EntityMappingDecision(field, sourceValue, target, false)
        entityCreationEnabled = entityCreationEnabled + (field to false)
        duplicateResolutions = emptyMap()
        refreshDecisionUi()
    }

    fun setFxPolicy(sourceCurrency: String, policy: ImportFxPolicyUi) {
        val base = baseCurrency ?: return
        fxDecisions = fxDecisions.filterNot { it.sourceCurrency == sourceCurrency } + FxImportDecision(
            sourceCurrency,
            base.value,
            when (policy) {
                ImportFxPolicyUi.HISTORICAL_FROM_FILE -> MissingFxPolicy.USE_IMPORTED_HISTORICAL_RATE
                ImportFxPolicyUi.MANUAL -> MissingFxPolicy.REQUIRE_MANUAL_RATE
            },
            null,
        )
        duplicateResolutions = emptyMap()
        refreshFxUi()
    }

    fun setFxRate(sourceCurrency: String, value: String) {
        val base = baseCurrency ?: return
        val normalized = value.filter { it.isDigit() || it == '.' }.take(MAX_RATE_LENGTH)
        val rate = normalized.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
        fxDecisions = fxDecisions.filterNot { it.sourceCurrency == sourceCurrency } + listOfNotNull(
            rate?.let { FxImportDecision(sourceCurrency, base.value, MissingFxPolicy.USE_PROVIDED_RATE, it) },
        )
        duplicateResolutions = emptyMap()
        refreshFxUi(mapOf(sourceCurrency to normalized))
    }

    suspend fun resolveDuplicate(rowNumber: Long, resolution: DuplicateResolution) {
        if (rowNumber <= 0L || mutableState.value.stage != ImportWizardStage.VALIDATION) return
        duplicateResolutions = duplicateResolutions + (rowNumber to resolution)
        prepare()
    }

    suspend fun selectSource(activeBookId: StableId, activeZoneId: ZoneId, uri: Uri) {
        bookId = activeBookId
        zoneId = activeZoneId
        val operation = BackgroundOperationId(runtime.stableIds.nextStableId())
        val handle = runtime.stableIds.nextStableId()
        operationId = operation
        sourceHandleId = handle
        format = detectFormat(uri, mutableState.value.mode)
        duplicateResolutions = emptyMap()
        try {
            SecureImportSourceHandleStore(applicationContext, keyProvider).save(activeBookId, handle, uri.toString())
            val repository = operations(activeBookId)
            repository.save(
                BackgroundOperation.queued(
                    operation,
                    BackgroundOperationType.IMPORT,
                    runtime.clock.now(),
                    OperationParameters.Import(handle, format, null),
                ),
            ).success()
            mutableState.value = mutableState.value.copy(
                stage = ImportWizardStage.STRUCTURE,
                structureState = ImportStructureState.PARSING,
                processedRows = 0L,
            )
            ImportWorkScheduler.enqueue(applicationContext, operation.value)
            awaitIngestion(activeBookId, operation)
        } catch (_: SecurityException) {
            SecureImportSourceHandleStore(applicationContext, keyProvider).destroy(handle)
            mutableState.value = mutableState.value.copy(sourceState = app.ledger.feature.transfer.ImportSourceState.PERMISSION_ERROR)
        } catch (_: Exception) {
            SecureImportSourceHandleStore(applicationContext, keyProvider).destroy(handle)
            mutableState.value = mutableState.value.copy(structureState = ImportStructureState.CORRUPT_FILE)
        }
    }

    suspend fun next() {
        when (mutableState.value.stage) {
            ImportWizardStage.SOURCE -> Unit
            ImportWizardStage.STRUCTURE -> {
                if (mutableState.value.headerRowNumber.toLongOrNull()?.takeIf { it > 0L } == null) return
                if (requiresReingestion()) reingestWithStructureOptions()
                mutableState.value = mutableState.value.copy(stage = ImportWizardStage.FIELD_MAPPING)
            }
            ImportWizardStage.FIELD_MAPPING -> if (mutableState.value.mappings.isNotEmpty() && mutableState.value.mappings.all { it.valid }) {
                mutableState.value = mutableState.value.copy(stage = ImportWizardStage.ENTITY_MAPPING)
            }
            ImportWizardStage.ENTITY_MAPPING -> if (mutableState.value.entityValueMappings.none { !it.createMissing && it.targetLabel == null }) {
                mutableState.value = mutableState.value.copy(stage = ImportWizardStage.FX)
            }
            ImportWizardStage.FX -> if (mutableState.value.fxRows.all { row ->
                when (row.policy) {
                    ImportFxPolicyUi.HISTORICAL_FROM_FILE -> row.historicalAvailable
                    ImportFxPolicyUi.MANUAL -> !row.manualRequired
                }
            }) prepare()
            ImportWizardStage.VALIDATION -> if (mutableState.value.errorCount == 0L) {
                mutableState.value = mutableState.value.copy(stage = ImportWizardStage.CONFIRMATION)
            }
            ImportWizardStage.CONFIRMATION -> commit()
            ImportWizardStage.EXECUTION, ImportWizardStage.RESULT -> Unit
        }
    }

    fun previous() {
        val current = mutableState.value.stage
        val previous = ImportWizardStage.entries.getOrNull(current.ordinal - 1) ?: return
        mutableState.value = mutableState.value.copy(stage = previous)
    }

    fun togglePause() {
        val id = operationId?.value ?: return
        val control = ImportRunControlRegistry.get(id)
        if (mutableState.value.executionState == ImportExecutionState.CANCEL_REQUESTED) return
        if (control.current() == ImportControlAction.PAUSE) {
            control.resume()
            mutableState.value = mutableState.value.copy(structureState = ImportStructureState.PARSING)
        } else {
            control.pause()
            mutableState.value = mutableState.value.copy(structureState = ImportStructureState.PAUSED)
        }
    }

    fun cancel() {
        operationId?.value?.let { ImportRunControlRegistry.get(it).cancel() }
    }

    suspend fun retry() {
        val id = operationId ?: return
        val activeBook = bookId ?: return
        mutableState.value = mutableState.value.copy(structureState = ImportStructureState.PARSING)
        ImportWorkScheduler.enqueue(applicationContext, id.value)
        awaitIngestion(activeBook, id)
    }

    suspend fun rollback() {
        val activeBook = bookId ?: return
        val activeBatch = batchId ?: return
        val request = ImportFinancialUndoRequest(activeBook, activeBatch, runtime.stableIds.nextStableId(), runtime.clock.now())
        val result = if (useStructuredUndo) structured.undo(request) else financial.undo(request)
        mutableState.value = if (result is DomainResult.Success) {
            mutableState.value.copy(executionState = ImportExecutionState.SUCCEEDED, resultOutcome = ImportResultOutcomeUi.ROLLED_BACK)
        } else {
            mutableState.value.copy(executionState = ImportExecutionState.FAILED, resultOutcome = ImportResultOutcomeUi.FAILED_PARTIAL_NOT_ALLOWED)
        }
    }

    suspend fun showHistory(activeBookId: StableId) {
        bookId = activeBookId
        val history = when (val loaded = financial.history(activeBookId)) {
            is DomainResult.Success -> loaded.value.map { item ->
                val importedAt = formatImportedAt(item.importedAt)
                ImportHistoryRowUi(
                    "",
                    importedAt,
                    item.importedRows,
                    !item.reversed,
                    item.batchId.toString(),
                )
            }
            is DomainResult.Failure -> emptyList()
        }
        mutableState.value = mutableState.value.copy(showHistory = true, history = history)
    }

    fun viewValidationIssues() {
        mutableState.value = mutableState.value.copy(stage = ImportWizardStage.VALIDATION, showHistory = false)
    }

    suspend fun cleanupTemporary() {
        val activeBook = bookId ?: return
        operationId?.let { staging(activeBook, it).destroy() }
        sourceHandleId?.let { SecureImportSourceHandleStore(applicationContext, keyProvider).destroy(it) }
        mutableState.value = mutableState.value.copy(temporaryCleanupComplete = true)
    }

    fun viewHistoryResult(batchIdText: String) {
        val selected = mutableState.value.history.singleOrNull { it.batchId == batchIdText } ?: return
        mutableState.value = mutableState.value.copy(
            showHistory = false,
            stage = ImportWizardStage.RESULT,
            executionState = ImportExecutionState.SUCCEEDED,
            resultOutcome = if (selected.reversible) ImportResultOutcomeUi.SUCCESS else ImportResultOutcomeUi.ROLLED_BACK,
            resultCounts = ImportResultCountsUi(transactions = selected.rowCount),
        )
    }

    suspend fun rollbackHistory(batchIdText: String) {
        val activeBook = bookId ?: return
        val selectedBatch = StableId.parse(batchIdText).getOrNull() ?: return
        val result = financial.undo(
            ImportFinancialUndoRequest(activeBook, selectedBatch, runtime.stableIds.nextStableId(), runtime.clock.now()),
        )
        if (result is DomainResult.Success) showHistory(activeBook)
    }

    private fun requiresReingestion(): Boolean {
        val requestedHeader = mutableState.value.headerRowNumber.toLongOrNull() ?: return false
        val encodingChanged = format == ImportFormat.CSV &&
            !mutableState.value.encoding.equals(appliedEncoding ?: "UTF-8", ignoreCase = true)
        return encodingChanged || requestedHeader != appliedHeaderRowNumber
    }

    private suspend fun reingestWithStructureOptions() {
        val activeBook = bookId ?: return
        val oldId = operationId ?: return
        val handle = sourceHandleId ?: return
        val now = runtime.clock.now()
        val repository = operations(activeBook)
        val old = repository.get(oldId).success() ?: return
        if (old.state == BackgroundOperationState.RUNNING) {
            val cancelled = old.transition(BackgroundOperationState.CANCEL_REQUESTED, now).success()
            repository.save(cancelled).success()
            val rolling = cancelled.transition(BackgroundOperationState.ROLLING_BACK, runtime.clock.now()).success()
            repository.save(rolling).success()
            repository.save(
                rolling.transition(
                    BackgroundOperationState.FAILED_FINAL,
                    runtime.clock.now(),
                    errorCode = RECONFIGURED_ERROR,
                ).success(),
            ).success()
        }
        staging(activeBook, oldId).destroy().success()
        val newId = BackgroundOperationId(runtime.stableIds.nextStableId())
        operationId = newId
        val requestedCharset = mutableState.value.encoding.takeIf { format == ImportFormat.CSV }
        val header = requireNotNull(mutableState.value.headerRowNumber.toLongOrNull())
        repository.save(
            BackgroundOperation.queued(
                newId,
                BackgroundOperationType.IMPORT,
                runtime.clock.now(),
                OperationParameters.Import(handle, format, null, requestedCharset, header),
            ),
        ).success()
        mutableState.value = mutableState.value.copy(structureState = ImportStructureState.PARSING, processedRows = 0L)
        ImportWorkScheduler.enqueue(applicationContext, newId.value)
        awaitIngestion(activeBook, newId)
    }

    private suspend fun awaitIngestion(activeBookId: StableId, id: BackgroundOperationId) = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(applicationContext)
        while (true) {
            val work = workManager.getWorkInfosForUniqueWork(ImportWorkScheduler.uniqueName(id.value)).get().firstOrNull()
            val stored = operations(activeBookId).get(id)
            if (stored is DomainResult.Success) {
                val operation = stored.value
                if (operation != null) {
                    mutableState.value = mutableState.value.copy(
                        processedRows = operation.progress.current,
                        structureState = when (operation.state) {
                            BackgroundOperationState.PAUSED -> ImportStructureState.PAUSED
                            BackgroundOperationState.RUNNING, BackgroundOperationState.PREPARING -> ImportStructureState.PARSING
                            else -> mutableState.value.structureState
                        },
                    )
                }
            }
            when (work?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    if (work.outputData.getBoolean(ImportWorker.OUTPUT_CANCELLED, false)) {
                        sourceHandleId?.let { SecureImportSourceHandleStore(applicationContext, keyProvider).destroy(it) }
                        mutableState.value = mutableState.value.copy(
                            stage = ImportWizardStage.RESULT,
                            executionState = ImportExecutionState.CANCELLED,
                            resultOutcome = ImportResultOutcomeUi.CANCELLED,
                            temporaryCleanupComplete = true,
                        )
                        return@withContext
                    }
                    val selectedCharset = work.outputData.getString(ImportWorker.OUTPUT_CHARSET)
                    if (selectedCharset != null) mutableState.value = mutableState.value.copy(encoding = selectedCharset)
                    appliedEncoding = selectedCharset
                    appliedHeaderRowNumber = operations(activeBookId).get(id).success()
                        ?.let { it.parameters as? OperationParameters.Import }?.headerRowNumber ?: 1L
                    loadStaging(activeBookId, id)
                    return@withContext
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    val errorCode = work.outputData.getString(ImportWorker.OUTPUT_ERROR_CODE)
                    val unsupported = errorCode == ImportFailure.InvalidEncoding.code ||
                        errorCode == ImportFailure.UnsupportedSource.code
                    mutableState.value = mutableState.value.copy(
                        structureState = if (unsupported) ImportStructureState.UNSUPPORTED else ImportStructureState.CORRUPT_FILE,
                        temporaryCleanupComplete = work.outputData.getBoolean(
                            ImportWorker.OUTPUT_CLEANUP_COMPLETE,
                            false,
                        ),
                    )
                    return@withContext
                }
                else -> delay(WORK_POLL_MILLIS)
            }
        }
    }

    private suspend fun loadStaging(activeBookId: StableId, id: BackgroundOperationId) {
        val repository = staging(activeBookId, id)
        val previews = mutableListOf<ImportPreviewRowUi>()
        val sheets = linkedSetOf<String>()
        val loadedPreviews = linkedMapOf<String, MutableList<ImportPreviewRowUi>>()
        val loadedSamples = linkedMapOf<String, MutableMap<String, String>>()
        val loadedDistinct = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
        val loadedCounts = linkedMapOf<String, Long>()
        var offset = 0L
        while (true) {
            val page = repository.parsedRows(offset, STAGING_PAGE_ROWS).success()
            if (page.isEmpty()) break
            page.forEach { row ->
                val values = row.values()
                val previewValues = row.fields.associate { field -> field.sourceColumn to formatPreviewValue(field.value) }
                val sheet = values["_sheet"].orEmpty()
                sheets += sheet
                loadedCounts[sheet] = (loadedCounts[sheet] ?: 0L) + 1L
                val samplesForSheet = loadedSamples.getOrPut(sheet, ::linkedMapOf)
                val distinctForSheet = loadedDistinct.getOrPut(sheet, ::linkedMapOf)
                values.filterKeys { !it.startsWith('_') }.forEach { (key, value) ->
                    samplesForSheet.putIfAbsent(key, previewValues[key].orEmpty())
                    if (value.isNotBlank()) {
                        val valuesForColumn = distinctForSheet.getOrPut(key, ::linkedSetOf)
                        if (valuesForColumn.size < MAX_DISTINCT_ENTITY_VALUES) valuesForColumn += value
                    }
                }
                val previewsForSheet = loadedPreviews.getOrPut(sheet, ::mutableListOf)
                if (previewsForSheet.size < PREVIEW_ROWS) {
                    val preview = ImportPreviewRowUi(
                        row.rowNumber,
                        previewValues.filterKeys { !it.startsWith('_') }.values.joinToString(" · "),
                        "READY",
                    )
                    previewsForSheet += preview
                    if (previews.size < PREVIEW_ROWS) previews += preview
                }
            }
            offset = page.last().rowNumber
        }
        val snapshot = references.snapshot(activeBookId).success()
        referenceSnapshot = snapshot
        baseCurrency = snapshot.baseCurrency
        samplesBySheet = loadedSamples.mapValues { it.value.toMap() }
        distinctBySheet = loadedDistinct.mapValues { (_, columns) -> columns.mapValues { it.value.toSet() } }
        previewsBySheet = loadedPreviews.mapValues { it.value.toList() }
        rowCountsBySheet = loadedCounts.toMap()
        val selected = sheets.firstOrNull()
        mutableState.value = mutableState.value.copy(
            structureState = ImportStructureState.CONTENT,
            sheetNames = sheets.filter(String::isNotBlank),
            selectedSheet = selected,
            totalRows = repository.counts().success().parsed,
        )
        if (selected != null) applySelectedSheet(selected, resetCreation = true)
    }

    private fun applySelectedSheet(name: String, resetCreation: Boolean = false) {
        samples = samplesBySheet[name].orEmpty()
        distinctValues = distinctBySheet[name].orEmpty()
        mappings = autoMappings(samples.keys)
        duplicateResolutions = emptyMap()
        referenceSnapshot?.let { rebuildEntityDecisions(it, resetCreation) }
        rebuildFxDecisions()
        val previews = previewsBySheet[name].orEmpty()
        mutableState.value = mutableState.value.copy(
            selectedSheet = name,
            mappings = mappingUi(),
            entityMappings = entityMappingUi(),
            entityValueMappings = entityValueMappingUi(),
            fxRows = fxUi(),
            previewRowCount = previews.size,
            previewRow = { index -> previews[index] },
            totalRows = if (format == ImportFormat.STRUCTURED_WORKBOOK) rowCountsBySheet.values.sum() else rowCountsBySheet[name],
        )
    }

    private suspend fun prepare() {
        val activeBook = bookId ?: return
        val id = operationId ?: return
        mutableState.value = mutableState.value.copy(
            stage = ImportWizardStage.VALIDATION,
            validationState = ImportValidationState.VALIDATING,
        )
        val repository = staging(activeBook, id)
        val result = ImportPreparationService(
            PrimaryLedgerDuplicateMatcher(activeBook, SecurePrimaryLedgerAccess(applicationContext, keyProvider)),
        ).prepare(
            id,
            format,
            ImportPreparationRequest(
                requireNotNull(baseCurrency).value,
                mappings,
                entityDecisions,
                fxDecisions,
                duplicateResolutions,
                if (format == ImportFormat.STRUCTURED_WORKBOOK) emptySet() else setOfNotNull(mutableState.value.selectedSheet),
            ),
            repository,
        )
        mutableState.value = when (result) {
            is DomainResult.Success -> {
                preparedRows = result.value.preparedRows
                preparedTransactionRows = countPreparedTransactions(repository)
                firstSourceRowNumber = firstPreparedSourceRow(repository)
                val duplicates = repository.duplicateCandidates().success().map { candidate ->
                    ImportDuplicateRowUi(candidate.rowNumber, candidate.kind.name, duplicateResolutions[candidate.rowNumber])
                }
                mutableState.value.copy(
                    validationState = when {
                        result.value.report.totalErrorCount > 0L -> ImportValidationState.ERRORS
                        result.value.report.totalWarningCount > 0L -> ImportValidationState.WARNINGS
                        else -> ImportValidationState.VALID
                    },
                    errorCount = result.value.report.totalErrorCount,
                    warningCount = result.value.report.totalWarningCount,
                    duplicateCount = result.value.duplicateRows,
                    duplicates = duplicates,
                    missingEntityCount = result.value.missingEntitiesToCreate,
                    validationIssues = result.value.report.issues.map { issue ->
                        ImportValidationIssueUi(
                            issue.rowNumber,
                            issue.field?.name,
                            if (issue.severity == app.ledger.transfer.domain.ImportValidationSeverity.ERROR) {
                                ImportValidationSeverityUi.ERROR
                            } else {
                                ImportValidationSeverityUi.WARNING
                            },
                            issue.code,
                        )
                    },
                    resultCounts = importResultCounts(),
                )
            }
            is DomainResult.Failure -> mutableState.value.copy(
                validationState = ImportValidationState.ERRORS,
                errorCount = 1L,
                validationIssues = listOf(
                    ImportValidationIssueUi(null, null, ImportValidationSeverityUi.ERROR, "IMPORT_VALIDATION_FAILED"),
                ),
            )
        }
    }

    private suspend fun commit() {
        val activeBook = bookId ?: return
        val id = operationId ?: return
        if (preparedRows <= 0L) {
            mutableState.value = mutableState.value.copy(
                stage = ImportWizardStage.RESULT,
                executionState = ImportExecutionState.FAILED,
                resultOutcome = ImportResultOutcomeUi.FAILED_PARTIAL_NOT_ALLOWED,
            )
            return
        }
        mutableState.value = mutableState.value.copy(stage = ImportWizardStage.EXECUTION, executionState = ImportExecutionState.PREPARING)
        val repository = staging(activeBook, id)
        val activeBatch = runtime.stableIds.nextStableId()
        val activeRecord = runtime.stableIds.nextStableId()
        batchId = activeBatch
        importRecordId = activeRecord
        val fingerprint = fingerprint(repository)
        val requestedAt = runtime.clock.now()
        val current = operations(activeBook).get(id).success() ?: return
        useStructuredUndo = format == ImportFormat.STRUCTURED_WORKBOOK ||
            entityDecisions.any(EntityMappingDecision::createMissing)
        val currentParameters = current.parameters as? OperationParameters.Import ?: return
        val configured = current.configureImportCommit(
            currentParameters.copy(
                commit = ImportCommitParameters(
                    importRecordId = activeRecord,
                    batchId = activeBatch,
                    baseCurrency = requireNotNull(baseCurrency).value,
                    zoneId = zoneId.id,
                    totalPreparedRows = preparedRows,
                    transactionRows = preparedTransactionRows,
                    sourceFingerprint = fingerprint,
                    firstSourceRowNumber = firstSourceRowNumber,
                    useStructuredUndo = useStructuredUndo,
                ),
            ),
            requestedAt,
        ).success()
        val committing = configured.transition(
            BackgroundOperationState.COMMITTING,
            runtime.clock.now(),
            progress = app.ledger.transfer.domain.OperationProgress(0L, preparedRows),
        ).success()
        operations(activeBook).save(committing).success()
        // The coordinator now owns an atomic commit. Parsing was cancel-safe; this boundary is deliberately non-cancelable.
        mutableState.value = mutableState.value.copy(executionState = ImportExecutionState.COMMITTING)
        ImportWorkScheduler.enqueueCommit(applicationContext, id.value)
        awaitCommit(activeBook, id)
    }

    private suspend fun awaitCommit(activeBookId: StableId, id: BackgroundOperationId) = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(applicationContext)
        while (true) {
            val work = workManager.getWorkInfosForUniqueWork(ImportWorkScheduler.uniqueName(id.value)).get().firstOrNull()
            val stored = operations(activeBookId).get(id).success()
            if (stored != null) {
                mutableState.value = mutableState.value.copy(processedRows = stored.progress.current)
            }
            when (work?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val committed = work.outputData.getBoolean(ImportWorker.OUTPUT_COMMITTED, false)
                    mutableState.value = if (committed) {
                        val rows = work.outputData.getLong(ImportWorker.OUTPUT_ROWS, preparedRows)
                        useStructuredUndo = work.outputData.getBoolean(ImportWorker.OUTPUT_STRUCTURED_UNDO, useStructuredUndo)
                        mutableState.value.copy(
                            stage = ImportWizardStage.RESULT,
                            executionState = ImportExecutionState.SUCCEEDED,
                            processedRows = rows,
                            totalRows = rows,
                            temporaryCleanupComplete = work.outputData.getBoolean(
                                ImportWorker.OUTPUT_CLEANUP_COMPLETE,
                                false,
                            ),
                            resultOutcome = ImportResultOutcomeUi.SUCCESS,
                            resultCounts = importResultCounts().copy(transactions = preparedTransactionRows),
                        )
                    } else {
                        mutableState.value.copy(
                            stage = ImportWizardStage.RESULT,
                            executionState = ImportExecutionState.FAILED,
                            resultOutcome = ImportResultOutcomeUi.FAILED_PARTIAL_NOT_ALLOWED,
                        )
                    }
                    return@withContext
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    mutableState.value = mutableState.value.copy(
                        stage = ImportWizardStage.RESULT,
                        executionState = ImportExecutionState.FAILED,
                        resultOutcome = ImportResultOutcomeUi.FAILED_PARTIAL_NOT_ALLOWED,
                    )
                    return@withContext
                }
                else -> delay(WORK_POLL_MILLIS)
            }
        }
    }

    private suspend fun fingerprint(repository: SqlCipherStagingRepository): Hash256 {
        val output = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val page = repository.preparedCommands(offset, STAGING_PAGE_ROWS).success()
            if (page.isEmpty()) break
            page.forEach { output.write(it.payloadHash.bytes) }
            offset = page.last().rowNumber
        }
        return Hash256.sha256(output.toByteArray())
    }

    private suspend fun countPreparedTransactions(repository: SqlCipherStagingRepository): Long {
        var count = 0L
        var offset = 0L
        while (true) {
            val page = repository.preparedCommands(offset, STAGING_PAGE_ROWS).success()
            if (page.isEmpty()) break
            count += page.count { it.commandType != null && it.validationState == app.ledger.transfer.domain.PreparedCommandValidationState.DOMAIN_VALIDATED }
            offset = page.last().rowNumber
        }
        return count
    }

    private suspend fun firstPreparedSourceRow(repository: SqlCipherStagingRepository): Long = repository.preparedCommands(0L, 1).success().firstOrNull()?.rowNumber ?: 1L

    private fun detectFormat(uri: Uri, mode: ImportModeUi): ImportFormat {
        val type = applicationContext.contentResolver.getType(uri).orEmpty()
        val csv = type.contains("csv", ignoreCase = true) || uri.lastPathSegment.orEmpty().endsWith(".csv", ignoreCase = true)
        return if (mode == ImportModeUi.STRUCTURED) {
            ImportFormat.STRUCTURED_WORKBOOK
        } else if (csv) {
            ImportFormat.CSV
        } else {
            ImportFormat.XLSX
        }
    }

    private fun autoMappings(columns: Set<String>): List<StagingMapping> = columns.mapNotNull { source ->
        val target = AUTO_FIELDS[source.trim().lowercase()] ?: return@mapNotNull null
        StagingMapping(source, target, ImportTransformation.Identity)
    }

    private fun refreshMappingUi() {
        mutableState.value = mutableState.value.copy(mappings = mappingUi())
    }

    private fun mappingUi(): List<ImportMappingRowUi> = samples.keys.sorted().map { source ->
        val mapping = mappings.singleOrNull { it.sourceColumn == source }
        ImportMappingRowUi(source, mapping?.targetField?.name, samples[source].orEmpty(), mapping != null)
    }

    private fun rebuildEntityDecisions(
        snapshot: app.ledger.finance.application.ReferenceDataSnapshot,
        resetCreation: Boolean,
    ) {
        val existing = mapOf(
            ImportTargetField.ACCOUNT to snapshot.accounts.associate { it.name.normalized() to it.id },
            ImportTargetField.CARD to snapshot.cards.associate { it.displayName.normalized() to it.id },
            ImportTargetField.CATEGORY to snapshot.categories.associate { it.name.normalized() to it.id },
            ImportTargetField.MERCHANT to snapshot.merchants.associate { it.name.normalized() to it.id },
        )
        if (resetCreation) entityCreationEnabled = CREATABLE_ENTITY_FIELDS.associateWith { true }
        allEntityDecisions = mappings.filter { it.targetField in ENTITY_FIELDS }.flatMap { mapping ->
            distinctValues[mapping.sourceColumn].orEmpty().mapNotNull { sourceValue ->
                if (StableId.parse(sourceValue).getOrNull() != null) return@mapNotNull null
                existing[mapping.targetField]?.get(sourceValue.normalized())?.let { target ->
                    EntityMappingDecision(mapping.targetField, sourceValue, target, false)
                } ?: mapping.targetField.takeIf { it in CREATABLE_ENTITY_FIELDS }?.let {
                    EntityMappingDecision(it, sourceValue, null, true)
                }
            }
        }
        refreshDecisionUi()
    }

    private fun refreshDecisionUi() {
        entityDecisions = allEntityDecisions.filter { !it.createMissing || entityCreationEnabled[it.targetField] != false }
        mutableState.value = mutableState.value.copy(
            entityMappings = entityMappingUi(),
            entityValueMappings = entityValueMappingUi(),
        )
    }

    private fun entityMappingUi(): List<ImportEntityMappingUi> {
        val mappedFields = mappings.filter { it.targetField in ENTITY_FIELDS }.map(StagingMapping::targetField).distinct()
        return mappedFields.map { field ->
            val sourceColumn = mappings.single { it.targetField == field }.sourceColumn
            val raw = distinctValues[sourceColumn].orEmpty().count { StableId.parse(it).getOrNull() == null }
            val mapped = allEntityDecisions.count { it.targetField == field && !it.createMissing }
            ImportEntityMappingUi(
                field.name,
                (raw - mapped).coerceAtLeast(0).toLong(),
                entityCreationEnabled[field] != false,
                field in CREATABLE_ENTITY_FIELDS,
            )
        }
    }

    private fun entityValueMappingUi(): List<ImportEntityValueMappingUi> = allEntityDecisions
        .sortedWith(compareBy<EntityMappingDecision> { it.targetField.name }.thenBy(EntityMappingDecision::sourceValue))
        .map { decision ->
            val options = entityOptions(decision.targetField)
            ImportEntityValueMappingUi(
                decision.targetField.name,
                decision.sourceValue,
                options.singleOrNull { it.first == decision.existingEntityId }?.second,
                options.map { it.second },
                decision.createMissing && entityCreationEnabled[decision.targetField] != false,
            )
        }

    private fun entityOptions(field: ImportTargetField): List<Pair<StableId, String>> {
        val snapshot = referenceSnapshot ?: return emptyList()
        return when (field) {
            ImportTargetField.ACCOUNT -> snapshot.accounts.map { it.id to it.name }
            ImportTargetField.CARD -> snapshot.cards.map { it.id to it.displayName }
            ImportTargetField.CATEGORY -> snapshot.categories.map { it.id to it.name }
            ImportTargetField.MERCHANT -> snapshot.merchants.map { it.id to it.name }
            ImportTargetField.LOCATION -> snapshot.places.map { it.id to it.name }
            else -> emptyList()
        }.sortedBy { it.second }
    }

    private fun importResultCounts(): ImportResultCountsUi {
        fun count(field: ImportTargetField): Long = allEntityDecisions.count {
            it.targetField == field && it.createMissing && entityCreationEnabled[field] != false
        }.toLong()
        return ImportResultCountsUi(
            transactions = preparedTransactionRows,
            accounts = count(ImportTargetField.ACCOUNT),
            categories = count(ImportTargetField.CATEGORY),
            merchants = count(ImportTargetField.MERCHANT),
        )
    }

    private fun rebuildFxDecisions() {
        val base = baseCurrency?.value
        val sourceColumn = mappings.singleOrNull { it.targetField == ImportTargetField.CURRENCY }?.sourceColumn
        val hasHistoricalRate = mappings.any { it.targetField == ImportTargetField.FX_RATE }
        fxDecisions = if (base == null || sourceColumn == null) {
            emptyList()
        } else {
            distinctValues[sourceColumn].orEmpty().map(String::uppercase)
                .filter { it != base && it.matches(CURRENCY_PATTERN) }.distinct().map { source ->
                    FxImportDecision(
                        source,
                        base,
                        if (hasHistoricalRate) MissingFxPolicy.USE_IMPORTED_HISTORICAL_RATE else MissingFxPolicy.REQUIRE_MANUAL_RATE,
                        null,
                    )
                }
        }
        refreshFxUi()
    }

    private fun refreshFxUi(pending: Map<String, String> = emptyMap()) {
        mutableState.value = mutableState.value.copy(fxRows = fxUi(pending))
    }

    private fun fxUi(pending: Map<String, String> = emptyMap()): List<ImportFxRowUi> {
        val base = baseCurrency?.value ?: return emptyList()
        val sourceColumn = mappings.singleOrNull { it.targetField == ImportTargetField.CURRENCY }?.sourceColumn ?: return emptyList()
        return distinctValues[sourceColumn].orEmpty().map(String::uppercase)
            .filter { it != base && it.matches(CURRENCY_PATTERN) }.distinct().sorted().map { source ->
                val decided = fxDecisions.singleOrNull { it.sourceCurrency == source }?.rate?.toPlainString()
                val decision = fxDecisions.singleOrNull { it.sourceCurrency == source }
                val displayed = pending[source] ?: decided
                val policy = if (decision?.policy == MissingFxPolicy.USE_IMPORTED_HISTORICAL_RATE) {
                    ImportFxPolicyUi.HISTORICAL_FROM_FILE
                } else {
                    ImportFxPolicyUi.MANUAL
                }
                ImportFxRowUi(
                    source,
                    base,
                    displayed,
                    policy == ImportFxPolicyUi.MANUAL && displayed?.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } != true,
                    policy,
                    mappings.any { it.targetField == ImportTargetField.FX_RATE },
                )
            }
    }

    private fun String.normalized(): String = trim().lowercase()

    private fun operations(activeBookId: StableId) = SqlCipherBackgroundOperationRepository(
        activeBookId,
        SecurePrimaryLedgerAccess(applicationContext, keyProvider),
    )

    private fun staging(activeBookId: StableId, id: BackgroundOperationId) = SqlCipherStagingRepository(
        activeBookId,
        id,
        SecureImportStagingAccess(applicationContext, keyProvider),
    )

    private fun StagingParsedRow.values(): Map<String, String> = fields.associate { field -> field.sourceColumn to field.value.text() }
    private fun StagingValue.text(): String = when (this) {
        is StagingValue.Text -> value
        is StagingValue.Integer -> value.toString()
        is StagingValue.Decimal -> value.toPlainString()
        is StagingValue.Date -> value.toString()
        is StagingValue.InstantValue -> value.toString()
        StagingValue.Empty -> ""
    }

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        const val STAGING_PAGE_ROWS = 512
        const val PREVIEW_ROWS = 200
        const val WORK_POLL_MILLIS = 250L
        const val MAX_DISTINCT_ENTITY_VALUES = 10_000
        const val MAX_ENCODING_LENGTH = 40
        const val MAX_HEADER_DIGITS = 7
        const val MAX_RATE_LENGTH = 32
        const val RECONFIGURED_ERROR = "IMPORT_STRUCTURE_RECONFIGURED"
        val CURRENCY_PATTERN = Regex("[A-Z]{3}")
        val ENTITY_FIELDS = setOf(
            ImportTargetField.CATEGORY,
            ImportTargetField.ACCOUNT,
            ImportTargetField.CARD,
            ImportTargetField.MERCHANT,
            ImportTargetField.PROJECT,
            ImportTargetField.LOCATION,
            ImportTargetField.PAYER,
            ImportTargetField.PAYEE,
        )
        val CREATABLE_ENTITY_FIELDS = setOf(
            ImportTargetField.ACCOUNT,
            ImportTargetField.CATEGORY,
            ImportTargetField.MERCHANT,
        )
        val MAPPABLE_FIELDS: List<ImportTargetField?> = ImportTargetField.entries.filterNot {
            it in setOf(ImportTargetField.ATTACHMENT, ImportTargetField.INSTALLMENT, ImportTargetField.SETTLEMENT_SHARE)
        } + null
        val AUTO_FIELDS = mapOf(
            "kind" to ImportTargetField.TRANSACTION_KIND,
            "type" to ImportTargetField.TRANSACTION_KIND,
            "category" to ImportTargetField.CATEGORY,
            "amount" to ImportTargetField.AMOUNT_EXPRESSION,
            "currency" to ImportTargetField.CURRENCY,
            "account" to ImportTargetField.ACCOUNT,
            "card" to ImportTargetField.CARD,
            "merchant" to ImportTargetField.MERCHANT,
            "occurred_at" to ImportTargetField.OCCURRED_AT,
            "date" to ImportTargetField.OCCURRED_AT,
            "project" to ImportTargetField.PROJECT,
            "note" to ImportTargetField.NOTE,
            "location" to ImportTargetField.LOCATION,
            "payer" to ImportTargetField.PAYER,
            "payee" to ImportTargetField.PAYEE,
        )
    }
}
