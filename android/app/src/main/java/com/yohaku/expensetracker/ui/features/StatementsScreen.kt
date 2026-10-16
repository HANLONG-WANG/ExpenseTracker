package com.yohaku.expensetracker.ui.features

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun StatementsScreen(s:BookSnapshot,vm:AppViewModel,onRecord:(EventEntity)->Unit,
    onSupplement:(StatementSupplementSeed)->Unit,onActual:(String,String,StatementRow,String)->Unit,onFinancial:(String)->Unit) {
    val context=LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val batches by remember(s.book.id){vm.repository.db.fileWorkflowDao().statements(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    val decisions by remember(s.book.id){vm.repository.db.fileWorkflowDao().decisions(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    val selected by vm.selectedStatementBatch.collectAsStateWithLifecycle()
    val projections=remember(s,batches,decisions){s.reviewStatements(batches,decisions)}
    val active=projections.find{it.batch.id==selected}?:projections.lastOrNull()
    var rowId by rememberSaveable(active?.batch?.id){mutableStateOf<String?>(null)}
    var supplement by rememberSaveable(active?.batch?.id){mutableStateOf<String?>(null)}
    var deleting by rememberSaveable(active?.batch?.id){mutableStateOf(false)}
    var showSides by rememberSaveable(s.book.id){mutableStateOf(false)}
    var identifiers by rememberSaveable(s.book.id){mutableStateOf(false)}
    var billChooser by rememberSaveable(active?.batch?.id){mutableStateOf(false)}
    var billId by rememberSaveable(active?.batch?.id){mutableStateOf<String?>(null)}
    var financialKind by rememberSaveable(active?.batch?.id){mutableStateOf<String?>(null)}
    var financialAccount by rememberSaveable(active?.batch?.id){mutableStateOf<String?>(null)}
    val finance by vm.financialState.collectAsStateWithLifecycle()
    val graph=finance?.takeIf{it.book.id==s.book.id}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        val batch=active?.batch
        if(uri!=null&&batch!=null)vm.execute {
            writeNewTreeDocument(context,uri,"yohaku-statement-${batch.id}.json","application/json"){it.write(batch.originalJson.toByteArray(Charsets.UTF_8))}
        }
    }
    YTitle(word("核对，心里更有数。","照合して、確かめる。"),"CHECK, DON’T DUPLICATE",word("流水独立保存，手动账目始终为准。","明細は別に保存。手動記録が基準です。"),pageFlow=true)
    YText(word("导入不会新增、修改或删除正式记账。日期、实际金额与方向一致，笔数逐一核对。","取込で記録を追加・変更・削除しません。日付・実金額・方向を1件ずつ照合。"),12,color=LocalYohaku.current.muted)
    Box(Modifier.heightIn(min=44.dp).testTag("statement-import-entry").clickable(enabled=!busy){vm.navigate("statement-import")},contentAlignment=androidx.compose.ui.Alignment.CenterStart) {
        YText(word("＋ 导入核对文件","＋ 照合ファイルを取込"),10,Modifier.border(1.dp,LocalYohaku.current.line,RoundedCornerShape(20.dp)).padding(horizontal=12.dp,vertical=8.dp),color=LocalYohaku.current.muted)
    }
    YLink(word("导入标识说明","取込用IDの説明"),{identifiers=true},Modifier.testTag("statement-identifiers"))
    if(projections.isEmpty())YEmpty(word("还没有核对资料，先导出标识并交给助手转换原流水。","照合資料はまだありません。IDを保存して助手に元明細の変換を依頼しましょう。"))
    if(projections.size>1)YSelect(word("核对批次","照合バッチ"),active?.batch?.id,projections.map{it.batch.id as String? to ((s.accounts.find{a->a.id==it.batch.accountId}?.name?:"")+" · "+it.file.from+" / "+it.file.rows.size)},{vm.selectedStatementBatch.value=it},Modifier.testTag("statement-batch"))
    active?.let { p->
        val file=p.file
        val account=s.accounts.first{it.id==file.account_id}
        val overlaps=projections.filter{it.batch.id!=p.batch.id&&it.file.account_id==file.account_id&&it.file.from<=file.to&&it.file.to>=file.from}
        ThemeAuditPanel(account.name,Modifier.testTag("statement-summary")) {
            RecordFacts(listOf(word("文件区间","明細期間") to "${file.from} — ${file.to}",
                word("完全匹配 / 待确认 / 仅流水","完全一致・要確認・明細のみ") to "${p.result.exact.size} / ${p.result.differences.size+p.decisions.size} / ${p.result.statementOnly.size}",
                word("匹配日期语义","照合する日付") to if(file.date_basis=="posted_date")word("实际入账日","実計上日")else word("发生日","利用日")))
            YText(if(file.complete&&file.issues.isEmpty())word("标记完整区间，逐笔按账户币种核对。","完全期間と指定。口座通貨で1件ずつ照合。")else word("部分或有疑点资料，未匹配不直接等于漏记或多记。","部分資料・不明点があります。不一致は記録漏れや重複とは限りません。"),12)
            file.issues.forEach{YText(it,11,color=LocalYohaku.current.warm)}
            if(overlaps.isNotEmpty())YText(word("与 ${overlaps.size} 个批次区间重叠。已占用资金端不会在其他批次再次匹配。","${overlaps.size} バッチと期間が重複。使用済みの資金側は他のバッチで再使用しません。"),12,color=LocalYohaku.current.warm)
        }
        file.rows.forEach { row->
            val exact=p.result.exact.firstOrNull{it.rowId==row.row_id}
            val manual=p.decisions.firstOrNull{it.saved.rowId==row.row_id}
            val line=LocalYohaku.current.line
            Column(Modifier.fillMaxWidth().testTag("statement-row-${row.row_id}").drawBehind{drawLine(line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                YText(row.date+" · "+(if(row.direction=="in")"+"else "−")+receiptCurrencyText(row.settlement.amount,row.settlement.currency),13,bold=true)
                YText(row.summary,12)
                val status=when {
                    exact!=null->word("日期、实际金额与方向一致","日付・実金額・方向一致")
                    manual?.active==true->word("已确认差异，非完全一致","差異確認済・完全一致ではない")
                    manual!=null->word("原记账已变化，待重新核对","元記録が変わり、再照合待ち")
                    p.result.differences.any{it.rowId==row.row_id}->word("待确认差异","差異の確認待ち")
                    else->word("仅流水存在，待核对","明細のみ・照合待ち")
                }
                YText(status,if(LocalYohaku.current.id=="journal")12 else 10,color=if(exact!=null)LocalYohaku.current.accent else LocalYohaku.current.warm,modifier=Modifier.fillMaxWidth().background(LocalYohaku.current.soft,RoundedCornerShape(4.dp)).padding(horizontal=7.dp,vertical=4.dp).testTag("statement-status-${row.row_id}"))
                val postingId=exact?.postingId?:manual?.saved?.postingId
                val posting=s.reconciliationPostings().firstOrNull{it.postingId==postingId}
                val event=posting?.let{post->s.visibleEvents.find{it.id==post.eventId}}
                if(event!=null)YLink(word("对应记账：","対応記録：")+event.merchant.ifBlank{event.date},{onRecord(event)},Modifier.testTag("statement-open-record-${row.row_id}"))
                if(exact==null) {
                    YButton(word("查看候选与差异","候補と差異を見る"),{rowId=row.row_id},Modifier.testTag("statement-candidates-${row.row_id}"),enabled=!busy)
                    YLink(word("选择类型补记","種類を選んで記録"),{supplement=row.row_id},Modifier.testTag("statement-supplement-${row.row_id}"))
                }
                row.source_ref?.let{YText(word("来源行：","元の行：")+it,10,color=LocalYohaku.current.muted)}
            }
        }
        if(p.result.ledgerOnly.isNotEmpty()) {
            ThemeSection(word("仅记账存在，先检查","記録のみ、まず確認"),"REVIEW")
            p.result.ledgerOnly.forEach{id->StatementPostingLink(s,id,onRecord)}
            YText(word("可能是日期差异、多记或资料尚未覆盖，不自动删除。","日付違い・重複・資料未到着の可能性。自動削除しません。"),11,color=LocalYohaku.current.muted)
        }
        if(p.result.uncovered.isNotEmpty()) {
            ThemeSection(word("未覆盖 / 待核对","未確認・照合待ち"),"NOT COVERED")
            p.result.uncovered.forEach{id->StatementPostingLink(s,id,onRecord)}
        }
        file.statement_info?.let{info->ThemeAuditPanel(word("账单补充资料","請求補足資料")) {
            StatementInfoFacts(info)
            YButton(word("选择正式账单并预览确认","正式な請求を選び確認"),{billChooser=true},Modifier.testTag("statement-confirm-bill-info"),enabled=!busy)
        }}
        YButton(word("保存完整原文件副本","元ファイル全体のコピーを保存"),{export.launch(null)},Modifier.testTag("statement-export-original"),enabled=!busy)
        YLink(word("删除此核对资料","この照合資料を削除"),{deleting=true},Modifier.testTag("statement-delete"))
        rowId?.let{id->file.rows.find{it.row_id==id}?.let { row-> StatementDifferenceSheet(s,p,projections,row,vm,{rowId=null},{eventId,postedDate->rowId=null;onActual(eventId,file.account_id,row,postedDate)},{event->rowId=null;onRecord(event)}) } }
        supplement?.let{id->file.rows.find{it.row_id==id}?.let{row->StatementSupplementSheet(s,file,row,vm,{supplement=null},{seed->supplement=null;onSupplement(seed)},{kind,debt->supplement=null;financialAccount=debt;financialKind=kind})}}
        if(deleting)YSheet(word("删除核对资料","照合資料を削除"),{deleting=false}) {
            YText(word("只移除本批原文件和核对关联，不删除或修改手动账目。","このバッチの元ファイルと照合関連だけを削除し、手動記録は削除・変更しません。"),13)
            YButton(word("确认删除资料","資料の削除を確認"),{vm.execute(onSuccess={deleting=false}){vm.repository.db.fileWorkflowDao().deleteStatement(s.book.id,p.batch.id)}},Modifier.testTag("statement-delete-confirm"),primary=true,enabled=!busy)
        }
        if(billChooser)StatementBillChooser(s,p,graph,{billChooser=false},{billId=it;billChooser=false},{billChooser=false;onFinancial(account.id)})
        billId?.let { id->graph?.bills?.firstOrNull{it.id==id}?.let { bill->
            val info=file.statement_info
            fun value(money:FileMoney?)=money?.takeIf{it.currency==account.currency}?.amount.orEmpty()
            FinancialBillConfirmationEditor(s,account.id,bill,Currency.valueOf(account.currency),vm,{billId=null},
                initial=KnownBillDraft(info?.payment_date?:bill.date,value(info?.total),value(info?.principal),value(info?.fees),bill.paymentAccountId))
        } }
    }
    ThemeAuditPanel(word("转账，两端各核对一次","振替は両側を照合")) {
        YText(word("仅导入一侧，另一侧保持未核对，不直接认定漏记。信用卡还款也不再次计入消费。","片側だけ取込なら、反対側は未照合のまま。記録漏れとは判定せず、カード返済も支出に重複計上しません。"),12)
        YLink(word("查看转账 / 还款两端关联","振替・返済の両側の関連を見る"),{showSides=true},Modifier.testTag("statement-sides"))
    }
    if(showSides)YSheet(word("同一交易，两端各核对","同じ取引の両側を照合"),{showSides=false}) {
        val events=s.visibleEvents.filter{it.kind in listOf(EntryKind.TRANSFER.name,EntryKind.REPAYMENT.name)}
        if(events.isEmpty())YEmpty(word("尚无转账或还款记录。","振替・返済記録はありません。"))
        events.forEach{event->ThemeAuditPanel(event.merchant.ifBlank{event.date}){StatementSideFacts(s,event,projections);YLink(word("查看原记账","元の記録を見る"),{showSides=false;onRecord(event)})}}
    }
    if(identifiers)StatementIdentifiers(s,vm,{identifiers=false})
    val debt=s.accounts.find{it.id==financialAccount}
    if(financialKind==EntryKind.REPAYMENT.name&&debt!=null&&graph!=null)FinancialRepaymentEditor(s,debt,graph,vm,{financialKind=null;financialAccount=null})
    if(financialKind==EntryKind.BORROWING.name&&debt!=null)FinancialBorrowingEditor(s,debt,vm,{financialKind=null;financialAccount=null})
}

@Composable private fun StatementPostingLink(s:BookSnapshot,id:String,onRecord:(EventEntity)->Unit) {
    val posting=s.reconciliationPostings().firstOrNull{it.postingId==id}?:return
    val event=s.visibleEvents.firstOrNull{it.id==posting.eventId}?:return
    YLink(posting.postedDate+" · "+event.merchant+" · "+receiptCurrencyText(posting.amount.toPlainString(),posting.currency),{onRecord(event)})
}
@Composable internal fun StatementSideFacts(s:BookSnapshot,event:EventEntity,projections:List<StatementReviewProjection>) {
    val postings=s.storedPostings.filter{it.eventId==event.id}
    val exact=projections.flatMap{it.result.exact}.map{it.postingId}.toSet()
    val manual=projections.flatMap{it.decisions}.filter{it.active}.map{it.saved.postingId}.toSet()
    RecordFacts(postings.map { posting->
        val account=s.accounts.first{it.id==posting.accountId}
        account.name to when {posting.id in exact->word("此侧已完全匹配","この側は完全一致");posting.id in manual->word("此侧已确认差异","この側は差異確認済み");else->word("此侧未核对，不直接认定漏记","この側は未照合・漏れとは判定しない")}
    })
    s.repayments.find{it.eventId==event.id&&postings.none{p->p.accountId==it.debtAccountId}}?.let { r->
        YText((s.accounts.find{it.id==r.debtAccountId}?.name.orEmpty())+" · "+word("清偿侧待确认，不推定已核对或漏记。","返済先側は未確定。照合済み・記録漏れとは判定しません。"),11,color=LocalYohaku.current.muted)
    }
}

@Composable internal fun RecordStatementSides(s:BookSnapshot,event:EventEntity,vm:AppViewModel) {
    if(event.kind !in listOf(EntryKind.TRANSFER.name,EntryKind.REPAYMENT.name))return
    val batches by remember(s.book.id){vm.repository.db.fileWorkflowDao().statements(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    val decisions by remember(s.book.id){vm.repository.db.fileWorkflowDao().decisions(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    val projections=remember(s,batches,decisions){s.reviewStatements(batches,decisions)}
    ThemeAuditPanel(word("两端核对状态","両側の照合状態"),Modifier.testTag("record-statement-sides")) {StatementSideFacts(s,event,projections)}
}
