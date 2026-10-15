package com.yohaku.expensetracker.domain.ledger

import com.yohaku.expensetracker.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode

/** Manual or reference quote is applied only to an unconfirmed side. Actual inputs win. */
fun estimateTransfer(d:EntryDraft,from:Currency,to:Currency,direction:String):EntryDraft {
    val rate=if(from==to)BigDecimal.ONE else d.manualRate.toBigDecimalOrNull()?:error("请填写汇率 / レートを入力してください")
    require(rate.signum()>0)
    fun fee(currency:Currency):BigDecimal {
        if(d.feeAmount.isBlank())return BigDecimal.ZERO
        val original=d.feeCurrency?:currency
        return Money.parse(if(original==currency)d.feeAmount else d.feeAccountAmount,currency).decimal
    }
    val sourceFee=if(d.feePosition==TransferFeePosition.INCLUDED_FROM)fee(from)else BigDecimal.ZERO
    val targetFee=if(d.feePosition==TransferFeePosition.DEDUCTED_TO)fee(to)else BigDecimal.ZERO
    return if(direction=="TO") {
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
}
