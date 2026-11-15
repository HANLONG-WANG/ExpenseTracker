package com.yohaku.expensetracker.domain.ledger

import com.yohaku.expensetracker.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode

/** Manual or reference quote is applied only to an unconfirmed side. Actual inputs win. */
fun estimateTransfer(d:EntryDraft,from:Currency,to:Currency,direction:String):EntryDraft {
    val rate=if(from==to)BigDecimal.ONE else d.manualRate.toBigDecimalOrNull()?:error("请填写汇率 / レートを入力してください")
    require(rate.signum()>0){"汇率须大于零 / レートは0より大きくしてください"}
    fun fee(currency:Currency):BigDecimal {
        if(d.feeAmount.isBlank())return BigDecimal.ZERO
        val original=d.feeCurrency?:currency
        return Money.parse(if(original==currency)d.feeAmount else d.feeAccountAmount,currency).decimal
    }
    val sourceFee=if(d.feePosition==TransferFeePosition.INCLUDED_FROM)fee(from)else BigDecimal.ZERO
    val targetFee=if(d.feePosition==TransferFeePosition.DEDUCTED_TO)fee(to)else BigDecimal.ZERO
    val value=if(direction=="TO") {
        require(!d.toConfirmed){"实际到账金额已确认，请先改为预计 / 実入金額は確認済み。先に見込みへ変更してください"}
        val principal=Money.parse(d.amount,from).decimal.subtract(sourceFee)
        val incoming=principal.multiply(rate).subtract(targetFee)
        require(incoming.signum()>0){"费用后预计到账须大于零 / 手数料控除後の入金見込みは0より大きくしてください"}
        d.copy(toAmount=incoming.setScale(to.scale,RoundingMode.HALF_UP).toPlainString(),estimateDirection="TO")
    }else {
        require(!d.fromConfirmed){"实际转出金额已确认，请先改为预计 / 実出金額は確認済み。先に見込みへ変更してください"}
        val outgoing=Money.parse(d.toAmount,to).decimal.add(targetFee).divide(rate,12,RoundingMode.HALF_UP).add(sourceFee)
        d.copy(amount=outgoing.setScale(from.scale,RoundingMode.HALF_UP).toPlainString(),estimateDirection="FROM")
    }
    return if(d.referenceRates.any{it.base==from&&it.quote==to})value.copy(referenceExpectedFromMinor=Money.parse(value.amount,from).minor,referenceExpectedToMinor=Money.parse(value.toAmount,to).minor)else value
}

/** Form validation is an explicit result; callers keep the draft unchanged until estimation succeeds. */
sealed interface TransferEstimateResult {
    data class Ready(val draft:EntryDraft):TransferEstimateResult
    data class Incomplete(val message:String):TransferEstimateResult
}

fun transferEstimateResult(d:EntryDraft,from:Currency,to:Currency,direction:String,
    reference:com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot?=null):TransferEstimateResult {
    // A mismatched quote is a programming error, not unfinished user input.
    if(reference!=null)require(reference.base==from&&reference.quote==to)
    return try {
        TransferEstimateResult.Ready(if(reference==null)estimateTransfer(d,from,to,direction)
            else applyReferenceTransfer(d,from,to,reference,direction))
    }catch(e:IllegalArgumentException) {
        TransferEstimateResult.Incomplete(requireNotNull(e.message))
    }catch(e:IllegalStateException) {
        TransferEstimateResult.Incomplete(requireNotNull(e.message))
    }catch(_:ArithmeticException) {
        TransferEstimateResult.Incomplete("请补全金额并核对币种小数位 / 金額を入力し、通貨の小数桁を確認してください")
    }
}
