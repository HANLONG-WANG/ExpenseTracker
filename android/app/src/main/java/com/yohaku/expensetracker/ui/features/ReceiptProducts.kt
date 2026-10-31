package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.ReceiptItemDocumentEntity
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.encodeToString
import androidx.compose.ui.unit.dp

/** Preserve decimal unit prices, including fractional JPY, while displaying currency grouping. */
internal fun receiptCurrencyText(value:String,currency:String):String {
    val c=Currency.valueOf(currency)
    val unsigned=value.removePrefix("-")
    val whole=java.text.DecimalFormat("#,##0",java.text.DecimalFormatSymbols(java.util.Locale.JAPAN)).format(unsigned.substringBefore('.').toBigInteger())
    val fraction=unsigned.substringAfter('.',"").padEnd(c.scale,'0')
    return (if(value.startsWith("-"))"−"else "")+c.symbol+whole+(if(fraction.isNotEmpty())".$fraction"else "")
}
@Composable internal fun ReceiptItemRows(record:ReceiptItemsRecord,knownSubtotal:java.math.BigDecimal) {
    val t=LocalYohaku.current
    val narrow=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp<=360
    val quantityWidth=if(narrow)23.dp else 28.dp
    val priceWidth=if(narrow)43.dp else 52.dp
    val amountWidth=if(narrow)47.dp else 57.dp
    val gap=if(narrow)5.dp else 8.dp
    val numberSize=if(narrow)9 else if(t.id=="journal")13 else 10
    val headerColor=if(t.id=="magazine")t.ink else if(t.id=="objects")Color(0xffdbc8a4)else t.line
    val rowColor=if(t.id=="journal")Color(0x59b4b9aa)else if(t.id=="objects")Color(0xffe6d9bd)else t.line
    Column(Modifier.fillMaxWidth().padding(top=7.dp)) {
        Row(Modifier.fillMaxWidth().drawBehind{drawLine(headerColor,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id!="magazine")PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(gap)) {
            YText(word("商品","商品"),if(t.id=="journal")10 else 8,Modifier.weight(1f),color=t.muted)
            YText(word("数量","数量"),if(t.id=="journal")10 else 8,Modifier.width(quantityWidth),color=t.muted,textAlign=TextAlign.End)
            YText(word("单价","単価"),if(t.id=="journal")10 else 8,Modifier.width(priceWidth),color=t.muted,textAlign=TextAlign.End)
            YText(word("金额","金額"),if(t.id=="journal")10 else 8,Modifier.width(amountWidth),color=t.muted,textAlign=TextAlign.End)
        }
        record.items.forEach { item ->
            Row(Modifier.fillMaxWidth().drawBehind{drawLine(rowColor,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id=="objects")PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(vertical=13.dp),horizontalArrangement=Arrangement.spacedBy(gap),verticalAlignment=androidx.compose.ui.Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    YText(item.name?:word("名称待补","名称未入力"),if(t.id=="journal")if(narrow)12 else 13 else if(t.id=="objects")10 else 11,weight=if(t.id=="journal")FontWeight.Normal else FontWeight.Medium,lineHeightMultiplier=1.7f)
                    item.unit?.let{YText(word("单位：","単位：")+it,if(t.id=="journal")10 else 8,color=t.muted)}
                    if(item.uncertain_fields.isNotEmpty())YText(word("不确定：","不確か：")+item.uncertain_fields.joinToString(", "),10,color=t.warm)
                    item.note?.let{YText(it,10,color=t.muted)}
                }
                YText(item.quantity?:"—",numberSize,Modifier.width(quantityWidth),number=true,textAlign=TextAlign.End,lineHeightMultiplier=1.7f)
                YText(item.unit_price?.let{receiptCurrencyText(it,record.currency)}?:"—",numberSize,Modifier.width(priceWidth),number=true,textAlign=TextAlign.End,lineHeightMultiplier=1.7f)
                YText(item.line_amount?.let{receiptCurrencyText(it,record.currency)}?:"—",numberSize,Modifier.width(amountWidth),number=true,textAlign=TextAlign.End,lineHeightMultiplier=1.7f)
            }
        }
        if(record.items.isEmpty())YText(word("尚无商品行","商品行はありません"),12,color=t.muted)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth().drawBehind{drawLine(if(t.id=="objects")Color(0xffc6b289)else if(t.id=="magazine")t.ink else t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=if(t.id!="magazine")PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))else null)}.padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            YText(word("商品已知小计","商品の既知小計"),if(t.id=="journal")13 else 10,Modifier.weight(1f))
            YText(receiptCurrencyText(knownSubtotal.toPlainString(),record.currency),if(t.id=="journal")23 else 18,number=true)
        }
    }
}
@Composable private fun ReceiptTotalRow(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        YText(label,11,Modifier.weight(1f),color=LocalYohaku.current.muted)
        YText(value,12,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.End)
    }
}
@Composable internal fun ReceiptTotalLines(record:ReceiptItemsRecord,totals:ReceiptTotals) {
    record.adjustments.forEach { adjustment ->
        val type=when(adjustment.type){"tax"->word("税费","税");"discount"->word("折扣","割引");"shipping"->word("运费","送料");else->word("其他调整","その他調整")}
        ReceiptTotalRow(type+if(adjustment.included_in_items)word(" · 已含商品行","・商品行に含む")else word(" · 另计","・別計"),(if(adjustment.type=="discount")"−"else "+")+adjustment.amount)
        adjustment.note?.let{YText(it,10,color=LocalYohaku.current.muted)}
    }
    if(record.adjustments.any{!it.included_in_items})ReceiptTotalRow(word("已知合计","既知合計"),receiptCurrencyText(totals.knownTotal.toPlainString(),record.currency))
    ReceiptTotalRow(word("票面合计","レシート合計"),totals.declaredTotal?.let{receiptCurrencyText(it.toPlainString(),record.currency)}?:word("未知","不明"))
    if(!totals.complete)YText(word("明细不完整，未知金额未纳入小计。","明細は未完了です。不明額は小計に含みません。"),11,color=LocalYohaku.current.warm)
    val difference=totals.differenceFromOriginal
    YText(when {
        difference==null->word("商品币种与当前原记账不同，不直接比较金额。","商品と現在の元記録の通貨が異なるため、金額は直接比較しません。")
        difference.signum()==0->word("商品合计与原消费金额一致","商品合計と元の支出額が一致")
        else->word("已知合计与原消费相差 ","既知合計と元の支出の差額 ")+receiptCurrencyText(difference.toPlainString(),record.currency)+word("。保存明细不修改记账金额。","。記録額は変更しません。")
    },12,color=if(difference?.signum()==0)LocalYohaku.current.accent else LocalYohaku.current.warm)
    totals.differenceFromDeclared?.takeIf{it.signum()!=0}?.let{YText(word("与票面合计差额：","レシート合計との差額：")+receiptCurrencyText(it.toPlainString(),record.currency),11,color=LocalYohaku.current.warm)}
    record.issues.forEach{YText(it,11,color=LocalYohaku.current.warm)}
}

