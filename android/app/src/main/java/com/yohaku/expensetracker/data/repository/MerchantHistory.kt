package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.MerchantOperationEntity
import com.yohaku.expensetracker.data.db.MerchantOperationImpactEntity

/**
 * Append only after actual business writes, inside their existing transaction. `changed` is the
 * caller's actual mutation result, not the preview count (profile-only changes also count).
 * No-op returns false; preview must never call this. Do not catch insert failures in the caller:
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
