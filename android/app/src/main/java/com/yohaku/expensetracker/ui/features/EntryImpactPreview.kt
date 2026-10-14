package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun EntryImpactPreview(s:BookSnapshot,event:EventEntity,impact:EntryImpact,currentMonth:String,restoring:Boolean=false){
    val t=LocalYohaku.current
    ThemeSection(if(restoring)word("恢复后，数字会这样变化","復元後の金額の変化") else word("这次操作，会改变什么","今回、変わる金額"),"BEFORE / AFTER")
    Column(Modifier.fillMaxWidth().testTag("entry-impact"),verticalArrangement=Arrangement.spacedBy(if(t.id=="objects")8.dp else 0.dp)){
        impact.balances.forEach{change->
            ImpactChange(change.account.name+" · "+if(AccountType.valueOf(change.account.type).debt)word("欠款","借入残高") else word("余额","残高"),
                Currency.valueOf(change.account.currency),change.before,change.after,"impact-balance-${change.account.id}")
        }
        if(impact.balances.isEmpty())YText(word("已包含期初，只改变记账资料，不改变账户余额。","期首残高に含む記録。内容のみ変更し、口座残高は変わりません。"),11,color=t.muted)
        if(event.kind==EntryKind.EXPENSE.name)ImpactChange(impact.month+" · "+word("消费统计","支出集計"),impact.currency,impact.expenseBefore,impact.expenseAfter,"impact-expense")
        if(event.kind==EntryKind.INCOME.name)ImpactChange(impact.month+" · "+word("收入统计","収入集計"),impact.currency,impact.incomeBefore,impact.incomeAfter,"impact-income")
        val budget=impact.remainingBudget(currentMonth,s.book.budgetMinor)
        if(budget!=null)ImpactChange(word("当月预算内还能花","今月の予算内で使える"),impact.currency,budget.before,budget.after,"impact-budget")
        else YText(if(impact.month==currentMonth)word("未设置当月预算，预算余量未知。","今月の予算は未設定。予算残額は不明です。") else word("该月预算尚未记录，不使用当前预算推算。","この月の予算は未記録。現在の予算から推定しません。"),11,Modifier.testTag("impact-budget-unknown").padding(vertical=12.dp),color=t.muted)
    }
}

@Composable private fun ImpactChange(label:String,currency:Currency,before:Long,after:Long,tag:String){
    val t=LocalYohaku.current
    val frame=when(t.id){
        "magazine"->Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx())}.padding(top=16.dp,bottom=17.dp)
        "journal"->Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(top=15.dp,bottom=16.dp)
        else->Modifier.background(Color(0x60ffffff),RoundedCornerShape(10.dp)).padding(12.dp)
    }
    Column(Modifier.fillMaxWidth().testTag(tag).then(frame)){
        YText(label,if(t.id=="journal")11 else 9,color=if(t.id=="journal")t.warm else t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
        YText(Money(currency,before).display()+" → "+Money(currency,after).display(),21,Modifier.fillMaxWidth().padding(top=6.dp),number=true,color=if(t.id=="journal"||t.id=="objects")t.accent else t.ink,lineHeightMultiplier=if(t.id=="journal")1.7f else 1.6f,cssLineBox=true)
    }
}
