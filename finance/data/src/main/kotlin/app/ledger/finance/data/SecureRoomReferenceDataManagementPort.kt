@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "TooManyFunctions",
    "ComplexMethod",
    "ComplexCondition",
    "LargeClass",
    "MaxLineLength",
    "SpreadOperator",
    "UnusedParameter",
)

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.application.AccountGoalReferenceView
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.AccountTransactionReferenceView
import app.ledger.finance.application.CardReferenceView
import app.ledger.finance.application.CategoryReferenceView
import app.ledger.finance.application.CheckpointReferenceView
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.GoalDraft
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.LocationReferenceView
import app.ledger.finance.application.MerchantReferenceView
import app.ledger.finance.application.PlaceReferenceView
import app.ledger.finance.application.ProjectDraft
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.domain.AccountUsage
import app.ledger.finance.domain.BatchFinancialCommand
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryRemovalStrategy
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.CommitKind
import app.ledger.finance.domain.DebitCredit
import app.ledger.finance.domain.EditTransactionCommand
import app.ledger.finance.domain.EntityChangeOperation
import app.ledger.finance.domain.EntityRevisionAction
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.ExpensePayload
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FxExchangePayload
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.IncomePayload
import app.ledger.finance.domain.LedgerAccountClass
import app.ledger.finance.domain.LedgerOwnerType
import app.ledger.finance.domain.LoanPaymentPayload
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.ProjectStatusPolicy
import app.ledger.finance.domain.ReferenceDataPolicies
import app.ledger.finance.domain.ReferenceDataViolation
import app.ledger.finance.domain.RefundPayload
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionPayload
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Instant

/** SQLCipher-backed, revision-audited owner for non-financial account/reference-data mutations. */
public class SecureRoomReferenceDataManagementPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) : ReferenceDataManagementPort {
    private val applicationContext = context.applicationContext
    private val projections = RoomProjectionEngine()
    private val financialWriteGate = ReferenceFinancialWriteGate()
    private val financialSnapshotMapper = RoomReferenceFinancialSnapshotMapper()

    override suspend fun snapshot(bookId: StableId): DomainResult<ReferenceDataSnapshot> = withDatabase(bookId) { database ->
        database.readLedger { connection -> readSnapshot(connection, bookId) }
    }

    override suspend fun mutate(command: ReferenceMutationCommand): DomainResult<Unit> = withDatabase(command.ids.bookId) { database ->
        when (val mutation = command.mutation) {
            is ReferenceMutation.RemoveCategory -> if (mutation.strategy == CategoryRemovalStrategy.REASSIGN) {
                mutateCategoryWithFinancialBatch(database, command.ids, mutation).valueOrAbort()
            } else {
                mutateReferenceOnly(database, command)
            }
            is ReferenceMutation.SplitPlace -> mutatePlaceSplit(database, command.ids, mutation).valueOrAbort()
            else -> mutateReferenceOnly(database, command)
        }
    }

