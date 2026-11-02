package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.json.*
import java.time.LocalDate

internal interface CommandAdapter {
    val capability:CommandCapability
    fun validate(input:JsonObject)
    suspend fun normalize(input:JsonObject,run:CommandRun):JsonObject=input
    suspend fun execute(step:CommandStep,run:CommandRun):StepEffect
    suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState):UndoPreparation
}
internal fun capability(name:String,schema:JsonObject,outputs:Map<String,String>,defaults:String,description:String,example:String,
    facts:Boolean=false)=CommandCapability(name,1,schema,outputs,defaults,
    when(name) {
        "account.save"->"Nonblank name and valid date/currency. Existing type is immutable; currency is immutable when used. Credit-limit anchors cannot be in the future; validate all capacities, usage and recovery settings. Current-state statements may use the task asOfDate in its zoneId; known zero usage requires a phone USER_FACT, not another assistant question. Unknown nullable limit policies do not block account creation."
        "financialRule.addVersion"->"Active debt account and matching mode; valid effective date/timezone; payment account is an active non-debt account (CASH, BANK or IC) in this book; deadlines refer to this account's bills. Incomplete rules stay explicitly incomplete. For a Japanese credit card without a stated payment mode, propose SINGLE with an explicit assumption; month-end is closingDay=31, closing-day purchases default into the current bill, and next-month payment means paymentMonthOffset=1. These are proposal conventions, not implicit decoder defaults."
        "financialPrediction.save"->"Active debt account; known opening principal within unallocated opening debt, or a live source event. Explicit dates and original first period for non-SINGLE modes. Recalculation retains source, mode and protected bills."
        else->"1–5000 distinct live expense/income targets in this book; category matches the entry kind and is enabled (or the preserved existing category). No other fields can be changed."
    },
    listOf(ConfirmationKind.INFORMATION,ConfirmationKind.RISK)+if(facts)listOf(ConfirmationKind.USER_FACT)else emptyList(),
    when(name) {
        "account.save"->listOf("account fields and opening debt/date","absolute ledger/as-of balances and negative state","credit-limit configuration, capacity, usage, available amount and issues","related shared limit and cash/budget forecast")
        "financialRule.addVersion"->listOf("account facts","rule definition and effective/version order","retained confirmed and predicted bill facts","affected cash, limits and budget forecast")
        "financialPrediction.save"->listOf("source and opening principal","plan before/after and retained/withdrawn periods","per-period date, principal, fees, payment account, rule basis and confirmation state","repayment/refund fact revisions","absolute balances, limits, cash and budget forecast","user fact condition bound to this step")
        else->listOf("fixed IDs and all identifying event facts","settlement, media and other retained relations","old/new categories and hierarchy","category/month budget absolute values and risk","save ordering and remembered-default semantics","related balances, limits and forecast","every intermediate step, even when later reverted")
    },
    description,commandJson.parseToJsonElement(example).jsonObject,undo=when(name) {
        "account.save"->"Local batch undo restores existing account fields. Accounts and credit-limit versions created by this execution are removed with recorded dependent rows only if unchanged and free of references outside the batch."
        "entry.update"->"Local batch undo restores every original category, including a persisted null or archived category, while retaining entry audit revisions. Full event revisions and related facts must still match execution."
        "financialRule.addVersion"->"Local batch undo removes the version created by this execution, including on an existing account, only if unchanged and without external FK/JSON/draft references."
        "financialPrediction.save"->"Local batch undo removes owned new plans, bills and revision rows; recalculation of an existing plan restores its exact recorded pre-batch plan and bill values. Any changed object or external reference blocks the whole undo."
        else->"Local inverse; cannot itself be undone."
    },unavailable=if(name=="financialPrediction.save")"Incomplete calculation produces a non-executable preview with missing fields. Invalid source relationships or failed commits reject the batch; do not create a zero-debt prediction."else"Reject the batch on invalid input or unmet prerequisites; unspecified nullable fields may remain unknown as documented.")
