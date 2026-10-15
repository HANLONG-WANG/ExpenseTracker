package com.yohaku.expensetracker.ui.features

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
import java.time.YearMonth

@Composable internal fun RecurringScreen(s:BookSnapshot,vm:AppViewModel,onConfirm:(OccurrenceEntity)->Unit,onRecord:(EventEntity)->Unit,onAccounts:()->Unit,onCategories:()->Unit) {
    val today by vm.today.collectAsStateWithLifecycle()
    var month by rememberSaveable(s.book.id){mutableStateOf(YearMonth.from(today).toString())}
    var editor by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var linking by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    val review by vm.occurrenceLinkReview.collectAsStateWithLifecycle()
    LaunchedEffect(linking,s.occurrences,s.events){if(linking!=null&&s.pendingOccurrences().none{it.id==linking})linking=null}
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(s.book.id,month,s.recurrences){vm.materializeMonth(s.book.id,month)}
    YTitle(word("先为日常，留好位置。","いつもの支払いに、余白を。"),"RECURRING BILLS",word("预留不是已花；确认后才成为记账。","予定は支出済みではありません。確認して記録。"),pageFlow=true)
    Box(Modifier.fillMaxWidth().testTag("recurring-month")) {MonthChooser(month){month=it}}
    val projection=s.budgetProjection(month)
    val journal=LocalYohaku.current.id=="journal"
    Panel(Modifier.testTag("recurring-reserve-summary"),contentSpacing=0.dp) {
        YText(word("本月尚未支付的预算预留","この月の未払い予算予定"),if(journal)11 else 10,color=LocalYohaku.current.muted,lineHeightMultiplier=if(journal)1.9f else 1.8f,cssLineBox=true)
        YText(projection.fixedReserveMinor?.let{Money(projection.currency,it).display()}?:word("待填写 / 折算","金額・換算待ち"),if(projection.fixedReserveMinor!=null)37 else 29,Modifier.padding(top=6.dp),number=projection.fixedReserveMinor!=null)
        YText(word("预留不是已花。确认新记账或关联已有支出后解除，不重复扣减。","予定は支出済みではありません。記録または既存支出との関連で解除し、二重控除しません。"),if(journal)11 else 10,Modifier.padding(top=10.dp,bottom=10.dp),color=LocalYohaku.current.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
    }
    val pending=s.pendingOccurrences().map{it.id}.toSet()
    val values=s.occurrences.filter{it.scheduledDate.startsWith(month)||(month==YearMonth.from(today).toString()&&it.id in pending&&it.scheduledDate<month+"-01")}.sortedBy{it.scheduledDate}
    if(values.isEmpty())YEmpty(word("添加房租、账单或订阅。","家賃・請求・サブスクを追加。"))
    values.forEach{o->
    val recorded=s.visibleEvents.find{it.id==o.eventId&&it.kind==EntryKind.EXPENSE.name}
    val rule=s.recurrences.first{it.id==o.recurrenceId}
    val status=when {o.status=="CANCELLED"->word("已取消","取消済み");o.status=="WITHDRAWN"->word("规则已撤下","ルール対象外");o.id !in pending->word("已记账","記録済み");o.amountMinor==null->word("金额未定","金額未定");o.estimated->word("预计金额","予定金額");else->word("金额已确认 · 待付款","金額確定・未払い")}
    RecurringCard(o.id,o.name,o.scheduledDate,(word("规则：","ルール：")+frequencyName(RecurrenceFrequency.valueOf(rule.frequency))+" · "+s.accounts.find{it.id==o.accountId}?.name.orEmpty()),recorded?.let{s.accountMoney(it).display()}?:o.amountMinor?.let{Money(Currency.valueOf(o.currency),it).display()}?:word("金额未定","金額未定"),recorded!=null||o.amountMinor!=null,status,o.id in pending&&(o.estimated||o.amountMinor==null),busy,if(o.id in pending)({onConfirm(o)})else null,{editor=o.recurrenceId},showDetails=recorded!=null||o.id in pending||o.status=="CANCELLED") {
        if(recorded!=null&&s.settlements.any{it.eventId==recorded.id&&!it.confirmed})YText(word("账户入账金额仍待确认","口座の計上額は確認待ち"),11,Modifier.testTag("occurrence-settlement-pending-${o.id}"),color=LocalYohaku.current.warm)
        if(recorded!=null)YText(word("原预计：","元の見込み：")+(o.amountMinor?.let{Money(Currency.valueOf(o.currency),it).display()}?:word("金额未定","金額未定")),10,color=LocalYohaku.current.muted)
        if(o.id in pending&&o.scheduledDate<today.toString())YText(word("到期仍待确认","期限後・確認待ち"),11,color=LocalYohaku.current.warm)
        if(o.status=="CANCELLED") {
            if(rule.includesScheduledDate(o.scheduledDate))YLink(word("恢复这次待办","この予定を復元"),{vm.execute{vm.repository.restoreOccurrence(s.book.id,o.id)}},Modifier.testTag("restore-occurrence-${o.id}"))
            else YText(word("先启用规则，并确认这次日期仍在安排范围内。","ルールを有効にし、予定日が範囲内か確認してください。"),10,color=LocalYohaku.current.muted)
        }
        if(o.id in pending) {
            YLink(word("关联已有支出","既存の支出に関連付け"),{linking=o.id},Modifier.testTag("link-occurrence-${o.id}"))
            YLink(word("取消这次待办","この予定を取消"),{vm.execute{vm.repository.cancelOccurrence(s.book.id,o.id)}},Modifier.testTag("cancel-occurrence-${o.id}"))
        }else o.eventId?.let{id->s.visibleEvents.find{it.id==id}?.let{event->
            YButton(word("查看已存记录","保存済みの記録を見る"),{onRecord(event)})
            YLink(if(rule.includesScheduledDate(o.scheduledDate))word("解除关联，重新待确认","関連を解除して確認待ちへ")else word("解除关联，保留已记账事实","関連を解除し、記録済みの事実を保持"),{vm.execute{vm.repository.unlinkOccurrence(s.book.id,o.id)}})
        }}
    }}
    ThemeSection(word("周期规则","定期ルール"),"SCHEDULE RULES")
    s.recurrences.forEach{rule->Panel(Modifier.testTag("recurrence-rule-${rule.id}")) {
        YText(rule.name,if(journal)18 else if(LocalYohaku.current.id=="objects")16 else 17,weight=if(LocalYohaku.current.id=="objects")androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal)
        YText(word("规则：","ルール：")+frequencyName(RecurrenceFrequency.valueOf(rule.frequency))+" · "+rule.anchorDate+" · "+if(rule.active)word("启用","有効")else word("停用","停止"),11,color=LocalYohaku.current.muted)
        YButton(word("编辑规则","ルールを編集"),{editor=rule.id})
    }}
    when {
        s.accounts.none{it.active&&it.type!=AccountType.LOAN.name}->YButton(word("添加付款账户","支払口座を追加"),onAccounts,primary=true)
        s.categories.none{it.active&&it.kind==EntryKind.EXPENSE.name}->YButton(word("创建支出分类","支出カテゴリを作成"),onCategories,primary=true)
        else->YButton(word("＋ 添加周期账单","＋ 定期支払を追加"),{editor=""},Modifier.testTag("recurrence-add"),primary=true)
    }
    editor?.let{id->RecurrenceEditor(s,if(id.isBlank())null else s.recurrences.first{it.id==id},vm,{editor=null})}
    val linkingOccurrence=s.occurrences.find{it.id==linking&&it.id in pending}
    if(linkingOccurrence!=null&&review==null)YSheet(word("选择已有支出","既存の支出を選ぶ"),{linking=null}) {
        val occurrence=linkingOccurrence
        val eligible=s.visibleEvents.filter{e->e.kind==EntryKind.EXPENSE.name&&s.occurrences.none{it.eventId==e.id&&it.status=="COMPLETED"&&it.id!=occurrence.id}}
        ThemeAuditPanel(occurrence.name,Modifier.testTag("occurrence-link-context")) {
            RecordFacts(listOf(
                word("周期安排日期","定期予定日") to occurrence.scheduledDate,
                word("原预计金额","元の見込み額") to (occurrence.amountMinor?.let{Money(Currency.valueOf(occurrence.currency),it).display()}?:word("金额未定","金額未定")),
                word("安排的付款账户","予定の支払口座") to s.accounts.first{it.id==occurrence.accountId}.name
            ),audit=true)
        }
        YText(word("选择已记支出，只解除这次预留，不重复记账。","記録済みの支出を選び、この予定を解除。重複記録はしません。"),11,color=LocalYohaku.current.muted)
        if(eligible.isEmpty())YText(word("没有可关联的已有支出，可以返回确认新记账。","関連できる支出がありません。戻って新しく記録できます。"),12)
        else TransactionList(s,eligible,{e->vm.previewOccurrenceLink(s.book.id,occurrence.id,e.id)})
    }

}

@Composable private fun RecurrenceEditor(s:BookSnapshot,original:RecurrenceEntity?,vm:AppViewModel,onClose:()->Unit) {
    val accounts=s.accounts.filter{it.active&&it.type!=AccountType.LOAN.name}
    val categories=s.categories.filter{it.active&&it.kind==EntryKind.EXPENSE.name}
    var name by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.name.orEmpty())}
    var accountId by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.accountId?:accounts.firstOrNull()?.id)}
    var categoryId by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.categoryId?:categories.firstOrNull()?.id)}
    var amount by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.amountMinor?.let{Money(Currency.valueOf(original.currency),it).decimal.toPlainString()}.orEmpty())}
    var base by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.baseMinor?.let{Money(Currency.valueOf(s.book.currency),it).decimal.toPlainString()}.orEmpty())}
    var frequency by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.frequency?:RecurrenceFrequency.MONTHLY.name)}
    var date by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.anchorDate?:java.time.LocalDate.now(vm.repository.clock).toString())}
    var endEnabled by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.endDate!=null)}
    var end by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.endDate?:date)}
    var estimated by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.estimated?:true)}
    var active by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.active?:true)}
    var activity by rememberSaveable(s.book.id,original?.id){mutableStateOf(original?.activityId)}
    var owner by remember{mutableStateOf<Int?>(null)}
    val account=accounts.find{it.id==accountId}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(if(original==null)word("添加周期账单","定期支払を追加")else word("编辑周期账单","定期支払を編集"),onClose) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            YField(word("名称","名称"),name,{name=it})
            YField(word("金额 / 未定可留空","金額・未定なら空欄")+" · "+(account?.currency?:"—"),amount,{amount=it},numeric=true)
            if(account?.currency!=s.book.currency)YField(word("本位币预留 / 未折算可留空","基準通貨の確保額・未換算なら空欄")+" · "+s.book.currency,base,{base=it},numeric=true)
            YDateFields(listOf(word("首次安排日期","最初の予定日") to date)+if(endEnabled)listOf(word("结束日期","終了日") to end)else emptyList(),owner,{owner=it},{index,value->if(index==0)date=value else end=value})
            YSelect(word("频率","頻度"),frequency,listOf(RecurrenceFrequency.MONTHLY,RecurrenceFrequency.WEEKLY,RecurrenceFrequency.YEARLY).map{it.name to frequencyName(it)},{frequency=it})
            YSelect(word("付款账户","支払口座"),accountId,accounts.map{it.id as String? to it.name},{id->
                val next=accounts.first{it.id==id};if(next.currency!=account?.currency){amount="";base="";estimated=true};accountId=id
            })
            YSelect(word("分类","カテゴリ"),categoryId,categories.map{it.id as String? to s.path(it.id)},{categoryId=it})
            if(amount.isBlank())YText(word("金额未定，预留金额仍未知；填写后可选择预计或已确认金额。","金額未定のため確保額は不明です。入力後に見込み・確定を選べます。"),11,Modifier.testTag("recurrence-unknown-amount"),color=LocalYohaku.current.muted)
            else YToggle(word("金额为预计","金額は見込み"),estimated,{estimated=it})
            YToggle(word("设置结束日期","終了日を設定"),endEnabled,{owner=null;endEnabled=it})
            if(s.features?.activityMode==true)YSelect(word("所属活动 / 可选","シーン・任意"),activity,listOf(null to word("日常","日常"))+s.activities.map{it.id as String? to it.name},{activity=it})
            YToggle(word("启用此周期规则","この定期ルールを有効にする"),active,{active=it})
            YText(word("规则只生成待办。实际确认后才写账；停用保留已确认事实。","ルールは予定のみ生成。実際の確認後に記録し、停止しても確認済みの事実を保持します。"),11,color=LocalYohaku.current.muted)
            YButton(word("保存周期规则","定期ルールを保存"),{
                val capturedName=name;val capturedAccount=accountId;val capturedCategory=categoryId;val capturedAmount=amount;val capturedBase=base
                val capturedFrequency=frequency;val capturedDate=date;val capturedEnd=if(endEnabled)end else null
                val capturedEstimated=estimated;val capturedActive=active;val capturedActivity=activity
                vm.execute(onSuccess=onClose) {
                    val selected=accounts.firstOrNull{it.id==capturedAccount}?:error("请选择付款账户 / 支払口座を選択してください")
                    val value=capturedAmount.takeIf{it.isNotBlank()}?.let{Money.parse(it,Currency.valueOf(selected.currency))}
                    val baseValue=capturedBase.takeIf{it.isNotBlank()}?.let{Money.parse(it,Currency.valueOf(s.book.currency))}
                    vm.repository.saveRecurrence(s.book.id,RecurrenceInput(capturedName,selected.id,capturedCategory?:error("请选择分类 / カテゴリを選択してください"),value,baseValue,
                        capturedEstimated,RecurrenceFrequency.valueOf(capturedFrequency),capturedDate,capturedEnd,original?.id,capturedActive,capturedActivity))
                }
            },Modifier.testTag("recurrence-save"),primary=true,enabled=!busy)
        }
    }
}
@Composable internal fun frequencyName(frequency:RecurrenceFrequency)=when(frequency) {
    RecurrenceFrequency.WEEKLY->word("每周","毎週")
    RecurrenceFrequency.MONTHLY->word("每月","毎月")
    RecurrenceFrequency.YEARLY->word("每年","毎年")
}

