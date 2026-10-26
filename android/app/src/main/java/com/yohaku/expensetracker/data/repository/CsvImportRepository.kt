package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.csv.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.time.LocalDate

/** File-internal equal rows deliberately remain separate; the index contains only pre-existing events. */
data class CsvPreviewRow(val source:CsvValue,val date:String?,val minor:Long?,val accountId:String?,val categoryId:String?,val newCategory:String?,
    val historical:Boolean,val duplicate:Boolean,val skipped:Boolean,val issues:List<String>)
data class CsvPreview(val rows:List<CsvPreviewRow>) {
    val selected get()=rows.filterNot{it.skipped}
    val canImport get()=selected.isNotEmpty()&&selected.all{it.issues.isEmpty()}
}
fun csvAccountAllowed(account:AccountEntity,book:BookEntity)=account.active&&account.currency==book.currency
fun csvCategoryChoice(s:BookSnapshot,kind:String,value:String):String = s.categories.filter{it.kind==kind&&it.name==value&&s.categoryEnabled(it.id)}.singleOrNull()?.id?:"NEW"
fun previewCsv(s:BookSnapshot,document:CsvDocument,settings:CsvSettings,edits:Map<Int,CsvEdit>):CsvPreview {
    require(settings.columns["date"]!=null&&settings.columns["amount"]!=null){"请选择日期与金额列 / 日付・金額の列を選択してください"}
    require(s.accounts.any{it.id==settings.accountId&&csvAccountAllowed(it,s.book)}){"请选择本位币的目标账户 / 基準通貨の移行先口座を選択してください"}
    val existing=s.events.filter{it.deletedAt==null&&it.purgedAt==null&&it.currency==s.book.currency}.map{CsvParser.duplicateKey(it.date,it.kind,it.amountMinor,it.note,it.merchant)}.toHashSet()
    val accounts=s.accounts.associateBy{it.id};val categories=s.categories.associateBy{it.id}
    return CsvPreview(CsvParser.values(document,settings,edits).map{source->
        val issues=mutableListOf<String>()
        val date=runCatching{CsvParser.date(source.date,settings.dateFormat).toString()}.getOrElse{issues+="日期无法解析 / 日付を解析できません";null}
        val amount=runCatching{CsvParser.minor(source.amount,Currency.valueOf(s.book.currency).scale)}.getOrElse{issues+=it.message?.takeIf{m->" / " in m}?:"金额无法解析 / 金額を解析できません";null}
        val kind=source.kind
        if(kind !in listOf("EXPENSE","INCOME","SKIP"))issues+="请选择收支类型对应 / 収支の対応を選択してください"
        val account=accounts[settings.accounts[source.account]?.takeIf{it.isNotBlank()}?:settings.accountId]
        if(account==null||!csvAccountAllowed(account,s.book))issues+="请选择本位币的启用账户 / 基準通貨の有効な口座を選択してください"
        if(kind=="INCOME"&&account!=null&&AccountType.valueOf(account.type).debt)issues+="收入只能迁入资金账户 / 収入は資金口座へ移行してください"
        val choice=if(source.category.isBlank())settings.defaults[kind] else settings.categories[CsvParser.categoryKey(kind.orEmpty(),source.category)]?:csvCategoryChoice(s,kind.orEmpty(),source.category)
        val newName=when{choice=="NEW"->source.category;choice?.startsWith("NEW:")==true->choice.removePrefix("NEW:").trim();else->null}
        val category=categories[choice]
        if(newName!=null) {if(newName.isBlank())issues+="请输入新分类名称 / 新しいカテゴリ名を入力してください"}
        else if(category==null||category.kind!=kind||!s.categoryEnabled(category.id))issues+="请选择有效分类；空分类需要默认分类 / 有効なカテゴリを選択してください。空欄には既定カテゴリが必要です"
        val duplicate=date!=null&&amount!=null&&CsvParser.duplicateKey(date,kind.orEmpty(),amount,source.note,source.merchant) in existing
        CsvPreviewRow(source,date,amount,account?.id,category?.id,newName,date!=null&&account!=null&&date<=account.openingDate,duplicate,
            kind=="SKIP"||(edits[source.row]?.skip?:duplicate),issues)
    })
}

