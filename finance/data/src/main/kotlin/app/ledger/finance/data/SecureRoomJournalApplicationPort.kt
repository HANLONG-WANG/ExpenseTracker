@file:Suppress("LongMethod", "LongParameterList", "TooManyFunctions", "MaxLineLength", "MagicNumber")

package app.ledger.finance.data

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.Money
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.JournalApplicationPort
import app.ledger.finance.application.JournalBulkEditOptions
import app.ledger.finance.application.JournalBulkEditRequest
import app.ledger.finance.application.JournalBulkOption
import app.ledger.finance.application.JournalDependencyView
import app.ledger.finance.application.JournalDetailView
import app.ledger.finance.application.JournalFieldUpdate
import app.ledger.finance.application.JournalFxEvidenceView
import app.ledger.finance.application.JournalMutationRequest
import app.ledger.finance.application.JournalPage
import app.ledger.finance.application.JournalPageRequest
import app.ledger.finance.application.JournalPurgeAssessment
import app.ledger.finance.application.JournalRevisionComparison
import app.ledger.finance.application.JournalRevisionView
import app.ledger.finance.application.JournalSavedFilter
import app.ledger.finance.application.JournalSavedFilterCommand
import app.ledger.finance.application.JournalSelectionMode
import app.ledger.finance.application.JournalTransactionView
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.PurgeIneligibilityReason
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.BalanceAdjustmentPayload
import app.ledger.finance.domain.BatchFinancialCommand
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CategoryAssignment
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.CreditPaymentPayload
import app.ledger.finance.domain.DependencyPolicy
import app.ledger.finance.domain.DependencyResolution
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.EditTransactionCommand
import app.ledger.finance.domain.ExpensePayer
import app.ledger.finance.domain.ExpensePayload
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FxExchangePayload
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.IncomePayload
import app.ledger.finance.domain.LoanPaymentPayload
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.MoveTransactionToTrashCommand
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.OpeningBalancePayload
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.RefundPayload
import app.ledger.finance.domain.RestoreHistoricalRevisionCommand
import app.ledger.finance.domain.RestoreTransactionCommand
import app.ledger.finance.domain.RevisionAction
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionDependencyType
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionPayload
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth

/** SQLCipher implementation for P15. All mutations still terminate at FinancialMutationCoordinator. */
class SecureRoomJournalApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) : JournalApplicationPort {
    private val applicationContext = context.applicationContext
    private val mapper = RoomReferenceFinancialSnapshotMapper()
    private val writeGate = JournalWriteGate()
    private val filterStore = EncryptedJournalFilterStore(applicationContext)

    override suspend fun page(request: JournalPageRequest): DomainResult<JournalPage> = withDatabase(request.bookId) { database ->
        when (val queried = RoomTransactionQueryService(database).page(request.filter, request.limit, request.cursor)) {
            is DomainResult.Failure -> queried
            is DomainResult.Success -> database.readLedger { db ->
                var previousDate = request.cursor?.transactionId?.value?.let { cursorId ->
                    db.queryOne(
                        "SELECT tr.local_date FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id WHERE bt.uid=?",
                        arrayOf(cursorId.bytes),
                    ) { it.getInt(0).toStoredLocalDate() }
                }
                DomainResult.Success(
                    JournalPage(
                        queried.value.items.map { projection ->
                            val startsDateGroup = projection.localDate != previousDate
                            previousDate = projection.localDate
                            row(db, projection.transactionId.value, request.runningBalanceAccountId, startsDateGroup)
                        },
                        queried.value.nextCursor,
                    ),
                )
            }
        }
    }.flatten()

    override suspend fun detail(bookId: StableId, transactionId: StableId): DomainResult<JournalDetailView?> = withDatabase(bookId) { database -> database.readLedger { db -> detail(db, transactionId) } }

    override suspend fun history(bookId: StableId, transactionId: StableId): DomainResult<List<JournalRevisionView>> = withDatabase(bookId) { database -> database.readLedger { db -> history(db, transactionId) } }

