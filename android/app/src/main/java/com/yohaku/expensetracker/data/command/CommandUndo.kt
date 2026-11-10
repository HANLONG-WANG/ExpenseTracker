package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import kotlinx.serialization.json.*

internal val localUndoUnavailable=UndoAvailability(false,"撤销操作不能再次撤销。","取消操作を再び取り消すことはできません。")
internal val accountUndo=UndoAvailability(true,"恢复原账户信息；本方案新建的账户和额度版本在无修改、无方案外引用时整体移除。","元の口座情報に戻します。このプランで作成した口座・利用枠の版は、変更やプラン外の参照がなければ削除します。")
internal val ruleUndo=UndoAvailability(true,"移除本方案新增且未被修改、无方案外引用的规则版本。","このプランで追加し、変更やプラン外の参照がない条件バージョンを削除します。")
internal val predictionUndo=UndoAvailability(true,"移除本方案新建的预测、账期和修订；重算已有预测时恢复执行前快照。","このプランで作成した予測・請求期間・履歴を削除します。既存の予測の再計算は、実行前の状態に戻します。")

internal suspend fun CommandRun.undoValue(type:String,key:String):JsonElement {
    val s=state().ledger
    return when(type) {
        "preference"->preferenceValues.getValue(key)
        "account"->s.accounts.firstOrNull{it.id==key}?.let{encoded(it)}?:JsonNull
        "entry"->s.events.firstOrNull{it.id==key}?.let{encoded(it)}?:JsonNull
        "entryAssociations"->{
            val original=s.settlements.firstOrNull{it.eventId==key}?.originalEventId
            val source=original?:key
            val refunds=s.settlements.filter{it.originalEventId==source}.map{it.eventId}.toSet()
            val postings=s.storedPostings.filter{it.eventId==key}.map{it.id}.toSet()
            val finance=state().finance
            val plans=finance.plans.filter{it.sourceEventId in setOf(key,source)}
            buildJsonObject {
                put("original",s.events.firstOrNull{it.id==source}?.let{encoded(it)}?:JsonNull)
                put("refunds",encoded(s.events.filter{it.id in refunds}.sortedBy{it.id}))
                put("settlements",encoded(s.settlements.filter{it.eventId in refunds}.sortedBy{it.eventId}))
                put("plans",encoded(plans.sortedBy{it.id}))
                put("bills",encoded(finance.bills.filter{bill->plans.any{it.id==bill.planId}}.sortedBy{it.id}))
                put("occurrences",encoded(s.occurrences.filter{it.eventId==key}.map{it.id to it.status}.sortedBy{it.first}))
                put("statementDecisions",JsonArray(statementDecisions().filter{it.postingId in postings}.sortedWith(compareBy<com.yohaku.expensetracker.data.db.StatementDecisionEntity>{it.batchId}.thenBy{it.rowId}).map{row->buildJsonObject {
                    put("bookId",row.bookId);put("batchId",row.batchId);put("rowId",row.rowId);put("postingId",row.postingId);put("postingJson",row.postingJson);put("note",row.note);put("updatedAt",row.updatedAt);put("invalidatedAt",row.invalidatedAt)
                }}))
            }
        }
        "entryPlace"->s.eventPlaces.firstOrNull{it.eventId==key}?.let{place->buildJsonObject{put("latitude",place.latitude);put("longitude",place.longitude);put("accuracyMeters",place.accuracyMeters)}}?:JsonNull
        "entryDetails"->if(s.events.any{it.id==key})encoded(s.entryDetails(key))else JsonNull
        "categoryChildren"->encoded(s.categories.filter{it.parentId==key}.map{it.id}.sorted())
        "bookUsage"->encoded(ledger.scopedReferenceCounts(key,"books",key))
        "accountUsage"->if(s.accounts.none{it.id==key})JsonNull else ledger.accountUsage(bookId,key).let{usage->
            buildJsonObject{put("references",encoded(usage.references));put("draftKeys",encoded(usage.draftKeys))}
        }
        "planningFile"->guardJournal().fileSnapshot(key)
        else->if(type.startsWith("finance:"))guardJournal().financeRelations(type.substringAfter(':'),key)else if(type.startsWith("planning:"))guardJournal().planningRelations(type.substringAfter(':'),key)else error("Unknown undo guard: $type")
    }
}
internal fun undoDifferences(type:String,key:String,expected:JsonElement,current:JsonElement):List<UndoConflict> = buildList {
    fun compare(expected:JsonElement,current:JsonElement,path:String) {
        if(expected==current)return
        if(expected is JsonObject&&current is JsonObject)for(field in expected.keys+current.keys)
            compare(expected[field]?:JsonNull,current[field]?:JsonNull,if(path.isEmpty())field else "$path.$field")
        else if(expected is JsonArray&&current is JsonArray)for(index in 0 until maxOf(expected.size,current.size))
            compare(expected.getOrNull(index)?:JsonNull,current.getOrNull(index)?:JsonNull,"$path.$index")
        else add(UndoConflict(type,key,path.ifEmpty{"object"},expected,current))
    }
    compare(expected,current,"")
}
internal suspend fun CommandRun.undoConflicts(saved:UndoRecord):List<UndoConflict> = buildList {
    val journal=CommandUndoJournal(ledger,bookId)
    val upgraded=journal.upgradeConflicts(saved)
    if(upgraded.isNotEmpty()){addAll(upgraded);return@buildList}
    val record=journal.migratedRecord(saved)
    for(guard in record.guards)addAll(undoDifferences(guard.type,guard.key,if(guard.type=="preference")completeCommandPreference(guard.key,guard.after)else journal.restorableGuard(guard),undoValue(guard.type,guard.key)))
    if(record.version in 2..3) {
        addAll(journal.conflicts(record))
        if(isEmpty())addAll(journal.merchantConflicts(record))
    }
}