@Composable internal fun OccurrenceLinkReviewSheet(r:OccurrenceLinkReview,refreshed:Boolean,busy:Boolean,returnToForm:Boolean,onDismiss:()->Unit,onConfirm:()->Unit) {
    val s=r.snapshot
    YSheet(word("关联变化预览","関連付けの変化を確認"),onDismiss) {
        if(refreshed)YText(word("资料已变化，请确认重新计算结果。","情報が変わりました。再計算した結果を確認してください。"),12,color=LocalYohaku.current.warm)
        ThemeAuditPanel(r.occurrence.name,Modifier.testTag("occurrence-link-review-panel")) {
            RecordFacts(listOf(
                word("周期安排日期","定期予定日") to r.occurrence.scheduledDate,
                word("已有支出","既存の支出") to r.event.merchant.ifBlank{s.path(r.event.categoryId)},
                word("支出日期","支出日") to r.event.date,
                word("账户金额","口座通貨の金額") to s.accountMoney(r.event).display()
            ),audit=true)
        }
        ThemeSection(word("关联后，预留会这样变化","関連付け後の確保額の変化"),"BEFORE / AFTER")
        BudgetChanges(r.before,r.after)
        YText(word("只建立关联并解除对应预留，不新增支出，不再次扣账户余额。","関連と予定解除のみ。支出の追加や口座残高の再控除はしません。"),11,color=LocalYohaku.current.muted)
        YButton(word("确认关联已有记录","既存記録との関連を確定"),onConfirm,primary=true,enabled=!busy)
        YLink(if(returnToForm)word("返回付款表单","支払フォームに戻る")else word("返回选择","選択に戻る"),onDismiss)
    }
}
