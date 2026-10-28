package com.yohaku.expensetracker.domain.finance

data class SinglePaymentPortion(val planId:String,val date:String,val createdAt:Long,val principalMinor:Long)
data class SinglePaymentTransfer(val planId:String,val principalMinor:Long,val retainedPrincipalMinor:Long)

fun allocateSinglePaymentTransfers(sources:List<SinglePaymentPortion>,amount:Long):List<SinglePaymentTransfer> {
    require(sources.all{it.principalMinor>0}&&sources.map{it.planId}.distinct().size==sources.size)
    require(amount>0&&amount<=sources.fold(0L){n,s->Math.addExact(n,s.principalMinor)}){"转换金额必须大于0且不超过所选本金 / 変更額は0より大きく選択元金以内にしてください"}
    var remaining=amount
    return sources.sortedWith(compareBy<SinglePaymentPortion>{it.date}.thenBy{it.createdAt}).mapNotNull{source->
        val moved=minOf(remaining,source.principalMinor);remaining-=moved
        if(moved==0L)null else SinglePaymentTransfer(source.planId,moved,source.principalMinor-moved)
    }
}
