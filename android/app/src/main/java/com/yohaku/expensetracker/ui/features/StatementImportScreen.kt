package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import com.yohaku.expensetracker.ui.haptics.Haptic

@Composable internal fun StatementImportScreen(s:BookSnapshot,vm:AppViewModel) {
    YTitle(word("先转换，再核对。","変換して、照合。"),"STATEMENT IMPORT",pageFlow=true)
    StatementSteps()
    StatementUploadPanel {
        Column(Modifier.fillMaxWidth(),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(15.dp)) {
        if(LocalYohaku.current.id=="objects")Box(Modifier.size(46.dp).background(Color(0xffd5dbbf),RoundedCornerShape(14.dp)).padding(10.dp)){YIcon("wallet",Modifier.fillMaxSize())}
        else YIcon("wallet",Modifier.size(38.dp))
        YText(word("交给云端转换流水","クラウドで明細を変換"),20,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        CloudStatementHandoff(s,vm)
        }
    }
}

/** Shared by verified cloud results; saving stays with the caller. */
@Composable internal fun StatementPreviewContent(s:BookSnapshot,vm:AppViewModel,p:StatementImportPreview,overlap:Boolean,onOverlap:(Boolean)->Unit,busy:Boolean,onConfirm:()->Unit,onCancel:()->Unit) {
    LaunchedEffect(p){if(p.duplicateBatchId!=null||p.overlappingBatchIds.isNotEmpty())vm.haptic(Haptic.Warning)}
    val account=s.accounts.first{it.id==p.file.account_id}
    Panel(Modifier.testTag("statement-preview")) {
        YText(word("导入预览","取込プレビュー"),19)
        RecordFacts(listOf(word("账本","帳簿") to s.book.name,word("账户","口座") to account.name,
            word("日期范围","期間") to "${p.file.from} — ${p.file.to}",word("笔数","件数") to p.file.rows.size.toString(),
            word("转出合计","出金合計") to receiptCurrencyText(p.outgoing.toPlainString(),p.file.account_currency),
            word("转入合计","入金合計") to receiptCurrencyText(p.incoming.toPlainString(),p.file.account_currency)))
        val line=LocalYohaku.current.line
        Column(Modifier.fillMaxWidth().drawBehind{drawLine(line,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=7.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            p.file.rows.take(3).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                YText(row.date+" · "+row.summary,9,Modifier.weight(1f),color=LocalYohaku.current.muted)
                YText(receiptCurrencyText(row.settlement.amount,row.settlement.currency),9,number=true)
            }}
        }
        if(p.duplicateBatchId!=null) {
            YText(word("这份流水已经导入过。","この明細は取込済みです。"),12,color=LocalYohaku.current.warm)
            YButton(word("查看已有核对资料","既存の照合資料を見る"),{vm.selectedStatementBatch.value=p.duplicateBatchId;vm.navigate("statements")},Modifier.testTag("statement-open-duplicate"))
        }
        if(p.overlappingBatchIds.isNotEmpty()&&p.duplicateBatchId==null) {
            YText(word("与已导入的 ${p.overlappingBatchIds.size} 份流水区间重叠。","取込済みの明細 ${p.overlappingBatchIds.size} 件と期間が重なっています。"),12,color=LocalYohaku.current.warm)
            YToggle(word("仍然单独导入这一份","この明細も別に取り込む"),overlap,onOverlap,Modifier.testTag("statement-accept-overlap"))
        }
        YActionRow {
            YButton(word("确认导入，开始核对","確認して照合開始"),onConfirm,Modifier.testTag("statement-confirm-import"),primary=true,enabled=!busy&&p.duplicateBatchId==null&&(p.overlappingBatchIds.isEmpty()||overlap))
            YButton(word("放弃预览","プレビューを破棄"),onCancel,Modifier.testTag("statement-cancel-preview"),enabled=!busy)
        }
    }
    ThemeAuditPanel(word("转换说明","変換メモ"),Modifier.testTag("statement-conversion-issues")) {
        RecordFacts(listOf(word("完整程度","完全性") to if(p.file.complete)word("标记完整","完全と指定")else word("部分资料","部分資料"),
            word("匹配日期语义","照合する日付") to if(p.file.date_basis=="posted_date")word("实际入账日","実計上日")else word("发生日","利用日")))
        YText(word("来源：","出典：")+p.file.source,11)
        p.file.issues.forEach{YText(it,12,color=LocalYohaku.current.warm)}
        p.file.statement_info?.let{StatementInfoFacts(it)}
    }
}

@Composable private fun StatementUploadPanel(content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.border(1.dp,t.ink)
        "journal"->Modifier.background(Color(0xfffff7e3)).drawBehind{drawRect(t.warm,style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.background(Color(0xffe8e9dc),RoundedCornerShape(25.dp))
    }
    Column(Modifier.fillMaxWidth().then(frame).padding(horizontal=20.dp,vertical=if(t.id=="magazine")30.dp else 28.dp),content=content)
}
@Composable internal fun StatementSteps() {
    val t=LocalYohaku.current
    val titles=listOf(word("交给云端转换","クラウドで変換"),word("领取转换结果","結果を受け取る"),word("预览并确认","確認して照合"))
    if(t.id=="objects")Column(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        titles.forEachIndexed{i,title->YText("0${i+1} $title",11,Modifier.fillMaxWidth().background(Color(0xffefe1c9),RoundedCornerShape(12.dp)).padding(horizontal=15.dp,vertical=13.dp),color=t.muted)}
    }else Column(Modifier.fillMaxWidth().padding(top=5.dp,bottom=10.dp).then(if(t.id=="journal")Modifier.rotate(-1f)else Modifier).drawBehind{
        drawLine(if(t.id=="magazine")t.ink else t.line,Offset.Zero,Offset(0f,size.height),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))else null)
    }.padding(start=12.dp),verticalArrangement=Arrangement.spacedBy(if(t.id=="magazine")18.dp else 17.dp)) {
        titles.forEachIndexed{i,title->YText("0${i+1} $title",if(t.id=="magazine")10 else 13,color=t.muted)}
    }
}
@Composable internal fun StatementInfoFacts(info:StatementInfo) {
    YText(word("独立账单补充资料","独立した請求補足資料"),14)
    fun money(value:FileMoney?)=value?.let{receiptCurrencyText(it.amount,it.currency)}
    val unknown=word("未知","不明")
    RecordFacts(listOf(word("本金","元金") to (money(info.principal)?:unknown),word("费用","手数料") to (money(info.fees)?:unknown),word("账单合计","請求合計") to (money(info.total)?:unknown),word("还款日","返済日") to (info.payment_date?:unknown)))
}
