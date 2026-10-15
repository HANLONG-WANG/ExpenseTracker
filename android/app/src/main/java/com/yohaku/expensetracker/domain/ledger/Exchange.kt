package com.yohaku.expensetracker.domain.ledger

import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.domain.model.Currency
import java.math.BigDecimal
import java.math.RoundingMode

/** Direction: one unit of original currency buys rate units of target currency. */
fun Money.convert(target:Currency,rate:BigDecimal):Money {
    require(rate>BigDecimal.ZERO)
    return Money(target,decimal.multiply(rate).setScale(target.scale,RoundingMode.HALF_UP).movePointRight(target.scale).longValueExact())
}
fun exchangeRate(original:Money,settlement:Money):BigDecimal {
    require(original.minor>0 && settlement.minor>0)
    return settlement.decimal.divide(original.decimal,12,RoundingMode.HALF_UP).stripTrailingZeros()
}
