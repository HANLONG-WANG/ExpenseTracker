package com.yohaku.expensetracker.ui.features

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.ledger.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay

@Composable fun NativeApp(vm:AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val entryReturnRoute by vm.entryReturnRoute.collectAsStateWithLifecycle()
    val draft by vm.entry.collectAsStateWithLifecycle()
    val financialDraft by vm.activeFinancialDraft.collectAsStateWithLifecycle()
    val entryReview by vm.entryReview.collectAsStateWithLifecycle()
    val occurrenceLinkReview by vm.occurrenceLinkReview.collectAsStateWithLifecycle()
    val occurrenceLinkRefreshed by vm.occurrenceLinkRefreshed.collectAsStateWithLifecycle()
    val reviewRefreshed by vm.reviewRefreshed.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME){vm.refreshToday()}
    val storedFirstSetup by vm.setupDraft.collectAsStateWithLifecycle()
    val openingBook by vm.selectedBookMetadata.collectAsStateWithLifecycle()
    val ready=state as? AppState.Ready;val snapshot=ready?.snapshot
    val firstSetup=ready?.firstSetup ?: storedFirstSetup
    var sheet by rememberSaveable{mutableStateOf<String?>(null)}
    var requestedEntryKind by rememberSaveable{mutableStateOf(EntryKind.EXPENSE.name)}
    var requestedActivity by rememberSaveable{mutableStateOf<String?>(null)}
    var requestedTreatFriend by rememberSaveable{mutableStateOf<String?>(null)}
    var requestedMerchant by rememberSaveable{mutableStateOf<String?>(null)}
    var requestedOccurrence by rememberSaveable{mutableStateOf<String?>(null)}
    var requestedTransferAccount by rememberSaveable{mutableStateOf<String?>(null)}
    var detailReturnRoute by rememberSaveable{mutableStateOf("home")}
    var pendingRecordAction by rememberSaveable{mutableStateOf("edit")}
    var selectedEventId by rememberSaveable{mutableStateOf<String?>(null)}
    val selectedEvent=snapshot?.events?.find{it.id==selectedEventId}
    var selectedAccountId by rememberSaveable{mutableStateOf<String?>(null)}
    var requestedDestination by rememberSaveable{mutableStateOf<String?>(null)}
    var recurringInitialMonth by rememberSaveable{mutableStateOf<String?>(null)}
    var financialInitialMonth by rememberSaveable{mutableStateOf<String?>(null)}
    var riskAccountId by rememberSaveable{mutableStateOf<String?>(null)}
    var incomeTarget by rememberSaveable{mutableStateOf<String?>(null)}
    var incomeAccount by rememberSaveable{mutableStateOf<String?>(null)}
    var incomeArriving by rememberSaveable{mutableStateOf(false)}
    var financialInitialTab by rememberSaveable{mutableStateOf("overview")}
    var editAccount by remember{mutableStateOf<AccountEntity?>(null)}
    var editBookId by rememberSaveable{mutableStateOf<String?>(null)}
    var leaveTarget by rememberSaveable{mutableStateOf("home")}
    var categoryParent by remember{mutableStateOf<String?>(null)}
    var categoryKind by remember{mutableStateOf(EntryKind.EXPENSE)}
    var editCategory by remember{mutableStateOf<CategoryEntity?>(null)}
    var switchTarget by remember{mutableStateOf<String?>(null)}
    val book=snapshot?.book
    YohakuTheme(book?.theme?:openingBook?.theme?:firstSetup?.theme?:"magazine",book?.language?:openingBook?.language?:firstSetup?.language?:"zh") {
        val t=LocalYohaku.current
        val accessibility=LocalAccessibilityManager.current
        LaunchedEffect(notice?.id){notice?.let{
            delay(accessibility?.calculateRecommendedTimeoutMillis(3000L,containsIcons=true,containsText=true,containsControls=false) ?: 3000L)
            vm.dismissNotice(it.id)
        }}
        Box(Modifier.fillMaxSize()){
        if(state is AppState.Failure) ReadFailureScreen((state as AppState.Failure).message,vm::retryRead)
        else if(state==AppState.Loading || book==null&&firstSetup==null) YStartup()
        else if(book?.initialized!=true) Setup(vm,snapshot,firstSetup ?: SetupDraft())
        else {
            val openEntry=draft!=null && route in listOf("entry","category-picker")
            val returnPage=if(openEntry)entryReturnRoute else route
            val baseRoute=if(returnPage=="detail")detailReturnRoute else returnPage
            fun openRecord(event:EventEntity){selectedEventId=event.id;detailReturnRoute=baseRoute;vm.navigate("detail")}
            fun startRequestedEntry(current:BookSnapshot) {
                val kind=EntryKind.valueOf(requestedEntryKind)
                if(kind==EntryKind.TRANSFER)vm.startTransfer(current,requestedTransferAccount)
                else vm.startEntry(current,kind,requestedActivity,requestedTreatFriend,requestedMerchant)
                if(requestedTransferAccount!=null||requestedDestination!=null)vm.entry.value?.let{d->
                    vm.changeEntry(book.id,d.copy(accountId=requestedTransferAccount?:d.accountId,toAccountId=requestedDestination?:d.toAccountId))
                }
            }
            fun requestEntry(kind:EntryKind=EntryKind.EXPENSE,fromAccountId:String?=null,activityId:String?=null,treatFriendId:String?=null,merchantId:String?=null,toAccountId:String?=null){
                requestedMerchant=merchantId;requestedTreatFriend=treatFriendId;requestedActivity=activityId;requestedEntryKind=kind.name;requestedTransferAccount=fromAccountId;requestedDestination=toAccountId;requestedOccurrence=null
                if(snapshot.draft!=null)sheet="existing-draft"else startRequestedEntry(snapshot)
            }
            fun openFinancial(id:String,month:String?=null) {
                selectedAccountId=id;financialInitialTab=if(month==null)"overview"else"statement";financialInitialMonth=month;sheet=null;vm.navigate("rules")
            }
            fun openTimelineItem(item:CashTimelineItem) {
                sheet=null
                when(item.kind) {
                    "BILL"->openFinancial(item.sourceId,item.scheduledDate.take(7))
                    "RECURRENCE"->{recurringInitialMonth=item.scheduledDate.take(7);vm.navigate("recurring")}
                    "RESERVE"->{vm.openActivity.value=item.sourceId;vm.navigate("life")}
                    "ACTUAL","TRANSFER","RECORD"->snapshot.visibleEvents.firstOrNull{it.id==item.sourceId}?.let{openRecord(it)}
                    "OPENING"->{selectedAccountId=item.sourceId;vm.navigate("account-detail")}
                    "EXPECTED_INCOME"->{incomeTarget=item.sourceId;sheet="income-actions"}
                }
            }
            fun openAttention(item:com.yohaku.expensetracker.data.repository.FinancialAttention) {
                if(item.kind=="ACCOUNT")openFinancial(item.sourceId)
                else openTimelineItem(com.yohaku.expensetracker.data.repository.CashTimelineItem(item.id,item.kind,item.sourceId,today.toString(),"",item.accountId,book.currency,null,false,null))
            }
            fun openRisk(id:String){riskAccountId=id;sheet="cash-risk"}
            fun requestOccurrence(occurrence:OccurrenceEntity) {
                requestedMerchant=null;requestedTreatFriend=null;requestedActivity=null;requestedEntryKind=EntryKind.EXPENSE.name;requestedTransferAccount=null;requestedDestination=null;requestedOccurrence=occurrence.id
                if(snapshot.draft!=null)sheet="existing-draft"else vm.startOccurrence(snapshot,occurrence)
            }
            LaunchedEffect(book.id,YearMonth.from(today).toString(),snapshot.recurrences){vm.materializeMonth(book.id,YearMonth.from(today).toString())}
            BackHandler(enabled=occurrenceLinkReview!=null || entryReview!=null || sheet!=null || route !in listOf("home","analysis","life","accounts")) {
                if(occurrenceLinkReview!=null)vm.occurrenceLinkReview.value=null else if(entryReview!=null)vm.dismissEntryReview() else if(sheet!=null)sheet=null else if(openEntry){leaveTarget=entryReturnRoute;sheet="leave-entry"} else vm.back()
            }
            LaunchedEffect(baseRoute){if(baseRoute!="recurring")recurringInitialMonth=null}
            val contentScroll=rememberSaveable(baseRoute,book.id,saver=ScrollState.Saver){ScrollState(0)}
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.weight(1f).verticalScroll(contentScroll).testTag("page-scroll")) {
                YAppHeader(book.name,{if(openEntry||financialDraft?.hasInput==true)sheet="books" else vm.navigate("books")},{vm.appearance(book.id,language=if(book.language=="zh")"ja" else "zh")},{if(openEntry){leaveTarget="settings";sheet="leave-entry"}else vm.navigate("settings")})
                Column(Modifier.padding(pageContentPadding()).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                    PageEdition(if(baseRoute=="rules")if(snapshot.accounts.firstOrNull{it.id==selectedAccountId}?.type==AccountType.LOAN.name)"loan"else"credit"else baseRoute)
                    if(baseRoute !in listOf("home","analysis","life","accounts")) YBackBar(book.name,{if(openEntry){leaveTarget="home";sheet="leave-entry"}else vm.back()})
                    when(baseRoute) {
                        "home"->HomeScreen(snapshot,{vm.navigate(it)},{requestEntry()},{vm.resumeEntry(snapshot)},{openRecord(it)},{vm.navigate("budget")},today=today,onRecurring={recurringInitialMonth=null;vm.navigate("recurring")},financialContent={HomeFinancialOutlook(snapshot,vm,{vm.navigate("calendar")},{vm.navigate("forecast")},{openRisk(it)},{openTimelineItem(it)},{openAttention(it)},{vm.navigate("reminders")})})
                        "books"->BooksScreen(ready.overviews,book.id,{vm.selectBook(it)},{sheet="new-book"},{editBookId=it;sheet="rename-book"})
                        "accounts"->AccountsScreen(snapshot,{editAccount=null;sheet="account"},{selectedAccountId=it.id;vm.navigate("account-detail")},vm.referenceRates,financialContent={AccountsFinancialOutlook(snapshot,vm,{requestEntry(EntryKind.TRANSFER)},{vm.navigate("calendar")},{vm.navigate("forecast")},{openRisk(it)},{openAttention(it)},{vm.navigate("reminders")})},today=today)
                        "account-detail"->snapshot.accounts.find{it.id==selectedAccountId}?.let{a->AccountDetail(snapshot,a,{editAccount=a;sheet="account"},{openRecord(it)},{requestEntry(EntryKind.TRANSFER,a.id)},{openFinancial(a.id)},today,{openRisk(a.id)})}
                        "calendar"->PaymentCalendarScreen(snapshot,vm,{openTimelineItem(it)},{vm.navigate("forecast")})
                        "reminders"->ImportantItemsScreen(snapshot,vm,{openTimelineItem(it)},{account,month->openFinancial(account,month)},{openRisk(it)},{openAttention(it)},{vm.navigate("forecast")})
                        "forecast"->FinancialForecastScreen(snapshot,vm,{openRecord(it)},{openFinancial(it)},{openRisk(it)},{openAttention(it)},{openTimelineItem(it)})
                        "rules"->snapshot.accounts.find{it.id==selectedAccountId}?.let{FinancialAccountScreen(snapshot,it,vm,financialInitialTab,{openRecord(it)},financialInitialMonth,{from,to->if(to==null)requestEntry(EntryKind.INCOME,from)else requestEntry(EntryKind.TRANSFER,from,toAccountId=to)})}
                        "ledger"->LedgerScreen(snapshot,{openRecord(it)},{requestEntry(it)})
                        "categories"->CategoryManagement(snapshot,{parent,kind->categoryParent=parent;categoryKind=kind;editCategory=null;sheet="category"},{c->editCategory=c;categoryParent=c.parentId;categoryKind=EntryKind.valueOf(c.kind);sheet="category"},{c->vm.execute{vm.repository.toggleCategoryFavorite(book.id,c.id)}})
                        "settings"->SettingsScreen(snapshot,{vm.appearance(book.id,theme=it)},{vm.appearance(book.id,language=if(book.language=="zh")"ja" else "zh")},{vm.navigate("categories")},{vm.navigate("books")},{vm.navigate("trash")},{editBookId=book.id;sheet="rename-book"},{vm.navigate("reference-rates")})
                        "reference-rates"->ReferenceRatesScreen(vm)
                        "trash"->TrashScreen(snapshot,vm)
                        "budget"->BudgetScreen(snapshot,vm,{vm.navigate("recurring")},{vm.navigate("categories")})
                        "recurring"->RecurringScreen(snapshot,vm,{requestOccurrence(it)},{openRecord(it)},{vm.navigate("accounts")},{vm.navigate("categories")},recurringInitialMonth)
                        "analysis"->AnalysisScreen(snapshot,vm,{openRecord(it)},{vm.navigate("categories")},{id->selectedAccountId=id;financialInitialTab="plans";financialInitialMonth=null;vm.navigate("rules")})
                        "receipts"->ReceiptsScreen(snapshot,vm,{openRecord(it)})
                        "merchants"->MerchantsScreen(snapshot,vm,{openRecord(it)},{requestEntry(merchantId=it)})
                        "friends"->FriendsScreen(snapshot,vm)
                        "life"->ActivityScreen(snapshot,vm,{id,friend->requestEntry(activityId=id,treatFriendId=friend)},{openRecord(it)})
                    }
                    PageFootnote(baseRoute)
                }
                }
                YBottomNav(baseRoute,{p->if(openEntry){leaveTarget=p;sheet="leave-entry"}else vm.navigate(p)},{if(!openEntry)requestEntry()})
            }
            if(route=="detail"&&sheet==null&&entryReview==null)selectedEvent?.let{event->
                YSheet(word("这一笔的详情","この一筆の詳細"),{vm.back()}){Detail(snapshot,event,{if(event.kind in listOf(EntryKind.BORROWING.name,EntryKind.REPAYMENT.name))sheet="finance-edit" else if(snapshot.draft!=null){pendingRecordAction="edit";sheet="record-existing-draft"}else vm.editEntry(snapshot,event)},{sheet="delete"},vm,
                    onRefund={if(snapshot.draft!=null)sheet="refund-existing-draft"else vm.startRefund(snapshot,event)},
                    onConfirm={if(snapshot.draft!=null){pendingRecordAction="confirm";sheet="record-existing-draft"}else vm.confirmActual(snapshot,event)},onRecord={selectedEventId=it.id})}
            }
            if(openEntry&&sheet==null&&entryReview==null&&occurrenceLinkReview==null) {
                val activeDraft=draft!!
                val fromCurrency=snapshot.accounts.find{it.id==activeDraft.accountId}?.currency
                val toCurrency=snapshot.accounts.find{it.id==activeDraft.toAccountId}?.currency
                val transferTitle=if(fromCurrency!=null&&toCurrency!=null&&fromCurrency==toCurrency)word("账户转账","口座振替")else word("换汇与转账","両替と振替")
                YSheet(if(activeDraft.kind==EntryKind.TRANSFER)transferTitle else if(activeDraft.kind==EntryKind.REFUND)word("退款 · 原币与实际到账","返金・原通貨と実入金") else if(route=="category-picker")word("先选一个分类","まずカテゴリを選ぶ") else if(draft?.id!=null)word("编辑这笔记录","この記録を編集") else if(activeDraft.occurrenceId!=null)word("确认周期账单","定期支払を確認") else word("记一笔","一筆を記録"),{leaveTarget=entryReturnRoute;sheet="leave-entry"}){
                    if(activeDraft.kind==EntryKind.TRANSFER)TransferForm(snapshot,activeDraft,vm,busy,{sheet="entry-themes"},{sheet="books"},{editAccount=null;sheet="account"},{vm.leaveEntry(book.id,false,entryReturnRoute)})
                    else if(activeDraft.kind==EntryKind.REFUND)RefundForm(snapshot,activeDraft,{vm.changeEntry(book.id,it)},{vm.saveEntry(book.id)},busy,{sheet="entry-themes"},{sheet="books"},{vm.leaveEntry(book.id,false,entryReturnRoute)},mediaContent={EntryMediaPanel(snapshot,activeDraft,vm)})
                    else if(route=="category-picker")CategoryPicker(vm,snapshot,activeDraft,{vm.changeEntry(book.id,it)},{categoryParent=it;categoryKind=activeDraft.kind;editCategory=null;sheet="category"},{vm.navigate("entry")})
                    else EntryForm(snapshot,activeDraft,{vm.changeEntry(book.id,it)},{vm.saveEntry(book.id)},{editAccount=null;sheet="account"},{vm.navigate("category-picker")},busy,{sheet="entry-themes"},{sheet="books"},{
                        if(entryReturnRoute!="detail"){selectedEventId=activeDraft.id;detailReturnRoute=entryReturnRoute}
                        vm.leaveEntry(book.id,false,entryReturnRoute){if(entryReturnRoute!="detail")vm.navigate("detail")}
                    },referenceService=vm.referenceRates,onPreviewExisting={eventId->vm.previewOccurrenceLink(book.id,activeDraft.occurrenceId!!,eventId)},onRate={sheet="manual-rate"},onExitActivity={vm.execute{vm.repository.setActivityMode(book.id,true)};vm.changeEntry(book.id,activeDraft.copy(activityId=null,reserveId=null,reserveAmount="",treatEnabled=false,treatFriends=emptyList(),treatWeights=emptyMap()))},mediaContent={EntryMediaPanel(snapshot,activeDraft,vm)})
                }
            }
            entryReview?.let{EntryReviewSheet(it,reviewRefreshed,busy,{vm.confirmEntryReview(book.id)},{vm.dismissEntryReview()})}
            occurrenceLinkReview?.takeIf{it.occurrence.bookId==book.id}?.let{r->
                OccurrenceLinkReviewSheet(r,occurrenceLinkRefreshed,busy,openEntry,{vm.occurrenceLinkReview.value=null},{vm.confirmOccurrenceLink()})
            }
            when(sheet) {
                "cash-risk"->riskAccountId?.let{id->FinancialRiskActions(snapshot,vm,id,{sheet=null},{openTimelineItem(it)},{sheet=null;vm.navigate("forecast")},{incomeAccount=it;incomeTarget=null;incomeArriving=false;sheet="outlook-income"},{from,to->sheet=null;requestEntry(EntryKind.TRANSFER,from,toAccountId=to)},{sheet=null;requestEntry(EntryKind.INCOME,it)})}
                "outlook-income"->{
                    val old=snapshot.expectedIncomes.firstOrNull{it.id==incomeTarget}
                    if(incomeTarget==null||old!=null)ExpectedIncomeEditor(snapshot,old,incomeArriving,vm,{sheet=null},incomeAccount)
                }
                "income-actions"->snapshot.expectedIncomes.firstOrNull{it.id==incomeTarget}?.let{income->YSheet(word("核对预计收入","収入予定を確認"),{sheet=null}) {
                    YText(income.name,18)
                    RecordFacts(listOf(word("预计金额 / 日期","予定額・日付") to (Money(Currency.valueOf(income.currency),income.amountMinor).display()+" · "+income.date)))
                    if(income.status!="CANCELLED"&&snapshot.expectedIncomeArrival(income)==null) {
                        YButton(word("确认实际到账","入金の確認"),{incomeArriving=true;sheet="outlook-income"},Modifier.testTag("outlook-income-arrive"),primary=true)
                        YButton(word("修改预计安排","収入予定を修正"),{incomeArriving=false;sheet="outlook-income"},Modifier.testTag("outlook-income-edit"))
                        YButton(word("取消预计收入","収入予定を取消"),{vm.execute(onSuccess={sheet=null}){vm.repository.cancelExpectedIncome(book.id,income.id)}},Modifier.testTag("outlook-income-cancel"))
                    }else snapshot.expectedIncomeArrival(income)?.let{event->YButton(word("查看实际收入","実収入を見る"),{sheet=null;openRecord(event)})}?:YText(word("此预计收入已取消。","この収入予定は取消済みです。"))
                }}

                "finance-edit"->selectedEvent?.let{event->
                    val finance by vm.financialState.collectAsStateWithLifecycle()
                    val graph=finance?.takeIf{it.book.id==book.id}
                    val contract=snapshot.contracts.firstOrNull{it.fundingEventId==event.id}
                    val repayment=snapshot.repayments.firstOrNull{it.eventId==event.id}
                    if(event.kind==EntryKind.BORROWING.name)FinancialBorrowingEditor(snapshot,snapshot.accounts.first{it.id==event.accountId},vm,{sheet=null},contract=contract,eventId=event.id)
                    else if(graph!=null) {
                        val debt=repayment?.debtAccountId?:snapshot.storedPostings.firstOrNull{it.eventId==event.id&&snapshot.accounts.any{a->a.id==it.accountId&&AccountType.valueOf(a.type).debt}}?.accountId
                        snapshot.accounts.firstOrNull{it.id==debt}?.let{FinancialRepaymentEditor(snapshot,it,graph,vm,{sheet=null},eventId=event.id)}
                    }
                }
                "manual-rate"->draft?.let{ManualRateEditor(snapshot,it,vm,{sheet=null})}
                "record-existing-draft"->selectedEvent?.let{event->YSheet(word("还有一笔草稿","下書きが残っています"),{sheet=null}) {
                    YButton(word("继续这笔草稿","下書きを続ける"),{vm.resumeEntry(snapshot);sheet=null},primary=true)
                    YButton(word("放弃草稿，继续当前操作","下書きを破棄して操作を続ける"),{vm.execute({
                        if(pendingRecordAction=="confirm")vm.confirmActual(snapshot,event)else vm.editEntry(snapshot,event)
                        sheet=null
                    }){vm.repository.clearDraft(book.id)}})
                }}
                "refund-existing-draft"->selectedEvent?.let{original->YSheet(word("还有一笔草稿","下書きが残っています"),{sheet=null}) {
                    YButton(word("继续这笔草稿","下書きを続ける"),{vm.resumeEntry(snapshot);sheet=null},primary=true)
                    YButton(word("放弃草稿，记录退款","下書きを破棄して返金を記録"),{vm.execute({vm.startRefund(snapshot,original);sheet=null}){vm.repository.clearDraft(book.id)}})
                }}
                "entry-themes"->YSheet(word("切换主题，保留这笔输入","入力を保持してテーマを切替"),{sheet=null}) {
                    Column(Modifier.fillMaxWidth()){themeChoices().forEach{(id,label)->EntryThemeOption(label,{vm.appearance(book.id,theme=id);sheet=null})}}
                }
                "account"->AccountEditor(snapshot,vm,{sheet=null},editAccount,debtOnly=if(openEntry&&draft?.kind in listOf(EntryKind.INCOME,EntryKind.TRANSFER))false else null)
                "category"->CategoryEditor(snapshot,categoryKind,categoryParent,editCategory,vm,{sheet=null},
                    onUse=if(openEntry&&route=="category-picker"&&editCategory==null){id->vm.entry.value?.let{vm.changeEntry(book.id,it.copy(categoryId=id));vm.navigate("entry")};sheet=null} else null)
                "books"->YSheet(word("切换账本","帳簿を切替"),{sheet=null}) {
                    ready.books.forEach{b->YButton(b.name+(if(b.id==book.id)" ✓" else ""),{if(b.id==book.id)sheet=null else if(openEntry){switchTarget=b.id;sheet="switch-entry"}else if(financialDraft?.hasInput==true){switchTarget=b.id;sheet="switch-financial"}else{vm.selectBook(b.id);sheet=null}},Modifier.fillMaxWidth())}
                    YButton(word("＋ 创建独立账本","＋ 独立した帳簿を作成"),{if(openEntry){leaveTarget="new-book";sheet="leave-entry"}else sheet="new-book"},primary=true)
                }
                "existing-draft"->YSheet(word("还有一笔草稿","下書きが残っています"),{sheet=null}) {
                    YButton(word("继续这笔草稿","下書きを続ける"),{vm.resumeEntry(snapshot);sheet=null},primary=true)
                    YButton(word("放弃草稿，记新的一笔","下書きを破棄して新しく記録"),{vm.execute({if(requestedOccurrence!=null)vm.startOccurrence(snapshot,snapshot.occurrences.first{it.id==requestedOccurrence})else startRequestedEntry(snapshot.copy(draft=null));sheet=null}){vm.repository.clearDraft(book.id)}})
                }
                "new-book"->BookEditor(null,book,vm,{sheet=null})
                "rename-book"->(ready.books.find{it.id==editBookId} ?: book).let{editing->BookEditor(editing,book,vm,{sheet=null},hasRecords=ready.overviews.firstOrNull{it.book.id==editing.id}?.let{it.recordCount>0||it.planningCount>0} ?: snapshot.events.isNotEmpty(),monthlyBudgetMinor=ready.overviews.firstOrNull{it.book.id==editing.id}?.monthlyBudgetMinor)}
                "leave-entry"->YSheet(word("这笔还未保存","この記録は未保存です"),{sheet=null}) {
                    YText(word("可以保留草稿，下次继续。原记录不会被未保存内容修改。","下書きを残して後で続行できます。元の記録は変更されません。"))
                    YButton(word("保留草稿并返回","下書きを残して戻る"),{vm.leaveEntry(book.id,true,if(leaveTarget=="new-book")"home" else leaveTarget){sheet=if(leaveTarget=="new-book")"new-book" else null}},primary=true)
                    YButton(word("放弃修改","変更を破棄"),{vm.leaveEntry(book.id,false,if(leaveTarget=="new-book")"home" else leaveTarget){sheet=if(leaveTarget=="new-book")"new-book" else null}})
                    YLink(word("继续编辑","編集を続ける"),{sheet=null})
                }
                "switch-financial"->financialDraft?.let{session->YSheet(word("切换前保存草稿？","切替前に下書きを残しますか？"),{sheet=null}) {
                    val target=requireNotNull(switchTarget)
                    YButton(word("保留草稿并切换","下書きを残して切替"),{vm.execute(onSuccess={session.close();vm.navigate("home");sheet=null}){session.flush();vm.preferences.select(target)}},Modifier.testTag("financial-draft-switch-keep"),primary=true,enabled=!busy)
                    YButton(word("放弃草稿并切换","下書きを破棄して切替"),{vm.execute(onSuccess={session.close();vm.navigate("home");sheet=null}){session.discard();vm.preferences.select(target)}},Modifier.testTag("financial-draft-switch-discard"),enabled=!busy)
                    YLink(word("继续编辑","編集を続ける"),{sheet=null},Modifier.testTag("financial-draft-switch-continue"))
                }}
                "switch-entry"->YSheet(word("切换前保存草稿？","切替前に下書きを残しますか？"),{sheet=null}) {
                    val target=switchTarget!!
                    YButton(word("保留草稿并切换","下書きを残して切替"),{val d=draft!!;vm.execute({vm.entry.value=null;vm.navigate("home");sheet=null}){vm.repository.saveDraft(book.id,d);vm.preferences.select(target)}},primary=true)
                    YButton(word("放弃草稿并切换","下書きを破棄して切替"),{vm.execute({vm.entry.value=null;vm.navigate("home");sheet=null}){vm.repository.clearDraft(book.id);vm.preferences.select(target)}})
                    YLink(word("继续编辑","編集を続ける"),{sheet=null})
                }
                "delete"->selectedEvent?.takeIf{it.deletedAt==null}?.let{event->YSheet(word("移至回收站","ごみ箱に移動"),{sheet=null}) {
                    YText(word("确认后从正常记录与统计中移除，30天内可以恢复。","通常の記録と集計から除外。30日以内は復元できます。"))
                    YText(snapshot.path(event.categoryId)+" · "+Money(Currency.valueOf(event.currency),event.amountMinor).display(),18,number=true)
                    RefundLifecycleNotice(snapshot,event)
                    if(snapshot.contracts.any{it.fundingEventId==event.id})YText(word("仅撤销这次借入的资金与本金变动；独立还款、账单与合同资料保留。","この借入の資金・元金の変動のみ取り消します。独立した返済・請求・契約情報は保持します。"),12)
                    if(snapshot.repayments.any{it.eventId==event.id})YText(word("撤销这次实际扣款、费用和清偿分配，恢复对应待付义务；合同与账单资料保留。","今回の実出金・手数料・返済充当を取り消し、支払義務を戻します。契約・請求情報は保持します。"),12)
                    snapshot.removalImpacts(event.id).forEach{EntryImpactPreview(snapshot,event,it,YearMonth.now(vm.repository.clock).toString())}
                    YButton(word("确认移至回收站","ごみ箱へ移動"),{vm.execute({sheet=null;vm.navigate("ledger")}){vm.repository.deleteEntry(book.id,event.id)}},primary=true)
                }}
            }
        }
        if(error!=null) YSheet(word("请检查","確認してください"),{vm.error.value=null}) {YText(if(LocalLanguage.current=="ja")error!!.substringAfter(" / ",error!!) else error!!.substringBefore(" / "));YButton(word("知道了","確認"),{vm.error.value=null},primary=true)}
        notice?.takeIf{it.bookId==book?.id&&error==null}?.let{
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom=95.dp),contentAlignment=Alignment.BottomCenter){
                YToast(word(it.zh,it.ja),Modifier.widthIn(max=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp*.9f))
            }
        }
        }
    }
}

