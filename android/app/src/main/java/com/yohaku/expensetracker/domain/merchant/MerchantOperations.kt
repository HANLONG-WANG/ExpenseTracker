package com.yohaku.expensetracker.domain.merchant

import java.security.MessageDigest

// Names are stored in Room history; keep existing values stable when adding future operations.
enum class MerchantOperationType {
    CREATE, CREATE_FROM_ORIGINAL_NAME, ASSIGN_ORIGINAL_NAME, RENAME, MERGE,
    CHANGE_NOTE, CHANGE_ACTIVE, DELETE, HISTORY_CLEANUP, CLOUD_UNDO
}
enum class MerchantOperationSource { LOCAL, CLOUD, UPGRADE }
enum class MerchantObjectType { MERCHANT, EVENT, REFUND, TEMPLATE, RECURRENCE, SAVED_LOCATION, PENDING_PLACE, DRAFT }

data class MerchantProfile(val name:String,val note:String,val active:Boolean)

/**
 * Counts are distinct objects per category, not additive totals. affectedRecords includes link
 * removal/reassignment without a name change (deletion and same-name merges). New links/name changes include
 * recoverable non-refund records; trashedRecords is their affected trash subset. Refunds have
 * their own count (including recoverable refunds). Templates, rules and places count separately.
 */
data class MerchantImpactCounts(
    val affectedRecords:Int=0,
    val newlyLinkedRecords:Int=0,
    val renamedRecords:Int=0,
    val trashedRecords:Int=0,
    val linkedRefunds:Int=0,
    val templates:Int=0,
    val recurrenceRules:Int=0,
    val savedLocations:Int=0,
    val pendingPlaces:Int=0,
    val drafts:Int=0,
)

/** One complete, operation-relevant state. Null state means the object does not exist. */
data class MerchantObjectState(val type:MerchantObjectType,val id:String,val fields:Map<String,String?>?)
data class MerchantObjectChange(val before:MerchantObjectState,val after:MerchantObjectState)

/**
 * Dependencies include unchanged source/target profiles, all candidates in old/new name groups,
 * original/refund links, trash state and location data used by the scope calculation. Recompute
 * the entire scope at commit, including additions/removals. Values must use stable encodings.
 * Intent includes names, source/target IDs and location keep/edit/discard choices; never UI order.
 */
data class MerchantPreviewBasis(
    val bookId:String,
    val operation:MerchantOperationType,
    val intent:Map<String,String?>,
    val dependencies:List<MerchantObjectState>,
    val changes:List<MerchantObjectChange>,
)

@JvmInline value class MerchantImpactFingerprint(val sha256:String)
data class MerchantOperationPreview(
    val basis:MerchantPreviewBasis,
    val counts:MerchantImpactCounts,
    val duplicateLocationIds:List<String> = emptyList(),
) {
    val fingerprint:MerchantImpactFingerprint get()=merchantImpactFingerprint(basis)
}

/** Length-prefixed UTF-8 encoding distinguishes null, empty, separators and field boundaries. */
fun merchantImpactFingerprint(basis:MerchantPreviewBasis):MerchantImpactFingerprint {
    val digest=MessageDigest.getInstance("SHA-256")
    fun value(value:String?) {
        if(value==null) { digest.update("-1:".toByteArray(Charsets.UTF_8));return }
        val bytes=value.toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8));digest.update(bytes)
    }
    fun fields(fields:Map<String,String?>?) {
        value(fields?.size?.toString())
        fields?.toSortedMap()?.forEach{(key,v)->value(key);value(v)}
    }
    fun state(state:MerchantObjectState) { value(state.type.name);value(state.id);fields(state.fields) }
    value("merchant-impact-v1");value(basis.bookId);value(basis.operation.name);fields(basis.intent)
    value(basis.dependencies.size.toString())
    basis.dependencies.sortedWith(compareBy({it.type.name},{it.id})).forEach(::state)
    value(basis.changes.size.toString())
    basis.changes.sortedWith(compareBy({it.before.type.name},{it.before.id})).forEach{state(it.before);state(it.after)}
    return MerchantImpactFingerprint(digest.digest().joinToString(""){"%02x".format(it)})
}

/** Call after recalculating in the same transaction as the proposed writes. */
fun requireCurrentMerchantPreview(expected:MerchantImpactFingerprint,recomputed:MerchantOperationPreview) {
    check(expected==recomputed.fingerprint){"商户影响范围已变化，请刷新预览 / 影響範囲が変わりました。プレビューを更新してください"}
}
