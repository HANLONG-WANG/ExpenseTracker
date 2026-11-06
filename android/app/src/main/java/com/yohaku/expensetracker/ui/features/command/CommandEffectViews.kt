package com.yohaku.expensetracker.ui.features.command

import com.yohaku.expensetracker.ui.haptics.Haptic
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.features.financeLabel
import com.yohaku.expensetracker.ui.features.cashIssue
import com.yohaku.expensetracker.ui.features.limitLabel
import com.yohaku.expensetracker.ui.features.RecordFacts
import com.yohaku.expensetracker.ui.features.FinancialDisplayFacts
import com.yohaku.expensetracker.ui.features.FinancialLimitRelationship
import com.yohaku.expensetracker.ui.features.FinancialBatchSummary
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.json.*

@Composable internal fun StepSummary(step:StepEffect,s:BookSnapshot,model:ConfirmationModel) {
    if(step.command=="entry.prepareDraft"){CommandDraftPreview(step,s,model);return}
    if(step.command in setOf("financialRepayment.save","financialRepayment.confirm")&&step.objects.any{it.type=="entry"&&it.after is JsonObject}){CommandRepaymentSummary(step,s,model);CommandFinancialChanges(step,s,model);return}
    CommandFinancialChanges(step,s,model)
    val changed=step.objects.filter{it.before!=it.after}
    val restoredCategories=if(step.command in setOf("local.restoreExecution","local.entry.restoreCategory"))changed.filter {
        it.type=="entry"&&it.before is JsonObject&&it.after is JsonObject&&it.before["categoryId"]!=it.after["categoryId"]
    }else emptyList()
    if(restoredCategories.isNotEmpty())CategoryRestoreSummary(restoredCategories,s,model)
    val summarizedPlans=step.objects.filter{it.type=="prediction"&&it.after is JsonObject}.map{it.key}.toSet()
    changed.filterNot{effect->
        effect.after is JsonObject&&(effect.type in setOf("prediction","creditLimitConfig","creditLimitGroupSetting","arrangementReconciliation")||effect.type=="bill"&&effect.after["planId"]?.jsonPrimitive?.contentOrNull in summarizedPlans)
    }.filter{it !in restoredCategories&&(it.type in workflowTypes||it.type in financeObjectTypes||it.type in setOf("monthlyPlan","book","account","category","merchant","merchantLocation","friend","entry","template","rule","prediction","bill","budgetSetting","activity","activityBalance","reserve","treat","recurrence","occurrence","expectedIncome","activityMode","activityParticipant"))}.take(3).forEach{EffectSummary(it,s,model)}
}

@Composable private fun CategoryRestoreSummary(entries:List<ObjectEffect>,s:BookSnapshot,model:ConfirmationModel) {
    val uncategorized=word("未分类","未分類")
    fun category(value:JsonElement)=value.jsonObject["categoryId"]?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)}?:uncategorized
    Column(Modifier.testTag("command-category-restoration"),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        YText(word("${entries.size} 笔记录将恢复分类","${entries.size} 件のカテゴリを元に戻します"),15)
        entries.take(3).forEach{entry->
            val value=entry.after.jsonObject
            val amount=Money(Currency.valueOf(value.getValue("currency").jsonPrimitive.content),value.getValue("amountMinor").jsonPrimitive.long).display()
            YText(resolveName(entry.key,s,model)+" · "+amount,12)
            YText(category(entry.before)+" → "+category(entry.after),12,color=LocalYohaku.current.muted)
        }
        if(entries.size>3)YText(word("其余 ${entries.size-3} 笔可在逐项详情中核对。","残り ${entries.size-3} 件は項目別の詳細で確認できます。"),11)
    }
}