    private fun mutateReferenceOnly(database: LedgerDatabase, command: ReferenceMutationCommand): Unit = database.inLedgerTransaction { connection ->
        val book = requireBook(connection, command.ids.bookId)
        if (book.localRevision != command.ids.expectedLocalRevision) abort(ReferenceDataViolation.StaleRevision)
        val nextRevision = Math.addExact(book.localRevision, 1L)
        when (val mutation = command.mutation) {
            is ReferenceMutation.SaveAccount -> saveAccount(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.ArchiveAccount -> archiveAccount(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.DeleteEmptyAccount -> deleteAccount(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.SaveCard -> saveCard(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.ArchiveCard -> archiveCard(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.ReplaceCard -> replaceCard(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.SaveCategory -> saveCategory(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.ReorderCategories -> reorderCategories(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.RemoveCategory -> removeCategory(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.SaveMerchant -> saveMerchant(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.MergeMerchant -> mergeMerchant(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.SavePlace -> savePlace(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.MergePlace -> mergePlace(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.SplitPlace -> splitPlace(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.SaveCheckpoint -> saveCheckpoint(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.SaveProject -> saveProject(connection, command.ids, mutation.draft, book, nextRevision)
            is ReferenceMutation.ChangeProjectStatus -> changeProjectStatus(connection, command.ids, mutation, book, nextRevision)
            is ReferenceMutation.SaveGoal -> saveGoal(connection, command.ids, mutation.draft, book, nextRevision)
        }
    }

    private suspend fun mutateCategoryWithFinancialBatch(
        database: LedgerDatabase,
        ids: ReferenceMutationIds,
        mutation: ReferenceMutation.RemoveCategory,
    ): DomainResult<Unit> {
        val affected = database.readLedger { db ->
            validateCategoryReassignment(db, mutation)
            db.queryList(
                "SELECT bt.uid FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                    "JOIN category c ON c.id=tr.category_id WHERE c.uid=? AND bt.lifecycle_state=0 ORDER BY bt.uid",
                arrayOf(mutation.categoryId.bytes),
            ) { it.stableId("uid") }
        }
        if (affected.isEmpty()) {
            mutateReferenceOnly(database, ReferenceMutationCommand(ids, mutation))
            return DomainResult.Success(Unit)
        }
        val target = database.readLedger { db ->
            db.queryOne("SELECT c.uid,c.direction,c.statistical_nature FROM category c WHERE c.uid=?", arrayOf(mutation.targetCategoryId?.bytes)) { cursor ->
                app.ledger.finance.domain.CategoryAssignment(
                    app.ledger.finance.domain.CategoryId(cursor.stableId("uid")),
                    CategoryDirection.entries[cursor.getInt(1)],
                    StatisticalNature.entries[cursor.getInt(2)],
                )
            } ?: abort(ReferenceDataViolation.InvalidField("category.reassignTarget"))
        }
        return executeFinancialBatch(
            database,
            ids,
            affected,
            replacement = { source, _ ->
                val replacementPayload = source.revision.payload.withClassification(target)
                NewTransactionInput(source.revision.toContextInput(), replacementPayload)
            },
            sideEffect = FinancialCommitSideEffect { db, _ ->
                val row = validateCategoryReassignment(db, mutation)
                val snapshot = canonical("category", mutation.categoryId.toString(), CategoryStatus.DELETED_TOMBSTONE.name, "reassigned")
                db.execSQL(
                    "UPDATE category SET status=?,last_commit_id=?,row_version=row_version+1 WHERE id=? AND row_version=?",
                    arrayOf<Any>(CategoryStatus.DELETED_TOMBSTONE.ordinal, db.requireInternalId("book_commit", ids.commitId), row.first, row.third),
                )
                audit(db, ids, EntityType.CATEGORY, mutation.categoryId, nextEntityRevision(db, EntityType.CATEGORY, mutation.categoryId), EntityRevisionAction.DELETE, EntityChangeOperation.DELETE, null, snapshot)
            },
        )
    }

    private suspend fun mutatePlaceSplit(
        database: LedgerDatabase,
        ids: ReferenceMutationIds,
        mutation: ReferenceMutation.SplitPlace,
    ): DomainResult<Unit> {
        val replacementBySource = mutation.locationRecordIds.zip(mutation.replacementLocationRecordIds).toMap()
        val affected = database.readLedger { db ->
            validatePlaceSplit(db, mutation)
            db.queryList(
                "SELECT bt.uid transaction_uid,lr.uid location_uid FROM business_transaction bt " +
                    "JOIN transaction_revision tr ON tr.id=bt.current_revision_id JOIN location_record lr ON lr.id=tr.location_record_id " +
                    "WHERE bt.lifecycle_state=0 AND lr.uid IN (${mutation.locationRecordIds.joinToString(",") { "?" }}) ORDER BY bt.uid",
                mutation.locationRecordIds.map { it.bytes }.toTypedArray(),
            ) { cursor -> cursor.stableId("transaction_uid") to cursor.stableId("location_uid") }
        }
        if (affected.isEmpty()) {
            mutateReferenceOnly(database, ReferenceMutationCommand(ids, mutation))
            return DomainResult.Success(Unit)
        }
        return executeFinancialBatch(
            database,
            ids,
            affected.map(Pair<StableId, StableId>::first),
            replacement = { source, index ->
                val oldLocation = affected[index].second
                val targetLocation = replacementBySource[oldLocation] ?: abort(FinanceDataError.CorruptData)
                NewTransactionInput(source.revision.toContextInput().copy(locationRecordId = app.ledger.finance.domain.LocationRecordId(targetLocation)), source.revision.payload)
            },
            sideEffect = FinancialCommitSideEffect { db, _ ->
                validatePlaceSplit(db, mutation)
                persistPlaceSplit(db, ids, mutation)
            },
        )
    }

    private suspend fun executeFinancialBatch(
        database: LedgerDatabase,
        ids: ReferenceMutationIds,
        transactionIds: List<StableId>,
        replacement: (ReferenceEditSource, Int) -> NewTransactionInput<TransactionPayload>,
        sideEffect: FinancialCommitSideEffect,
    ): DomainResult<Unit> {
        val sources = database.readLedger { db ->
            transactionIds.mapIndexed { index, transactionId ->
                try {
                    financialSnapshotMapper.load(
                        db,
                        transactionId,
                        derivedId(ids.commitId, "revision:$index"),
                        ids.commitId,
                        List(1_024) { factIndex -> derivedId(ids.commitId, "fact:$index:$factIndex") },
                        List(64) { fxIndex -> derivedId(ids.commitId, "fx:$index:$fxIndex") },
                        ids.changedAt,
                        ids.deviceInstanceId,
                    )
                } catch (failure: FinancialPersistenceAbort) {
                    if (failure.domainError == FinanceDataError.CorruptData) {
                        abort(ReferenceDataViolation.InvalidField("referenceBatch.snapshot"))
                    }
                    throw failure
                }
            }
        }
        val children = sources.mapIndexed { index, source ->
            val draft = EditTransactionCommand(
                commandId = CommandId(derivedId(ids.commitId, "child-command:$index")),
                expectedRevisionId = source.revision.id,
                payloadHash = app.ledger.finance.domain.Hash256.fromBytes(ByteArray(32)).valueOrAbort(),
                transactionId = source.revision.transactionId,
                replacement = replacement(source, index),
                dependencyResolutions = emptyList(),
            )
            draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        }
        val batchDraft = BatchFinancialCommand(
            CommandId(derivedId(ids.commitId, "batch-command")),
            app.ledger.finance.domain.Hash256.fromBytes(ByteArray(32)).valueOrAbort(),
            children,
        )
        val batch = batchDraft.copy(payloadHash = CanonicalFinancialHash.command(batchDraft))
        val first = sources.first().snapshot
        val root = PlanningSnapshot(
            book = first.book,
            currentTransaction = null,
            currentRevision = null,
            dependencies = sources.flatMap { it.snapshot.dependencies },
            reversedApplyEntryIds = sources.flatMap { it.snapshot.reversedApplyEntryIds }.toSet(),
            refundStatuses = emptyList(),
            budgetRevision = null,
            participants = emptyList(),
            batchSnapshots = sources.map(ReferenceEditSource::snapshot),
        )
        val repository = RoomFinancialCommitRepository(database, sideEffect = sideEffect)
        val result = DefaultFinancialMutationCoordinator(
            financialWriteGate,
            repository,
            object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(root)
            },
            FinancialPlanningPort(app.ledger.finance.domain.DeterministicFinancialPlanner::plan),
            repository,
        ).execute(batch)
        return when (result) {
            is DomainResult.Success -> DomainResult.Success(Unit)
            is DomainResult.Failure -> if (result.error == FinanceDataError.CorruptData) {
                DomainResult.Failure(ReferenceDataViolation.InvalidField("referenceBatch.commit"))
            } else {
                result
            }
        }
    }

    private fun readSnapshot(connection: SupportSQLiteDatabase, bookId: StableId): ReferenceDataSnapshot {
        val book = requireBook(connection, bookId)
        val baseCurrency = CurrencyCode.parse(book.baseCurrency).valueOrAbort()
        val accounts = connection.queryList(
            """
            SELECT ua.uid, ua.type, ua.name, ua.currency_code, ua.status, ua.institution_name, ua.branch_name,
              ua.opened_date, ua.icon_key, ua.color_argb, ua.sort_order, ua.row_version,
              COALESCE(abc.normal_balance_minor, 0) balance_minor, avc.current_base_value_minor, avc.rate_quoted_at,
              avc.rate_decimal,
              EXISTS(SELECT 1 FROM posting p WHERE p.ledger_account_id = ua.ledger_account_id) has_postings,
              (SELECT COUNT(*) FROM payment_card pc WHERE pc.account_id = ua.id) card_count
            FROM user_account ua LEFT JOIN account_balance_current abc ON abc.account_id = ua.id
              LEFT JOIN account_valuation_current avc ON avc.account_id = ua.id
            ORDER BY ua.sort_order, ua.id
            """.trimIndent(),
        ) { cursor ->
            AccountReferenceView(
                id = cursor.stableId("uid"),
                type = UserAccountType.entries[cursor.getInt(cursor.getColumnIndexOrThrow("type"))],
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                currency = CurrencyCode.parse(cursor.getString(cursor.getColumnIndexOrThrow("currency_code"))).valueOrAbort(),
                status = EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))],
                institutionName = cursor.nullableString("institution_name"),
                branchName = cursor.nullableString("branch_name"),
                openedOn = cursor.nullableLong("opened_date")?.toInt()?.toStoredLocalDate(),
                iconKey = cursor.getString(cursor.getColumnIndexOrThrow("icon_key")),
                colorArgb = cursor.getInt(cursor.getColumnIndexOrThrow("color_argb")),
                sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow("sort_order")),
                rowVersion = cursor.getLong(cursor.getColumnIndexOrThrow("row_version")),
                balanceMinor = cursor.getLong(cursor.getColumnIndexOrThrow("balance_minor")),
                currentBaseValueMinor = cursor.nullableLong("current_base_value_minor"),
                valuationQuotedAt = cursor.nullableLong("rate_quoted_at")?.toStoredInstant(),
                hasFinancialPostings = cursor.getInt(cursor.getColumnIndexOrThrow("has_postings")) == 1,
                cardCount = cursor.getLong(cursor.getColumnIndexOrThrow("card_count")),
                currentValuationRate = cursor.nullableString("rate_decimal")?.toBigDecimal(),
            )
        }
        val cards = readCards(connection)
        val categories = readCategories(connection)
        val merchants = readMerchants(connection)
        val places = readPlaces(connection)
        val locations = readLocations(connection)
        val checkpoints = readCheckpoints(connection)
        val accountTransactions = readAccountTransactions(connection)
        val accountGoals = readAccountGoals(connection)
        val missingValuation = accounts.any { it.status == EntityStatus.ACTIVE && it.currency != baseCurrency && it.currentBaseValueMinor == null }
        val core = if (missingValuation) null else checkedNetPosition(accounts, baseCurrency)
        val settlement = connection.queryList(
            "SELECT sap.net_position_minor, sa.settlement_currency FROM settlement_position_projection sap " +
                "JOIN settlement_activity sa ON sa.id = sap.activity_id JOIN participant p ON p.id = sap.participant_id WHERE p.is_self = 1",
        ) { it.getLong(0) to it.getString(1) }
        val settlementMissing = settlement.any { it.second != baseCurrency.value }
        val settlementNet = if (settlementMissing) null else CheckedArithmetic.sum(settlement.map(Pair<Long, String>::first)).valueOrAbort()
        val adjusted = if (core == null || settlementNet == null) null else CheckedArithmetic.add(core, settlementNet).valueOrAbort()
        return ReferenceDataSnapshot(
            bookId = bookId,
            baseCurrency = baseCurrency,
            localRevision = book.localRevision,
            accounts = accounts,
            cards = cards,
            categories = categories,
            merchants = merchants,
            places = places,
            locations = locations,
            checkpoints = checkpoints,
            accountTransactions = accountTransactions,
            accountGoals = accountGoals,
            coreNetFinancialAssetsMinor = core,
            adjustedNetFinancialPositionMinor = adjusted,
            valuationMissing = missingValuation || settlementMissing,
            valuationRevision = book.valuationRevision,
        )
    }

    private fun readCards(connection: SupportSQLiteDatabase): List<CardReferenceView> = connection.queryList(
        """
        SELECT pc.uid, ua.uid account_uid, pc.card_type, pc.display_name, pc.last_four, pc.status,
          replacement.uid replacement_uid, pc.icon_key, pc.color_argb, pc.sort_order, pc.row_version,
          (SELECT COUNT(*) FROM current_transaction_projection ctp WHERE ctp.card_id = pc.id) history_count
        FROM payment_card pc JOIN user_account ua ON ua.id = pc.account_id
          LEFT JOIN payment_card replacement ON replacement.id = pc.replacement_of_card_id
        ORDER BY pc.sort_order, pc.id
        """.trimIndent(),
    ) { cursor ->
        CardReferenceView(
            cursor.stableId("uid"),
            cursor.stableId("account_uid"),
            CardType.entries[cursor.getInt(cursor.getColumnIndexOrThrow("card_type"))],
            cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
            cursor.nullableString("last_four"),
            EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))],
            cursor.nullableStableId("replacement_uid"),
            cursor.getString(cursor.getColumnIndexOrThrow("icon_key")),
            cursor.getInt(cursor.getColumnIndexOrThrow("color_argb")),
            cursor.getInt(cursor.getColumnIndexOrThrow("sort_order")),
            cursor.getLong(cursor.getColumnIndexOrThrow("row_version")),
            cursor.getLong(cursor.getColumnIndexOrThrow("history_count")),
        )
    }

    private fun readCategories(connection: SupportSQLiteDatabase): List<CategoryReferenceView> = connection.queryList(
        """
        SELECT c.uid, c.direction, parent.uid parent_uid, c.depth, c.name, c.icon_key, c.color_argb, c.sort_order,
          c.status, c.statistical_nature, da.uid default_account_uid, dc.uid default_card_uid,
          dm.uid default_merchant_uid, c.row_version,
          (SELECT COUNT(*) FROM transaction_revision tr WHERE tr.category_id = c.id) history_count,
          (SELECT COUNT(*) FROM category child WHERE child.parent_id = c.id) child_count
        FROM category c LEFT JOIN category parent ON parent.id = c.parent_id
          LEFT JOIN user_account da ON da.id = c.default_account_id LEFT JOIN payment_card dc ON dc.id = c.default_card_id
          LEFT JOIN merchant dm ON dm.id = c.default_merchant_id
        ORDER BY c.direction, c.sort_order, c.id
        """.trimIndent(),
    ) { cursor ->
        CategoryReferenceView(
            cursor.stableId("uid"),
            CategoryDirection.entries[cursor.getInt(cursor.getColumnIndexOrThrow("direction"))],
            cursor.nullableStableId("parent_uid"),
            cursor.getInt(cursor.getColumnIndexOrThrow("depth")),
            cursor.getString(cursor.getColumnIndexOrThrow("name")),
            cursor.getString(cursor.getColumnIndexOrThrow("icon_key")),
            cursor.getInt(cursor.getColumnIndexOrThrow("color_argb")),
            cursor.getInt(cursor.getColumnIndexOrThrow("sort_order")),
            CategoryStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))],
            StatisticalNature.entries[cursor.getInt(cursor.getColumnIndexOrThrow("statistical_nature"))],
            cursor.nullableStableId("default_account_uid"),
            cursor.nullableStableId("default_card_uid"),
            cursor.nullableStableId("default_merchant_uid"),
            cursor.getLong(cursor.getColumnIndexOrThrow("row_version")),
            cursor.getLong(cursor.getColumnIndexOrThrow("history_count")),
            cursor.getLong(cursor.getColumnIndexOrThrow("child_count")),
        )
    }

    private fun readMerchants(connection: SupportSQLiteDatabase): List<MerchantReferenceView> = connection.queryList(
        """
        SELECT m.uid, m.name, m.status, merged.uid merged_uid, m.row_version,
          (SELECT COUNT(*) FROM current_transaction_projection ctp WHERE ctp.merchant_id = m.id) transaction_count,
          (SELECT COUNT(*) FROM place p WHERE p.merchant_id = m.id) place_count
        FROM merchant m LEFT JOIN merchant merged ON merged.id = m.merged_into_id ORDER BY m.name, m.id
        """.trimIndent(),
    ) { cursor ->
        val id = cursor.stableId("uid")
        MerchantReferenceView(
            id,
            cursor.getString(cursor.getColumnIndexOrThrow("name")),
            connection.queryList("SELECT alias FROM merchant_alias WHERE merchant_id = (SELECT id FROM merchant WHERE uid = ?) ORDER BY normalized_alias", arrayOf(id.bytes)) { it.getString(0) },
            EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))],
            cursor.nullableStableId("merged_uid"),
            cursor.getLong(cursor.getColumnIndexOrThrow("row_version")),
            cursor.getLong(cursor.getColumnIndexOrThrow("transaction_count")),
            cursor.getLong(cursor.getColumnIndexOrThrow("place_count")),
        )
    }

    private fun readPlaces(connection: SupportSQLiteDatabase): List<PlaceReferenceView> = connection.queryList(
        """
        SELECT p.uid, p.name, p.center_lat_e7, p.center_lon_e7,
          COALESCE(resolved_merchant.uid, m.uid) merchant_uid, p.status,
          merged.uid merged_uid, p.row_version,
          (
            WITH RECURSIVE place_descendant(id) AS (
              SELECT p.id
              UNION ALL
              SELECT child.id FROM place child JOIN place_descendant parent ON child.merged_into_id = parent.id
            )
            SELECT COUNT(*) FROM location_record lr WHERE lr.place_id IN (SELECT id FROM place_descendant)
          ) location_count
        FROM place p LEFT JOIN merchant m ON m.id = p.merchant_id
          LEFT JOIN merchant resolved_merchant ON resolved_merchant.id = (
            WITH RECURSIVE merchant_chain(id, merged_into_id) AS (
              SELECT id, merged_into_id FROM merchant WHERE id = p.merchant_id
              UNION ALL
              SELECT next.id, next.merged_into_id FROM merchant next
                JOIN merchant_chain current ON next.id = current.merged_into_id
            )
            SELECT id FROM merchant_chain WHERE merged_into_id IS NULL LIMIT 1
          )
          LEFT JOIN place merged ON merged.id = p.merged_into_id
        ORDER BY p.name, p.id
        """.trimIndent(),
    ) { cursor ->
        PlaceReferenceView(
            cursor.stableId("uid"),
            cursor.getString(cursor.getColumnIndexOrThrow("name")),
            cursor.getInt(cursor.getColumnIndexOrThrow("center_lat_e7")),
            cursor.getInt(cursor.getColumnIndexOrThrow("center_lon_e7")),
            cursor.nullableStableId("merchant_uid"),
            EntityStatus.entries[cursor.getInt(cursor.getColumnIndexOrThrow("status"))],
            cursor.nullableStableId("merged_uid"),
            cursor.getLong(cursor.getColumnIndexOrThrow("row_version")),
            cursor.getLong(cursor.getColumnIndexOrThrow("location_count")),
        )
    }

    private fun readCheckpoints(connection: SupportSQLiteDatabase): List<CheckpointReferenceView> = connection.queryList(
        """
        SELECT cp.uid, ua.uid account_uid, cp.as_of_instant, cp.as_of_local_date, cp.observed_amount_minor,
          cp.calculated_amount_minor, cp.difference_minor, COALESCE(bt.uid, derived_bt.uid) adjustment_uid
        FROM account_balance_checkpoint cp JOIN user_account ua ON ua.id = cp.account_id
          LEFT JOIN business_transaction bt ON bt.id = cp.adjustment_transaction_id
          LEFT JOIN balance_adjustment_revision_detail bad ON bad.checkpoint_id = cp.id
          LEFT JOIN business_transaction derived_bt ON derived_bt.current_revision_id = bad.revision_id
        ORDER BY cp.as_of_instant DESC
        """.trimIndent(),
    ) { cursor ->
        CheckpointReferenceView(
            cursor.stableId("uid"),
            cursor.stableId("account_uid"),
            cursor.getLong(cursor.getColumnIndexOrThrow("as_of_instant")).toStoredInstant(),
            cursor.getInt(cursor.getColumnIndexOrThrow("as_of_local_date")).toStoredLocalDate(),
            cursor.getLong(cursor.getColumnIndexOrThrow("observed_amount_minor")),
            cursor.getLong(cursor.getColumnIndexOrThrow("calculated_amount_minor")),
            cursor.getLong(cursor.getColumnIndexOrThrow("difference_minor")),
            cursor.nullableStableId("adjustment_uid"),
        )
    }

    private fun readLocations(connection: SupportSQLiteDatabase): List<LocationReferenceView> = connection.queryList(
        """
        SELECT lr.uid, lr.lat_e7, lr.lon_e7, lr.captured_at, p.uid place_uid,
          (SELECT COUNT(*) FROM current_transaction_projection ctp
            JOIN transaction_revision tr ON tr.id = ctp.current_revision_id
            WHERE tr.location_record_id = lr.id) current_transaction_count
        FROM location_record lr LEFT JOIN place p ON p.id = lr.place_id
        ORDER BY lr.captured_at DESC, lr.id
        """.trimIndent(),
    ) { cursor ->
        LocationReferenceView(
            cursor.stableId("uid"),
            cursor.getInt(cursor.getColumnIndexOrThrow("lat_e7")),
            cursor.getInt(cursor.getColumnIndexOrThrow("lon_e7")),
            cursor.getLong(cursor.getColumnIndexOrThrow("captured_at")).toStoredInstant(),
            cursor.nullableStableId("place_uid"),
            cursor.getLong(cursor.getColumnIndexOrThrow("current_transaction_count")),
        )
    }

    private fun readAccountTransactions(connection: SupportSQLiteDatabase): List<AccountTransactionReferenceView> = connection.queryList(
        """
        WITH account_impacts AS (
          SELECT bt.uid transaction_uid, tr.uid revision_uid, ua.uid account_uid,
            tr.local_date, tr.occurred_at, bt.kind, ua.currency_code,
            SUM(CASE WHEN p.side = la.normal_side THEN p.account_amount_minor ELSE -p.account_amount_minor END) impact_minor,
            ctp.transaction_id
          FROM current_transaction_projection ctp
          JOIN business_transaction bt ON bt.id = ctp.transaction_id
          JOIN transaction_revision tr ON tr.id = ctp.current_revision_id
          JOIN journal_entry je ON je.applies_revision_id = tr.id AND je.entry_role = 0
          JOIN posting p ON p.journal_entry_id = je.id
          JOIN ledger_account la ON la.id = p.ledger_account_id
          JOIN user_account ua ON ua.ledger_account_id = la.id
          WHERE ctp.state = 0
          GROUP BY ctp.transaction_id, ua.id
        ), running AS (
          SELECT *, SUM(impact_minor) OVER (
            PARTITION BY account_uid ORDER BY occurred_at, transaction_id
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
          ) running_minor
          FROM account_impacts
        )
        SELECT * FROM running ORDER BY account_uid, occurred_at DESC, transaction_id DESC
        """.trimIndent(),
    ) { cursor ->
        AccountTransactionReferenceView(
            transactionId = cursor.stableId("transaction_uid"),
            revisionId = cursor.stableId("revision_uid"),
            accountId = cursor.stableId("account_uid"),
            localDate = cursor.getInt(cursor.getColumnIndexOrThrow("local_date")).toStoredLocalDate(),
            occurredAt = cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at")).toStoredInstant(),
            kind = TransactionKind.entries[cursor.getInt(cursor.getColumnIndexOrThrow("kind"))],
            impactMinor = cursor.getLong(cursor.getColumnIndexOrThrow("impact_minor")),
            runningBalanceMinor = cursor.getLong(cursor.getColumnIndexOrThrow("running_minor")),
            currency = CurrencyCode.parse(cursor.getString(cursor.getColumnIndexOrThrow("currency_code"))).valueOrAbort(),
        )
    }

    private fun readAccountGoals(connection: SupportSQLiteDatabase): List<AccountGoalReferenceView> = connection.queryList(
        """
        SELECT g.uid, ua.uid account_uid, g.name, gbp.balance_minor, gbp.target_minor, gbp.currency_code
        FROM goal g JOIN user_account ua ON ua.id = g.account_id
          JOIN goal_balance_projection gbp ON gbp.goal_id = g.id
        WHERE g.status = ? ORDER BY g.due_date IS NULL, g.due_date, g.id
        """.trimIndent(),
        arrayOf(EntityStatus.ACTIVE.ordinal),
    ) { cursor ->
        AccountGoalReferenceView(
            id = cursor.stableId("uid"),
            accountId = cursor.stableId("account_uid"),
            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
            balanceMinor = cursor.getLong(cursor.getColumnIndexOrThrow("balance_minor")),
            targetMinor = cursor.getLong(cursor.getColumnIndexOrThrow("target_minor")),
            currency = CurrencyCode.parse(cursor.getString(cursor.getColumnIndexOrThrow("currency_code"))).valueOrAbort(),
        )
    }

    private fun saveAccount(
        db: SupportSQLiteDatabase,
        ids: ReferenceMutationIds,
        mutation: ReferenceMutation.SaveAccount,
        book: BookRow,
        revision: Long,
    ) {
        val draft = mutation.draft
        validateName(draft.name, "account.name")
        validateIcon(draft.iconKey)
        val existing = db.queryOne(
            "SELECT ua.id, ua.ledger_account_id, ua.type, ua.currency_code, ua.row_version, EXISTS(SELECT 1 FROM posting p WHERE p.ledger_account_id = ua.ledger_account_id) FROM user_account ua WHERE ua.uid = ?",
            arrayOf(draft.accountId.bytes),
        ) { AccountRow(it.getLong(0), it.getLong(1), it.getInt(2), it.getString(3), it.getLong(4), it.getInt(5) == 1) }
        val snapshot = canonicalAccount(draft)
        startCommit(db, ids, book, revision, snapshot)
        if (existing == null) {
            require(draft.expectedRowVersion == null)
            val accountClass = if (draft.type in setOf(UserAccountType.CASH, UserAccountType.BANK)) LedgerAccountClass.ASSET else LedgerAccountClass.LIABILITY
            val ledgerId = db.allocateInternalId("ledger_account", draft.ledgerAccountId)
            db.execSQL(
                "INSERT INTO ledger_account(id, uid, owner_type, account_class, normal_side, currency_code, parent_ledger_account_id, system_code, status, created_commit_id) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)",
                arrayOf<Any>(ledgerId, draft.ledgerAccountId.bytes, LedgerOwnerType.USER_ACCOUNT.ordinal, accountClass.ordinal, normalSide(accountClass).ordinal, draft.currency.value, EntityStatus.ACTIVE.ordinal, db.requireInternalId("book_commit", ids.commitId)),
            )
            db.execSQL(
                "INSERT INTO user_account(id, uid, ledger_account_id, type, name, currency_code, institution_name, branch_name, account_number, opened_date, status, icon_key, color_argb, sort_order, last_commit_id, row_version, content_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                arrayOf<Any?>(db.allocateInternalId("user_account", draft.accountId), draft.accountId.bytes, ledgerId, draft.type.ordinal, draft.name.trim(), draft.currency.value, draft.institutionName.clean(), draft.branchName.clean(), draft.accountNumber.clean(), draft.openedOn?.toStorageInt(), EntityStatus.ACTIVE.ordinal, draft.iconKey, draft.colorArgb, draft.sortOrder, db.requireInternalId("book_commit", ids.commitId), sha256(snapshot)),
            )
            audit(db, ids, EntityType.ACCOUNT, draft.accountId, 1, EntityRevisionAction.CREATE, EntityChangeOperation.CREATE, null, snapshot)
        } else {
            requireVersion(existing.rowVersion, draft.expectedRowVersion)
            if (existing.type != draft.type.ordinal) abort(ReferenceDataViolation.InvalidField("account.type"))
            if (existing.hasPostings && existing.currency != draft.currency.value) abort(ReferenceDataViolation.CurrencyLocked)
            val updated = db.compileStatement(
                "UPDATE user_account SET name=?, currency_code=?, institution_name=?, branch_name=?, account_number=?, opened_date=?, icon_key=?, color_argb=?, sort_order=?, last_commit_id=?, row_version=row_version+1, content_hash=? WHERE id=? AND row_version=?",
            ).apply {
                bindString(1, draft.name.trim())
                bindString(2, draft.currency.value)
                bindNullableString(3, draft.institutionName.clean())
                bindNullableString(4, draft.branchName.clean())
                bindNullableString(5, draft.accountNumber.clean())
                bindNullableLong(6, draft.openedOn?.toStorageInt()?.toLong())
                bindString(7, draft.iconKey)
                bindLong(8, draft.colorArgb.toLong())
                bindLong(9, draft.sortOrder.toLong())
                bindLong(10, db.requireInternalId("book_commit", ids.commitId))
                bindBlob(11, sha256(snapshot))
                bindLong(12, existing.id)
                bindLong(13, existing.rowVersion)
            }.executeUpdateDelete()
            if (updated != 1) abort(ReferenceDataViolation.StaleRevision)
            db.execSQL("UPDATE ledger_account SET currency_code=? WHERE id=?", arrayOf<Any>(draft.currency.value, existing.ledgerId))
            audit(db, ids, EntityType.ACCOUNT, draft.accountId, nextEntityRevision(db, EntityType.ACCOUNT, draft.accountId), EntityRevisionAction.EDIT, EntityChangeOperation.UPDATE, null, snapshot)
        }
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun archiveAccount(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.ArchiveAccount, book: BookRow, revision: Long) {
        val row = accountRow(db, mutation.accountId)
        requireVersion(row.rowVersion, mutation.expectedRowVersion)
        val snapshot = canonical("account", mutation.accountId.toString(), "ARCHIVED", (row.rowVersion + 1).toString())
        startCommit(db, ids, book, revision, snapshot)
        db.execSQL("UPDATE user_account SET status=?, last_commit_id=?, row_version=row_version+1 WHERE id=?", arrayOf<Any>(EntityStatus.ARCHIVED.ordinal, db.requireInternalId("book_commit", ids.commitId), row.id))
        db.execSQL("UPDATE ledger_account SET status=? WHERE id=?", arrayOf<Any>(EntityStatus.ARCHIVED.ordinal, row.ledgerId))
        audit(db, ids, EntityType.ACCOUNT, mutation.accountId, nextEntityRevision(db, EntityType.ACCOUNT, mutation.accountId), EntityRevisionAction.ARCHIVE, EntityChangeOperation.ARCHIVE, null, snapshot)
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun deleteAccount(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.DeleteEmptyAccount, book: BookRow, revision: Long) {
        val row = accountRow(db, mutation.accountId)
        requireVersion(row.rowVersion, mutation.expectedRowVersion)
        val cardCount = count(db, "SELECT COUNT(*) FROM payment_card WHERE account_id=?", row.id)
        val activeCount = count(db, "SELECT COUNT(*) FROM user_account WHERE status=?", EntityStatus.ACTIVE.ordinal.toLong())
        val policy = ReferenceDataPolicies.accountLifecycle(AccountUsage(if (row.hasPostings) 1 else 0, cardCount, activeCount))
        if (!policy.canPermanentlyDelete) abort(ReferenceDataViolation.AccountHasHistory)
        val snapshot = canonical("account", mutation.accountId.toString(), "DELETED")
        startCommit(db, ids, book, revision, snapshot)
        audit(db, ids, EntityType.ACCOUNT, mutation.accountId, nextEntityRevision(db, EntityType.ACCOUNT, mutation.accountId), EntityRevisionAction.DELETE, EntityChangeOperation.DELETE, null, snapshot)
        db.execSQL("DELETE FROM user_account WHERE id=?", arrayOf<Any>(row.id))
        db.execSQL("DELETE FROM ledger_account WHERE id=?", arrayOf<Any>(row.ledgerId))
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun saveCard(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.SaveCard, book: BookRow, revision: Long) {
        val draft = mutation.draft
        validateName(draft.displayName, "card.displayName")
        validateIcon(draft.iconKey)
        val lastFour = draft.lastFour
        if (lastFour != null && !lastFour.matches(Regex("[0-9]{4}"))) abort(ReferenceDataViolation.InvalidField("card.lastFour"))
        val accountId = db.requireInternalId("user_account", draft.accountId)
        val accountType = db.queryOne("SELECT type FROM user_account WHERE id=? AND status=?", arrayOf<Any?>(accountId, EntityStatus.ACTIVE.ordinal)) { UserAccountType.entries[it.getInt(0)] }
            ?: abort(ReferenceDataViolation.InvalidField("card.account"))
        ReferenceDataPolicies.validateCard(draft.type, accountType).valueOrAbort()
        val existing = db.queryOne("SELECT id, row_version FROM payment_card WHERE uid=?", arrayOf(draft.cardId.bytes)) { it.getLong(0) to it.getLong(1) }
        val snapshot = canonicalCard(draft)
        startCommit(db, ids, book, revision, snapshot)
        if (existing == null) {
            require(draft.expectedRowVersion == null)
            db.execSQL(
                "INSERT INTO payment_card(id,uid,account_id,card_type,display_name,last_four,status,replacement_of_card_id,icon_key,color_argb,sort_order,last_commit_id,row_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1)",
                arrayOf<Any?>(db.allocateInternalId("payment_card", draft.cardId), draft.cardId.bytes, accountId, draft.type.ordinal, draft.displayName.trim(), draft.lastFour, EntityStatus.ACTIVE.ordinal, db.optionalInternalId("payment_card", draft.replacementOfId), draft.iconKey, draft.colorArgb, draft.sortOrder, db.requireInternalId("book_commit", ids.commitId)),
            )
            audit(db, ids, EntityType.CARD, draft.cardId, 1, EntityRevisionAction.CREATE, EntityChangeOperation.CREATE, null, snapshot)
        } else {
            requireVersion(existing.second, draft.expectedRowVersion)
            db.execSQL(
                "UPDATE payment_card SET account_id=?,card_type=?,display_name=?,last_four=?,replacement_of_card_id=?,icon_key=?,color_argb=?,sort_order=?,last_commit_id=?,row_version=row_version+1 WHERE id=? AND row_version=?",
                arrayOf<Any?>(accountId, draft.type.ordinal, draft.displayName.trim(), draft.lastFour, db.optionalInternalId("payment_card", draft.replacementOfId), draft.iconKey, draft.colorArgb, draft.sortOrder, db.requireInternalId("book_commit", ids.commitId), existing.first, existing.second),
            )
            audit(db, ids, EntityType.CARD, draft.cardId, nextEntityRevision(db, EntityType.CARD, draft.cardId), EntityRevisionAction.EDIT, EntityChangeOperation.UPDATE, null, snapshot)
        }
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun archiveCard(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.ArchiveCard, book: BookRow, revision: Long) {
        val row = db.queryOne("SELECT id,row_version FROM payment_card WHERE uid=?", arrayOf(mutation.cardId.bytes)) { it.getLong(0) to it.getLong(1) }
            ?: abort(ReferenceDataViolation.InvalidField("card.id"))
        requireVersion(row.second, mutation.expectedRowVersion)
        val snapshot = canonical("card", mutation.cardId.toString(), "ARCHIVED")
        startCommit(db, ids, book, revision, snapshot)
        db.execSQL("UPDATE payment_card SET status=?,last_commit_id=?,row_version=row_version+1 WHERE id=?", arrayOf<Any>(EntityStatus.ARCHIVED.ordinal, db.requireInternalId("book_commit", ids.commitId), row.first))
        audit(db, ids, EntityType.CARD, mutation.cardId, nextEntityRevision(db, EntityType.CARD, mutation.cardId), EntityRevisionAction.ARCHIVE, EntityChangeOperation.ARCHIVE, null, snapshot)
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun replaceCard(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.ReplaceCard, book: BookRow, revision: Long) {
        require(ids.entityRevisionIds.size >= 2)
        val old = db.queryOne("SELECT id,row_version FROM payment_card WHERE uid=? AND status=?", arrayOf(mutation.oldCardId.bytes, EntityStatus.ACTIVE.ordinal)) { it.getLong(0) to it.getLong(1) }
            ?: abort(ReferenceDataViolation.InvalidField("card.replacement"))
        requireVersion(old.second, mutation.oldRowVersion)
        if (mutation.replacement.replacementOfId != mutation.oldCardId || mutation.replacement.expectedRowVersion != null) abort(ReferenceDataViolation.InvalidField("card.replacementOf"))
        val accountId = db.requireInternalId("user_account", mutation.replacement.accountId)
        val accountType = db.queryOne("SELECT type FROM user_account WHERE id=?", arrayOf(accountId)) { UserAccountType.entries[it.getInt(0)] }
            ?: abort(ReferenceDataViolation.InvalidField("card.account"))
        ReferenceDataPolicies.validateCard(mutation.replacement.type, accountType).valueOrAbort()
        val snapshot = canonical("cardReplacement", mutation.oldCardId.toString(), mutation.replacement.cardId.toString())
        startCommit(db, ids, book, revision, snapshot)
        db.execSQL("UPDATE payment_card SET status=?,last_commit_id=?,row_version=row_version+1 WHERE id=?", arrayOf<Any>(EntityStatus.ARCHIVED.ordinal, db.requireInternalId("book_commit", ids.commitId), old.first))
        val draft = mutation.replacement
        db.execSQL(
            "INSERT INTO payment_card(id,uid,account_id,card_type,display_name,last_four,status,replacement_of_card_id,icon_key,color_argb,sort_order,last_commit_id,row_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1)",
            arrayOf<Any?>(db.allocateInternalId("payment_card", draft.cardId), draft.cardId.bytes, accountId, draft.type.ordinal, draft.displayName.trim(), draft.lastFour, EntityStatus.ACTIVE.ordinal, old.first, draft.iconKey, draft.colorArgb, draft.sortOrder, db.requireInternalId("book_commit", ids.commitId)),
        )
        audit(db, ids, EntityType.CARD, mutation.oldCardId, nextEntityRevision(db, EntityType.CARD, mutation.oldCardId), EntityRevisionAction.ARCHIVE, EntityChangeOperation.ARCHIVE, null, snapshot, 0)
        audit(db, ids, EntityType.CARD, draft.cardId, 1, EntityRevisionAction.CREATE, EntityChangeOperation.CREATE, null, snapshot, 1)
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun saveCategory(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.SaveCategory, book: BookRow, revision: Long) {
        val draft = mutation.draft
        validateName(draft.name, "category.name")
        validateIcon(draft.iconKey)
        val parent = draft.parentId?.let { parentId ->
            db.queryOne("SELECT id,direction,depth,status FROM category WHERE uid=?", arrayOf(parentId.bytes)) { ParentRow(it.getLong(0), CategoryDirection.entries[it.getInt(1)], it.getInt(2), CategoryStatus.entries[it.getInt(3)]) }
                ?: abort(ReferenceDataViolation.InvalidField("category.parent"))
        }
        if (parent != null && (parent.direction != draft.direction || parent.depth != 1 || parent.status != CategoryStatus.ACTIVE)) abort(ReferenceDataViolation.CategoryDirectionMismatch)
        validateDefaults(db, draft.defaultAccountId, draft.defaultCardId, draft.defaultMerchantId)
        val existing = db.queryOne("SELECT id,parent_id,direction,row_version FROM category WHERE uid=?", arrayOf(draft.categoryId.bytes)) { CategoryRow(it.getLong(0), it.nullableLong("parent_id"), CategoryDirection.entries[it.getInt(2)], it.getLong(3)) }
        if (existing != null && existing.direction != draft.direction) abort(ReferenceDataViolation.CategoryDirectionMismatch)
        if (existing != null && existing.parentId != parent?.id && existing.parentId != null) abort(ReferenceDataViolation.CategoryParentLocked)
        val snapshot = canonicalCategory(draft)
        startCommit(db, ids, book, revision, snapshot)
        if (existing == null) {
            require(draft.expectedRowVersion == null)
            db.execSQL(
                "INSERT INTO category(id,uid,direction,parent_id,depth,name,normalized_name,icon_key,color_argb,sort_order,status,statistical_nature,default_account_id,default_card_id,default_merchant_id,last_commit_id,row_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)",
                arrayOf<Any?>(db.allocateInternalId("category", draft.categoryId), draft.categoryId.bytes, draft.direction.ordinal, parent?.id, if (parent == null) 1 else 2, draft.name.trim(), draft.normalizedName, draft.iconKey, draft.colorArgb, draft.sortOrder, CategoryStatus.ACTIVE.ordinal, draft.statisticalNature.ordinal, db.optionalInternalId("user_account", draft.defaultAccountId), db.optionalInternalId("payment_card", draft.defaultCardId), db.optionalInternalId("merchant", draft.defaultMerchantId), db.requireInternalId("book_commit", ids.commitId)),
            )
            audit(db, ids, EntityType.CATEGORY, draft.categoryId, 1, EntityRevisionAction.CREATE, EntityChangeOperation.CREATE, null, snapshot)
        } else {
            requireVersion(existing.rowVersion, draft.expectedRowVersion)
            db.execSQL(
                "UPDATE category SET name=?,normalized_name=?,icon_key=?,color_argb=?,sort_order=?,statistical_nature=?,default_account_id=?,default_card_id=?,default_merchant_id=?,last_commit_id=?,row_version=row_version+1 WHERE id=? AND row_version=?",
                arrayOf<Any?>(draft.name.trim(), draft.normalizedName, draft.iconKey, draft.colorArgb, draft.sortOrder, draft.statisticalNature.ordinal, db.optionalInternalId("user_account", draft.defaultAccountId), db.optionalInternalId("payment_card", draft.defaultCardId), db.optionalInternalId("merchant", draft.defaultMerchantId), db.requireInternalId("book_commit", ids.commitId), existing.id, existing.rowVersion),
            )
            audit(db, ids, EntityType.CATEGORY, draft.categoryId, nextEntityRevision(db, EntityType.CATEGORY, draft.categoryId), EntityRevisionAction.EDIT, EntityChangeOperation.UPDATE, null, snapshot)
        }
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun reorderCategories(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.ReorderCategories, book: BookRow, revision: Long) {
        if (mutation.orderedIds.isEmpty() || mutation.orderedIds.toSet().size != mutation.orderedIds.size) abort(ReferenceDataViolation.InvalidField("category.order"))
        val rows = db.queryList("SELECT uid FROM category WHERE direction=? AND status=?", arrayOf(mutation.direction.ordinal, CategoryStatus.ACTIVE.ordinal)) { it.stableId("uid") }
        if (rows.toSet() != mutation.orderedIds.toSet()) abort(ReferenceDataViolation.InvalidField("category.orderScope"))
        require(ids.entityRevisionIds.size >= mutation.orderedIds.size)
        val snapshot = canonical("categoryOrder", mutation.direction.name, *mutation.orderedIds.map(StableId::toString).toTypedArray())
        startCommit(db, ids, book, revision, snapshot)
        mutation.orderedIds.forEachIndexed { index, id ->
            val row = db.queryOne("SELECT id,row_version FROM category WHERE uid=?", arrayOf(id.bytes)) { it.getLong(0) to it.getLong(1) }!!
            db.execSQL("UPDATE category SET sort_order=?,last_commit_id=?,row_version=row_version+1 WHERE id=?", arrayOf<Any>(index, db.requireInternalId("book_commit", ids.commitId), row.first))
            audit(db, ids, EntityType.CATEGORY, id, nextEntityRevision(db, EntityType.CATEGORY, id), EntityRevisionAction.EDIT, EntityChangeOperation.UPDATE, null, snapshot, index)
        }
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun removeCategory(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.RemoveCategory, book: BookRow, revision: Long) {
        val row = db.queryOne("SELECT id,direction,row_version FROM category WHERE uid=?", arrayOf(mutation.categoryId.bytes)) { Triple(it.getLong(0), CategoryDirection.entries[it.getInt(1)], it.getLong(2)) }
            ?: abort(ReferenceDataViolation.InvalidField("category.id"))
        requireVersion(row.third, mutation.expectedRowVersion)
        val children = count(db, "SELECT COUNT(*) FROM category WHERE parent_id=?", row.first)
        if (children > 0L) abort(ReferenceDataViolation.InvalidField("category.children"))
        val history = count(db, "SELECT COUNT(*) FROM transaction_revision WHERE category_id=?", row.first)
        if (mutation.strategy == CategoryRemovalStrategy.REASSIGN) {
            val target = mutation.targetCategoryId ?: abort(ReferenceDataViolation.InvalidField("category.reassignTarget"))
            val targetRow = db.queryOne("SELECT id,direction,status FROM category WHERE uid=?", arrayOf(target.bytes)) { Triple(it.getLong(0), CategoryDirection.entries[it.getInt(1)], CategoryStatus.entries[it.getInt(2)]) }
                ?: abort(ReferenceDataViolation.InvalidField("category.reassignTarget"))
            if (targetRow.second != row.second || targetRow.third != CategoryStatus.ACTIVE || targetRow.first == row.first) abort(ReferenceDataViolation.CategoryDirectionMismatch)
        }
        val status = if (mutation.strategy == CategoryRemovalStrategy.ARCHIVE) CategoryStatus.ARCHIVED else CategoryStatus.DELETED_TOMBSTONE
        val snapshot = canonical("category", mutation.categoryId.toString(), status.name, history.toString())
        startCommit(db, ids, book, revision, snapshot)
        db.execSQL("UPDATE category SET status=?,last_commit_id=?,row_version=row_version+1 WHERE id=?", arrayOf<Any>(status.ordinal, db.requireInternalId("book_commit", ids.commitId), row.first))
        audit(db, ids, EntityType.CATEGORY, mutation.categoryId, nextEntityRevision(db, EntityType.CATEGORY, mutation.categoryId), if (status == CategoryStatus.ARCHIVED) EntityRevisionAction.ARCHIVE else EntityRevisionAction.DELETE, if (status == CategoryStatus.ARCHIVED) EntityChangeOperation.ARCHIVE else EntityChangeOperation.DELETE, null, snapshot)
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun saveMerchant(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.SaveMerchant, book: BookRow, revision: Long) {
        val draft = mutation.draft
        validateName(draft.name, "merchant.name")
        val existing = db.queryOne("SELECT id,row_version FROM merchant WHERE uid=?", arrayOf(draft.merchantId.bytes)) { it.getLong(0) to it.getLong(1) }
        val duplicate = count(db, "SELECT COUNT(*) FROM merchant WHERE normalized_name=? AND uid<>? AND status=?", draft.normalizedName, draft.merchantId.bytes, EntityStatus.ACTIVE.ordinal)
        if (duplicate > 0L) abort(ReferenceDataViolation.InvalidField("merchant.duplicate"))
        val snapshot = canonical("merchant", draft.merchantId.toString(), draft.name, draft.normalizedName, *draft.aliases.sorted().toTypedArray())
        startCommit(db, ids, book, revision, snapshot)
        val internalId = if (existing == null) {
            require(draft.expectedRowVersion == null)
            db.allocateInternalId("merchant", draft.merchantId).also { id ->
                db.execSQL("INSERT INTO merchant(id,uid,name,normalized_name,status,merged_into_id,last_commit_id,row_version) VALUES(?,?,?,?,?,NULL,?,1)", arrayOf<Any>(id, draft.merchantId.bytes, draft.name.trim(), draft.normalizedName, EntityStatus.ACTIVE.ordinal, db.requireInternalId("book_commit", ids.commitId)))
                audit(db, ids, EntityType.MERCHANT, draft.merchantId, 1, EntityRevisionAction.CREATE, EntityChangeOperation.CREATE, null, snapshot)
            }
        } else {
            requireVersion(existing.second, draft.expectedRowVersion)
            db.execSQL("UPDATE merchant SET name=?,normalized_name=?,last_commit_id=?,row_version=row_version+1 WHERE id=?", arrayOf<Any>(draft.name.trim(), draft.normalizedName, db.requireInternalId("book_commit", ids.commitId), existing.first))
            audit(db, ids, EntityType.MERCHANT, draft.merchantId, nextEntityRevision(db, EntityType.MERCHANT, draft.merchantId), EntityRevisionAction.EDIT, EntityChangeOperation.UPDATE, null, snapshot)
            existing.first
        }
        db.execSQL("DELETE FROM merchant_alias WHERE merchant_id=?", arrayOf<Any>(internalId))
        draft.aliases.filter(String::isNotBlank).forEach { alias -> db.execSQL("INSERT INTO merchant_alias(merchant_id,alias,normalized_alias) VALUES(?,?,?)", arrayOf<Any>(internalId, alias.trim(), alias.trim().lowercase())) }
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun mergeMerchant(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.MergeMerchant, book: BookRow, revision: Long) {
        if (mutation.sourceId == mutation.targetId) abort(ReferenceDataViolation.MergeCycle)
        val source = db.requireInternalId("merchant", mutation.sourceId)
        val target = db.requireInternalId("merchant", mutation.targetId)
        val targetActive = count(db, "SELECT COUNT(*) FROM merchant WHERE id=? AND status=? AND merged_into_id IS NULL", target, EntityStatus.ACTIVE.ordinal)
        if (targetActive != 1L) abort(ReferenceDataViolation.MergeCycle)
        val snapshot = canonical("merchantMerge", mutation.sourceId.toString(), mutation.targetId.toString())
        startCommit(db, ids, book, revision, snapshot)
        db.execSQL("INSERT OR IGNORE INTO merchant_alias(merchant_id,alias,normalized_alias) SELECT ?,name,normalized_name FROM merchant WHERE id=?", arrayOf<Any>(target, source))
        db.execSQL("INSERT OR IGNORE INTO merchant_alias(merchant_id,alias,normalized_alias) SELECT ?,alias,normalized_alias FROM merchant_alias WHERE merchant_id=?", arrayOf<Any>(target, source))
        db.execSQL("UPDATE merchant SET status=?,merged_into_id=?,last_commit_id=?,row_version=row_version+1 WHERE id=?", arrayOf<Any>(EntityStatus.ARCHIVED.ordinal, target, db.requireInternalId("book_commit", ids.commitId), source))
        audit(db, ids, EntityType.MERCHANT, mutation.sourceId, nextEntityRevision(db, EntityType.MERCHANT, mutation.sourceId), EntityRevisionAction.MERGE, EntityChangeOperation.ARCHIVE, null, snapshot)
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun savePlace(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.SavePlace, book: BookRow, revision: Long) {
        val draft = mutation.draft
        validateName(draft.name, "place.name")
        validateCoordinates(draft.latitudeE7, draft.longitudeE7)
        val existing = db.queryOne("SELECT id,row_version FROM place WHERE uid=?", arrayOf(draft.placeId.bytes)) { it.getLong(0) to it.getLong(1) }
        val snapshot = canonical("place", draft.placeId.toString(), draft.name, draft.latitudeE7.toString(), draft.longitudeE7.toString(), draft.merchantId?.toString().orEmpty())
        startCommit(db, ids, book, revision, snapshot)
        if (existing == null) {
            require(draft.expectedRowVersion == null)
            db.execSQL("INSERT INTO place(id,uid,name,center_lat_e7,center_lon_e7,merchant_id,status,merged_into_id,last_commit_id,row_version) VALUES(?,?,?,?,?,?,?,NULL,?,1)", arrayOf<Any?>(db.allocateInternalId("place", draft.placeId), draft.placeId.bytes, draft.name.trim(), draft.latitudeE7, draft.longitudeE7, db.optionalInternalId("merchant", draft.merchantId), EntityStatus.ACTIVE.ordinal, db.requireInternalId("book_commit", ids.commitId)))
            audit(db, ids, EntityType.PLACE, draft.placeId, 1, EntityRevisionAction.CREATE, EntityChangeOperation.CREATE, null, snapshot)
        } else {
            requireVersion(existing.second, draft.expectedRowVersion)
            db.execSQL("UPDATE place SET name=?,center_lat_e7=?,center_lon_e7=?,merchant_id=?,last_commit_id=?,row_version=row_version+1 WHERE id=?", arrayOf<Any?>(draft.name.trim(), draft.latitudeE7, draft.longitudeE7, db.optionalInternalId("merchant", draft.merchantId), db.requireInternalId("book_commit", ids.commitId), existing.first))
            audit(db, ids, EntityType.PLACE, draft.placeId, nextEntityRevision(db, EntityType.PLACE, draft.placeId), EntityRevisionAction.EDIT, EntityChangeOperation.UPDATE, null, snapshot)
        }
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun mergePlace(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.MergePlace, book: BookRow, revision: Long) {
        if (mutation.sourceId == mutation.targetId) abort(ReferenceDataViolation.MergeCycle)
        val source = db.requireInternalId("place", mutation.sourceId)
        val target = db.requireInternalId("place", mutation.targetId)
        if (count(db, "SELECT COUNT(*) FROM place WHERE id=? AND status=? AND merged_into_id IS NULL", target, EntityStatus.ACTIVE.ordinal) != 1L) abort(ReferenceDataViolation.MergeCycle)
        val snapshot = canonical("placeMerge", mutation.sourceId.toString(), mutation.targetId.toString())
        startCommit(db, ids, book, revision, snapshot)
        db.execSQL("UPDATE place SET status=?,merged_into_id=?,last_commit_id=?,row_version=row_version+1 WHERE id=?", arrayOf<Any>(EntityStatus.ARCHIVED.ordinal, target, db.requireInternalId("book_commit", ids.commitId), source))
        audit(db, ids, EntityType.PLACE, mutation.sourceId, nextEntityRevision(db, EntityType.PLACE, mutation.sourceId), EntityRevisionAction.MERGE, EntityChangeOperation.ARCHIVE, null, snapshot)
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun splitPlace(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.SplitPlace, book: BookRow, revision: Long) {
        validatePlaceSplit(db, mutation)
        val snapshot = canonical("placeSplit", mutation.sourceId.toString(), mutation.newPlace.placeId.toString(), *mutation.locationRecordIds.map(StableId::toString).toTypedArray())
        startCommit(db, ids, book, revision, snapshot)
        persistPlaceSplit(db, ids, mutation)
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun validateCategoryReassignment(
        db: SupportSQLiteDatabase,
        mutation: ReferenceMutation.RemoveCategory,
    ): Triple<Long, CategoryDirection, Long> {
        val source = db.queryOne("SELECT id,direction,row_version FROM category WHERE uid=?", arrayOf(mutation.categoryId.bytes)) {
            Triple(it.getLong(0), CategoryDirection.entries[it.getInt(1)], it.getLong(2))
        } ?: abort(ReferenceDataViolation.InvalidField("category.id"))
        requireVersion(source.third, mutation.expectedRowVersion)
        if (count(db, "SELECT COUNT(*) FROM category WHERE parent_id=?", source.first) > 0L) {
            abort(ReferenceDataViolation.InvalidField("category.children"))
        }
        val target = mutation.targetCategoryId ?: abort(ReferenceDataViolation.InvalidField("category.reassignTarget"))
        val targetRow = db.queryOne("SELECT id,direction,status FROM category WHERE uid=?", arrayOf(target.bytes)) {
            Triple(it.getLong(0), CategoryDirection.entries[it.getInt(1)], CategoryStatus.entries[it.getInt(2)])
        } ?: abort(ReferenceDataViolation.InvalidField("category.reassignTarget"))
        if (targetRow.first == source.first || targetRow.second != source.second || targetRow.third != CategoryStatus.ACTIVE) {
            abort(ReferenceDataViolation.CategoryDirectionMismatch)
        }
        return source
    }

    private fun validatePlaceSplit(db: SupportSQLiteDatabase, mutation: ReferenceMutation.SplitPlace) {
        if (mutation.locationRecordIds.isEmpty() || mutation.locationRecordIds.toSet().size != mutation.locationRecordIds.size) {
            abort(ReferenceDataViolation.InvalidField("place.splitSelection"))
        }
        val source = db.requireInternalId("place", mutation.sourceId)
        val draft = mutation.newPlace
        validateName(draft.name, "place.name")
        validateCoordinates(draft.latitudeE7, draft.longitudeE7)
        if (draft.expectedRowVersion != null || count(db, "SELECT COUNT(*) FROM place WHERE uid=?", draft.placeId.bytes) != 0L) {
            abort(ReferenceDataViolation.InvalidField("place.splitTarget"))
        }
        mutation.locationRecordIds.forEach { locationId ->
            val internal = db.requireInternalId("location_record", locationId)
            if (count(db, "SELECT COUNT(*) FROM location_record WHERE id=? AND place_id=?", internal, source) != 1L) {
                abort(ReferenceDataViolation.InvalidField("place.splitSelection"))
            }
        }
    }

    private fun persistPlaceSplit(
        db: SupportSQLiteDatabase,
        ids: ReferenceMutationIds,
        mutation: ReferenceMutation.SplitPlace,
    ) {
        val draft = mutation.newPlace
        val placeInternal = db.allocateInternalId("place", draft.placeId)
        val commitInternal = db.requireInternalId("book_commit", ids.commitId)
        db.execSQL(
            "INSERT INTO place(id,uid,name,center_lat_e7,center_lon_e7,merchant_id,status,merged_into_id,last_commit_id,row_version) VALUES(?,?,?,?,?,?,?,NULL,?,1)",
            arrayOf<Any?>(placeInternal, draft.placeId.bytes, draft.name.trim(), draft.latitudeE7, draft.longitudeE7, db.optionalInternalId("merchant", draft.merchantId), EntityStatus.ACTIVE.ordinal, commitInternal),
        )
        mutation.locationRecordIds.zip(mutation.replacementLocationRecordIds).forEach { (sourceId, replacementId) ->
            val sourceInternal = db.requireInternalId("location_record", sourceId)
            val source = db.queryOne(
                "SELECT lat_e7,lon_e7,accuracy_mm,captured_at,source,provider FROM location_record WHERE id=?",
                arrayOf(sourceInternal),
            ) { cursor ->
                arrayOf<Any?>(cursor.getInt(0), cursor.getInt(1), cursor.nullableLong("accuracy_mm"), cursor.getLong(3), cursor.getInt(4), cursor.nullableString("provider"))
            } ?: abort(FinanceDataError.CorruptData)
            db.execSQL(
                "INSERT INTO location_record(id,uid,lat_e7,lon_e7,accuracy_mm,captured_at,source,provider,place_id,created_commit_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(db.allocateInternalId("location_record", replacementId), replacementId.bytes, *source, placeInternal, commitInternal),
            )
        }
        val snapshot = canonical("place", draft.placeId.toString(), draft.name, draft.latitudeE7.toString(), draft.longitudeE7.toString(), draft.merchantId?.toString().orEmpty(), "splitFrom=${mutation.sourceId}")
        audit(db, ids, EntityType.PLACE, draft.placeId, 1, EntityRevisionAction.CREATE, EntityChangeOperation.CREATE, null, snapshot)
    }

    private fun saveCheckpoint(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, mutation: ReferenceMutation.SaveCheckpoint, book: BookRow, revision: Long) {
        val accountId = db.requireInternalId("user_account", mutation.accountId)
        val calculated = calculatedBalanceAt(db, accountId, mutation.asOf)
        val difference = CheckedArithmetic.subtract(mutation.observedMinor, calculated).valueOrAbort()
        val snapshot = canonical("checkpoint", mutation.checkpointId.toString(), mutation.accountId.toString(), mutation.asOf.toString(), mutation.observedMinor.toString(), calculated.toString(), difference.toString())
        startCommit(db, ids, book, revision, snapshot)
        db.execSQL(
            "INSERT INTO account_balance_checkpoint(id,uid,account_id,as_of_instant,as_of_local_date,observed_amount_minor,calculated_amount_minor,difference_minor,created_commit_id,adjustment_transaction_id,note) VALUES(?,?,?,?,?,?,?,?,?,NULL,?)",
            arrayOf<Any?>(db.allocateInternalId("account_balance_checkpoint", mutation.checkpointId), mutation.checkpointId.bytes, accountId, mutation.asOf.toEpochMilli(), mutation.asOfLocalDate.toStorageInt(), mutation.observedMinor, calculated, difference, db.requireInternalId("book_commit", ids.commitId), mutation.note.clean()),
        )
        audit(db, ids, EntityType.ACCOUNT, mutation.accountId, nextEntityRevision(db, EntityType.ACCOUNT, mutation.accountId), EntityRevisionAction.EDIT, EntityChangeOperation.UPDATE, null, snapshot)
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun calculatedBalanceAt(db: SupportSQLiteDatabase, accountId: Long, asOf: Instant): Long {
        val normalSide = db.queryOne("SELECT la.normal_side FROM user_account ua JOIN ledger_account la ON la.id=ua.ledger_account_id WHERE ua.id=?", arrayOf(accountId)) { it.getInt(0) }
            ?: abort(ReferenceDataViolation.InvalidField("checkpoint.account"))
        val amounts = db.queryList(
            "SELECT p.side,p.account_amount_minor FROM user_account ua JOIN posting p ON p.ledger_account_id=ua.ledger_account_id JOIN journal_entry je ON je.id=p.journal_entry_id WHERE ua.id=? AND je.effective_at<=? ORDER BY je.effective_at,p.id",
            arrayOf(accountId, asOf.toEpochMilli()),
        ) { (if (it.getInt(0) == normalSide) 1L else -1L) to it.getLong(1) }
        var total = 0L
        amounts.forEach { (sign, amount) -> total = if (sign > 0) CheckedArithmetic.add(total, amount).valueOrAbort() else CheckedArithmetic.subtract(total, amount).valueOrAbort() }
        return total
    }

    private fun saveProject(
        db: SupportSQLiteDatabase,
        ids: ReferenceMutationIds,
        draft: ProjectDraft,
        book: BookRow,
        revision: Long,
    ) {
        validateName(draft.name, "project.name")
        if (draft.budgetBaseMinor < 0L || draft.endDate?.isBefore(draft.startDate) == true) {
            abort(ReferenceDataViolation.InvalidField("project.periodOrBudget"))
        }
        val existing = projectRow(db, draft.projectId)
        if (existing == null && draft.expectedRowVersion != null) abort(ReferenceDataViolation.StaleRevision)
        existing?.let {
            requireVersion(it.rowVersion, draft.expectedRowVersion)
            if (it.status != draft.status) abort(ReferenceDataViolation.InvalidField("project.statusMutation"))
        }
        val goalInternal = draft.goalId?.let { goalId ->
            db.queryOne("SELECT id FROM goal WHERE uid=?", arrayOf(goalId.bytes)) { it.getLong(0) }
                ?: abort(ReferenceDataViolation.InvalidField("project.goal"))
        }
        val snapshot = canonicalProject(draft)
        startCommit(db, ids, book, revision, snapshot)
        val commit = db.requireInternalId("book_commit", ids.commitId)
        if (existing == null) {
            db.execSQL(
                "INSERT INTO project(id,uid,name,description,start_date,end_date,budget_base_minor,included_in_monthly_budget,goal_id,status,last_commit_id,row_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,1)",
                arrayOf<Any?>(db.allocateInternalId("project", draft.projectId), draft.projectId.bytes, draft.name.trim(), draft.description.clean(), draft.startDate.toStorageInt(), draft.endDate?.toStorageInt(), draft.budgetBaseMinor, draft.includedInMonthlyBudget.toSqlInt(), goalInternal, draft.status.ordinal, commit),
            )
        } else {
            val changed = db.compileStatement(
                "UPDATE project SET name=?,description=?,start_date=?,end_date=?,budget_base_minor=?,included_in_monthly_budget=?,goal_id=?,last_commit_id=?,row_version=row_version+1 WHERE id=? AND row_version=?",
            ).apply {
                bindString(1, draft.name.trim())
                draft.description.clean()?.let { bindString(2, it) } ?: bindNull(2)
                bindLong(3, draft.startDate.toStorageInt().toLong())
                draft.endDate?.let { bindLong(4, it.toStorageInt().toLong()) } ?: bindNull(4)
                bindLong(5, draft.budgetBaseMinor)
                bindLong(6, draft.includedInMonthlyBudget.toSqlInt().toLong())
                goalInternal?.let { bindLong(7, it) } ?: bindNull(7)
                bindLong(8, commit)
                bindLong(9, existing.id)
                bindLong(10, existing.rowVersion)
            }.executeUpdateDelete()
            if (changed != 1) abort(ReferenceDataViolation.StaleRevision)
        }
        audit(
            db, ids, EntityType.PROJECT, draft.projectId,
            nextEntityRevision(db, EntityType.PROJECT, draft.projectId),
            if (existing == null) EntityRevisionAction.CREATE else EntityRevisionAction.EDIT,
            if (existing == null) EntityChangeOperation.CREATE else EntityChangeOperation.UPDATE,
            existing?.canonical, snapshot,
        )
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun changeProjectStatus(
        db: SupportSQLiteDatabase,
        ids: ReferenceMutationIds,
        mutation: ReferenceMutation.ChangeProjectStatus,
        book: BookRow,
        revision: Long,
    ) {
        val existing = projectRow(db, mutation.projectId)
            ?: abort(ReferenceDataViolation.InvalidField("project.id"))
        requireVersion(existing.rowVersion, mutation.expectedRowVersion)
        ProjectStatusPolicy.transition(existing.status, mutation.status).valueOrAbort()
        val after = canonical(
            "project", mutation.projectId.toString(), existing.name, existing.description.orEmpty(),
            existing.startDate.toString(), existing.endDate?.toString().orEmpty(), existing.budgetBaseMinor.toString(),
            existing.includedInMonthlyBudget.toString(), existing.goalId?.toString().orEmpty(), mutation.status.name,
        )
        startCommit(db, ids, book, revision, after)
        val changed = db.compileStatement(
            "UPDATE project SET status=?,last_commit_id=?,row_version=row_version+1 WHERE id=? AND row_version=?",
        ).apply {
            bindLong(1, mutation.status.ordinal.toLong())
            bindLong(2, db.requireInternalId("book_commit", ids.commitId))
            bindLong(3, existing.id)
            bindLong(4, existing.rowVersion)
        }.executeUpdateDelete()
        if (changed != 1) abort(ReferenceDataViolation.StaleRevision)
        audit(
            db, ids, EntityType.PROJECT, mutation.projectId,
            nextEntityRevision(db, EntityType.PROJECT, mutation.projectId),
            if (mutation.status == ProjectStatus.ARCHIVED) EntityRevisionAction.ARCHIVE else EntityRevisionAction.RESTORE,
            if (mutation.status == ProjectStatus.ARCHIVED) EntityChangeOperation.ARCHIVE else EntityChangeOperation.RESTORE,
            existing.canonical, after,
        )
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun saveGoal(
        db: SupportSQLiteDatabase,
        ids: ReferenceMutationIds,
        draft: GoalDraft,
        book: BookRow,
        revision: Long,
    ) {
        validateName(draft.name, "goal.name")
        if (draft.targetAmountMinor <= 0L || (draft.suggestedMonthlyAmountMinor ?: 1L) <= 0L) {
            abort(ReferenceDataViolation.InvalidField("goal.amount"))
        }
        val account = accountRow(db, draft.accountId)
        val existing = goalRow(db, draft.goalId)
        if (existing == null && draft.expectedRowVersion != null) abort(ReferenceDataViolation.StaleRevision)
        existing?.let {
            requireVersion(it.rowVersion, draft.expectedRowVersion)
            if (it.accountId != account.id) abort(ReferenceDataViolation.InvalidField("goal.accountLocked"))
        }
        val snapshot = canonicalGoal(draft, account.currency)
        startCommit(db, ids, book, revision, snapshot)
        val commit = db.requireInternalId("book_commit", ids.commitId)
        if (existing == null) {
            db.execSQL(
                "INSERT INTO goal(id,uid,account_id,name,target_amount_minor,due_date,suggested_monthly_minor,status,last_commit_id,row_version) VALUES(?,?,?,?,?,?,?,?,?,1)",
                arrayOf<Any?>(db.allocateInternalId("goal", draft.goalId), draft.goalId.bytes, account.id, draft.name.trim(), draft.targetAmountMinor, draft.dueDate?.toStorageInt(), draft.suggestedMonthlyAmountMinor, draft.status.ordinal, commit),
            )
        } else {
            val changed = db.compileStatement(
                "UPDATE goal SET name=?,target_amount_minor=?,due_date=?,suggested_monthly_minor=?,status=?,last_commit_id=?,row_version=row_version+1 WHERE id=? AND row_version=?",
            ).apply {
                bindString(1, draft.name.trim())
                bindLong(2, draft.targetAmountMinor)
                draft.dueDate?.let { bindLong(3, it.toStorageInt().toLong()) } ?: bindNull(3)
                draft.suggestedMonthlyAmountMinor?.let { bindLong(4, it) } ?: bindNull(4)
                bindLong(5, draft.status.ordinal.toLong())
                bindLong(6, commit)
                bindLong(7, existing.id)
                bindLong(8, existing.rowVersion)
            }.executeUpdateDelete()
            if (changed != 1) abort(ReferenceDataViolation.StaleRevision)
        }
        audit(
            db, ids, EntityType.GOAL, draft.goalId,
            nextEntityRevision(db, EntityType.GOAL, draft.goalId),
            if (existing == null) EntityRevisionAction.CREATE else EntityRevisionAction.EDIT,
            if (existing == null) EntityChangeOperation.CREATE else EntityChangeOperation.UPDATE,
            existing?.canonical, snapshot,
        )
        finish(db, ids, revision, book.valuationRevision)
    }

    private fun projectRow(db: SupportSQLiteDatabase, id: StableId): ProjectRow? = db.queryOne(
        "SELECT p.id,p.name,p.description,p.start_date,p.end_date,p.budget_base_minor,p.included_in_monthly_budget,g.uid goal_uid,p.status,p.row_version FROM project p LEFT JOIN goal g ON g.id=p.goal_id WHERE p.uid=?",
        arrayOf(id.bytes),
    ) { cursor ->
        val goalId = cursor.nullableStableId("goal_uid")
        val row = ProjectRow(
            cursor.long("id"), cursor.string("name"), cursor.nullableString("description"),
            cursor.int("start_date").toStoredLocalDate(), cursor.nullableLong("end_date")?.toInt()?.toStoredLocalDate(),
            cursor.long("budget_base_minor"), cursor.int("included_in_monthly_budget") == 1,
            goalId, ProjectStatus.entries[cursor.int("status")], cursor.long("row_version"), ByteArray(0),
        )
        row.copy(canonical = canonical("project", id.toString(), row.name, row.description.orEmpty(), row.startDate.toString(), row.endDate?.toString().orEmpty(), row.budgetBaseMinor.toString(), row.includedInMonthlyBudget.toString(), goalId?.toString().orEmpty(), row.status.name))
    }

    private fun goalRow(db: SupportSQLiteDatabase, id: StableId): GoalRow? = db.queryOne(
        "SELECT g.id,g.account_id,g.name,g.target_amount_minor,g.due_date,g.suggested_monthly_minor,g.status,g.row_version,ua.currency_code FROM goal g JOIN user_account ua ON ua.id=g.account_id WHERE g.uid=?",
        arrayOf(id.bytes),
    ) { cursor ->
        val row = GoalRow(cursor.long("id"), cursor.long("account_id"), cursor.string("name"), cursor.long("target_amount_minor"), cursor.nullableLong("due_date")?.toInt()?.toStoredLocalDate(), cursor.nullableLong("suggested_monthly_minor"), GoalStatus.entries[cursor.int("status")], cursor.long("row_version"), cursor.string("currency_code"), ByteArray(0))
        row.copy(canonical = canonical("goal", id.toString(), row.accountId.toString(), row.name, row.targetAmountMinor.toString(), row.dueDate?.toString().orEmpty(), row.suggestedMonthlyMinor?.toString().orEmpty(), row.status.name, row.currency))
    }

    private fun canonicalProject(draft: ProjectDraft): ByteArray = canonical(
        "project", draft.projectId.toString(), draft.name.trim(), draft.description.clean().orEmpty(), draft.startDate.toString(),
        draft.endDate?.toString().orEmpty(), draft.budgetBaseMinor.toString(), draft.includedInMonthlyBudget.toString(),
        draft.goalId?.toString().orEmpty(), draft.status.name,
    )

    private fun canonicalGoal(draft: GoalDraft, currency: String): ByteArray = canonical(
        "goal", draft.goalId.toString(), draft.accountId.toString(), draft.name.trim(), draft.targetAmountMinor.toString(),
        draft.dueDate?.toString().orEmpty(), draft.suggestedMonthlyAmountMinor?.toString().orEmpty(), draft.status.name, currency,
    )

    private fun startCommit(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, book: BookRow, revision: Long, snapshot: ByteArray) {
        if (ids.entityRevisionIds.toSet().size != ids.entityRevisionIds.size) abort(ReferenceDataViolation.InvalidField("revisionIds"))
        db.execSQL(
            "INSERT INTO book_commit(id,uid,local_revision,kind,command_uid,device_instance_uid,created_at,root_hash) VALUES(?,?,?,?,NULL,?,?,?)",
            arrayOf<Any>(db.allocateInternalId("book_commit", ids.commitId), ids.commitId.bytes, revision, CommitKind.REFERENCE_DATA_CHANGE.ordinal, ids.deviceInstanceId.bytes, ids.changedAt.toEpochMilli(), sha256(snapshot)),
        )
        db.execSQL("INSERT INTO book_commit_parent(commit_id,parent_commit_id,ordinal) VALUES(?,?,0)", arrayOf<Any>(db.requireInternalId("book_commit", ids.commitId), book.headCommitId))
    }

    private fun audit(
        db: SupportSQLiteDatabase,
        ids: ReferenceMutationIds,
        type: EntityType,
        entityId: StableId,
        revisionNumber: Int,
        action: EntityRevisionAction,
        operation: EntityChangeOperation,
        before: ByteArray?,
        after: ByteArray,
        revisionIndex: Int = 0,
    ) {
        val revisionId = ids.entityRevisionIds.getOrNull(revisionIndex) ?: abort(ReferenceDataViolation.InvalidField("revisionIds"))
        db.execSQL(
            "INSERT INTO entity_revision(id,uid,entity_type,entity_uid,revision_no,action,commit_id,content_hash,canonical_snapshot_blob,schema_version) VALUES(?,?,?,?,?,?,?,?,?,1)",
            arrayOf<Any>(db.allocateInternalId("entity_revision", revisionId), revisionId.bytes, type.ordinal, entityId.bytes, revisionNumber, action.ordinal, db.requireInternalId("book_commit", ids.commitId), sha256(after), after),
        )
        db.execSQL(
            "INSERT INTO entity_change(commit_id,entity_type,entity_uid,operation,before_hash,after_hash,entity_revision_uid) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(db.requireInternalId("book_commit", ids.commitId), type.ordinal, entityId.bytes, operation.ordinal, before?.let(::sha256), sha256(after), revisionId.bytes),
        )
    }

    private fun finish(db: SupportSQLiteDatabase, ids: ReferenceMutationIds, revision: Long, valuationRevision: Long) {
        db.execSQL("UPDATE book SET head_commit_id=?,local_revision=? WHERE id=1", arrayOf<Any>(db.requireInternalId("book_commit", ids.commitId), revision))
        projections.rebuildAll(db, revision, valuationRevision)
        val mismatches = projections.mismatchedFamilies(db, revision, valuationRevision)
        if (mismatches.isNotEmpty()) abort(FinanceDataError.ProjectionMismatch)
        if (!DatabaseIntegrityAudit.run(db).isValid) abort(FinanceDataError.CorruptData)
    }

    private fun requireBook(db: SupportSQLiteDatabase, bookId: StableId): BookRow {
        val row = db.queryOne("SELECT uid,base_currency,head_commit_id,local_revision,valuation_revision,state FROM book WHERE id=1") {
            BookRow(it.getBlob(0), it.getString(1), it.getLong(2), it.getLong(3), it.getLong(4), it.getInt(5))
        } ?: abort(FinanceDataError.CorruptData)
        if (!row.uid.contentEquals(bookId.bytes) || row.state != 0) abort(FinanceDataError.MaintenanceRequired)
        return row
    }

    private fun accountRow(db: SupportSQLiteDatabase, id: StableId): AccountRow = db.queryOne(
        "SELECT ua.id,ua.ledger_account_id,ua.type,ua.currency_code,ua.row_version,EXISTS(SELECT 1 FROM posting p WHERE p.ledger_account_id=ua.ledger_account_id) FROM user_account ua WHERE ua.uid=?",
        arrayOf(id.bytes),
    ) { AccountRow(it.getLong(0), it.getLong(1), it.getInt(2), it.getString(3), it.getLong(4), it.getInt(5) == 1) }
        ?: abort(ReferenceDataViolation.InvalidField("account.id"))

    private fun validateDefaults(db: SupportSQLiteDatabase, accountId: StableId?, cardId: StableId?, merchantId: StableId?) {
        val internalAccount = accountId?.let { db.requireInternalId("user_account", it) }
        if (cardId != null) {
            val cardAccount = db.queryOne("SELECT account_id FROM payment_card WHERE uid=? AND status=?", arrayOf(cardId.bytes, EntityStatus.ACTIVE.ordinal)) { it.getLong(0) }
                ?: abort(ReferenceDataViolation.InvalidField("category.defaultCard"))
            if (internalAccount != null && cardAccount != internalAccount) abort(ReferenceDataViolation.CardAccountIncompatible)
        }
        merchantId?.let { db.requireInternalId("merchant", it) }
    }

    private fun checkedNetPosition(accounts: List<AccountReferenceView>, base: CurrencyCode): Long {
        var total = 0L
        accounts.filter { it.status == EntityStatus.ACTIVE }.forEach { account ->
            val value = account.currentBaseValueMinor ?: if (account.currency == base) account.balanceMinor else abort(FinanceDataError.ProjectionMismatch)
            total = if (account.type in setOf(UserAccountType.CASH, UserAccountType.BANK)) CheckedArithmetic.add(total, value).valueOrAbort() else CheckedArithmetic.subtract(total, value).valueOrAbort()
        }
        return total
    }

    private suspend inline fun <T> withDatabase(bookId: StableId, crossinline block: suspend (LedgerDatabase) -> T): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { passphrase -> EncryptedDatabaseFactory.openPrimary(applicationContext, passphrase) }
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
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    private fun nextEntityRevision(db: SupportSQLiteDatabase, type: EntityType, id: StableId): Int = Math.addExact(
        db.queryOne("SELECT COALESCE(MAX(revision_no),0) FROM entity_revision WHERE entity_type=? AND entity_uid=?", arrayOf(type.ordinal, id.bytes)) { it.getInt(0) } ?: 0,
        1,
    )

    private fun requireVersion(actual: Long, expected: Long?) {
        if (expected == null || actual != expected) abort(ReferenceDataViolation.StaleRevision)
    }

    private fun validateName(value: String, field: String) {
        if (value.isBlank() || value.trim().length > 80) abort(ReferenceDataViolation.InvalidField(field))
    }

    private fun validateIcon(value: String) {
        if (!value.matches(Regex("[a-z][a-z0-9_]{1,31}"))) abort(ReferenceDataViolation.InvalidField("display.icon"))
    }

    private fun validateCoordinates(latitudeE7: Int, longitudeE7: Int) {
        if (latitudeE7 !in -900_000_000..900_000_000 || longitudeE7 !in -1_800_000_000..1_800_000_000) abort(ReferenceDataViolation.InvalidField("place.center"))
    }

    private fun normalSide(accountClass: LedgerAccountClass): DebitCredit = if (accountClass == LedgerAccountClass.LIABILITY) DebitCredit.CREDIT else DebitCredit.DEBIT
    private fun canonicalAccount(draft: app.ledger.finance.application.AccountDraft): ByteArray = canonical("account", draft.accountId.toString(), draft.type.name, draft.name.trim(), draft.currency.value, draft.institutionName.orEmpty(), draft.branchName.orEmpty(), draft.openedOn?.toString().orEmpty(), draft.iconKey, draft.colorArgb.toString(), draft.sortOrder.toString())
    private fun canonicalCard(draft: app.ledger.finance.application.CardDraft): ByteArray = canonical("card", draft.cardId.toString(), draft.accountId.toString(), draft.type.name, draft.displayName.trim(), draft.lastFour.orEmpty(), draft.replacementOfId?.toString().orEmpty(), draft.iconKey, draft.colorArgb.toString(), draft.sortOrder.toString())
    private fun canonicalCategory(draft: app.ledger.finance.application.CategoryDraft): ByteArray = canonical("category", draft.categoryId.toString(), draft.direction.name, draft.parentId?.toString().orEmpty(), draft.name.trim(), draft.normalizedName, draft.iconKey, draft.colorArgb.toString(), draft.sortOrder.toString(), draft.statisticalNature.name, draft.defaultAccountId?.toString().orEmpty(), draft.defaultCardId?.toString().orEmpty(), draft.defaultMerchantId?.toString().orEmpty())
    private fun canonical(vararg values: String): ByteArray = values.joinToString("\u001f").toByteArray(Charsets.UTF_8)
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun derivedId(seed: StableId, label: String): StableId = StableId.fromBytes(
        sha256(seed.bytes + label.toByteArray(Charsets.UTF_8)).copyOf(StableId.BYTE_COUNT),
    ).valueOrAbort()

    private fun app.ledger.finance.domain.TransactionRevision.toContextInput(): TransactionContextInput = TransactionContextInput(
        occurredAt,
        accrualDate,
        budgetMonth,
        merchantId,
        projectId,
        goalId,
        locationRecordId,
        note,
        amountExpression,
        source,
        sourceReferenceId,
        statementAssignment,
        attachmentIds,
    )

    private fun TransactionPayload.withClassification(
        assignment: app.ledger.finance.domain.CategoryAssignment,
    ): TransactionPayload = when (this) {
        is ExpensePayload -> copy(classification = assignment)
        is IncomePayload -> copy(classification = assignment)
        is RefundPayload -> copy(classification = assignment)
        is LoanPaymentPayload -> copy(classification = assignment)
        is FxExchangePayload -> copy(classification = assignment)
        else -> abort(ReferenceDataViolation.InvalidField("category.reassignTransactionKind"))
    }
    private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)
    private fun count(db: SupportSQLiteDatabase, sql: String, vararg args: Any?): Long = db.queryOne(sql, args) { it.getLong(0) } ?: 0L

    private data class BookRow(val uid: ByteArray, val baseCurrency: String, val headCommitId: Long, val localRevision: Long, val valuationRevision: Long, val state: Int)
    private data class AccountRow(val id: Long, val ledgerId: Long, val type: Int, val currency: String, val rowVersion: Long, val hasPostings: Boolean)
    private data class ProjectRow(
        val id: Long,
        val name: String,
        val description: String?,
        val startDate: java.time.LocalDate,
        val endDate: java.time.LocalDate?,
        val budgetBaseMinor: Long,
        val includedInMonthlyBudget: Boolean,
        val goalId: StableId?,
        val status: ProjectStatus,
        val rowVersion: Long,
        val canonical: ByteArray,
    )
    private data class GoalRow(
        val id: Long,
        val accountId: Long,
        val name: String,
        val targetAmountMinor: Long,
        val dueDate: java.time.LocalDate?,
        val suggestedMonthlyMinor: Long?,
        val status: GoalStatus,
        val rowVersion: Long,
        val currency: String,
        val canonical: ByteArray,
    )
    private data class ParentRow(val id: Long, val direction: CategoryDirection, val depth: Int, val status: CategoryStatus)
    private data class CategoryRow(val id: Long, val parentId: Long?, val direction: CategoryDirection, val rowVersion: Long)
}

private class ReferenceFinancialWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

private fun androidx.sqlite.db.SupportSQLiteStatement.bindNullableString(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindString(index, value)
}

private fun androidx.sqlite.db.SupportSQLiteStatement.bindNullableLong(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun android.database.Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
