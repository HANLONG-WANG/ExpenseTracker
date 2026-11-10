package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.MerchantEntity
import com.yohaku.expensetracker.domain.model.EntryKind

sealed interface MerchantResolution {
    data class Resolved(val merchant:MerchantEntity):MerchantResolution
    data object None:MerchantResolution
    data class Ambiguous(val merchants:List<MerchantEntity>):MerchantResolution
    data class Invalid(val reason:MerchantResolutionError):MerchantResolution
}

enum class MerchantResolutionError { INVALID_MERCHANT_ID, ID_NAME_MISMATCH, UNSUPPORTED_ENTRY_KIND, ORIGINAL_EXPENSE_REQUIRED }

/** Input from the original expense, never the refund's own display copy. */
data class OriginalExpenseMerchant(val bookId:String,val merchantId:String?,val name:String)

/**
 * Pure resolution over a transaction's merchant snapshot; disabled merchants remain eligible.
 * Null/blank names with an explicit ID mean "use the formal name". A nonblank name must match.
 * Local editors must clear a stale selection when editing its name; cloud callers can report Invalid.
 * Refund callers supply the original expense and must not persist an independent refund link.
 * This is infrastructure only until batch B switches the write paths to it.
 */
fun resolveMerchant(
    bookId:String,
    kind:EntryKind,
    explicitMerchantId:String?,
    finalName:String?,
    merchants:List<MerchantEntity>,
    originalExpense:OriginalExpenseMerchant?=null,
):MerchantResolution {
    if(kind==EntryKind.REFUND) {
        if(originalExpense==null||originalExpense.bookId!=bookId)
            return MerchantResolution.Invalid(MerchantResolutionError.ORIGINAL_EXPENSE_REQUIRED)
        // An existing valid original link is authoritative even if its old display copy is stale.
        return resolveMerchant(bookId,EntryKind.EXPENSE,originalExpense.merchantId,
            originalExpense.name.takeIf{originalExpense.merchantId==null},merchants)
    }
    if(explicitMerchantId!=null) {
        if(kind !in listOf(EntryKind.EXPENSE,EntryKind.INCOME))
            return MerchantResolution.Invalid(MerchantResolutionError.UNSUPPORTED_ENTRY_KIND)
        val merchant=merchants.firstOrNull{it.bookId==bookId&&it.id==explicitMerchantId}
            ?:return MerchantResolution.Invalid(MerchantResolutionError.INVALID_MERCHANT_ID)
        if(!finalName.isNullOrBlank()&&normalizedMerchantName(finalName)!=normalizedMerchantName(merchant.name))
            return MerchantResolution.Invalid(MerchantResolutionError.ID_NAME_MISMATCH)
        return MerchantResolution.Resolved(merchant)
    }
    if(kind!=EntryKind.EXPENSE||finalName.isNullOrBlank())return MerchantResolution.None
    val matches=merchants.filter{it.bookId==bookId&&normalizedMerchantName(it.name)==normalizedMerchantName(finalName)}
    return when(matches.size) {
        0->MerchantResolution.None
        1->MerchantResolution.Resolved(matches.single())
        else->MerchantResolution.Ambiguous(matches.sortedBy{it.id})
    }
}
