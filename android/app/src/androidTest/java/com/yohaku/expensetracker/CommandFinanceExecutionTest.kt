package com.yohaku.expensetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CommandFinanceExecutionTest {
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    private lateinit var commands:CommandRepository
    private var now=Instant.parse("2026-11-02T03:00:00Z")
    private val clock=object:Clock(){override fun getZone()=ZoneId.of("Asia/Tokyo");override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone);override fun instant()=now}
    @Before fun open(){db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build();r=LedgerRepository(db,clock);commands=CommandRepository(r)}
    @After fun close(){db.close()}
    private fun step(id:String,name:String,input:String)=CommandStep(id,name,1,commandJson.parseToJsonElement(input).jsonObject)
    private fun ref(step:String,role:String)="""{"ref":"$step","output":"$role"}"""
    private suspend fun setup():Pair<String,String>{val b=r.createBook("金融组合",Currency.JPY,startDate="2026-11-01");return b to r.saveAccount(b,"银行・銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,300000),"2026-11-01")}
    private suspend fun card(b:String,name:String,amount:Long)=r.saveAccount(b,name,AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,amount),"2026-11-01")
    private suspend fun receive(b:String,steps:List<CommandStep>):CommandPlanEntity {
        val p=CommandPlan("yohaku.command-plan",1,UUID.randomUUID().toString(),b,commands.registry.catalogDigest,r.commandExports(b,RecordScope()).context.contextDigest,"金融与额度组合",steps=steps)
        return commands.receive(commandClaim(p,resultId=p.plan_id,localId=p.plan_id),"https://example.test",p.context_digest)
    }
    private suspend fun cycle(b:String,steps:List<CommandStep>,verify:suspend (ConfirmationModel,FinancialReviewState)->Unit) {
        val original=CommandUndoJournal(r,b).snapshot();val row=receive(b,steps);assertEquals(row.error,"PENDING",row.status)
        val preview=commands.preview(row.planId);assertTrue(preview.conflicts.toString()+preview.steps.filter{it.missing.isNotEmpty()}.map{it.stepId to it.missing},preview.executable);assertTrue(preview.steps.all{it.undo.available});assertEquals(original,CommandUndoJournal(r,b).snapshot())
        now=now.plusSeconds(2);val rerun=commands.preview(row.planId);assertNull(CommandConfirmationComparator.firstDifference(preview,rerun))
        commands.confirm(row.planId,commandChecks(rerun));verify(rerun,r.financialReviewState(b,preserveLedgerInputs=true))
        undo(b,row.planId,original)
    }
    private suspend fun undo(b:String,id:String,original:Map<String,Pair<String,JsonObject>>) {
        val executed=CommandUndoJournal(r,b).snapshot();val inverse=commands.prepareUndo(id);val model=commands.preview(inverse.planId)
        assertTrue(model.conflicts.toString(),model.executable);assertEquals(executed,CommandUndoJournal(r,b).snapshot())
        commands.confirm(inverse.planId,commandChecks(model));assertEquals(original,CommandUndoJournal(r,b).snapshot());assertEquals("UNDONE",commands.plan(id).status)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun pendingRepaymentRejectsOverAllocationLocallyAndThroughCloudCommand()=runBlocking<Unit> {
        val (b,bank)=setup();val debt=card(b,"待确认",2000)
        val plan=checkNotNull(r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(debt,"两期",FinanceMode.SINGLE,
            listOf(KnownBillInput("2026-12-10",1000,1000,0,bank),KnownBillInput("2027-01-10",1000,1000,0,bank)),2000,true))))
        val bills=db.financialDao().bills(b,plan)
        val input=FinancialRepaymentInput(bank,debt,Money(Currency.JPY,1000),"2026-11-02",principal=Money(Currency.JPY,1000),
            allocations=bills.map{RepaymentAllocationInput(billId=it.id,principalMinor=1000,interestMinor=0,feeMinor=0)})
        val before=CommandUndoJournal(r,b).snapshot()
        assertThrows(IllegalArgumentException::class.java){runBlocking{r.previewRepayment(b,input)}}
        val payload=commandJson.encodeToJsonElement(FinancialRepaymentInput.serializer(),input).jsonObject.filterKeys{it!="platformConfirmed"}
        val row=receive(b,listOf(CommandStep("pending","financialRepayment.save",1,JsonObject(payload))))
        assertThrows(IllegalArgumentException::class.java){runBlocking{commands.preview(row.planId)}}
        assertEquals(before,CommandUndoJournal(r,b).snapshot())
        val valid=input.copy(received=Money(Currency.JPY,1000),interest=Money(Currency.JPY,0),fee=Money(Currency.JPY,0),
            allocations=input.allocations.map{it.copy(principalMinor=500)})
        val validPayload=commandJson.encodeToJsonElement(FinancialRepaymentInput.serializer(),valid).jsonObject.filterKeys{it!="platformConfirmed"}
        val accepted=receive(b,listOf(CommandStep("pending","financialRepayment.save",1,JsonObject(validPayload))))
        val preview=commands.preview(accepted.planId);assertTrue(preview.executable)
        commands.confirm(accepted.planId,commandChecks(preview))
        val state=r.financialReviewState(b);assertFalse(state.ledger.repayments.single().platformConfirmed)
        assertEquals(299000L,state.ledger.balance(state.ledger.accounts.first{it.id==bank}))
        assertEquals(1000L,state.ledger.cashTimeline(state.finance,LocalDate.parse("2026-11-02"),LocalDate.parse("2027-01-31")).steps.filter{it.item.kind=="BILL"}.sumOf{it.item.amountMinor!!})
    }
    @Test fun trashingCardPurchaseThroughCloudRetiresItsPredictionAndUndoRestoresIt()=runBlocking<Unit> {
        val (b,bank)=setup();val c=card(b,"一次付卡",0)
        r.commitFinancialRule(r.previewFinancialRule(b,c,FinancialRuleVersion("",b,c,FinanceMode.SINGLE,"2026-11-01","Asia/Tokyo",BillingRule(15,true,1,10,bank))))
        val cat=r.saveCategory(b,"生活",EntryKind.EXPENSE,null)
        val e=r.saveEntry(b,EntryDraft(accountId=c,categoryId=cat,amount="1000",date="2026-11-02",merchant="買物",cardPaymentChoice="SINGLE"))
        val plan=db.financialDao().graph(b).first()!!.plans.single{it.sourceEventId==e}
        cycle(b,listOf(step("trash","entry.lifecycle","""{"entry":"$e","operation":"TRASH"}"""))){_,s->
            assertFalse(s.finance.plans.single{it.id==plan.id}.active);assertTrue(s.finance.bills.none{it.planId==plan.id&&it.active})
        }
        assertTrue(db.financialDao().graph(b).first()!!.plans.single{it.id==plan.id}.active)
    }
    @Test fun repaymentRestoreCannotReuseBillCapacityLocallyOrThroughCloudCommand()=runBlocking<Unit> {
        val (b,bank)=setup();val debt=card(b,"还款恢复",1000)
        val plan=checkNotNull(r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(debt,"账单",FinanceMode.SINGLE,
            listOf(KnownBillInput("2026-12-10",1000,1000,0,bank)),1000,true))))
        val bill=db.financialDao().bills(b,plan).single()
        val input=FinancialRepaymentInput(bank,debt,Money(Currency.JPY,1000),"2026-11-02",Money(Currency.JPY,1000),Money(Currency.JPY,1000),Money(Currency.JPY,0),Money(Currency.JPY,0),
            platformConfirmed=true,clearingDate="2026-11-02",allocations=listOf(RepaymentAllocationInput(billId=bill.id,principalMinor=1000,interestMinor=0,feeMinor=0)))
        val first=checkNotNull(r.commitFinancialLedger(r.previewRepayment(b,input)));r.deleteEntry(b,first)
        val stale=r.previewEntryLifecycle(b,first,EntryLifecycleOperation.RESTORE)
        val second=checkNotNull(r.commitFinancialLedger(r.previewRepayment(b,input)))
        assertFalse(r.commitEntryLifecycle(stale))
        val before=CommandUndoJournal(r,b).snapshot()
        assertThrows(IllegalArgumentException::class.java){runBlocking{r.restoreEntry(b,first)}}
        val row=receive(b,listOf(step("restore","entry.lifecycle","""{"entry":"$first","operation":"RESTORE"}""")))
        assertThrows(IllegalArgumentException::class.java){runBlocking{commands.preview(row.planId)}}
        assertEquals(before,CommandUndoJournal(r,b).snapshot())
        r.deleteEntry(b,second)
        val preview=commands.preview(row.planId);assertTrue(preview.executable)
        commands.confirm(row.planId,commandChecks(preview))
        val state=r.financialReviewState(b);assertEquals(first,state.ledger.visibleEvents.single().id)
        assertEquals(0L,state.ledger.billOutstanding(bill).totalMinor)
    }
    @Test fun multipleCardsInstallmentsPoolFactsBindToChangedObjectsAndWholeUndo()=runBlocking<Unit> {
        val (b,bank)=setup();val a=card(b,"分期卡",12000);val c=card(b,"循环卡",10000)
        val steps=commandFinanceSteps(bank,a,c);val row=receive(b,steps);val before=CommandUndoJournal(r,b).snapshot()
        val first=commands.preview(row.planId);assertTrue(first.executable);assertEquals(before,CommandUndoJournal(r,b).snapshot())
        assertEquals(2,first.requirements.count{it.kind==ConfirmationKind.USER_FACT})
        try{commands.confirm(row.planId,emptySet());fail("phone facts required")}catch(_:IllegalArgumentException){}
        assertEquals(before,CommandUndoJournal(r,b).snapshot())
        now=now.plusSeconds(2);assertNull(CommandConfirmationComparator.firstDifference(first,commands.preview(row.planId)))
        r.saveAccount(b,"已核对的新卡名",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-11-01",c)
        val changed=CommandUndoJournal(r,b).snapshot()
        try{commands.confirm(row.planId,commandChecks(first));fail("changed fact must invalidate old checks")}catch(_:CommandRepreviewRequired){}
        assertNull(commands.confirmation(commands.plan(row.planId)));assertEquals(changed,CommandUndoJournal(r,b).snapshot())
        val fresh=commands.preview(row.planId);assertNotEquals(commandChecks(first),commandChecks(fresh))
        try{commands.confirm(row.planId,commandChecks(first));fail("old declaration binding must not carry over")}catch(_:IllegalArgumentException){}
        commands.confirm(row.planId,commandChecks(fresh));val state=r.financialReviewState(b)
        assertEquals(2,state.finance.plans.size);assertEquals(4,state.finance.bills.size);assertEquals(120L,state.finance.bills.sumOf{it.feeMinor?:0})
        assertTrue(fresh.net.any{it.type=="cashForecast"&&it.before!=it.after})
        assertTrue(fresh.requirements.filter{it.kind==ConfirmationKind.USER_FACT}.all{it.zh.contains("2026-11-01")})
        assertTrue(fresh.requirements.any{it.zh.contains(Money(Currency.JPY,12000).display())})
        undo(b,row.planId,changed)
    }
    @Test fun unknownLastBillPrincipalBlocksTheWholePredictionBatch()=runBlocking<Unit> {
        val (b,bank)=setup();val a=card(b,"未知拆分",10000)
        val rule=FinancialRuleVersion("",b,a,FinanceMode.SINGLE,"2026-11-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,bank))
        r.commitFinancialRule(r.previewFinancialRule(b,a,rule))!!
        val plan=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(a,"已知总额",FinanceMode.SINGLE,
            listOf(KnownBillInput("2026-12-27",5000,paymentAccountId=bank)),principalMinor=10000,opening=true)))!!
        val before=CommandUndoJournal(r,b).snapshot()
        val row=receive(b,listOf(
            step("budget","budget.save","""{"month":"2026-11","amount":{"currency":"JPY","minor":12345}}"""),
            step("forecast","financialPrediction.save","""{"account":"$a","name":"已知总额","mode":"SINGLE","plan":"$plan","openingPrincipalMinor":10000,"terms":{"agreementDate":"2026-11-01","paymentDates":["2026-12-27"]}}""")))
        val preview=commands.preview(row.planId)
        assertFalse(preview.executable)
        assertEquals(listOf("plan.confirmedPrincipal"),preview.steps.single{it.stepId=="forecast"}.missing)
        assertEquals(before,CommandUndoJournal(r,b).snapshot())
        try{commands.confirm(row.planId,commandChecks(preview));fail("incomplete prediction must block the batch")}catch(_:IllegalStateException){}
        assertEquals(before,CommandUndoJournal(r,b).snapshot())
        val bill=db.financialDao().bills(b,plan).single()
        assertEquals(5000L,bill.totalMinor);assertNull(bill.principalMinor);assertNull(bill.feeMinor)
        assertFalse(db.financialDao().plan(b,plan)!!.complete)
    }

    @Test fun arrangedPredictionRecalculationKeepsFeesAndRejectsPreAgreementInterest()=runBlocking<Unit> {
        val (b,bank)=setup();val a=card(b,"办理后重算",0);val category=r.saveCategory(b,"购物",EntryKind.EXPENSE,null)
        val event=r.saveEntry(b,EntryDraft(accountId=a,categoryId=category,amount="10000",date="2026-11-01"))
        val original=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(a,"原一次付",FinanceMode.SINGLE,
            listOf(KnownBillInput("2026-12-10",10000,10000,0,bank)),10000,true,sourceEventId=event)))!!
        val rule=FinancialRuleVersion("",b,a,FinanceMode.REVOLVING,"2026-11-01","Asia/Tokyo",billing=BillingRule(30,true,1,10,bank),
            revolving=RevolvingRule(InterestRule("0.12",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,5000,allocation=AllocationOrder.FEES_FIRST,automatic=false))
        r.commitFinancialRule(r.previewFinancialRule(b,a,rule))!!
        val terms=FinancialScheduleTerms("2026-11-02",listOf("2026-12-10","2027-01-10"),listOf(AccrualWindow("2026-11-02","2026-11-30"),AccrualWindow("2026-12-01","2026-12-30")))
        val plan=r.commitFinancialArrangement(r.previewFinancialArrangement(b,FinancialArrangementInput(original,"已办理循环",FinanceMode.REVOLVING,terms,bank,OriginalFeeTreatment.RETAIN,300)),"2026-11-02T00:00:00Z",true)!!
        val saved=r.json.decodeFromString<FinancialPredictionInput>(db.financialDao().plan(b,plan)!!.termsJson!!)
        val before=CommandUndoJournal(r,b).snapshot()
        fun forecast(t:FinancialScheduleTerms)=step("forecast","financialPrediction.save","""{"account":"$a","name":"已办理循环","mode":"REVOLVING","plan":"$plan","sourceEvent":"$event","paymentAccount":"$bank","terms":${encoded(t)}}""")
        val budget=step("budget","budget.save","""{"month":"2026-11","amount":{"currency":"JPY","minor":12345}}""")
        for(fees in listOf(emptyList(),listOf(AdditionalPeriodFee(1,100)))) {
            val row=receive(b,listOf(budget,forecast(saved.terms.copy(additionalFees=fees))))
            assertThrows(IllegalArgumentException::class.java){runBlocking{commands.preview(row.planId)}}
            assertEquals(before,CommandUndoJournal(r,b).snapshot())
        }
        val invalid=receive(b,listOf(budget,forecast(saved.terms.copy(accrualWindows=listOf(AccrualWindow("2026-11-01","2026-11-30"))+terms.accrualWindows.drop(1)))))
        val preview=commands.preview(invalid.planId)
        assertFalse(preview.executable)
        assertEquals(listOf("plan.accrualBeforeAgreement"),preview.steps.single{it.stepId=="forecast"}.missing)
        assertEquals(before,CommandUndoJournal(r,b).snapshot())
        assertThrows(IllegalStateException::class.java){runBlocking{commands.confirm(invalid.planId,commandChecks(preview))}}
        assertEquals(before,CommandUndoJournal(r,b).snapshot())
        val originalBills=db.financialDao().bills(b,plan).sortedBy{it.number}
        val candidates=step("forecast","financialPrediction.save","""{"account":"$a","name":"已办理循环","mode":"REVOLVING","plan":"$plan","sourceEvent":"$event","paymentAccount":"$bank","candidates":{"agreementDate":"2026-11-02","count":2,"firstDate":"2026-12-10","firstEnd":"2026-11-30","firstPeriodNumber":1}}""")
        cycle(b,listOf(candidates)) {_,state->
            val definition=r.json.decodeFromString<FinancialPredictionInput>(state.finance.plans.first{it.id==plan}.termsJson!!)
            assertEquals(saved.terms.additionalFees,definition.terms.additionalFees)
            assertEquals("2026-11-02",definition.terms.accrualWindows.first().start)
            assertEquals(originalBills.map{it.totalMinor},state.finance.bills.filter{it.planId==plan}.sortedBy{it.number}.map{it.totalMinor})
        }
    }

    @Test fun manualBorrowingContractAllocationAndRepayment()=runBlocking<Unit> {
        val (b,bank)=setup();val loan=r.saveAccount(b,"贷款",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-11-01")
        cycle(b,listOf(
            step("borrow","financialBorrowing.save","""{"accountId":"$loan","name":"购置借款","mode":"FIXED_LOAN","principal":{"currency":"JPY","minor":30000},"date":"2026-11-02","fundingAccountId":"$bank","received":{"currency":"JPY","minor":30000}}"""),
            step("manual","financialManualPlan.save","""{"accountId":"$loan","name":"三期已知计划","mode":"FIXED_LOAN","sourceEventId":${ref("borrow","entry")},"principalMinor":30000,"complete":true,"periods":[{"date":"2026-12-10","totalMinor":10000,"principalMinor":10000,"feeMinor":0,"paymentAccountId":"$bank"},{"date":"2027-01-10","totalMinor":10000,"principalMinor":10000,"feeMinor":0,"paymentAccountId":"$bank"},{"date":"2027-02-10","totalMinor":10000,"principalMinor":10000,"feeMinor":0,"paymentAccountId":"$bank"}]}"""),
            step("link","financialContract.linkPlan","""{"contractId":${ref("borrow","contract")},"planId":${ref("manual","prediction")}}"""),
            step("bill","financialBill.resolve","""{"planId":${ref("manual","prediction")},"number":1}"""),
            step("pending","financialRepayment.save","""{"sourceAccountId":"$bank","debtAccountId":"$loan","paid":{"currency":"JPY","minor":10000},"date":"2026-11-02"}"""),
            step("paid","financialRepayment.confirm","""{"eventId":${ref("pending","entry")},"sourceAccountId":"$bank","debtAccountId":"$loan","paid":{"currency":"JPY","minor":10000},"date":"2026-11-02","received":{"currency":"JPY","minor":10000},"principal":{"currency":"JPY","minor":10000},"interest":{"currency":"JPY","minor":0},"fee":{"currency":"JPY","minor":0},"clearingDate":"2026-11-02","type":"PARTIAL","allocations":[{"billId":${ref("bill","bill")},"contractId":${ref("borrow","contract")},"principalMinor":10000,"interestMinor":0,"feeMinor":0}]}"""),
            step("allocation","financialRepaymentAllocation.resolve","""{"eventId":${ref("paid","entry")},"billId":${ref("bill","bill")}}"""))) {model,state->
            assertEquals(20000L,state.ledger.contractRemaining(state.finance.contracts.single()));assertEquals(320000L,state.ledger.balance(state.ledger.accounts.single{it.id==bank}))
            val pendingDebt=model.steps.single{it.stepId=="pending"}.objects.single{it.type=="balance"&&it.key==loan}
            assertEquals(pendingDebt.before,pendingDebt.after)
            assertEquals(1,state.finance.contractPlans.size);assertEquals(1,state.finance.repaymentAllocations.size)
            val repaymentFacts=model.steps.single{it.stepId=="paid"}.objects.single{it.type=="repayment"}.after.jsonObject
            assertEquals(JsonPrimitive("JPY"),repaymentFacts["sourceCurrency"])
            val contractFacts=model.steps.single{it.stepId=="borrow"}.objects.single{it.type=="contract"}.after.jsonObject
            assertEquals(JsonPrimitive("JPY"),contractFacts["fundingCurrency"])
            assertTrue(model.net.any{it.type=="contractBalance"&&it.after.jsonObject["remainingMinor"]==JsonPrimitive(20000)})
        }
    }
    @Test fun ruleValidationAndAppliedArrangementReconciliation()=runBlocking<Unit> {
        val (b,bank)=setup();val a=card(b,"分期卡",12000)
        val prefix=commandFinanceSteps(bank,a,card(b,"循环卡",10000)).filter{it.id in setOf("splitRule","split")}
        cycle(b,prefix+listOf(
            step("first","financialBill.resolve","""{"planId":${ref("split","prediction")},"number":1}"""),
            step("bill","financialBill.confirm","""{"billId":${ref("first","bill")},"bill":{"date":"2026-12-10","totalMinor":6060,"principalMinor":6000,"feeMinor":60,"paymentAccountId":"$bank"}}"""),
            step("check","financialRule.validate","""{"billId":${ref("first","bill")}}"""),
            // Confirming an actual bill makes the saved prediction facts stale; the app requires recalculation before arranging.
            step("recalc","financialPrediction.save","""{"plan":${ref("split","prediction")},"account":"$a","name":"两期分期・2回分割","mode":"INSTALLMENT","openingPrincipalMinor":12000,"paymentAccount":"$bank","terms":{"agreementDate":"2026-11-01","paymentDates":["2026-12-10","2027-01-10"],"firstPeriodNumber":1,"remainingFeeMinor":120}}"""),
            step("arrange","financialArrangement.record","""{"input":{"planId":${ref("split","prediction")},"name":"平台新安排","mode":"INSTALLMENT","terms":{"agreementDate":"2026-11-02","paymentDates":["2026-12-10","2027-01-10"],"firstPeriodNumber":1},"paymentAccountId":"$bank","originalFeeTreatment":"CANCELLED_BY_PLATFORM","changeFeeMinor":0},"appliedAt":"2026-11-02T00:00:00Z"}"""),
            step("second","financialBill.resolve","""{"planId":${ref("split","prediction")},"number":2}"""),
            step("reconcile","financialArrangement.reconcile","""{"planId":${ref("arrange","prediction")},"effectiveDate":"2026-11-02","principalMinor":12000,"bills":[{"billId":${ref("first","bill")},"principalMinor":0,"feeMinor":0},{"billId":${ref("second","bill")},"principalMinor":0,"feeMinor":0}],"refunds":[]}"""))) {model,state->
            assertEquals(1,state.finance.validations.size);assertEquals(0L,state.finance.validations.single().totalDifferenceMinor)
            assertEquals(1,state.finance.arrangements.size);assertNotNull(state.finance.plans.single{it.name=="平台新安排"}.reconciliationJson)
            assertTrue(model.steps.single{it.stepId=="reconcile"}.requirements.any{it.kind==ConfirmationKind.USER_FACT})
        }
    }
    @Test fun sharedLimitsRecoveryVoidAndActualObservation()=runBlocking<Unit> {
        val (b,bank)=setup();val a=card(b,"卡一",10000);val c=card(b,"卡二",20000)
        cycle(b,listOf(
            step("limit","creditLimit.save","""{"accountId":"$a","totalMinor":100000,"installmentMinor":null,"revolvingMinor":null,"sharing":"TOTAL_ONLY","combinedMinor":null,"effectiveFrom":"2026-11-01","basisDate":"2026-11-01","usedTotalMinor":10000,"usedInstallmentMinor":null,"usedRevolvingMinor":null,"recovery":"MANUAL","usageBasis":"PRINCIPAL_ONLY","zoneId":"Asia/Tokyo"}"""),
            step("group","creditLimitGroup.save","""{"name":"家庭共享额度","currency":"JPY","totalMinor":200000,"usedMinor":30000,"basisDate":"2026-11-01","accountIds":["$a","$c"]}"""),
            step("paid","financialRepayment.confirm","""{"sourceAccountId":"$bank","debtAccountId":"$a","paid":{"currency":"JPY","minor":1000},"date":"2026-11-02","received":{"currency":"JPY","minor":1000},"principal":{"currency":"JPY","minor":1000},"interest":{"currency":"JPY","minor":0},"fee":{"currency":"JPY","minor":0},"clearingDate":"2026-11-02"}"""),
            step("recovery","creditLimitRecovery.confirm","""{"accountId":"$a","eventId":${ref("paid","entry")},"date":"2026-11-02","principalMinor":1000,"installmentMinor":null,"revolvingMinor":null}"""),
            step("void","creditLimitRecovery.void","""{"recoveryId":${ref("recovery","recovery")}}"""),
            step("recheck","creditLimitRecovery.confirm","""{"accountId":"$a","eventId":${ref("paid","entry")},"date":"2026-11-02","principalMinor":1000,"installmentMinor":null,"revolvingMinor":null}"""),
            step("observe","creditLimitObservation.record","""{"groupId":${ref("group","group")},"scope":"TOTAL","availableMinor":171000,"observedAt":"2026-11-02T01:00:00Z"}"""))) {model,state->
            assertEquals(2,state.limits.members.size);assertEquals(2,state.limits.recoveries.size);assertEquals(1,state.limits.recoveries.count{it.voidedAt!=null});assertEquals(171000L,state.limits.observations.single().availableMinor)
            assertTrue(model.steps.single{it.stepId=="observe"}.requirements.any{it.kind==ConfirmationKind.USER_FACT})
        }
    }
    @Test fun poolPaymentBatchConversionAndAuditedRestoration()=runBlocking<Unit> {
        val (b,bank)=setup();val a=card(b,"循环卡",10000);val category=r.saveCategory(b,"购物",EntryKind.EXPENSE,null)
        // Existing single source/manual bill is deliberately outside the command's ownership.
        val event=r.saveEntry(b,EntryDraft(accountId=a,categoryId=category,amount="2000",date="2026-11-01"))
        val single=checkNotNull(r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(a,"一次付",FinanceMode.SINGLE,listOf(KnownBillInput("2026-12-10",2000,2000,0,bank)),2000,true,sourceEventId=event))))
        val prefix=commandFinanceSteps(bank,card(b,"分期卡",12000),a).filter{it.id in setOf("poolRule","pool")}
        val steps=prefix+listOf(
            step("payment","financialPool.changePayment","""{"input":{"planId":${ref("pool","prediction")},"effectiveDate":"2026-11-02","setting":{"fromPaymentDate":"2026-12-10","method":"FIXED_PRINCIPAL","paymentMinor":6000},"feeMinor":0},"appliedAt":"2026-11-02T00:00:00Z"}"""),
            step("convert","financialBatchRevolving.apply","""{"input":{"accountId":"$a","paymentDate":"2026-12-10","planIds":["$single"],"principalMinor":2000,"poolId":${ref("pool","prediction")},"effectiveDate":"2026-11-02","feeMinor":0},"appliedAt":"2026-11-02T00:00:00Z"}"""),
            step("restore","financialBatchRevolving.undo","""{"undoGroupId":${ref("convert","batch")}}"""))
        cycle(b,steps) {model,state->
            assertTrue(state.finance.arrangements.isEmpty());assertTrue(state.finance.billAdjustments.isEmpty())
            assertTrue(state.finance.plans.single{it.id==single}.active);assertTrue(state.finance.plans.any{it.origin=="CANCELLED_ARRANGEMENT"})
            assertEquals(3,model.steps.count{it.stepId in setOf("payment","convert","restore")})
        }
        // Later form input is a real external reference missed by the former plan/bill-only guard.
        val original=CommandUndoJournal(r,b).snapshot()
        val applied=receive(b,steps.dropLast(1));val model=commands.preview(applied.planId)
        val done=commands.confirm(applied.planId,commandChecks(model))
        val execution=readCommandExecution(requireNotNull(done.executionJson))
        val group=execution.outputs.getValue("convert").getValue("batch")
        val target=db.financialDao().graph(b).first()!!.arrangements.single().targetPlanId
        val draft=FinancialDraftContext("ARRANGEMENT_REVIEW",a,targetId=target)
        r.storeFinancialDraft(draft,b,"{}","existing-form")
        val withDraft=CommandUndoJournal(r,b).snapshot()
        val blocked=receive(b,listOf(step("budget","budget.save","""{"month":"2026-11","amount":{"currency":"JPY","minor":12345}}"""),step("restore","financialBatchRevolving.undo","""{"undoGroupId":"$group"}""")))
        try{commands.preview(blocked.planId);fail("external reconciliation draft must block the whole batch")}catch(_:IllegalArgumentException){}
        assertEquals(withDraft,CommandUndoJournal(r,b).snapshot())
        val inverse=commands.prepareUndo(applied.planId);assertFalse(commands.preview(inverse.planId).executable)
        r.discardFinancialDraft(b,draft.slot())
        // Reuse the local inverse after the external reference is deliberately removed.
        val retry=commands.preview(inverse.planId);assertTrue(retry.executable);commands.confirm(inverse.planId,commandChecks(retry))
        assertEquals(original,CommandUndoJournal(r,b).snapshot())
    }
    @Test fun financialDraftCategoryMigrationPreservesAllOtherFormValues()=runBlocking<Unit> {
        val (b,bank)=setup();val old=r.saveCategory(b,"旧收入",EntryKind.INCOME,null);val next=r.saveCategory(b,"新收入",EntryKind.INCOME,null)
        val context=FinancialDraftContext("EXPECTED",bank)
        r.storeFinancialDraft(context,b,"""{"category":"$old","name":"奖金","amount":"1234","note":"继续填写"}""","original-basis")
        cycle(b,listOf(step("draft","financialDraft.reclassify","""{"sourceCategoryId":"$old","categoryId":"$next"}"""))) {model,_->
            val draft=requireNotNull(db.financialDao().draft(b,context.slot()));assertEquals("original-basis",draft.basis)
            assertEquals(JsonPrimitive(next),commandJson.parseToJsonElement(draft.valuesJson).jsonObject["category"]);assertTrue(draft.valuesJson.contains("继续填写"));assertTrue(model.net.any{it.type=="financialDraft"})
        }
    }
}
