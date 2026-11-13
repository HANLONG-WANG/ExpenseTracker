package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** Fixed presets cannot carry payment months or fees that must be checked for each purchase. */
internal fun fixedCardPayment(account:AccountEntity,choice:String?,periods:Int?):Pair<String?,Int?> {
    if(account.type!=AccountType.CREDIT.name)return null to null
    require(choice==null||CardPaymentChoice.entries.any{it.name==choice&&it!=CardPaymentChoice.DEFERRED}){"此付款方式需逐笔填写，请在记账表单中设置 / この支払方法は記録ごとにフォームで設定してください"}
    require(choice!="INSTALLMENT"||periods!=null&&periods>0){"分期次数须大于0 / 分割回数は0より大きくしてください"}
    return choice to periods.takeIf{choice=="INSTALLMENT"}
}

fun BookSnapshot.occurrenceDraft(occurrence:OccurrenceEntity,rule:RecurrenceEntity):EntryDraft {
    val draft=EntryDraft(kind=EntryKind.EXPENSE,date=occurrence.scheduledDate,accountId=occurrence.accountId,categoryId=occurrence.categoryId,
        amount=occurrence.amountMinor?.let{Money(Currency.valueOf(occurrence.currency),it).decimal.toPlainString()}.orEmpty(),
        baseAmount=occurrence.baseMinor?.let{Money(Currency.valueOf(book.currency),it).decimal.toPlainString()}.orEmpty(),
        activityId=occurrence.activityId,occurrenceId=occurrence.id,merchant=rule.merchant.ifBlank{rule.name},merchantId=rule.merchantId,
        merchantManuallyEdited=true,note=rule.note,cardPaymentChoice=rule.cardPaymentChoice,cardPaymentPeriods=rule.cardPaymentPeriods?.toString().orEmpty())
    return if(rule.cardPaymentChoice==null)withRememberedCardPayment(draft)else draft
}

data class TemplateInput(val name:String,val kind:EntryKind,val categoryId:String,val accountId:String,val currency:Currency,
    val amountMinor:Long?=null,val merchant:String="",val merchantId:String?=null,val note:String="",val cardPaymentChoice:String?=null,
    val cardPaymentPeriods:Int?=null,val activityId:String?=null,val id:String?=null,val budgetExcluded:Boolean=false,val merchantBaseline:MerchantBaseline?=null)

fun BookSnapshot.validateTemplate(input:TemplateInput) {
    require(input.name.isNotBlank()){ "请输入模板名称 / テンプレート名を入力してください" }
    require(input.kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME)){"模板仅支持支出和收入 / テンプレートは支出・収入のみ対応します"}
    require(categories.any{it.id==input.categoryId&&it.kind==input.kind.name}&&categoryEnabled(input.categoryId)){"请选择可用的同类型分类 / 有効な同じ種類のカテゴリを選択してください"}
    val account=entryAccounts(input.kind).firstOrNull{it.id==input.accountId}?:error("请选择可用账户 / 有効な口座を選択してください")
    require(input.currency.name==account.currency&&(input.amountMinor==null||input.amountMinor>0)){"金额须大于零且币种与账户一致 / 金額は0より大きく、口座の通貨と一致させてください"}
    require(input.merchantId==null||merchants.any{it.id==input.merchantId&&it.bookId==book.id}){"请选择本账本的可用商户 / この帳簿の有効なお店を選択してください"}
    require(input.activityId==null||availableActivities.any{it.id==input.activityId}){"活动不存在或已删除 / シーンは存在しないか削除済みです"}
    require(input.activityId==null||input.kind==EntryKind.EXPENSE){"活动只关联支出 / シーンは支出に関連付けてください"}
    fixedCardPayment(account,input.cardPaymentChoice,input.cardPaymentPeriods)
}
suspend fun LedgerRepository.saveTemplate(bookId:String,input:TemplateInput):String=db.withTransaction {
    val s=snapshot(dao.book(bookId)?:error("账本不存在 / 帳簿がありません")).first()
    val old=input.id?.let{id->s.templates.firstOrNull{it.id==id}?:error("模板不存在 / テンプレートがありません")}
    val fields=if(old!=null&&merchantFieldsUnchanged(MerchantBaseline(input.merchant,input.merchantId),input.merchantBaseline,s.merchants))MerchantBaseline(old.merchant,old.merchantId)else MerchantBaseline(input.merchant,input.merchantId)
    s.validateTemplate(input.copy(merchant=fields.name,merchantId=fields.id))
    require(input.activityId==null||s.features?.activityMode==true||old?.activityId==input.activityId){"请先开启活动模式 / シーンモードを有効にしてください"}
    val payment=fixedCardPayment(s.accounts.first{it.id==input.accountId},input.cardPaymentChoice,input.cardPaymentPeriods)
    val merchant=resolveSavedMerchant(bookId,input.kind,fields.id,fields.name,s.merchants)
    val value=EntryTemplateEntity(bookId,old?.id?:id(),input.name.trim(),input.kind.name,input.categoryId,input.accountId,input.currency.name,input.amountMinor,
        merchant?.name?:fields.name,merchant?.id,input.note,payment.first,payment.second,input.activityId,old?.sortOrder?:((s.templates.maxOfOrNull{it.sortOrder}?:-1)+1),old?.createdAt?:clock.millis(),budgetExcluded=input.kind==EntryKind.EXPENSE&&input.budgetExcluded)
    dao.putTemplate(value);value.id
}
suspend fun LedgerRepository.deleteTemplate(bookId:String,id:String)=dao.deleteTemplate(bookId,id)
suspend fun LedgerRepository.moveTemplate(bookId:String,id:String,direction:Int)=db.withTransaction {
    require(direction in listOf(-1,1))
    val list=dao.templates(bookId).toMutableList();val index=list.indexOfFirst{it.id==id}
    require(index>=0){"模板不存在 / テンプレートがありません"}
    val destination=index+direction
    if(destination in list.indices){java.util.Collections.swap(list,index,destination);list.forEachIndexed{i,t->dao.putTemplate(t.copy(sortOrder=i))}}
}

