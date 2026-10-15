package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun RefundForm(s:BookSnapshot,d:EntryDraft,onChange:(EntryDraft)->Unit,onSave:()->Unit,
    busy:Boolean,onTheme:()->Unit,onBooks:()->Unit,onCancel:()->Unit,mediaContent:@Composable ()->Unit={}) {
    val original=s.visibleEvents.find{it.id==d.originalEventId}
    if(original==null){YText(word("原消费已被删除，请先恢复。","元の支出を復元してください。"));YButton(word("返回","戻る"),onCancel);return}
    val account=s.accounts.find{it.id==d.accountId}
    EntryTools(s.book.name,onBooks,onTheme)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        RefundSource(s,original)
        YField(word("商户退回原币金额","お店が返金した原通貨額")+" · "+original.currency,d.amount,{onChange(d.copy(amount=it))},numeric=true)
        YSelect(word("实际退款到账账户","実際の返金口座"),d.accountId,s.accounts.filter{it.active||it.id==d.accountId}.map{it.id as String? to it.name},{id->
            val next=s.accounts.first{it.id==id}
            onChange(d.copy(accountId=id,accountAmount=if(next.currency==account?.currency)d.accountAmount else "",baseAmount=if(next.currency==account?.currency)d.baseAmount else ""))
        })
        YField(word("账户实际退款 / 待到账可填预计","口座の実返金額・未入金なら見込み")+" · "+(account?.currency?:"—"),d.accountAmount,{onChange(d.copy(accountAmount=it))},numeric=true)
        YField(word("原消费月冲减金额","元の購入月から控除する額")+" · "+s.book.currency,
            if(account?.currency==s.book.currency)d.accountAmount else d.baseAmount,
            {onChange(d.copy(baseAmount=it))},numeric=true,readOnly=account?.currency==s.book.currency)
        if(account?.currency==s.book.currency)YText(word("与到账金额相同，无需折算。","入金額と同額で、換算は不要です。"),11,color=LocalYohaku.current.muted)
        var owner by remember{mutableStateOf<Int?>(null)}
        YDateFields(listOf(word("实际 / 预计到账日期","実際・予定入金日") to d.date),owner,{owner=it},{_,date->onChange(d.copy(date=date,settlementDate=date,rateDate=date))})
        YSelect(word("退款状态","返金の状態"),if(d.settlementConfirmed)"confirmed" else "pending",listOf(
            "confirmed" as String? to word("已实际到账","実際に入金済"),"pending" as String? to word("待到账，不提前冲减消费","未入金・支出をまだ控除しない")
        ),{onChange(d.copy(settlementConfirmed=it=="confirmed"))})
        YText(word("沿用原分类与活动。按实际退款冲减原消费月，不增加到账月预算，不把汇差再记为到账月费用。","元のカテゴリ・シーンを保持。実返金額を元の購入月から控除し、入金月の予算は増やさず差額費用を重複計上しません。"),if(LocalYohaku.current.id=="journal")14 else 11,Modifier.padding(vertical=15.dp).testTag("refund-guidance"),color=LocalYohaku.current.muted,lineHeightMultiplier=2f,cssLineBox=true)
        YField(word("备注","メモ"),d.note,{onChange(d.copy(note=it))})
        mediaContent()
        YButton(word("预览退款变化","返金の変化を確認"),onSave,primary=true,enabled=!busy&&account!=null)
        YLink(word("取消修改并返回","変更を破棄して戻る"),onCancel)
    }
}

@Composable internal fun EntryReviewSheet(review:LedgerChangeReview,refreshed:Boolean,busy:Boolean,onConfirm:()->Unit,onBack:()->Unit) {
    val e=review.change.event
    val occurrence=review.snapshot.occurrences.firstOrNull{it.id==review.change.occurrenceId}
    YSheet(if(e.kind==EntryKind.REFUND.name)word("退款变化预览","返金の変化を確認")else if(e.kind==EntryKind.TRANSFER.name)word("转账变化预览","振替の変化を確認")else word("保存前，再看一次。","保存する前に、もう一度。"),onBack) {
        if(refreshed)YText(word("账务资料已变化。以下是重新计算的影响，请再次确认。","記録が変わりました。再計算した影響をもう一度確認してください。"),12,color=LocalYohaku.current.warm)
        ThemeAuditPanel(e.merchant.ifBlank{if(e.kind==EntryKind.TRANSFER.name)word("账户转账","口座振替")else word("这一笔","この一筆")}) {
            val transfer=review.change.transfer
            if(transfer!=null) {
                TransferFacts(review.snapshot,e,transfer,review.change.components,review.change.settlement.expectedMoney())
            }else RecordFacts(listOf(
                word("原币金额","原通貨額") to Money(Currency.valueOf(e.currency),e.amountMinor).display(),
                (if(review.change.settlement.confirmed)word("实际账户金额","口座の実額")else word("预计账户金额","口座の見込額")) to Money(Currency.valueOf(review.change.settlement.accountCurrency),review.change.settlement.accountMinor).display(),
            )+(if(occurrence!=null)listOf(word("原周期预计","元の定期見込み") to (occurrence.amountMinor?.let{Money(Currency.valueOf(occurrence.currency),it).display()}?:word("金额未定","金額未定")))
                else if(review.change.before!=null||!review.change.settlement.confirmed)listOf(word("原预计入账","元の計上見込み") to review.change.settlement.expectedMoney().display())else emptyList())+
                listOf(word("入账日期","計上日") to review.change.settlement.settlementDate),audit=true)
        }
        if(review.originalNetBefore!=null&&review.originalNetAfter!=null)RecordFacts(listOf(word("原消费剩余净支出","元の購入の純支出残額") to
            (Money(Currency.valueOf(review.snapshot.book.currency),review.originalNetBefore).display()+" → "+Money(Currency.valueOf(review.snapshot.book.currency),review.originalNetAfter).display())),audit=true)
        review.change.treat?.let{change->
            ThemeSection(word("这次互请的份额","今回のごちそうの取り分"),"SHARED PORTIONS")
            change.allocations.forEach{a->YText((if(a.participant=="ME")word("本人","自分")else review.snapshot.friends.first{it.id==a.friendId}.name)+" · "+Money(Currency.valueOf(e.currency),a.amountMinor).display(),14)}

        }
        review.change.reserveUses.forEach{use->YText(word("使用预留：","確保を使用：")+review.snapshot.reserves.first{it.id==use.reserveId}.name+" · "+Money(Currency.valueOf(review.snapshot.book.currency),use.amountMinor).display(),13)}
        if(e.kind==EntryKind.TRANSFER.name)YText(word("转账本金不计收支，只有明确且实际扣收的费用参与消费。","振替元金は収支に含めず、明確で引落済みの費用だけ支出に含めます。"),11,color=LocalYohaku.current.muted)
        review.impacts.forEach{impact->EntryImpactPreview(review.snapshot,e,impact,java.time.YearMonth.now().toString())}
        if(e.kind==EntryKind.REFUND.name)YText(word("到账月份预算不会因退款自动增加。","返金によって入金月の予算は自動で増えません。"),11,color=LocalYohaku.current.muted)
        YButton(word("确认并保存","確認して保存"),onConfirm,primary=true,enabled=!busy)
        YLink(word("返回表单继续编辑","フォームに戻って編集"),onBack)
    }
}