internal fun requirement(step:String,kind:ConfirmationKind,condition:JsonElement,zh:String,ja:String)=ConfirmationRequirement(step,kind,digest(condition),zh,ja)

internal class AccountCommandAdapter:CommandAdapter {
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState)=UndoPreparation(accountUndo)
    override val capability=capability("account.save",accountSchema,mapOf("account" to "account"),"Full account save: account omitted/null creates; type=CREDIT even on edit, so explicitly supply the existing type for non-CREDIT accounts. name/currency/openingMinor/openingDate required. creditLimit omitted/null preserves existing settings; it cannot delete a configuration. Assistant proposal convention: for a current/now state, use task asOfDate for openingDate; no debt means openingMinor=0 and zero usage anchors when adding creditLimit. List every assumption in message, never supply USER_FACT flags. " + creditLimitInputGuide,
        "Create/edit CASH, BANK, CREDIT, LOAN or IC accounts, opening balance/debt and opening date. Optional creditLimit covers total/installment/revolving/shared sublimits, usage anchor, recovery policy/delay, usage start and timezone. Payment modes/bonus windows are financialRule.addVersion.",
        """{"name":"カード","currency":"JPY","openingMinor":30000,"openingDate":"2026-11-01"}""",facts=true)
    override fun validate(input:JsonObject) { val v=commandJson.decodeFromJsonElement<AccountCommandInput>(input);AccountType.valueOf(v.type);Currency.valueOf(v.currency);LocalDate.parse(v.openingDate) }
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<AccountCommandInput>(step.input);val r=run.ledger
        val old=v.account?.let{run.resolve(it)}
        val before=run.state();val account=r.saveAccount(run.bookId,v.name,AccountType.valueOf(v.type),Currency.valueOf(v.currency),Money(Currency.valueOf(v.currency),v.openingMinor),v.openingDate,old)
        run.output(step.id,"account",account,old==null)
        var savedLimit:CreditLimitInput?=null
        v.creditLimit?.let{raw->
            val input=commandJson.decodeFromJsonElement<CreditLimitInput>(JsonObject(raw+("accountId" to JsonPrimitive(account))))
            savedLimit=input
            val review=r.previewCreditLimit(run.bookId,input)
            review.config?.let{run.generated(it.id,step.id,"creditLimit",it.version.toString())}
            check(r.commitCreditLimit(review)){"额度资料已变化 / 利用枠情報が変わりました"}
        }
        val effect=run.effect(step,before,run.state(refresh=true),setOf(account))
        return savedLimit?.let{financeUsageFact(step,run,effect,it)}?:effect
    }
}

internal class RuleCommandAdapter:CommandAdapter {
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState)=UndoPreparation(ruleUndo)
    override val capability=capability("financialRule.addVersion",ruleSchema,mapOf("rule" to "rule"),"Append a version, never a patch of the previous rule. For current/now conditions, explicitly set rule.effectiveFrom=task asOfDate and zoneId=task zoneId (Japanese card: Asia/Tokyo if no contrary evidence). Japanese credit card mode omitted in the request: propose SINGLE; month-end closing: billing.closingDay=31; closing-day purchases: billing.closingDayInCurrentBill=true; next-month payment: billing.paymentMonthOffset=1, with paymentDay taken from the request. State these assumptions in message. Do not invent unknown fees, rates or historical facts from an institution name. Outer paymentAccount, when supplied, overrides rule.billing.paymentAccountId; omitted/null leaves the supplied rule billing unchanged. Payment account and deadline bill IDs accept typed earlier-step references, including nested billing.paymentAccountId/deadlines.billId.",
        "Append a rule version: SINGLE (billing and bonus windows), INSTALLMENT (fees, remainder), REVOLVING (interest, tiers, automatic mode), FIXED_LOAN/CREDIT_LINE, all loan methods (EQUAL_PRINCIPAL/ANNUITY/INTEREST_ONLY/MANUAL) and revolving methods (FIXED_PRINCIPAL/TOTAL_PAYMENT/BALANCE_TIERS). Existing confirmed bills are preserved.",
        """{"account":{"ref":"card","output":"account"},"rule":{"mode":"SINGLE","effectiveFrom":"2026-11-01","zoneId":"Asia/Tokyo"}}""")
    override fun validate(input:JsonObject) { commandJson.decodeFromJsonElement<RuleCommandInput>(input) }
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<RuleCommandInput>(step.input);val account=run.resolve(v.account)
        val resolvedRule=financeInput(ruleSchema.getValue("properties").jsonObject.getValue("rule").jsonObject,v.rule,run::resolve).jsonObject
        var fields=JsonObject(resolvedRule+mapOf("id" to JsonPrimitive(""),"bookId" to JsonPrimitive(run.bookId),"accountId" to JsonPrimitive(account)))
        v.paymentAccount?.let{payment->fields=JsonObject(fields+("billing" to JsonObject((fields["billing"] as? JsonObject).orEmpty()+("paymentAccountId" to JsonPrimitive(run.resolve(payment))))))}
        val rule=commandJson.decodeFromJsonElement<FinancialRuleVersion>(fields)
        val before=run.state();val review=run.ledger.previewFinancialRule(run.bookId,account,rule)
        val id=checkNotNull(run.ledger.commitFinancialRule(review)){"规则资料已变化 / 条件情報が変わりました"}
        run.output(step.id,"rule",id,true)
        return run.effect(step,before,run.state(refresh=true),setOf(account),unknown=rule.issues())
    }
}

