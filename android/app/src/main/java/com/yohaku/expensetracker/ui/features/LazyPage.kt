package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.accountEventPostings
import com.yohaku.expensetracker.data.repository.netExpense
import com.yohaku.expensetracker.data.repository.dailyExpense
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.theme.LocalYohaku

/** App header, edition mark and back bar that every page shows above its own content. */
internal class PageChrome(val head:@Composable ()->Unit={},val top:@Composable ()->Unit={},val foot:@Composable ()->Unit={})

/**
 * Page for record lists that can grow to thousands of rows: only rows on screen are composed.
 * Same gutters and 15dp section rhythm as the regular scrolling page.
 */
@Composable internal fun LazyPage(chrome:PageChrome,key:Any,modifier:Modifier=Modifier,top:@Composable ()->Unit,items:LazyListScope.()->Unit) {
    val state=rememberSaveable(key,saver=LazyListState.Saver){LazyListState()}
    LazyColumn(modifier.fillMaxWidth().testTag("page-scroll"),state) {
        item("page-head"){chrome.head()}
        item("page-top"){Column(Modifier.fillMaxWidth().padding(pageContentPadding()),verticalArrangement=Arrangement.spacedBy(15.dp)){chrome.top();top()}}
        items()
        item("page-foot"){Box(Modifier.padding(pageContentPadding()).padding(top=15.dp,bottom=28.dp)){chrome.foot()}}
    }
}

/** Rows of a [TransactionList] prepared once per snapshot and filter. */
internal class TransactionRows(val days:List<Pair<String,List<EventEntity>>>,val rows:List<EventEntity>,val spent:Map<String,Long>,val byDay:Boolean)
internal fun BookSnapshot.transactionRowDate(event:EventEntity,accountId:String?)=
    accountId?.let{accountEventPostings(event,it).maxOfOrNull{p->p.date}}?:event.date

@Composable internal fun rememberTransactionRows(s:BookSnapshot,entries:List<EventEntity>,accountId:String?,byDay:Boolean)=remember(s,entries,accountId,byDay) {
    transactionRows(s,entries,accountId,byDay)
}
internal fun transactionRows(s:BookSnapshot,entries:List<EventEntity>,accountId:String?,byDay:Boolean):TransactionRows {
    fun date(e:EventEntity)=s.transactionRowDate(e,accountId)
    val ordered=if(accountId==null)entries else entries.sortedByDescending{date(it)}

    val groups=if(byDay)ordered.groupBy{date(it)}else emptyMap()
    val spent=when {
        !byDay->emptyMap()
        accountId==null->s.dailyExpense(entries)
        else->{
            val fees=s.expenseComponents.filter{it.confirmed&&it.accountId==accountId}.groupBy{it.eventId}
            groups.mapValues{(_,events)->events.sumOf{e->
                (if(e.accountId==accountId)s.netExpense(e) else 0L)+fees[e.id].orEmpty().sumOf{it.baseMinor}
            }}
        }
    }
    // A confirmed fee can have its own date without a transaction starting on that day.
    val days=(groups.keys+spent.keys).sortedDescending().map{it to groups[it].orEmpty()}
    return TransactionRows(days,ordered,spent,byDay)
}

/** Lazy counterpart of [TransactionList]: one item per day (or per row), each carrying the list's own spacing. */
internal fun LazyListScope.transactionItems(s:BookSnapshot,list:TransactionRows,onSelect:(EventEntity)->Unit,emptyContent:@Composable ()->Unit,accountId:String?=null,gapBefore:Boolean=false,startGap:Dp=0.dp,matchedProducts:Map<String,List<String>> = emptyMap()) {
    item("transaction-list-start") {
        val t=LocalYohaku.current
        // Objects cards keep an 8dp gap from the controls above; it sits outside the list itself.
        val gap=if(t.id=="objects"&&gapBefore&&list.rows.isNotEmpty())8.dp else 0.dp
        Column(Modifier.fillMaxWidth().padding(pageContentPadding()).padding(top=startGap+gap).testTag("transaction-list-start")) {
            if(t.id!="objects"&&(!list.byDay||list.rows.isEmpty()))Box(Modifier.fillMaxWidth().height(1.dp).background(if(t.id=="magazine")t.ink else t.line))
        }
    }
    if(list.byDay)list.days.forEachIndexed{index,(day,events)->item("transaction-list-day-$day") {
        val objects=LocalYohaku.current.id=="objects"
        Column(Modifier.fillMaxWidth().padding(pageContentPadding()).padding(top=if(objects&&index>0)8.dp else 0.dp).testTag("transaction-list-day-$day"),verticalArrangement=Arrangement.spacedBy(if(objects)8.dp else 0.dp)) {
            LedgerDayHeader(day,Money(Currency.valueOf(s.book.currency),list.spent[day]?:0L))
            events.forEach{EventRow(s,it,{onSelect(it)},accountId,showDate=false,last=it===events.last(),matchedProducts=matchedProducts[it.id].orEmpty())}
        }
    }}
    else list.rows.forEachIndexed{index,e->item("transaction-list-row-${e.id}") {
        val objects=LocalYohaku.current.id=="objects"
        Box(Modifier.fillMaxWidth().padding(pageContentPadding()).padding(top=if(objects&&index>0)8.dp else 0.dp).testTag("transaction-list-row-${e.id}")) {
            EventRow(s,e,{onSelect(e)},accountId,last=index==list.rows.lastIndex,matchedProducts=matchedProducts[e.id].orEmpty())
        }
    }}
    if(list.rows.isEmpty())item("transaction-list-empty"){Box(Modifier.fillMaxWidth().padding(pageContentPadding()).testTag("transaction-list-empty")){emptyContent()}}
}
