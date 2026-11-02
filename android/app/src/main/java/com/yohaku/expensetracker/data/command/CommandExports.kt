package com.yohaku.expensetracker.data.command

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.FinancialRuleVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Explicit outbound DTOs. No Entity serializer, file path, draft/media payload or credentials. */
@Serializable data class ContextItem(val id:String,val kind:String,val name:String,val values:JsonObject)
@Serializable data class LedgerContextExport(val format:String="yohaku.ledger-context",val version:Int=1,val book_id:String,
    val generatedAt:String,val coverage:List<String>,val items:List<ContextItem>,val contextDigest:String)
@Serializable data class RecordExport(val id:String,val kind:String,val date:String,val account:String,val category:String?,
    val currency:String,val amountMinor:Long,val baseMinor:Long,val merchant:String,val note:String,val revision:Int,
    val accountMinor:Long?,val accountCurrency:String?,val confirmed:Boolean?,val historicalIncluded:Boolean,
    val cardPaymentChoice:String?,val cardPaymentPeriods:Int?,val cardPaymentMonth:String?,val cardPaymentFeeMinor:Long?,
    val settlementDate:String?=null,val originalEvent:String?=null,val toAccount:String?=null,val toMinor:Long?=null,val toCurrency:String?=null,
    val activity:String?=null,val merchantId:String?=null,val cardPaymentMode:String?=null,val cardPaymentRule:String?=null)
@Serializable data class RecordsExport(val format:String="yohaku.records",val version:Int=1,val book_id:String,val generatedAt:String,
    val scope:RecordScope,val records:List<RecordExport>)
data class CommandExportBundle(val context:LedgerContextExport,val records:RecordsExport?)

fun recordScopeSelector(scope:RecordScope):EntrySelector?=when(scope.mode) {
    "none"->null
    "dates"->{require(scope.from!=null&&scope.through!=null);EntrySelector(from=scope.from,through=scope.through).also(::validateSelector)}
    "account"->{require(!scope.account.isNullOrBlank());EntrySelector(account=scope.account)}
    else->error("记录范围无效 / 記録の範囲が無効です")
}

