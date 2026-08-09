@file:Suppress("LongMethod", "LongParameterList", "TooManyFunctions", "LargeClass", "MagicNumber", "MaxLineLength", "NestedBlockDepth")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.application.AutomationApplicationPort
import app.ledger.finance.application.AutomationSnapshot
import app.ledger.finance.application.BlueprintDraft
import app.ledger.finance.application.BlueprintView
import app.ledger.finance.application.CandidateView
import app.ledger.finance.application.CatchUpResult
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FormalOccurrenceGenerator
import app.ledger.finance.application.FormalOccurrenceRequest
import app.ledger.finance.application.ModifyOccurrenceRequest
import app.ledger.finance.application.OccurrenceView
import app.ledger.finance.application.RecurrenceExceptionDraft
import app.ledger.finance.application.RecurrenceSeriesDraft
import app.ledger.finance.application.RecurrenceSeriesView
import app.ledger.finance.application.SaveBlueprintRequest
import app.ledger.finance.application.SaveRecurrenceRequest
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CommitKind
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.EntityChangeOperation
import app.ledger.finance.domain.EntityRevisionAction
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.RecurrenceCandidateStatus
import app.ledger.finance.domain.RecurrenceEngine
import app.ledger.finance.domain.RecurrenceException
import app.ledger.finance.domain.RecurrenceExceptionAction
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceModificationScope
import app.ledger.finance.domain.RecurrenceOccurrenceStatus
import app.ledger.finance.domain.RecurrenceRule
import app.ledger.finance.domain.RecurrenceSeriesId
import app.ledger.finance.domain.RecurrenceSeriesRevision
import app.ledger.finance.domain.RecurrenceSeriesRevisionId
import app.ledger.finance.domain.RecurrenceStatus
import app.ledger.finance.domain.TransactionBlueprintRevisionId
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.WeekendAdjustment
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SecureRoomAutomationApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val formalGenerator: FormalOccurrenceGenerator,
    private val databaseName: String = EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME,
) : AutomationApplicationPort {
    private val applicationContext = context.applicationContext
    private val projections = RoomProjectionEngine()

    override suspend fun snapshot(bookId: StableId): DomainResult<AutomationSnapshot> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            val book = requireBook(db, bookId)
            val blueprints = blueprints(db)
            val series = series(db)
            DomainResult.Success(
                AutomationSnapshot(
                    bookId,
                    book.localRevision,
                    blueprints,
                    series,
                    candidates(db),
                    occurrences(db),
                ),
            )
        }
    }

    override suspend fun saveBlueprint(request: SaveBlueprintRequest): DomainResult<Unit> = withDatabase(request.ids.bookId) { database ->
        database.inLedgerTransaction { db ->
            val book = requireBook(db, request.ids.bookId)
            requireExpectedRevision(book, request.ids.expectedLocalRevision)
            val existingRevision = db.queryOne(
                "SELECT r.uid FROM transaction_blueprint b LEFT JOIN transaction_blueprint_revision r ON r.id=b.current_revision_id WHERE b.uid=?",
                arrayOf(request.draft.id.bytes),
            ) { it.getBlob(0)?.let { bytes -> StableId.fromBytes(bytes).valueOrAbort() } }
            if (existingRevision != request.draft.expectedRevisionId) abort(DomainViolation.StaleExpectedRevision)
            validateBlueprintReferences(db, request.draft)
            val next = Math.addExact(book.localRevision, 1L)
            val canonical = canonicalBlueprint(request.draft)
            startCommit(db, request.ids.commitId, request.ids.deviceInstanceId, request.ids.changedAt, book, next, canonical)
            val commitId = db.requireInternalId("book_commit", request.ids.commitId)
            val blueprintId = db.queryOne("SELECT id FROM transaction_blueprint WHERE uid=?", arrayOf(request.draft.id.bytes)) { it.getLong(0) }
                ?: db.allocateInternalId("transaction_blueprint", request.draft.id).also { id ->
                    db.execSQL(
                        "INSERT INTO transaction_blueprint(id,uid,name,current_revision_id,status,icon_key,color_argb) VALUES(?,?,?,NULL,?,?,?)",
                        arrayOf<Any>(id, request.draft.id.bytes, request.draft.name.trim(), request.draft.status.ordinal, request.draft.iconKey, request.draft.colorArgb),
                    )
                }
            val revisionNumber = Math.addExact(db.queryOne("SELECT COALESCE(MAX(revision_no),0) FROM transaction_blueprint_revision WHERE blueprint_id=?", arrayOf(blueprintId)) { it.getInt(0) } ?: 0, 1)
            val revisionId = db.allocateInternalId("transaction_blueprint_revision", request.draft.revisionId)
            db.execSQL(
                "INSERT INTO transaction_blueprint_revision(id,uid,blueprint_id,revision_no,target_kind,category_id,primary_account_id,secondary_account_id,card_id,merchant_id,project_id,goal_id,settlement_activity_id,amount_expression,currency_code,note_template,fixed_place_id,created_commit_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    revisionId, request.draft.revisionId.bytes, blueprintId, revisionNumber, request.draft.targetKind.ordinal,
                    request.draft.categoryId?.let { db.requireInternalId("category", it) }, request.draft.primaryAccountId?.let { db.requireInternalId("user_account", it) },
                    request.draft.secondaryAccountId?.let { db.requireInternalId("user_account", it) }, request.draft.cardId?.let { db.requireInternalId("payment_card", it) },
                    request.draft.merchantId?.let { db.requireInternalId("merchant", it) }, request.draft.projectId?.let { db.requireInternalId("project", it) },
                    request.draft.goalId?.let { db.requireInternalId("goal", it) }, request.draft.settlementActivityId?.let { db.requireInternalId("settlement_activity", it) },
                    request.draft.amountExpression, request.draft.currency?.value, request.draft.noteTemplate, request.draft.fixedPlaceId?.let { db.requireInternalId("place", it) }, commitId,
                ),
            )
            db.execSQL(
                "UPDATE transaction_blueprint SET name=?,current_revision_id=?,status=?,icon_key=?,color_argb=? WHERE id=?",
                arrayOf<Any>(request.draft.name.trim(), revisionId, request.draft.status.ordinal, request.draft.iconKey, request.draft.colorArgb, blueprintId),
            )
            audit(db, request.ids.entityRevisionId, request.ids.commitId, EntityType.BLUEPRINT, request.draft.id, existingRevision == null, canonical)
            finishReferenceCommit(db, request.ids.commitId, next, book.valuationRevision)
            DomainResult.Success(Unit)
        }
    }

    override suspend fun saveSeries(request: SaveRecurrenceRequest): DomainResult<Unit> = withDatabase(request.ids.bookId) { database ->
        database.inLedgerTransaction { db ->
            persistSeries(db, request)
            DomainResult.Success(Unit)
        }
    }

    override suspend fun modifyOccurrence(request: ModifyOccurrenceRequest): DomainResult<Unit> = withDatabase(request.ids.bookId) { database ->
        database.inLedgerTransaction { db ->
            val book = requireBook(db, request.ids.bookId)
            requireExpectedRevision(book, request.ids.expectedLocalRevision)
            val seriesInternal = db.requireInternalId("recurrence_series", request.seriesId)
            when (request.scope) {
                RecurrenceModificationScope.THIS_OCCURRENCE -> {
                    val canonical = canonical("occurrenceOverride", request.seriesId.toString(), request.occurrenceLocalDate.toString(), request.replacement.occurrenceTime.toString())
                    val next = Math.addExact(book.localRevision, 1L)
                    startCommit(db, request.ids.commitId, request.ids.deviceInstanceId, request.ids.changedAt, book, next, canonical)
                    val instant = request.occurrenceLocalDate.atTime(request.replacement.occurrenceTime).atZone(request.replacement.zoneId).toInstant()
                    db.execSQL(
                        "INSERT OR REPLACE INTO recurrence_exception(series_id,occurrence_local_date,action,override_blueprint_revision_id,override_instant) VALUES(?,?,?,?,?)",
                        arrayOf<Any?>(seriesInternal, request.occurrenceLocalDate.toStorageInt(), RecurrenceExceptionAction.MOVE.ordinal, null, instant.toEpochMilli()),
                    )
                    audit(db, request.ids.entityRevisionId, request.ids.commitId, EntityType.RECURRENCE_SERIES, request.seriesId, false, canonical)
                    finishReferenceCommit(db, request.ids.commitId, next, book.valuationRevision)
                }
                RecurrenceModificationScope.THIS_AND_FUTURE, RecurrenceModificationScope.ENTIRE_SERIES -> {
                    val originalStart = db.queryOne(
                        "SELECT r.start_date FROM recurrence_series s JOIN recurrence_series_revision r ON r.id=s.current_revision_id WHERE s.id=?",
                        arrayOf(seriesInternal),
                    ) { it.getInt(0).toStoredLocalDate() } ?: abort(FinanceDataError.CorruptData)
                    val start = if (request.scope == RecurrenceModificationScope.THIS_AND_FUTURE) request.occurrenceLocalDate else originalStart
                    db.execSQL(
                        "UPDATE recurrence_occurrence SET status=? WHERE series_id=? AND local_date>=? AND status IN (?,?)",
                        arrayOf<Any>(RecurrenceOccurrenceStatus.CANCELLED.ordinal, seriesInternal, request.occurrenceLocalDate.toStorageInt(), RecurrenceOccurrenceStatus.PENDING.ordinal, RecurrenceOccurrenceStatus.FAILED.ordinal),
                    )
                    persistSeries(db, SaveRecurrenceRequest(request.ids, request.replacement.copy(id = request.seriesId, startDate = start)))
                }
            }
            DomainResult.Success(Unit)
        }
    }

    override suspend fun catchUp(operationId: StableId, through: Instant): DomainResult<CatchUpResult> = withDatabase(operationId) { database ->
        val reserved = database.inLedgerTransaction { db -> reserveDue(db, operationId, through) }
        processReserved(database, operationId, reserved)
    }

    override suspend fun retryOccurrence(operationId: StableId, occurrenceId: StableId): DomainResult<CatchUpResult> = withDatabase(operationId) { database ->
        val reserved = database.readLedger { db -> listOfNotNull(reservedById(db, occurrenceId)) }
        processReserved(database, operationId, reserved)
    }

    override suspend fun confirmCandidate(bookId: StableId, candidateId: StableId): DomainResult<CandidateView> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            val candidate = candidates(db).singleOrNull { it.id == candidateId }
                ?: abort(DomainViolation.InvalidField("candidate.id"))
            if (candidate.status !in setOf(RecurrenceCandidateStatus.PENDING_CONFIRMATION, RecurrenceCandidateStatus.INVALID)) {
                abort(DomainViolation.InvalidStateTransition("candidate.confirm"))
            }
            DomainResult.Success(candidate)
        }
    }

    override suspend fun completeCandidate(bookId: StableId, candidateId: StableId, transactionId: StableId): DomainResult<Unit> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { db ->
            val transactionInternal = db.queryOne("SELECT id FROM business_transaction WHERE uid=?", arrayOf(transactionId.bytes)) { it.getLong(0) }
                ?: abort(DomainViolation.InvalidField("candidate.transaction"))
            val completed = db.queryOne(
                "SELECT c.status,t.uid FROM recurrence_candidate c JOIN recurrence_occurrence o ON o.id=c.occurrence_id LEFT JOIN business_transaction t ON t.id=o.transaction_id WHERE c.uid=?",
                arrayOf(candidateId.bytes),
            ) { cursor ->
                RecurrenceCandidateStatus.entries[cursor.getInt(0)] to
                    if (cursor.isNull(1)) null else StableId.fromBytes(cursor.getBlob(1)).valueOrAbort()
            }
            if (completed?.first == RecurrenceCandidateStatus.ACCEPTED && completed.second == transactionId) {
                return@inLedgerTransaction DomainResult.Success(Unit)
            }
            val candidateInternal = db.queryOne(
                "SELECT id FROM recurrence_candidate WHERE uid=? AND status IN (?,?)",
                arrayOf(candidateId.bytes, RecurrenceCandidateStatus.PENDING_CONFIRMATION.ordinal, RecurrenceCandidateStatus.INVALID.ordinal),
            ) { it.getLong(0) } ?: abort(DomainViolation.InvalidStateTransition("candidate.complete"))
            db.execSQL("UPDATE recurrence_candidate SET status=?,validation_error_code=NULL WHERE id=?", arrayOf<Any>(RecurrenceCandidateStatus.ACCEPTED.ordinal, candidateInternal))
            db.execSQL(
                "UPDATE recurrence_occurrence SET status=?,transaction_id=?,candidate_id=NULL,error_code=NULL WHERE candidate_id=?",
                arrayOf<Any>(RecurrenceOccurrenceStatus.TRANSACTION_CREATED.ordinal, transactionInternal, candidateInternal),
            )
            DomainResult.Success(Unit)
        }
    }

    override suspend fun skipCandidate(bookId: StableId, candidateId: StableId, changedAt: Instant): DomainResult<Unit> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { db ->
            val updated = db.compileStatement("UPDATE recurrence_candidate SET status=? WHERE uid=? AND status IN (?,?)").apply {
                bindLong(1, RecurrenceCandidateStatus.REJECTED.ordinal.toLong())
                bindBlob(2, candidateId.bytes)
                bindLong(3, RecurrenceCandidateStatus.PENDING_CONFIRMATION.ordinal.toLong())
                bindLong(4, RecurrenceCandidateStatus.INVALID.ordinal.toLong())
            }.executeUpdateDelete()
            if (updated != 1) abort(DomainViolation.InvalidStateTransition("candidate.skip"))
            db.execSQL(
                "UPDATE recurrence_occurrence SET status=? WHERE candidate_id=(SELECT id FROM recurrence_candidate WHERE uid=?)",
                arrayOf<Any>(RecurrenceOccurrenceStatus.SKIPPED.ordinal, candidateId.bytes),
            )
            DomainResult.Success(Unit)
        }
    }

    private fun persistSeries(db: SupportSQLiteDatabase, request: SaveRecurrenceRequest) {
        val book = requireBook(db, request.ids.bookId)
        requireExpectedRevision(book, request.ids.expectedLocalRevision)
        val blueprint = db.queryOne(
            "SELECT b.id,r.fixed_place_id FROM transaction_blueprint b JOIN transaction_blueprint_revision r ON r.id=b.current_revision_id WHERE b.uid=? AND b.status=?",
            arrayOf(request.draft.blueprintId.bytes, EntityStatus.ACTIVE.ordinal),
        ) { it.getLong(0) to if (it.isNull(1)) null else it.getLong(1) } ?: abort(DomainViolation.InvalidField("recurrence.blueprint"))
        if (request.draft.occurrenceTime != DEFAULT_OCCURRENCE_TIME) abort(DomainViolation.InvalidField("recurrence.occurrenceTime"))
        val suppliedPlace = request.draft.fixedPlaceId?.let { db.requireInternalId("place", it) }
        if (suppliedPlace != blueprint.second) abort(DomainViolation.InvalidField("recurrence.fixedPlace"))
        val existing = db.queryOne(
            "SELECT s.id,r.uid FROM recurrence_series s LEFT JOIN recurrence_series_revision r ON r.id=s.current_revision_id WHERE s.uid=?",
            arrayOf(request.draft.id.bytes),
        ) { it.getLong(0) to if (it.isNull(1)) null else it.stableId("uid") }
        if (existing?.second != request.draft.expectedRevisionId) abort(DomainViolation.StaleExpectedRevision)
        val next = Math.addExact(book.localRevision, 1L)
        val canonical = canonicalSeries(request.draft, request.exceptions)
        startCommit(db, request.ids.commitId, request.ids.deviceInstanceId, request.ids.changedAt, book, next, canonical)
        val commitId = db.requireInternalId("book_commit", request.ids.commitId)
        val seriesId = existing?.first ?: db.allocateInternalId("recurrence_series", request.draft.id).also { id ->
            db.execSQL(
                "INSERT INTO recurrence_series(id,uid,blueprint_id,current_revision_id,status) VALUES(?,?,?,NULL,?)",
                arrayOf<Any>(id, request.draft.id.bytes, blueprint.first, request.draft.status.ordinal),
            )
        }
        val number = Math.addExact(db.queryOne("SELECT COALESCE(MAX(revision_no),0) FROM recurrence_series_revision WHERE series_id=?", arrayOf(seriesId)) { it.getInt(0) } ?: 0, 1)
        val revisionId = db.allocateInternalId("recurrence_series_revision", request.draft.revisionId)
        db.execSQL(
            "INSERT INTO recurrence_series_revision(id,uid,series_id,revision_no,frequency,interval_value,start_date,end_date,max_occurrences,zone_id,month_day,nth_week,weekday,missing_day_policy,weekend_policy,generation_mode,notify_candidate,created_commit_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                revisionId, request.draft.revisionId.bytes, seriesId, number, request.draft.rule.frequency.ordinal, request.draft.rule.interval,
                request.draft.startDate.toStorageInt(), request.draft.endDate?.toStorageInt(), request.draft.maxOccurrences, request.draft.zoneId.id,
                request.draft.rule.monthDay, request.draft.rule.nthWeek, request.draft.rule.weekday?.value, request.draft.rule.missingDayPolicy.ordinal,
                request.draft.rule.weekendAdjustment.ordinal, request.draft.generationMode.ordinal, request.draft.notifyCandidate.toSqlInt(), commitId,
            ),
        )
        request.draft.rule.weekdays.sortedBy(DayOfWeek::getValue).forEach { weekday ->
            db.execSQL("INSERT INTO recurrence_rule_weekday(series_revision_id,weekday) VALUES(?,?)", arrayOf<Any>(revisionId, weekday.value))
        }
        request.exceptions.forEach { saveException(db, seriesId, it) }
        db.execSQL(
            "UPDATE recurrence_series SET blueprint_id=?,current_revision_id=?,status=? WHERE id=?",
            arrayOf<Any>(blueprint.first, revisionId, request.draft.status.ordinal, seriesId),
        )
        audit(db, request.ids.entityRevisionId, request.ids.commitId, EntityType.RECURRENCE_SERIES, request.draft.id, existing == null, canonical)
        finishReferenceCommit(db, request.ids.commitId, next, book.valuationRevision)
    }

    private fun reserveDue(db: SupportSQLiteDatabase, bookId: StableId, through: Instant): List<ReservedOccurrence> {
        requireBook(db, bookId)
        val activeSeries = series(db).filter { it.status == RecurrenceStatus.ACTIVE }
        val reserved = mutableListOf<ReservedOccurrence>()
        activeSeries.forEach { view ->
            val existingInstants = db.queryList(
                "SELECT occurrence_instant FROM recurrence_occurrence WHERE series_id=(SELECT id FROM recurrence_series WHERE uid=?) AND status<>?",
                arrayOf(view.id.bytes, RecurrenceOccurrenceStatus.CANCELLED.ordinal),
            ) { it.getLong(0).toStoredInstant() }.toSet()
            val domainRevision = view.toDomain()
            val exceptions = exceptions(db, view.id)
            RecurrenceEngine.next(domainRevision, exceptions, through, existingInstants).forEach { planned ->
                val occurrenceId = deriveStableId("occurrence", view.id, view.revisionId, planned.occurrenceInstant.toEpochMilli())
                val internal = db.allocateInternalId("recurrence_occurrence", occurrenceId)
                db.execSQL(
                    "INSERT INTO recurrence_occurrence(id,uid,series_id,series_revision_id,occurrence_instant,local_date,status,candidate_id,transaction_id,error_code) VALUES(?,?,?,?,?,?,?,NULL,NULL,NULL)",
                    arrayOf<Any>(internal, occurrenceId.bytes, db.requireInternalId("recurrence_series", view.id), db.requireInternalId("recurrence_series_revision", view.revisionId), planned.occurrenceInstant.toEpochMilli(), planned.localDate.toStorageInt(), RecurrenceOccurrenceStatus.PENDING.ordinal),
                )
                val blueprint = planned.blueprintRevisionId?.value?.let { revision ->
                    blueprintRevision(db, null, revision)
                } ?: blueprintRevision(db, view.blueprintId, null)
                reserved += ReservedOccurrence(occurrenceId, view, blueprint, planned.occurrenceInstant, planned.localDate, RecurrenceOccurrenceStatus.PENDING)
            }
        }
        return reserved
    }

    private suspend fun processReserved(database: LedgerDatabase, bookId: StableId, reserved: List<ReservedOccurrence>): DomainResult<CatchUpResult> {
        var candidateCount = 0
        var transactionCount = 0
        var skippedCount = 0
        reserved.forEach { occurrence ->
            when (occurrence.status) {
                RecurrenceOccurrenceStatus.CANDIDATE_CREATED, RecurrenceOccurrenceStatus.TRANSACTION_CREATED, RecurrenceOccurrenceStatus.SKIPPED, RecurrenceOccurrenceStatus.CANCELLED -> skippedCount += 1
                RecurrenceOccurrenceStatus.PENDING, RecurrenceOccurrenceStatus.FAILED -> {
                    if (occurrence.series.generationMode == RecurrenceGenerationMode.CANDIDATE || occurrence.blueprint.amountExpression == null) {
                        database.inLedgerTransaction { db -> createCandidate(db, occurrence, null) }
                        candidateCount += 1
                    } else {
                        val result = formalGenerator.generate(
                            FormalOccurrenceRequest(bookId, occurrence.id, occurrence.series.id, occurrence.series.revisionId, occurrence.blueprint, occurrence.instant, occurrence.localDate, occurrence.series.zoneId),
                        )
                        when (result) {
                            is DomainResult.Success -> {
                                database.inLedgerTransaction { db ->
                                    val transactionInternal = db.queryOne(
                                        "SELECT id FROM business_transaction WHERE uid=?",
                                        arrayOf(result.value.bytes),
                                    ) { it.getLong(0) } ?: abort(DomainViolation.InvalidField("recurrence.transaction"))
                                    db.execSQL(
                                        "UPDATE recurrence_occurrence SET status=?,transaction_id=?,candidate_id=NULL,error_code=NULL WHERE uid=?",
                                        arrayOf<Any>(RecurrenceOccurrenceStatus.TRANSACTION_CREATED.ordinal, transactionInternal, occurrence.id.bytes),
                                    )
                                }
                                transactionCount += 1
                            }
                            is DomainResult.Failure -> {
                                database.inLedgerTransaction { db -> createCandidate(db, occurrence, result.error.code) }
                                candidateCount += 1
                            }
                        }
                    }
                }
            }
        }
        return DomainResult.Success(CatchUpResult(candidateCount, transactionCount, skippedCount, 0))
    }

    private fun createCandidate(db: SupportSQLiteDatabase, occurrence: ReservedOccurrence, errorCode: String?) {
        val candidateId = deriveStableId("candidate", occurrence.id)
        val existing = db.queryOne("SELECT id FROM recurrence_candidate WHERE occurrence_id=(SELECT id FROM recurrence_occurrence WHERE uid=?)", arrayOf(occurrence.id.bytes)) { it.getLong(0) }
        val candidateInternal = existing ?: db.allocateInternalId("recurrence_candidate", candidateId).also { id ->
            db.execSQL(
                "INSERT INTO recurrence_candidate(id,uid,occurrence_id,blueprint_revision_id,created_at,status,validation_error_code) VALUES(?,?,(SELECT id FROM recurrence_occurrence WHERE uid=?),(SELECT id FROM transaction_blueprint_revision WHERE uid=?),?,?,?)",
                arrayOf<Any?>(id, candidateId.bytes, occurrence.id.bytes, occurrence.blueprint.revisionId.bytes, occurrence.instant.toEpochMilli(), if (errorCode == null) RecurrenceCandidateStatus.PENDING_CONFIRMATION.ordinal else RecurrenceCandidateStatus.INVALID.ordinal, errorCode),
            )
        }
        db.execSQL(
            "UPDATE recurrence_occurrence SET status=?,candidate_id=?,transaction_id=NULL,error_code=NULL WHERE uid=?",
            arrayOf<Any>(RecurrenceOccurrenceStatus.CANDIDATE_CREATED.ordinal, candidateInternal, occurrence.id.bytes),
        )
    }

    private fun reservedById(db: SupportSQLiteDatabase, occurrenceId: StableId): ReservedOccurrence? = db.queryOne(
        "SELECT o.uid,o.occurrence_instant,o.local_date,o.status,s.uid series_uid,sr.uid revision_uid,b.uid blueprint_uid FROM recurrence_occurrence o JOIN recurrence_series s ON s.id=o.series_id JOIN recurrence_series_revision sr ON sr.id=o.series_revision_id JOIN transaction_blueprint b ON b.id=s.blueprint_id WHERE o.uid=?",
        arrayOf(occurrenceId.bytes),
    ) { cursor ->
        val seriesView = seriesRevision(db, cursor.stableId("series_uid"), cursor.stableId("revision_uid"))
        val blueprint = blueprintRevision(db, cursor.stableId("blueprint_uid"), null)
        ReservedOccurrence(cursor.stableId("uid"), seriesView, blueprint, cursor.getLong(1).toStoredInstant(), cursor.getInt(2).toStoredLocalDate(), RecurrenceOccurrenceStatus.entries[cursor.getInt(3)])
    }

    private fun blueprints(db: SupportSQLiteDatabase): List<BlueprintView> = db.queryList(
        "SELECT b.uid,r.uid,r.revision_no,b.name,b.icon_key,b.color_argb,b.status,r.target_kind,c.uid category_uid,pa.uid primary_uid,sa.uid secondary_uid,pc.uid card_uid,m.uid merchant_uid,p.uid project_uid,g.uid goal_uid,act.uid activity_uid,r.amount_expression,r.currency_code,r.note_template,pl.uid place_uid FROM transaction_blueprint b JOIN transaction_blueprint_revision r ON r.id=b.current_revision_id LEFT JOIN category c ON c.id=r.category_id LEFT JOIN user_account pa ON pa.id=r.primary_account_id LEFT JOIN user_account sa ON sa.id=r.secondary_account_id LEFT JOIN payment_card pc ON pc.id=r.card_id LEFT JOIN merchant m ON m.id=r.merchant_id LEFT JOIN project p ON p.id=r.project_id LEFT JOIN goal g ON g.id=r.goal_id LEFT JOIN settlement_activity act ON act.id=r.settlement_activity_id LEFT JOIN place pl ON pl.id=r.fixed_place_id ORDER BY b.status,b.name,b.uid",
    ) { c ->
        BlueprintView(c.stableId("uid"), c.stableIdAt(1), c.getInt(2), c.getString(3), c.getString(4), c.getInt(5), EntityStatus.entries[c.getInt(6)], TransactionKind.entries[c.getInt(7)], c.nullableStableId("category_uid"), c.nullableStableId("primary_uid"), c.nullableStableId("secondary_uid"), c.nullableStableId("card_uid"), c.nullableStableId("merchant_uid"), c.nullableStableId("project_uid"), c.nullableStableId("goal_uid"), c.nullableStableId("activity_uid"), c.nullableString("amount_expression"), c.nullableString("currency_code")?.let { CurrencyCode.parse(it).valueOrAbort() }, c.nullableString("note_template"), c.nullableStableId("place_uid"))
    }

    private fun series(db: SupportSQLiteDatabase): List<RecurrenceSeriesView> = db.queryList(
        "SELECT s.uid,r.uid,r.revision_no,b.uid blueprint_uid,b.name,s.status,r.frequency,r.interval_value,r.start_date,r.end_date,r.max_occurrences,r.zone_id,r.month_day,r.nth_week,r.weekday,r.missing_day_policy,r.weekend_policy,r.generation_mode,r.notify_candidate,pl.uid place_uid FROM recurrence_series s JOIN recurrence_series_revision r ON r.id=s.current_revision_id JOIN transaction_blueprint b ON b.id=s.blueprint_id JOIN transaction_blueprint_revision br ON br.id=b.current_revision_id LEFT JOIN place pl ON pl.id=br.fixed_place_id ORDER BY s.status,b.name,s.uid",
    ) { c ->
        val id = c.stableIdAt(0)
        val revisionId = c.stableIdAt(1)
        val weekdays = db.queryList("SELECT weekday FROM recurrence_rule_weekday WHERE series_revision_id=(SELECT id FROM recurrence_series_revision WHERE uid=?) ORDER BY weekday", arrayOf(revisionId.bytes)) { DayOfWeek.of(it.getInt(0)) }.toSet()
        val rule = RecurrenceRule(RecurrenceFrequency.entries[c.getInt(6)], c.getInt(7), weekdays, c.nullableIntAt(12), c.nullableIntAt(13), c.nullableIntAt(14)?.let(DayOfWeek::of), MissingDayPolicy.entries[c.getInt(15)], WeekendAdjustment.entries[c.getInt(16)])
        val view = RecurrenceSeriesView(id, revisionId, c.getInt(2), c.stableId("blueprint_uid"), c.getString(4), RecurrenceStatus.entries[c.getInt(5)], rule, c.getInt(8).toStoredLocalDate(), c.nullableIntAt(9)?.toStoredLocalDate(), c.nullableIntAt(10), DEFAULT_OCCURRENCE_TIME, ZoneId.of(c.getString(11)), RecurrenceGenerationMode.entries[c.getInt(17)], c.nullableStableId("place_uid"), c.getInt(18) == 1, emptyList())
        view.copy(preview = RecurrenceEngine.preview(view.toDomain()))
    }

    private fun seriesRevision(db: SupportSQLiteDatabase, seriesId: StableId, revisionId: StableId): RecurrenceSeriesView = db.queryOne(
        "SELECT s.uid,r.uid,r.revision_no,b.uid blueprint_uid,b.name,s.status,r.frequency,r.interval_value,r.start_date,r.end_date,r.max_occurrences,r.zone_id,r.month_day,r.nth_week,r.weekday,r.missing_day_policy,r.weekend_policy,r.generation_mode,r.notify_candidate,pl.uid place_uid FROM recurrence_series s JOIN recurrence_series_revision r ON r.series_id=s.id JOIN transaction_blueprint b ON b.id=s.blueprint_id JOIN transaction_blueprint_revision br ON br.id=b.current_revision_id LEFT JOIN place pl ON pl.id=br.fixed_place_id WHERE s.uid=? AND r.uid=?",
        arrayOf(seriesId.bytes, revisionId.bytes),
    ) { c ->
        val weekdays = db.queryList(
            "SELECT weekday FROM recurrence_rule_weekday WHERE series_revision_id=(SELECT id FROM recurrence_series_revision WHERE uid=?) ORDER BY weekday",
            arrayOf(revisionId.bytes),
        ) { DayOfWeek.of(it.getInt(0)) }.toSet()
        val rule = RecurrenceRule(RecurrenceFrequency.entries[c.getInt(6)], c.getInt(7), weekdays, c.nullableIntAt(12), c.nullableIntAt(13), c.nullableIntAt(14)?.let(DayOfWeek::of), MissingDayPolicy.entries[c.getInt(15)], WeekendAdjustment.entries[c.getInt(16)])
        RecurrenceSeriesView(c.stableIdAt(0), c.stableIdAt(1), c.getInt(2), c.stableId("blueprint_uid"), c.getString(4), RecurrenceStatus.entries[c.getInt(5)], rule, c.getInt(8).toStoredLocalDate(), c.nullableIntAt(9)?.toStoredLocalDate(), c.nullableIntAt(10), DEFAULT_OCCURRENCE_TIME, ZoneId.of(c.getString(11)), RecurrenceGenerationMode.entries[c.getInt(17)], c.nullableStableId("place_uid"), c.getInt(18) == 1, emptyList())
    } ?: abort(FinanceDataError.CorruptData)

    private fun blueprintRevision(db: SupportSQLiteDatabase, blueprintId: StableId?, revisionId: StableId?): BlueprintView {
        if ((blueprintId == null) == (revisionId == null)) abort(FinanceDataError.CorruptData)
        val where = if (blueprintId != null) "b.uid=? AND r.id=b.current_revision_id" else "r.uid=?"
        val argument = (blueprintId ?: revisionId ?: abort(FinanceDataError.CorruptData)).bytes
        return db.queryOne(
            "SELECT b.uid,r.uid,r.revision_no,b.name,b.icon_key,b.color_argb,b.status,r.target_kind,c.uid category_uid,pa.uid primary_uid,sa.uid secondary_uid,pc.uid card_uid,m.uid merchant_uid,p.uid project_uid,g.uid goal_uid,act.uid activity_uid,r.amount_expression,r.currency_code,r.note_template,pl.uid place_uid FROM transaction_blueprint b JOIN transaction_blueprint_revision r ON r.blueprint_id=b.id LEFT JOIN category c ON c.id=r.category_id LEFT JOIN user_account pa ON pa.id=r.primary_account_id LEFT JOIN user_account sa ON sa.id=r.secondary_account_id LEFT JOIN payment_card pc ON pc.id=r.card_id LEFT JOIN merchant m ON m.id=r.merchant_id LEFT JOIN project p ON p.id=r.project_id LEFT JOIN goal g ON g.id=r.goal_id LEFT JOIN settlement_activity act ON act.id=r.settlement_activity_id LEFT JOIN place pl ON pl.id=r.fixed_place_id WHERE $where",
            arrayOf(argument),
        ) { c ->
            BlueprintView(c.stableIdAt(0), c.stableIdAt(1), c.getInt(2), c.getString(3), c.getString(4), c.getInt(5), EntityStatus.entries[c.getInt(6)], TransactionKind.entries[c.getInt(7)], c.nullableStableId("category_uid"), c.nullableStableId("primary_uid"), c.nullableStableId("secondary_uid"), c.nullableStableId("card_uid"), c.nullableStableId("merchant_uid"), c.nullableStableId("project_uid"), c.nullableStableId("goal_uid"), c.nullableStableId("activity_uid"), c.nullableString("amount_expression"), c.nullableString("currency_code")?.let { CurrencyCode.parse(it).valueOrAbort() }, c.nullableString("note_template"), c.nullableStableId("place_uid"))
        } ?: abort(FinanceDataError.CorruptData)
    }

    private fun candidates(db: SupportSQLiteDatabase): List<CandidateView> = db.queryList(
        "SELECT c.uid,o.uid,s.uid series_uid,br.uid blueprint_revision_uid,o.occurrence_instant,o.local_date,c.status,c.validation_error_code FROM recurrence_candidate c JOIN recurrence_occurrence o ON o.id=c.occurrence_id JOIN recurrence_series s ON s.id=o.series_id JOIN transaction_blueprint_revision br ON br.id=c.blueprint_revision_id ORDER BY c.status,o.occurrence_instant,c.uid",
    ) { c ->
        val revisionId = c.stableId("blueprint_revision_uid")
        val blueprint = blueprintRevision(db, null, revisionId)
        CandidateView(c.stableIdAt(0), c.stableIdAt(1), c.stableId("series_uid"), blueprint, c.getLong(4).toStoredInstant(), c.getInt(5).toStoredLocalDate(), RecurrenceCandidateStatus.entries[c.getInt(6)], if (c.isNull(7)) null else c.getString(7))
    }

    private fun occurrences(db: SupportSQLiteDatabase): List<OccurrenceView> = db.queryList(
        "SELECT o.uid,s.uid series_uid,r.uid revision_uid,o.occurrence_instant,o.local_date,o.status,c.uid candidate_uid,t.uid transaction_uid,o.error_code FROM recurrence_occurrence o JOIN recurrence_series s ON s.id=o.series_id JOIN recurrence_series_revision r ON r.id=o.series_revision_id LEFT JOIN recurrence_candidate c ON c.id=o.candidate_id LEFT JOIN business_transaction t ON t.id=o.transaction_id ORDER BY o.occurrence_instant DESC,o.uid",
    ) { c -> OccurrenceView(c.stableIdAt(0), c.stableId("series_uid"), c.stableId("revision_uid"), c.getLong(3).toStoredInstant(), c.getInt(4).toStoredLocalDate(), RecurrenceOccurrenceStatus.entries[c.getInt(5)], c.nullableStableId("candidate_uid"), c.nullableStableId("transaction_uid"), if (c.isNull(8)) null else c.getString(8)) }

    private fun exceptions(db: SupportSQLiteDatabase, seriesId: StableId): List<RecurrenceException> = db.queryList(
        "SELECT e.occurrence_local_date,e.action,r.uid revision_uid,e.override_instant FROM recurrence_exception e JOIN recurrence_series s ON s.id=e.series_id LEFT JOIN transaction_blueprint_revision r ON r.id=e.override_blueprint_revision_id WHERE s.uid=? ORDER BY e.occurrence_local_date",
        arrayOf(seriesId.bytes),
    ) { c -> RecurrenceException(RecurrenceSeriesId(seriesId), c.getInt(0).toStoredLocalDate(), RecurrenceExceptionAction.entries[c.getInt(1)], c.nullableStableId("revision_uid")?.let(::TransactionBlueprintRevisionId), if (c.isNull(3)) null else c.getLong(3).toStoredInstant()) }

    private fun saveException(db: SupportSQLiteDatabase, seriesId: Long, draft: RecurrenceExceptionDraft) {
        db.execSQL(
            "INSERT OR REPLACE INTO recurrence_exception(series_id,occurrence_local_date,action,override_blueprint_revision_id,override_instant) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(seriesId, draft.localDate.toStorageInt(), draft.action.ordinal, draft.overrideBlueprintRevisionId?.let { db.requireInternalId("transaction_blueprint_revision", it) }, draft.overrideInstant?.toEpochMilli()),
        )
    }

    private fun validateBlueprintReferences(db: SupportSQLiteDatabase, draft: BlueprintDraft) {
        draft.categoryId?.let { db.requireInternalId("category", it) }
        draft.primaryAccountId?.let { db.requireInternalId("user_account", it) }
        draft.secondaryAccountId?.let { db.requireInternalId("user_account", it) }
        draft.cardId?.let { db.requireInternalId("payment_card", it) }
        draft.merchantId?.let { db.requireInternalId("merchant", it) }
        draft.projectId?.let { db.requireInternalId("project", it) }
        draft.goalId?.let { db.requireInternalId("goal", it) }
        draft.settlementActivityId?.let { db.requireInternalId("settlement_activity", it) }
        draft.fixedPlaceId?.let { db.requireInternalId("place", it) }
        if (draft.targetKind in setOf(TransactionKind.EXPENSE, TransactionKind.INCOME) && draft.categoryId == null) abort(DomainViolation.InvalidField("blueprint.category"))
    }

    private fun startCommit(db: SupportSQLiteDatabase, commitId: StableId, deviceId: StableId, changedAt: Instant, book: BookRow, revision: Long, canonical: ByteArray) {
        db.execSQL(
            "INSERT INTO book_commit(id,uid,local_revision,kind,command_uid,device_instance_uid,created_at,root_hash) VALUES(?,?,?,?,NULL,?,?,?)",
            arrayOf<Any>(db.allocateInternalId("book_commit", commitId), commitId.bytes, revision, CommitKind.REFERENCE_DATA_CHANGE.ordinal, deviceId.bytes, changedAt.toEpochMilli(), sha256(canonical)),
        )
        db.execSQL("INSERT INTO book_commit_parent(commit_id,parent_commit_id,ordinal) VALUES(?,?,0)", arrayOf<Any>(db.requireInternalId("book_commit", commitId), book.headCommitId))
    }

    private fun audit(db: SupportSQLiteDatabase, revisionId: StableId, commitId: StableId, type: EntityType, entityId: StableId, create: Boolean, canonical: ByteArray) {
        val number = Math.addExact(db.queryOne("SELECT COALESCE(MAX(revision_no),0) FROM entity_revision WHERE entity_type=? AND entity_uid=?", arrayOf(type.ordinal, entityId.bytes)) { it.getInt(0) } ?: 0, 1)
        val digest = sha256(canonical)
        db.execSQL(
            "INSERT INTO entity_revision(id,uid,entity_type,entity_uid,revision_no,action,commit_id,content_hash,canonical_snapshot_blob,schema_version) VALUES(?,?,?,?,?,?,?,?,?,1)",
            arrayOf<Any>(db.allocateInternalId("entity_revision", revisionId), revisionId.bytes, type.ordinal, entityId.bytes, number, if (create) EntityRevisionAction.CREATE.ordinal else EntityRevisionAction.EDIT.ordinal, db.requireInternalId("book_commit", commitId), digest, canonical),
        )
        db.execSQL(
            "INSERT INTO entity_change(commit_id,entity_type,entity_uid,operation,before_hash,after_hash,entity_revision_uid) VALUES(?,?,?,?,NULL,?,?)",
            arrayOf<Any>(db.requireInternalId("book_commit", commitId), type.ordinal, entityId.bytes, if (create) EntityChangeOperation.CREATE.ordinal else EntityChangeOperation.UPDATE.ordinal, digest, revisionId.bytes),
        )
    }

    private fun finishReferenceCommit(db: SupportSQLiteDatabase, commitId: StableId, revision: Long, valuationRevision: Long) {
        db.execSQL("UPDATE book SET head_commit_id=?,local_revision=? WHERE id=1", arrayOf<Any>(db.requireInternalId("book_commit", commitId), revision))
        projections.rebuildAll(db, revision, valuationRevision)
        if (projections.mismatchedFamilies(db, revision, valuationRevision).isNotEmpty()) abort(FinanceDataError.ProjectionMismatch)
        if (!DatabaseIntegrityAudit.run(db).isValid) abort(FinanceDataError.CorruptData)
    }

    private fun requireBook(db: SupportSQLiteDatabase, bookId: StableId): BookRow = db.queryOne(
        "SELECT uid,head_commit_id,local_revision,valuation_revision,state FROM book WHERE id=1",
    ) { BookRow(it.getBlob(0), it.getLong(1), it.getLong(2), it.getLong(3), it.getInt(4)) }
        ?.also { if (!it.uid.contentEquals(bookId.bytes) || it.state != 0) abort(FinanceDataError.MaintenanceRequired) }
        ?: abort(FinanceDataError.CorruptData)

    private fun requireExpectedRevision(book: BookRow, expected: Long) {
        if (book.localRevision != expected) abort(DomainViolation.StaleExpectedRevision)
    }

    private suspend fun <T> withDatabase(bookId: StableId, block: suspend (LedgerDatabase) -> DomainResult<T>): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { openSelectedLedger(applicationContext, it, databaseName) }
            try {
                block(database)
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

    private fun RecurrenceSeriesView.toDomain(): RecurrenceSeriesRevision = RecurrenceSeriesRevision(RecurrenceSeriesRevisionId(revisionId), RecurrenceSeriesId(id), revisionNumber, rule, startDate, endDate, maxOccurrences, occurrenceTime, zoneId, generationMode, fixedPlaceId?.let { app.ledger.finance.domain.PlaceId(it) }, notifyCandidate, BookCommitId(ZERO_ID))

    private fun deriveStableId(label: String, vararg parts: Any): StableId {
        val digest = sha256((listOf(label) + parts.map(Any::toString)).joinToString("\u001f").toByteArray(Charsets.UTF_8))
        return StableId.fromBytes(digest.copyOf(StableId.BYTE_COUNT)).valueOrAbort()
    }

    private fun canonicalBlueprint(d: BlueprintDraft): ByteArray = canonical("blueprint", d.id.toString(), d.revisionId.toString(), d.name.trim(), d.iconKey, d.colorArgb.toString(), d.status.name, d.targetKind.name, d.categoryId?.toString().orEmpty(), d.primaryAccountId?.toString().orEmpty(), d.secondaryAccountId?.toString().orEmpty(), d.cardId?.toString().orEmpty(), d.merchantId?.toString().orEmpty(), d.projectId?.toString().orEmpty(), d.goalId?.toString().orEmpty(), d.settlementActivityId?.toString().orEmpty(), d.amountExpression.orEmpty(), d.currency?.value.orEmpty(), d.noteTemplate.orEmpty(), d.fixedPlaceId?.toString().orEmpty())
    private fun canonicalSeries(d: RecurrenceSeriesDraft, exceptions: List<RecurrenceExceptionDraft>): ByteArray = canonical("series", d.id.toString(), d.revisionId.toString(), d.blueprintId.toString(), d.status.name, d.rule.toString(), d.startDate.toString(), d.endDate?.toString().orEmpty(), d.maxOccurrences?.toString().orEmpty(), d.zoneId.id, d.generationMode.name, d.fixedPlaceId?.toString().orEmpty(), d.notifyCandidate.toString(), exceptions.joinToString("|") { it.toString() })
    private fun canonical(vararg values: String): ByteArray = values.joinToString("\u001f").toByteArray(Charsets.UTF_8)
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class BookRow(val uid: ByteArray, val headCommitId: Long, val localRevision: Long, val valuationRevision: Long, val state: Int)
    private data class ReservedOccurrence(val id: StableId, val series: RecurrenceSeriesView, val blueprint: BlueprintView, val instant: Instant, val localDate: LocalDate, val status: RecurrenceOccurrenceStatus)

    private companion object {
        val DEFAULT_OCCURRENCE_TIME: LocalTime = LocalTime.of(9, 0)
        val ZERO_ID: StableId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT)).valueOrAbort()
    }
}

private fun android.database.Cursor.stableIdAt(index: Int): StableId = StableId.fromBytes(getBlob(index)).valueOrAbort()
private fun android.database.Cursor.nullableIntAt(index: Int): Int? = if (isNull(index)) null else getInt(index)
