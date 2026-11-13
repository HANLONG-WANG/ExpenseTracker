package com.yohaku.expensetracker.domain.model

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object AmountExpression {
    private fun normalize(text:String)=text.map{when(it){'＋'->'+';'－','−'->'-';'×','x','X'->'*';'÷'->'/';'（'->'(';'）'->')';else->it}}.joinToString("")
    fun isExpression(text:String)=normalize(text).any{it in "+-*/()"}
    fun money(text:String,currency:Currency):Money=if(isExpression(text))evaluate(text,currency)else Money.parse(text,currency)
    fun evaluate(text:String,currency:Currency):Money {
        val parser=Parser(normalize(text))
        val result=parser.expression()
        parser.space()
        require(parser.end){"算式有误 / 式を確認してください"}
        val rounded=result.setScale(currency.scale,RoundingMode.HALF_UP)
        require(rounded>BigDecimal.ZERO){"金额应大于零 / 金額は0より大きくしてください"}
        return Money(currency,rounded.movePointRight(currency.scale).longValueExact())
    }
    private class Parser(val text:String) {
        var index=0
        val end get()=index==text.length
        fun space(){while(!end&&text[index].isWhitespace())index++}
        fun take(char:Char):Boolean {space();return if(!end&&text[index]==char){index++;true}else false}
        fun expression():BigDecimal {
            var value=product()
            while(true)value=when{take('+')->value+product();take('-')->value-product();else->return value}
        }
        fun product():BigDecimal {
            var value=factor()
            while(true)value=when{
                take('*')->value*factor()
                take('/')->{val divisor=factor();require(divisor.compareTo(BigDecimal.ZERO)!=0){"不能除以 0 / 0では割れません"};value.divide(divisor,MathContext.DECIMAL128)}
                else->return value
            }
        }
        fun factor():BigDecimal {
            if(take('-'))return factor().negate()
            if(take('+'))return factor()
            if(take('(')){val value=expression();require(take(')')){"括号不匹配 / 括弧を確認してください"};return value}
            space();val start=index
            while(!end&&(text[index] in '0'..'9'||text[index]=='.'))index++
            return text.substring(start,index).toBigDecimalOrNull()?:error("算式未完成或有误 / 式が未完成か無効です")
        }
    }
}

/** Only the account amount shown in the main entry field accepts expressions. */
fun EntryDraft.resolveAmountExpression(accountCurrency:Currency):EntryDraft {
    val foreign=originalCurrency!=null&&originalCurrency!=accountCurrency
    val text=if(foreign)accountAmount else amount
    if(!AmountExpression.isExpression(text))return this
    val resolved=AmountExpression.evaluate(text,accountCurrency).decimal.toPlainString()
    return if(foreign)copy(accountAmount=resolved)else copy(amount=resolved)
}
