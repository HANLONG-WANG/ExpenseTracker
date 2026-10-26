package com.yohaku.expensetracker.ui.features

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.csv.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun CsvImportScreen(s:BookSnapshot,vm:AppViewModel,providedModel:CsvImportViewModel?=null) {
    val context=LocalContext.current
    val model:CsvImportViewModel=providedModel?:viewModel(key="csv-${s.book.id}",factory=object:androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T=CsvImportViewModel(vm.repository,s.book.id) as T
    })
    val state by model.state.collectAsState()
    val history by remember(s.book.id){vm.repository.db.csvImportDao().batches(s.book.id)}.collectAsState(emptyList())
    val select=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{uri->model.open(context,uri)}}
    var newAccount by remember{mutableStateOf(false)}
    var confirm by remember{mutableStateOf(false)}
    var undo by remember{mutableStateOf<CsvBatchEntity?>(null)}
    val t=LocalYohaku.current
    YLead(word("旧账，接着记。","これまでの記録も、一緒に。"),word("CSV 迁入会在确认后生成正式账目，参与分析、预算与日历。银行流水核对是另一条独立流程，不会自动记账。","CSV 移行は確認後に正式な記録を作り、分析・予算・カレンダーに反映します。銀行明細の照合とは別の機能です。"),"CSV MIGRATION",pageFlow=true)
    YText(word("目标账本：","移行先の帳簿：")+s.book.name+" · "+s.book.currency,14)
    YButton(word("选择 CSV 文件","CSV ファイルを選ぶ"),{select.launch(arrayOf("*/*"))},Modifier.fillMaxWidth().testTag("csv-open"),primary=true,enabled=!state.busy)
    state.error?.let{YText(csvMessage(it),12,color=t.warm)}
    state.imported?.let{YText(word("已迁入 $it 条正式记录，可在下方撤销整批。","$it 件の正式な記録を移行しました。下の履歴から一括で取り消せます。"),16)}
    if(state.repeated&&state.document!=null)Panel{YText(word("这份文件已导入过","このファイルは移行済みです"),18,color=t.warm);YText(word("SHA-256 与历史批次相同。请核对疑似重复行；默认跳过，仍可逐行选择迁入。","過去のバッチと SHA-256 が一致します。重複候補は既定でスキップしますが、行ごとに移行を選べます。"),12)}
    state.document?.let{document->
        YText("${state.step+1} / 4 · "+listOf(word("文件与编码","ファイル・文字コード"),word("列对应与收支","列の対応・収支"),word("账户与分类","口座・カテゴリ"),word("预览与修正","プレビュー・修正"))[state.step],16)
        YText(state.fileName+" · ${document.data.size} "+word("行","行"),12)
        if(state.remembered)YText(word("已套用此表头的对应设置，可继续修改。","この見出しの対応設定を適用しました。変更できます。"),11,color=t.muted)
        when(state.step) {
            0->{
                YSelect(word("编码","文字コード"),state.settings.encoding,listOf("AUTO" to word("自动","自動"),"UTF-8" to "UTF-8","Shift_JIS" to "Shift_JIS","GBK" to "GBK","UTF-16LE" to "UTF-16LE","UTF-16BE" to "UTF-16BE"),{model.decode(encoding=it,delimiter=null)})
                YText(word("识别为：","認識結果：")+document.encoding+word("；BOM 优先。","。BOM を優先します。"),11)
                YSelect(word("分隔符","区切り文字"),document.delimiter,listOf("," to word("逗号","カンマ"),"\t" to word("制表符","タブ"),";" to word("分号","セミコロン")),{model.decode(delimiter=it)})
                YToggle(word("第一行是表头","1行目は見出し"),document.header,{model.decode(header=it)})
                Panel{YText(word("前五行预览","先頭5行のプレビュー"),16);document.rows.take(5).forEach{YText(it.joinToString(" | "),11)}}
            }
            1->CsvColumns(document,state.settings,model::configure)
            2->CsvMappings(s,document,state.settings,model::configure,{newAccount=true})
            3->state.preview?.let{CsvReview(it,state,s,model::edit)}
        }
        if(state.step>0)YButton(word("上一步","戻る"),{model.step(state.step-1)},enabled=!state.busy)
        if(state.step<3)YButton(if(state.step==2)word("生成预览","プレビューを作成")else word("下一步","次へ"),{model.step(state.step+1)},Modifier.fillMaxWidth(),primary=true,enabled=!state.busy)
        else YButton(word("确认迁入正式账目","正式な記録へ移行する"),{confirm=true},Modifier.fillMaxWidth().testTag("csv-import"),primary=true,enabled=!state.busy&&state.preview?.canImport==true)
    }
    YText(word("导入批次","移行履歴"),18)
    if(history.isEmpty())YEmpty(word("还没有 CSV 迁入记录","CSV の移行履歴はありません"))
    history.forEach{batch->Panel {
        YText(batch.fileName,14)
        YText(java.time.Instant.ofEpochMilli(batch.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString(),11)
        YText(word("共 ${batch.rowCount} 行 · 迁入 ${batch.importedCount} · 跳过 ${batch.skippedCount}","全 ${batch.rowCount} 行・移行 ${batch.importedCount}・スキップ ${batch.skippedCount}"),12)
        YButton(word("查看批次记录","バッチの記録を見る"),{model.events(batch)},enabled=!state.busy)
        YButton(word("撤销整批","一括で取り消す"),{undo=batch},enabled=!state.busy)
    }}
    if(newAccount)AccountEditor(s,vm,{newAccount=false})
    if(confirm)YSheet(word("确认 CSV 迁入","CSV 移行の確認"),{confirm=false}) {
        YText(word("将创建 ${state.preview?.selected?.size?:0} 条正式账目；期初日之后的记录会改变账户余额。文件内相同行会分别保留。","${state.preview?.selected?.size?:0} 件の正式な記録を作成します。期首日より後の記録は残高を変えます。ファイル内の同じ行も別々に保存します。"))
        YText(word("收入分类「退款」按普通收入迁入，不建立退款关联。可通过历史批次撤销至回收站。","収入カテゴリの「退款」は通常の収入として移行し、返金の関連は作りません。履歴からごみ箱へ一括で取り消せます。"),12)
        YButton(word("确认导入","移行を確定"),{confirm=false;model.importBatch()},primary=true)
    }
    undo?.let{batch->YSheet(word("撤销整批","一括取り消し"),{undo=null}) {
        YText(word("本批次仍存在的记录及其关联退款将一起进入回收站，30 天内可整组恢复。","このバッチに残る記録と関連する返金をごみ箱へ移します。30日以内はグループで復元できます。"))
        YButton(word("移至回收站","ごみ箱へ移動"),{undo=null;model.undo(batch)},primary=true)
    }}
    state.batchEvents?.let{events->YSheet(word("批次记录","バッチの記録"),model::closeEvents) {
        var page by remember(events){mutableIntStateOf(0)}
        if(events.isEmpty())YText(word("记录已永久清理","記録は完全に削除されています"))
        events.drop(page*30).take(30).forEach{e->YText("${e.date} · ${Money(Currency.valueOf(e.currency),e.amountMinor).display()} · ${e.merchant.ifBlank{e.note}}"+if(e.deletedAt!=null)word(" · 回收站","・ごみ箱")else"",12)}
        CsvPager(page,events.size,30){page=it}
        YButton(word("前往回收站恢复","ごみ箱で復元"),{model.closeEvents();vm.navigate("trash")})
    }}
    if(state.busy)YSheet(word("正在处理","処理しています"),{}){YText(state.progress?.let{(done,total)->"$done / $total"}?:word("正在解析或准备，请稍候。","解析・準備中です。お待ちください。"));YText(word("整批完成后才保存，失败不会留下半批账目。","バッチ全体が完了した時だけ保存します。失敗時に一部だけ残りません。"),12)}
}

