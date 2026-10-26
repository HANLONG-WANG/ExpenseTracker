package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.ledger.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun TransferForm(s:BookSnapshot,d:EntryDraft,vm:AppViewModel,busy:Boolean,onAccount:()->Unit,onCancel:()->Unit) {
    val eligible=s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}
    val from=eligible.find{it.id==d.accountId};val to=eligible.find{it.id==d.toAccountId}
    val source=Currency.valueOf(from?.currency?:s.book.currency);val target=Currency.valueOf(to?.currency?:s.book.currency)
    val different=source!=target
    val knownPair=from!=null&&to!=null
    var owner by remember{mutableStateOf<Int?>(null)}
    val currentFeeCurrency=Currency.valueOf(when(d.feePosition){TransferFeePosition.INCLUDED_FROM->source.name;TransferFeePosition.DEDUCTED_TO->target.name;TransferFeePosition.SEPARATE->s.accounts.find{it.id==d.feeAccountId}?.currency?:source.name})
    val preservedFeeCurrency=if(d.feeAmount.isBlank())d.feeCurrency else d.feeCurrency?:currentFeeCurrency
    fun change(value:EntryDraft)=vm.changeEntry(s.book.id,value)
    fun estimate(direction:String) {
        var value=d
        vm.execute(onSuccess={change(value)}){value=estimateTransfer(d,source,target,direction)}
    }
    TransferPair(from?.currency?:"—",to?.currency?:"—")
    Column(Modifier.fillMaxWidth().testTag("transfer-form"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        YSelect(word("转出账户","出金口座"),d.accountId,eligible.map{it.id as String? to it.name},{id->
            val account=eligible.first{it.id==id};val currencyChanged=account.currency!=from?.currency
            change(if(currencyChanged)d.copy(accountId=id,amount="",manualRate="",rateSource="manual",referenceExpectedFromMinor=null,referenceExpectedToMinor=null,referenceExpectedAccountMinor=null,referenceExpectedBaseMinor=null,referenceRates=emptyList(),fromConfirmed=false,
                feeCurrency=preservedFeeCurrency,feeAccountAmount=if(d.feePosition==TransferFeePosition.INCLUDED_FROM)""else d.feeAccountAmount,
                feeBaseAmount=if(d.feePosition==TransferFeePosition.INCLUDED_FROM)""else d.feeBaseAmount,
                toAccountId=if(id==d.toAccountId)null else d.toAccountId,toAmount=if(id==d.toAccountId)""else d.toAmount,toConfirmed=if(id==d.toAccountId)false else d.toConfirmed)
                else d.copy(accountId=id,toAccountId=if(id==d.toAccountId)null else d.toAccountId,toAmount=if(id==d.toAccountId)""else d.toAmount,toConfirmed=if(id==d.toAccountId)false else d.toConfirmed))
        })
        YSelect(word("转入账户","入金口座"),d.toAccountId,eligible.filter{it.id!=from?.id}.map{it.id as String? to it.name},{id->
            val account=eligible.first{it.id==id};val currencyChanged=account.currency!=to?.currency
            change(if(currencyChanged)d.copy(toAccountId=id,toAmount="",manualRate="",rateSource="manual",referenceExpectedFromMinor=null,referenceExpectedToMinor=null,referenceExpectedAccountMinor=null,referenceExpectedBaseMinor=null,referenceRates=emptyList(),toConfirmed=false,
                feeCurrency=preservedFeeCurrency,feeAccountAmount=if(d.feePosition==TransferFeePosition.DEDUCTED_TO)""else d.feeAccountAmount,feeBaseAmount=if(d.feePosition==TransferFeePosition.DEDUCTED_TO)""else d.feeBaseAmount)else d.copy(toAccountId=id))
        })
        if(eligible.size<2)YButton(word("添加资金账户","資金口座を追加"),onAccount,primary=true)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            YField(word("实际扣款总额","実際の出金総額")+" · "+(from?.currency?:"—"),d.amount,{if(knownPair)vm.changeTransferAmount(s.book.id,d.copy(amount=it),source,target,"TO")else change(d.copy(amount=it))},Modifier.weight(1f),numeric=true,compactNumber=true,
                displayLabel=(if(d.fromConfirmed)word("实际扣款总额","実際の出金総額")else word("预计扣款总额","出金総額の見込み"))+" · "+(from?.currency?:"—"))
            YField(word("实际到账净额","実際の入金純額")+" · "+(to?.currency?:"—"),d.toAmount,{if(knownPair)vm.changeTransferAmount(s.book.id,d.copy(toAmount=it),source,target,"FROM")else change(d.copy(toAmount=it))},Modifier.weight(1f),numeric=true,compactNumber=true,
                displayLabel=(if(d.toConfirmed)word("实际到账净额","実際の入金純額")else word("预计到账净额","入金純額の見込み"))+" · "+(to?.currency?:"—"))
        }
        if(knownPair&&different) {
            val referenceDirection=if(d.toConfirmed&&!d.fromConfirmed)"FROM"else if(d.fromConfirmed&&!d.toConfirmed)"TO"else d.estimateDirection
            ReferenceRatePanel(vm.referenceRates,source,target,d.referenceQueryDate?:d.date,enabled=!(d.fromConfirmed&&d.toConfirmed),
                autoKey=d.amount+"/"+d.toAmount+"/"+d.manualRate+"/"+d.fromConfirmed+"/"+d.toConfirmed,
                onAuto={q->if(d.id==null&&d.manualRate.isBlank()) {
                    if(!d.toConfirmed&&d.toAmount.isBlank()&&runCatching{Money.parse(d.amount,source).minor>0}.getOrDefault(false))change(applyReferenceTransfer(d,source,target,q,"TO"))
                    else if(!d.fromConfirmed&&d.amount.isBlank()&&runCatching{Money.parse(d.toAmount,target).minor>0}.getOrDefault(false))change(applyReferenceTransfer(d,source,target,q,"FROM"))
                }},onUse={q->change(applyReferenceTransfer(d,source,target,q,referenceDirection))},
                useLabel=if(referenceDirection=="TO")word("按参考值估算到账额","参考値で入金額を見積もる")else word("按参考值反推转出额","参考値で出金額を逆算"))
            CurrencyEntryNote {
                YField(word("手填汇率","手入力レート")+" · 1 ${source.name} = ${target.name}",d.manualRate,{change(d.copy(manualRate=it,rateSource="manual",rateDate=d.referenceQueryDate?:d.date,referenceExpectedFromMinor=null,referenceExpectedToMinor=null,referenceRates=emptyList()))},numeric=true)
                YText(word("手填汇率只联动尚未确认的一端。实际金额不被估算覆盖。","手入力レートは未確認の側だけ連動。実額は見積もりで上書きしません。"),11,color=LocalYohaku.current.muted)
                val ratio=runCatching{exchangeRate(Money.parse(d.amount,source),Money.parse(d.toAmount,target))}.getOrNull()
                YText((if(d.fromConfirmed&&d.toConfirmed)word("综合到账汇率","総合入金レート")else word("预计两端比率","両側の金額の見込み比率"))+" · "+(ratio?.let{"1 ${source.name} = ${it.toPlainString()} ${target.name}"}?:"—"),16,number=true)
                YText(word("由两端金额反算，可能含费用影响，不推断手续费。","両側の金額から算出。費用の影響を含む場合がありますが、手数料とは断定しません。"),10,color=LocalYohaku.current.muted)
            }
        }else YText(if(knownPair)word("同币种转账，不进行汇率换算。","同じ通貨の振替。レート換算は行いません。")else word("先选择两端账户，再确认币种与金额。","出金・入金口座を選び、通貨と金額を確認してください。"),11,color=LocalYohaku.current.muted)
        TransferEstimateLink(word("估算未确认的到账额","未確認の入金額を見積もる"),{estimate("TO")},enabled=!busy&&!d.toConfirmed&&from!=null&&to!=null)
        TransferEstimateLink(word("反推未确认的转出额","未確認の出金額を逆算"),{estimate("FROM")},enabled=!busy&&!d.fromConfirmed&&from!=null&&to!=null)
        var feeOpen by rememberSaveable(s.book.id,d.id){mutableStateOf(true)}
        YLink((if(feeOpen)"− "else "+ ")+word("手续费 · 避免重复扣款","手数料・二重控除しない"),{feeOpen=!feeOpen})
        if(feeOpen) {
            val payer=when(d.feePosition){TransferFeePosition.INCLUDED_FROM->from;TransferFeePosition.DEDUCTED_TO->to;TransferFeePosition.SEPARATE->s.accounts.find{it.id==d.feeAccountId}}
            val feeCurrency=d.feeCurrency?:Currency.valueOf(payer?.currency?:source.name)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                YField(word("明确收取的手续费 / 可留空","確定手数料・空欄可")+" · "+feeCurrency.name,d.feeAmount,{change(d.copy(feeAmount=it))},Modifier.weight(1f),numeric=true)
                YSelect(word("费用币种","手数料通貨"),feeCurrency.name,Currency.entries.map{it.name as String? to it.name},{change(d.copy(feeCurrency=Currency.valueOf(it!!),feeAccountAmount="",feeBaseAmount=""))},Modifier.weight(1f))
            }
            YSelect(word("手续费如何扣收","手数料の扱い"),d.feePosition.name,TransferFeePosition.entries.map{it.name as String? to feePositionName(it)},{owner=null;change(d.copy(feePosition=TransferFeePosition.valueOf(it!!),feeCurrency=preservedFeeCurrency,feeAccountAmount="",feeBaseAmount=""))})
            if(d.feePosition==TransferFeePosition.SEPARATE)YSelect(word("另扣手续费的账户","別途手数料の口座"),d.feeAccountId,s.accounts.filter{it.active}.map{it.id as String? to it.name},{change(d.copy(feeAccountId=it,feeCurrency=preservedFeeCurrency,feeAccountAmount="",feeBaseAmount=""))})
            if(d.feeAmount.isNotBlank()&&payer!=null) {
                if(feeCurrency.name!=payer.currency)YField(word("费用在账户中的金额","口座通貨での手数料額")+" · "+payer.currency,d.feeAccountAmount,{change(d.copy(feeAccountAmount=it))},numeric=true)
                if(payer.currency!=s.book.currency)YField(word("手续费本位币统计金额","手数料の基準通貨集計額")+" · "+s.book.currency,d.feeBaseAmount,{change(d.copy(feeBaseAmount=it))},numeric=true)
            }
            YText(word("已包含的费用计入支出，但不再次扣余额；没有明确费用时留空，不从汇差猜手续费。","含まれる費用は支出へ、残高から再控除しません。不明なら空欄にし、差額から手数料を推定しません。"),if(LocalYohaku.current.id=="journal")11 else 9,color=LocalYohaku.current.muted)
        }
        val state=when {d.fromConfirmed&&d.toConfirmed->"confirmed";d.fromConfirmed->"inTransit";d.toConfirmed->"received";else->"planned"}
        YSelect(word("资金状态","資金の状態"),state,listOf(
            "confirmed" as String? to word("已转出，已到账","出金・入金済"),"inTransit" as String? to word("已转出，尚在途中","出金済・送金中"),
            "planned" as String? to word("只是计划，未转出","予定のみ・未出金"),"received" as String? to word("已到账，转出待确认","入金済・出金確認待ち")
        ),{change(d.copy(fromConfirmed=it in listOf("confirmed","inTransit"),toConfirmed=it in listOf("confirmed","received")))})
        val dates=listOf(word("转出日期","出金日") to d.date,(if(d.toConfirmed)word("实际到账日期","実際の入金日")else word("预计到账日期","入金予定日")) to d.toDate)+
            (if(d.feePosition==TransferFeePosition.SEPARATE)listOf(word("手续费扣收日期","手数料の引落日") to d.feeDate)else emptyList())+if(different)listOf(word("参考汇率查询日期","参考レートの照会日") to (d.referenceQueryDate?:d.date))else emptyList()
        YDateFields(dates,owner,{owner=it},{index,value->change(when{index==0->d.copy(date=value);index==1->d.copy(toDate=value);d.feePosition==TransferFeePosition.SEPARATE&&index==2->d.copy(feeDate=value);else->d.copy(referenceQueryDate=value)})})
        if(d.feePosition==TransferFeePosition.SEPARATE)YToggle(word("手续费已经实际扣收","手数料は引落済み"),d.feeConfirmed,{change(d.copy(feeConfirmed=it))})
        YField(word("名称 / 说明","名称 / 内容"),d.merchant,{change(d.copy(merchant=it))})
        YField(word("备注","メモ"),d.note,{change(d.copy(note=it))})
        TransferStateNote(d.fromConfirmed,d.toConfirmed)
        YText(word("只记录账务，不向银行提交转账。转账本金不计消费，两端按各自实际确认更新。","記録のみで銀行に送金指示しません。元金は支出に含めず、各側の実際の確認で残高を更新します。"),11,color=LocalYohaku.current.muted)
        EntryMediaPanel(s,d,vm)
        YButton(if(d.id==null)word("保存这笔转账","この振替を保存")else word("保存转账修改","振替の変更を保存"),{vm.saveEntry(s.book.id)},primary=true,enabled=!busy&&from!=null&&to!=null)
        YLink(word("取消修改并返回","変更を破棄して戻る"),onCancel)
    }
}