internal class PredictionCommandAdapter:CommandAdapter {
    override suspend fun inverse(step:CommandStep,run:CommandRun,before:FinancialReviewState,after:FinancialReviewState)=UndoPreparation(predictionUndo)
    override val capability=capability("financialPrediction.save",predictionSchema,mapOf("prediction" to "prediction"),"No invented principal or historical dates; dates derived from supplied billing rules may be proposed with assumptions in message. Supply exactly one of terms or candidates for an executable prediction. plan omitted/null creates; plan references an existing prediction for recalculation. paymentAccount omitted/null is unassigned. For non-SINGLE modes explicitly supply firstPeriodNumber; 1 means the first contractual period, not the first remaining row. Existing plan preserves confirmed periods. User fact cannot be supplied in input.",
        "Create or recalculate a rule-based prediction for opening debt or a recorded expense. Incomplete calculation lists missing information and blocks the entire batch. Do not add a prediction for zero debt with no source transaction; creating the card and its rule completes that request.",
        """{"account":{"ref":"card","output":"account"},"name":"期初欠款","mode":"SINGLE","openingPrincipalMinor":30000,"terms":{"agreementDate":"2026-11-01","paymentDates":["2026-12-10"]}}""",facts=true)
    override fun validate(input:JsonObject) {val v=commandJson.decodeFromJsonElement<PredictionCommandInput>(input);require(v.terms==null||v.candidates==null);require(v.terms==null||v.terms.paymentDates.size<=1200)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        val v=commandJson.decodeFromJsonElement<PredictionCommandInput>(step.input);val account=run.resolve(v.account)
        val before=run.state();val plan=v.plan?.let{run.resolve(it)};val event=v.sourceEvent?.let{run.resolve(it)}
        val firstPeriodMissing=v.mode!=FinanceMode.SINGLE&&if(v.terms!=null)
            step.input.getValue("terms").jsonObject["firstPeriodNumber"]?.jsonPrimitive?.intOrNull==null else v.candidates?.firstPeriodNumber==null
        val missing=buildList {
            if(v.terms==null&&v.candidates==null||v.terms?.paymentDates?.isEmpty()==true)add("prediction.dates")
            if(event==null&&v.openingPrincipalMinor==null&&plan==null)add("prediction.openingPrincipal")
            if(firstPeriodMissing)add("prediction.firstPeriodNumber")
        }
        if(missing.isNotEmpty())return StepEffect(step.id,step.command,listOf(run.semantic(JsonPrimitive(account)).jsonPrimitive.content),emptyList(),missing=missing,risks=missing.map{CommandRisk("PREDICTION_INCOMPLETE",run.semantic(JsonPrimitive(account)).jsonPrimitive.content,detail=it)})
        val terms=v.terms?:v.candidates?.let{c->
            val rule=run.ledger.effectiveFinancialRule(run.bookId,account,v.mode,LocalDate.parse(c.agreementDate))
            val rows=predictionCandidates(v.mode,c.count,LocalDate.parse(c.agreementDate),LocalDate.parse(c.firstDate),LocalDate.parse(c.firstEnd),rule,before.ledger.events.firstOrNull{it.id==event})
            FinancialScheduleTerms(c.agreementDate,rows.map{it.first},if(v.mode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE))rows.map{it.second}else emptyList(),firstPeriodNumber=c.firstPeriodNumber?:1,remainingFeeMinor=c.remainingFeeMinor)
        }
        // Trial execution is hypothetical. The required USER_FACT is always generated below; the
        // protocol cannot set it. Confirm validates the user's bound checks before committing.
        val input=FinancialPredictionInput(account,v.name,v.mode,terms!!,event,v.openingPrincipalMinor,v.paymentAccount?.let{run.resolve(it)},plan,currentArrangementConfirmed=true)
        val review=run.ledger.previewFinancialPrediction(run.bookId,input)
        val incomplete=when(val result=review.calculation){is Calculation.Incomplete->result.fields;is Calculation.Unsupported->listOf(result.reason);is Calculation.CannotSettle->listOf("prediction.cannotSettle");is Calculation.Ready->emptyList()}
        if(incomplete.isNotEmpty())return StepEffect(step.id,step.command,listOf(run.semantic(JsonPrimitive(account)).jsonPrimitive.content),emptyList(),missing=incomplete,risks=incomplete.map{CommandRisk("PREDICTION_INCOMPLETE",run.semantic(JsonPrimitive(account)).jsonPrimitive.content,detail=it)})
        run.output(step.id,"prediction",review.plan.id,plan==null)
        review.bills.filter{bill->before.finance.bills.none{it.id==bill.id}}.forEach{run.generated(it.id,step.id,"bill",it.number.toString())}
        checkNotNull(run.ledger.commitFinancialPrediction(review)){"预测资料已变化 / 予測情報が変わりました"}
        val effect=run.effect(step,before,run.state(refresh=true),setOf(account))
        return if(plan!=null)effect else financeFact(step,run,effect,encoded(v),account,review.originalPrincipalMinor,terms.agreementDate,
            "这是平台当前安排，来源或期初本金、逐期日期和金额均已核对。","返済先の現在の予定で、対象・期首元金・各回の日付と金額を確認しました。")
    }
}

