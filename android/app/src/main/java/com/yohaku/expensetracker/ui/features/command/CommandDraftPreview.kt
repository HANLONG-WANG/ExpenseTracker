package com.yohaku.expensetracker.ui.features.command

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.features.RecordFacts
import com.yohaku.expensetracker.ui.features.receiptCurrencyText
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.json.*

/** Display the saved proposal itself. A deferred draft is not a set of existing ledger targets.
 * Do not infer attachments from sibling steps: attachSource targets a record, not this draft. */
@Composable internal fun CommandDraftPreview(step:StepEffect,s:BookSnapshot,model:ConfirmationModel) {
    val draft=step.objects.firstOrNull{it.type=="entryDraft"}?.after as? JsonObject?:return
    val pending=word("待你补完","ご自身で補完")
    fun text(key:String)=draft[key]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}
    fun accountCurrency(key:String):String? {
        val id=text(key)?:return null
        return s.accounts.firstOrNull{it.id==id}?.currency?:(model.steps.flatMap{it.objects}+model.net)
            .firstOrNull{it.type=="account"&&it.key==id}?.let{(it.after as? JsonObject)?:it.before as? JsonObject}
            ?.get("currency")?.jsonPrimitive?.contentOrNull
    }
    fun amount(key:String,currency:String?)=text(key)?.let{if(currency==null)"$it · $pending"else receiptCurrencyText(it,currency)}?:pending
    fun name(key:String)=text(key)?.let{resolveName(it,s,model)}?:pending
    val currency=text("originalCurrency")?:accountCurrency("accountId")
    val transfer=text("kind")=="TRANSFER"
    Column(Modifier.testTag("command-draft-preview-${step.stepId}")) {
        DraftPreviewFact(word("种类","種類"),fieldValue((draft["kind"]?:JsonNull) to null,"kind",s.book.currency,s,model),"kind")
        DraftPreviewFact(word("金额","金額"),amount("amount",currency),"amount")
        DraftPreviewFact(if(transfer)word("转出账户","出金口座")else word("账户","口座"),name("accountId"),"account")
        if(!transfer)DraftPreviewFact(word("分类","カテゴリ"),name("categoryId"),"category")
        DraftPreviewFact(word("日期","日付"),text("date")?:pending,"date")
        DraftPreviewFact(word("商户","お店"),text("merchant")?:pending,"merchant")
        DraftPreviewFact(word("备注","メモ"),text("note")?:pending,"note")
        if(transfer) {
            DraftPreviewFact(word("转入账户","入金口座"),name("toAccountId"),"toAccount")
            DraftPreviewFact(word("转入金额","入金額"),amount("toAmount",accountCurrency("toAccountId")),"toAmount")
            DraftPreviewFact(word("到账日期","入金日"),text("toDate")?:pending,"toDate")
        }
        if(text("accountAmount")!=null)DraftPreviewFact(word("账户币金额","口座通貨額"),amount("accountAmount",accountCurrency("accountId")),"accountAmount")
        if(text("baseAmount")!=null)DraftPreviewFact(word("本位币金额","基準通貨額"),amount("baseAmount",s.book.currency),"baseAmount")
        if(text("settlementDate")!=null&&text("settlementDate")!=text("date"))DraftPreviewFact(word("入账日期","計上日"),text("settlementDate")!!,"settlementDate")
        for(key in listOf("originalEventId","activityId","occurrenceId","reserveId","cardPaymentChoice","cardPaymentPeriods","cardPaymentMonth","cardPaymentFee","feeAmount","manualRate")) {
            if(text(key)!=null) {
                val label=when(key){"feeAmount"->word("手续费","手数料");"cardPaymentFee"->word("付款费用","支払手数料");else->fieldName(key)}
                val value=when(key){"feeAmount"->amount(key,text("feeCurrency")?:currency);"cardPaymentFee"->amount(key,accountCurrency("accountId"));else->fieldValue(draft.getValue(key) to null,key,currency?:s.book.currency,s,model)}
                DraftPreviewFact(label,value,key)
            }
        }
        val files=listOf("attachmentIds","receiptFileIds").flatMap{key->(draft[key] as? JsonArray).orEmpty().map{it.jsonPrimitive.content}}.distinct()
        val media=text("requestedMedia")?:"NONE"
        DraftPreviewFact(word("将附上的文件","添付するファイル"),if(files.isEmpty()) {
            if(media=="NONE")word("未指定；不会自动附上任务源文件","未指定・タスクの元ファイルは自動添付しません")else word("待你在本机选择或拍摄","端末で選択・撮影してください")
        }else files.joinToString("、"){resolveName(it,s,model)},"files")
        if(media!="NONE")DraftPreviewFact(word("本机补充方式","端末での補完方法"),fieldValue(JsonPrimitive(media) to null,"requestedMedia",s.book.currency,s,model),"media")
        YText(word("只准备草稿；补全后仍需你亲自保存。","下書きのみ用意します。補完後、ご自身で保存してください。"),11,color=LocalYohaku.current.muted)
    }
}

@Composable private fun DraftPreviewFact(label:String,value:String,tag:String) {
    Column(Modifier.testTag("command-draft-field-$tag")){RecordFacts(listOf(label to value))}
}
