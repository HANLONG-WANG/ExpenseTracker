package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class LedgerSearchTest {
    private val bank=AccountEntity("b","bank","銀行","BANK","JPY",0,"2026-10-01")
    private fun event(id:String,merchant:String="",note:String="")=EventEntity("b",id,"EXPENSE","bank",null,1280,"JPY",1280,"2026-10-10",merchant,note)
    private fun item(name:String?)=ReceiptItem("i-$name",listOf("img"),emptyList(),name=name,note="メモ商品")
    private fun document(receipt:String,vararg names:String?)=ReceiptItemDocumentEntity("b",receipt,receipt,
        FileProtocols.json.encodeToString(ReceiptItemsRecord("r",receipt,1,1,"JPY",listOf("img"),names.map(::item),emptyList(),true,emptyList())),1,0)
    private val s=BookSnapshot(BookEntity("b","生活","JPY","2026-10-01"),listOf(bank),emptyList(),listOf(event("shop","スーパー"),event("plain","コンビニ","牛乳")),emptyList(),null,
        receipts=listOf(ReceiptEntity("b","receipt","shop")))

    @Test fun productNamesMatchAcrossHalfWidthAndOnlyReportProductHits() {
        val products=s.receiptProductNames(listOf(document("receipt","ｺｰﾋｰ豆",null,"牛乳")))
        assertEquals(listOf("ｺｰﾋｰ豆","牛乳"),products["shop"])
        val shop=s.events[0];val plain=s.events[1]
        assertEquals(listOf("ｺｰﾋｰ豆"),s.ledgerSearch(shop,"コーヒー",null,products["shop"].orEmpty())?.products)
        assertNull(s.ledgerSearch(plain,"コーヒー",null,products["plain"].orEmpty()))
        // A note hit lists no products; item notes are not searched.
        assertEquals(emptyList<String>(),s.ledgerSearch(plain,"牛乳",null,emptyList())?.products)
        assertNull(s.ledgerSearch(shop,"メモ商品",null,products["shop"].orEmpty()))
        assertNotNull(s.ledgerSearch(shop,"１２８０",null,emptyList()))
    }
}
