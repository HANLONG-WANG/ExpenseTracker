package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.ui.haptics.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FixedPaymentFields(d:EntryDraft,onChange:(EntryDraft)->Unit) {
    YSelect(word("付款方式","支払方法"),d.cardPaymentChoice,listOf(null to word("沿用最近一次付款方式","直近の支払方法を使用"))+
        CardPaymentChoice.entries.filter{it!=CardPaymentChoice.DEFERRED}.map{it.name as String? to cardPaymentLabel(it.name)},
        {onChange(d.clearCardPayment().copy(cardPaymentChoice=it,cardPaymentPeriods=if(it=="INSTALLMENT")d.cardPaymentPeriods else ""))},Modifier.testTag("fixed-payment-choice"))
    if(d.cardPaymentChoice=="INSTALLMENT")YField(word("分期次数","分割回数"),d.cardPaymentPeriods,{onChange(d.copy(cardPaymentPeriods=it))},Modifier.testTag("fixed-payment-periods"),numeric=true)
}

@Composable internal fun HomeTemplates(s:BookSnapshot,onUse:(EntryTemplateEntity)->Unit,onManage:()->Unit) {
    val haptics=LocalHaptics.current
    val available=s.templates.filter{s.templateIssue(it)==null}
    if(available.isEmpty())return
    ThemeSection(word("常用的一笔","いつもの一筆"),"QUICK NOTES",word("管理","管理"),onManage,linkTag="home-manage-templates")
    TemplateShelf {
        available.forEachIndexed{index,t->
            val meta=t.amountMinor?.let{Money(Currency.valueOf(t.currency),it).display()}?:s.categories.firstOrNull{it.id==t.categoryId}?.name.orEmpty()
            TemplateShelfItem(index,t.name,meta,com.yohaku.expensetracker.domain.category.resolveCategoryIcon(t.categoryId,s.categories),{haptics.perform(Haptic.Compose);onUse(t)},Modifier.testTag("use-template-${t.id}"))
        }
    }
}

@Composable internal fun TemplatesScreen(s:BookSnapshot,vm:AppViewModel) {
    var editing by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    val requestedEditor by vm.desktopTemplateEditor.collectAsStateWithLifecycle()
    LaunchedEffect(requestedEditor){requestedEditor?.let{editing=it;vm.desktopTemplateEditor.value=null}}
    var deleting by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YTitle(word("常用的一笔，留作模板。","いつもの記録を、テンプレートに。"),"ENTRY TEMPLATES",word("首页点一下模板，只填金额就能记好。","ホームでテンプレートを押せば、金額だけで記録できます。"),pageFlow=true)
    s.templates.forEachIndexed{index,t->Panel(Modifier.testTag("template-${t.id}")) {
        val account=s.accounts.first{it.id==t.accountId}
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(LocalYohaku.current.id!="magazine")CategoryIconBadge(com.yohaku.expensetracker.domain.category.resolveCategoryIcon(t.categoryId,s.categories),CategoryBadge.SMALL)
            YText(t.name,if(LocalYohaku.current.id=="journal")19 else 17,Modifier.weight(1f))
            YText(t.amountMinor?.let{Money(Currency.valueOf(t.currency),it).display()}?:word("每次输入","毎回入力"),if(t.amountMinor!=null)16 else 11,number=t.amountMinor!=null,color=if(t.amountMinor!=null)LocalYohaku.current.ink else LocalYohaku.current.muted)
        }
        RecordFacts(listOfNotNull(word("类型 · 分类","種類・カテゴリ") to ((if(t.kind==EntryKind.INCOME.name)word("收入","収入")else word("支出","支出"))+" · "+s.path(t.categoryId)),
            word("账户","口座") to (account.name+(t.cardPaymentChoice?.let{" · "+cardPaymentLabel(it)+if(it=="INSTALLMENT")" ${t.cardPaymentPeriods}"else ""}?:"")),
            t.merchant.takeIf{it.isNotBlank()}?.let{word("商户","お店") to it},t.note.takeIf{it.isNotBlank()}?.let{word("备注","メモ") to it}))
        s.templateIssue(t)?.let{YText(localizedRepositoryMessage(it),12,Modifier.testTag("template-issue-${t.id}"),color=LocalYohaku.current.warm)}
        YActionRow {
            YButton(word("编辑","編集"),{editing=t.id},Modifier.testTag("edit-template-${t.id}"),enabled=!busy,inline=true)
            if(index>0)YLink(word("↑ 上移","↑ 上へ"),{vm.execute{vm.repository.moveTemplate(s.book.id,t.id,-1)}},Modifier.testTag("template-up-${t.id}"),haptic=Haptic.Step)
            if(index<s.templates.lastIndex)YLink(word("↓ 下移","↓ 下へ"),{vm.execute{vm.repository.moveTemplate(s.book.id,t.id,1)}},Modifier.testTag("template-down-${t.id}"),haptic=Haptic.Step)
            YLink(word("删除","削除"),{deleting=t.id},Modifier.testTag("delete-template-${t.id}"))
        }
    }}
    if(s.templates.isEmpty())YEmpty(word("把常用的支出或收入保存为模板。","よく使う支出・収入をテンプレートに保存できます。"))
    YButton(word("新建模板","テンプレートを作成"),{editing=""},Modifier.testTag("template-add"),primary=true)
    editing?.let{id->TemplateEditor(s,s.templates.firstOrNull{it.id==id},vm){editing=null}}
    deleting?.let{id->YSheet(word("删除模板","テンプレートを削除"),{deleting=null}) {
        YText(word("只删除模板，已保存的记账不受影响。","テンプレートのみ削除し、保存済みの記録は保持します。"))
        YButton(word("确认删除","削除を確定"),{vm.execute(onSuccess={vm.haptic(Haptic.Removed);deleting=null}){vm.repository.deleteTemplate(s.book.id,id)}},Modifier.testTag("template-delete-confirm"),primary=true,enabled=!busy)
    }}
}