@Composable internal fun ReceiptProducts(s:BookSnapshot,eventId:String,vm:AppViewModel) {
    val documents by remember(s.book.id){vm.repository.db.fileWorkflowDao().items(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    val receipt=s.receipts.find{it.eventId==eventId}?:return
    val document=documents.find{it.receiptId==receipt.id}?:return
    val record=remember(document.recordJson){FileProtocols.json.decodeFromString<ReceiptItemsRecord>(document.recordJson)}
    val event=s.visibleEvents.find{it.id==eventId}?:return
    var editor by rememberSaveable(receipt.id){mutableStateOf(false)}
    ThemeSection(word("买过的东西","買ったもの"),"RECEIPT ITEMS")
    val totals=receiptTotals(record,FileMoney(event.currency,Money(Currency.valueOf(event.currency),event.amountMinor).decimal.toPlainString()))
    ReceiptPaper {
        if(event.revision!=record.record_revision||receipt.revision!=record.receipt_revision)YText(word("原记账或图片在整理后已变化，请核对现有明细。","整理後に元記録・画像が変わっています。既存明細を確認してください。"),11,color=LocalYohaku.current.warm)
        ReceiptItemRows(record,totals.knownItems)
        ReceiptTotalLines(record,totals)
        if(s.entryReceiptImages(eventId).any{it.id !in record.image_ids})YText(word("还有新增图片未整理。","追加された画像が未整理です。"),11,color=LocalYohaku.current.warm)
        YButton(word("编辑商品明细","商品明細を編集"),{editor=true},Modifier.testTag("receipt-edit-items"))
    }
    if(editor)ReceiptItemEditor(s,document,record,vm,{editor=false})
}

@Composable private fun ReceiptItemEditor(s:BookSnapshot,document:ReceiptItemDocumentEntity,original:ReceiptItemsRecord,vm:AppViewModel,onClose:()->Unit) {
    val baseRevision=rememberSaveable(document.receiptId){document.revision}
    var raw by rememberSaveable(document.receiptId){mutableStateOf(FileProtocols.json.encodeToString(original))}
    val edited=remember(raw){FileProtocols.json.decodeFromString<ReceiptItemsRecord>(raw)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    fun update(record:ReceiptItemsRecord){raw=FileProtocols.json.encodeToString(record)}
    fun updateItem(index:Int,transform:(ReceiptItem)->ReceiptItem){update(edited.copy(items=edited.items.mapIndexed{i,item->if(i==index)transform(item)else item}))}
    YSheet(word("编辑买过的东西","買ったものを編集"),{if(!busy)onClose()}) {
        YText("ITEMS / PERSONAL NOTES",8,color=LocalYohaku.current.muted)
        YText(word("修改商品信息，不改变整单金额或分类；留空表示未知。","商品情報のみ編集。記録金額・カテゴリは変更しません。空欄は不明として保持します。"),10,color=LocalYohaku.current.muted)
        edited.items.forEachIndexed { index,item -> key(item.item_id){ ReceiptEditorItem(index,{update(edited.copy(items=edited.items.filterIndexed{i,_->i!=index}))},!busy) {
            YField(word("商品名","商品名"),item.name.orEmpty(),{v->updateItem(index){it.copy(name=v.ifBlank{null})}})
            val narrow=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp<=360
            @Composable fun quantity(modifier:Modifier){YField(word("数量","数量"),item.quantity.orEmpty(),{v->updateItem(index){it.copy(quantity=v.ifBlank{null})}},modifier,numeric=true)}
            @Composable fun price(modifier:Modifier){YField(word("单价","単価"),item.unit_price.orEmpty(),{v->updateItem(index){it.copy(unit_price=v.ifBlank{null})}},modifier,numeric=true)}
            @Composable fun lineAmount(modifier:Modifier){YField(word("行金额","行の金額"),item.line_amount.orEmpty(),{v->updateItem(index){it.copy(line_amount=v.ifBlank{null})}},modifier,numeric=true)}
            if(narrow)Column(verticalArrangement=Arrangement.spacedBy(9.dp)){quantity(Modifier);price(Modifier);lineAmount(Modifier)}
            else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){quantity(Modifier.weight(1f));price(Modifier.weight(1f));lineAmount(Modifier.weight(1f))}
            YField(word("单位","単位"),item.unit.orEmpty(),{v->updateItem(index){it.copy(unit=v.ifBlank{null})}})
            YField(word("不确定字段（逗号分隔）","不確かな項目（カンマ区切り）"),item.uncertain_fields.joinToString(","),{v->updateItem(index){it.copy(uncertain_fields=v.split(',').map{it.trim()}.filter{it.isNotEmpty()})}})
            YField(word("说明","説明"),item.note.orEmpty(),{v->updateItem(index){it.copy(note=v.ifBlank{null})}},multiline=true)
        } } }
        YButton(word("＋ 增加商品","＋ 商品を追加"),{update(edited.copy(items=edited.items+ReceiptItem(vm.repository.id(),edited.image_ids,emptyList())))},Modifier.testTag("receipt-add-item"),enabled=!busy)
        ThemeSection(word("税费与调整","税・調整"),"ADJUSTMENTS")
        edited.adjustments.forEachIndexed { index,a -> ReceiptPaper {
            YSelect(word("调整类型","調整種別"),a.type,listOf("tax" to word("税费","税"),"discount" to word("折扣","割引"),"shipping" to word("运费","送料"),"other" to word("其他增加","その他加算")),{type->update(edited.copy(adjustments=edited.adjustments.mapIndexed{i,v->if(i==index)v.copy(type=type)else v}))})
            YField(word("调整金额","調整額"),a.amount,{amount->update(edited.copy(adjustments=edited.adjustments.mapIndexed{i,v->if(i==index)v.copy(amount=amount)else v}))},numeric=true)
            YToggle(word("已含于商品行","商品行に含まれる"),a.included_in_items,{included->update(edited.copy(adjustments=edited.adjustments.mapIndexed{i,v->if(i==index)v.copy(included_in_items=included)else v}))})
            YButton(word("移除此调整","この調整を削除"),{update(edited.copy(adjustments=edited.adjustments.filterIndexed{i,_->i!=index}))})
        } }
        YButton(word("＋ 添加税费或折扣","＋ 税・割引を追加"),{update(edited.copy(adjustments=edited.adjustments+ReceiptAdjustment("tax","",false)))},enabled=!busy)
        YField(word("票面合计","レシート合計"),edited.receipt_total.orEmpty(),{update(edited.copy(receipt_total=it.ifBlank{null}))},numeric=true)
        YField(word("待确认说明（每行一项）","確認事項（1行1件）"),edited.issues.joinToString("\n"),{update(edited.copy(issues=it.lines().filter{line->line.isNotBlank()}))},multiline=true)
        YToggle(word("明细已补全","明細を補完済み"),edited.complete,{update(edited.copy(complete=it))})
        YActionRow {
            YButton(word("保存商品明细","商品明細を保存"),{vm.execute(onSuccess=onClose){vm.repository.editReceiptItems(s.book.id,document.receiptId,baseRevision,edited)}},Modifier.testTag("receipt-save-item-edits"),primary=true,enabled=!busy)
            YButton(word("取消修改","変更を取消"),onClose,enabled=!busy)
        }
    }
}