fun BookSnapshot.templateIssue(t:EntryTemplateEntity):String?=when {
    t.bookId!=book.id->"模板不属于本账本 / 別の帳簿のテンプレートです"
    t.kind !in listOf("EXPENSE","INCOME")->"不支持的模板类型 / 未対応のテンプレート種類です"
    entryAccounts(EntryKind.valueOf(t.kind)).none{it.id==t.accountId&&it.currency==t.currency}->"账户已停用或不可用，请编辑模板 / 口座が停止中か利用不可です。テンプレートを編集してください"
    !categoryEnabled(t.categoryId)||categories.none{it.id==t.categoryId&&it.kind==t.kind}->"分类已归档或不可用，请编辑模板 / カテゴリがアーカイブ済みか利用不可です。テンプレートを編集してください"
    t.merchantId!=null&&merchants.none{it.id==t.merchantId&&it.bookId==book.id}->"商户不存在或不属于本账本，请编辑模板 / お店が存在しないか別の帳簿のお店です。テンプレートを編集してください"
    t.activityId!=null&&availableActivities.none{it.id==t.activityId}->"活动已删除，请编辑模板 / シーンが削除済みです。テンプレートを編集してください"
    t.activityId!=null&&features?.activityMode!=true->"请先开启活动模式 / シーンモードを有効にしてください"
    else->null
}
fun BookSnapshot.templateDraft(template:EntryTemplateEntity,date:String=LocalDate.now().toString()):EntryDraft {
    require(templateIssue(template)==null){templateIssue(template)!!}
    val d=EntryDraft(kind=EntryKind.valueOf(template.kind),date=date,accountId=template.accountId,categoryId=template.categoryId,
        amount=template.amountMinor?.let{Money(Currency.valueOf(template.currency),it).decimal.toPlainString()}.orEmpty(),
        merchant=template.merchant,merchantId=template.merchantId,merchantManuallyEdited=template.merchant.isNotBlank()||template.merchantId!=null,
        note=template.note,budgetExcluded=template.budgetExcluded,cardPaymentChoice=template.cardPaymentChoice,cardPaymentPeriods=template.cardPaymentPeriods?.toString().orEmpty(),activityId=template.activityId)
    return if(template.cardPaymentChoice==null)withRememberedCardPayment(d)else d
}
fun BookSnapshot.templateInput(d:EntryDraft,name:String,includeAmount:Boolean):TemplateInput {
    val account=accounts.firstOrNull{it.id==d.accountId}?:error("请选择账户 / 口座を選択してください")
    val currency=Currency.valueOf(account.currency)
    val amount=if(d.originalCurrency!=null&&d.originalCurrency!=currency)d.accountAmount else d.amount
    return TemplateInput(name,d.kind,d.categoryId?:error("请选择分类 / カテゴリを選択してください"),account.id,currency,
        if(includeAmount)AmountExpression.money(amount,currency).minor else null,d.merchant,d.merchantId,d.note,d.cardPaymentChoice,
        d.cardPaymentPeriods.takeIf{it.isNotBlank()}?.toIntOrNull(),d.activityId,budgetExcluded=d.kind==EntryKind.EXPENSE&&d.budgetExcluded,merchantBaseline=d.merchantBaseline)
}
internal fun List<EntryTemplateEntity>.remapTemplateCategories(sourceIds:Set<String>,targetId:String)=map{if(it.categoryId in sourceIds)it.copy(categoryId=targetId)else it}
