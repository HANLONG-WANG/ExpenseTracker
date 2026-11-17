package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import com.yohaku.expensetracker.ui.haptics.Haptic
import java.time.LocalDate

@Composable internal fun StatementDifferenceSheet(s:BookSnapshot,p:StatementReviewProjection,all:List<StatementReviewProjection>,row:StatementRow,
    vm:AppViewModel,onClose:()->Unit,onActual:(String,String,String)->Unit,onRepayment:(EventEntity)->Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val manual=p.decisions.find{it.saved.rowId==row.row_id}
    val occupied=all.flatMap{other->other.result.exact.filterNot{other.batch.id==p.batch.id&&it.rowId==row.row_id}.map{it.postingId}+
        other.decisions.filter{it.active&&!(other.batch.id==p.batch.id&&it.saved.rowId==row.row_id)}.map{it.saved.postingId}}.toSet()
    val candidates=s.reconciliationPostings().filter{it.accountId==p.file.account_id&&it.currency==row.settlement.currency&&it.direction==row.direction&&it.postingId !in occupied}
    var selected by rememberSaveable(p.batch.id,row.row_id){mutableStateOf(manual?.saved?.postingId?.takeIf{id->candidates.any{it.postingId==id}})}
    var note by rememberSaveable(p.batch.id,row.row_id){mutableStateOf(manual?.saved?.note.orEmpty())}
    var review by remember(p.batch.id,row.row_id){mutableStateOf<StatementDifferenceReview?>(null)}
    val knownPosted=row.posted_date?:row.date.takeIf{p.file.date_basis=="posted_date"}
    var posted by rememberSaveable(p.batch.id,row.row_id){mutableStateOf(knownPosted?:LocalDate.now(vm.repository.clock).toString())}
    var confirmedDate by rememberSaveable(p.batch.id,row.row_id){mutableStateOf(knownPosted!=null)}
    var dateOwner by remember{mutableStateOf<YDateSelection?>(null)}
    YSheet(word("待确认差异","差異の確認"),{if(!busy)onClose()}) {
        ThemeAuditPanel(word("外部流水","外部明細")) {
            RecordFacts(listOf(word("日期","日付") to row.date,word("方向","方向") to if(row.direction=="in")word("流入","入金")else word("流出","出金"),
                word("实际入账金额","実計上額") to (receiptCurrencyText(row.settlement.amount,row.settlement.currency))))
            YText(row.summary,12)
            row.original?.let{YText(word("原币辅助资料：","元通貨の補足：")+receiptCurrencyText(it.amount,it.currency),11,color=LocalYohaku.current.muted)}
            row.fees?.let{YText(word("文件手续费（辅助）：","明細手数料（補足）：")+receiptCurrencyText(it.amount,it.currency),11,color=LocalYohaku.current.muted)}
            row.exchange_rate?.let{YText(word("文件汇率（辅助）：","明細レート（補足）：")+it,11,color=LocalYohaku.current.muted)}
            row.note?.let{YText(it,11,color=LocalYohaku.current.muted)}
            row.event_hint?.let{hint->
                val label=when(hint){"expense"->word("消费","支出");"income"->word("收入","収入");"transfer"->word("转账","振替");"repayment"->word("还款","返済");"refund"->word("退款","返金");else->word("借入","借入")}
                YText(word("转换辅助类型：","変換の補助種別：")+label,11,color=LocalYohaku.current.muted)
            }
        }
        if(manual!=null) {
            YText(if(manual.active)word("已确认差异。","差異確認済み。")else word("记账已改动或删除，需重新核对。","記録が変更・削除されたため再照合が必要です。"),12,color=LocalYohaku.current.warm)
            RecordFacts(listOf(word("原决定依据","旧判断の根拠") to "${manual.original.postedDate} · ${manual.original.currency} ${manual.original.amount}",
                word("当前依据","現在の根拠") to (manual.current?.let{"${it.postedDate} · ${it.currency} ${it.amount}"}?:word("已不存在","存在しません"))))
            if(manual.saved.note.isNotBlank())YText(manual.saved.note,12)
            YButton(word("解除此人工关联","この手動関連を解除"),{vm.execute(onSuccess=onClose){vm.repository.clearStatementDecision(s.book.id,p.batch.id,row.row_id);vm.haptic(Haptic.Removed)}},Modifier.testTag("statement-unlink-difference"),enabled=!busy)
        }
        val currentReview=review
        if(currentReview!=null) {
            ThemeAuditPanel(word("确认差异，不改账","差異を確認・記録額は保持")) {
                val posting=currentReview.posting
                RecordFacts(listOf(word("记账实际金额","記録の実金額") to receiptCurrencyText(posting.amount,posting.currency),
                    word("记账入账日","記録の計上日") to posting.postedDate,word("记账发生日","記録の利用日") to posting.occurredDate))
                Spacer(Modifier.height(16.dp))
                YActionRow {
                    YButton(word("确认保留差异","差異を保持して確定"),{vm.execute(onSuccess=onClose){vm.repository.acceptStatementDifference(currentReview);vm.haptic(Haptic.Done)}},Modifier.testTag("statement-confirm-difference"),primary=true,enabled=!busy)
                    YButton(word("返回选择","候補に戻る"),{review=null},enabled=!busy)
                }
            }
        }else {
            YSelect(word("实际资金端候选","実際の資金側の候補"),selected,listOf(null to word("请选择","選択してください"))+candidates.map{post->post.postingId as String? to
                (post.postedDate+" · "+receiptCurrencyText(post.amount.toPlainString(),post.currency)+" · "+s.visibleEvents.first{it.id==post.eventId}.merchant)},{selected=it},Modifier.testTag("statement-posting-candidate"))
            YField(word("差异说明","差異のメモ"),note,{note=it},multiline=true)
            YButton(word("预览人工差异决定","手動の差異判断を確認"),{var r:StatementDifferenceReview?=null;vm.execute(onSuccess={review=r}){r=vm.repository.previewStatementDifference(s.book.id,p.batch.id,row.row_id,selected!!,note)}},Modifier.fillMaxWidth().testTag("statement-preview-difference"),primary=true,enabled=!busy&&selected!=null)
        }
        val expected=s.storedPostings.filter{!it.confirmed&&it.accountId==p.file.account_id&&it.deltaMinor!=0L}.filter { post->
            val account=s.accounts.first{it.id==post.accountId}
            val out=if(AccountType.valueOf(account.type).debt)post.deltaMinor>0 else post.deltaMinor<0
            (if(out)"out"else "in")==row.direction
        }
        val pending=expected.filter{post->s.visibleEvents.any{it.id==post.eventId&&it.kind in listOf(EntryKind.EXPENSE.name,EntryKind.INCOME.name,EntryKind.REFUND.name,EntryKind.TRANSFER.name)}}
        val pendingRepayments=s.repayments.filter{it.debtAccountId==p.file.account_id&&!it.platformConfirmed}.mapNotNull{r->s.visibleEvents.find{it.id==r.eventId}}
        if(pending.isNotEmpty()||pendingRepayments.isNotEmpty()) {
            ThemeSection(word("预计资金端，实际入账待确认","見込みの資金側・実計上未確認"),"ACTUAL SETTLEMENT")
            if(knownPosted==null) {
                YText(word("流水里没有入账日，请选择。","明細に計上日がありません。選んでください。"),12,color=LocalYohaku.current.warm)
                YDateFields(listOf(YDateField("posted",word("实际入账日","実計上日"),posted)),dateOwner,{dateOwner=it},{_,value->posted=value})
                YToggle(word("已确认实际入账日","実計上日を確認済み"),confirmedDate,{confirmedDate=it})
            }
            pendingRepayments.forEach{event->YButton(word("查看原还款，确认平台清偿","元の支払を見て返済先を確定"),{onRepayment(event)},Modifier.testTag("statement-confirm-clearing-${event.id}"),enabled=!busy)}
            pending.forEach { post->
                val event=s.visibleEvents.first{it.id==post.eventId}
                val fee=post.id==event.id+":fee"
                YText(post.date+" · "+event.merchant+" · "+(if(fee)word("独立手续费","別途手数料")else word("尚未确认实际","実計上未確認"))+" · "+receiptCurrencyText(Money(Currency.valueOf(post.currency),kotlin.math.abs(post.deltaMinor)).decimal.toPlainString(),post.currency),12)
                YButton(word("进入原记录，预览实际确认","元記録で実計上を確認"),{onActual(event.id,posted,post.id)},Modifier.testTag("statement-confirm-actual-${event.id}"+if(fee)"-fee"else""),enabled=!busy&&confirmedDate)
            }
        }
    }
}

