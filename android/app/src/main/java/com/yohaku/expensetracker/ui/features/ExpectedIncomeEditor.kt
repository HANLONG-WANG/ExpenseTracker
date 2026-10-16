package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import kotlinx.serialization.encodeToString

@Composable internal fun ExpectedIncomeEditor(s:BookSnapshot,old:ExpectedIncomeEntity?,arriving:Boolean,vm:AppViewModel,onClose:()->Unit,initialAccountId:String?=null) {
    FinancialDraftHost(s,FinancialDraftContext("EXPECTED",null,targetId=old?.id,mode=if(arriving)"ARRIVAL"else"PLAN"),vm,onClose){ExpectedIncomeEditorContent(s,old,arriving,vm,onClose,initialAccountId)}
}

@Composable private fun ExpectedIncomeEditorContent(s:BookSnapshot,old:ExpectedIncomeEntity?,arriving:Boolean,vm:AppViewModel,onClose:()->Unit,initialAccountId:String?=null) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    val today=LocalDate.now(vm.repository.clock).toString()
    var name by financialField("name"){old?.name.orEmpty()}
    var accountId by financialField("accountId"){old?.accountId?:initialAccountId?:s.accounts.firstOrNull{it.active&&!AccountType.valueOf(it.type).debt}?.id}
    var currencyCode by financialField("currencyCode"){old?.currency?:s.accounts.firstOrNull{it.id==initialAccountId}?.currency?:s.book.currency}
    var amount by financialField("amount"){old?.let{Money(Currency.valueOf(it.currency),it.amountMinor).decimal.toPlainString()}.orEmpty()}
    var accountAmount by financialField("accountAmount"){old?.accountMinor?.let{n->Money(Currency.valueOf(s.accounts.first{it.id==old.accountId}.currency),n).decimal.toPlainString()}.orEmpty()}
    var estimateJson by financialField("estimateJson"){old?.accountEstimateJson}
    var date by financialField("date"){old?.date?:today}
    var actualAmount by financialField("actualAmount"){old?.accountMinor?.let{n->Money(Currency.valueOf(s.accounts.first{it.id==old.accountId}.currency),n).decimal.toPlainString()}.orEmpty()}
    var actualDate by financialField("actualDate"){today}
    var baseAmount by financialField("baseAmount"){""}
    var category by financialField<String?>("category"){null}
    var existingId by financialField<String?>("existingId"){null}
    var existingMode by financialField("existingMode"){false}
    var owner by remember{mutableStateOf<Int?>(null)}
    var review by remember{mutableStateOf<ExpectedIncomeReview?>(null)}
    var receipt by remember{mutableStateOf<ExpectedIncomeArrivalReview?>(null)}
    var refreshed by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val formScroll=rememberScrollState();val previewScroll=rememberScrollState()
    val account=s.accounts.firstOrNull{it.id==accountId}
    val preview=review!=null||receipt!=null
    LaunchedEffect(preview){if(preview)previewScroll.scrollTo(0)}
    YSheet(if(preview)word("确认收入安排","収入の確認")else if(arriving)word("确认实际到账","入金の確認")else word("手动预计收入","収入予定"),{if(preview){review=null;receipt=null}else onClose()},scrollState=if(preview)previewScroll else formScroll) {
        if(refreshed)FinanceRefreshed()
        if(preview) {
            val planned=review?.income?:receipt!!.income
            YText(planned.name,18)
            RecordFacts(listOf(word("预计金额","予定金額") to Money(Currency.valueOf(planned.currency),planned.amountMinor).display(),word("预计到账日","入金予定日") to planned.date,word("到账账户","入金口座") to s.accounts.first{it.id==planned.accountId}.name))
            receipt?.let{r->
                val actual=r.entry?.change?.settlement?.accountMinor?:s.settlements.first{it.eventId==r.event.id}.accountMinor
                RecordFacts(listOf(word("实际到账金额","実入金額") to Money(Currency.valueOf(account!!.currency),actual).display(),word("实际到账日","実入金日") to (r.entry?.change?.settlement?.settlementDate?:s.settlements.first{it.eventId==r.event.id}.settlementDate)))
                if(r.entry==null)YText(word("关联已有收入，不再次增加余额。","既存の収入に関連付け、残高を重複加算しません。"),12)
                r.entry?.impacts?.forEach{EntryImpactPreview(r.before,r.event,it,today.take(7))}
            }?:YText(word("仅保存预计项。未到账不更新真实余额、已到账收入或预算。","収入予定のみ保存。未入金は実残高・入金済み収入・予算に加えません。"),12)
            YButton(word("确认保存","確定して保存"),{val r=review;val a=receipt;var saved=false
                vm.execute(onSuccess={if(saved)onClose()else{review=null;receipt=null;refreshed=true}}){saved=commitFinancialDraft(draftSession){if(r!=null)vm.repository.commitExpectedIncome(r)else vm.repository.commitExpectedIncomeArrival(a!!)}!=null}
            },Modifier.testTag("expected-confirm"),primary=true,enabled=!busy)
            YButton(word("返回修改","修正に戻る"),{review=null;receipt=null},Modifier.testTag("expected-back"),enabled=!busy)
        }else if(!arriving) {
            FinancialDraftNotice()
            YField(word("预计收入名称","収入予定名"),name,{name=it})
            YSelect(word("到账账户","入金口座"),accountId,listOf(null to word("请选择","選択してください"))+s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}.map{it.id as String? to (it.name+" · "+it.currency)},{accountId=it;accountAmount="";estimateJson=null})
            YSelect(word("预计币种","予定の通貨"),currencyCode,Currency.entries.map{it.name to it.name},{currencyCode=it;accountAmount="";estimateJson=null})
            YField(word("预计金额","予定金額")+" · "+currencyCode,amount,{amount=it;if(estimateJson!=null)accountAmount="";estimateJson=null},numeric=true)
            if(account!=null&&account.currency!=currencyCode) {
                YField(word("预计到账账户金额 / 未知留空","口座通貨での予定額・不明は空欄")+" · "+account.currency,accountAmount,{accountAmount=it;estimateJson=null},numeric=true)
                StoredReferenceRates(estimateJson)
                fun apply(q:ReferenceRateSnapshot){accountAmount=q.convert(Money.parse(amount,Currency.valueOf(currencyCode))).decimal.toPlainString();estimateJson=vm.repository.json.encodeToString(listOf(q))}
                ReferenceRatePanel(vm.referenceRates,Currency.valueOf(currencyCode),Currency.valueOf(account.currency),today,autoKey=amount+"/"+accountAmount,
                    onAuto={q->if(old==null&&accountAmount.isBlank()&&runCatching{Money.parse(amount,q.base).minor>0}.getOrDefault(false))apply(q)},onUse={apply(it)})
            }
            YDateFields(listOf(word("预计到账日","入金予定日") to date),owner,{owner=it},{_,v->date=v})
            YText(word("只参与收入情景，未到账不更新真实余额，也不增加预算。","収入シナリオのみ。未入金は実残高・予算に加えません。"),11)
            YButton(word("预览预计收入","収入予定を確認"),{vm.execute{review=vm.repository.previewExpectedIncome(s.book.id,ExpectedIncomeInput(name,Money.parse(amount,Currency.valueOf(currencyCode)),date,accountId?:error("请选择账户 / 口座を選択してください"),account?.takeIf{it.currency!=currencyCode&&accountAmount.isNotBlank()}?.let{Money.parse(accountAmount,Currency.valueOf(it.currency))},old?.id,estimateJson?.let{vm.repository.json.decodeFromString<List<ReferenceRateSnapshot>>(it).single()}))}},Modifier.testTag("expected-preview"),primary=true,enabled=!busy)
        }else {
            FinancialDraftNotice()
            YText(old!!.name,18)
            YToggle(word("关联已有收入","既存の収入を関連付け"),existingMode,{existingMode=it},Modifier.testTag("expected-link-existing"))
            if(existingMode) {
                val candidates=s.visibleEvents.filter{it.kind==EntryKind.INCOME.name&&it.accountId==old.accountId&&!it.historicalIncluded&&it.date<=today&&s.settlements.any{p->p.eventId==it.id&&p.confirmed&&p.settlementDate<=today}&&s.expectedIncomes.none{p->p.id!=old.id&&p.eventId==it.id}}
                YSelect(word("已有收入记录","既存の収入記録"),existingId,listOf(null to word("请选择","選択してください"))+candidates.map{it.id as String? to (it.date+" · "+s.accountMoney(it).display()+" · "+it.note)},{existingId=it})
            }else {
                YText(account!!.name+" · "+account.currency,14)
                YField(word("实际到账金额","実入金額")+" · "+account.currency,actualAmount,{actualAmount=it},numeric=true)
                if(account.currency!=s.book.currency)YField(word("实际本位币金额","実際の基準通貨額")+" · "+s.book.currency,baseAmount,{baseAmount=it},numeric=true)
                YDateFields(listOf(word("实际到账日","実入金日") to actualDate),owner,{owner=it},{_,v->actualDate=v})
                YSelect(word("收入分类","収入カテゴリ"),category,listOf(null to word("请选择","選択してください"))+s.categories.filter{s.categoryEnabled(it.id)&&it.kind==EntryKind.INCOME.name}.map{it.id as String? to s.path(it.id)},{category=it})
                YLink(word("管理收入分类","収入カテゴリを管理"),{onClose();vm.navigate("categories")})
            }
            YButton(word("预览到账影响","入金の影響を確認"),{vm.execute{receipt=vm.repository.previewExpectedIncomeArrival(s.book.id,old.id,if(existingMode)null else EntryDraft(kind=EntryKind.INCOME,accountId=old.accountId,categoryId=category,amount=actualAmount,baseAmount=baseAmount,date=actualDate,settlementDate=actualDate,rateDate=actualDate,note=old.name),if(existingMode)existingId?:error("请选择已有收入 / 既存の収入を選択してください")else null)}},Modifier.testTag("expected-arrival-preview"),primary=true,enabled=!busy)
        }
    }
}

