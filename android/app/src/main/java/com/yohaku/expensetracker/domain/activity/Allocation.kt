package com.yohaku.expensetracker.domain.activity

import java.math.BigDecimal

/** Largest remainder, tied by participant order. Never lose a minor currency unit. */
fun apportion(total:Long,weights:List<BigDecimal>):List<Long> {
    require(total>=0&&weights.isNotEmpty()&&weights.all{it.signum()>=0})
    val sum=weights.fold(BigDecimal.ZERO,BigDecimal::add);require(sum.signum()>0)
    val numerators=weights.map{BigDecimal.valueOf(total).multiply(it)}
    val amounts=numerators.map{it.divideToIntegralValue(sum).longValueExact()}.toMutableList()
    val remaining=Math.subtractExact(total,amounts.fold(0L,Math::addExact)).toInt()
    val order=weights.indices.sortedWith(compareByDescending<Int>{numerators[it].remainder(sum)}.thenBy{it})
    repeat(remaining){amounts[order[it]]=Math.addExact(amounts[order[it]],1)}
    return amounts
}

/** Explicit ratio remainder recipient; the default is the final participant. */
fun apportionWithTail(total:Long,weights:List<BigDecimal>,tail:Int=weights.lastIndex):List<Long> {
    require(total>=0&&weights.isNotEmpty()&&tail in weights.indices&&weights.all{it.signum()>=0})
    val sum=weights.fold(BigDecimal.ZERO,BigDecimal::add);require(sum.signum()>0)
    val amounts=weights.map{total.toBigDecimal().multiply(it).divideToIntegralValue(sum).longValueExact()}.toMutableList()
    amounts[tail]=Math.addExact(amounts[tail],Math.subtractExact(total,amounts.fold(0L,Math::addExact)))
    return amounts
}
