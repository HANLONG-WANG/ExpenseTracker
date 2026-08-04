package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.domain.DueDateRule
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.StatementDateRule

internal class RoomCreditPlanWriter {
    fun write(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        writeProfiles(database, plan)
        writeStatements(database, plan)
    }

    private fun writeProfiles(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.creditProfileMutations.forEach { mutation ->
            val profile = mutation.profile
            val accountId = database.requireInternalId("user_account", profile.accountId.value)
            val values = arrayOf<Any?>(
                statementRuleType(profile.statementRule),
                (profile.statementRule as? StatementDateRule.DayOfMonth)?.day,
                dueRuleType(profile.paymentDueRule),
                (profile.paymentDueRule as? DueDateRule.FixedDay)?.day,
                (profile.paymentDueRule as? DueDateRule.DaysAfterStatement)?.days,
                profile.statementZoneId.id,
                profile.standardLimitMinor,
                profile.temporaryLimitMinor,
                profile.temporaryLimitExpiresOn?.toStorageInt(),
                profile.defaultPaymentAccountId?.let { database.requireInternalId("user_account", it.value) },
                profile.autoPaymentMode.ordinal,
                profile.weekendAdjustment.ordinal,
                database.commitId(profile.lastCommitId),
            )
            if (mutation.expectedLastCommitId == null) {
                database.execSQL(
                    "INSERT INTO credit_account_profile(account_id,statement_rule_type,statement_day,due_rule_type,due_day," +
                        "days_after_statement,zone_id,standard_limit_minor,temporary_limit_minor,temporary_limit_expires_on," +
                        "default_payment_account_id,auto_payment_mode,weekend_adjustment,last_commit_id) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (listOf(accountId) + values.toList()).toTypedArray(),
                )
            } else {
                val changed = database.compileStatement(
                    "UPDATE credit_account_profile SET statement_rule_type=?,statement_day=?,due_rule_type=?,due_day=?," +
                        "days_after_statement=?,zone_id=?,standard_limit_minor=?,temporary_limit_minor=?,temporary_limit_expires_on=?," +
                        "default_payment_account_id=?,auto_payment_mode=?,weekend_adjustment=?,last_commit_id=? " +
                        "WHERE account_id=? AND last_commit_id=?",
                ).apply {
                    values.forEachIndexed { index, value -> bind(index + 1, value) }
                    bindLong(values.size + 1, accountId)
                    bindLong(values.size + 2, database.commitId(requireNotNull(mutation.expectedLastCommitId)))
                }.executeUpdateDelete()
                if (changed != 1) abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
            }
            mutation.limitPeriod?.let { period ->
                database.execSQL(
                    "INSERT INTO credit_limit_period(credit_account_id,effective_from,effective_to,limit_minor,created_commit_id) VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(
                        accountId,
                        period.effectiveFrom.toStorageInt(),
                        period.effectiveTo?.toStorageInt(),
                        period.limitMinor,
                        database.commitId(period.createdCommitId),
                    ),
                )
            }
        }
    }

    private fun writeStatements(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.creditStatementMutations.forEach { mutation ->
            val statement = mutation.statement
            val revision = mutation.revision
            val accountId = database.requireInternalId("user_account", statement.creditAccountId.value)
            val statementId = if (mutation.expectedRevisionId == null) {
                database.allocateInternalId("credit_statement", statement.id.value).also { internalId ->
                    database.execSQL(
                        "INSERT INTO credit_statement(id,uid,credit_account_id,cycle_start,cycle_end,due_date,current_revision_id,status) " +
                            "VALUES(?,?,?,?,?,?,NULL,?)",
                        arrayOf<Any>(
                            internalId,
                            statement.id.value.bytes,
                            accountId,
                            statement.cycleStart.toStorageInt(),
                            statement.cycleEnd.toStorageInt(),
                            statement.dueDate.toStorageInt(),
                            statement.status.ordinal,
                        ),
                    )
                }
            } else {
                database.requireInternalId("credit_statement", statement.id.value)
            }
            val revisionId = database.allocateInternalId("credit_statement_revision", revision.id.value)
            database.execSQL(
                "INSERT INTO credit_statement_revision(id,uid,statement_id,revision_no,estimated_amount_minor,official_amount_minor," +
                    "official_recorded_at,difference_minor,statement_date,due_date,sealed,created_commit_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    revisionId,
                    revision.id.value.bytes,
                    statementId,
                    revision.revisionNumber,
                    revision.estimatedAmountMinor,
                    revision.officialAmountMinor,
                    revision.officialRecordedAt?.toStorageEpochMillis(),
                    revision.differenceMinor,
                    revision.statementDate.toStorageInt(),
                    revision.dueDate.toStorageInt(),
                    revision.sealed.toSqlInt(),
                    database.commitId(revision.createdCommitId),
                ),
            )
            val changed = if (mutation.expectedRevisionId == null) {
                database.compileStatement(
                    "UPDATE credit_statement SET current_revision_id=?,status=? WHERE id=? AND current_revision_id IS NULL",
                ).apply {
                    var index = 1
                    bindLong(index++, revisionId)
                    bindLong(index++, statement.status.ordinal.toLong())
                    bindLong(index, statementId)
                }.executeUpdateDelete()
            } else {
                database.compileStatement(
                    "UPDATE credit_statement SET cycle_start=?,cycle_end=?,due_date=?,current_revision_id=?,status=? " +
                        "WHERE id=? AND current_revision_id=?",
                ).apply {
                    var index = 1
                    bindLong(index++, statement.cycleStart.toStorageInt().toLong())
                    bindLong(index++, statement.cycleEnd.toStorageInt().toLong())
                    bindLong(index++, statement.dueDate.toStorageInt().toLong())
                    bindLong(index++, revisionId)
                    bindLong(index++, statement.status.ordinal.toLong())
                    bindLong(index++, statementId)
                    bindLong(index, database.requireInternalId("credit_statement_revision", requireNotNull(mutation.expectedRevisionId).value))
                }.executeUpdateDelete()
            }
            if (changed != 1) abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
    }

    private fun statementRuleType(rule: StatementDateRule): Int = when (rule) {
        StatementDateRule.LastDayOfMonth -> STATEMENT_LAST_DAY
        is StatementDateRule.DayOfMonth -> if (rule.missingDayPolicy == MissingDayPolicy.SKIP) STATEMENT_DAY_SKIP else STATEMENT_DAY_MOVE
    }

    private fun dueRuleType(rule: DueDateRule): Int = when (rule) {
        is DueDateRule.DaysAfterStatement -> DUE_DAYS_AFTER
        is DueDateRule.FixedDay -> if (rule.missingDayPolicy == MissingDayPolicy.SKIP) DUE_FIXED_SKIP else DUE_FIXED_MOVE
    }

    private fun androidx.sqlite.db.SupportSQLiteStatement.bind(index: Int, value: Any?) {
        when (value) {
            null -> bindNull(index)
            is Long -> bindLong(index, value)
            is Int -> bindLong(index, value.toLong())
            is String -> bindString(index, value)
            else -> error("unsupported credit bind value")
        }
    }

    private companion object {
        const val STATEMENT_DAY_MOVE = 0
        const val STATEMENT_DAY_SKIP = 1
        const val STATEMENT_LAST_DAY = 2
        const val DUE_FIXED_MOVE = 0
        const val DUE_FIXED_SKIP = 1
        const val DUE_DAYS_AFTER = 2
    }
}
