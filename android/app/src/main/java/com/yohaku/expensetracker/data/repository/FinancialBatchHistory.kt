package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.domain.finance.FinancialRuleVersion
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Read-only archival evidence. The existing undo transaction remains the sole restoration authority. */
data class FinancialBatchHistory(val archive:BatchArrangementArchive,val undone:Boolean,val zones:Map<String,String>)

suspend fun LedgerRepository.financialBatchHistory(bookId:String,accountId:String):List<FinancialBatchHistory> {
    val graph=db.financialDao().graph(bookId).first()!!
    val revisions=graph.plans.filter{it.accountId==accountId}.flatMap{db.financialDao().planRevisions(bookId,it.id).first()}
    val archives=revisions.mapNotNull{row->json.decodeFromString<FinancialPlanArchive>(row.json).batchArrangement?.let{row to it}}
    return archives.groupBy{it.second.undoGroupId}.values.map{rows->
        val archive=rows.first().second
        val zones=archive.arrangements.associate{arrangement->
            val pool=archive.afterPlans.first{it.origin=="POOL"}
            arrangement.id to (archivedOperationZone(pool,arrangement.effectiveDate)
                ?:java.time.OffsetDateTime.parse(arrangement.appliedAt).offset.id)
        }
        FinancialBatchHistory(archive,rows.any{it.first.reason=="BATCH_REVOLVING_UNDONE"},zones)
    }.sortedByDescending{it.archive.arrangements.first().appliedAt}
}

/** Pool facts retain the exact rule versions available at the operation, including their time zones. */
internal fun archivedOperationZone(plan:com.yohaku.expensetracker.data.db.FinancialPlanEntity,effectiveDate:String):String? =
    poolInput(plan)?.facts?.rules.orEmpty().filter{it.mode==plan.mode&&it.effectiveFrom<=effectiveDate}
        .maxWithOrNull(compareBy<com.yohaku.expensetracker.data.db.FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})
        ?.let{Json.decodeFromString<FinancialRuleVersion>(it.json).zoneId}

@Serializable data class BatchBillFact(val date:String,val totalMinor:Long)
@Serializable data class BatchRestorePlanFact(val id:String,val name:String,val complete:Boolean,val bills:List<BatchBillFact>)
@Serializable data class BatchTransferFact(val id:String,val sourcePlanId:String,val name:String,val principalMinor:Long,val appliedAt:String,val zoneId:String,val bills:List<BatchBillFact>)
@Serializable data class BatchRestorationFacts(val undoGroupId:String,val currency:String,val paymentDate:String,val poolId:String,val poolName:String,val transfers:List<BatchTransferFact>,val restore:List<BatchRestorePlanFact>)

/** A display projection of the archive, never an undo input or a replacement for transaction validation. */
fun batchRestorationFacts(history:FinancialBatchHistory):BatchRestorationFacts {
    val archive=history.archive
    fun bills(plan:FinancialPlanArchive)=plan.bills.filter{it.active}.sortedWith(compareBy({it.date},{it.number})).map{BatchBillFact(it.date,it.totalMinor)}
    val pool=archive.before.first{it.plan.origin=="POOL"}.plan
    return BatchRestorationFacts(archive.undoGroupId,pool.currency,archive.paymentDate,pool.id,pool.name,
        archive.arrangements.map{row->val original=archive.before.first{it.plan.id==row.originalPlanId}
            BatchTransferFact(row.id,original.plan.id,original.plan.name,row.principalMinor,row.appliedAt,history.zones.getValue(row.id),bills(original))},
        archive.before.map{BatchRestorePlanFact(it.plan.id,it.plan.name,it.plan.complete,bills(it))}).inDisplayOrder()
}

/** Cloud confirmation canonicalizes ID-bearing arrays; apply business ordering after decoding too. */
fun BatchRestorationFacts.inDisplayOrder()=copy(
    transfers=transfers.sortedWith(compareBy({it.bills.minOfOrNull{bill->bill.date}.orEmpty()},{it.name},{it.sourcePlanId})),
    restore=restore.sortedWith(compareBy({it.id==poolId},{it.bills.minOfOrNull{bill->bill.date}.orEmpty()},{it.name},{it.id})))
