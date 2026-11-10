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

/** Invalid explicit references are errors, while unmatched/ambiguous text remains unlinked. */
fun MerchantResolution.merchantOrThrow():MerchantEntity?=when(this) {
    is MerchantResolution.Resolved->merchant
    is MerchantResolution.None,is MerchantResolution.Ambiguous->null
    is MerchantResolution.Invalid->error(when(reason) {
        MerchantResolutionError.ID_NAME_MISMATCH->"商户 ID 与名称不一致 / お店の ID と名前が一致しません"
        MerchantResolutionError.INVALID_MERCHANT_ID->"商户不存在或不属于本账本 / お店が存在しないか別の帳簿のお店です"
        MerchantResolutionError.UNSUPPORTED_ENTRY_KIND->"此记录类型不支持独立商户关联 / この記録種類はお店を直接関連付けられません"
        MerchantResolutionError.ORIGINAL_EXPENSE_REQUIRED->"退款必须关联原消费 / 返金には元の支出が必要です"
    })
}

/** Local selections are authoritative; editors clear the ID when the user changes text.
 * Cloud adapters validate explicitly supplied ID + text together before calling repositories. */
internal fun resolveSavedMerchant(bookId:String,kind:EntryKind,id:String?,name:String,merchants:List<MerchantEntity>)=
    resolveMerchant(bookId,kind,id,name.takeIf{id==null},merchants).merchantOrThrow()
