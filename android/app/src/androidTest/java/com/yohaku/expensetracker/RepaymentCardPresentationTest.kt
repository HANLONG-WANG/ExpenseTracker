package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.theme.YohakuTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class RepaymentCardPresentationTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private val today=LocalDate.parse("2026-10-15")
    @Before fun open(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
        vm=AppViewModel(repo,AppPreferences(context),testReferenceRates(AppPreferences(context),repo.clock))
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun shot(name:String){
        if(!visualEnabled())return
        ui.waitForIdle()
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        val folder=File(i.targetContext.getExternalFilesDir(null),"repayment-cards$suffix").also{it.mkdirs()}
        val bitmap=i.uiAutomation.takeScreenshot()
        File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

    @Test fun cardsShowActualPaymentsWhileDebtWaitsForClearing(){
        val ids=runBlocking{
            val book=repo.createBook("付款进度",Currency.JPY,"magazine","zh",startDate="2026-10-01")
            val bank=repo.saveAccount(book,"付款银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val card=repo.saveAccount(book,"信用卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            val loan=repo.saveAccount(book,"借款",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            listOf(book,bank,card,loan)
        }
        val(book,bank,card,loan)=ids
        fun snapshot()=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
        fun graph()=runBlocking{db.financialDao().graph(book).first()!!}
        val s=mutableStateOf(snapshot());val f=mutableStateOf(graph())
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val scene=mutableStateOf("credit")
        fun refresh(){val next=snapshot();val finance=graph();ui.runOnIdle{s.value=next;f.value=finance}}
        ui.setContent{YohakuTheme(theme.value,language.value){
            val account=s.value.accounts.first{it.id==(if(scene.value=="loan")loan else card)}
            if(scene.value=="detail")AccountDetail(s.value,account,{},{},today=today)
            else key(scene.value){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(pageContentPadding()).verticalScroll(rememberScrollState())){
                when(scene.value){
                    "accounts"->AccountsScreen(s.value,{},{},today=today)
                    "single"->{
                        AccountCard(s.value,s.value.accounts.first{it.id==card},0,{},today)
                        AccountCard(s.value,s.value.accounts.first{it.id==loan},1,{},today)
                    }
                    else->FinancialAccountLead(s.value,account,f.value,vm)
                }
            }}
        }}
        for((t,l) in testLooks(this)){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithTag("lead-amount").assertTextEquals(if(l=="ja")"既知の支払日はありません"else"尚无已知待付日期")
            ui.onNodeWithTag("lead-amount").assertBalancedHeading()
            shot("$t-$l-empty-credit")
        }
        val bill=runBlocking{
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"本期账单",FinanceMode.SINGLE,
                listOf(KnownBillInput("2026-10-20",1000,1000,0,bank)),1000,opening=true)))!!
            db.financialDao().bills(book,plan).single().id
        }
        val payment=FinancialRepaymentInput(bank,card,Money(Currency.JPY,300),today.toString(),received=Money(Currency.JPY,300),principal=Money(Currency.JPY,300),
            interest=Money(Currency.JPY,0),fee=Money(Currency.JPY,0),allocations=listOf(RepaymentAllocationInput(billId=bill,principalMinor=300,interestMinor=0,feeMinor=0)))
        val event=runBlocking{
            // An unallocated loan payment must also be visible on its account card.
            repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,loan,Money(Currency.JPY,200),today.toString())))
            repo.commitFinancialLedger(repo.previewRepayment(book,payment))!!
        }
        refresh()
        for((t,l) in testLooks(this))for(view in listOf("accounts","single","detail","credit","loan")){
            ui.runOnIdle{theme.value=t;language.value=l;scene.value=view}
            val label=if(l=="ja")"支払済み・確認待ち："else"已付待确认："
            if(view in listOf("accounts","single")){
                ui.onNodeWithTag("account-$card").assert(hasText(label+"¥300",substring=true))
                ui.onNodeWithTag("account-$loan").assert(hasText(label+"¥200",substring=true))
                ui.onNodeWithTag("account-$card").scrollIfScrollable()
            }else{
                ui.onNodeWithTag("lead-supporting").assertTextContains(label+(if(view=="loan")"¥200"else"¥300"),substring=true)
                ui.onNodeWithTag("lead-amount").assertTextContains(if(view=="credit")"¥700"else"¥1,000",substring=true)
            }
            shot("$t-$l-$view-pending")
        }
        val full=payment.copy(eventId=event,paid=Money(Currency.JPY,1000),received=Money(Currency.JPY,1000),principal=Money(Currency.JPY,1000),allocations=listOf(RepaymentAllocationInput(billId=bill,principalMinor=1000,interestMinor=0,feeMinor=0)))
        runBlocking{repo.commitFinancialLedger(repo.previewRepayment(book,full))}
        refresh();ui.runOnIdle{scene.value="credit"}
        for((t,l) in testLooks(this)){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithTag("lead-amount").assertTextEquals(if(l=="ja")"支払済み・返済先の確認待ち"else"已付款，待平台确认")
            ui.onNodeWithTag("lead-supporting").assertTextContains("¥1,000",substring=true)
            shot("$t-$l-credit-fully-paid-pending")
        }
        runBlocking{repo.commitFinancialLedger(repo.previewRepayment(book,full.copy(platformConfirmed=true,received=Money(Currency.JPY,1000),clearingDate=today.toString(),sourceFee=Money(Currency.JPY,0))))}
        refresh()
        assertEquals(0L,snapshot().balance(snapshot().accounts.first{it.id==card}))
        for((t,l) in testLooks(this)){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithTag("lead-supporting").assertDoesNotExist()
            ui.onNodeWithTag("lead-amount").assertTextEquals(if(l=="ja")"既知の請求に資金準備は不要"else"已知账单无需备款")
            shot("$t-$l-credit-cleared")
        }
    }
}
