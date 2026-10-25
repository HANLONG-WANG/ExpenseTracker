package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate

@Composable internal fun CashTimelineView(s:BookSnapshot,filter:AnalysisFilter,vm:AppViewModel,onRecurring:()->Unit,onActivity:(String)->Unit,onRecord:(EventEntity)->Unit,onFinancial:(String)->Unit,outlook:FinancialOutlookRead?=null,onTimelineItem:((CashTimelineItem)->Unit)?=null,onRetry:(()->Unit)?=null,beforeTimeline:@Composable ()->Unit={}) {
    var attempt by remember{mutableIntStateOf(0)}
    val read=outlook?:rememberFinancialOutlook(s,vm,LocalDate.parse(filter.end),attempt).value

    when(val state=read) {
        FinancialOutlookRead.Loading->{YText(word("正在计算资金安排…","資金予定を計算中…"),12);beforeTimeline()}
        is FinancialOutlookRead.Failure->{YText(state.message,12);YButton(word("重新读取","再読込"),{if(onRetry!=null)onRetry()else attempt++});beforeTimeline()}
        is FinancialOutlookRead.Ready->{
            val t=state.timeline
            FinancialBudgetScenario(state.pace,Currency.valueOf(s.book.currency))
            beforeTimeline()
            YText(word("以今天实际资金为起点，按账户独立推算；分类、商户与活动筛选不移除其他扣款。未到账收入另列情景。","本日の実資金を起点に口座別に推計。他の引落はカテゴリ・お店・シーンの絞込で除外しません。未入金の収入は別シナリオです。"),11)
            ThemeSection(word("实际扣款账户的时间线","引落口座ごとの予定"),"ACCOUNT BY ACCOUNT")
            YText(t.asOf+" → "+t.through,12)
            if(t.openings.isNotEmpty())RecordFacts(t.openings.map{o->o.account.name+" · "+o.account.currency to Money(Currency.valueOf(o.account.currency),o.actualMinor).display()})
            YText(word("同一天未配置扣款顺序，先扣后入；活动预留从可安排资金中扣除，不是真实支出。待确认金额影响后，余额显示未知。","同日の処理順が未設定のため出金を先に計算。シーン確保額は予定に使える資金から差し引き、実支出にはしません。未確定額の影響後は残高を不明と表示します。"),11,color=LocalYohaku.current.muted)
            val rows=t.steps.filter{it.item.date>=maxOf(filter.start,t.asOf)}
            if(rows.isEmpty())YEmpty(word("这个期间没有已知资金安排。","この期間の既知の資金予定はありません。"))
            rows.forEach{step->
                val item=step.item;val account=s.accounts.firstOrNull{it.id==item.accountId}
                Column(Modifier.testTag("cash-${item.id}")) {
                    FutureTimelineRow(item.id,item.date,if(item.name in EntryKind.entries.map{it.name})entryKindName(EntryKind.valueOf(item.name))else item.name,account?.let{it.name+" · "+it.currency}?:word("扣款账户未确定","引落口座未定"),
                        item.amountMinor?.let{(if(item.incoming)"+ "else"− ")+Money(Currency.valueOf(item.currency),it).display()}?:word("金额未知","金額不明"),
                        when(item.kind){"EXPECTED_INCOME"->word("预计收入 · 尚未到账","収入予定・未入金");"RESERVE"->word("主动资金预留 · 非实际支出","自分で設定した資金確保・実支出ではない");else->if(item.estimated)word("预计 / 待确认安排","見込み・確認待ち予定")else word("已知安排","既知の予定")},atRisk=step.baselineAfterMinor?.let{it<0}==true,last=step===rows.last()) {
                    val unknown=word("未知 / 请补充资料","不明・情報を補足してください")
                    fun amount(n:Long?,estimated:Boolean=false)=if(account==null||n==null)unknown else (if(estimated)"≈ "else"")+Money(Currency.valueOf(account.currency),n).display()
                    YText(word("不含预计收入，安排后：","収入見込みなし・予定後：")+amount(step.baselineAfterMinor,step.baselineEstimated),11,Modifier.padding(top=9.dp),color=if(step.baselineAfterMinor?.let{it<0}==true)LocalYohaku.current.warm else LocalYohaku.current.muted)
                    YText(word("含预计收入情景：","収入見込みあり：")+amount(step.includingIncomeAfterMinor,step.includingEstimated),11,color=LocalYohaku.current.muted)
                    if(step.baselineAfterMinor?.let{it<0}==true)YText(word("基础情景存在本账户资金缺口：","基本シナリオでこの口座に不足：")+amount(Math.negateExact(step.baselineAfterMinor),step.baselineEstimated),13,Modifier.testTag("cash-gap-${item.id}"),color=LocalYohaku.current.warm)
                    if(item.scheduledDate!=item.date)YText(word("原安排 / 记录日期：","元の予定・記録日：")+item.scheduledDate,11,color=LocalYohaku.current.muted)
                    item.referenceRate?.let{ReferenceRateFacts(it)}
                    item.issue?.let{YText(cashIssue(it),12,color=LocalYohaku.current.warm)}
                    if(onTimelineItem!=null)YLink(word("查看并处理安排","予定を確認・対応"),{onTimelineItem(item)},Modifier.testTag("cash-open-${item.id}"))
                    else when(item.kind) {
                        "BILL"->YLink(word("查看账单与调整方案","請求・支払方法を見る"),{onFinancial(item.sourceId)},Modifier.testTag("cash-open-${item.id}"))
                        "RECURRENCE"->YLink(word("查看周期账单","定期支払を見る"),onRecurring)
                        "RESERVE"->YLink(word("查看活动预留","シーンの確保を見る"),{onActivity(item.sourceId)})
                        "ACTUAL","TRANSFER"->s.visibleEvents.firstOrNull{it.id==item.sourceId}?.let{e->YLink(word("查看实际记录","実際の記録を見る"),{onRecord(e)})}
                    }
                    }
                }
            }
        }
    }
}
@Composable internal fun cashIssue(code:String)=when(code) {
    "PAYMENT_ACCOUNT_INACTIVE"->word("所选资金账户已停用，请重新确认实际收付款账户。","選択した資金口座は停止中です。実際の入出金口座を再確認してください。")
    "INCOME_RECEIPT_CHANGED"->word("关联的实际收入已删除或改变，请核对到账。","関連する実収入が削除・変更されています。入金を確認してください。")
    "REFERENCE_CONVERSION"->word("账户金额使用参考估算；换汇/付款费用尚未确认，不代表实际足额或已到账。","口座金額は参考見積もりです。両替・支払手数料は未確定で、実際の充足・入金済みを意味しません。")
    "STALE_PLAN"->word("原预测资料已变化，请核查并重算后再判断缺口。","予測の根拠が変わっています。確認・再計算してから不足を判定してください。")
    "PENDING_POSTING"->word("资金端尚未确认；此处按已填日期/金额预测，不代表已到账。","出入金は未確認。入力日・金額からの予測で、入金済みを意味しません。")
    "DEBT_PAYMENT_UNSCHEDULED"->word("此安排使用债务账户，尚无实际资金扣款安排。","この予定は借入口座を使用し、実資金の引落予定が未設定です。")
    "PAYMENT_ACCOUNT_REQUIRED","RESERVE_ACCOUNT_REQUIRED"->word("请指定实际资金账户；不把信用额度当作现金。","実資金の口座を指定してください。利用枠を現金には含めません。")
    "UNKNOWN_CONVERSION"->word("扣款/到账账户币种不同，账户金额尚未确认；需换汇或转入，其他账户资金不会自动补足。","口座通貨が異なり口座金額は未確定。両替・振込が必要で、他口座の資金を自動充当しません。")
    "PENDING_CLEARING_AMOUNT"->word("已有付款待平台确认，分配金额未知；不再假定需要重复付款。","支払済みで返済先の確認待ち。充当額が不明のため、再度支払うと仮定しません。")
    "PENDING_CLEARING"->word("已实付的待确认分配从未来现金需求中单独扣除，债务清偿仍待平台确认。","支払済みの確認待ち充当額は今後の資金需要から除外。債務の返済確定は返済先の確認待ちです。")
    else->word("金额未知，请补充实际安排。","金額が不明です。実際の予定を補足してください。")
}
