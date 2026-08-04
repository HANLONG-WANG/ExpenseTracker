package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.domain.FinancialMutationPlan

internal class RoomBudgetPlanWriter {
    fun write(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.budgetMonthMutations.forEach { mutation -> writeMonth(database, mutation) }
        plan.budgetTemplateMutations.forEach { mutation -> writeTemplate(database, mutation) }
    }

    private fun writeMonth(
        database: SupportSQLiteDatabase,
        mutation: app.ledger.finance.domain.BudgetMonthMutation,
    ) {
        val currentId = database.queryOne(
            "SELECT id FROM budget_month WHERE uid=?",
            arrayOf(mutation.month.id.value.bytes),
        ) { it.getLong(0) }
        val monthId = currentId ?: createMonth(database, mutation)
        val revisionId = database.allocateInternalId("budget_month_revision", mutation.revision.id.value)
        database.execSQL(
            "INSERT INTO budget_month_revision(" +
                "id,uid,budget_month_id,revision_no,base_total_minor,source_template_revision_id,created_commit_id" +
                ") VALUES (?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                revisionId,
                mutation.revision.id.value.bytes,
                monthId,
                mutation.revision.revisionNumber,
                mutation.revision.totalBaseMinor,
                mutation.revision.sourceTemplateRevisionId?.let {
                    database.requireInternalId("budget_template_revision", it.value)
                },
                database.commitId(mutation.revision.createdCommitId),
            ),
        )
        mutation.revision.categoryLimits.forEach { limit ->
            database.execSQL(
                "INSERT INTO budget_category_limit(" +
                    "budget_month_revision_id,category_id,amount_base_minor" +
                    ") VALUES (?,?,?)",
                arrayOf<Any>(
                    revisionId,
                    database.requireInternalId("category", limit.categoryId.value),
                    limit.amountBaseMinor,
                ),
            )
        }
        database.execSQL(
            "UPDATE budget_month SET current_revision_id=? WHERE id=?",
            arrayOf<Any>(revisionId, monthId),
        )
    }

    private fun createMonth(
        database: SupportSQLiteDatabase,
        mutation: app.ledger.finance.domain.BudgetMonthMutation,
    ): Long = database.allocateInternalId("budget_month", mutation.month.id.value).also { allocated ->
        database.execSQL(
            "INSERT INTO budget_month(id,uid,year_month,current_revision_id) VALUES (?,?,?,NULL)",
            arrayOf<Any>(allocated, mutation.month.id.value.bytes, mutation.month.month.toStorageInt()),
        )
    }

    private fun writeTemplate(
        database: SupportSQLiteDatabase,
        mutation: app.ledger.finance.domain.BudgetTemplateMutation,
    ) {
        val currentId = database.queryOne(
            "SELECT id FROM budget_template WHERE uid=?",
            arrayOf(mutation.template.id.value.bytes),
        ) { it.getLong(0) }
        val templateId = currentId ?: createTemplate(database, mutation)
        val revisionId = database.allocateInternalId("budget_template_revision", mutation.revision.id.value)
        database.execSQL(
            "INSERT INTO budget_template_revision(" +
                "id,uid,template_id,revision_no,total_base_minor,created_commit_id" +
                ") VALUES (?,?,?,?,?,?)",
            arrayOf<Any>(
                revisionId,
                mutation.revision.id.value.bytes,
                templateId,
                mutation.revision.revisionNumber,
                mutation.revision.totalBaseMinor,
                database.commitId(mutation.revision.createdCommitId),
            ),
        )
        mutation.revision.categoryLimits.forEach { limit ->
            database.execSQL(
                "INSERT INTO budget_template_category_limit(" +
                    "template_revision_id,category_id,amount_base_minor" +
                    ") VALUES (?,?,?)",
                arrayOf<Any>(
                    revisionId,
                    database.requireInternalId("category", limit.categoryId.value),
                    limit.amountBaseMinor,
                ),
            )
        }
        database.execSQL(
            "UPDATE budget_template SET name=?,status=?,current_revision_id=? WHERE id=?",
            arrayOf<Any>(mutation.template.name, mutation.template.status.ordinal, revisionId, templateId),
        )
    }

    private fun createTemplate(
        database: SupportSQLiteDatabase,
        mutation: app.ledger.finance.domain.BudgetTemplateMutation,
    ): Long = database.allocateInternalId("budget_template", mutation.template.id.value).also { allocated ->
        database.execSQL(
            "INSERT INTO budget_template(id,uid,name,current_revision_id,status) VALUES (?,?,?,NULL,?)",
            arrayOf<Any>(
                allocated,
                mutation.template.id.value.bytes,
                mutation.template.name,
                mutation.template.status.ordinal,
            ),
        )
    }
}
