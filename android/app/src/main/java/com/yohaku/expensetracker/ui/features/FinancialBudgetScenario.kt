package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.repository.BudgetPace
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FinancialBudgetScenario(pace:BudgetPace,currency:Currency) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp,bottomEnd=27.dp,bottomStart=20.dp)
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind {
            drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())
            drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
        }.padding(vertical=21.dp)
        "journal"->Modifier.rotate(-.3f).background(Color(0xfffff5dc)).drawBehind {
            drawRect(t.line,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))
        }.padding(22.dp)
        else->Modifier.shadow(2.dp,shape).background(Color(0xfff1ddbc),shape).padding(22.dp)
    }
    val density=LocalDensity.current
    val compact=with(density){LocalWindowInfo.current.containerSize.width.toDp()<=360.dp}||density.fontScale>1.1f
    val unknown=if(compact)word("未知","不明")else word("未知 / 资料待补齐","不明・情報未補足")
    fun amount(n:Long?)=n?.let{Money(currency,it).display()}?:unknown
    val actualOver=pace.actualOverMinor?.let{it>0}==true
    val primaryAmount=if(actualOver)pace.actualOverMinor else pace.projectedOverMinor
    var details by rememberSaveable(pace.sampleStart){mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().testTag("forecast-budget").then(frame),verticalArrangement=Arrangement.spacedBy(13.dp)) {
        YText(word("消费趋势 · 当月推算","支出推移・今月の推計"),10,color=t.muted)
        YText(when {
            actualOver->word("已经超预算","予算超過が発生")
            pace.limitMinor==null->word("尚未设置预算","予算未設定")
            pace.projectedMinor==null->if(compact)word("资料待补齐","情報不足・要確認")else word("资料不足，风险未确定","情報不足でリスク未確定")
            pace.projectedOverMinor!!>0->word("月底可能超预算","月末に予算超過の見込み")
            else->word("按目前节奏仍有余量","現在のペースでは予算内")
        },if(journal)23 else 20,Modifier.testTag("forecast-budget-status"))
        YText(if(actualOver)word("已发生超出","実支出の超過")else word("预计超出","超過の見込み"),10,color=t.muted)
        YText(amount(primaryAmount),31,Modifier.testTag("forecast-budget-amount"),number=primaryAmount!=null,color=if(objects)Color(0xffae7951)else t.warm)
        if(actualOver)RecordFacts(listOf(word("预计月末消费","月末支出の予測") to amount(pace.projectedMinor),word("预计超出","超過の見込み") to amount(pace.projectedOverMinor)))
        YText(pace.sampleStart+" — "+pace.asOf+" · "+word("样本${pace.sampleDays}个自然日，剩余${pace.remainingDays}日；只外推日常净消费，活动、固定账单与费用不重复外推。","${pace.sampleDays}暦日の標本、残り${pace.remainingDays}日。日常純支出のみ外挿し、シーン・固定費・手数料は重ねて推計しません。"),if(journal)12 else 10,color=t.muted,lineHeightMultiplier=2f)
        if(pace.lowConfidence)YText(word("月初或资料较少：低置信估计。","月初・標本が少ないため信頼度の低い推計です。"),11,Modifier.testTag("forecast-budget-low-confidence"),color=t.warm)
        YButton(word("查看测算依据","推計の根拠を見る")+if(details)" −"else" +",{details=!details},Modifier.testTag("forecast-budget-details"),inline=true)
        if(details)RecordFacts(listOf(word("月预算","月予算") to amount(pace.limitMinor),word("已发生预算内消费","予算内の実支出") to amount(pace.actualSpentMinor),word("未来日常推算","今後の日常支出推計") to amount(pace.futureDailyMinor),word("已知固定 / 费用 / 预留","既知の固定費・手数料・確保") to amount(pace.knownFutureMinor),word("预计月末消费","月末支出の予測") to amount(pace.projectedMinor),word("实际 / 预计超出","実際・見込みの超過") to (amount(pace.actualOverMinor)+" / "+amount(pace.projectedOverMinor))))
    }
}