@Composable internal fun SaveTemplateSheet(s:BookSnapshot,d:EntryDraft,vm:AppViewModel,onClose:()->Unit) {
    var name by rememberSaveable(d.mediaSession){mutableStateOf(d.merchant.ifBlank{s.categories.firstOrNull{it.id==d.categoryId}?.name.orEmpty()})}
    var includeAmount by rememberSaveable(d.mediaSession){mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(word("存为模板","テンプレートに保存"),onClose) {
        YField(word("模板名称","テンプレート名"),name,{name=it},Modifier.testTag("template-name"))
        YToggle(word("同时保存金额","金額も保存"),includeAmount,{includeAmount=it},Modifier.testTag("template-include-amount"))
        YButton(word("保存模板","テンプレートを保存"),{
            vm.execute(onSuccess={vm.haptic(Haptic.Done);onClose()}){vm.repository.saveTemplate(s.book.id,s.templateInput(d,name,includeAmount))}
        },Modifier.testTag("template-save"),primary=true,enabled=!busy)
    }
}

@Composable private fun TemplateEditor(s:BookSnapshot,t:EntryTemplateEntity?,vm:AppViewModel,onClose:()->Unit) {
    var name by rememberSaveable(t?.id){mutableStateOf(t?.name.orEmpty())}
    var d by remember(t?.id){mutableStateOf(EntryDraft(kind=t?.kind?.let{EntryKind.valueOf(it)}?:EntryKind.EXPENSE,categoryId=t?.categoryId,
        accountId=t?.accountId?:s.defaultEntryAccount(EntryKind.EXPENSE),amount=t?.amountMinor?.let{Money(Currency.valueOf(t.currency),it).decimal.toPlainString()}.orEmpty(),
        merchant=t?.merchant.orEmpty(),merchantId=t?.merchantId,note=t?.note.orEmpty(),budgetExcluded=t?.budgetExcluded?:false,cardPaymentChoice=t?.cardPaymentChoice,cardPaymentPeriods=t?.cardPaymentPeriods?.toString().orEmpty(),activityId=t?.activityId))}
    val account=s.accounts.firstOrNull{it.id==d.accountId}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(if(t==null)word("新建模板","テンプレートを作成")else word("编辑模板","テンプレートを編集"),onClose) {
        YField(word("模板名称","テンプレート名"),name,{name=it},Modifier.testTag("template-name"))
        YSelect(word("类型","種類"),d.kind,listOf(EntryKind.EXPENSE to word("支出","支出"),EntryKind.INCOME to word("收入","収入")),{kind->d=d.clearCardPayment().copy(kind=kind,budgetExcluded=false,categoryId=null,accountId=null,amount="",activityId=null)},Modifier.testTag("template-kind"))
        YSelect(word("分类","カテゴリ"),d.categoryId,s.categories.filter{s.categoryEnabled(it.id)&&it.kind==d.kind.name}.map{it.id as String? to s.path(it.id)},{d=d.copy(categoryId=it)},Modifier.testTag("template-category"))
        YSelect(word("账户","口座"),d.accountId,s.entryAccounts(d.kind).map{it.id as String? to it.name},{id->
            val next=s.accounts.first{it.id==id};d=d.clearCardPayment().copy(accountId=id,amount=if(next.currency!=account?.currency)""else d.amount)
        },Modifier.testTag("template-account"))
        if(d.kind==EntryKind.EXPENSE&&account?.type==AccountType.CREDIT.name)FixedPaymentFields(d){d=it}
        YField(word("金额 / 每次输入可留空","金額・毎回入力なら空欄")+" · "+(account?.currency?:s.book.currency),d.amount,{d=d.copy(amount=it)},Modifier.testTag("template-amount"),numeric=true)
        EntryMerchantInput(s,d){d=it}
        YField(word("备注","メモ"),d.note,{d=d.copy(note=it)},Modifier.testTag("template-note"),multiline=true)
        if(d.kind==EntryKind.EXPENSE&&(s.features?.activityMode==true||d.activityId!=null))YSelect(word("所属活动 / 可选","シーン・任意"),d.activityId,
            listOf(null to word("日常","日常"))+s.availableActivities.map{it.id as String? to it.name},{d=d.copy(activityId=it)},Modifier.testTag("template-activity"))
        if(d.kind==EntryKind.EXPENSE)BudgetExcludedToggle(d.budgetExcluded){d=d.copy(budgetExcluded=it)}
        YButton(word("保存模板","テンプレートを保存"),{vm.execute(onSuccess={vm.haptic(Haptic.Done);onClose()}){vm.repository.saveTemplate(s.book.id,s.templateInput(d,name,d.amount.isNotBlank()).copy(id=t?.id))}},Modifier.testTag("template-save"),primary=true,enabled=!busy)
    }
}

@Composable internal fun localizedRepositoryMessage(message:String):String {
    val parts=message.split(" / ",limit=2)
    return word(parts[0],parts.getOrElse(1){parts[0]})
}
