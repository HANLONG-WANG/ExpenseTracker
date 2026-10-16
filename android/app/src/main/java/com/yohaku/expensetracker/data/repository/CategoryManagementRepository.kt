package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.YearMonth

fun BookSnapshot.categoryEnabled(id:String):Boolean=categories.firstOrNull{it.id==id}?.let{it.active&&(it.parentId==null||categories.any{p->p.id==it.parentId&&p.active})}==true
internal suspend fun LedgerRepository.categoryEnabled(bookId:String,id:String):Boolean=dao.category(bookId,id)?.let{it.active&&(it.parentId==null||dao.category(bookId,it.parentId)?.active==true)}==true

enum class CategoryOperation { DEACTIVATE,ACTIVATE,REPARENT,MERGE_DELETE,DELETE_EMPTY }
enum class CategoryBudgetPolicy { KEEP_TARGET,COMBINE }
data class CategoryProjectionChange(val categoryId:String,val month:String,val before:BudgetProjection,val after:BudgetProjection)
class CategoryOperationReview internal constructor(internal val before:BookSnapshot,internal val after:BookSnapshot,
    internal val draftRows:List<FinancialDraftEntity>,internal val nextDraftRows:List<FinancialDraftEntity>,
    val category:CategoryEntity,val operation:CategoryOperation,val targetId:String?,val budgetPolicy:CategoryBudgetPolicy,
    val sourceIds:Set<String>,val recordCount:Int,val recurrenceCount:Int,val occurrenceCount:Int,val draftCount:Int,
    val projections:List<CategoryProjectionChange>,val deletionBlocked:Boolean) {
    val budgetChanges get()=before.budgets.filter{it!=after.budgets.find{n->n.month==it.month&&n.scope==it.scope}} to after.budgets.filter{it!=before.budgets.find{n->n.month==it.month&&n.scope==it.scope}}
    val activityBudgetChanges get()=before.activityBudgets.filter{it !in after.activityBudgets} to after.activityBudgets.filter{it !in before.activityBudgets}
}
private fun sumCaps(values:List<Long?>):Long?=values.filterNotNull().takeIf{it.isNotEmpty()}?.fold(0L,Math::addExact)

