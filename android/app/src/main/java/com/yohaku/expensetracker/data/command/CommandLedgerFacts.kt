package com.yohaku.expensetracker.data.command

import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.first
import com.yohaku.expensetracker.data.files.FileProtocols
import java.time.Instant
import java.time.ZoneOffset

/** Audit instants are not user facts. Trash dates retain their recovery-day meaning; the domain
 * rechecks the exact expiry timestamp on every execution. Deletion groups remain mapped identities. */
internal fun commandEventFacts(value:JsonElement):JsonObject=commandLedgerRow("events",value.jsonObject)
internal fun commandLedgerRow(table:String,value:JsonObject):JsonObject {
    val audit=setOf("createdAt","timestamp","updatedAt")+if(table in setOf("events","financial_plans","financial_bills","treats","expected_incomes","receipt_item_documents"))setOf("revision")else emptySet()
    val booleans=setOf("platformConfirmed","exceptionAccepted","dateChanged","active","archived","initialized","favorite","historicalIncluded","confirmed","fromConfirmed","toConfirmed","complete","estimated","activityMode","ended","excludeMonthly")
    val projected=JsonObject(value.filterKeys{it !in audit}.mapValues{(key,v)->
        when {
            key in setOf("deletedAt","invalidatedAt")&&v!=JsonNull->JsonPrimitive(Instant.ofEpochMilli(v.jsonPrimitive.long).atZone(ZoneOffset.UTC).toLocalDate().toString())
            key in booleans&&v is JsonPrimitive&&!v.isString&&v.longOrNull!=null->JsonPrimitive(v.long!=0L)
            (key.endsWith("Json")||key=="json")&&v is JsonPrimitive&&v.isString->commandJson.parseToJsonElement(v.content)
            else->v
        }
    })
    return if(table.startsWith("financial_")||table.startsWith("credit_limit_")||table=="debt_contracts")commandFinanceFacts(projected).jsonObject else projected
}
internal fun commandTableType(table:String)=when(table) {
    // Persisted draft rows retain the stage-4 effect identity; entryDraft is a deferred creation.
    "drafts"->"drafts";"receipt_item_documents"->"receiptProducts";"receipts"->"receipt";"receipt_images"->"receiptImage";"attachment_links"->"attachment";"statement_decisions"->"statementAssociation";"csv_batches"->"csvBatch";"csv_batch_events"->"csvEntry";"analysis_preferences"->"analysisPreference";
    "monthly_plans"->"monthlyPlan";"books"->"book";"accounts"->"account";"categories"->"category";"events"->"entry";"merchants"->"merchant";"merchant_locations"->"merchantLocation";"friends"->"friend";"entry_templates"->"template"
    "activities"->"activity";"activity_reserves"->"reserve";"activity_budgets"->"activityBudget";"activity_participants"->"activityParticipant";"book_features"->"activityMode";"recurrences"->"recurrence";"occurrences"->"occurrence";"treats"->"treat";"expected_incomes"->"expectedIncome"
    "financial_rule_validations"->"ruleValidation";"financial_arrangements"->"arrangement";"financial_bill_adjustments"->"billAdjustment";"debt_contracts"->"contract";"financial_repayments"->"repayment";"financial_repayment_allocations"->"repaymentAllocation";"financial_contract_plans"->"contractPlan";"financial_drafts"->"financialDraft";"credit_limit_configs"->"creditLimitConfig";"credit_limit_groups"->"creditLimitGroupSetting";"credit_limit_group_members"->"creditLimitMember";"credit_limit_recoveries"->"creditLimitRecovery";"credit_limit_observations"->"creditLimitObservation";
    "financial_rules"->"rule";"financial_plans"->"prediction";"financial_bills"->"bill";"budgets"->"budgetSetting"
    else->table
}
internal fun CommandRun.registerLedgerRows(step:String,rows:List<UndoRowChange>) {
    val order=listOf("books","accounts","categories","merchants","merchant_locations","friends","activities","recurrences","occurrences","activity_reserves","treats","expected_incomes","events","receipts","attachment_links","entry_templates","financial_rules","financial_plans","financial_bills")
    rows.filter{it.before==null&&!it.removed&&it.table!="revisions"}.sortedBy{order.indexOf(it.table).let{i->if(i<0)100 else i}}.forEach{row->
        row.after["id"]?.jsonPrimitive?.contentOrNull?.let{id->
            val facts=commandLedgerRow(row.table,row.after).filterKeys{it!="id"}
            generated(id,step,commandTableType(row.table),digest(semantic(JsonObject(facts))))
        }
    }
    rows.filter{it.table=="events"}.mapNotNull{it.after["deletionGroupId"]?.jsonPrimitive?.contentOrNull}.distinct().forEachIndexed{i,id->if(rows.none{it.before?.get("deletionGroupId")==JsonPrimitive(id)})generated(id,step,"trashGroup",i.toString())}
}
internal suspend fun CommandRun.ledgerRowEffects(rows:List<UndoRowChange>):List<ObjectEffect> {
    val snapshot=state();val s=snapshot.ledger
    val statements=if(rows.any{it.table=="statement_decisions"})ledger.db.fileWorkflowDao().statements(bookId).first().associate{it.id to FileProtocols.statement(it.originalJson)}else emptyMap()
    fun facts(table:String,row:JsonObject):JsonElement {
        val value=commandLedgerRow(table,row).toMutableMap()
        if(table=="statement_decisions")statements[row["batchId"]?.jsonPrimitive?.contentOrNull]?.rows?.firstOrNull{it.row_id==row["rowId"]?.jsonPrimitive?.contentOrNull}?.let{source->value["sourceDate"]=JsonPrimitive(source.date);value["sourceSummary"]=JsonPrimitive(source.summary)}
        if(table in setOf("budgets","activities","activity_budgets","activity_reserves","reserve_uses"))value["currency"]=JsonPrimitive(s.book.currency)
        if(table=="expected_incomes")row["accountId"]?.jsonPrimitive?.contentOrNull?.let{id->s.accounts.firstOrNull{it.id==id}?.let{value["accountCurrency"]=JsonPrimitive(it.currency)}}
        if(table.startsWith("financial_")||table.startsWith("credit_limit_")) {
            val account=(row["accountId"]?:row["debtAccountId"])?.jsonPrimitive?.contentOrNull
            val plan=row["planId"]?.jsonPrimitive?.contentOrNull
            val bill=row["billId"]?.jsonPrimitive?.contentOrNull
            val f=snapshot.finance
            val accountId=account?:f.plans.firstOrNull{it.id==plan||f.bills.any{b->b.id==bill&&b.planId==it.id}}?.accountId
                ?:row["eventId"]?.jsonPrimitive?.contentOrNull?.let{id->f.repayments.firstOrNull{it.eventId==id}?.debtAccountId}
            s.accounts.firstOrNull{it.id==accountId}?.let{value["currency"]=JsonPrimitive(it.currency)}
        }
        return semantic(JsonObject(value))
    }
    // Compare/display effective settings even when their optional storage row does not exist.
    // Ownership and undo still use the untouched nullable raw row in CommandUndoJournal.
    fun settingDefault(table:String):JsonElement=when(table) {
        "book_features"->buildJsonObject{put("bookId",bookId);put("activityMode",false);put("activeActivityId",JsonNull)}
        else->JsonNull
    }
    return rows.filter{it.table !in setOf("revisions","financial_plan_revisions","credit_limit_group_revisions")}.map { row->
    val key=row.after["id"]?.jsonPrimitive?.contentOrNull?:row.after["eventId"]?.jsonPrimitive?.contentOrNull
        ?:canonical(semantic(row.key)).toString()
    ObjectEffect(commandTableType(row.table),semantic(JsonPrimitive(key)).jsonPrimitive.content,
        row.before?.let{facts(row.table,it)}?:settingDefault(row.table),
        if(row.removed)settingDefault(row.table)else facts(row.table,row.after))
}.sortedWith(compareBy<ObjectEffect>{it.type}.thenBy{it.key})
}
internal suspend fun CommandRun.finishLedgerEffect(step:CommandStep,effect:StepEffect,rows:List<UndoRowChange>,basis:List<UndoRowChange> = emptyList()):StepEffect {
    registerLedgerRows(step.id,rows)
    val extra=ledgerRowEffects(rows+basis).distinctBy{it.type to it.key}
    val normalized=commandJson.decodeFromJsonElement<StepEffect>(semantic(encoded(effect)))
    val keys=normalized.objects.map{it.type to it.key}.toSet()
    val objects=(normalized.objects+extra.filter{it.type to it.key !in keys}).sortedWith(compareBy<ObjectEffect>{it.type}.thenBy{it.key})
    val risks=commandRisks(objects)
    val information=requirement(step.id,ConfirmationKind.INFORMATION,encoded(objects),if(effect.mode=="transactional")"此步骤为方案中的中间状态；数据库步骤整批一起提交。"else"此步骤在数据库提交后继续执行，状态保存在本机历史。",if(effect.mode=="transactional")"このステップは途中の状態です。データベースの処理は全体をまとめて保存します。"else"データベースの保存後に続けて実行し、端末の履歴に状態を保持します。")
    val primary=ledgerRowEffects(rows).filter{it.type in setOf("book","category","merchant","merchantLocation","friend","template","budgetSetting","activity","activityMode","activityBudget","reserve","activityParticipant","treat","recurrence","occurrence","expectedIncome")}
    return normalized.copy(targets=normalized.targets.ifEmpty{primary.ifEmpty{extra}.map{it.key}.distinct()},objects=objects,requirements=listOf(information)+normalized.requirements.filter{it.kind==ConfirmationKind.USER_FACT}+commandRiskRequirements(step.id,objects,risks),risks=risks)
}
