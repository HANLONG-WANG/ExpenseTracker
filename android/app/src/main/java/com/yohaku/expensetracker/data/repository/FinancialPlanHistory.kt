package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class PoolMergeArchive(val arrangement:FinancialArrangementEntity,val pool:FinancialPlanEntity)
@Serializable data class FinancialPlanArchive(val plan:FinancialPlanEntity,val bills:List<FinancialBillEntity>,val facts:FinancialPredictionFacts?=null,val poolMerge:PoolMergeArchive?=null,val batchArrangement:BatchArrangementArchive?=null)

internal suspend fun LedgerRepository.archiveFinancialPlan(plan:FinancialPlanEntity,bills:List<FinancialBillEntity>,reason:String,causeEventId:String?=null,facts:FinancialPredictionFacts?=null,poolMerge:PoolMergeArchive?=null,batchArrangement:BatchArrangementArchive?=null) {
    db.financialDao().insertPlanRevision(FinancialPlanRevisionEntity(plan.bookId,plan.id,plan.revision,clock.millis(),reason,causeEventId,
        json.encodeToString(FinancialPlanArchive(plan,bills.sortedBy{it.number},facts,poolMerge,batchArrangement))))
}

/** Older installations gain an honest current baseline, without inventing lost historical versions. */
internal suspend fun LedgerRepository.archiveExistingPlan(plan:FinancialPlanEntity,bills:List<FinancialBillEntity>,facts:FinancialPredictionFacts?=null) {
    if(db.financialDao().planRevision(plan.bookId,plan.id,plan.revision)==null)archiveFinancialPlan(plan,bills,"EXISTING",facts=facts)
}