@Composable private fun Detail(s:BookSnapshot,e:EventEntity,onEdit:()->Unit,onDelete:()->Unit,vm:AppViewModel,onRefund:()->Unit,onConfirm:()->Unit,onRecord:(EventEntity)->Unit) {
    RecordDetailCore(s,e,onEdit)
    s.settlements.find{it.eventId==e.id}?.takeIf{it.accountCurrency!=e.currency||!it.confirmed||e.kind==EntryKind.REFUND.name}?.let{settlement->
        RecordFacts(listOf(
            word("原币金额","原通貨額") to Money(Currency.valueOf(e.currency),e.amountMinor).display(),
            (if(settlement.confirmed)word("账户实际入账","口座の実計上額")else word("预计入账 · 待确认","計上見込み・確認待ち")) to Money(Currency.valueOf(settlement.accountCurrency),settlement.accountMinor).display(),
            word("原预计入账","元の計上見込み") to settlement.expectedMoney().display(),
            word("实际 / 预计入账日期","実際・予定計上日") to settlement.settlementDate,
            word("保存的换算来源 / 日期","保存した換算情報・日付") to ((if(settlement.rateSource=="manual")word("手动填写","手入力")else settlement.rateSource)+" · "+settlement.rateDate)
        ),audit=true)
    }
    if(e.cardPaymentChoice!=null) {
        val finance by vm.financialState.collectAsStateWithLifecycle()
        CardPaymentFacts(e,finance?.takeIf{it.book.id==s.book.id}?.rules?.firstOrNull{it.id==e.cardPaymentRuleId})
    }
    StoredReferenceRates(s.settlements.firstOrNull{it.eventId==e.id}?.referenceRatesJson)
    if(e.currency!=s.book.currency&&e.kind in listOf(EntryKind.EXPENSE.name,EntryKind.INCOME.name,EntryKind.REFUND.name))YText(word("本位币统计：","基準通貨集計：")+Money(Currency.valueOf(s.book.currency),e.baseMinor).display(),12,number=true)
    if(e.historicalIncluded)YText(word("已包含在期初余额","期首残高に含まれます"),11)
    val settlement=s.settlements.find{it.eventId==e.id}
    if(e.kind in listOf(EntryKind.EXPENSE.name,EntryKind.INCOME.name,EntryKind.REFUND.name)&&settlement!=null&&(settlement.accountCurrency!=e.currency||!settlement.confirmed))YButton(word("确认实际入账金额","実計上額を確認"),onConfirm,primary=true)
    s.repayments.firstOrNull{it.eventId==e.id}?.let{FinancialRepaymentFacts(s,e,it)}
    s.contracts.firstOrNull{it.fundingEventId==e.id}?.let{FinancialBorrowingFacts(s,it)}
    s.transfers.find{it.eventId==e.id}?.let{TransferFacts(s,e,it,s.expenseComponents.filter{c->c.eventId==e.id})}
    if(e.kind==EntryKind.EXPENSE.name) {
        val linked=s.settlements.filter{it.originalEventId==e.id}
        val refunds=s.visibleEvents.filter{r->linked.any{it.eventId==r.id}}
        if(refunds.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().testTag("refund-history"),verticalArrangement=Arrangement.spacedBy(15.dp)) {
            ThemeSection(word("原消费与退款","元の購入と返金"),"REFUND HISTORY")
            RecordFacts(listOf(
                word("原币累计退款","原通貨の返金合計") to Money(Currency.valueOf(e.currency),refunds.filter{r->linked.any{it.eventId==r.id&&it.confirmed}}.sumOf{it.amountMinor}).display(),
                word("原消费月剩余净支出","元の購入月の純支出残額") to Money(Currency.valueOf(s.book.currency),s.netExpense(e)).display()
            ),audit=true)
            val confirmedOriginal=refunds.filter{r->linked.any{it.eventId==r.id&&it.confirmed}}.sumOf{it.amountMinor}
            if(confirmedOriginal==e.amountMinor&&s.netExpense(e)!=0L)YText(word("原币已全额退款，剩余为退款换算 / 收支差异，不据此认定手续费。","原通貨は全額返金済み。残額は換算・収支の差額で、手数料とは断定しません。"),11,color=LocalYohaku.current.muted)
            TransactionList(s,refunds.sortedByDescending{it.date},onRecord)
            }
        }
        if(refunds.sumOf{it.amountMinor}<e.amountMinor)YButton(word("记录退款","返金を記録"),onRefund,primary=true)
        else YText(word("原币退款已全部记录，实际收支差额仍保留在原消费月。","原通貨の返金は全額記録済み。実収支の差額は元の購入月に保持します。"),11)
    }
    settlement?.originalEventId?.let{id->s.visibleEvents.find{it.id==id}?.let{original->YLink(word("查看原消费","元の購入を見る"),{onRecord(original)})}}
    s.occurrences.filter{it.eventId==e.id}.forEach{o->RecordFacts(listOf(word("关联周期实例","関連する定期予定") to (o.name+" · "+o.scheduledDate)),audit=true)}
    s.treats.find{it.eventId==e.id}?.let{treat->
        ThemeSection(word("活动内互请份额","シーンのごちそうの取り分"),"SHARED PORTIONS")
        s.allocations.filter{it.treatId==treat.id}.forEach{a->YText((if(a.participant=="ME")word("本人","自分")else s.friends.first{it.id==a.friendId}.name)+" · "+Money(Currency.valueOf(treat.currency),a.amountMinor).display(),14)}
    }
    s.reserveUses.filter{it.eventId==e.id}.forEach{u->YText(word("使用预留：","使用した確保：")+s.reserves.first{it.id==u.reserveId}.name+" · "+Money(Currency.valueOf(s.book.currency),u.amountMinor).display(),13)}
    RecordMediaPanel(s,e,vm)
    RevisionHistory(s,e,vm)
    YLink(word("移至回收站","ごみ箱に移動"),onDelete)
}

