package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.Duration
import java.util.UUID

data class AccountBalanceChange(val account:AccountEntity,val before:Long,val after:Long)
data class BudgetBalanceChange(val before:Long,val after:Long)
data class EntryImpact(val month:String,val currency:Currency,val expenseBefore:Long,val expenseAfter:Long,val incomeBefore:Long,val incomeAfter:Long,val balances:List<AccountBalanceChange>){
    fun remainingBudget(currentMonth:String,budgetMinor:Long?):BudgetBalanceChange? =
        if(month==currentMonth&&budgetMinor!=null)BudgetBalanceChange(Math.subtractExact(budgetMinor,expenseBefore),Math.subtractExact(budgetMinor,expenseAfter)) else null
}

data class BookSnapshot(val book:BookEntity,val accounts:List<AccountEntity>,val categories:List<CategoryEntity>,val events:List<EventEntity>,val postings:List<PostingEntity>,val draft:EntryDraft?, val storedPostings:List<PostingEntity> = postings) {
    val visibleEvents get()=events.filter { it.deletedAt==null }
    fun balance(account:AccountEntity):Long=postings.filter{it.accountId==account.id}.fold(account.openingMinor){a,p->Math.addExact(a,p.deltaMinor)}
    fun removalImpact(eventId:String):EntryImpact {
        val event=visibleEvents.first{it.id==eventId}
        val month=event.date.take(7)
        val monthly=visibleEvents.filter{it.date.startsWith(month)}
        val expense=monthly.filter{it.kind==EntryKind.EXPENSE.name}.sumOf{it.baseMinor}
        val income=monthly.filter{it.kind==EntryKind.INCOME.name}.sumOf{it.baseMinor}
        val removed=postings.filter{it.eventId==event.id}.groupBy{it.accountId}
        return EntryImpact(month,Currency.valueOf(book.currency),expense,expense-if(event.kind==EntryKind.EXPENSE.name)event.baseMinor else 0,
            income,income-if(event.kind==EntryKind.INCOME.name)event.baseMinor else 0,
            removed.map{(id,p)->val a=accounts.first{it.id==id};val before=balance(a);AccountBalanceChange(a,before,before-p.sumOf{it.deltaMinor})})
    }
    fun restorationImpact(eventId:String):EntryImpact {
        val event=events.first{it.id==eventId&&it.deletedAt!=null}
        val month=event.date.take(7)
        val monthly=visibleEvents.filter{it.date.startsWith(month)}
        val expense=monthly.filter{it.kind==EntryKind.EXPENSE.name}.sumOf{it.baseMinor}
        val income=monthly.filter{it.kind==EntryKind.INCOME.name}.sumOf{it.baseMinor}
        val restored=storedPostings.filter{it.eventId==event.id&&it.confirmed}.groupBy{it.accountId}
        return EntryImpact(month,Currency.valueOf(book.currency),expense,expense+if(event.kind==EntryKind.EXPENSE.name)event.baseMinor else 0,
            income,income+if(event.kind==EntryKind.INCOME.name)event.baseMinor else 0,
            restored.map{(id,p)->val a=accounts.first{it.id==id};val before=balance(a);AccountBalanceChange(a,before,before+p.sumOf{it.deltaMinor})})
    }
    fun entryAccounts(kind:EntryKind)=accounts.filter{it.active&&(kind!=EntryKind.INCOME||!AccountType.valueOf(it.type).debt)}
    fun path(id:String?):String { val c=categories.find{it.id==id} ?: return ""; return c.parentId?.let{p->categories.find{it.id==p}?.name?.plus(" / ")} .orEmpty()+c.name }
}
class LedgerRepository(val db:YohakuDatabase, val clock:Clock=Clock.systemDefaultZone()) {
    val dao=db.dao()
    val json=Json { encodeDefaults=true }
    val books=dao.books()
    fun snapshot(book:BookEntity):Flow<BookSnapshot> = dao.graph(book.id).map { graph ->
        val g=graph ?: error("账本不存在 / 帳簿がありません")
        val liveIds=g.events.filter{it.deletedAt==null}.map{it.id}.toSet()
        BookSnapshot(g.book,g.accounts,g.categories,g.events.sortedWith(compareByDescending<EventEntity>{it.date}.thenByDescending{it.createdAt}.thenBy{it.id}),
            g.postings.filter{it.confirmed && it.eventId in liveIds},
            g.drafts.singleOrNull()?.let{json.decodeFromString<EntryDraft>(it.json)},g.postings)
    }
    fun id()=UUID.randomUUID().toString()
    suspend fun createBook(name:String,currency:Currency,theme:String="magazine",language:String="zh",startDate:String=LocalDate.now(clock).toString(),budgetMinor:Long?=null):String {
        require(name.isNotBlank()){ "请输入名称 / 名前を入力してください" }; LocalDate.parse(startDate);require(budgetMinor==null||budgetMinor>=0); val id=id()
        dao.putBook(BookEntity(id,name.trim(),currency.name,startDate,theme,language,budgetMinor=budgetMinor));return id
    }
    suspend fun updateBook(book:BookEntity) { require(book.name.isNotBlank()); db.withTransaction {
        val old=dao.book(book.id) ?: error("账本不存在 / 帳簿がありません")
        require(old.currency==book.currency || dao.eventCount(book.id)==0){"已有记录，本位币不可更改 / 記録済みの基準通貨は変更できません"}
        require(book.theme in listOf("magazine","journal","objects"));require(book.language in listOf("zh","ja"));dao.putBook(book)
    } }
    suspend fun updateAppearance(bookId:String,theme:String?=null,language:String?=null) { db.withTransaction {
        val old=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
        updateBook(old.copy(theme=theme?:old.theme,language=language?:old.language))
    } }
    suspend fun setupStep(bookId:String,step:Int) { db.withTransaction {
        require(step in 0..4)
        val old=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
        dao.putBook(old.copy(setupStep=step))
    } }
    suspend fun saveAccount(bookId:String,name:String,type:AccountType,currency:Currency,opening:Money,date:String,accountId:String?=null):String = db.withTransaction {
        require(name.isNotBlank()){ "请输入名称 / 名前を入力してください" };require(opening.currency==currency);LocalDate.parse(date)
        val old=accountId?.let{dao.account(bookId,it) ?: error("账户不存在 / 口座がありません")}
        require(old==null || old.type==type.name){"账户类型不可改写 / 口座の種類は変更できません"}
        require(old==null || old.currency==currency.name || !dao.hasAccountHistory(bookId,old.id)){"已有交易的账户币种不可更改 / 記録がある口座の通貨は変更できません"}
        val account=AccountEntity(bookId,old?.id?:id(),name.trim(),type.name,currency.name,opening.minor,date,old?.active?:true)
        dao.putAccount(account);account.id
    }
    suspend fun saveCategory(bookId:String,name:String,kind:EntryKind,parentId:String?,categoryId:String?=null,favorite:Boolean=false):String = db.withTransaction {
        require(name.isNotBlank()){ "请输入名称 / 名前を入力してください" };require(kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME))
        val parent=parentId?.let{dao.category(bookId,it) ?: error("父分类不存在 / 親カテゴリがありません")}
        require(parent==null || parent.parentId==null && parent.kind==kind.name){"最多两级且用途相同 / 同じ用途で2階層まで"}
        require(parentId!=categoryId || categoryId==null){"不能归属自己 / 自分を親にはできません"}
        if(categoryId!=null) {
            val old=dao.category(bookId,categoryId) ?: error("分类不存在 / カテゴリがありません")
            require(old.parentId==parentId && old.kind==kind.name){"已有分类层级调整需影响预览 / 階層変更には影響確認が必要です"}
        }
        val savedId=categoryId?:id()
        dao.putCategory(CategoryEntity(bookId,savedId,name.trim(),kind.name,parentId,favorite))
        savedId
    }
    suspend fun toggleCategoryFavorite(bookId:String,categoryId:String)=db.withTransaction {
        val category=dao.category(bookId,categoryId) ?: error("分类不存在 / カテゴリがありません")
        dao.putCategory(category.copy(favorite=!category.favorite))
    }
    suspend fun saveEntry(bookId:String,draft:EntryDraft):String=db.withTransaction {
        val b=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
        require(draft.kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME))
        val a=dao.account(bookId,draft.accountId ?: error("请选择账户 / 口座を選択してください")) ?: error("账户不属于本账本 / 別の帳簿の口座です")
        require(a.active)
        require(draft.kind!=EntryKind.INCOME||!AccountType.valueOf(a.type).debt){"收入请选择资金账户；还款或退款使用对应类型 / 収入は資金口座へ。返済・返金は専用の種類で記録してください"}
        val c=dao.category(bookId,draft.categoryId ?: error("请选择分类 / カテゴリを選択してください")) ?: error("分类不属于本账本 / 別の帳簿のカテゴリです")
        require(c.kind==draft.kind.name && c.active)
        val amount=Money.parse(draft.amount,Currency.valueOf(a.currency)); require(amount.minor>0){"金额应大于零 / 金額は0より大きくしてください"}
        val base=if(a.currency==b.currency)amount else Money.parse(draft.baseAmount,Currency.valueOf(b.currency))
        val date=LocalDate.parse(draft.date)
        require(!draft.historicalIncluded || date<=LocalDate.parse(a.openingDate)){"只有期初已包含的历史记录可使用此选项 / 期首以前の記録のみ指定できます"}
        val old=draft.id?.let{dao.event(bookId,it) ?: error("原记录不存在 / 元の記録がありません")}
        require(old?.deletedAt==null){"请先恢复记录 / 記録を復元してください"}
        val event=EventEntity(bookId,old?.id?:id(),draft.kind.name,a.id,c.id,amount.minor,a.currency,base.minor,date.toString(),draft.merchant.trim(),draft.note,draft.historicalIncluded,(old?.revision?:0)+1,createdAt=old?.createdAt ?: clock.millis())
        dao.putEvent(event);dao.clearPostings(bookId,event.id)
        if(!draft.historicalIncluded){
            val expenseSign=if(draft.kind==EntryKind.EXPENSE)-1 else 1
            val accountSign=if(AccountType.valueOf(a.type).debt)-expenseSign else expenseSign
            dao.putPostings(listOf(PostingEntity(bookId,id(),event.id,a.id,Math.multiplyExact(amount.minor,accountSign.toLong()),a.currency,event.date)))
        }
        dao.putRevision(RevisionEntity(bookId,id(),event.id,event.revision,clock.millis(),old?.let{json.encodeToString(it)},json.encodeToString(event)))
        dao.clearDraft(bookId);event.id
    }
    /** Foundation for transfers/borrowing/repayments. UI is implemented in subsequent phases. */
    suspend fun saveMovement(bookId:String,kind:EntryKind,fromId:String,toId:String,fromAmount:Money,toAmount:Money,date:String):String=db.withTransaction {
        require(kind in listOf(EntryKind.TRANSFER,EntryKind.REPAYMENT,EntryKind.BORROWING));require(fromId!=toId)
        val from=dao.account(bookId,fromId) ?: error("付款账户不存在")
        val to=dao.account(bookId,toId) ?: error("到账账户不存在")
        require(from.currency==fromAmount.currency.name && to.currency==toAmount.currency.name)
        require(fromAmount.minor>0 && toAmount.minor>0);LocalDate.parse(date)
        val fromDebt=AccountType.valueOf(from.type).debt;val toDebt=AccountType.valueOf(to.type).debt
        require(when(kind){EntryKind.TRANSFER->!fromDebt&&!toDebt;EntryKind.REPAYMENT->!fromDebt&&toDebt;else->fromDebt&&!toDebt})
        val event=EventEntity(bookId,id(),kind.name,fromId,null,fromAmount.minor,from.currency,0,date,"","",revision=1,createdAt=clock.millis())
        dao.putEvent(event)
        dao.putPostings(listOf(
            PostingEntity(bookId,id(),event.id,fromId,if(fromDebt)fromAmount.minor else -fromAmount.minor,from.currency,date),
            PostingEntity(bookId,id(),event.id,toId,if(toDebt)-toAmount.minor else toAmount.minor,to.currency,date)))
        dao.putRevision(RevisionEntity(bookId,id(),event.id,1,clock.millis(),null,json.encodeToString(event)));event.id
    }
    suspend fun saveDraft(bookId:String,draft:EntryDraft) { dao.putDraft(DraftEntity(bookId,json.encodeToString(draft),clock.millis())) }
    suspend fun clearDraft(bookId:String)=dao.clearDraft(bookId)
    suspend fun deleteEntry(bookId:String,id:String) { db.withTransaction {
        val old=dao.event(bookId,id) ?: error("记录不存在 / 記録がありません")
        if(old.deletedAt==null) dao.putEvent(old.copy(deletedAt=clock.millis()))
    } }
    suspend fun restoreEntry(bookId:String,id:String) { db.withTransaction {
        val old=dao.event(bookId,id) ?: error("记录不存在 / 記録がありません")
        require(old.deletedAt!=null && old.deletedAt>clock.millis()-Duration.ofDays(30).toMillis()){ "记录已超过恢复期限 / 復元期限を過ぎました" }
        dao.putEvent(old.copy(deletedAt=null))
    } }
    suspend fun purgeExpired()=dao.purgeUnlinked(clock.millis()-Duration.ofDays(30).toMillis())
}
