package com.yohaku.expensetracker.ui.features.command

import com.yohaku.expensetracker.ui.haptics.Haptic
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth

/** Magazine spread, ruled notebook, and a soft keepsake card are separate theme compositions. */
@Composable internal fun MonthlyPanel(title:String,tag:String,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    when(t.id) {
        "magazine"->Column(Modifier.fillMaxWidth().testTag(tag).drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),3.dp.toPx())}.padding(top=14.dp,bottom=14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Box(Modifier.size(8.dp,26.dp).background(t.accent));YText(title,23,Modifier.weight(1f))};content()
        }
        "journal"->Column(Modifier.fillMaxWidth().testTag(tag).background(t.surface).drawBehind{val line=30.dp.toPx();var y=line;while(y<size.height){drawLine(t.line,Offset(0f,y),Offset(size.width,y),.5.dp.toPx());y+=line}}.padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            YText(title,24,Modifier.rotate(-1f),color=t.warm);content()
        }
        else->Column(Modifier.fillMaxWidth().testTag(tag).background(t.surface,RoundedCornerShape(24.dp)).border(1.dp,t.line,RoundedCornerShape(24.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){YIcon("file",Modifier.size(24.dp),t.accent);YText(title,20,Modifier.weight(1f))};content()
        }
    }
}

@Composable internal fun MonthlyPlanScreen(s:BookSnapshot,vm:AppViewModel) {
    var text by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(s.book.id){if(text==null)text=withContext(Dispatchers.IO){vm.repository.db.monthlyDao().plan(s.book.id)?.text.orEmpty()}}
    MonthlyPanel(word("给每个月，一个方向。","毎月の暮らしに、方針を。"),"monthly-plan") {
        YText(word("本账本共用一份月度计划。每次提交评语时会保存当时的原文，之后编辑不改变过去的评语。","この帳簿で使う月の計画です。振り返りの送信時に原文を保存し、その後の編集で過去の記録は変わりません。"),13)
        text?.let{value->YField(word("月度计划","月の計画"),value,{text=it.take(20000)},Modifier.testTag("monthly-plan-text"),multiline=true)}
        YButton(word("保存月度计划","月の計画を保存"),{val value=text?:return@YButton;vm.execute(onSuccess={vm.navigate("budget")}){vm.repository.saveMonthlyPlan(s.book.id,value);vm.haptic(Haptic.Done)}},Modifier.fillMaxWidth().testTag("monthly-plan-save"),primary=true,enabled=!busy&&text!=null&&!s.book.archived)
        YLink(word("评语历史与提交","振り返りの履歴・送信"),{vm.navigate("monthly-history")})
    }
}

@Composable internal fun MonthlySubmitScreen(s:BookSnapshot,vm:AppViewModel) {
    val cloud=vm.cloud?:return
    val selected by vm.monthlyReviewMonth.collectAsStateWithLifecycle()
    val month=selected?:YearMonth.now(vm.repository.clock).minusMonths(1).toString()
    var bundle by remember(s.book.id,month){mutableStateOf<MonthlyReviewBundle?>(null)}
    var submitted by remember(s.book.id,month){mutableStateOf(false)}
    var error by remember(s.book.id,month){mutableStateOf<String?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(s.book.id,month) {
        try {
            val ready=withContext(Dispatchers.IO){(vm.repository.db.monthlyDao().review(s.book.id,month)!=null) to vm.repository.monthlyReviewBundle(s.book.id,month,vm.preferences)}
            submitted=ready.first;bundle=ready.second
        }
        catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}
    }
    MonthlyPanel(word("${month} · 提交月度评语","${month}・月の振り返りを依頼"),"monthly-submit") {
        YText(word("确认后上传本页汇总、月计划与账本结构，请助手写评语。不会自动执行建议。","確認後、この集計・月の計画・帳簿構成を送信してコメントを依頼します。提案は自動実行されません。"),13)
        bundle?.let{MonthlyUploadOverview(it)}
        error?.let{YText(localizedCommand(it),13,color=LocalYohaku.current.warm)}
        if(submitted)YText(word("这个月已经提交。请在云端交接台继续查看或重试原任务。","この月は送信済みです。クラウド画面で元のタスクの確認・再試行ができます。"),13)
        val title=word("${month} 月度评语","${month} 月の振り返り")
        val request=word("请按所附月度汇总、月计划与账本结构，评估本月情况。评语写入 message，可选建议写入 steps，不得代替用户声明事实。请用中文回复。","添付の月次集計・計画・帳簿構成から今月を振り返ってください。コメントは message、任意の提案は steps に記載し、利用者の事実確認を代行しないでください。日本語で回答してください。")
        YButton(word("确认上传并提交评语","内容を確認して送信"),{val ready=bundle?:return@YButton;vm.execute(onSuccess={vm.navigate("monthly-history")}){cloud.prepareCommandTask(s.book.id,emptyList(),title,request,CommandExportBundle(ready.context,null),ready);vm.haptic(Haptic.Done)}},Modifier.fillMaxWidth().testTag("monthly-submit-confirm"),primary=true,enabled=!busy&&bundle!=null&&!submitted)
        YLink(word("云端交接台","クラウド"),{vm.navigate("cloud")})
    }
    bundle?.let{MonthlyUploadDetails(it)}
}