@Composable private fun CategoryPicker(vm:AppViewModel,s:BookSnapshot,d:EntryDraft,onChange:(EntryDraft)->Unit,onCreate:(String?)->Unit,onChoose:()->Unit) {
    val t=LocalYohaku.current
    CategoryCover()
    CategoryKindTabs(d.kind){kind->val eligible=s.entryAccounts(kind);onChange(d.copy(kind=kind,categoryId=null,accountId=eligible.find{it.id==d.accountId}?.id?:eligible.firstOrNull()?.id))}
    CategorySectionCaption()
    var search by rememberSaveable(s.book.id,d.kind){mutableStateOf("")}
    var expanded by rememberSaveable(s.book.id,d.kind){mutableStateOf(s.categories.find{it.id==d.categoryId}?.let{listOf(it.parentId?:it.id)} ?: emptyList())}
    val categories=s.categories.filter{it.active&&it.kind==d.kind.name}
    val roots=categories.filter{it.parentId==null}
    fun matches(root:CategoryEntity,query:String)=root.name.contains(query.trim(),true)||categories.any{it.parentId==root.id&&it.name.contains(query.trim(),true)}
    val recentIds by remember(s.book.id,d.kind){vm.recentCategories(s.book.id,d.kind)}.collectAsStateWithLifecycle(emptyList())
    val recent=recentIds.mapNotNull{id->categories.find{it.id==id}}
    val favorite=categories.filter{it.favorite}
    if(favorite.isNotEmpty())CategoryShortcuts(word("我的常用 · 一步选择","よく使うカテゴリ・直接選択"),favorite.map{it.id to ("★ "+s.path(it.id))}){id->onChange(d.copy(categoryId=id));onChoose()}
    if(recent.isNotEmpty())CategoryShortcuts(word("最近使用 · 直接选择","最近使ったカテゴリ・直接選択"),recent.map{it.id to s.path(it.id)}){id->onChange(d.copy(categoryId=id));onChoose()}
    if(categories.isNotEmpty())YSearch(word("搜索分类","カテゴリを検索"),search,{query->
        search=query
        if(query.isNotBlank())expanded=(expanded+roots.filter{matches(it,query)}.map{it.id}).distinct()
    },category=true,displayPlaceholder=word("搜索一级或二级分类","親・子カテゴリを検索"))
    val visibleRoots=roots.filter{search.isBlank()||matches(it,search)}
    if(visibleRoots.isNotEmpty())Column(Modifier.fillMaxWidth().padding(top=5.dp,bottom=7.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        visibleRoots.forEach{c->
            val children=categories.filter{it.parentId==c.id}
            CategoryGroup(c.name,roots.indexOf(c),children.map{it.id to it.name},c.id in expanded,
                {if(children.isEmpty()){onChange(d.copy(categoryId=c.id));onChoose()}else expanded=if(c.id in expanded)expanded-c.id else expanded+c.id},
                {onChange(d.copy(categoryId=c.id));onChoose()},
                {id->onChange(d.copy(categoryId=id));onChoose()},{onCreate(c.id)},id=c.id)
        }
    }
    if(search.isNotBlank()&&visibleRoots.isEmpty())YText(word("没有找到分类，可以新建自己的分类。","カテゴリがありません。自分で追加できます。"),12,Modifier.testTag("category-no-results"),color=t.muted)
    if(categories.isEmpty())YEmpty(word("还没有分类，从你自己的用途开始。","カテゴリはまだありません。自分の使い道から。"),{onCreate(null)},word("创建第一个分类","最初のカテゴリを作成"))
    else YButton(word("＋ 新建分类","＋ カテゴリを作成"),{onCreate(null)},primary=true)
    CategoryStepFooter()
}

@Composable internal fun EntryForm(s:BookSnapshot,d:EntryDraft,onChange:(EntryDraft)->Unit,onSave:()->Unit,onAccount:()->Unit,onCategory:()->Unit,busy:Boolean,onTheme:()->Unit,onBooks:()->Unit,onCancelEdit:()->Unit,onRate:()->Unit={},onPreviewExisting:(String)->Unit={},onExitActivity:()->Unit={},referenceService:ReferenceRates?=null,mediaContent:@Composable ()->Unit={}) {
    val t=LocalYohaku.current;val eligible=s.entryAccounts(d.kind);val a=eligible.find{it.id==d.accountId};val currency=a?.currency ?: s.book.currency
    val foreign=d.originalCurrency!=null&&d.originalCurrency.name!=currency
    val occurrence=s.occurrences.find{it.id==d.occurrenceId}
    val confirmingOccurrence=occurrence!=null&&d.id==null
    val existingId=d.occurrenceLinkEventId
    val accountText=if(foreign)d.accountAmount else d.amount
    fun changeAccountAmount(value:String) {
        val clearBase=d.referenceBaseLinked
        val next=if(foreign)d.copy(accountAmount=value,referenceAccountLinked=false)else d.copy(amount=value)
        onChange(next.copy(baseAmount=if(clearBase)""else d.baseAmount))
    }
    EntrySummary(s.book.name,s.path(d.categoryId),d.id!=null,onCategory)
    EntryTools(s.book.name,onBooks,onTheme)
    if(confirmingOccurrence) {
        ThemeAuditPanel(occurrence!!.name,Modifier.testTag("occurrence-payment-context")) {
            RecordFacts(listOf(
                word("周期安排日期","定期予定日") to occurrence.scheduledDate,
                word("原预计金额","元の見込み額") to (occurrence.amountMinor?.let{Money(Currency.valueOf(occurrence.currency),it).display()}?:word("金额未定","金額未定"))
            ),audit=true)
        }
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(bottom=4.dp)){
            YField(word("金额","金額")+" · "+currency,accountText,{changeAccountAmount(it)},numeric=true,emphasizeAmount=true,amountSymbol=Currency.valueOf(currency).symbol.takeLast(1),displayLabel=(if(confirmingOccurrence&&d.settlementConfirmed)word("实际支付金额","実際の支払額")else if(d.settlementConfirmed)word("账户实际入账金额","口座の実際の計上額")else word("预计入账金额","計上見込額"))+" · "+currency)
            EntryCurrencyControls(d,currency,onChange)
            if(foreign&&referenceService!=null)ReferenceRatePanel(referenceService,d.originalCurrency!!,Currency.valueOf(currency),d.referenceQueryDate?:d.date,
                enabled=!d.settlementConfirmed,autoKey=d.amount+"/"+d.accountAmount+"/"+d.settlementConfirmed,
                onAuto={q->if(d.id==null&&!d.settlementConfirmed&&d.accountAmount.isBlank()&&runCatching{Money.parse(d.amount,q.base).minor>0}.getOrDefault(false))onChange(applyReferenceAccountAmount(d,Currency.valueOf(currency),q))},
                onUse={q->onChange(applyReferenceAccountAmount(d,Currency.valueOf(currency),q))})
            val baseText=if(currency==s.book.currency)accountText else d.baseAmount
            val base=if(baseText.isBlank()){if(currency==s.book.currency)Money(Currency.valueOf(s.book.currency),0) else null} else runCatching{Money.parse(baseText,Currency.valueOf(s.book.currency))}.getOrNull()
            YText(word("本位币参考","基準通貨の参考")+" "+(base?.display()?:"—"),if(t.id=="journal")11 else 9,Modifier.padding(top=16.dp).testTag("entry-base-caption"),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
            if(d.kind==EntryKind.EXPENSE&&currency=="JPY")Box(Modifier.padding(top=13.dp)){EntryQuickAmount(accountText){changeAccountAmount(it)}}
            YLink(word("查看 / 手动填写换算","換算を確認・手入力"),onRate)
        }
        if(currency!=s.book.currency) {
            YField(word("本位币统计金额","基準通貨の集計額")+" · "+s.book.currency,d.baseAmount,{onChange(d.copy(baseAmount=it,referenceBaseLinked=false))},numeric=true)
            if(referenceService!=null)ReferenceRatePanel(referenceService,Currency.valueOf(currency),Currency.valueOf(s.book.currency),d.referenceQueryDate?:d.date,
                autoKey=accountText+"/"+d.baseAmount,
                onAuto={q->if(d.id==null&&d.baseAmount.isBlank()&&runCatching{Money.parse(accountText,q.base).minor>0}.getOrDefault(false))onChange(applyReferenceBaseAmount(d,Currency.valueOf(currency),Currency.valueOf(s.book.currency),q))},
                onUse={q->onChange(applyReferenceBaseAmount(d,Currency.valueOf(currency),Currency.valueOf(s.book.currency),q))})
        }
        if(eligible.isNotEmpty())YSelect(if(d.kind==EntryKind.INCOME)word("到账账户","入金口座") else word("付款账户","支払口座"),d.accountId,eligible.map{it.id as String? to it.name},{id->
            val next=eligible.first{it.id==id}
            onChange(if(a!=null&&a.currency!=next.currency)d.copy(accountId=id,originalCurrency=d.originalCurrency?:Currency.valueOf(a.currency),accountAmount="",baseAmount="",settlementConfirmed=false,manualRate="",rateSource="manual",referenceExpectedFromMinor=null,referenceExpectedToMinor=null,referenceExpectedAccountMinor=null,referenceExpectedBaseMinor=null,referenceRates=emptyList())
                else d.copy(accountId=id))
        })
        else {YText(if(d.kind==EntryKind.INCOME)word("还没有到账账户","入金口座がありません") else word("还没有付款账户","支払口座がありません"),14);YButton(word("添加第一个账户","最初の口座を追加"),onAccount,primary=true)}
        if(d.kind==EntryKind.EXPENSE&&a?.type==AccountType.CREDIT.name)CardPaymentFields(d,onChange)
        if((s.features?.activityMode==true||d.activityId!=null)&&d.kind==EntryKind.EXPENSE) {
            s.features?.activeActivityId?.let{id->s.activities.find{it.id==id}?.let{YText(word("正在记录：","記録中：")+it.name,13);YLink(word("退出活动记录模式","シーンの記録モードを終了"),onExitActivity)}}
            YSelect(word("活动 / 可选","シーン・任意"),d.activityId,listOf(null to word("日常","日常"))+s.activities.map{it.id as String? to it.name},{onChange(d.copy(activityId=it,reserveId=null,reserveAmount="",treatEnabled=false,treatFriends=emptyList(),treatWeights=emptyMap()))})
            d.activityId?.let{activityId->
                val choices=s.reserves.filter{it.activityId==activityId&&(it.id==d.reserveId||it.active&&s.reserveRemaining(it,d.id)>0)}
                if(choices.isNotEmpty()) {
                    YSelect(word("使用预留 / 可选","確保を使用・任意"),d.reserveId,listOf(null to word("不使用","使用しない"))+choices.map{it.id as String? to it.name+" · "+Money(Currency.valueOf(s.book.currency),s.reserveRemaining(it,d.id)).display()},{onChange(d.copy(reserveId=it,reserveAmount=""))})
                    if(d.reserveId!=null)YField(word("使用金额 / 留空按本次支出","使用額・空欄なら今回の支出額"),d.reserveAmount,{onChange(d.copy(reserveAmount=it))},numeric=true)
                }
                YToggle(word("本人付款 · 活动内互请","自分の支払・シーンのごちそう"),d.treatEnabled,{onChange(d.copy(treatEnabled=it))})
                if(d.treatEnabled)TreatSplitFields(s,activityId,d.treatFriends,d.treatMode,d.treatWeights,{onChange(d.copy(treatFriends=it))},{onChange(d.copy(treatMode=it))},{onChange(d.copy(treatWeights=it))})
            }
        }
        if(d.kind==EntryKind.EXPENSE&&s.merchants.any{it.active})YSelect(word("已保存的商户 / 可手动填写","保存済みのお店・手入力可"),d.merchantId,listOf(null to word("手动填写","手入力"))+s.merchants.filter{it.active||it.id==d.merchantId}.map{it.id as String? to it.name},{id->onChange(d.copy(merchantId=id,merchant=id?.let{s.merchants.first{it.id==id}.name}?:d.merchant))})
        YField(if(d.kind==EntryKind.INCOME)word("名称 / 说明","名称 / 内容") else word("商户 / 买了什么","お店 / 買ったもの"),d.merchant,{onChange(d.copy(merchant=it,merchantId=null))})
        var activeDate by remember{mutableStateOf<Int?>(null)}
        YDateFields(listOf((if(confirmingOccurrence)word("实际付款日期","実際の支払日")else word("日期","日付")) to d.date)+if(foreign)listOf(word("实际 / 预计入账日期","実際・予定計上日") to d.settlementDate)else emptyList(),activeDate,{activeDate=it},{index,date->
            onChange(if(index==0)d.copy(date=date,settlementDate=if(d.settlementDate==d.date)date else d.settlementDate)else d.copy(settlementDate=date))
        })
        Column(Modifier.fillMaxWidth()){
            EntryNotes(d.id,d.note,{onChange(d.copy(note=it))})
            HistoricalOpeningToggle(s.book.startDate,d.historicalIncluded){onChange(d.copy(historicalIncluded=it))}
        }
        mediaContent()
        if(confirmingOccurrence) {
            val candidates=s.visibleEvents.filter{e->e.kind==EntryKind.EXPENSE.name&&s.occurrences.none{it.eventId==e.id&&it.status=="COMPLETED"&&it.id!=d.occurrenceId}}
            YSelect(word("关联已记流水，或新增一次","既存記録に関連付け、または新規"),existingId,
                listOf(null to word("尚未记账，确认后新增","未記録・確認後に追加"))+candidates.map{it.id as String? to (it.date+" · "+it.merchant.ifBlank{s.path(it.categoryId)}+" · "+s.accountMoney(it).display())},{onChange(d.copy(occurrenceLinkEventId=it))})
            YText(if(existingId==null&&!d.settlementConfirmed)word("这笔消费按预计入账金额计入预算，保存后解除原周期预留，避免双算。账户实际金额仍待确认；已记过时请选择已有流水。","この支出は計上見込額で予算に含め、保存後に元の定期予定を解除して二重計算を防ぎます。口座の実額は確認待ちです。記録済みなら既存記録を選んでください。")else if(existingId==null)word("已手动记过时请选择对应流水，避免重复记账。实际确认后解除这次预留。","手動記録済みなら関連する記録を選択。重複せず、実際の確認後に予定を解除します。")else word("采用所选已有支出的金额和日期，不使用本表单输入，也不新增记录或附件；确认后解除这次预留。","選択した既存支出の金額・日付を使用します。このフォームの入力や添付は追加せず、確認後に予定を解除します。"),11,Modifier.testTag("occurrence-payment-note"),color=t.muted)
        }
        Box(Modifier.padding(top=25.dp)){EntrySaveArea(d.id!=null,busy,existingId!=null||a!=null,{existingId?.let(onPreviewExisting)?:onSave()},onCancelEdit,if(existingId!=null)word("预览关联已有支出","既存支出との関連を確認")else if(confirmingOccurrence&&!d.settlementConfirmed)word("记录消费并解除预留","支出を記録して予定を解除")else if(confirmingOccurrence)word("确认付款并解除预留","支払を確認して予定を解除")else null)}
    }
}

@Composable internal fun CategoryManagement(s:BookSnapshot,onCreate:(String?,EntryKind)->Unit,onEdit:(CategoryEntity)->Unit,onFavorite:(CategoryEntity)->Unit) {
    YLead(word("用途有层次，记录更清楚。","使い道を、親と子で整理。"),word("一级归纳，二级细分。直接记入一级的消费列为未细分。","親で集計、子で詳細。親への直接記録は内訳未分類。"),"CATEGORIES / TWO LEVELS",pageFlow=true)
    listOf(EntryKind.EXPENSE,EntryKind.INCOME).forEachIndexed{index,kind->
        ThemeSection(if(kind==EntryKind.EXPENSE)word("支出分类","支出カテゴリ") else word("收入分类","収入カテゴリ"),previousBottomPadding=if(index==0)10 else 0)
        val roots=s.categories.filter{it.kind==kind.name&&it.parentId==null}
        if(roots.isEmpty())YEmpty(word("从自己的用途开始，尚无预建分类。","自分の使い道から。初期カテゴリはありません。"))
        else Column(Modifier.fillMaxWidth().padding(top=5.dp,bottom=17.dp),verticalArrangement=Arrangement.spacedBy(17.dp)){roots.forEach{root->
            CategoryManageGroup(root.id,root.name,root.favorite,s.categories.filter{it.parentId==root.id}.map{it.id to (it.name+if(it.favorite)" ★" else "")},{onEdit(root)},
                {id->onEdit(s.categories.first{it.id==id})},{onCreate(root.id,kind)})
        }}
        YButton(word("＋ 添加一级分类","＋ 親カテゴリを追加"),{onCreate(null,kind)},Modifier.testTag("category-kind-add-${kind.name}"),primary=true)
    }
    CategoryFavorites(s,onFavorite)
}

@Composable private fun CategoryEditor(s:BookSnapshot,kind:EntryKind,parentId:String?,original:CategoryEntity?,vm:AppViewModel,onClose:()->Unit,onUse:((String)->Unit)?=null) {
    var name by remember(original?.id){mutableStateOf(original?.name.orEmpty())}
    var parent by remember(original?.id,parentId){mutableStateOf(parentId)}
    var saving by remember(original?.id){mutableStateOf(false)}
    YSheet(if(original==null)word("创建一级 / 二级分类","親・子カテゴリを作成") else word("编辑分类","カテゴリを編集"),onClose) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Box(Modifier.padding(top=if(LocalYohaku.current.id=="objects")15.dp else 0.dp)){
                YText(word("一级归纳用途，二级记录细节。最多两级，也可以只用一级。","親で用途をまとめ、子で詳しく記録。最大2階層、親だけでも使えます。"),11,Modifier.testTag("category-editor-intro"),color=LocalYohaku.current.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
            }
            YField(word("分类名称","カテゴリ名"),name,{name=it})
            if(original==null)YSelect(word("分类层级","階層"),parent,listOf(null to word("无 · 创建为一级分类","なし・親カテゴリとして作成"))+s.categories.filter{it.kind==kind.name&&it.parentId==null}.map{it.id as String? to it.name},{parent=it},displayLabel=word("归属一级分类","親カテゴリ"))
            else Box(Modifier.padding(vertical=4.dp)){
                SetupNote(if(s.categories.any{it.parentId==original.id})word("已有子分类，保持为一级分类。","子カテゴリがあるため親カテゴリを保持。") else word("归属一级分类","親カテゴリ")+" · "+(s.categories.firstOrNull{it.id==original.parentId}?.name?:word("无 · 一级分类","なし・親カテゴリ")),pageFlow=false,modifier=Modifier.testTag("category-parent-fixed"))
            }
            Box(Modifier.padding(top=15.dp)){
                YButton(if(onUse!=null)word("创建并使用","作成して使う") else word("保存分类","カテゴリを保存"),{
                    if(saving)return@YButton
                    saving=true
                    val submittedName=name;val submittedParent=parent;val submittedFavorite=original?.favorite?:false
                    var savedId:String?=null
                    vm.execute(onSuccess={if(onUse!=null)onUse(savedId!!) else onClose()},onComplete={saving=false}){savedId=vm.repository.saveCategory(s.book.id,submittedName,kind,submittedParent,original?.id,submittedFavorite)}
                },Modifier.testTag("category-editor-save"),primary=true,enabled=!saving)
            }
        }
    }
}