@Composable private fun CsvColumns(document:CsvDocument,settings:CsvSettings,onChange:(CsvSettings)->Unit) {
    val names=listOf(word("日期（必选）","日付（必須）"),word("金额（必选）","金額（必須）"),word("收支类型","収支区分"),word("分类","カテゴリ"),word("备注","メモ"),word("商户","お店"),word("账户","口座"))
    val options=listOf(-1 to word("未对应","対応なし"))+document.labels.mapIndexed{i,label->i to "${i+1} · $label"}
    CsvParser.fields.forEachIndexed{i,field->YSelect(names[i],settings.columns[field]?:-1,options,{col->onChange(settings.copy(columns=if(col<0)settings.columns-field else settings.columns+(field to col)))})}
    YSelect(word("日期格式","日付形式"),settings.dateFormat,listOf("AUTO" to word("自动识别（忽略时间）","自動認識（時刻を除く）"))+listOf("yyyy-MM-dd","yyyy/M/d","yyyy.M.d","yyyy年M月d日").map{it to it},{onChange(settings.copy(dateFormat=it))})
    YSelect(word("收支判断","収支の判定"),settings.rule,listOf("TYPE" to word("按类型列","区分列を使う"),"SIGN" to word("按金额正负（负数为支出）","金額の符号（負数は支出）"),"EXPENSE" to word("全部视为支出","すべて支出")),{onChange(settings.copy(rule=it))})
    if(settings.rule=="TYPE") {
        val values=settings.columns["type"]?.let{i->document.data.map{it.getOrElse(i){""}}.distinct()}.orEmpty()
        if(values.isEmpty())YText(word("请对应收支类型列，或选择其他判断方式。","収支区分の列を対応させるか、別の判定方法を選んでください。"),12)
        values.forEach{value->YSelect(value.ifBlank{word("空值","空欄")},settings.types[value]?:CsvParser.direction(value)?:"UNSET",listOf("UNSET" to word("请选择","選択してください"),"EXPENSE" to word("支出","支出"),"INCOME" to word("收入","収入"),"SKIP" to word("跳过","スキップ")),{onChange(settings.copy(types=settings.types+(value to it)))})}
    }
    YToggle(word("备注同时作为商户名","メモをお店の名前にも使う"),settings.noteAsMerchant,{onChange(settings.copy(noteAsMerchant=it))})
}