@Composable internal fun EffectSummary(effect:ObjectEffect,s:BookSnapshot,model:ConfirmationModel) {
    if(effect.type in workflowTypes){WorkflowSummary(effect,s,model);return}
    val before=effect.before as? JsonObject;val after=effect.after as? JsonObject
    val title=effectTitle(effect,s,model)
    val currency=(after?.get("currency")?:before?.get("currency"))?.jsonPrimitive?.contentOrNull?:s.book.currency
    fun amount(v:JsonElement?)=v?.jsonPrimitive?.longOrNull?.let{Money(Currency.valueOf(currency),it).display()}
    val none=word("无","なし");val unknown=word("未知","不明");val confirmedLabel=word("已确认","確定");val predictedLabel=word("预测","予測");val unset=word("未设定","未設定")
    val text=if(effect.type in financeObjectTypes) {
        val created=word("尚未创建","未作成");val removed=word("移除本方案新建对象","このプランで作成した対象を削除")
        val verified=word("日期一致","日付一致");val differs=word("日期有差异","日付に相違")
        fun finance(v:JsonObject?):String?=v?.let{row->
            val moneyKey=when(effect.type){"ruleValidation"->"totalDifferenceMinor";"contractBalance"->"remainingMinor";"creditLimitObservation"->"availableMinor";"creditLimitGroupSetting","creditLimitConfig"->"totalMinor";else->"principalMinor"}
            listOfNotNull(row["name"]?.jsonPrimitive?.contentOrNull,
                (row["date"]?:row["effectiveDate"]?:row["basisDate"]?:row["clearingDate"])?.jsonPrimitive?.contentOrNull,
                row["observedAt"]?.jsonPrimitive?.contentOrNull?.let{FinancialDisplayFacts.time(java.time.Instant.parse(it),java.time.OffsetDateTime.parse(it).offset,s.book.language)},
                if(moneyKey in row)amount(row[moneyKey])?:unknown else null,
                if(effect.type=="ruleValidation")if(row["dateChanged"]==JsonPrimitive(true))differs else verified else null,
                row["accountId"]?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)},
                if(effect.type=="financialDraft")(row["valuesJson"] as? JsonObject)?.get("category")?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)}else null
            ).joinToString(" · ").ifBlank{title}
        }
        (finance(before)?:created)+" → "+(finance(after)?:removed)
    }else when(effect.type) {
        "monthlyPlan"->(before?.get("text")?.jsonPrimitive?.content?:unset)+" → "+(after?.get("text")?.jsonPrimitive?.content?:unset)
        "balance"->(amount(before?.get("ledgerMinor"))?:word("无","なし"))+" → "+(amount(after?.get("ledgerMinor"))?:word("未知","不明"))
        "bill"->{
            fun bill(v:JsonObject?):String=if(v==null)none else {
                val confirmed=v["confirmed"]?.jsonPrimitive?.booleanOrNull==true
                val date=v[if(confirmed)"confirmedDate"else"predictedDate"]?.jsonPrimitive?.contentOrNull.orEmpty()
                date+" · "+(amount(v[if(confirmed)"confirmedTotalMinor"else"predictedTotalMinor"])?:unknown)+" · "+if(confirmed)confirmedLabel else predictedLabel
            }
            bill(before)+" → "+bill(after)
        }
        "activityBalance"->word("预留 ","確保 ")+(amount(before?.get("reserveMinor"))?:none)+" → "+(amount(after?.get("reserveMinor"))?:none)+"\n"+
            word("预留后可用 ","確保後の残額 ")+(amount(before?.get("availableMinor"))?:unset)+" → "+(amount(after?.get("availableMinor"))?:unset)
        "budget"->{
            fun remaining(v:JsonObject?)=amount(v?.get("remainingMinor"))?:unset
            word("剩余 ","残り ")+remaining(before)+" → "+remaining(after)
        }
        "budgetSetting","activityBudget"->(amount(before?.get("amountMinor"))?:unset)+" → "+(amount(after?.get("amountMinor"))?:unset)
        "activity","reserve","treat","recurrence","occurrence","expectedIncome"->{
            val removed=word("移除本方案新建对象","このプランで作成した対象を削除")
            val created=word("尚未创建","未作成")
            val ended=word("已结束","終了済み")
            fun facts(v:JsonObject?):String?=v?.let{value->
                listOfNotNull(value["name"]?.jsonPrimitive?.contentOrNull,
                    (value["scheduledDate"]?:value["date"]?:value["startDate"])?.jsonPrimitive?.contentOrNull,
                    amount(value[if(effect.type=="activity")"budgetMinor"else"amountMinor"]),
                    if(value["ended"]==JsonPrimitive(true))ended else null).joinToString(" · ")
            }
            (facts(before)?:created)+" → "+(facts(after)?:removed)
        }
        "activityMode"->fieldValue(before?.get("activityMode")?.let{it to null},"activityMode",currency,s,model)+" → "+fieldValue(after?.get("activityMode")?.let{it to null},"activityMode",currency,s,model)
        "activityParticipant"->(after?:before)?.get("friendId")?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)}.orEmpty()
        "creditLimit","creditLimitGroup"->{
            fun available(v:JsonObject?)=amount((v?.get("total") as? JsonObject)?.get("availableMinor"))?:unknown
            word("可用 ","利用可能 ")+available(before)+" → "+available(after)
        }
        "book","category","merchant","merchantLocation","friend","template"-> {
            val changed=if(after==null)word("将移除本方案新建对象","このプランで作成した対象を削除")else if(before==null)word("新建","新規")else word("更新","更新")
            val name=(after?:before)?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val state=(after?:before)?.let{v->when {v["deletedAt"]!=null&&v["deletedAt"]!=JsonNull->word("回收站","ごみ箱");v["archived"]==JsonPrimitive(true)||v["active"]==JsonPrimitive(false)->word("已归档 / 停用","アーカイブ・停止中");else->""}}.orEmpty()
            listOf(changed,name,state).filter{it.isNotEmpty()}.joinToString(" · ")
        }
        "entry"->{
            val trash=word("回收站","ごみ箱")
            fun entry(v:JsonObject?)=if(v==null)none else listOfNotNull(v["date"]?.jsonPrimitive?.contentOrNull,v["merchant"]?.jsonPrimitive?.contentOrNull,amount(v["amountMinor"]),if(v["deletedAt"]!=null&&v["deletedAt"]!=JsonNull)trash else null).filter{it.isNotBlank()}.joinToString(" · ")
            entry(before)+" → "+entry(after)
        }
        "account"->(before?.get("name")?.jsonPrimitive?.contentOrNull?:word("新建","新規"))+" → "+(after?.get("name")?.jsonPrimitive?.contentOrNull?:word("移除方案新建账户","プランで作成した口座を削除"))
        "rule"->if(after==null)word("将移除版本 ","削除するバージョン ")+(before?.get("version")?.jsonPrimitive?.content.orEmpty())else word("新增版本 ","新しいバージョン ")+(after.get("version")?.jsonPrimitive?.content.orEmpty())
        "prediction"->{
            fun plan(v:JsonObject?)=v?.let{(it["name"]?.jsonPrimitive?.contentOrNull.orEmpty())+" · "+(amount(it["principalMinor"])?:unknown)}
            (plan(before)?:word("尚未创建","未作成"))+" → "+(plan(after)?:word("移除本方案新建计划","このプランで作成したプランを削除"))
        }
        else->word("查看逐项前后值","各項目の変更前後を見る")
    }
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        YText(title,12,color=LocalYohaku.current.muted);YText(text,14,number=effect.type in setOf("balance","bill","budget","creditLimit"))
        if(effect.type=="creditLimitGroup") {
            val row=after?:before
            YText(word("跨账户共享组上限：","口座間の共通枠上限：")+(amount((row?.get("group") as? JsonObject)?.get("totalMinor"))?:unknown),12)
        }
        if(effect.type=="creditLimit") {
            FinancialLimitRelationship(FinancialDisplayFacts.limitRelationship((after?:before)?.get("config") as? JsonObject),Currency.valueOf(currency))
            (after?:before)?.get("groupId")?.jsonPrimitive?.contentOrNull?.let{groupId->
                YText(word("共享组限制：","共通枠の制限：")+resolveName(groupId,s,model),11)
                YText(word("限制后本账户预计可用：","制限後の当口座の予測利用可能額：")+(amount((after?:before)?.get("effectiveTotalMinor"))?:unknown),12)
            }
        }
    }
}