suspend fun LedgerRepository.csvPreview(bookId:String,document:CsvDocument,settings:CsvSettings,edits:Map<Int,CsvEdit>):CsvPreview {
    val book=requireNotNull(dao.book(bookId))
    return previewCsv(snapshot(book).first(),document,settings,edits)
}

suspend fun LedgerRepository.importCsv(bookId:String,fileName:String,sha256:String,document:CsvDocument,settings:CsvSettings,edits:Map<Int,CsvEdit>,review:CsvPreview,onProgress:(Int,Int)->Unit={_,_->}):CsvBatchEntity=db.withTransaction {
    val current=csvPreview(bookId,document,settings,edits)
    require(current==review){"账目或对应发生变化，请重新预览 / 記録または対応が変わりました。再プレビューしてください"}
    require(current.canImport){"请修正或跳过问题行，并至少选择一行 / 問題行を修正・スキップし、1行以上選択してください"}
    require(dao.account(bookId,settings.accountId)?.let{csvAccountAllowed(it,requireNotNull(dao.book(bookId)))}==true){"请选择目标账户 / 移行先口座を選択してください"}
    val selected=current.selected
    val currency=Currency.valueOf(requireNotNull(dao.book(bookId)).currency)
    val newCategories=mutableMapOf<Pair<String,String>,String>()
    val batch=CsvBatchEntity(bookId,id(),fileName,sha256,clock.millis(),document.data.size,current.selected.size,current.rows.count{it.skipped},settings.accountId)
    db.csvImportDao().putBatch(batch)
    val links=mutableListOf<CsvBatchEventEntity>()
    val accountsToRefresh=linkedMapOf<String,PreparedLedgerChange>()
    selected.forEachIndexed{index,row->
        val kind=EntryKind.valueOf(row.source.kind!!)
        val key=kind.name to row.newCategory.orEmpty()
        val category=row.categoryId?:newCategories[key]?:saveCategory(bookId,row.newCategory!!,kind,null).also{newCategories[key]=it}
        val amount=java.math.BigDecimal.valueOf(row.minor!!,currency.scale).toPlainString()
        val draft=EntryDraft(kind=kind,accountId=row.accountId,categoryId=category,date=row.date!!,amount=amount,originalCurrency=currency,
            merchant=row.source.merchant,note=row.source.note,historicalIncluded=row.historical)
        // Same validation + complete event/settlement/postings/revision writer as manual entry;
        // do not clear the user's unrelated draft or repeatedly derive whole-book finance views.
        val change=prepareEntry(bookId,draft)
        val eventId=writeChange(change,refreshFinance=false)
        accountsToRefresh[change.event.accountId]=change
        links+=CsvBatchEventEntity(bookId,eventId,batch.id)
        if(index%50==0||index==selected.lastIndex)onProgress(index+1,selected.size)
    }
    accountsToRefresh.values.forEach{refreshFinancialPools(it)}
    db.csvImportDao().putEvents(links)
    val resolvedCategories=settings.categories.toMutableMap();val resolvedDefaults=settings.defaults.toMutableMap()
    selected.forEach{row->
        val category=row.categoryId?:newCategories[row.source.kind to row.newCategory]!!
        if(row.source.category.isBlank())resolvedDefaults[row.source.kind!!]=category
        else resolvedCategories[CsvParser.categoryKey(row.source.kind!!,row.source.category)]=category
    }
    db.csvImportDao().putProfile(CsvProfileEntity(bookId,document.signature,json.encodeToString(settings.copy(categories=resolvedCategories,defaults=resolvedDefaults))))
    batch
}

suspend fun LedgerRepository.undoCsvBatch(bookId:String,batchId:String)=db.withTransaction {
    val batch=requireNotNull(db.csvImportDao().batch(bookId,batchId))
    val s=snapshot(requireNotNull(dao.book(bookId))).first()
    val selected=db.csvImportDao().events(bookId,batchId).filter{it.deletedAt==null}
    require(selected.isNotEmpty()){"本批次没有可撤销的记录 / このバッチに取り消せる記録はありません"}
    // Follow the same refund linkage as deleting a purchase from its detail page.
    val events=(selected+selected.flatMap{s.linkedLifecycleRefunds(it,false)}).distinctBy{it.id}
    val group=trashEntrySet(bookId,events)
    db.csvImportDao().putBatch(batch.copy(undoGroupId=group))
}