    override suspend fun compare(
        bookId: StableId,
        transactionId: StableId,
        leftRevisionId: StableId,
        rightRevisionId: StableId,
    ): DomainResult<JournalRevisionComparison> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            val versions = history(db, transactionId)
            val left = versions.singleOrNull { it.revisionId == leftRevisionId } ?: abort(FinanceDataError.CorruptData)
            val right = versions.singleOrNull { it.revisionId == rightRevisionId } ?: abort(FinanceDataError.CorruptData)
            val candidates = linkedMapOf(
                "occurredAt" to (left.occurredAt to right.occurredAt),
                "category" to (left.category to right.category),
                "account" to (left.account to right.account),
                "amount" to ((left.amountMinor to left.currency) to (right.amountMinor to right.currency)),
                "state" to (left.resultingState to right.resultingState),
            )
            JournalRevisionComparison(left, right, candidates.filterValues { it.first != it.second }.keys.toList(), candidates.filterValues { it.first == it.second }.keys.toList())
        }
    }

    override suspend fun dependencies(bookId: StableId, transactionId: StableId): DomainResult<List<JournalDependencyView>> = withDatabase(bookId) { database -> database.readLedger { db -> dependencies(db, transactionId) } }

    override suspend fun assessPurge(bookId: StableId, transactionId: StableId, now: Instant): DomainResult<JournalPurgeAssessment> = withDatabase(bookId) { database -> database.readLedger { db -> purgeAssessment(db, transactionId, now) } }

    override suspend fun mutate(request: JournalMutationRequest): DomainResult<CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        executeMutation(database, request)
    }.flatten()

    override suspend fun bulkEdit(request: JournalBulkEditRequest): DomainResult<CommandReceipt> = withDatabase(request.bookId) { database ->
        executeBulkEdit(database, request)
    }.flatten()

    override suspend fun bulkEditOptions(bookId: StableId): DomainResult<JournalBulkEditOptions> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            JournalBulkEditOptions(
                accounts = db.queryList("SELECT uid,name FROM user_account WHERE status=0 ORDER BY sort_order,name", emptyArray()) { JournalBulkOption(it.stableId("uid"), it.jString("name")) },
                cards = db.queryList(
                    "SELECT pc.uid,pc.display_name,ua.uid account_uid FROM payment_card pc JOIN user_account ua ON ua.id=pc.account_id WHERE pc.status=0 ORDER BY pc.sort_order,pc.display_name",
                    emptyArray(),
                ) { JournalBulkOption(it.stableId("uid"), it.jString("display_name"), it.stableId("account_uid")) },
                categories = db.queryList("SELECT uid,name FROM category WHERE status=0 ORDER BY direction,sort_order,name", emptyArray()) { JournalBulkOption(it.stableId("uid"), it.jString("name")) },
                merchants = db.queryList("SELECT uid,name FROM merchant WHERE status=0 ORDER BY normalized_name", emptyArray()) { JournalBulkOption(it.stableId("uid"), it.jString("name")) },
                projects = db.queryList("SELECT uid,name FROM project WHERE status=0 ORDER BY name", emptyArray()) { JournalBulkOption(it.stableId("uid"), it.jString("name")) },
                settlementActivities = db.queryList("SELECT uid,name FROM settlement_activity WHERE status=0 ORDER BY name", emptyArray()) { JournalBulkOption(it.stableId("uid"), it.jString("name")) },
                participants = db.queryList("SELECT uid,name FROM participant WHERE status=0 ORDER BY name", emptyArray()) { JournalBulkOption(it.stableId("uid"), it.jString("name")) },
                currencies = db.queryList(
                    "SELECT currency_code FROM user_account UNION SELECT base_currency FROM book ORDER BY currency_code",
                    emptyArray(),
                ) { currency(it.getString(0)) },
            )
        }
    }

    override suspend fun savedFilters(bookId: StableId): DomainResult<List<JournalSavedFilter>> = withKeys(bookId) { keys ->
        filterStore.read(bookId, keys)
    }

    override suspend fun mutateSavedFilter(
        bookId: StableId,
        command: JournalSavedFilterCommand,
    ): DomainResult<List<JournalSavedFilter>> = withKeys(bookId) { keys ->
        val current = filterStore.read(bookId, keys)
        val next = when (command) {
            is JournalSavedFilterCommand.Save -> {
                require(command.name.isNotBlank() && command.name.trim().length <= 80)
                require(current.none { it.id == command.id })
                current + JournalSavedFilter(command.id, command.name.trim(), command.filter, command.naturalLanguageSummary, false, current.size)
            }
            is JournalSavedFilterCommand.Copy -> {
                require(command.newName.isNotBlank() && command.newName.trim().length <= 80)
                require(current.none { it.id == command.newId })
                val source = current.single { it.id == command.sourceId }
                current + source.copy(id = command.newId, name = command.newName.trim(), isDefault = false, sortOrder = current.size)
            }
            is JournalSavedFilterCommand.SetDefault -> current.map { it.copy(isDefault = it.id == command.id) }.also { require(it.any(JournalSavedFilter::isDefault)) }
            is JournalSavedFilterCommand.Delete -> current.filterNot { it.id == command.id }.mapIndexed { index, value -> value.copy(sortOrder = index) }
            is JournalSavedFilterCommand.Reorder -> {
                require(command.orderedIds.size == current.size && command.orderedIds.toSet() == current.map(JournalSavedFilter::id).toSet())
                command.orderedIds.mapIndexed { index, id -> current.single { it.id == id }.copy(sortOrder = index) }
            }
        }
        filterStore.write(bookId, keys, next)
        next.sortedBy(JournalSavedFilter::sortOrder)
    }

    private suspend fun executeBulkEdit(
        database: LedgerDatabase,
        request: JournalBulkEditRequest,
    ): DomainResult<CommandReceipt> {
        val selectedIds = resolveSelection(database, request)
        if (selectedIds.isEmpty()) return DomainResult.Failure(DomainViolation.InvalidField("journalSelection.empty"))
        val sources = database.readLedger { db ->
            selectedIds.mapIndexed { index, transactionId ->
                mapper.load(
                    db,
                    transactionId,
                    derivedId(request.commitId, "revision:$index"),
                    request.commitId,
                    List(BULK_FACT_ID_RESERVE) { fact -> derivedId(request.commitId, "fact:$index:$fact") },
                    List(BULK_FX_ID_RESERVE) { fx -> derivedId(request.commitId, "fx:$index:$fx") },
                    request.changedAt,
                    request.deviceInstanceId,
                )
            }
        }
        val edits = sources.mapIndexedNotNull { index, source ->
            val replacement = applyPatch(source, request.patch)
            if (replacement == NewTransactionInput(source.revision.toContextInput(), source.revision.payload)) {
                return@mapIndexedNotNull null
            }
            val draft = EditTransactionCommand(
                commandId = CommandId(derivedId(request.commandId, "child:$index")),
                expectedRevisionId = source.revision.id,
                payloadHash = zeroHash(),
                transactionId = source.revision.transactionId,
                replacement = replacement,
                dependencyResolutions = emptyList(),
                revisionAction = RevisionAction.BULK_EDIT,
            )
            source to draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        }
        if (edits.isEmpty()) return DomainResult.Failure(DomainViolation.InvalidField("bulk.noChanges"))
        val children = edits.map { it.second }
        val draft = BatchFinancialCommand(CommandId(request.commandId), zeroHash(), children)
        val batch = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        val first = sources.first().snapshot
        val root = PlanningSnapshot(
            book = first.book,
            currentTransaction = null,
            currentRevision = null,
            dependencies = emptyList(),
            reversedApplyEntryIds = emptySet(),
            refundStatuses = emptyList(),
            budgetRevision = null,
            participants = emptyList(),
            batchSnapshots = edits.map { it.first.snapshot },
        )
        return coordinate(database, batch, root)
    }

    private suspend fun resolveSelection(database: LedgerDatabase, request: JournalBulkEditRequest): List<StableId> {
        val chosen = mutableListOf<StableId>()
        var cursor: app.ledger.finance.application.CurrentTransactionCursor? = null
        do {
            val page = when (val result = RoomTransactionQueryService(database).page(request.filter, BULK_QUERY_PAGE_SIZE, cursor)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> abort(result.error)
            }
            page.items.asSequence().map { it.transactionId.value }.filter(request.selection::contains).forEach(chosen::add)
            cursor = page.nextCursor
            if (request.selection.mode == JournalSelectionMode.EXPLICIT && chosen.size == request.selection.includedIds.size) break
        } while (cursor != null)
        return chosen
    }

    private fun applyPatch(
        source: ReferenceEditSource,
        patch: app.ledger.finance.application.JournalBulkEditPatch,
    ): NewTransactionInput<TransactionPayload> {
        val original = source.revision
        val references = requireNotNull(source.snapshot.accountingContext).references
        var context = original.toContextInput()
        var payload = original.payload
        context = context.copy(
            merchantId = patch.merchantId.updateNullable(context.merchantId) { MerchantId(it) },
            projectId = patch.projectId.updateNullable(context.projectId) { ProjectId(it) },
            note = patch.note.updateNullable(context.note) { it.trim().takeIf(String::isNotEmpty) },
        )
        val occurredAtUpdate = patch.occurredAt
        if (occurredAtUpdate is JournalFieldUpdate.Set) {
            val occurred = EffectiveTime.fromInstant(occurredAtUpdate.value, context.occurredAt.zoneId)
            context = context.copy(occurredAt = occurred, accrualDate = occurred.localDate, budgetMonth = context.budgetMonth?.let { YearMonth.from(occurred.localDate) })
        }
        val budgetUpdate = patch.includedInBudget
        if (budgetUpdate is JournalFieldUpdate.Set) {
            context = context.copy(budgetMonth = if (budgetUpdate.value) YearMonth.from(context.accrualDate) else null)
        }
        val category = when (val update = patch.categoryId) {
            JournalFieldUpdate.Unchanged -> payload.classification
            JournalFieldUpdate.Clear -> null
            is JournalFieldUpdate.Set -> references.category(CategoryId(update.value))?.let { CategoryAssignment(it.id, it.direction, it.statisticalNature) }
                ?: abort(DomainViolation.InvalidField("bulk.category"))
        }
        val statistical = when (val update = patch.statisticalNature) {
            JournalFieldUpdate.Unchanged -> category
            JournalFieldUpdate.Clear -> abort(DomainViolation.InvalidField("bulk.statisticalNature"))
            is JournalFieldUpdate.Set -> category?.copy(statisticalNatureSnapshot = update.value)
                ?: abort(DomainViolation.InvalidField("bulk.statisticalNature"))
        }
        if (category != payload.classification || statistical != category) payload = payload.withClassification(statistical)
        val accountUpdate = patch.accountAndCard
        if (accountUpdate is JournalFieldUpdate.Set) {
            val update = accountUpdate.value
            val target = references.account(UserAccountId(update.accountId))?.account ?: abort(DomainViolation.InvalidField("bulk.account"))
            val card = update.cardId?.let(::PaymentCardId)
            payload = when (payload) {
                is ExpensePayload -> {
                    val payer = payload.payer as? ExpensePayer.LocalAccount ?: abort(DomainViolation.InvalidField("bulk.account.externalExpense"))
                    payload.copy(payer = ExpensePayer.LocalAccount(AccountAmount.create(target, payer.accountAmount.amount.money).valueOrAbort(), card))
                }
                is IncomePayload -> payload.copy(receivingAmount = AccountAmount.create(target, payload.receivingAmount.amount.money).valueOrAbort())
                is RefundPayload -> payload.copy(receivingAmount = AccountAmount.create(target, payload.receivingAmount.amount.money).valueOrAbort(), receivingCardId = card)
                is LoanPaymentPayload -> payload.copy(payment = AccountAmount.create(target, payload.payment.amount.money).valueOrAbort())
                is CreditPaymentPayload -> payload.copy(payment = AccountAmount.create(target, payload.payment.amount.money).valueOrAbort())
                is BalanceAdjustmentPayload -> payload.copy(accountAmount = AccountAmount.create(target, payload.accountAmount.amount.money).valueOrAbort())
                is OpeningBalancePayload -> payload.copy(accountAmount = AccountAmount.create(target, payload.accountAmount.amount.money).valueOrAbort())
                else -> abort(DomainViolation.InvalidField("bulk.account.transactionKind"))
            }
        }
        return NewTransactionInput(context, payload)
    }

    private suspend fun executeMutation(database: LedgerDatabase, request: JournalMutationRequest): DomainResult<CommandReceipt> {
        val ids = request.ids
        val source = database.readLedger { db ->
            mapper.load(db, ids.transactionId, ids.revisionId, ids.commitId, ids.factIds, ids.fxRateSnapshotIds, request.createdAt, ids.deviceInstanceId)
        }
        if (request is JournalMutationRequest.MoveToTrash && source.snapshot.dependencies.any {
                it.type == TransactionDependencyType.REFUND && it.parentTransactionId.value == ids.transactionId
            }
        ) {
            return executeOriginalWithRefundPolicy(database, request, source)
        }
        val emptyHash = Hash256.fromBytes(ByteArray(32)).valueOrAbort()
        val commandAndSnapshot: Pair<FinancialCommand, PlanningSnapshot> = when (request) {
            is JournalMutationRequest.MoveToTrash ->
                MoveTransactionToTrashCommand(
                    CommandId(ids.commandId),
                    TransactionRevisionId(request.expectedRevisionId),
                    emptyHash,
                    TransactionId(ids.transactionId),
                    request.purgeAfter,
                    request.dependencyResolutions,
                ).canonical() to source.snapshot
            is JournalMutationRequest.RestoreFromTrash ->
                RestoreTransactionCommand(
                    CommandId(ids.commandId),
                    TransactionRevisionId(request.expectedRevisionId),
                    emptyHash,
                    TransactionId(ids.transactionId),
                ).canonical() to source.snapshot
            is JournalMutationRequest.RestoreHistorical -> {
                val historical = database.readLedger { db -> mapper.historicalInput(db, ids.transactionId, request.sourceRevisionId, ids.fxRateSnapshotIds) }
                val context = requireNotNull(source.snapshot.accountingContext)
                val historicalSnapshot = source.snapshot.copy(
                    accountingContext = context.copy(
                        identities = PlanningIdentitySet(TransactionId(ids.transactionId), TransactionRevisionId(ids.revisionId), BookCommitId(ids.commitId), ids.factIds),
                        createdAt = request.createdAt,
                        deviceInstanceId = DeviceInstanceId(ids.deviceInstanceId),
                        amountEvidence = historical.second,
                    ),
                )
                RestoreHistoricalRevisionCommand(
                    CommandId(ids.commandId),
                    TransactionRevisionId(request.expectedRevisionId),
                    emptyHash,
                    TransactionId(ids.transactionId),
                    TransactionRevisionId(request.sourceRevisionId),
                    historical.first,
                    request.dependencyResolutions,
                ).canonical() to historicalSnapshot
            }
        }
        return coordinate(database, commandAndSnapshot.first, commandAndSnapshot.second)
    }

    /** Applies the selected original/refund dependency policy in the same financial commit. */
    private suspend fun executeOriginalWithRefundPolicy(
        database: LedgerDatabase,
        request: JournalMutationRequest.MoveToTrash,
        original: ReferenceEditSource,
    ): DomainResult<CommandReceipt> {
        val resolutions = request.dependencyResolutions.associateBy(DependencyResolution::dependency)
        if (resolutions.size != request.dependencyResolutions.size) {
            return DomainResult.Failure(DomainViolation.InvalidField("refundDependency.duplicateResolution"))
        }
        val refundDependencies = original.snapshot.dependencies.filter {
            it.type == TransactionDependencyType.REFUND && it.parentTransactionId.value == request.ids.transactionId
        }
        val dependentSources = database.readLedger { db ->
            refundDependencies.mapIndexed { index, dependency ->
                val resolution = resolutions[dependency]
                    ?: abort(DomainViolation.InvalidField("refundDependency.unresolved"))
                val source = mapper.load(
                    db,
                    dependency.childTransactionId.value,
                    derivedId(request.ids.commitId, "refund-revision:$index"),
                    request.ids.commitId,
                    List(BULK_FACT_ID_RESERVE) { fact -> derivedId(request.ids.commitId, "refund-fact:$index:$fact") },
                    List(BULK_FX_ID_RESERVE) { fx -> derivedId(request.ids.commitId, "refund-fx:$index:$fx") },
                    request.createdAt,
                    request.ids.deviceInstanceId,
                )
                DependentRefundSource(source, resolution)
            }
        }
        val dependentCommands = dependentSources.mapIndexed { index, dependent ->
            val source = dependent.source
            val covered = source.snapshot.dependencies.map { dependency ->
                resolutions[dependency] ?: abort(DomainViolation.InvalidField("refundDependency.childUnresolved"))
            }
            when (dependent.resolution.policy) {
                DependencyPolicy.ReverseDependentTransactions -> MoveTransactionToTrashCommand(
                    commandId = CommandId(derivedId(request.ids.commandId, "refund-child:$index")),
                    expectedRevisionId = source.revision.id,
                    payloadHash = zeroHash(),
                    transactionId = source.revision.transactionId,
                    purgeAfter = request.purgeAfter,
                    dependencyResolutions = covered,
                ).canonical()
                DependencyPolicy.ConvertRefundToIndependent -> {
                    val refund = source.revision.payload as? RefundPayload
                        ?: abort(DomainViolation.InvalidField("refundDependency.childKind"))
                    EditTransactionCommand(
                        commandId = CommandId(derivedId(request.ids.commandId, "refund-child:$index")),
                        expectedRevisionId = source.revision.id,
                        payloadHash = zeroHash(),
                        transactionId = source.revision.transactionId,
                        replacement = NewTransactionInput(
                            source.revision.toContextInput(),
                            refund.copy(
                                allocations = emptyList(),
                                independent = true,
                                allowExcessOverride = false,
                            ),
                        ),
                        dependencyResolutions = covered,
                    ).canonical()
                }
                else -> abort(DomainViolation.InvalidField("refundDependency.policy"))
            }
        }
        val originalCommand = MoveTransactionToTrashCommand(
            commandId = CommandId(derivedId(request.ids.commandId, "original")),
            expectedRevisionId = TransactionRevisionId(request.expectedRevisionId),
            payloadHash = zeroHash(),
            transactionId = TransactionId(request.ids.transactionId),
            purgeAfter = request.purgeAfter,
            dependencyResolutions = request.dependencyResolutions,
        ).canonical()
        val batchDraft = BatchFinancialCommand(
            CommandId(request.ids.commandId),
            zeroHash(),
            dependentCommands + originalCommand,
        )
        val batch = batchDraft.copy(payloadHash = CanonicalFinancialHash.command(batchDraft))
        val root = PlanningSnapshot(
            book = original.snapshot.book,
            currentTransaction = null,
            currentRevision = null,
            dependencies = emptyList(),
            reversedApplyEntryIds = emptySet(),
            refundStatuses = emptyList(),
            budgetRevision = null,
            participants = emptyList(),
            batchSnapshots = dependentSources.map { it.source.snapshot } + original.snapshot,
        )
        return coordinate(database, batch, root)
    }

    private suspend fun coordinate(database: LedgerDatabase, command: FinancialCommand, snapshot: PlanningSnapshot): DomainResult<CommandReceipt> {
        // Lifecycle mutations touch every derived financial view. Rebuilding them in the same
        // SQLite transaction prevents a trashed transaction from surviving in balances, budgets,
        // analytics, goals, widgets, or the active journal after an incremental edge case.
        val repository = RoomFinancialCommitRepository(database, forceFullProjectionRebuild = true)
        return DefaultFinancialMutationCoordinator(
            writeGate,
            repository,
            object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
            },
            FinancialPlanningPort(DeterministicFinancialPlanner::plan),
            repository,
        ).execute(command)
    }

    private fun FinancialCommand.canonical(): FinancialCommand = when (this) {
        is EditTransactionCommand -> copy(payloadHash = CanonicalFinancialHash.command(this))
        is MoveTransactionToTrashCommand -> copy(payloadHash = CanonicalFinancialHash.command(this))
        is RestoreTransactionCommand -> copy(payloadHash = CanonicalFinancialHash.command(this))
        is RestoreHistoricalRevisionCommand -> copy(payloadHash = CanonicalFinancialHash.command(this))
        is BatchFinancialCommand -> copy(payloadHash = CanonicalFinancialHash.command(this))
        else -> error("journal command type")
    }

    private fun row(
        db: SupportSQLiteDatabase,
        id: StableId,
        runningAccountId: StableId?,
        startsDateGroup: Boolean = false,
    ): JournalTransactionView = db.queryOne(
        "SELECT bt.uid,tr.uid revision_uid,bt.kind,bt.lifecycle_state,tr.occurred_at,tr.local_date," +
            "COALESCE(c.name,'') category_name,COALESCE(m.name,tr.note,'') summary,COALESCE(pa.name,sa.name,'') account_name," +
            "pc.display_name card_name,ctp.account_amount_minor,ctp.account_currency,ctp.input_amount_minor,ctp.input_currency," +
            "ctp.has_attachment,ctp.has_location,ctp.is_refund,ctp.is_refunded,ctp.has_installment,ctp.source_type," +
            "bt.trashed_at,bt.purge_after,(SELECT COUNT(*) FROM transaction_dependency td WHERE td.parent_transaction_id=bt.id OR td.child_transaction_id=bt.id) dependency_count " +
            "FROM current_transaction_projection ctp JOIN business_transaction bt ON bt.id=ctp.transaction_id " +
            "JOIN transaction_revision tr ON tr.id=ctp.current_revision_id LEFT JOIN category c ON c.id=ctp.category_id " +
            "LEFT JOIN merchant m ON m.id=ctp.merchant_id LEFT JOIN user_account pa ON pa.id=ctp.primary_account_id " +
            "LEFT JOIN user_account sa ON sa.id=ctp.secondary_account_id LEFT JOIN payment_card pc ON pc.id=ctp.card_id WHERE bt.uid=?",
        arrayOf(id.bytes),
    ) { cursor ->
        val accountCurrency = currency(cursor.jString("account_currency"))
        val inputCurrency = currency(cursor.jString("input_currency"))
        val badges = buildList {
            if (cursor.jInt("has_attachment") == 1) add("attachment")
            if (cursor.jInt("has_location") == 1) add("location")
            if (cursor.jInt("is_refund") == 1) add("refund")
            if (cursor.jInt("is_refunded") == 1) add("refunded")
            if (cursor.jInt("has_installment") == 1) add("installment")
        }
        JournalTransactionView(
            cursor.stableId("uid"), cursor.stableId("revision_uid"), TransactionKind.entries[cursor.jInt("kind")],
            TransactionLifecycleState.entries[cursor.jInt("lifecycle_state")], Instant.ofEpochMilli(cursor.jLong("occurred_at")),
            cursor.jInt("local_date").toStoredLocalDate(), cursor.jString("category_name"),
            cursor.jString("summary"),
            listOfNotNull(cursor.jString("account_name").takeIf(String::isNotBlank), cursor.nullableString("card_name")).joinToString(" · "),
            cursor.jLong("account_amount_minor"), accountCurrency,
            cursor.jLong("input_amount_minor").takeIf { inputCurrency != accountCurrency }, inputCurrency.takeIf { it != accountCurrency },
            badges, runningAccountId?.let { runningBalance(db, it, cursor.jLong("occurred_at"), id) },
            TransactionSource.entries[cursor.jInt("source_type")],
            cursor.nullableLong("trashed_at")?.let(Instant::ofEpochMilli),
            cursor.nullableLong("purge_after")?.let(Instant::ofEpochMilli),
            cursor.jInt("dependency_count"),
            startsDateGroup,
        )
    } ?: abort(FinanceDataError.CorruptData)

    private fun runningBalance(db: SupportSQLiteDatabase, accountId: StableId, occurredAt: Long, transactionId: StableId): Long = db.queryOne(
        "SELECT COALESCE(SUM(CASE WHEN p.side=la.normal_side THEN p.account_amount_minor ELSE -p.account_amount_minor END),0) balance " +
            "FROM posting p JOIN ledger_account la ON la.id=p.ledger_account_id JOIN user_account ua ON ua.ledger_account_id=la.id " +
            "JOIN journal_entry je ON je.id=p.journal_entry_id JOIN transaction_revision sr ON sr.id=je.source_revision_id JOIN business_transaction bt ON bt.id=sr.transaction_id " +
            "WHERE ua.uid=? AND bt.lifecycle_state=0 AND (je.effective_at<? OR (je.effective_at=? AND bt.uid<=?))",
        arrayOf(accountId.bytes, occurredAt, occurredAt, transactionId.bytes),
    ) { it.jLong("balance") } ?: 0L

    private fun detail(db: SupportSQLiteDatabase, id: StableId): JournalDetailView? {
        val base = db.queryOne(
            "SELECT bt.uid,tr.uid revision_uid,created.created_at created_at,modified.created_at modified_at,tr.zone_id,tr.amount_expression,tr.note," +
                "m.name merchant_name,p.name project_name,pl.name place_name,tr.statistical_nature_snapshot,tr.source_type,bt.purge_after " +
                "FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                "JOIN book_commit created ON created.id=bt.created_commit_id JOIN book_commit modified ON modified.id=bt.last_commit_id " +
                "LEFT JOIN merchant m ON m.id=tr.merchant_id LEFT JOIN project p ON p.id=tr.project_id " +
                "LEFT JOIN location_record lr ON lr.id=tr.location_record_id LEFT JOIN place pl ON pl.id=lr.place_id " +
                "WHERE bt.uid=? AND NOT EXISTS(SELECT 1 FROM purge_tombstone pt WHERE pt.entity_type=? AND pt.entity_uid=bt.uid)",
            arrayOf(id.bytes, app.ledger.finance.domain.EntityType.TRANSACTION.ordinal),
        ) { cursor -> DetailBase.from(cursor) } ?: return null
        val item = row(db, id, null)
        val attachments = db.queryList(
            "SELECT a.uid,a.display_name FROM transaction_revision_attachment tra JOIN attachment a ON a.id=tra.attachment_id JOIN transaction_revision tr ON tr.id=tra.revision_id WHERE tr.uid=? ORDER BY tra.sort_order",
            arrayOf(base.revisionId.bytes),
        ) { it.stableId("uid") to it.getString(1) }
        val fx = db.queryList(
            "SELECT DISTINCT fx.source_currency,fx.target_currency,fx.rate_decimal,fx.provider,fx.quoted_at,fx.manual_override,fx.stale_at_use " +
                "FROM revision_amount ra JOIN transaction_revision tr ON tr.id=ra.revision_id JOIN fx_rate_snapshot fx ON fx.id=ra.fx_rate_snapshot_id WHERE tr.uid=? ORDER BY fx.source_currency,fx.target_currency",
            arrayOf(base.revisionId.bytes),
        ) { c -> JournalFxEvidenceView(currency(c.getString(0)), currency(c.getString(1)), c.getString(2), c.getString(3), c.nullableLong("quoted_at")?.let(Instant::ofEpochMilli), c.getInt(5) == 1, c.getInt(6) == 1) }
        val accountEffects = db.queryList(
            "SELECT ua.name,CASE WHEN p.side=la.normal_side THEN p.account_amount_minor ELSE -p.account_amount_minor END,p.account_currency FROM posting p JOIN journal_entry je ON je.id=p.journal_entry_id " +
                "JOIN transaction_revision tr ON tr.id=je.source_revision_id JOIN ledger_account la ON la.id=p.ledger_account_id " +
                "JOIN user_account ua ON ua.ledger_account_id=la.id WHERE tr.uid=? ORDER BY je.entry_role,p.line_no",
            arrayOf(base.revisionId.bytes),
        ) { c -> "account-change|${c.getString(0)}|${c.getLong(1)}|${c.getString(2)}" }
        val dependencyRelations = dependencies(db, id).map { "${it.type}:${it.childTransactionId}" }
        // A malformed legacy relationship projection must not make an otherwise valid
        // transaction detail permanently unreachable. The typed dependency list remains
        // available and relationship repair can be handled by integrity maintenance.
        val relations = dependencyRelations + runCatching { RoomJournalRefundRelations.summaries(db, id) }.getOrDefault(emptyList())
        val budget = db.queryOne("SELECT COUNT(*) n FROM budget_effect be JOIN transaction_revision tr ON tr.id=be.source_revision_id WHERE tr.uid=? AND be.polarity=1", arrayOf(base.revisionId.bytes)) { it.jLong("n") }
        return JournalDetailView(
            item, base.createdAt, base.modifiedAt, base.zoneId, base.expression, base.note, base.merchant, base.project, base.place,
            attachments.map { it.first }, attachments.map { it.second },
            budget?.takeIf { it > 0 }?.let { "included:$it" }, base.statisticalNature, fx, relations, accountEffects,
            TransactionSource.entries[base.source].name, base.purgeAfter, dependencyRelations.size,
        )
    }

    private fun history(db: SupportSQLiteDatabase, id: StableId): List<JournalRevisionView> {
        val raw = db.queryList(
            "SELECT tr.uid,tr.revision_no,tr.action,tr.resulting_state,tr.created_at,tr.occurred_at,c.name category_name," +
                "COALESCE(ua.name,ua2.name,ua3.name) account_name,ra.amount_minor,ra.currency_code,tr.note,tr.merchant_id,tr.project_id,tr.location_record_id,tr.source_type " +
                "FROM transaction_revision tr JOIN business_transaction bt ON bt.id=tr.transaction_id LEFT JOIN category c ON c.id=tr.category_id " +
                "LEFT JOIN expense_revision_detail erd ON erd.revision_id=tr.id LEFT JOIN income_revision_detail ird ON ird.revision_id=tr.id " +
                "LEFT JOIN refund_revision_detail rrd ON rrd.revision_id=tr.id " +
                "LEFT JOIN user_account ua ON ua.id=erd.payer_account_id LEFT JOIN user_account ua2 ON ua2.id=ird.receiving_account_id " +
                "LEFT JOIN user_account ua3 ON ua3.id=rrd.receiving_account_id " +
                "LEFT JOIN revision_amount ra ON ra.revision_id=tr.id AND ra.component_index=0 AND ra.role IN (?,?) AND ra.representation=1 " +
                "WHERE bt.uid=? ORDER BY tr.revision_no",
            arrayOf(AmountRole.PRIMARY.ordinal, AmountRole.REFUND.ordinal, id.bytes),
        ) { RevisionRaw.from(it) }
        return raw.mapIndexed { index, value ->
            val previous = raw.getOrNull(index - 1)
            val changes = if (previous == null) {
                listOf("created")
            } else {
                buildList {
                    if (value.occurredAt != previous.occurredAt) add("occurredAt")
                    if (value.category != previous.category) add("category")
                    if (value.account != previous.account) add("account")
                    if (value.amount != previous.amount || value.currency != previous.currency) add("amount")
                    if (value.note != previous.note) add("note")
                    if (value.merchant != previous.merchant) add("merchant")
                    if (value.project != previous.project) add("project")
                    if (value.location != previous.location) add("location")
                    if (value.state != previous.state) add("state")
                }
            }
            value.toView(changes)
        }.reversed()
    }

    private fun dependencies(db: SupportSQLiteDatabase, id: StableId): List<JournalDependencyView> = db.queryList(
        "SELECT parent.uid parent_uid,child.uid child_uid,td.dependency_type,child.lifecycle_state," +
            "COALESCE(NULLIF(parent_revision.note,''),parent_category.name) parent_label," +
            "COALESCE(NULLIF(child_revision.note,''),child_category.name) child_label FROM transaction_dependency td " +
            "JOIN business_transaction parent ON parent.id=td.parent_transaction_id JOIN business_transaction child ON child.id=td.child_transaction_id " +
            "JOIN transaction_revision parent_revision ON parent_revision.id=parent.current_revision_id " +
            "JOIN transaction_revision child_revision ON child_revision.id=child.current_revision_id " +
            "LEFT JOIN category parent_category ON parent_category.id=parent_revision.category_id " +
            "LEFT JOIN category child_category ON child_category.id=child_revision.category_id " +
            "WHERE parent.uid=? OR child.uid=? ORDER BY td.dependency_type,parent.uid,child.uid",
        arrayOf(id.bytes, id.bytes),
    ) { c ->
        JournalDependencyView(
            c.stableId("parent_uid"),
            c.stableId("child_uid"),
            TransactionDependencyType.entries[c.jInt("dependency_type")],
            TransactionLifecycleState.entries[c.jInt("lifecycle_state")],
            c.nullableString("parent_label"),
            c.nullableString("child_label"),
        )
    }

    private fun purgeAssessment(db: SupportSQLiteDatabase, id: StableId, now: Instant): JournalPurgeAssessment {
        val lifecycle = db.queryOne("SELECT lifecycle_state,purge_after,id FROM business_transaction WHERE uid=?", arrayOf(id.bytes)) {
            Triple(TransactionLifecycleState.entries[it.getInt(0)], it.nullableLong("purge_after")?.let(Instant::ofEpochMilli), it.getLong(2))
        } ?: abort(FinanceDataError.CorruptData)
        val reasons = linkedSetOf<PurgeIneligibilityReason>()
        if (lifecycle.first != TransactionLifecycleState.TRASHED) reasons += PurgeIneligibilityReason.NOT_TRASHED
        if (lifecycle.second == null || now < lifecycle.second) reasons += PurgeIneligibilityReason.RETENTION_NOT_ELAPSED
        val accountNet = db.queryList(
            "SELECT p.ledger_account_id,p.account_currency,SUM(CASE WHEN p.side=0 THEN p.account_amount_minor ELSE -p.account_amount_minor END) net FROM posting p " +
                "JOIN journal_entry je ON je.id=p.journal_entry_id JOIN transaction_revision tr ON tr.id=je.source_revision_id WHERE tr.transaction_id=? GROUP BY p.ledger_account_id,p.account_currency",
            arrayOf(lifecycle.third),
        ) { it.getLong(2) }
        if (accountNet.any { it != 0L }) reasons += PurgeIneligibilityReason.ACCOUNT_NET_NON_ZERO
        val baseNet = db.queryList(
            "SELECT p.base_currency,SUM(CASE WHEN p.side=0 THEN p.base_amount_minor ELSE -p.base_amount_minor END) net FROM posting p " +
                "JOIN journal_entry je ON je.id=p.journal_entry_id JOIN transaction_revision tr ON tr.id=je.source_revision_id WHERE tr.transaction_id=? GROUP BY p.base_currency",
            arrayOf(lifecycle.third),
        ) { it.getLong(1) }
        if (baseNet.any { it != 0L }) reasons += PurgeIneligibilityReason.BASE_NET_NON_ZERO
        val effectNets = listOf(
            "SELECT 'base' currency,SUM(ee.polarity*ee.base_amount_minor) net FROM economic_effect ee JOIN transaction_revision tr ON tr.id=ee.source_revision_id WHERE tr.transaction_id=?",
            "SELECT 'base' currency,SUM(be.polarity*be.base_amount_minor) net FROM budget_effect be JOIN transaction_revision tr ON tr.id=be.source_revision_id WHERE tr.transaction_id=?",
            "SELECT 'base' currency,SUM(pe.polarity*pe.base_amount_minor) net FROM project_effect pe JOIN transaction_revision tr ON tr.id=pe.source_revision_id WHERE tr.transaction_id=?",
            "SELECT ge.currency_code currency,SUM(ge.polarity*ge.amount_minor) net FROM goal_effect ge JOIN transaction_revision tr ON tr.id=ge.source_revision_id WHERE tr.transaction_id=? GROUP BY ge.currency_code",
            "SELECT se.currency_code currency,SUM(se.polarity*se.amount_minor) net FROM statement_effect se JOIN transaction_revision tr ON tr.id=se.source_revision_id WHERE tr.transaction_id=? GROUP BY se.currency_code",
            "SELECT 'base' currency,SUM(le.polarity*le.base_amount_minor) net FROM loan_effect le JOIN transaction_revision tr ON tr.id=le.source_revision_id WHERE tr.transaction_id=?",
            "SELECT ste.currency_code currency,SUM(ste.signed_delta_minor) net FROM settlement_effect ste JOIN transaction_revision tr ON tr.id=ste.source_revision_id WHERE tr.transaction_id=? GROUP BY ste.currency_code",
        ).flatMap { sql -> db.queryList(sql, arrayOf(lifecycle.third)) { it.getLong(1) } }
        if (effectNets.any { it != 0L }) reasons += PurgeIneligibilityReason.EFFECT_NET_NON_ZERO
        val dependencyCount = listOf(
            "SELECT COUNT(*) FROM transaction_dependency WHERE parent_transaction_id=?1 OR child_transaction_id=?2",
            "SELECT COUNT(*) FROM installment_plan WHERE purchase_transaction_id=?1 OR purchase_transaction_id=?2",
            "SELECT COUNT(*) FROM recurrence_occurrence WHERE transaction_id=?1 OR transaction_id=?2",
            "SELECT COUNT(*) FROM account_balance_checkpoint WHERE adjustment_transaction_id=?1 OR adjustment_transaction_id=?2",
            "SELECT COUNT(*) FROM refund_allocation WHERE (refund_transaction_id=?1 AND original_transaction_id<>?2) OR " +
                "(original_transaction_id=?1 AND refund_transaction_id<>?2)",
        ).sumOf { sql -> db.queryOne(sql, arrayOf(lifecycle.third, lifecycle.third)) { it.getLong(0) } ?: 0L }
        if (dependencyCount > 0L) reasons += PurgeIneligibilityReason.DEPENDENCIES_OPEN
        val operationRefs = (db.queryOne("SELECT COUNT(*) FROM import_source_reference WHERE transaction_id=?", arrayOf(lifecycle.third)) { it.getLong(0) } ?: 0L) +
            (
                db.queryOne(
                    "SELECT COUNT(*) FROM merge_conflict WHERE entity_type=? AND entity_uid=? AND resolution IS NULL",
                    arrayOf(app.ledger.finance.domain.EntityType.TRANSACTION.ordinal, id.bytes),
                ) { it.getLong(0) } ?: 0L
                )
        if (operationRefs > 0) reasons += PurgeIneligibilityReason.OPERATION_REFERENCE
        val backupReads = db.queryOne(
            "SELECT COUNT(*) FROM transaction_revision tr JOIN transaction_revision_attachment tra ON tra.revision_id=tr.id " +
                "JOIN attachment a ON a.id=tra.attachment_id JOIN encrypted_blob eb ON eb.id=a.blob_id JOIN backup_object bo ON bo.content_hash=eb.plaintext_sha256 " +
                "JOIN backup_snapshot_object bso ON bso.object_id=bo.id JOIN backup_snapshot bs ON bs.id=bso.snapshot_id WHERE tr.transaction_id=? AND bs.state IN (0,1)",
            arrayOf(lifecycle.third),
        ) { it.getLong(0) } ?: 0L
        if (backupReads > 0) reasons += PurgeIneligibilityReason.ATTACHMENTS_READ_BY_BACKUP
        return JournalPurgeAssessment(id, now, lifecycle.second, reasons)
    }

    private suspend fun <T> withDatabase(bookId: StableId, block: suspend (LedgerDatabase) -> T): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
            try {
                DomainResult.Success(block(database))
            } finally {
                database.close()
            }
        }
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(FinanceDataError.NumericRangeExceeded)
    } catch (failure: Exception) {
        DomainResult.Failure(failure.toFinanceDatabaseError())
    }

    private fun <T> withKeys(bookId: StableId, block: (app.ledger.core.security.DeviceLedgerKeys) -> T): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys -> DomainResult.Success(block(keys)) }
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    private fun derivedId(seed: StableId, label: String): StableId = StableId.fromBytes(
        MessageDigest.getInstance("SHA-256").digest(seed.bytes + label.toByteArray(Charsets.UTF_8)).copyOf(StableId.BYTE_COUNT),
    ).valueOrAbort()

    private fun zeroHash(): Hash256 = Hash256.fromBytes(ByteArray(32)).valueOrAbort()

    private data class DependentRefundSource(
        val source: ReferenceEditSource,
        val resolution: DependencyResolution,
    )

    private fun app.ledger.finance.domain.TransactionRevision.toContextInput(): TransactionContextInput = TransactionContextInput(
        occurredAt, accrualDate, budgetMonth, merchantId, projectId, goalId, locationRecordId, note, amountExpression,
        source, sourceReferenceId, statementAssignment, attachmentIds,
    )

    private fun TransactionPayload.withClassification(assignment: CategoryAssignment?): TransactionPayload = when (this) {
        is ExpensePayload -> copy(classification = assignment ?: abort(DomainViolation.InvalidField("bulk.category.required")))
        is IncomePayload -> copy(classification = assignment ?: abort(DomainViolation.InvalidField("bulk.category.required")))
        is RefundPayload -> copy(classification = assignment)
        is LoanPaymentPayload -> copy(classification = assignment)
        is FxExchangePayload -> copy(classification = assignment)
        else -> if (assignment == classification) this else abort(DomainViolation.InvalidField("bulk.category.transactionKind"))
    }

    private fun <A, B> JournalFieldUpdate<A>.updateNullable(original: B?, transform: (A) -> B?): B? = when (this) {
        JournalFieldUpdate.Unchanged -> original
        JournalFieldUpdate.Clear -> null
        is JournalFieldUpdate.Set -> transform(value)
    }

    private data class DetailBase(
        val revisionId: StableId,
        val createdAt: Instant,
        val modifiedAt: Instant,
        val zoneId: String,
        val expression: String?,
        val note: String?,
        val merchant: String?,
        val project: String?,
        val place: String?,
        val statisticalNature: String?,
        val source: Int,
        val purgeAfter: Instant?,
    ) {
        companion object {
            fun from(c: Cursor) = DetailBase(
                c.stableId("revision_uid"), Instant.ofEpochMilli(c.jLong("created_at")), Instant.ofEpochMilli(c.jLong("modified_at")),
                c.jString("zone_id"), c.nullableString("amount_expression"), c.nullableString("note"), c.nullableString("merchant_name"),
                c.nullableString("project_name"), c.nullableString("place_name"), c.nullableLong("statistical_nature_snapshot")?.let { app.ledger.finance.domain.StatisticalNature.entries[it.toInt()].name },
                c.jInt("source_type"), c.nullableLong("purge_after")?.let(Instant::ofEpochMilli),
            )
        }
    }

    private data class RevisionRaw(
        val id: StableId,
        val number: Int,
        val action: RevisionAction,
        val state: TransactionLifecycleState,
        val createdAt: Instant,
        val occurredAt: Instant,
        val category: String?,
        val account: String?,
        val amount: Long?,
        val currency: CurrencyCode?,
        val note: String?,
        val merchant: Long?,
        val project: Long?,
        val location: Long?,
        val source: TransactionSource,
    ) {
        fun toView(changes: List<String>) = JournalRevisionView(id, number, action, state, createdAt, occurredAt, category, account, amount, currency, changes, source)
        companion object {
            fun from(c: Cursor) = RevisionRaw(
                c.stableId("uid"), c.jInt("revision_no"), RevisionAction.entries[c.jInt("action")], TransactionLifecycleState.entries[c.jInt("resulting_state")],
                Instant.ofEpochMilli(c.jLong("created_at")), Instant.ofEpochMilli(c.jLong("occurred_at")), c.nullableString("category_name"), c.nullableString("account_name"),
                c.nullableLong("amount_minor"), c.nullableString("currency_code")?.let(::currency), c.nullableString("note"), c.nullableLong("merchant_id"), c.nullableLong("project_id"), c.nullableLong("location_record_id"), TransactionSource.entries[c.jInt("source_type")],
            )
        }
    }

    private companion object {
        const val BULK_QUERY_PAGE_SIZE = 200
        const val BULK_FACT_ID_RESERVE = 1_024
        const val BULK_FX_ID_RESERVE = 64
    }
}

private class JournalWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

private fun currency(value: String): CurrencyCode = CurrencyCode.parse(value).valueOrAbort()
private fun Cursor.jInt(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun Cursor.jLong(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.jString(name: String): String = getString(getColumnIndexOrThrow(name))
private fun <T> DomainResult<DomainResult<T>>.flatten(): DomainResult<T> = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> this
}