@Composable internal fun CommandEffectsSheet(effects:List<ObjectEffect>,s:BookSnapshot,model:ConfirmationModel,onClose:()->Unit) {
    var page by remember{mutableIntStateOf(0)}
    val pages=(effects.size+11)/12
    YSheet(word("逐项核对","項目ごとの確認"),onClose) {
        YText(word("显示绝对值；左侧为操作前，右侧为操作后。未变化的相关值也参与确认。","絶対値で表示します。左は変更前、右は変更後。変わらない関連値も確定時に照合します。"),11)
        effects.drop(page*12).take(12).forEach{effect->Panel {
            YText(effectTitle(effect,s,model),17)
            if(effect.type=="revolvingBatchArchive") {
                FinancialBatchSummary(commandJson.decodeFromJsonElement(com.yohaku.expensetracker.data.repository.BatchRestorationFacts.serializer(),effect.before))
            } else {
            if(effect.type=="creditLimit") {
                YText(word("变更后额度关系","変更後の枠の関係"),12)
                val row=(effect.after as? JsonObject)?:effect.before as? JsonObject
                FinancialLimitRelationship(FinancialDisplayFacts.limitRelationship(row?.get("config") as? JsonObject),Currency.valueOf(row?.get("currency")?.jsonPrimitive?.contentOrNull?:s.book.currency))
            }
            val before=flattenFacts(workflowDisplayFacts(effect.type,effect.before));val after=flattenFacts(workflowDisplayFacts(effect.type,effect.after))
            val fields=(before.keys+after.keys).toList()
            if(fields.isEmpty())YText(word("没有明细","明細なし"),12)
            for(path in fields) {
                val old=before[path];val new=after[path]
                val baseCurrency=(effect.after as? JsonObject)?.get("currency")?.jsonPrimitive?.contentOrNull
                    ?:(effect.before as? JsonObject)?.get("currency")?.jsonPrimitive?.contentOrNull
                    ?:s.accounts.firstOrNull{it.id==effect.key}?.currency?:s.book.currency
                val leaf=path.substringAfterLast('.')
                val key=if(effect.type=="saveOrder")"eventId"else if(leaf.toIntOrNull()!=null)path.substringBeforeLast('.').substringAfterLast('.')else leaf
                val label=path.split('.').map{part->when {
                    part=="ME"->word("本人","自分")
                    part.startsWith("@")||part.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}"))->resolveName(part,s,model)
                    else->fieldName(if(part=="position"&&effect.type in setOf("activityParticipant","receipt_images"))"sortOrder"else part)
                }}.joinToString(" / ")
                val a=fieldValue(old,key,baseCurrency,s,model,(effect.before as? JsonObject)?.takeIf{key=="appliedAt"}?.let{commandFieldZone(it,path,effects,after=false)})
                val b=fieldValue(new,key,baseCurrency,s,model,(effect.after as? JsonObject)?.takeIf{key=="appliedAt"}?.let{commandFieldZone(it,path,effects,after=true)})
                val displayLabel=if(effect.type=="creditLimit"&&path in setOf("installment.capacityMinor","revolving.capacityMinor"))label+word("（计算容量，受总额度约束）","（計算上の容量・総枠で制限）")else label
                RecordFacts(listOf(displayLabel to "$a → $b"),audit=true)
            }
            }
        }}
        if(pages>1)YActionRow {
            YButton(word("上一页","前へ"),{page--},enabled=page>0,haptic=Haptic.Step)
            YText("${page+1} / $pages",12,number=true)
            YButton(word("下一页","次へ"),{page++},enabled=page+1<pages,haptic=Haptic.Step)
        }
    }
}