internal class RestoreEntryCategoryAdapter:CommandAdapter {
    override val capability=capability("local.entry.restoreCategory",buildJsonObject{},emptyMap(),"","Restore recorded category, including null.","{}")
    override fun validate(input:JsonObject) {val v=commandJson.decodeFromJsonElement<RestoreCategoryInput>(input);require(v.ids.size in 1..5000&&v.ids.distinct().size==v.ids.size)}
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState)=UndoPreparation(localUndoUnavailable)
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        check(step in requireNotNull(run.undoRecord).steps)
        val input=commandJson.decodeFromJsonElement<RestoreCategoryInput>(step.input)
        val before=run.state();val snapshot=before.ledger
        val changes=input.ids.map{id->
            val old=snapshot.visibleById[id]?:error("目标记录已变化 / 対象の記録が変わりました")
            require(old.kind in listOf("EXPENSE","INCOME"))
            input.category?.let{category->require(snapshot.categoryById[category]?.kind==old.kind){"原分类不存在或用途已变化 / 元のカテゴリがないか用途が変わりました"}}
            // null was a valid persisted pre-batch state. The cloud/ordinary editor still requires a
            // category; only a recorded local inverse can recover null or an archived old category.
            PreparedCategoryChange(old,old.copy(categoryId=input.category,revision=Math.addExact(old.revision,1)),snapshot.entryDetails(id))
        }
        run.ledger.writeEntryCategoriesInTransaction(changes)
        return run.effect(step,before,run.state(refresh=true),changes.map{it.before.accountId}.toSet(),input.ids.toSet(),
            (changes.mapNotNull{it.before.categoryId}+listOfNotNull(input.category)).toSet(),changes.map{it.before.date.take(7)}.toSet())
    }
}

/** Persisted v2 category inverses use restoreCategory; v2/v3 row journals use restoreExecution.
 * Stage 1 has no undo snapshot. Incomplete early v37 account-deletion records are rejected by
 * undoAvailability before dispatch; no persisted inverse producer uses forward command aliases. */
internal fun CommandRegistry.undoAdapter(name:String):CommandAdapter=when(name) {
    "local.restorePreference"->RestorePreferenceAdapter()
    "local.restoreExecution"->RestoreExecutionAdapter()
    "local.entry.restoreCategory"->RestoreEntryCategoryAdapter()
    else->error("未知本机撤销操作 / 未知の端末取消操作: $name")
}

/** Resolve only exact ID values and keys; never substitute strings inside user text. */
internal fun actualCommandValues(value:JsonElement,ids:Map<String,String>):JsonElement=when(value) {
    is JsonObject->JsonObject(value.mapKeys{ids[it.key]?:it.key}.mapValues{actualCommandValues(it.value,ids)})
    is JsonArray->JsonArray(value.map{actualCommandValues(it,ids)})
    is JsonPrimitive->if(value.isString)ids[value.content]?.let(::JsonPrimitive)?:value else value
    else->value
}
