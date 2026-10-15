package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth

data class BudgetChangeReview(val snapshot:BookSnapshot,val setting:BudgetEntity,val before:BudgetProjection,val after:BudgetProjection,val bookUpdate:BookEntity?=null)

suspend fun LedgerRepository.previewBudget(bookId:String,month:String,categoryId:String?,amount:Money?):BudgetChangeReview=db.withTransaction {
    YearMonth.parse(month)
    val book=dao.book(bookId)?:error("账本不存在 / 帳簿がありません")
    require(amount==null||amount.currency.name==book.currency&&amount.minor>=0){"本位币或金额不一致，请重新打开预算表单 / 基準通貨・金額が変わりました。予算フォームを開き直してください"}
    if(categoryId!=null)require(dao.category(bookId,categoryId)?.let{it.kind==EntryKind.EXPENSE.name&&it.active}==true)
    val setting=BudgetEntity(bookId,month,categoryId.orEmpty(),categoryId,amount?.minor)
    val snapshot=snapshot(book).first()
    val next=snapshot.copy(budgets=snapshot.budgets.filter{it.month!=month||it.scope!=setting.scope}+setting)
    BudgetChangeReview(snapshot,setting,snapshot.budgetProjection(month,categoryId),next.budgetProjection(month,categoryId))
}
suspend fun LedgerRepository.commitBudget(review:BudgetChangeReview):Boolean=db.withTransaction {
    val book=dao.book(review.setting.bookId)?:error("账本不存在 / 帳簿がありません")
    if(snapshot(book).first().reviewState()!=review.snapshot.reviewState())return@withTransaction false
    review.bookUpdate?.let{updateBook(it)}
    dao.putBudget(review.setting);true
}
suspend fun LedgerRepository.setActivityMode(bookId:String,enabled:Boolean,activeId:String?=null)=db.withTransaction {
    require(dao.book(bookId)!=null)
    if(activeId!=null)require(enabled&&dao.activity(bookId,activeId)?.ended==false){"已结束活动不能进入记录模式 / 終了済みシーンは記録モードにできません"}
    dao.putFeatures(BookFeaturesEntity(bookId,enabled,if(enabled)activeId else null))
}
/** Activity foundations needed by the shared monthly budget projection; full activity UI is P11. */
suspend fun LedgerRepository.saveActivity(bookId:String,name:String,start:String,end:String,budget:Money?,excludeMonthly:Boolean=false,id:String?=null):String=db.withTransaction {
    val book=dao.book(bookId)?:error("账本不存在 / 帳簿がありません")
    require(dao.features(bookId)?.activityMode==true){"请先开启本账本活动模式 / この帳簿のシーンモードを有効にしてください"}
    require(name.isNotBlank());require(LocalDate.parse(end)>=LocalDate.parse(start))
    require(budget==null||budget.currency.name==book.currency&&budget.minor>=0)
    val old=id?.let{dao.activity(bookId,it)?:error("活动不属于本账本 / 別の帳簿のシーンです")}
    val activity=ActivityEntity(bookId,old?.id?:this.id(),name.trim(),start,end,budget?.minor,excludeMonthly,old?.ended?:false)
    dao.putActivity(activity);activity.id
}

/** Book-info budget edits use the same monthly source and confirmation transaction. */
suspend fun LedgerRepository.previewBookBudget(updated:BookEntity,month:String,amount:Money?):BudgetChangeReview=db.withTransaction {
    YearMonth.parse(month)
    val old=dao.book(updated.id)?:error("账本不存在 / 帳簿がありません")
    require(updated.name.isNotBlank()){ "请输入名称 / 名前を入力してください" }
    if(old.currency!=updated.currency) {
        require(dao.eventCount(old.id)==0&&dao.recurrences(old.id).isEmpty()&&dao.activityCount(old.id)==0&&(!old.initialized||dao.budgets(old.id).none{it.amountMinor!=null})){ "已有账务或预算资料，本位币不可直接改写 / 記録・予算情報の基準通貨は直接変更できません" }
    }
    require(amount==null||amount.currency.name==updated.currency&&amount.minor>=0)
    val snapshot=snapshot(old).first()
    val setting=BudgetEntity(old.id,month,"",null,amount?.minor)
    val next=snapshot.copy(book=updated,budgets=snapshot.budgets.filter{it.month!=month||it.scope!=""}+setting)
    BudgetChangeReview(snapshot,setting,snapshot.budgetProjection(month),next.budgetProjection(month),updated)
}