suspend fun LedgerRepository.previewCategoryOperation(bookId:String,categoryId:String,operation:CategoryOperation,targetId:String?=null,
    budgetPolicy:CategoryBudgetPolicy=CategoryBudgetPolicy.KEEP_TARGET):CategoryOperationReview=db.withTransaction {
    val s=snapshot(requireNotNull(dao.book(bookId))).first()
    val c=requireNotNull(s.categories.find{it.id==categoryId})
    val children=s.categories.filter{it.parentId==categoryId}
    val ids=setOf(categoryId)+children.map{it.id}
    val target=targetId?.let{id->requireNotNull(s.categories.find{it.id==id})}
    val drafts=db.financialDao().bookDrafts(bookId)
    val draftReferences=drafts.count{json.parseToJsonElement(it.valuesJson).jsonObject["category"]?.jsonPrimitive?.contentOrNull in ids}
    val deletionBlocked=ids.any{scopedReferenceCounts(bookId,"categories",it).any{(table,_)->table!="categories"}}||s.draft?.categoryId in ids||draftReferences>0
    var nextDrafts=drafts
    var next=s
    when(operation) {
        CategoryOperation.DEACTIVATE->{require(c.active);next=s.copy(categories=s.categories.map{if(it.id==c.id)it.copy(active=false)else it})}
        CategoryOperation.ACTIVATE->{require(!c.active);next=s.copy(categories=s.categories.map{if(it.id==c.id)it.copy(active=true)else it})}
        CategoryOperation.REPARENT->{
            require(target==null||target.id!=c.id&&target.parentId==null&&target.kind==c.kind&&s.categoryEnabled(target.id)){"请选择启用的同用途一级分类 / 同じ用途の有効な親カテゴリを選択してください"}
            require(target==null||children.isEmpty()){"已有子分类，请先调整子分类或合并整组 / 子カテゴリを先に移動するか、グループ全体を統合してください"}
            require(c.parentId!=targetId)
            next=s.copy(categories=s.categories.map{if(it.id==c.id)it.copy(parentId=targetId)else it})
        }
        CategoryOperation.DELETE_EMPTY->{if(!deletionBlocked)next=s.copy(categories=s.categories.filter{it.id !in ids})}
        CategoryOperation.MERGE_DELETE->{
            require(target!=null&&target.id !in ids&&target.kind==c.kind&&s.categoryEnabled(target.id)){"请选择本账本同用途的启用分类，不能迁入待删除组 / 削除対象外の同じ用途の有効なカテゴリを選択してください"}
            fun cap(month:String):Long? {
                val source=s.budgetLimit(month,c.id)?:sumCaps(children.map{s.budgetLimit(month,it.id)})
                val destination=s.budgetLimit(month,target.id)
                return if(budgetPolicy==CategoryBudgetPolicy.KEEP_TARGET||c.parentId==target.id&&destination!=null)destination else sumCaps(listOf(destination,source))
            }
            val months=s.budgets.filter{it.categoryId in ids||it.categoryId==target.id}.map{it.month}.distinct().sorted()
            val budgets=s.budgets.filter{it.categoryId !in ids&&it.categoryId!=target.id}+months.map{BudgetEntity(bookId,it,target.id,target.id,cap(it))}
            val activityBudgets=s.activityBudgets.filter{it.categoryId !in ids&&it.categoryId!=target.id}+s.activityBudgets.filter{it.categoryId in ids||it.categoryId==target.id}.groupBy{it.activityId}.mapNotNull{(activity,rows)->
                val source=rows.find{it.categoryId==c.id}?.amountMinor?:sumCaps(rows.filter{it.categoryId in ids}.map{it.amountMinor})
                val destination=rows.find{it.categoryId==target.id}?.amountMinor
                val merged=if(budgetPolicy==CategoryBudgetPolicy.KEEP_TARGET||c.parentId==target.id&&destination!=null)destination else sumCaps(listOf(destination,source))
                merged?.let{ActivityBudgetEntity(bookId,activity,target.id,it)}
            }
            nextDrafts=drafts.map{row->
                val fields=json.parseToJsonElement(row.valuesJson).jsonObject
                if(fields["category"]?.jsonPrimitive?.contentOrNull in ids)row.copy(valuesJson=JsonObject(fields+("category" to JsonPrimitive(target.id))).toString())else row
            }
            next=s.copy(categories=s.categories.filter{it.id !in ids},events=s.events.map{if(it.categoryId in ids)it.copy(categoryId=target.id,revision=it.revision+1)else it},
                budgets=budgets,activityBudgets=activityBudgets,recurrences=s.recurrences.map{if(it.categoryId in ids)it.copy(categoryId=target.id)else it},
                occurrences=s.occurrences.map{if(it.categoryId in ids)it.copy(categoryId=target.id)else it},draft=s.draft?.let{if(it.categoryId in ids)it.copy(categoryId=target.id)else it})
        }
    }
    val months=(s.events.map{it.date.take(7)}+s.budgets.map{it.month}+s.occurrences.map{it.scheduledDate.take(7)}+YearMonth.now(clock).toString()).distinct().sorted()
    val changed=s.categories.map{it.id}.union(next.categories.map{it.id}).flatMap{id->months.mapNotNull{m->
        val before=s.budgetProjection(m,id);val after=next.budgetProjection(m,id)
        if(before!=after||s.categories.find{it.id==id}?.parentId!=next.categories.find{it.id==id}?.parentId)CategoryProjectionChange(id,m,before,after)else null
    }}
    CategoryOperationReview(s,next,drafts,nextDrafts,c,operation,targetId,budgetPolicy,ids,s.events.count{it.categoryId in ids},s.recurrences.count{it.categoryId in ids},s.occurrences.count{it.categoryId in ids},(if(s.draft?.categoryId in ids)1 else 0)+draftReferences,changed,deletionBlocked)
}
suspend fun LedgerRepository.commitCategoryOperation(review:CategoryOperationReview):Boolean=db.withTransaction {
    val b=review.category.bookId
    if(snapshot(requireNotNull(dao.book(b))).first()!=review.before||db.financialDao().bookDrafts(b)!=review.draftRows)return@withTransaction false
    require(review.operation!=CategoryOperation.DELETE_EMPTY||!review.deletionBlocked){"仍有记录、预算、计划或草稿引用，请停用或明确迁移 / 記録・予算・予定・下書きの参照があります。停止または統合してください"}
    val before=review.before;val next=review.after
    next.categories.filter{it!=before.categories.find{c->c.id==it.id}}.forEach{dao.putCategory(it)}
    next.events.filter{it!=before.events.find{e->e.id==it.id}}.forEach{e->
        dao.putEvent(e);dao.putRevision(RevisionEntity(b,id(),e.id,e.revision,clock.millis(),json.encodeToString(before.events.first{it.id==e.id}),json.encodeToString(e)))
    }
    next.recurrences.filter{it!=before.recurrences.find{r->r.id==it.id}}.forEach{dao.putRecurrence(it)}
    next.occurrences.filter{it!=before.occurrences.find{o->o.id==it.id}}.forEach{dao.putOccurrence(it)}
    review.nextDraftRows.filter{it !in review.draftRows}.forEach{db.financialDao().putDraft(it)}
    if(next.draft!=before.draft)next.draft?.let{saveDraft(b,it)}
    if(next.budgets!=before.budgets){dao.clearBudgets(b);next.budgets.forEach{dao.putBudget(it)}}
    if(next.activityBudgets!=before.activityBudgets){before.activities.forEach{dao.clearActivityBudgets(b,it.id)};next.activityBudgets.forEach{dao.putActivityBudget(it)}}
    if(review.operation in listOf(CategoryOperation.MERGE_DELETE,CategoryOperation.DELETE_EMPTY)) {
        // Children first; all formal references have been migrated in this same transaction.
        before.categories.filter{it.id in review.sourceIds}.sortedBy{if(it.parentId==null)1 else 0}.forEach{dao.deleteCategory(b,it.id)}
    }
    true
}