@Composable private fun AccountEditor(s:BookSnapshot,vm:AppViewModel,onClose:()->Unit,original:AccountEntity?=null,debtOnly:Boolean?=null) {
    val types=AccountType.entries.filter{debtOnly==null||it.debt==debtOnly}
    var name by rememberSaveable(original?.id){mutableStateOf(original?.name.orEmpty())}
    var type by rememberSaveable(original?.id,debtOnly){mutableStateOf(original?.let{AccountType.valueOf(it.type)} ?: types.first())}
    var currency by rememberSaveable(original?.id){mutableStateOf(Currency.valueOf(original?.currency ?: s.book.currency))}
    var opening by rememberSaveable(original?.id){mutableStateOf(original?.let{Money(currency,it.openingMinor).decimal.toPlainString()} ?: "0")}
    var date by rememberSaveable(original?.id){mutableStateOf(original?.openingDate ?: s.book.startDate)}
    var owner by remember{mutableStateOf<Int?>(null)}
    var saving by remember(original?.id){mutableStateOf(false)}
    val hasHistory=original!=null&&(s.events.any{it.accountId==original.id}||s.storedPostings.any{it.accountId==original.id})
    YSheet(if(original==null)word("添加账户与期初信息","口座・期首情報を追加") else word("编辑账户与期初信息","口座・期首情報を編集"),onClose) {
        Column(Modifier.fillMaxWidth().testTag("account-form")){
        Box(Modifier.padding(bottom=20.dp)){SetupNote(if(!s.book.initialized)word("首次设置 · 不计收入或消费","初回設定・収入と支出に含めません") else word("账户币种决定余额与实际入账金额","口座通貨が残高と計上額の基準"),pageFlow=false,modifier=Modifier.testTag("account-setup-label"))}
        if(original==null)YSelect(word("账户类型","種類"),type,types.map{it to accountTypeName(it)},{type=it},modifier=Modifier.padding(bottom=16.dp),displayLabel=word("账户类型","口座の種類"))
        else Box(Modifier.padding(bottom=16.dp)){RecordFacts(listOf(word("账户类型","口座の種類") to accountTypeName(type)))}
        YField(word("账户名称","口座名"),name,{name=it},Modifier.padding(bottom=if(hasHistory)17.dp else 16.dp))
        if(!hasHistory)YSelect(word("账户币种","通貨"),currency,Currency.entries.map{it to (it.name+" · "+when(it){Currency.JPY->word("日元","日本円");Currency.CNY->word("人民币","人民元");Currency.USD->word("美元","米ドル")})},{currency=it},modifier=Modifier.padding(bottom=16.dp),displayLabel=word("账户币种","口座通貨"))
        else Box(Modifier.padding(bottom=17.dp)){AccountCurrencyFixed(currency.name)}
        YField(if(type.debt)word("期初欠款","期首借入残高") else word("期初余额","期首残高"),opening,{opening=it},Modifier.padding(bottom=16.dp),numeric=true,displayLabel=(if(type.debt)word("期初未还本金","期首未返済元金") else word("期初实际余额","期首実残高"))+" · "+currency.name)
        YDateFields(listOf(word("余额 / 欠款对应日期","残高・借入の基準日") to date),owner,{owner=it},{_,v->date=v})
        if(original!=null){
            val proposed=opening.toBigDecimalOrNull()?.let{runCatching{Money.parse(opening,currency).minor}.getOrNull()}
            if(proposed!=null&&proposed!=original.openingMinor)YText(word("保存后账户余额：","保存後の残高：")+Money(currency,Math.addExact(s.balance(original)-original.openingMinor,proposed)).display(),16,Modifier.padding(top=16.dp),number=true)
        }
        YButton(word("保存账户信息","口座情報を保存"),{
            if(saving)return@YButton
            saving=true
            val submittedName=name;val submittedType=type;val submittedCurrency=currency;val submittedOpening=opening;val submittedDate=date
            var id:String?=null
            vm.execute(onSuccess={onClose();vm.entry.value?.takeIf{it.accountId==null}?.let{vm.changeEntry(s.book.id,it.copy(accountId=id))}},onComplete={saving=false}){
                id=vm.repository.saveAccount(s.book.id,submittedName,submittedType,submittedCurrency,Money.parse(submittedOpening,submittedCurrency),submittedDate,original?.id)
            }
        },Modifier.padding(top=31.dp).testTag("account-save-button"),primary=true,enabled=!saving)
        }
    }
}