@Composable private fun MonthlyUploadOverview(bundle:MonthlyReviewBundle) {
    val s=bundle.summary
    RecordFacts(listOf(word("收入","収入") to monthlyAmount(s.currency,s.incomeMinor),word("净消费","純支出") to monthlyAmount(s.currency,s.expenseMinor)))
    val budget=s.budgets.first()
    YText(word("月预算：","月予算：")+monthlyAmount(s.currency,budget.limitMinor)+word(" · 已用：","・使用済み：")+monthlyAmount(s.currency,budget.spentMinor),13)
    YText(word("月计划：","月の計画：")+s.monthlyPlan.ifBlank{word("尚未填写","未記入")},14)
    YText(word("将上传 ${s.categories.size} 项分类汇总、${s.budgets.size} 项预算执行、${s.accounts.size} 个账户变化与 ${bundle.context.items.size} 项账本结构。下方可逐项查看。","カテゴリ集計 ${s.categories.size} 件、予算実績 ${s.budgets.size} 件、口座変動 ${s.accounts.size} 件、帳簿構成 ${bundle.context.items.size} 件を送信します。以下で内容を確認できます。"),12)
    YText(word("不附具体交易、图片或文件。","個別の取引・画像・ファイルは添付しません。"),11,color=LocalYohaku.current.muted)
}

@Composable private fun monthlyAmount(currency:String,value:Long?)=value?.let{Money(Currency.valueOf(currency),it).display()}?:word("未设置／尚未开立","未設定・未開設")

@Composable private fun MonthlyUploadDetails(bundle:MonthlyReviewBundle) {
    val s=bundle.summary
    MonthlyPanel(word("收支与预算执行","収支と予算実績"),"monthly-upload-categories") {
        YText(word("按账本币种汇总；退款回归原消费月，手续费按发生日。分类不重复计入上级，预算继承原设置并排除独立活动。","基準通貨で集計。返金は購入月、手数料は発生日に計上。カテゴリは親と重複せず、予算は引き継ぎ設定を使用し独立シーンを除きます。"),11)
        if(s.provisionalExpenseCount>0)YText(word("${s.provisionalExpenseCount} 笔消费金额尚待确认。","支出 ${s.provisionalExpenseCount} 件は金額の確定待ちです。"),12,color=LocalYohaku.current.warm)
        s.categories.forEach{row->YText((if(row.kind=="INCOME")word("收入 · ","収入・")else word("支出 · ","支出・"))+localizedCommand(row.name)+" · "+monthlyAmount(s.currency,row.amountMinor),13)}
        s.budgets.forEach{b->YText(localizedCommand(b.name)+word("：预算 ","：予算 ")+monthlyAmount(s.currency,b.limitMinor)+word(" · 已用 ","・使用済み ")+monthlyAmount(s.currency,b.spentMinor)+word(" · 剩余 ","・残額 ")+monthlyAmount(s.currency,b.remainingMinor),13)}
    }
    MonthlyPanel(word("账户变化","口座の変動"),"monthly-upload-accounts") {
        YText(word("按各账户币种与已确认入账日期；月初 → 月末。月中开立账户的变化包含期初资金。历史值反映当前已知事实。","口座通貨・確定計上日で月初→月末を表示。月中開設口座の変動には期首資金を含みます。現在判明している事実に基づく集計です。"),11)
        s.accounts.forEach{a->YText("${a.name} · ${a.currency}\n"+monthlyAmount(a.currency,a.beforeMinor)+" → "+monthlyAmount(a.currency,a.afterMinor)+word(" · 变化 ","・変動 ")+monthlyAmount(a.currency,a.changeMinor),13)}
    }
    MonthlyPanel(word("一并上传的账本结构","一緒に送る帳簿構成"),"monthly-upload-structure") {
        YText(word("包含当前账户余额、分类、商户与地点、朋友、模板、预算、周期、金融规则与计划、额度、活动、偏好和 CSV 批次摘要及月计划。下列是本次快照的项目名称；不含凭据、草稿、交易明细或评语历史。","現在の口座残高、カテゴリ、お店・場所、友人、テンプレート、予算、定期予定、金融条件・プラン、利用枠、シーン、設定、CSV概要と月の計画を含みます。以下は今回の構成項目名です。認証情報・下書き・個別取引・過去のコメントは含みません。"),12)
        var expanded by remember{mutableStateOf(false)}
        YLink(if(expanded)word("收起结构项目","構成項目を閉じる")else word("查看全部结构项目名称","すべての構成項目名を見る"),{expanded=!expanded})
        if(expanded)bundle.context.items.forEach{item->
            val name=when(item.kind){"preference"->localizedCommand(monthlyPreferenceName(item.name));"financial_rule"->word("金融规则","金融条件");"financial_bill"->word("第 ${item.name} 期付款","${item.name} 回目の支払");else->localizedCommand(item.name)}
            YText(name,12)
        }
    }
}