internal fun validateSelector(s:EntrySelector) {
    s.from?.let{LocalDate.parse(it)};s.through?.let{LocalDate.parse(it)}
    require(s.from==null||s.through==null||s.from<=s.through)
    require(s.min_minor==null||s.max_minor==null||s.min_minor<=s.max_minor)
    require(s.kind==null||s.kind in listOf("EXPENSE","INCOME","TRANSFER","REFUND"))
    require(s.min_minor==null&&s.max_minor==null||s.currency!=null){"金额选择器须指定币种 / 金額条件には通貨が必要です"}
    s.currency?.let{Currency.valueOf(it)}
}
internal fun selectEntries(s:BookSnapshot,q:EntrySelector)=s.visibleEvents.filter{e->
    (q.from==null||e.date>=q.from)&&(q.through==null||e.date<=q.through)&&(q.account==null||e.accountId==q.account)&&
    (q.category==null||e.categoryId==q.category)&&(q.merchant_contains==null||e.merchant.contains(q.merchant_contains,ignoreCase=true))&&
    (q.note_contains==null||e.note.contains(q.note_contains,ignoreCase=true))&&(q.kind==null||e.kind==q.kind)&&
    (q.currency==null||e.currency==q.currency)&&(q.min_minor==null||e.amountMinor>=q.min_minor)&&(q.max_minor==null||e.amountMinor<=q.max_minor)
}