@Composable internal fun StatementSupplementSheet(s:BookSnapshot,file:StatementFile,row:StatementRow,vm:AppViewModel,
    onClose:()->Unit,onStart:(StatementSupplementSeed)->Unit,onFinancial:(String,String)->Unit) {
    val account=s.accounts.first{it.id==file.account_id}
    val debt=AccountType.valueOf(account.type).debt
    var kind by rememberSaveable(row.row_id){mutableStateOf<String?>(null)}
    var original by rememberSaveable(row.row_id){mutableStateOf<String?>(null)}
    var debtAccount by rememberSaveable(row.row_id){mutableStateOf(account.id.takeIf{debt})}
    val knownOccurred=row.occurred_date?:row.date.takeIf{file.date_basis=="occurred_date"}
    val knownPosted=row.posted_date?:row.date.takeIf{file.date_basis=="posted_date"}
    var occurred by rememberSaveable(row.row_id){mutableStateOf(knownOccurred?:LocalDate.now(vm.repository.clock).toString())}
    var posted by rememberSaveable(row.row_id){mutableStateOf(knownPosted?:LocalDate.now(vm.repository.clock).toString())}
    var dateConfirmed by rememberSaveable(row.row_id){mutableStateOf(knownOccurred!=null&&knownPosted!=null)}
    var owner by remember{mutableStateOf<YDateSelection?>(null)}
    val options=buildList<Pair<String?,String>> {
        add(null to word("请选择类型","種類を選択"))
        if(row.direction=="out") {
            add(EntryKind.EXPENSE.name to word("消费支出","消費支出"))
            if(account.type!=AccountType.LOAN.name)add(EntryKind.TRANSFER.name to word("账户转账 / 换汇","口座振替・両替"))
            if(!debt)add(EntryKind.REPAYMENT.name to word("偿还信用卡 / 借款","カード・借入返済"))
            if(account.type==AccountType.LOAN.name)add(EntryKind.BORROWING.name to word("借入，非收入","借入・収入ではありません"))
        }else {
            if(!debt){add(EntryKind.INCOME.name to word("实际收入","実際の収入"));add(EntryKind.TRANSFER.name to word("账户转账 / 换汇","口座振替・両替"))}
            if(debt)add(EntryKind.REPAYMENT.name to word("偿还信用卡 / 借款","カード・借入返済"))
            add(EntryKind.REFUND.name to word("原消费的退款","元購入の返金"))
        }
    }
    YSheet(word("先选补记类型","記録の種類を選択"),onClose) {
        YText(row.date+" · "+receiptCurrencyText(row.settlement.amount,row.settlement.currency)+" · "+row.summary,13)
        YSelect(word("补记类型","記録の種類"),kind,options,{if(kind!=it){kind=it;original=null;debtAccount=account.id.takeIf{debt}}},Modifier.testTag("statement-supplement-kind"))
        if(kind==EntryKind.REPAYMENT.name||kind==EntryKind.BORROWING.name) {
            YSelect(word("债务账户","債務口座"),debtAccount,listOf(null to word("请选择","選択してください"))+s.accounts.filter{it.active&&AccountType.valueOf(it.type).debt&&(kind!=EntryKind.BORROWING.name||it.type==AccountType.LOAN.name)}.map{it.id as String? to it.name},{debtAccount=it},columns=2)
            YButton(word("打开正式金融表单","正式な金融フォームを開く"),{onFinancial(kind!!,debtAccount!!)},Modifier.testTag("statement-start-supplement"),primary=true,enabled=debtAccount!=null)
        }else if(kind!=null) {
            if(kind==EntryKind.REFUND.name)YSelect(word("关联原消费","元の支出と関連付け"),original,listOf(null to word("请选择","選択してください"))+s.visibleEvents.filter{it.kind==EntryKind.EXPENSE.name}.map{it.id as String? to (it.date+" · "+it.merchant+" · "+Money(Currency.valueOf(it.currency),it.amountMinor).display())},{original=it})
            YDateFields(listOf(YDateField("occurred",word("交易发生日","利用日"),occurred),YDateField("posted",word("实际入账日","実計上日"),posted)),owner,{owner=it},{i,date->if(i=="occurred")occurred=date else posted=date})
            if(knownOccurred==null||knownPosted==null) {
                YText(word("流水缺少部分日期，请补上。","明細に日付が欠けています。入力してください。"),11,color=LocalYohaku.current.warm)
                YToggle(word("已核对缺少的日期","不足している日付を確認済み"),dateConfirmed,{dateConfirmed=it})
            }
            YButton(word("打开普通记账表单","通常の記録フォームを開く"),{onStart(StatementSupplementSeed(s.book.id,account.id,row,EntryKind.valueOf(kind!!),occurred,posted,original))},Modifier.testTag("statement-start-supplement"),primary=true,enabled=dateConfirmed&&(kind!=EntryKind.REFUND.name||original!=null))
        }
    }
}

