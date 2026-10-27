package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class AnalysisFilter(val start:String,val end:String,val categoryId:String?=null,val merchantKey:String?=null,val activityId:String?=null)
data class AnalysisFact(val event:EventEntity,val date:String,val categoryId:String?,val merchantKey:String,val activityId:String?,val gross:Long,val refunds:Long,val fee:Long) {val net get()=Math.addExact(Math.subtractExact(gross,refunds),fee)}
data class AnalysisGroup(val key:String,val amount:Long,val count:Int)
data class AnalysisReport(val filter:AnalysisFilter,val facts:List<AnalysisFact>) {
    val gross get()=facts.fold(0L){sum,f->Math.addExact(sum,f.gross)}
    val refunds get()=facts.fold(0L){sum,f->Math.addExact(sum,f.refunds)}
    val fees get()=facts.fold(0L){sum,f->Math.addExact(sum,f.fee)}
    val total get()=Math.addExact(Math.subtractExact(gross,refunds),fees)
    val events get()=facts.map{it.event}.distinctBy{it.id}
    fun groups(key:(AnalysisFact)->String)=facts.groupBy(key).map{(id,values)->AnalysisGroup(id,values.fold(0L){s,f->Math.addExact(s,f.net)},values.map{it.event.id}.distinct().size)}.sortedByDescending{it.amount}
}
fun BookSnapshot.merchantKey(event:EventEntity):String {
    merchantByEvent[event.id]?.let{return "id:"+it}
    val matching=merchantsByName[event.merchant]?.takeIf{event.merchant.isNotBlank()}?.singleOrNull()
    return matching?.let{"id:"+it.id}?:"name:"+event.merchant
}
fun BookSnapshot.merchantLabel(key:String):String=if(key.startsWith("id:"))merchants.first{it.id==key.removePrefix("id:")}.name else key.removePrefix("name:")
fun BookSnapshot.analysis(filter:AnalysisFilter):AnalysisReport {
    require(LocalDate.parse(filter.start)<=LocalDate.parse(filter.end)){"开始日期不能晚于结束日期 / 開始日は終了日以前にしてください"}
    val ids=filter.categoryId?.let{id->setOf(id)+categories.filter{it.parentId==id}.map{it.id}}
    fun activity(e:EventEntity)=currentActivityId(e.id)
    val period=filter.start..filter.end
    // Date and category narrow the records before the per-record merchant and refund work.
    val expenses=visibleEvents.filter{it.kind==EntryKind.EXPENSE.name&&it.date in period&&(ids==null||it.categoryId in ids)}.map{e->AnalysisFact(e,e.date,e.categoryId,merchantKey(e),activity(e),e.baseMinor,Math.subtractExact(e.baseMinor,netExpense(e)),0)}
    val fees=if(ids!=null)emptyList() else visibleComponents().filter{it.date in period}.map{c->val e=visibleById.getValue(c.eventId);AnalysisFact(e,c.date,null,merchantKey(e),activity(e),0,0,c.baseMinor)}
    val selected=(expenses+fees).filter{f->(filter.merchantKey==null||f.merchantKey==filter.merchantKey)&&(filter.activityId==null||f.activityId.orEmpty()==filter.activityId)}
    return AnalysisReport(filter,selected)
}
fun BookSnapshot.categoryGroups(report:AnalysisReport)=report.groups{f->f.categoryId?.let{id->categoryById.getValue(id).parentId?:id}?:"FEES"}
fun BookSnapshot.categoryChildren(report:AnalysisReport,parentId:String)=report.facts.filter{f->f.categoryId==parentId||categories.any{it.id==f.categoryId&&it.parentId==parentId}}.groupBy{if(it.categoryId==parentId)"UNSPECIFIED"else it.categoryId!!}.map{(id,values)->AnalysisGroup(id,values.fold(0L){s,f->Math.addExact(s,f.net)},values.size)}.sortedByDescending{it.amount}
fun BookSnapshot.confirmedRefunds(report:AnalysisReport):List<EventEntity> {
    val ids=report.facts.filter{it.gross>0}.map{it.event.id}.toSet()
    return visibleEvents.filter{e->settlements.any{it.eventId==e.id&&it.confirmed&&it.originalEventId in ids}}
}
data class AnalysisComparison(val current:AnalysisReport,val previous:AnalysisReport,val previousStart:String,val previousEnd:String,val partialMonth:Boolean) {val change get()=Math.subtractExact(current.total,previous.total)}
fun BookSnapshot.compareAnalysis(filter:AnalysisFilter,today:LocalDate):AnalysisComparison {
    val start=LocalDate.parse(filter.start);val end=LocalDate.parse(filter.end);val month=YearMonth.from(start)
    val singleMonth=YearMonth.from(end)==month
    val partial=singleMonth&&month==YearMonth.from(today)&&start.dayOfMonth==1&&end>=today
    val currentEnd=if(partial)today else end
    val previousStart:LocalDate;val previousEnd:LocalDate
    if(singleMonth&&start.dayOfMonth==1&&(partial||end==month.atEndOfMonth())) {
        val previousMonth=month.minusMonths(1);previousStart=previousMonth.atDay(1)
        previousEnd=if(partial)previousMonth.atDay(minOf(today.dayOfMonth,previousMonth.lengthOfMonth()))else previousMonth.atEndOfMonth()
    }else {val days=ChronoUnit.DAYS.between(start,end)+1;previousEnd=start.minusDays(1);previousStart=previousEnd.minusDays(days-1)}
    return AnalysisComparison(analysis(filter.copy(end=currentEnd.toString())),analysis(filter.copy(start=previousStart.toString(),end=previousEnd.toString())),previousStart.toString(),previousEnd.toString(),partial)
}
data class AnalysisCause(val key:String,val before:Long,val after:Long) {val change get()=Math.subtractExact(after,before)}
fun analysisCauses(current:List<AnalysisGroup>,previous:List<AnalysisGroup>):List<AnalysisCause> = (current.map{it.key}+previous.map{it.key}).distinct().map{key->AnalysisCause(key,previous.find{it.key==key}?.amount?:0,current.find{it.key==key}?.amount?:0)}.filter{it.change!=0L}.sortedByDescending{kotlin.math.abs(it.change)}