private fun flattenFacts(value:JsonElement,prefix:String="",currency:String?=null):Map<String,Pair<JsonElement,String?>> = buildMap {
    if(prefix.isEmpty()&&value==JsonNull)return@buildMap
    when(value) {
        is JsonObject->{
            val unit=value["currency"]?.jsonPrimitive?.contentOrNull?:value["accountCurrency"]?.jsonPrimitive?.contentOrNull?:currency
            value.filterKeys{it !in setOf("id","bookId","createdAt","explanation","deletionGroupId","slot","basis","item_id","record_id","receipt_id","row_id","bundleId","checksum","sha256","mediaSession","path","undoGroupId")}.forEach{(key,v)->putAll(flattenFacts(v,if(prefix.isEmpty())key else "$prefix.$key",when(key){"accountMinor","expectedAccountMinor","accountDeltaMinor"->value["accountCurrency"]?.jsonPrimitive?.contentOrNull?:unit;"toMinor","expectedToMinor"->value["toCurrency"]?.jsonPrimitive?.contentOrNull?:unit;"originalMinor"->value["originalCurrency"]?.jsonPrimitive?.contentOrNull?:unit;else->unit}))}
        }
        is JsonArray->if(value.isEmpty())put(prefix,JsonPrimitive("EMPTY") to currency)else value.forEachIndexed{i,v->putAll(flattenFacts(v,if(prefix.isEmpty())"${i+1}"else"$prefix.${i+1}",currency))}
        else->put(prefix.ifEmpty{"value"},value to currency)
    }
}
internal fun resolveName(id:String,s:BookSnapshot,model:ConfirmationModel):String {
    fun label(zh:String,ja:String)=if(s.book.language=="ja")ja else zh
    val current=s.book.takeIf{it.id==id}?.name?:s.merchants.firstOrNull{it.id==id}?.name?:s.merchantLocations.firstOrNull{it.id==id}?.name?:s.friends.firstOrNull{it.id==id}?.name?:s.templates.firstOrNull{it.id==id}?.name?:s.accounts.firstOrNull{it.id==id}?.name?:s.categories.firstOrNull{it.id==id}?.let{s.path(it.id)}
        ?:s.events.firstOrNull{it.id==id}?.let{"${it.date} ${it.merchant}"}
        ?:s.activities.firstOrNull{it.id==id}?.name?:s.treats.firstOrNull{it.id==id}?.name?:s.occurrences.firstOrNull{it.id==id}?.let{it.name+" · "+it.scheduledDate}
        ?:s.recurrences.firstOrNull{it.id==id}?.name?:s.reserves.firstOrNull{it.id==id}?.name
        ?:s.receipts.firstOrNull{it.id==id}?.let{receipt->s.events.firstOrNull{it.id==receipt.eventId}?.let{it.date+" · "+it.merchant}}
        ?:s.storedPostings.firstOrNull{it.id==id}?.let{p->s.accounts.firstOrNull{it.id==p.accountId}?.name+" · "+p.date}
        ?:s.expectedIncomes.firstOrNull{it.id==id}?.name?:s.files.firstOrNull{it.id==id}?.originalName
    if(current!=null)return current
    return (model.steps.flatMap{it.objects}+model.net).firstNotNullOfOrNull{effect->
        if(effect.key!=id)null else {
            val value=(effect.after as? JsonObject)?:effect.before as? JsonObject
            (if(effect.type=="occurrence")value?.let{it["name"]?.jsonPrimitive?.contentOrNull+" · "+it["scheduledDate"]?.jsonPrimitive?.contentOrNull}else null)?:value?.get("name")?.jsonPrimitive?.contentOrNull?:(value?.get("group") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?:when(effect.type) {
                "monthlyPlan"->label("月度计划","月の計画")
                "preference"->workflowFieldLabels[id]?.let{label(it.first,it.second)}
                "csvBatch"->value?.get("fileName")?.jsonPrimitive?.contentOrNull
                "statementAssociation"->value?.let{listOfNotNull(it["sourceDate"]?.jsonPrimitive?.contentOrNull,it["sourceSummary"]?.jsonPrimitive?.contentOrNull).joinToString(" · ")}
                "receiptProducts"->value?.get("receiptId")?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)}
                "budgetSetting"->value?.let{it["month"]?.jsonPrimitive?.contentOrNull.orEmpty()+" · "+(it["categoryId"]?.jsonPrimitive?.contentOrNull?.let{category->resolveName(category,s,model)}?:label("月预算","月予算"))}
                "activityBudget","activityParticipant"->value?.get("activityId")?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)}
                "treat_allocations"->value?.get("treatId")?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)}
                "reserve_uses"->value?.get("reserveId")?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)}
                "activityMode"->label("活动记录模式","シーン記録モード")
                "entry"->value?.let{listOfNotNull(it["date"]?.jsonPrimitive?.contentOrNull,it["merchant"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()},it["note"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}).joinToString(" · ").ifBlank{label("记录","記録")}}
                "contractBalance"->value?.get("name")?.jsonPrimitive?.contentOrNull
                "ruleValidation"->value?.get("billId")?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)}
                "arrangement","arrangementReconciliation"->value?.let{(it["targetPlanId"]?:it["planId"])?.jsonPrimitive?.contentOrNull?.let{plan->resolveName(plan,s,model)}}
                "repayment","repaymentAllocation"->value?.let{(it["debtAccountId"]?:it["contractId"]?:it["billId"])?.jsonPrimitive?.contentOrNull?.let{target->resolveName(target,s,model)}}
                "creditLimitConfig","creditLimitRecovery","creditLimitObservation","creditLimitMember"->value?.let{(it["accountId"]?:it["groupId"])?.jsonPrimitive?.contentOrNull?.let{target->resolveName(target,s,model)}}
                "financialDraft"->label("预计收入草稿","収入予定の下書き")
                "rule"->value?.let{label("规则版本","条件バージョン")+" "+it["version"]?.jsonPrimitive?.content.orEmpty()+" · "+it["effectiveFrom"]?.jsonPrimitive?.content.orEmpty()}
                "bill"->value?.let{bill->
                    val planId=bill["planId"]?.jsonPrimitive?.contentOrNull
                    val plan=(model.steps.flatMap{it.objects}+model.net).firstOrNull{it.type=="prediction"&&it.key==planId}
                        ?.let{(it.after as? JsonObject)?:it.before as? JsonObject}
                    val account=plan?.get("accountId")?.jsonPrimitive?.contentOrNull?.let{resolveName(it,s,model)}
                    val period=label("第","第")+bill["number"]?.jsonPrimitive?.content.orEmpty()+label("期","回")
                    val date=bill["confirmedDate"]?.jsonPrimitive?.contentOrNull?:bill["predictedDate"]?.jsonPrimitive?.contentOrNull
                    listOfNotNull(account,planId?.let{resolveName(it,s,model)},period,date).joinToString(" · ")
                }
                else->null
            }
        }
    }?:label("关联对象","関連する対象")
}
@Composable internal fun fieldValue(value:Pair<JsonElement,String?>?,key:String,currency:String,s:BookSnapshot,model:ConfirmationModel,zone:java.time.ZoneId?=null):String {
    if(value==null)return word("无此项","項目なし")
    val raw=value.first
    if(raw is JsonArray&&raw.isEmpty())return word("无","なし")
    if(raw !is JsonPrimitive&&raw!=JsonNull)return readableCommandJson(raw,key,currency,s,model,zone)
    if(raw==JsonNull)return word("未知 / 未设定","不明・未設定")
    val text=(raw as? JsonPrimitive)?.content?:raw.toString()
    if((key.endsWith("Json")||key in setOf("json","encoded","sourceProof"))&&raw is JsonPrimitive&&raw.isString&&text.trimStart().let{it.startsWith("{")||it.startsWith("[")})return readableCommandJson(commandJson.parseToJsonElement(text),key,currency,s,model,zone)
    if(key in setOf("active","archived","initialized","favorite","historicalIncluded","budgetExcluded","confirmed","fromConfirmed","toConfirmed","complete")&&raw.jsonPrimitive.longOrNull!=null)return if(raw.jsonPrimitive.long==0L)word("否","いいえ")else word("是","はい")
    if(key.endsWith("At")&&raw.jsonPrimitive.longOrNull!=null)return word("记录时间：","記録日時：")+FinancialDisplayFacts.time(java.time.Instant.ofEpochMilli(raw.jsonPrimitive.long),java.time.ZoneId.systemDefault(),s.book.language)
    if(key in setOf("appliedAt","observedAt","recordedAt")&&raw.jsonPrimitive.isString)return (if(key=="recordedAt")word("记录时间：","記録日時：")else "")+FinancialDisplayFacts.time(java.time.Instant.parse(text),if(key=="observedAt")java.time.OffsetDateTime.parse(text).offset else zone?:java.time.OffsetDateTime.parse(text).offset,s.book.language)
    if((key.endsWith("Minor")||key=="minor")&&raw.jsonPrimitive.longOrNull!=null)return Money(Currency.valueOf(if(key in setOf("baseMinor","expectedBaseMinor"))s.book.currency else value.second?:currency),raw.jsonPrimitive.long).display()
    if(key in setOf("automaticRates","location","haptics","enabled"))return if(text=="true"||text=="1")word("开","オン")else word("关","オフ")
    if(key=="photoOriginal")return if(text=="true")word("原样保存","そのまま保存")else word("节省空间","省容量")
    if(key=="activeBook")return resolveName(text,s,model)
    if(key in setOf("modulesJson","pinnedJson"))return when(text){"CATEGORIES"->word("分类","カテゴリ");"MERCHANTS"->word("商户","お店");"TREND"->word("趋势","推移");"ACTIVITIES"->word("活动","シーン");"COMPARISON"->word("对比","比較");else->word("无","なし")}
    if(key=="uncertain_fields")return fieldName(text)
    if(key=="requestedMedia")return when(text){"ATTACHMENT"->word("在表单中选择附件","フォームで添付を選択");"RECEIPT_IMAGES"->word("在表单中选择小票图片","フォームでレシート画像を選択");"RECEIPT_CAMERA"->word("在表单中拍摄小票","フォームでレシートを撮影");else->word("补全记账资料","記録情報を補完")}
    if(key=="permission")return if(text=="LOCATION")word("前台定位","前景の位置情報")else word("系统通知","システム通知")
    if(key=="direction")return if(text=="in")word("流入","入金")else word("流出","出金")
    if(key=="mode"&&text in setOf("interactive","post-commit"))return if(text=="interactive")word("需要你在本机操作","端末での操作が必要")else word("提交后执行","保存後に実行")
    if(key=="theme")return when(text){"magazine"->word("杂志","マガジン");"journal"->word("手账","手帳");"objects"->word("小物","小物");else->word("未设定","未設定")}
    if(key=="language")return if(text=="ja")"日本語"else"中文"
    if(key=="icon")return com.yohaku.expensetracker.domain.category.categoryIconOf(text)?.let{word(it.zh,it.ja)}?:word("自动 / 继承","自動・親に従う")
    if(key=="rateSource")return when(text){"manual"->word("手填","手入力");else->text}
    if(key=="scope"&&text in setOf("TOTAL","INSTALLMENT","REVOLVING"))return when(text){"TOTAL"->word("总额度","総枠");"INSTALLMENT"->word("分期额度","分割枠");else->word("循环额度","リボ枠")}
    if(text.startsWith("source:")||text.startsWith("charge:")||text.startsWith("refund:")||text.startsWith("rule:")||text.startsWith("actual:")||text.startsWith("arrangement:")||text.startsWith("refund-allocation:"))return word("相关本金／额度变动","関連する元金・利用枠の変動")
    if(key=="scope"&&text.isEmpty())return word("月预算","月予算")
    if(key=="position")return when(text){"INCLUDED_FROM"->word("包含在转出金额","出金額に含む");"DEDUCTED_TO"->word("从到账金额扣除","入金額から差引");"SEPARATE"->word("单独付款","別途支払");"WITHHELD"->word("预扣费用","天引手数料");"INCLUDED_PAYMENT"->word("包含于还款的费用","返済に含む手数料");else->text}
    if(text.startsWith("@")||text.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}")))return resolveName(text,s,model)
    if(key.endsWith("Id")||key.endsWith("Ids")||key in setOf("account","category","scope"))return resolveName(text,s,model)
    if(text.startsWith("limit."))return limitLabel(text)
    if(key=="issue")return cashIssue(text)
    if(key=="cardPaymentChoice")return when(text){"BONUS"->word("奖金月付款","ボーナス払い");"DEFERRED"->word("指定月付款","支払月を指定");"ACCOUNT_RULE"->word("按账户规则","口座の条件に従う");else->financeLabel(text)}
    return when(text){"SORTED"->word("已整理","整理済み");"NORMAL"->word("通常","通常");"CARD_ENTRY"->word("信用卡消费预测","カード利用の予測");"RULE_BASED"->word("按规则预测","条件による予測");"POOL"->word("循环还款计划","リボ・循環返済プラン");"POOL_MEMBER"->word("已并入循环还款计划","リボ・循環返済プランへ統合済み");"POOL_RETAINED"->word("计划内保留义务","循環返済プラン内に保持する債務");"CANCELLED_ARRANGEMENT"->word("已撤销安排","取消済みの予定");"MANUAL"->word("手工记录","手入力");"EXPECTED"->word("预计收入草稿","収入予定の下書き");"TRANSACTION"->word("交易日","取引日");"ACCOUNT_POSTING"->word("账户入账日","口座計上日");"RETAIN"->word("保留原费用","元の手数料を保持");"CANCELLED_BY_PLATFORM"->word("平台已取消费用","返済先が手数料を取消済み");"PARTIAL"->word("部分还款","一部返済");"EARLY"->word("提前还款","繰上返済");"SETTLEMENT"->word("全部清偿","完済");"ADDITION"->word("新增本金","元金追加");"REFUND_ALLOCATION"->word("退款本金归属","返金元金の帰属");"CHARGE"->word("额度占用","利用枠の使用");"ACTUAL_RECOVERY"->word("平台实际恢复","返済先の実回復");"RECOVERY_CHANGED"->word("恢复依据已变化","回復の根拠が変更済み");"RULE_RECOVERY"->word("按规则恢复","条件による回復");"ARRANGEMENT"->word("办理变更","手続き変更");"ARRANGEMENT_LIMIT_UNKNOWN"->word("安排变更的额度影响未知","予定変更の枠への影響が不明");"UNSORTED"->word("未整理","未整理");"ARRIVED"->word("已到账","入金済み");"WEEKLY"->word("每周","毎週");"MONTHLY"->word("每月","毎月");"YEARLY"->word("每年","毎年");"PENDING"->word("待处理","未処理");"COMPLETED"->word("已完成","完了");"CANCELLED"->word("已取消","取消済み");"WITHDRAWN"->word("已撤回","取下げ済み");"EQUAL"->word("均分","均等");"AMOUNTS"->word("按金额分摊","金額で分担");"PERCENT"->word("按比例分摊","割合で分担");"ME"->word("本人","自分");"true"->word("是","はい");"false"->word("否","いいえ");"EMPTY"->word("无","なし");"OPENING"->word("期初欠款","期首残高");"EVENT"->word("记录来源","記録が対象");"EXPENSE"->word("支出","支出");"INCOME"->word("收入","収入");"TRANSFER"->word("转账","振替");"REFUND"->word("退款","返金");"REPAYMENT"->word("还款","返済");"BORROWING"->word("借入","借入");"BILL"->word("账单","請求");"POSTING"->word("入账","計上");"CARD_RECURRENCE"->word("信用卡周期预计扣款","カード定期支払の引落見込み");"RECURRENCE"->word("周期计划","定期予定");"RESERVE"->word("预留","確保");"EXPECTED_INCOME"->word("预计收入","入金予定");else->when {
        key in setOf("mode","method","loanMethod","frequency","balanceBasis","rounding","roundingMode","firstCharge","allocation","tierBalanceDate","feeMethod","principalRemainder","feeRemainder","loanPrincipalRemainder","cardPaymentMode") -> financeLabel(text)
        key in setOf("sharing","recovery","usageBasis","usageStart") -> limitLabel(text)
        key=="type"&&text in setOf("CASH","BANK","CREDIT","LOAN","IC") -> com.yohaku.expensetracker.ui.features.accountTypeName(com.yohaku.expensetracker.domain.model.AccountType.valueOf(text))
        else->text
    }}
}
@Composable private fun readableCommandJson(value:JsonElement,key:String,currency:String,s:BookSnapshot,model:ConfirmationModel,zone:java.time.ZoneId?=null):String {
    val parts=mutableListOf<String>()
    when(value) {
        is JsonObject->for((field,v) in value) {
            if(field in setOf("id","bookId","createdAt","deletionGroupId"))continue
            val label=if(field=="ME")word("本人","自分")else if(field.startsWith("@")||field.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}")))resolveName(field,s,model)else fieldName(field)
            parts+=label+"："+readableCommandJson(v,field,currency,s,model,zone)
        }
        is JsonArray->for(v in value)parts+=readableCommandJson(v,key,currency,s,model,zone)
        else->return fieldValue(value to null,key,currency,s,model,zone)
    }
    return parts.joinToString(" · ")
}
@Composable private fun effectTitle(e:ObjectEffect,s:BookSnapshot,model:ConfirmationModel):String {
    val origin=((e.after as? JsonObject)?:e.before as? JsonObject)?.get("origin")?.jsonPrimitive?.contentOrNull
    val kind=financeObjectLabel(e.type)?:if(e.type=="prediction"&&origin=="POOL")word("循环还款计划","リボ・循環返済プラン")else if(e.type=="prediction"&&origin=="MANUAL")word("手工逐期计划","手入力の各回予定")else when(e.type){"monthlyPlan"->word("月度计划","月の計画");"preference"->word("偏好设置","設定");"analysisPreference"->word("分析设置","分析設定");"statementAssociation","statementRow"->word("流水核对","明細の照合");"receiptProducts"->word("商品明细","商品明細");"receipt","receiptSource","receiptImage"->word("小票来源","レシートの出典");"attachment"->word("附件","添付");"entryDraft"->word("待补完草稿","補完待ちの下書き");"followup"->word("后续步骤","後続の処理");"csvBatch","csvEntry"->word("CSV 导入批次","CSV取込バッチ");"attachment_links"->word("记录附件关联","記録の添付関係");"receipts"->word("本地小票","端末のレシート");"receipt_images"->word("小票图片关联","レシート画像の関連");"activity"->word("活动","シーン");"activityBalance"->word("活动余量","シーンの残額");"reserve"->word("活动预留","シーンの確保");"treat"->word("他人请客","友達のごちそう");"recurrence"->word("周期规则","定期ルール");"occurrence"->word("周期实例","定期予定");"expectedIncome"->word("预计收入","収入予定");"activityMode"->word("活动模式","シーンモード");"activityBudget"->word("活动分类预算","シーンのカテゴリ予算");"activityParticipant"->word("活动参与人","シーンの参加者");"treat_allocations"->word("请客份额","ごちそうの取り分");"reserve_uses"->word("预留使用","確保の使用");"drafts"->word("记账草稿","記録の下書き");"draft_media"->word("草稿暂存附件","下書きの添付資料");"book"->word("账本","帳簿");"merchant"->word("商户","お店");"merchantLocation"->word("保存地点","保存した場所");"friend"->word("朋友","友達");"template"->word("记账模板","記録テンプレート");"budgetSetting"->word("预算设置","予算設定");"event_places"->word("记录地点","記録の場所");"settlements"->word("结算","決済");"transfers"->word("转账两端","振替の両側");"expense_components"->word("手续费","手数料");"postings"->word("入账","計上");"account"->word("账户","口座");"balance"->word("余额 / 欠款","残高・残債");"entry"->word("记录","記録");"entryDetails"->word("结算与关联资料","決済・関連情報");"entryCategory","category"->word("分类","カテゴリ");"budget","budgetForecast"->word("预算与预测","予算・予測");"rule"->word("金融规则","金融条件");"prediction"->word("预测计划","予測プラン");"predictionFacts"->word("预测依据","予測の根拠");"bill"->word("逐期付款","各回の支払");"cashForecast"->word("资金预测","資金予測");"creditLimit","creditLimitGroup"->word("信用额度","利用枠");"saveOrder","entryDefaults"->word("保存顺序 / 默认值依据","保存順・初期値の根拠");"unknownRuleFields"->word("规则缺项","条件の不足項目");else->word("连带影响","関連する影響")}
    val name=resolveName(e.key,s,model)
    // Opaque row IDs are available in the protocol, not useful as UI headings.
    return if(name!=e.key)"$kind · $name"else kind
}