@Composable internal fun feePositionName(position:TransferFeePosition)=when(position){
    TransferFeePosition.INCLUDED_FROM->word("已包含在转出总额","出金総額に含む")
    TransferFeePosition.DEDUCTED_TO->word("已从到账金额扣除","入金額から控除済")
    TransferFeePosition.SEPARATE->word("另行扣收","別途引落")
}

@Composable internal fun TransferFacts(s:BookSnapshot,e:EventEntity,transfer:TransferEntity,components:List<ExpenseComponentEntity>,expectedFrom:Money?=null) {
    val facts=listOf(
        word("转出账户 / 金额","出金口座・金額") to (s.accounts.find{it.id==e.accountId}?.name.orEmpty()+" · "+Money(Currency.valueOf(transfer.fromCurrency),e.amountMinor).display()),
        word("转入账户 / 净额","入金口座・純額") to (s.accounts.find{it.id==transfer.toAccountId}?.name.orEmpty()+" · "+Money(Currency.valueOf(transfer.toCurrency),transfer.toMinor).display()),
        word("转出状态 / 日期","出金の状態・日付") to ((if(transfer.fromConfirmed)word("已确认","確認済")else word("未确认","未確認"))+" · "+transfer.fromDate),
        word("到账状态 / 日期","入金の状態・日付") to ((if(transfer.toConfirmed)word("已到账","入金済")else word("待到账","未入金"))+" · "+transfer.toDate),
        word("原预计到账","元の入金見込み") to Money(Currency.valueOf(transfer.expectedToCurrency),transfer.expectedToMinor).display()
    )+components.flatMap{c->listOf(
        word("明确手续费","確定手数料") to Money(Currency.valueOf(c.originalCurrency),c.originalMinor).display(),
        word("账户费用 / 本位币费用","口座通貨・基準通貨の手数料") to (Money(Currency.valueOf(c.currency),c.amountMinor).display()+" / "+Money(Currency.valueOf(s.book.currency),c.baseMinor).display()),
        word("扣收方式","手数料の扱い") to feePositionName(TransferFeePosition.valueOf(c.position)),
        word("扣收状态 / 日期","手数料の状態・日付") to ((if(c.confirmed)word("已实际扣收","引落済")else word("未实际扣收","未引落"))+" · "+c.date)
    )}+listOfNotNull(expectedFrom?.let{word("原预计转出","元の出金見込み") to it.display()})
    RecordFacts(facts,audit=true)
}