@Composable private fun CsvMappings(s:BookSnapshot,document:CsvDocument,settings:CsvSettings,onChange:(CsvSettings)->Unit,onNewAccount:()->Unit) {
    val accounts=s.accounts.filter{csvAccountAllowed(it,s.book)}
    val accountOptions=listOf("" to word("请选择账户","口座を選択"))+accounts.map{it.id to it.name}
    YSelect(word("目标账户（未对应时使用）","移行先口座（未対応時に使用）"),settings.accountId,accountOptions,{onChange(settings.copy(accountId=it))})
    s.accounts.filterNot{csvAccountAllowed(it,s.book)}.forEach{YText(it.name+" · "+if(!it.active)word("已停用，不可用","停止中のため利用不可")else word("币种 ${it.currency} 与本位币不同，不可用","通貨 ${it.currency} が基準通貨と異なるため利用不可"),11,color=LocalYohaku.current.muted)}
    YButton(word("新建账户","口座を新規作成"),onNewAccount)
    YText(word("收入只可迁入资金账户。日期不晚于所选账户期初日的记录标为已包含期初，不改变余额；其余记录会改变余额。","収入は資金口座のみ。選択した口座の期首日以前の記録は期首残高に含め、残高を変えません。それ以降の記録は残高を変えます。"),12)
    settings.columns["account"]?.let{i->document.data.map{it.getOrElse(i){""}}.distinct().forEach{value->YSelect(word("账户值：","口座の値：")+value.ifBlank{word("空值","空欄")},settings.accounts[value].orEmpty(),listOf("" to word("使用目标账户","移行先口座を使う"))+accounts.map{it.id to it.name},{onChange(settings.copy(accounts=settings.accounts+(value to it)))})}}
    val groups=CsvParser.values(document,settings).filter{it.kind in listOf("EXPENSE","INCOME")}.groupBy{it.kind!! to it.category}
    groups.forEach{(key,rows)->
        val (kind,value)=key
        val label=(if(kind=="INCOME")word("收入","収入")else word("支出","支出"))+" · "+value.ifBlank{word("空分类 / 默认分类","空欄・既定カテゴリ")}+" (${rows.size})"
        val matching=s.categories.filter{it.kind==kind&&s.categoryEnabled(it.id)}.map{it.id to s.path(it.id)}
        val mapKey=CsvParser.categoryKey(kind,value)
        val chosen=if(value.isBlank())settings.defaults[kind].orEmpty()else settings.categories[mapKey]?:csvCategoryChoice(s,kind,value)
        val newChoice=if(value.isBlank())"NEW:"+word("未分类","未分類")else"NEW"
        val options=listOf("" to word("请选择分类","カテゴリを選択"),newChoice to word("新建为一级分类","親カテゴリを新規作成"))+matching
        Panel {
            fun update(choice:String){onChange(if(value.isBlank())settings.copy(defaults=settings.defaults+(kind to choice))else settings.copy(categories=settings.categories+(mapKey to choice)))}
            YSelect(label,if(chosen.startsWith("NEW:"))newChoice else chosen,options,::update,Modifier.testTag("csv-category-$mapKey"))
            if(chosen.startsWith("NEW:"))YField(word("新分类名称","新しいカテゴリ名"),chosen.removePrefix("NEW:"),{update("NEW:$it")})
        }
    }
}