@Composable private fun BookEditor(original:BookEntity?,current:BookEntity,vm:AppViewModel,onClose:()->Unit,hasRecords:Boolean=false,monthlyBudgetMinor:Long?=original?.budgetMinor) {
    var name by rememberSaveable(original?.id){mutableStateOf(original?.name.orEmpty())};var currency by rememberSaveable(original?.id){mutableStateOf(Currency.valueOf(original?.currency?:current.currency))}
    val month=rememberSaveable(original?.id){YearMonth.now(vm.repository.clock).toString()}
    var budget by rememberSaveable(original?.id){mutableStateOf(monthlyBudgetMinor?.let{Money(Currency.valueOf(original?.currency?:current.currency),it).decimal.toPlainString()}.orEmpty())}
    var review by remember{mutableStateOf<BudgetChangeReview?>(null)}
    var refreshed by remember{mutableStateOf(false)}
    var saving by remember(original?.id){mutableStateOf(false)}
    if(review!=null) {
        val value=review!!
        YSheet(word("账本与月预算变化","帳簿と月予算の変化"),{review=null}) {
            if(refreshed)YText(word("资料已变化，请重新确认。","情報が変わりました。再確認してください。"),12)
            RecordFacts(listOf(word("账本名称","帳簿名") to (value.snapshot.book.name+" → "+value.bookUpdate!!.name),word("统计币种","基準通貨") to (value.snapshot.book.currency+" → "+value.bookUpdate!!.currency),word("预算月份","予算の月") to month),audit=true)
            BudgetChanges(value.before,value.after)
            YButton(word("确认保存账本与预算","帳簿と予算を保存"),{
                if(saving)return@YButton
                saving=true
                var saved=false
                vm.execute(onSuccess={if(saved)onClose()},onComplete={saving=false}) {
                    saved=vm.repository.commitBudget(value)
                    if(!saved){review=vm.repository.previewBookBudget(value.bookUpdate!!,month,value.setting.amountMinor?.let{Money(Currency.valueOf(value.bookUpdate!!.currency),it)});refreshed=true}
                }
            },primary=true,enabled=!saving)
            YLink(word("返回继续编辑","戻って編集"),{review=null})
        }
        return
    }
    YSheet(if(original==null)word("创建独立账本","独立した帳簿を作成") else word("账本资料","帳簿情報"),onClose) {
        YField(word("账本名称","帳簿名"),name,{name=it})
        if(original==null||!hasRecords)YSelect(word("统计本位币","基準通貨"),currency,Currency.entries.map{it to it.name},{currency=it})
        else YText(word("本位币：","基準通貨：")+original.currency+word(" · 已有账务或预算资料，不可直接改写"," · 記録・予算情報があるため直接変更できません"))
        if(original?.initialized==true) {
            YText(month,11,number=true)
            YField(word("月预算 / 可稍后设置","月予算・後から設定可"),budget,{budget=it},numeric=true)
        }
        YText(word("账户、分类、余额和记录全都独立。","口座、カテゴリ、残高、記録はすべて独立です。"),12)
        YButton(word("保存账本","帳簿を保存"),{
            if(saving)return@YButton
            saving=true
            val submittedName=name;val submittedCurrency=currency;val submittedBudget=budget
            var saved=false
            vm.execute(onSuccess={if(saved)onClose()},onComplete={saving=false}) {
                if(original==null){vm.preferences.select(vm.repository.createBook(submittedName,submittedCurrency,current.theme,current.language));saved=true}
                else {
                    val updated=original.copy(name=submittedName,currency=submittedCurrency.name)
                    val amount=submittedBudget.takeIf{it.isNotBlank()}?.let{Money.parse(it,submittedCurrency)}
                    if(original.initialized&&amount?.minor!=monthlyBudgetMinor){review=vm.repository.previewBookBudget(updated,month,amount);refreshed=false}
                    else {vm.repository.updateBook(updated);saved=true}
                }
            }
        },primary=true,enabled=!saving)
    }
}