@Composable internal fun fieldName(key:String):String {
    if(key.toIntOrNull()!=null)return word("第 $key 项","$key 件目")
    val names=mapOf(
        "endDate" to ("结束日期" to "終了日"),"anchorDate" to ("首次安排日期" to "最初の予定日"),"excludeMonthly" to ("不计入月预算" to "月予算から独立"),"ended" to ("已结束" to "終了済み"),"activityMode" to ("活动模式" to "シーンモード"),"activeActivityId" to ("当前活动" to "現在のシーン"),"recurrenceId" to ("周期规则" to "定期ルール"),"reserveId" to ("预留" to "確保"),"releasedMinor" to ("已释放金额" to "解放済み額"),"payerFriendId" to ("请客的朋友" to "ごちそうした友達"),"accountEstimateJson" to ("预计到账换算依据" to "入金見込の換算根拠"),"categoryBudgets" to ("分类预算" to "カテゴリ予算"),"friendBalances" to ("互请往来" to "ごちそうのやり取り"),"iGiveMinor" to ("我请对方" to "自分からのおごり"),"theyGiveMinor" to ("对方请我" to "相手からのおごり"),"differenceMinor" to ("互请差额" to "ごちそうの差額"),"receiptId" to ("小票" to "レシート"),"sessionId" to ("附件所属草稿" to "添付先の下書き"),"fileId" to ("附件" to "添付資料"),"mediaSession" to ("草稿附件会话" to "下書きの添付先"),
        "original" to ("原对象" to "元の対象"),"settlements" to ("相关结算" to "関連する決済"),"plans" to ("关联计划" to "関連プラン"),"bills" to ("关联账期" to "関連する請求"),"occurrences" to ("关联周期实例" to "関連する定期予定"),"statementDecisions" to ("流水关联" to "明細との関連"),"postingJson" to ("入账核对依据" to "計上照合の根拠"),"status" to ("状态" to "状態"),"recoveryDeadline" to ("恢复期限" to "復元期限"),"accounts" to ("账户数" to "口座数"),"records" to ("记录数" to "記録数"),"files" to ("文件数" to "ファイル数"),"financialPlans" to ("金融计划数" to "金融プラン数"),"statements" to ("流水批次数" to "明細の件数"),"theme" to ("主题" to "テーマ"),"language" to ("语言" to "言語"),"startDate" to ("开始日期" to "開始日"),"budgetMinor" to ("预算金额" to "予算額"),"initialized" to ("已完成设置" to "設定完了"),"setupStep" to ("设置步骤" to "設定ステップ"),"archived" to ("已归档" to "アーカイブ済み"),
        "latitude" to ("纬度" to "緯度"),"longitude" to ("经度" to "経度"),"radiusMeters" to ("匹配半径（米）" to "選択半径（m）"),"accuracyMeters" to ("定位精度（米）" to "測位精度（m）"),"sortOrder" to ("排序位置" to "並び順"),
        "toAccountId" to ("转入账户" to "入金口座"),"toMinor" to ("到账金额" to "入金額"),"fromCurrency" to ("转出币种" to "出金通貨"),"toCurrency" to ("到账币种" to "入金通貨"),"fromDate" to ("转出日期" to "出金日"),"toDate" to ("到账日期" to "入金日"),"fromConfirmed" to ("转出已确认" to "出金確定"),"toConfirmed" to ("到账已确认" to "入金確定"),"expectedToMinor" to ("预计到账金额" to "入金見込額"),"expectedToCurrency" to ("预计到账币种" to "入金見込通貨"),"position" to ("手续费位置" to "手数料の扱い"),"deltaMinor" to ("入账变化" to "計上の変化"),"originalMinor" to ("原币金额" to "原通貨額"),"originalCurrency" to ("原币" to "原通貨"),"invalidatedAt" to ("流水关联失效日" to "明細関連の失効日"),"participant" to ("参与人" to "参加者"),"friendId" to ("朋友" to "友達"),"treatId" to ("互请记录" to "ごちそう記録"),"weightsJson" to ("分摊份额" to "分担割合"),
        "object" to ("对象存在状态" to "対象の存在状態"),"references" to ("关联资料数量" to "関連情報の件数"),"draftKeys" to ("引用此账户的草稿" to "この口座を参照する下書き"),
        "settlement" to ("结算" to "決済"),"transfer" to ("转账" to "振替"),"components" to ("资金组成" to "資金の内訳"),"activityId" to ("活动" to "シーン"),"occurrenceId" to ("周期实例" to "定期予定の回"),"reserveUses" to ("预留使用" to "確保の使用"),"treat" to ("请客" to "おごり"),"allocations" to ("分摊" to "分担"),"merchantId" to ("商户" to "お店"),"attachmentIds" to ("附件" to "添付資料"),"receiptRevision" to ("小票版本" to "レシートの版"),"contract" to ("合同" to "契約"),"repayment" to ("还款" to "返済"),"repaymentAllocations" to ("还款分配" to "返済の充当"),"postings" to ("入账" to "計上"),"createdAt" to ("创建时间" to "作成日時"),"events" to ("记录" to "記録"),
        "text" to ("月计划原文" to "月の計画の原文"),"value" to ("内容" to "内容"),"name" to ("名称" to "名前"),"type" to ("类型" to "種類"),"kind" to ("种类" to "種類"),"currency" to ("币种" to "通貨"),"openingMinor" to ("期初余额 / 欠款" to "期首残高・残債"),"openingDate" to ("期初日期" to "期首日"),"active" to ("启用" to "有効"),
        "date" to ("日期" to "日付"),"amountMinor" to ("金额" to "金額"),"baseMinor" to ("账本币金额" to "基準通貨額"),"accountId" to ("账户" to "口座"),"categoryId" to ("分类" to "カテゴリ"),"merchant" to ("商户" to "お店"),"note" to ("备注" to "メモ"),"historicalIncluded" to ("包含于期初" to "期首に含む"),"budgetExcluded" to ("不计入预算" to "予算に含めない"),"deletedAt" to ("回收站时间" to "削除日時"),"purgedAt" to ("清理时间" to "消去日時"),"originalEventId" to ("原记录" to "元の記録"),"eventId" to ("记录" to "記録"),
        "accountMinor" to ("入账金额" to "計上額"),"accountCurrency" to ("账户币种" to "口座通貨"),"confirmed" to ("已确认" to "確定済み"),"settlementDate" to ("入账日" to "計上日"),"rateSource" to ("汇率来源" to "レートの出典"),"rateDate" to ("汇率日期" to "レートの日付"),"expectedAccountMinor" to ("预计入账" to "計上見込"),"expectedBaseMinor" to ("预计账本币金额" to "基準通貨の見込額"),"manualRate" to ("手填汇率" to "手入力レート"),"expectedAccountCurrency" to ("预计账户币种" to "見込口座通貨"),"referenceRatesJson" to ("参考汇率" to "参考レート"),
        "cardPaymentMode" to ("付款模式" to "支払方式"),"cardPaymentChoice" to ("付款选择" to "支払の選択"),"cardPaymentPeriods" to ("分期期数" to "分割回数"),"cardPaymentMonth" to ("指定付款月" to "指定支払月"),"cardPaymentFeeMinor" to ("付款费用" to "支払手数料"),"cardPaymentRuleId" to ("付款规则" to "支払条件"),
        "ledgerMinor" to ("已确认余额" to "確定残高"),"asOfMinor" to ("基准日余额" to "基準日残高"),"debt" to ("债务账户" to "借入口座"),"negative" to ("负余额" to "残高不足"),"parentId" to ("一级分类" to "親カテゴリ"),"favorite" to ("收藏" to "お気に入り"),"icon" to ("图标" to "アイコン"),
        "scope" to ("范围" to "範囲"),"projection" to ("预算计算" to "予算計算"),"month" to ("月份" to "月"),"limitMinor" to ("预算上限" to "予算上限"),"spentMinor" to ("已用" to "使用済み"),"allExpenseMinor" to ("全部支出" to "全支出"),"independentMinor" to ("不计入预算的支出" to "予算に含めない支出"),"reserveMinor" to ("预留" to "確保額"),"pendingCount" to ("待处理数量" to "未処理件数"),"fixedReserveMinor" to ("周期预留" to "定期確保額"),"activityReserveMinor" to ("活动预留" to "シーン確保額"),"fundReserveMinor" to ("资金预留" to "資金確保額"),"remainingMinor" to ("剩余" to "残り"),"availableMinor" to ("可用" to "利用可能"),"overBudget" to ("超预算" to "予算超過"),"overAvailable" to ("可用不足" to "利用可能額不足"),
        "config" to ("额度配置" to "利用枠設定"),"total" to ("总额度" to "総枠"),"installment" to ("分期" to "分割"),"revolving" to ("循环还款" to "リボ"),"capacityMinor" to ("上限" to "上限"),"usedMinor" to ("占用" to "使用額"),"excessMinor" to ("超额" to "超過額"),"issues" to ("待核对" to "要確認"),"effectiveTotalMinor" to ("限制后本账户预计可用" to "制限後の当口座の予測利用可能額"),"groupId" to ("共享额度组" to "共通枠"),"version" to ("版本" to "バージョン"),"effectiveFrom" to ("生效日期" to "適用日"),"totalMinor" to ("合计" to "合計"),"installmentMinor" to ("分期子额度上限" to "分割個別枠上限"),"revolvingMinor" to ("循环还款子额度上限" to "リボ個別枠上限"),"sharing" to ("额度共用方式" to "枠の共有方式"),"combinedMinor" to ("共用子额度上限" to "共通の内枠上限"),"basisDate" to ("占用基准日" to "使用額基準日"),"usedTotalMinor" to ("总占用" to "総使用額"),"usedInstallmentMinor" to ("分期占用" to "分割使用額"),"usedRevolvingMinor" to ("循环占用" to "リボ使用額"),"recovery" to ("额度恢复方式" to "枠の回復方式"),"recoveryDelayDays" to ("恢复延迟天数" to "回復までの日数"),"usageBasis" to ("占用口径" to "使用額の基準"),"usageStart" to ("占用起点" to "使用開始"),"zoneId" to ("时区" to "タイムゾーン"),
        "mode" to ("模式" to "方式"),"json" to ("规则内容" to "条件内容"),"billing" to ("账期" to "請求期間"),"closingDay" to ("结账日" to "締日"),"closingDayInCurrentBill" to ("结账日计入本期" to "締日を当期に含む"),"paymentMonthOffset" to ("付款月份偏移" to "支払月の繰越"),"paymentDay" to ("付款日" to "支払日"),"paymentAccountId" to ("付款账户" to "引落口座"),"bonusPayments" to ("奖金月付款" to "ボーナス払い"),"startMonth" to ("开始月份" to "開始月"),"startDay" to ("开始日" to "開始日"),"endMonth" to ("结束月份" to "終了月"),"endDay" to ("结束日" to "終了日"),"paymentMonth" to ("付款月份" to "支払月"),
        "interest" to ("计息" to "利息計算"),"annualRate" to ("年率（小数）" to "年利率（小数）"),"frequency" to ("频率" to "頻度"),"balanceBasis" to ("余额依据" to "残高の基準"),"includeStart" to ("包含开始日" to "開始日を含む"),"includeEnd" to ("包含结束日" to "終了日を含む"),"yearDays" to ("年度天数" to "年間日数"),"rounding" to ("取整时点" to "端数処理の時点"),"roundingMode" to ("取整方向" to "端数処理の方向"),"firstCharge" to ("首次计费" to "初回手数料"),"method" to ("还款方式" to "返済方式"),"paymentMinor" to ("月设定金额" to "月設定額"),"tiers" to ("阶梯" to "残高別コース"),"upToMinor" to ("余额上限" to "残高上限"),"tierPaymentIncludesFees" to ("阶梯金额含费用" to "コース額に手数料を含む"),"allocation" to ("分配顺序" to "充当順序"),"automatic" to ("自动循环" to "自動リボ"),"tierBalanceDate" to ("阶梯余额判定日" to "残高コース判定日"),
        "supportedPeriods" to ("支持期数" to "対応回数"),"feeMethod" to ("费用算法" to "手数料の計算方式"),"totalFeeMinor" to ("总费用" to "総手数料"),"totalRate" to ("总费率（小数）" to "総料率（小数）"),"feeTable" to ("费用表" to "手数料表"),"periods" to ("期数" to "回数"),"feePerHundred" to ("每100单位费用" to "100通貨単位当たり手数料"),"principalRemainder" to ("本金尾差" to "元金端数"),"feeRemainder" to ("费用尾差" to "手数料端数"),"loanMethod" to ("贷款算法" to "ローン計算方式"),"loanInterest" to ("贷款计息" to "ローン利息"),"loanPrincipalRemainder" to ("贷款本金尾差" to "ローン元金端数"),"limitRecovery" to ("历史恢复规则" to "旧回復条件"),"unsupportedReason" to ("不适用原因" to "未対応の理由"),"deadlines" to ("办理期限" to "手続き期限"),
        "planId" to ("计划" to "プラン"),"sourceEventId" to ("来源记录" to "対象記録"),"origin" to ("来源" to "対象"),"principalMinor" to ("本金" to "元金"),"feeMinor" to ("费用" to "手数料"),"complete" to ("完整预测" to "全期間の予測"),"termsJson" to ("预测条件" to "予測条件"),"reconciliationJson" to ("核对依据" to "照合の根拠"),"terms" to ("日期与金额条件" to "日付・金額の条件"),"agreementDate" to ("合同 / 期初基准日" to "契約・期首基準日"),"paymentDates" to ("付款日期" to "支払日"),"accrualWindows" to ("计息区间" to "計算期間"),"start" to ("开始" to "開始"),"end" to ("截止" to "終了"),"overrides" to ("平台逐期金额" to "返済先の各回金額"),"firstPeriodNumber" to ("合同起始期数" to "契約の開始回数"),"remainingFeeMinor" to ("剩余总费用" to "残手数料"),"currentArrangementConfirmed" to ("本机事实确认要求" to "端末での事実確認要件"),
        "number" to ("期数" to "回数"),"ruleVersionId" to ("规则版本" to "条件バージョン"),"predictedDate" to ("预测日期" to "予測日"),"predictedPrincipalMinor" to ("预测本金" to "予測元金"),"predictedFeeMinor" to ("预测费用" to "予測手数料"),"predictedTotalMinor" to ("预测合计" to "予測合計"),"confirmedDate" to ("确认日期" to "確定日"),"confirmedPrincipalMinor" to ("确认本金" to "確定元金"),"confirmedFeeMinor" to ("确认费用" to "確定手数料"),"confirmedTotalMinor" to ("确认合计" to "確定合計"),"basisJson" to ("计算依据" to "計算根拠"),"basis" to ("依据" to "根拠"),"facts" to ("实际事实" to "実際の事実"),"projections" to ("逐期预测" to "各回の予測"),"asOf" to ("基准日期" to "基準日"),"retainedBills" to ("保留账期" to "保持する請求"),"repayments" to ("实际还款" to "実返済"),"refunds" to ("退款" to "返金"),"revision" to ("事实修订" to "事実の改訂"),
        "item" to ("资金事项" to "資金項目"),"sourceId" to ("来源" to "対象"),"incoming" to ("流入" to "入金"),"accountDeltaMinor" to ("账户变动" to "口座の変動"),"estimated" to ("估算" to "見込"),"issue" to ("待核对" to "要確認"),"scheduledDate" to ("计划日期" to "予定日"),"referenceRate" to ("参考汇率" to "参考レート"),"baselineAfterMinor" to ("付款后基准余额" to "支払後の基準残高"),"includingIncomeAfterMinor" to ("包含预计收入后余额" to "入金見込を含む残高"),"baselineEstimated" to ("基准余额含估算" to "基準残高に見込を含む"),"includingEstimated" to ("合计余额含估算" to "合計残高に見込を含む")
    )
    return (workflowFieldLabels[key]?:financeFieldLabels[key]?:names[key])?.let{word(it.first,it.second)}?:word("补充依据","補足の根拠")
}

