package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.LedgerDetails
import com.yohaku.expensetracker.data.repository.accountMoney
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.time.format.DateTimeFormatter

private data class Change(val version:Int,val timestamp:Long,val before:EventEntity,val after:EventEntity,val beforeDetails:LedgerDetails?,val afterDetails:LedgerDetails?)
private sealed interface HistoryRead{
    data object Loading:HistoryRead
    data class Ready(val changes:List<Change>):HistoryRead
    data class Failure(val message:String):HistoryRead
}

@Composable internal fun RevisionHistory(s:BookSnapshot,event:EventEntity,vm:AppViewModel){
    var attempt by remember(s.book.id,event.id){mutableIntStateOf(0)}
    val result by remember(s.book.id,event.id,attempt){
        vm.repository.dao.revisions(s.book.id,event.id).map<List<RevisionEntity>,HistoryRead>{rows->
            HistoryRead.Ready(rows.filter{it.beforeJson!=null}.map{r->
                Change(r.version,r.timestamp,vm.repository.json.decodeFromString(r.beforeJson!!),vm.repository.json.decodeFromString(r.afterJson),
                    r.beforeDetailsJson?.let{vm.repository.json.decodeFromString<LedgerDetails>(it)},
                    r.afterDetailsJson?.let{vm.repository.json.decodeFromString<LedgerDetails>(it)})
            })
        }.catch{emit(HistoryRead.Failure(it.message ?: "读取失败 / 読込失敗"))}
    }.collectAsStateWithLifecycle(HistoryRead.Loading)
    when(val state=result){
        HistoryRead.Loading->YText(word("正在读取修改记录…","変更履歴を読み込み中…"),11,color=LocalYohaku.current.muted)
        is HistoryRead.Failure->ThemeAuditPanel(word("修改记录","変更履歴"),Modifier.testTag("revision-error")){
            YText(word("无法读取修改记录","変更履歴を読み込めません"),12)
            YText(state.message,10,color=LocalYohaku.current.muted)
            YButton(word("重新读取","再読み込み"),{attempt++},primary=true)
        }
        is HistoryRead.Ready->if(state.changes.isNotEmpty())ThemeAuditPanel(word("修改记录","変更履歴"),Modifier.testTag("revision-history")){
            state.changes.forEach{change->Column(Modifier.fillMaxWidth().testTag("revision-${change.version}").padding(bottom=12.dp)){
                val b=change.before;val a=change.after
                val date=Instant.ofEpochMilli(change.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                YText(word("第${change.version}版","第${change.version}版")+" · "+date,11,color=LocalYohaku.current.muted)
                val fields=mutableListOf<Pair<String,String>>()
                fun field(label:String,before:String,after:String){fields+=label to (before.ifBlank{"—"}+" → "+after.ifBlank{"—"})}
                if(b.kind!=a.kind)field(word("类型","種類"),entryKindName(EntryKind.valueOf(b.kind)),entryKindName(EntryKind.valueOf(a.kind)))
                if(b.amountMinor!=a.amountMinor||b.currency!=a.currency)field(word("金额","金額"),Money(Currency.valueOf(b.currency),b.amountMinor).display(),Money(Currency.valueOf(a.currency),a.amountMinor).display())
                if(b.baseMinor!=a.baseMinor&&(b.currency!=s.book.currency||a.currency!=s.book.currency))field(word("本位币统计","基準通貨集計"),Money(Currency.valueOf(s.book.currency),b.baseMinor).display(),Money(Currency.valueOf(s.book.currency),a.baseMinor).display())
                if(b.accountId!=a.accountId)field(word("账户","口座"),s.accounts.find{it.id==b.accountId}?.name ?: word("已移除账户","削除済みの口座"),s.accounts.find{it.id==a.accountId}?.name ?: word("已移除账户","削除済みの口座"))
                if(b.categoryId!=a.categoryId)field(word("分类","カテゴリ"),s.path(b.categoryId),s.path(a.categoryId))
                if(b.date!=a.date)field(word("日期","日付"),b.date,a.date)
                if(b.merchant!=a.merchant)field(word("商户","お店"),b.merchant,a.merchant)
                if(b.note!=a.note)field(word("备注","メモ"),b.note,a.note)
                if(b.historicalIncluded!=a.historicalIncluded)field(word("期初包含","期首に含む"),if(b.historicalIncluded)word("已包含","含む") else word("不包含","含まない"),if(a.historicalIncluded)word("已包含","含む") else word("不包含","含まない"))
                val oldAttachments=change.beforeDetails?.attachmentIds.orEmpty();val newAttachments=change.afterDetails?.attachmentIds.orEmpty()
                if(oldAttachments!=newAttachments)field(word("附件列表 / 顺序","添付一覧・順序"),oldAttachments.size.toString(),newAttachments.size.toString())
                if(change.beforeDetails?.receiptRevision!=change.afterDetails?.receiptRevision)field(word("小票原图修订","レシート原画像の版"),change.beforeDetails?.receiptRevision?.toString().orEmpty(),change.afterDetails?.receiptRevision?.toString().orEmpty())
                if(b.cardPaymentChoice!=a.cardPaymentChoice||b.cardPaymentMode!=a.cardPaymentMode||b.cardPaymentPeriods!=a.cardPaymentPeriods) {
                    field(word("记录的付款方式","記録した支払方法"),cardPaymentLabel(b.cardPaymentChoice),cardPaymentLabel(a.cardPaymentChoice))
                    field(word("采用的方式","適用した方式"),cardPaymentLabel(b.cardPaymentMode),cardPaymentLabel(a.cardPaymentMode))
                    field(word("分期次数","分割回数"),b.cardPaymentPeriods?.toString().orEmpty(),a.cardPaymentPeriods?.toString().orEmpty())
                }
                if(b.cardPaymentRuleId!=a.cardPaymentRuleId) {
                    val finance by vm.financialState.collectAsStateWithLifecycle()
                    fun rule(id:String?)=finance?.rules?.firstOrNull{it.id==id}?.let{"v${it.version} · ${it.effectiveFrom}"}.orEmpty()
                    field(word("自动リボ依据版本","自動リボの条件の版"),rule(b.cardPaymentRuleId),rule(a.cardPaymentRuleId))
                }
                val bs=change.beforeDetails?.settlement;val ds=change.afterDetails?.settlement
                if(bs!=null&&ds!=null) {
                    val separateAccountAmount=bs.accountCurrency!=b.currency||ds.accountCurrency!=a.currency||bs.accountMinor!=b.amountMinor||ds.accountMinor!=a.amountMinor
                    if(separateAccountAmount&&(bs.accountMinor!=ds.accountMinor||bs.accountCurrency!=ds.accountCurrency))field(word("账户入账金额","口座の計上額"),bs.accountMoney().display(),ds.accountMoney().display())
                    if(bs.confirmed!=ds.confirmed)field(word("入账状态","計上の状態"),if(bs.confirmed)word("已确认","確認済")else word("预计","見込み"),if(ds.confirmed)word("已确认","確認済")else word("预计","見込み"))
                    if(bs.settlementDate!=ds.settlementDate&&(bs.settlementDate!=b.date||ds.settlementDate!=a.date))field(word("入账日期","計上日"),bs.settlementDate,ds.settlementDate)
                }
                if(bs?.referenceRatesJson!=ds?.referenceRatesJson) {
                    fun summary(value:String?)=value?.let{vm.repository.json.decodeFromString<List<ReferenceRateSnapshot>>(it).joinToString("; "){r->"1 ${r.base.name} = ${r.rate} ${r.quote.name} · Frankfurter v2 / ${r.provider} · ${r.date} · ${r.fetchedAt}"}}.orEmpty()
                    field(word("参考估算依据","参考見積もりの根拠"),summary(bs?.referenceRatesJson),summary(ds?.referenceRatesJson))
                }
                val bt=change.beforeDetails?.transfer;val dt=change.afterDetails?.transfer
                if(bt!=null&&dt!=null) {
                    if(bt.toMinor!=dt.toMinor||bt.toCurrency!=dt.toCurrency)field(word("到账净额","入金純額"),Money(Currency.valueOf(bt.toCurrency),bt.toMinor).display(),Money(Currency.valueOf(dt.toCurrency),dt.toMinor).display())
                    if(bt.toAccountId!=dt.toAccountId)field(word("转入账户","入金口座"),s.accounts.find{it.id==bt.toAccountId}?.name.orEmpty(),s.accounts.find{it.id==dt.toAccountId}?.name.orEmpty())
                    if(bt.toConfirmed!=dt.toConfirmed)field(word("到账状态","入金の状態"),if(bt.toConfirmed)word("已到账","入金済")else word("待到账","未入金"),if(dt.toConfirmed)word("已到账","入金済")else word("待到账","未入金"))
                    if(bt.toDate!=dt.toDate)field(word("到账日期","入金日"),bt.toDate,dt.toDate)
                }
                val beforeFees=change.beforeDetails?.components.orEmpty();val afterFees=change.afterDetails?.components.orEmpty()
                val unknown=word("未知","不明")
                fun feeSummary(values:List<ExpenseComponentEntity>)=values.joinToString("; "){Money(Currency.valueOf(it.originalCurrency),it.originalMinor).display()+" · "+it.date+if(it.originalCurrency!=s.book.currency)" ("+Money(Currency.valueOf(s.book.currency),it.baseMinor).display()+")"else ""}
                for(position in (beforeFees+afterFees).map{it.position}.distinct()) {
                    val before=beforeFees.filter{it.position==position};val after=afterFees.filter{it.position==position}
                    if(before!=after)field(financialFeePositionName(position),feeSummary(before),feeSummary(after))
                }
                val br=change.beforeDetails?.repayment;val dr=change.afterDetails?.repayment
                if(dr!=null) {
                    val currency=s.accounts.first{it.id==dr.debtAccountId}.currency
                    fun repaid(value:Long?)=value?.let{Money(Currency.valueOf(currency),it).display()}?:unknown
                    if(br?.debtAccountId!=dr.debtAccountId)field(word("还款目标","返済先"),s.accounts.firstOrNull{it.id==br?.debtAccountId}?.name.orEmpty(),s.accounts.first{it.id==dr.debtAccountId}.name)
                    if(br?.platformConfirmed!=dr.platformConfirmed)field(word("平台清偿确认","返済先の返済確定"),if(br?.platformConfirmed==true)word("已确认","確定済み")else word("未确认","未確定"),if(dr.platformConfirmed)word("已确认","確定済み")else word("未确认","未確定"))
                    if(br?.receivedMinor!=dr.receivedMinor)field(word("平台实收","返済先受取額"),repaid(br?.receivedMinor),repaid(dr.receivedMinor))
                    if(br?.principalMinor!=dr.principalMinor)field(word("清偿本金","返済元金"),repaid(br?.principalMinor),repaid(dr.principalMinor))
                    if(br?.interestMinor!=dr.interestMinor)field(word("实付利息","実支払利息"),repaid(br?.interestMinor),repaid(dr.interestMinor))
                    if(br?.feeMinor!=dr.feeMinor)field(word("平台费用","返済先手数料"),repaid(br?.feeMinor),repaid(dr.feeMinor))
                    if(br?.clearingDate!=dr.clearingDate)field(word("清偿日期","返済確定日"),br?.clearingDate?:unknown,dr.clearingDate?:unknown)
                    if(br?.paymentType!=dr.paymentType)field(word("还款类型","返済種別"),br?.let{repaymentTypeLabel(com.yohaku.expensetracker.domain.finance.RepaymentType.valueOf(it.paymentType))}.orEmpty(),repaymentTypeLabel(com.yohaku.expensetracker.domain.finance.RepaymentType.valueOf(dr.paymentType)))
                    fun allocationSummary(rows:List<FinancialRepaymentAllocationEntity>)=rows.joinToString("; "){listOfNotNull(it.billId,it.contractId).joinToString(" / "){id->s.contracts.firstOrNull{c->c.id==id}?.name?:id.take(8)}+" · "+listOf(it.principalMinor,it.interestMinor,it.feeMinor).joinToString(" / "){n->repaid(n)}}
                    if(change.beforeDetails?.repaymentAllocations!=change.afterDetails?.repaymentAllocations)field(word("清偿分配：本金 / 利息 / 费用","返済充当：元金・利息・手数料"),allocationSummary(change.beforeDetails?.repaymentAllocations.orEmpty()),allocationSummary(change.afterDetails?.repaymentAllocations.orEmpty()))
                }
                val bc=change.beforeDetails?.contract;val dc=change.afterDetails?.contract
                if(bc!=null&&dc!=null) {
                    if(bc.name!=dc.name)field(word("合同名称","契約名"),bc.name,dc.name)
                    if(bc.mode!=dc.mode)field(word("借款模式","借入方式"),financeLabel(bc.mode),financeLabel(dc.mode))
                    if(bc.fundingAccountId!=dc.fundingAccountId)field(word("实际到账账户","実際の入金先口座"),s.accounts.firstOrNull{it.id==bc.fundingAccountId}?.name.orEmpty(),s.accounts.firstOrNull{it.id==dc.fundingAccountId}?.name.orEmpty())
                    if(bc.receivedMinor!=dc.receivedMinor)field(word("实际到账","実入金額"),bc.receivedMinor?.let{Money(Currency.valueOf(s.accounts.first{a->a.id==bc.fundingAccountId}.currency),it).display()}?:unknown,dc.receivedMinor?.let{Money(Currency.valueOf(s.accounts.first{a->a.id==dc.fundingAccountId}.currency),it).display()}?:unknown)
                }
                RecordFacts(fields,audit=true)
            }}
        }
    }
}

@Composable private fun financialFeePositionName(position:String):String=when(position) {
    "WITHHELD"->word("预扣费用","天引手数料")
    "INCLUDED_PAYMENT"->word("还款内含利息及平台费用","返済に含む利息・返済先手数料")
    else->feePositionName(TransferFeePosition.valueOf(position))
}