@Composable internal fun accountTypeName(type:AccountType)=when(type){AccountType.CASH->word("现金","現金");AccountType.BANK->word("银行账户 / 储蓄卡","銀行口座");AccountType.CREDIT->word("信用卡","クレジットカード");AccountType.LOAN->word("网贷","借入");AccountType.IC->word("交通 IC 卡","交通系IC")}


@Composable private fun Setup(vm:AppViewModel,s:BookSnapshot?,first:SetupDraft) {
    val t=LocalYohaku.current
    val language=LocalLanguage.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val step=s?.book?.setupStep ?: first.step
    val setupKey=s?.book?.id ?: "first"
    var name by rememberSaveable(setupKey){mutableStateOf(s?.book?.name ?: first.name.ifBlank{if(first.language=="ja")"日々の暮らし" else "日常生活"})}
    var currency by rememberSaveable(setupKey){mutableStateOf(Currency.valueOf(s?.book?.currency ?: first.currency))}
    var date by rememberSaveable(setupKey){mutableStateOf(s?.book?.startDate ?: first.date)}
    var budget by rememberSaveable(setupKey){mutableStateOf(s?.book?.budgetMinor?.let{Money(Currency.valueOf(s.book.currency),it).decimal.toPlainString()} ?: first.budget)}
    var dateOwner by remember{mutableStateOf<Int?>(null)}
    var accountSheet by rememberSaveable{mutableStateOf(false)}
    var originalAccountId by rememberSaveable{mutableStateOf<String?>(null)}
    var appearanceSheet by rememberSaveable{mutableStateOf(false)}
    var creating by remember(setupKey){mutableStateOf(false)}
    val setupScroll=rememberScrollState()
    LaunchedEffect(step,setupKey){setupScroll.scrollTo(0)}
    fun persistFirst(theme:String=first.theme,language:String=first.language,stage:Int=step) {
        if(creating)return
        if(s==null)vm.saveSetup(SetupDraft(stage,name,currency.name,date,budget,theme,language))
        else vm.appearance(s.book.id,theme,language)
    }
    fun move(next:Int) { if(s==null)persistFirst(stage=next) else vm.setupStep(s.book.id,next) }
    BackHandler(enabled=step>0||accountSheet){if(accountSheet)accountSheet=false else move(step-1)}
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(Modifier.weight(1f).testTag("setup-viewport").verticalScroll(setupScroll).testTag("setup-scroll")) {
        YAppHeader(null,{},{persistFirst(t.id,if(language=="zh")"ja" else "zh")},{focus.clearFocus();keyboard?.hide();appearanceSheet=true})
        Column(Modifier.padding(pageContentPadding()).padding(bottom=28.dp).testTag("setup-page"),verticalArrangement=Arrangement.spacedBy(15.dp)) {
        PageEdition("onboarding")
        if(step==0) {
            YLead(word("从今天开始，留一点余量。","今日から、少し余白を。"),word("不用补齐所有过去。从现有余额和欠款开始，慢慢记下日常。","過去の全記録は不要。今の残高と借入から、日々を少しずつ。"),"YOUR FIRST PAGE",word("你好，余量。","こんにちは、ヨハク。"),word("分类从空白开始","カテゴリは空白から"),amountIsText=true,pageFlow=true)
            SetupIntro(listOf(word("一本独立的账本","独立した帳簿") to word("账户、预算、活动和资料各自独立。","口座・予算・シーン・資料を独立管理。"),word("期初不是新的消费","期首残高は新しい支出ではない") to word("现有余额、欠款和已确认账单分别填写。","今の残高・借入・確定明細を分けて入力。"),word("复杂规则可以稍后补齐","計算条件は後からでも") to word("没有规则时，只展示已知安排，不假装能准确预测。","条件未設定なら既知の予定のみ。正確な予測を装いません。")))
            YButton(word("开始设置我的账本","マイ帳簿を設定"),{move(1)},Modifier.padding(top=if(t.id=="journal")23.dp else if(t.id=="objects")24.dp else 0.dp).testTag("setup-start-button"),primary=true)
        } else {
            SetupProgress(step,pageFlow=true)
            when(step) {
                1->{
                    YLead(word("先给日常，一个名字。","日々に、名前を。"),word("本位币用于预算和分析；账户仍可持有不同币种。","基準通貨は予算・分析用。口座は別の通貨も持てます。"),"FIRST STEPS / 01",pageFlow=true,afterSetupProgress=true)
                    Column(Modifier.fillMaxWidth().testTag("setup-book-form")){
                    YField(word("账本名称","帳簿名"),name,{name=it;if(s==null)persistFirst()},Modifier.padding(bottom=16.dp))
                    YSelect(word("统计本位币","基準通貨"),currency,Currency.entries.map{it to (it.name+" · "+when(it){Currency.JPY->word("日元","日本円");Currency.CNY->word("人民币","人民元");Currency.USD->word("美元","米ドル")})},{currency=it;if(s==null)persistFirst()},Modifier.padding(bottom=16.dp),displayLabel=word("统计本位币","集計の基準通貨"))
                    YField(word("月预算 / 可稍后设置","月予算・後から設定可"),budget,{budget=it;if(s==null)persistFirst()},modifier=Modifier.padding(bottom=16.dp),numeric=true,displayLabel=word("月预算 / 可稍后设置","月予算・後から設定可")+" · "+currency.name)
                    YDateFields(listOf(word("开始记账日期","記録の開始日") to date),dateOwner,{dateOwner=it},{_,v->date=v;if(s==null)persistFirst()})
                    Box(Modifier.padding(vertical=15.dp)){SetupNote(word("新账本没有预建分类，也不复制其他账本的账户和记录。","新しい帳簿はカテゴリ未作成。他の口座・記録はコピーしません。"))}
                    YButton(word("下一步：资金账户","次へ：資金口座"),{
                        if(creating)return@YButton
                        creating=true
                        val submittedName=name;val submittedCurrency=currency;val submittedDate=date;val submittedBudget=budget;val submittedTheme=t.id;val submittedLanguage=language
                        vm.execute(onComplete={creating=false}) {
                        LocalDate.parse(submittedDate)
                        val budgetMinor=submittedBudget.takeIf{it.isNotBlank()}?.let{Money.parse(it,submittedCurrency).minor}
                        if(s==null){
                            val id=vm.repository.createBook(submittedName,submittedCurrency,submittedTheme,submittedLanguage,submittedDate,budgetMinor)
                            vm.preferences.select(id);vm.preferences.clearSetup()
                        }else vm.repository.updateBook(s.book.copy(name=submittedName,currency=submittedCurrency.name,startDate=submittedDate,budgetMinor=budgetMinor,setupStep=2))
                    }},Modifier.padding(top=15.dp).testTag("setup-next-button"),primary=true,enabled=!creating)
                    }
                }
                2,3->{
                    val debt=step==3
                    YLead(if(debt)word("已有的欠款，接着记。","今ある借入も、ここから。") else word("现在的钱，放在哪里？","今のお金は、どこに？"),if(debt)word("本金、已确认账单和未来计划分开，不重复计债务。","元金・確定明細・将来計画を区別。借入を重複計上しません。") else word("填入开始日期的实际余额，不当作收入。","開始日の実残高を入力。収入として計上しません。"),"FIRST STEPS / 0$step",pageFlow=true,afterSetupProgress=true)
                    val accounts=s?.accounts?.filter{AccountType.valueOf(it.type).debt==debt}.orEmpty()
                    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Column(Modifier.fillMaxWidth().testTag("setup-account-list"),verticalArrangement=Arrangement.spacedBy(14.dp)){
                        if(accounts.isEmpty())YEmpty(if(debt)word("可以稍后补齐本金和还款安排。","元金と返済予定は後で補足できます。") else word("从现金或常用银行账户开始。外币账户也可以加入。","現金やよく使う銀行から。外貨口座も追加できます。"))
                        accounts.forEach{a->SetupAccountCard(a){originalAccountId=a.id;accountSheet=true}}
                    }
                    if(s!=null)YButton(if(debt)word("＋ 添加已有信用卡 / 借款","＋ カード・借入を追加") else word("＋ 添加资金账户","＋ 資金口座を追加"),{originalAccountId=null;accountSheet=true},Modifier.fillMaxWidth().testTag("setup-add-account"))
                    }
                    SetupNote(if(debt)word("已确认账单是期初债务的还款安排，不会再叠加一份欠款。","確定明細は期首借入の返済予定。借入額に重ねません。") else word("每个账户保留原币余额。只有汇总展示才做参考换算。","口座残高は元通貨を保持。集計表示だけ参考換算。"))
                    YButton(if(accounts.isEmpty())word("先跳过，稍后添加","後で追加して次へ") else word("保存这一页，继续","このページを保存して次へ"),{move(step+1)},Modifier.padding(top=15.dp).testTag("setup-next-button"),primary=true)
                }
                4->{
                    YLead(word("检查一下，就可以开始。","確認して、はじめよう。"),word("分类由你自己创建，分析重点也由你选择。","カテゴリも、分析の視点も、自分で選択。"),"FIRST STEPS / 04",pageFlow=true,afterSetupProgress=true)
                    s?.let{
                        SetupReview(it){a->originalAccountId=a.id;accountSheet=true}
                        YButton(word("完成，进入我的空白账本","完了・空白のマイ帳簿へ"),{vm.updateBook(it.book.copy(initialized=true))},Modifier.padding(top=20.dp).testTag("setup-finish-button"),primary=true)
                    }
                }
            }
            SetupBackLink{move(step-1)}
        }
        PageFootnote("onboarding")
        }
        }
    }
    if(appearanceSheet)YSheet(word("外观主题","テーマ"),{appearanceSheet=false}){
        Column(Modifier.fillMaxWidth()){themeChoices().forEach{(id,label)->EntryThemeOption(label+(if(id==t.id)" ✓" else ""),{persistFirst(id,language);appearanceSheet=false})}}
    }
    if(accountSheet&&s!=null)AccountEditor(s,vm,{accountSheet=false},s.accounts.find{it.id==originalAccountId},debtOnly=if(step==4)null else step==3)
}