suspend fun LedgerRepository.commandExports(bookId:String,scope:RecordScope):CommandExportBundle=db.withTransaction {
    val state=financialReviewState(bookId);val s=state.ledger;val f=state.finance;val l=state.limits
    val originalBook=requireNotNull(dao.book(bookId))
    val items=mutableListOf<ContextItem>()
    fun add(id:String,kind:String,name:String,fields:JsonObjectBuilder.()->Unit){items+=ContextItem(id,kind,name,buildJsonObject(fields))}
    add(s.book.id,"book",s.book.name){put("currency",s.book.currency);put("theme",originalBook.theme);put("language",originalBook.language);put("startDate",s.book.startDate);put("budgetMinor",s.book.budgetMinor);put("archived",s.book.archived);put("activityMode",s.features?.activityMode?:false);put("activeActivityId",s.features?.activeActivityId)}
    s.accounts.forEach{a->add(a.id,"account",a.name){put("type",a.type);put("currency",a.currency);put("openingMinor",a.openingMinor);put("openingDate",a.openingDate);put("balanceMinor",s.balance(a));put("active",a.active)}}
    s.categories.forEach{c->add(c.id,"category",s.path(c.id)){put("kind",c.kind);put("parentId",c.parentId);put("active",c.active);put("favorite",c.favorite);put("icon",c.icon)}}
    s.merchants.forEach{m->add(m.id,"merchant",m.name){put("note",m.note);put("active",m.active)}}
    s.merchantLocations.forEach{p->add(p.id,"place",p.name){put("merchantId",p.merchantId);put("latitude",p.latitude);put("longitude",p.longitude);put("radiusMeters",p.radiusMeters)}}
    s.friends.forEach{a->add(a.id,"friend",a.name){put("active",a.active)}}
    s.templates.forEach{t->add(t.id,"template",t.name){put("kind",t.kind);put("accountId",t.accountId);put("categoryId",t.categoryId);put("currency",t.currency);put("amountMinor",t.amountMinor);put("merchant",t.merchant);put("merchantId",t.merchantId);put("note",t.note);put("activityId",t.activityId);put("sortOrder",t.sortOrder);put("cardPaymentChoice",t.cardPaymentChoice);put("cardPaymentPeriods",t.cardPaymentPeriods)}}
    s.budgets.forEach{b->add("${b.month}:${b.scope}","budget",b.month){put("categoryId",b.categoryId);put("currency",s.book.currency);put("amountMinor",b.amountMinor)}}
    s.recurrences.forEach{r->add(r.id,"recurrence",r.name){put("accountId",r.accountId);put("categoryId",r.categoryId);put("currency",r.currency);put("amountMinor",r.amountMinor);put("baseMinor",r.baseMinor);put("estimated",r.estimated);put("frequency",r.frequency);put("anchorDate",r.anchorDate);put("endDate",r.endDate);put("active",r.active);put("activityId",r.activityId);put("merchant",r.merchant);put("note",r.note);put("cardPaymentChoice",r.cardPaymentChoice);put("cardPaymentPeriods",r.cardPaymentPeriods)}}
    s.occurrences.forEach{o->add(o.id,"occurrence",o.name){put("recurrenceId",o.recurrenceId);put("scheduledDate",o.scheduledDate);put("accountId",o.accountId);put("categoryId",o.categoryId);put("currency",o.currency);put("amountMinor",o.amountMinor);put("baseMinor",o.baseMinor);put("estimated",o.estimated);put("status",o.status);put("eventId",o.eventId);put("activityId",o.activityId)}}
    f.rules.forEach{r->add(r.id,"financial_rule",r.mode){put("accountId",r.accountId);put("version",r.version);put("definition",encoded(json.decodeFromString<FinancialRuleVersion>(r.json)))}}
    f.plans.forEach{p->add(p.id,if(p.origin=="POOL")"balance_pool"else"financial_plan",p.name){put("accountId",p.accountId);put("mode",p.mode);put("origin",p.origin);put("sourceEventId",p.sourceEventId);put("currency",p.currency);put("principalMinor",p.principalMinor);put("complete",p.complete);put("active",p.active)
        // The public terms carry dates/amounts, not the internal saved fact/revision snapshots.
        p.termsJson?.let{raw->val input=json.decodeFromString<FinancialPredictionInput>(raw);put("terms",encoded(input.terms));put("paymentAccountId",input.paymentAccountId)
            input.pool?.let{pool->put("pool",buildJsonObject{put("openingMinor",pool.openingMinor);put("sourceDate",pool.sourceDate.name);put("unallocatedShares",encoded(pool.unallocatedShares));put("adoptExisting",pool.adoptExisting);put("paymentChanges",encoded(pool.paymentChanges));put("adoptedFees",encoded(pool.adoptedFees))})}}
    }}
    f.bills.forEach{b->add(b.id,"financial_bill","${b.number}"){put("planId",b.planId);put("number",b.number);put("date",b.date);put("principalMinor",b.principalMinor);put("feeMinor",b.feeMinor);put("totalMinor",b.totalMinor);put("paymentAccountId",b.paymentAccountId);put("ruleId",b.ruleVersionId);put("confirmed",b.confirmed);put("active",b.active)}}
    f.validations.forEach{v->add(v.id,"rule_validation","规则核验 / 条件の検証"){put("ruleId",v.ruleVersionId);put("billId",v.billId);put("billRevision",v.billRevision);put("principalDifferenceMinor",v.principalDifferenceMinor);put("feeDifferenceMinor",v.feeDifferenceMinor);put("totalDifferenceMinor",v.totalDifferenceMinor);put("dateChanged",v.dateChanged);put("checkedAt",v.checkedAt)}}
    f.contracts.forEach{c->add(c.id,"debt_contract",c.name){put("accountId",c.accountId);put("mode",c.mode);put("currency",c.currency);put("principalMinor",c.principalMinor);put("date",c.date);put("fundingEventId",c.fundingEventId);put("fundingAccountId",c.fundingAccountId);put("receivedMinor",c.receivedMinor);put("withheldFeeMinor",c.withheldFeeMinor);put("separateFeeMinor",c.separateFeeMinor);put("separateFeeAccountId",c.separateFeeAccountId)}}
    f.arrangements.forEach{a->add(a.id,"financial_arrangement","已办理安排 / 手続き済みの予定"){put("originalPlanId",a.originalPlanId);put("targetPlanId",a.targetPlanId);put("effectiveDate",a.effectiveDate);put("appliedAt",a.appliedAt);put("principalMinor",a.principalMinor);put("changeFeeMinor",a.changeFeeMinor);put("feeTreatment",a.feeTreatment)}}
    l.configs.forEach{c->add(c.id,"credit_limit","${c.version}"){put("accountId",c.accountId);put("version",c.version);put("currency",c.currency);put("effectiveFrom",c.effectiveFrom);put("totalMinor",c.totalMinor);put("installmentMinor",c.installmentMinor);put("revolvingMinor",c.revolvingMinor);put("sharing",c.sharing);put("combinedMinor",c.combinedMinor);put("basisDate",c.basisDate);put("usedTotalMinor",c.usedTotalMinor);put("usedInstallmentMinor",c.usedInstallmentMinor);put("usedRevolvingMinor",c.usedRevolvingMinor);put("recovery",c.recovery);put("recoveryDelayDays",c.recoveryDelayDays);put("usageBasis",c.usageBasis);put("usageStart",c.usageStart);put("zoneId",c.zoneId)}}
    l.groups.forEach{g->add(g.id,"credit_limit_group",g.name){put("currency",g.currency);put("totalMinor",g.totalMinor);put("usedMinor",g.usedMinor);put("basisDate",g.basisDate);put("accounts",encoded(l.members.filter{it.groupId==g.id}.map{it.accountId}))}}
    l.recoveries.forEach{v->add(v.id,"credit_limit_recovery","额度恢复确认 / 利用枠回復の確認"){put("accountId",v.accountId);put("eventId",v.eventId);put("date",v.date);put("principalMinor",v.principalMinor);put("installmentMinor",v.installmentMinor);put("revolvingMinor",v.revolvingMinor);put("voided",v.voidedAt!=null)}}
    l.observations.forEach{v->add(v.id,"credit_limit_observation","可用额度观测 / 利用可能枠の確認"){put("accountId",v.accountId);put("groupId",v.groupId);put("scope",v.scope);put("availableMinor",v.availableMinor);put("observedAt",v.observedAt)}}
    s.activityBudgets.forEach{b->add("${b.activityId}:${b.categoryId}","activity_budget","活动分类预算 / シーンのカテゴリ予算"){put("activityId",b.activityId);put("categoryId",b.categoryId);put("currency",s.book.currency);put("amountMinor",b.amountMinor)}}
    s.activities.forEach{a->add(a.id,"activity",a.name){put("startDate",a.startDate);put("endDate",a.endDate);put("budgetMinor",a.budgetMinor);put("excludeMonthly",a.excludeMonthly);put("ended",a.ended);put("trashed",a.deletedAt!=null);put("participants",encoded(s.participants.filter{it.activityId==a.id}.sortedBy{it.position}.map{it.friendId}))}}
    s.reserves.forEach{a->add(a.id,"reserve",a.name){put("activityId",a.activityId);put("date",a.date);put("amountMinor",a.amountMinor);put("accountId",a.accountId);put("accountMinor",a.accountMinor);put("accountCurrency",a.accountCurrency);put("releasedMinor",a.releasedMinor);put("active",a.active)}}
    s.expectedIncomes.forEach{a->add(a.id,"expected_income",a.name){put("accountId",a.accountId);put("date",a.date);put("currency",a.currency);put("amountMinor",a.amountMinor);put("accountMinor",a.accountMinor);put("status",a.status)}}
    val coverage=listOf("Book structure and user planning fields; no transaction records unless separately selected.","Monthly natural-language plans are not available in this app version.","Drafts, media bytes, credentials, internal file paths and deleted transaction records excluded.")
    val generated=clock.instant().toString()
    val export=LedgerContextExport(book_id=bookId,generatedAt=generated,coverage=coverage,items=items.sortedWith(compareBy<ContextItem>{it.kind}.thenBy{it.id}),contextDigest="")
    val context=export.copy(contextDigest=digest(encoded(export).jsonObject.let{JsonObject(it.filterKeys{k->k!="contextDigest"})}))
    val selector=recordScopeSelector(scope)
    if(scope.mode=="account")require(s.accounts.any{it.id==scope.account})
    val records=selector?.let{q->
        val related=(s.storedPostings.filter{it.accountId==scope.account}.map{it.eventId}+s.transfers.filter{it.toAccountId==scope.account}.map{it.eventId}+s.expenseComponents.filter{it.accountId==scope.account}.map{it.eventId}).toSet()
        val selected=if(scope.mode=="account")s.visibleEvents.filter{it.accountId==scope.account||it.id in related}else selectEntries(s,q)
        RecordsExport(book_id=bookId,generatedAt=generated,scope=scope,records=selected.map{e->
            val st=s.settlementByEvent[e.id];val transfer=s.transferByEvent[e.id]
            RecordExport(e.id,e.kind,e.date,e.accountId,e.categoryId,e.currency,e.amountMinor,e.baseMinor,e.merchant,e.note,e.revision,st?.accountMinor,st?.accountCurrency,st?.confirmed,e.historicalIncluded,e.cardPaymentChoice,e.cardPaymentPeriods,e.cardPaymentMonth,e.cardPaymentFeeMinor,
                st?.settlementDate,st?.originalEventId,transfer?.toAccountId,transfer?.toMinor,transfer?.toCurrency,s.activityByEvent[e.id],s.merchantByEvent[e.id],e.cardPaymentMode,e.cardPaymentRuleId)
        })
    }
    CommandExportBundle(context,records)
}