@Composable internal fun StatementBillChooser(s:BookSnapshot,p:StatementReviewProjection,graph:FinancialGraph?,onClose:()->Unit,onSelect:(String)->Unit,onManage:()->Unit) {
    val account=s.accounts.first{it.id==p.file.account_id}
    val info=p.file.statement_info!!
    val wrongCurrency=listOfNotNull(info.principal,info.fees,info.total).any{it.currency!=account.currency}
    val plans=graph?.plans?.filter{it.accountId==account.id}?.map{it.id}.orEmpty().toSet()
    val bills=graph?.bills?.filter{it.active&&it.planId in plans}.orEmpty()
    YSheet(word("选择账单补充资料的目标","請求補足資料の対象を選択"),onClose) {
        StatementInfoFacts(info)
        if(wrongCurrency)YText(word("补充资料的币种与账户不同，不能套用。","補足資料の通貨が口座と異なるため適用できません。"),12,color=LocalYohaku.current.warm)
        if(bills.isEmpty())YEmpty(word("还没有可对应的账单，请先在金融账户中建立。","対応できる請求がありません。先に金融口座で作成してください。"))
        bills.forEach { bill->YButton(bill.date+" · "+Money(Currency.valueOf(account.currency),bill.totalMinor).display(),{onSelect(bill.id)},Modifier.testTag("statement-bill-${bill.id}"),enabled=!wrongCurrency,haptic=Haptic.Select) }
        if(AccountType.valueOf(account.type).debt)YLink(word("打开金融账户管理","金融口座の管理を開く"),onManage)
    }
}