// Preference keys are protocol identifiers, never display them as labels.
private fun monthlyPreferenceName(key:String)=when(key){"activeBook"->"当前账本 / 現在の帳簿";"reminders"->"提醒设置 / 通知設定";"automaticRates"->"自动汇率 / 自動レート";"photoOriginal"->"照片保存 / 写真保存";"haptics"->"触感反馈 / 触覚フィードバック";"location"->"定位 / 位置情報";else->"偏好 / 設定"}

@Composable internal fun MonthlyHistoryScreen(s:BookSnapshot,vm:AppViewModel) {
    val cloud=vm.cloud?:return
    val history by remember(s.book.id){vm.repository.db.monthlyDao().history(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    val plans by remember(s.book.id){cloud.commands.history(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    var month by rememberSaveable(s.book.id){mutableStateOf(YearMonth.now(vm.repository.clock).minusMonths(1).toString())}
    MonthlyPanel(word("每月，留一页回望。","毎月の振り返りを、一頁に。"),"monthly-history") {
        MonthChooser(month,{month=it},previousTag="monthly-previous",nextTag="monthly-next",monthTag="monthly-month",previousEnabled=month>s.book.startDate.take(7))
        YButton(word("提交这个月的评语","この月の振り返りを依頼"),{vm.openMonthlyReview(month)},Modifier.fillMaxWidth().testTag("monthly-history-submit"),primary=true,enabled=month<YearMonth.now(vm.repository.clock).toString()&&month>=s.book.startDate.take(7)&&history.none{it.month==month})
        YLink(word("编辑月计划","月の計画を編集"),{vm.navigate("monthly-plan")})
        YLink(word("云端任务进度与重试","クラウドの進捗・再試行"),{vm.navigate("cloud")})
        if(history.isEmpty())YEmpty(word("还没有月度评语。提醒关闭时，也可以在这里手动提交。","月の振り返りはまだありません。通知を無効にしていても、ここから送信できます。"))
    }
    history.forEach{review->
        val bundle=remember(review.snapshotJson){readCommandDocument<MonthlyReviewBundle>(review.snapshotJson)}
        val row=plans.firstOrNull{it.planId==review.planId}
        val plan=remember(row?.planJson){row?.let{readCommandDocument<CommandPlan>(it.planJson)}}
        MonthlyPanel(review.month,"monthly-history-${review.month}") {
            YText(plan?.message?:word("已提交，等待评语。","送信済み・コメント待ち。"),15)
            YText(word("当时的月计划：","送信時の月の計画：")+bundle.summary.monthlyPlan.ifBlank{word("未填写","未記入")},13)
            YText(monthlySuggestionStatus(row,plan),12,color=LocalYohaku.current.accent)
            row?.let{YButton(word("查看评语与建议","コメント・提案を見る"),{vm.openCommandPlan(it.planId)},Modifier.testTag("monthly-review-open-${review.month}"))}
        }
    }
}

@Composable internal fun monthlySuggestionStatus(row:CommandPlanEntity?,plan:CommandPlan?):String=when {
    plan==null->word("等待领取","受け取り待ち")
    plan.steps.isEmpty()->word("仅评语，无执行步骤","コメントのみ・実行手順なし")
    row?.status=="UNDONE"->word("建议曾执行，现已撤销","提案を実行後、取消済み")
    row?.status=="EXECUTED"->word("建议已执行；后续办理状态见详情","提案を実行済み。後続処理の状態は詳細へ")
    row?.status=="REJECTED"->word("未执行建议；评语已保存","提案は実行せず、コメントを保存済み")
    else->word("评语已保存，建议尚未执行","コメント保存済み・提案は未実行")
}
