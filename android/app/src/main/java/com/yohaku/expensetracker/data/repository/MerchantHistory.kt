package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.MerchantOperationEntity
import com.yohaku.expensetracker.data.db.MerchantOperationImpactEntity
import com.yohaku.expensetracker.domain.merchant.MerchantObjectType
import com.yohaku.expensetracker.domain.merchant.MerchantOperationType
import java.util.UUID

/**
 * Append only after actual business writes, inside their existing transaction. `changed` is the
 * caller's actual mutation result, not the preview count (profile-only changes also count).
 * No-op returns false. Pure merchant previews do not call this; cloud trial execution may call
 * it only inside its rollback-only outer transaction. Do not catch insert failures in the caller:
 * the enclosing transaction must roll back both business changes and history together.
 * This helper does not open/commit a transaction or write event revisions.
 */
suspend fun LedgerRepository.writeMerchantOperationInTransaction(
    operation:MerchantOperationEntity,
    impacts:List<MerchantOperationImpactEntity>,
    changed:Boolean,
):Boolean {
    check(db.inTransaction()){"Merchant history must share the business transaction"}
    if(!changed)return false
    require(impacts.all{it.bookId==operation.bookId&&it.operationId==operation.id})
    db.merchantHistoryDao().insert(operation)
    db.merchantHistoryDao().insertImpacts(impacts)
    return true
}

/** One inverse per original operation uses the existing bidirectional history links. Called only
 * after successful undo confirmation, within the transaction restoring the business rows. */
internal suspend fun LedgerRepository.recordMerchantCloudUndoInTransaction(
    bookId:String,originalPlanId:String,originalExecutionId:String,undoPlanId:String,
) {
    check(db.inTransaction())
    val history=db.merchantHistoryDao()
    for(original in history.cloudOperations(bookId,originalPlanId,originalExecutionId)) {
        val id=UUID.randomUUID().toString();val timestamp=clock.millis()
        val impacts=history.undoImpacts(bookId,original.id).map { it.copy(operationId=id,
            oldMerchantName=it.newMerchantName,newMerchantName=it.oldMerchantName,
            oldMerchantId=it.newMerchantId,newMerchantId=it.oldMerchantId) }
        val inverse=original.copy(id=id,timestamp=timestamp,type=MerchantOperationType.CLOUD_UNDO,
            sourceMerchantId=original.targetMerchantId,sourceMerchantName=original.targetMerchantName,
            targetMerchantId=original.sourceMerchantId,targetMerchantName=original.sourceMerchantName,
            sourceBefore=original.targetAfter,sourceAfter=original.targetBefore,
            targetBefore=original.sourceAfter,targetAfter=original.sourceBefore,
            counts=original.counts.copy(newlyLinkedRecords=impacts.count{it.objectType==MerchantObjectType.EVENT&&it.oldMerchantId==null&&it.newMerchantId!=null}),
            cloudPlanId=undoPlanId,cloudExecutionId=undoPlanId,cloudCommandId=null,
            undoOfOperationId=original.id,undoneByOperationId=null,undoneAt=null)
        writeMerchantOperationInTransaction(inverse,impacts,changed=true)
        check(history.markUndone(bookId,original.id,id,timestamp)==1)
    }
}