@Composable internal fun AccountDetail(s:BookSnapshot,a:AccountEntity,onEdit:()->Unit,onEvent:(EventEntity)->Unit,onTransfer:()->Unit={},onFinancial:(()->Unit)?=null,today:LocalDate=LocalDate.now(),onRisk:(()->Unit)?=null) {
    val debt=AccountType.valueOf(a.type).debt
    val amount=Money(Currency.valueOf(a.currency),if(debt)s.balance(a)else s.cashBalanceAt(a,today.toString())).display()
    YLead(a.name,a.currency+" · "+if(AccountType.valueOf(a.type).debt)word("账户原币欠款","口座の元通貨借入残高") else word("账户原币余额","口座の元通貨残高"),"AVAILABLE BALANCE",amount,word("本位币参考","基準通貨参考")+" "+if(a.currency==s.book.currency)amount else "—",pageFlow=true)
    if(!AccountType.valueOf(a.type).debt)AccountTransferShortcut(onTransfer)
    else if(onFinancial!=null)YButton(word("账单、计划与金融规则","請求・予定と金融条件"),onFinancial,Modifier.testTag("account-financial"),primary=true)
    if(!debt&&onRisk!=null)YButton(word("核对未来资金安排","将来の資金予定を確認"),onRisk,Modifier.testTag("account-cash-risk"))
    if(!debt)YText(today.toString()+" · "+word("仅含截至今日已确认流水。未来已记流水在时间线中另列。","本日までの確定記録のみ。将来日付の記録は予定に別掲。"),11)
    Box(Modifier.padding(top=12.dp)){YButton(word("编辑账户","口座を編集"),onEdit,Modifier.fillMaxWidth().testTag("account-detail-edit"))}
    val transit=s.inTransitTransfers().filter{t->t.toAccountId==a.id||s.visibleEvents.any{it.id==t.eventId&&it.accountId==a.id}}
    if(transit.isNotEmpty())ThemeAuditPanel(word("在途资金 · 尚未计入收款余额","送金中・入金残高に未反映")) {
        transit.forEach{t->YText(Money(Currency.valueOf(t.toCurrency),t.toMinor).display()+" · "+t.toDate,15,number=true)}
    }
    ThemeSection(word("账户流水","口座の記録"),"RECENT")
    val entries=s.visibleEvents.filter{e->e.accountId==a.id||s.storedPostings.any{it.eventId==e.id&&it.accountId==a.id}}
    TransactionList(s,entries,onEvent,emptyContent={YEmpty(word("还没有流水，余额来自期初状态。","記録はまだありません。残高は期首情報から。"))},accountId=a.id)
}

@Composable internal fun entryKindName(kind:EntryKind)=when(kind){
    EntryKind.EXPENSE->word("支出","支出")
    EntryKind.INCOME->word("收入","収入")
    EntryKind.TRANSFER->word("转账","振替")
    EntryKind.REPAYMENT->word("还款","返済")
    EntryKind.BORROWING->word("借入","借入")
    EntryKind.REFUND->word("退款","返金")
}