@Composable internal fun CommandRiskNotices(risks:List<CommandRisk>,s:BookSnapshot,model:ConfirmationModel) {
    for(risk in risks) {
        val subject=when {
            risk.code.startsWith("BUDGET")->risk.subject.substringBefore(':')+risk.subject.substringAfter(':',"").takeIf{it.isNotEmpty()}?.let{" · "+resolveName(it,s,model)}.orEmpty()
            risk.code=="RULE_INCOMPLETE"->model.steps.firstOrNull{it.stepId==risk.subject}?.targets?.joinToString{resolveName(it,s,model)}.orEmpty()
            else->resolveName(risk.subject,s,model)
        }
        val amount=if(risk.currency!=null&&risk.minor!=null)Money(Currency.valueOf(risk.currency),risk.minor).display()else ""
        val text=when(risk.code) {
            "NEGATIVE_BALANCE"->word("账户「$subject」余额为 $amount。","口座「$subject」の残高は $amount です。")
            "LIMIT_EXCESS"->{
                val scope=when(risk.detail){"installment"->word("分期额度","分割枠");"revolving"->word("循环额度","リボ枠");else->word("总额度","総枠")}
                word("「$subject」的${scope}预计超出 $amount。","「$subject」の${scope}は $amount の超過見込みです。")
            }
            "LIMIT_ISSUE"->"$subject · "+limitLabel(risk.detail)
            "BUDGET_EXCESS"->word("$subject 预算超出 $amount。","$subject の予算を $amount 超過します。")
            "BUDGET_FORECAST_EXCESS"->word("$subject 预算预计超出 $amount。","$subject の予算は $amount の超過見込みです。")
            "CASH_SHORTAGE"->word("账户「$subject」在 ${risk.date} 的预测余额为 $amount。","口座「$subject」の ${risk.date} の予測残高は $amount です。")
            "CASH_ISSUE"->"$subject · ${risk.date.orEmpty()} · "+cashIssue(risk.detail.orEmpty())
            else->"$subject · "+commandIssue(risk.detail.orEmpty())
        }
        YText(text,12,color=LocalYohaku.current.warm)
    }
}