@Composable private fun CsvReview(preview:CsvPreview,state:CsvUiState,s:BookSnapshot,onEdit:(Int,CsvEdit)->Unit) {
    val selected=preview.selected
    val currency=Currency.valueOf(s.book.currency)
    val incomeLabel=word("收入","収入");val expenseLabel=word("支出","支出")
    fun money(rows:List<CsvPreviewRow>)=Money(currency,rows.mapNotNull{it.minor}.fold(0L,Math::addExact)).display()
    Panel {
        YText(word("共 ${preview.rows.size} 行 · 选中 ${selected.size} · 跳过 ${preview.rows.size-selected.size}","全 ${preview.rows.size} 行・選択 ${selected.size}・スキップ ${preview.rows.size-selected.size}"),16)
        val dates=selected.mapNotNull{it.date};YText("${dates.minOrNull()?:"—"} ～ ${dates.maxOrNull()?:"—"}",12)
        for(kind in listOf("EXPENSE","INCOME")) {
            val rows=selected.filter{it.source.kind==kind}
            YText((if(kind=="INCOME")word("收入","収入")else word("支出","支出"))+" · ${money(rows)}",16)
            val historical=rows.filter{it.historical};val normal=rows.filterNot{it.historical}
            YText(word("已包含期初：${historical.size} 笔 / ${money(historical)}","期首に含む：${historical.size} 件 / ${money(historical)}"),12)
            YText(word("会改变账户余额：${normal.size} 笔 / ${money(normal)}","口座残高が変わる：${normal.size} 件 / ${money(normal)}"),12,color=LocalYohaku.current.warm)
        }
        val new=selected.mapNotNull{r->r.newCategory?.let{r.source.kind to it}}.distinct()
        YText(word("将新建分类：","新規カテゴリ：")+new.joinToString("、"){(kind,name)->(if(kind=="INCOME")incomeLabel else expenseLabel)+" / "+name}.ifEmpty{"—"},12)
    }
    var all by remember{mutableStateOf(false)}
    var page by remember{mutableIntStateOf(0)}
    YToggle(word("显示全部行（含已跳过）","全行を表示（スキップ済みも含む）"),all,{all=it;page=0})
    val rows=if(all)preview.rows else preview.rows.filter{it.issues.isNotEmpty()||it.duplicate}
    var editing by remember{mutableStateOf<CsvPreviewRow?>(null)}
    YText(word("问题 / 疑似重复：${preview.rows.count{it.issues.isNotEmpty()||it.duplicate}} 行","問題・重複候補：${preview.rows.count{it.issues.isNotEmpty()||it.duplicate}} 行"),14)
    rows.drop(page*20).take(20).forEach{row->Panel {
        YText("#${row.source.row+if(state.document?.header==true)2 else 1} · ${row.source.date} · ${row.source.amount}",14)
        YText(row.source.category+" · "+row.source.note,12)
        row.issues.forEach{YText(csvMessage(it),12,color=LocalYohaku.current.warm)}
        if(row.duplicate)YText(word("与已有账目疑似重复，默认跳过。文件内部相同行不去重。","既存記録との重複候補のため、既定でスキップ。ファイル内の同じ行は除外しません。"),12)
        if(row.source.kind=="SKIP")YText(word("已按收支规则跳过；要导入请返回修改类型对应。","収支ルールでスキップします。移行する場合は区分の対応を変更してください。"),12)
        else YToggle(word("跳过此行","この行をスキップ"),row.skipped,{onEdit(row.source.row,(state.edits[row.source.row]?:CsvEdit()).copy(skip=it))})
        YButton(word("修改金额 / 日期","金額・日付を修正"),{editing=row})
    }}
    CsvPager(page,rows.size,20){page=it}
    editing?.let{row->
        var date by remember(row.source.row){mutableStateOf(row.source.date)}
        var amount by remember(row.source.row){mutableStateOf(row.source.amount)}
        YSheet(word("修正此行","この行を修正"),{editing=null}) {
            YField(word("日期","日付"),date,{date=it})
            YField(word("金额","金額"),amount,{amount=it})
            YButton(word("保存并重新检查","保存して再確認"),{editing=null;onEdit(row.source.row,(state.edits[row.source.row]?:CsvEdit()).copy(date=date,amount=amount))},primary=true)
        }
    }
}
@Composable private fun csvMessage(message:String)=if(LocalLanguage.current=="ja")message.substringAfter(" / ",message)else message.substringBefore(" / ")
@Composable private fun CsvPager(page:Int,count:Int,size:Int,onPage:(Int)->Unit) {
    if(count>size)YActionRow {
        YButton(word("上一页","前へ"),{onPage((page-1).coerceAtLeast(0))},enabled=page>0)
        YText("${page+1} / ${(count+size-1)/size}",12)
        YButton(word("下一页","次へ"),{onPage(page+1)},enabled=(page+1)*size<count)
    }
}
