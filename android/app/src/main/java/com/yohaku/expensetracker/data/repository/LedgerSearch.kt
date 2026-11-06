package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.db.PostingEntity
import com.yohaku.expensetracker.data.db.ReceiptItemDocumentEntity
import com.yohaku.expensetracker.data.files.FileProtocols
import com.yohaku.expensetracker.data.files.ReceiptItemsRecord
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import java.text.Normalizer
import java.util.Locale

/** NFKC folding lets half-width receipt katakana and full-width digits match what people type. */
fun normalizedSearchText(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

/** Named receipt products per entry; unnamed lines carry nothing to search. */
fun BookSnapshot.receiptProductNames(documents:List<ReceiptItemDocumentEntity>):Map<String,List<String>> {
    val eventByReceipt=receipts.associate{it.id to it.eventId}
    return documents.mapNotNull{document->
        val eventId=eventByReceipt[document.receiptId]?:return@mapNotNull null
        val names=FileProtocols.json.decodeFromString<ReceiptItemsRecord>(document.recordJson).items.mapNotNull{it.name?.takeIf(String::isNotBlank)}
        names.takeIf{it.isNotEmpty()}?.let{eventId to it}
    }.toMap()
}

/** Products are reported separately so a row matched only through its receipt can say why. */
data class LedgerSearchMatch(val products:List<String>)

/**
 * Searches merchant, note, amounts and receipt product names. [sides] are the filtered account's postings,
 * whose amounts are searchable too. Returns null when the entry does not match.
 */
fun BookSnapshot.ledgerSearch(e:EventEntity,query:String,sides:List<PostingEntity>?,products:List<String>):LedgerSearchMatch? {
    val q=normalizedSearchText(query)
    if(q.isEmpty())return LedgerSearchMatch(emptyList())
    val amounts=(listOf(accountMoney(e),Money(Currency.valueOf(e.currency),e.amountMinor))+sides.orEmpty().map{Money(Currency.valueOf(it.currency),if(it.deltaMinor<0)Math.negateExact(it.deltaMinor)else it.deltaMinor)})
        .flatMap{listOf(it.display(),it.decimal.toPlainString())}
    val matchedProducts=products.filter{normalizedSearchText(it).contains(q)}
    return if(matchedProducts.isNotEmpty()||(listOf(e.merchant,e.note)+amounts).any{normalizedSearchText(it).contains(q)})LedgerSearchMatch(matchedProducts)else null
}