@Composable internal fun ExpectedIncomeSection(s:BookSnapshot,vm:AppViewModel,onRecord:(EventEntity)->Unit) {
    var editor by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var resumed by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var arriving by rememberSaveable(s.book.id){mutableStateOf(false)}
    var cancellation by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var showHistory by rememberSaveable(s.book.id){mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    ThemeSection(word("手动预计收入","手入力の収入予定"),"EXPECTED, NOT RECEIVED")
    FinancialDraftList(s,null,vm,{resumed=vm.repository.json.encodeToString(it)})
    s.expectedIncomes.filter{showHistory||it.status=="PENDING"||s.expectedIncomeNeedsReview(it)}.sortedBy{it.date}.forEach{income->Panel(Modifier.testTag("expected-item-${income.id}")) {
        YText(income.name,16)
        RecordFacts(listOf(word("预计金额 / 日期","予定額・日付") to (Money(Currency.valueOf(income.currency),income.amountMinor).display()+" · "+income.date),word("到账账户","入金口座") to s.accounts.first{it.id==income.accountId}.name))
        val actual=s.expectedIncomeArrival(income)
        YText(when{actual!=null->word("已关联实际收入","実収入に関連付け済み");s.expectedIncomeNeedsReview(income)->word("实际收入已删除或改变，请核对关联","実収入が削除・変更されています。関連を確認してください");income.status=="CANCELLED"->word("已取消","取消済み");else->word("未到账 · 仅参与收入情景","未入金・収入シナリオのみ")},12,color=LocalYohaku.current.warm)
        if(actual!=null)YButton(word("查看实际收入","実収入を見る"),{onRecord(actual)},enabled=!busy)
        else if(income.status!="CANCELLED") {
            YButton(word("确认到账","入金を確認"),{editor=income.id;arriving=true},Modifier.testTag("expected-arrive-${income.id}"),enabled=!busy)
            YButton(word("修改预计项","予定を編集"),{editor=income.id;arriving=false},Modifier.testTag("expected-edit-${income.id}"),enabled=!busy)
            if(income.status=="PENDING")YButton(word("取消预计收入","収入予定を取消"),{cancellation=income.id},Modifier.testTag("expected-cancel-${income.id}"),enabled=!busy)
        }
    }}
    YButton(word("添加手动预计收入","収入見込みを手入力"),{editor="";arriving=false},Modifier.testTag("expected-add"),primary=true,enabled=!busy)
    YButton(word("已到账 / 已取消记录","入金済み・取消済み")+if(showHistory)" −"else" +",{showHistory=!showHistory})
    editor?.let{id->ExpectedIncomeEditor(s,s.expectedIncomes.firstOrNull{it.id==id},arriving,vm,{editor=null})}
    resumed?.let{encoded->
        val context=vm.repository.json.decodeFromString<FinancialDraftContext>(encoded)
        val old=s.expectedIncomes.firstOrNull{it.id==context.targetId}
        if(context.targetId==null||old?.status=="PENDING")ExpectedIncomeEditor(s,old,context.mode=="ARRIVAL",vm,{resumed=null})
        else YSheet(word("原资料待核对","元の情報を要確認"),{resumed=null}) {
            YText(word("草稿仍保留，原预计收入已取消或到账，请先核对原记录。","下書きは保持しています。元の収入予定は取消・入金済みです。元の記録を確認してください。"),12)
            YButton(word("放弃这份草稿","この下書きを破棄"),{vm.execute(onSuccess={resumed=null}){discardFinancialDraft(vm,s.book.id,context)}})
        }
    }
    cancellation?.let{id->YSheet(word("取消预计收入","収入予定を取消"),{cancellation=null}) {
        YText(s.expectedIncomes.first{it.id==id}.name,16)
        YText(word("从收入情景移除此安排，实际资金和收入记录不变。","収入シナリオから外します。実資金・収入記録は変わりません。"),12)
        YButton(word("确认取消","取消を確定"),{vm.execute(onSuccess={cancellation=null}){vm.repository.cancelExpectedIncome(s.book.id,id)}},Modifier.testTag("expected-cancel-confirm"),primary=true,enabled=!busy)
    }}
}
