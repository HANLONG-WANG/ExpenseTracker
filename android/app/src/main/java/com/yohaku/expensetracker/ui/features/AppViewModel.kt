package com.yohaku.expensetracker.ui.features

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yohaku.expensetracker.YohakuApplication
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

sealed interface AppState {
    data object Loading:AppState
    data class Ready(val books:List<BookEntity>,val snapshot:BookSnapshot?,val firstSetup:SetupDraft?=null,val overviews:List<BookOverview> = emptyList()):AppState
    data class Failure(val message:String):AppState
}
data class AppNotice(val id:Long,val bookId:String,val zh:String,val ja:String)
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(val repository:LedgerRepository,val preferences:AppPreferences,val referenceRates:ReferenceRates=ReferenceRates(preferences,clock=repository.clock),val reminders:com.yohaku.expensetracker.platform.ReminderCoordinator?=null,val locationSource:com.yohaku.expensetracker.platform.CurrentLocationSource?=null):ViewModel() {
    private val writes=Mutex()
    private val readRequests=MutableStateFlow(0L)
    val today=MutableStateFlow(LocalDate.now(repository.clock))
    fun refreshToday(){today.value=LocalDate.now(repository.clock)}
    fun retryRead(){readRequests.update{it+1}}
    val error=MutableStateFlow<String?>(null)
    val notice=MutableStateFlow<AppNotice?>(null)
    private var noticeSequence=0L
    fun dismissNotice(id:Long){if(notice.value?.id==id)notice.value=null}
    val selectedBookMetadata=MutableStateFlow<BookEntity?>(null)
    val route=MutableStateFlow("home")
    val selectedStatementBatch=MutableStateFlow<String?>(null)
    internal val activeFinancialDraft=MutableStateFlow<FinancialDraftSession?>(null)
    internal val financialDraftSessions=java.util.concurrent.ConcurrentHashMap<String,FinancialDraftSession>()
    val entry=MutableStateFlow<EntryDraft?>(null)
    val entryReview=MutableStateFlow<LedgerChangeReview?>(null)
    val reviewRefreshed=MutableStateFlow(false)
    val entryReturnRoute=MutableStateFlow("home")
    val busy=MutableStateFlow(false)
    private var savingEntry=false
    private val backStack=mutableListOf<String>()
    fun navigate(page:String) {
        if(page==route.value)return
        if(page in listOf("home","analysis","life","accounts"))backStack.clear() else backStack.add(route.value)
        route.value=page
    }
    fun back(){route.value=if(backStack.isEmpty())"home" else backStack.removeAt(backStack.lastIndex)}
    val state=combine(readRequests,today){_,date->date}.flatMapLatest{date->
        combine(repository.dao.bookOverviews(java.time.YearMonth.from(date).toString()),preferences.activeBook,preferences.setupDraft){overviews,id,first->Triple(overviews,overviews.map{it.book}.let{books->books.find{it.id==id&&it.deletedAt==null} ?: books.firstOrNull{!it.archived&&it.deletedAt==null}},first).also{selectedBookMetadata.value=it.second}}
        .flatMapLatest{(overviews,book,first)-> val books=overviews.map{it.book};if(book==null)flowOf<AppState>(AppState.Ready(books,null,first,overviews)) else repository.snapshot(book).map{AppState.Ready(books,it,first,overviews)}}
        .catch{emit(AppState.Failure(it.message ?: "数据库读取失败 / データ読込失敗"))}
        .onStart{emit(AppState.Loading)}
    }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),AppState.Loading)
    val financialState=state.map{(it as? AppState.Ready)?.snapshot?.book?.id}.distinctUntilChanged()
        .flatMapLatest{bookId->if(bookId==null)flowOf(null)else repository.db.financialDao().graph(bookId)}
        .catch{error.value=it.message ?: "金融资料读取失败 / 金融情報の読込に失敗しました";emit(null)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val financialPredictionReview=MutableStateFlow<FinancialPredictionReview?>(null)
    val financialRuleReview=MutableStateFlow<FinancialRuleReview?>(null)
    val manualFinancialPlanReview=MutableStateFlow<ManualFinancialPlanReview?>(null)
    val creditLimitState=combine(state.map{(it as? AppState.Ready)?.snapshot?.book?.id}.distinctUntilChanged(),readRequests){bookId,_->bookId}
        .flatMapLatest{bookId->if(bookId==null)flowOf(null)else repository.db.creditLimitDao().graph(bookId)
            .catch{error.value=it.message ?: "额度资料读取失败 / 利用枠情報の読込に失敗しました";emit(null)}}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val financialArrangementReview=MutableStateFlow<FinancialArrangementReview?>(null)
    val financialLedgerReview=MutableStateFlow<FinancialLedgerReview?>(null)
    val financialBillReview=MutableStateFlow<BillConfirmationReview?>(null)
    val financialReviewRefreshed=MutableStateFlow(false)
    val setupDraft=state.mapNotNull{(it as? AppState.Ready)?.firstSetup}.map<SetupDraft,SetupDraft?>{it}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    init { execute { repository.purgeExpired();repository.collectUnusedMedia();preferences.pruneBookPreferences(repository.books.first().map{it.id}.toSet()) } }
    fun execute(onSuccess:()->Unit={},onComplete:()->Unit={},action:suspend()->Unit) { viewModelScope.launch {
        writes.withLock {
            busy.value=true
            try { withContext(Dispatchers.IO){action()};onSuccess() }
            catch(e:CancellationException){throw e}
            catch(e:Exception){error.value=e.message ?: "操作失败 / 操作できませんでした"}
            finally {busy.value=false;onComplete()}
        }
    } }
    fun createBook(name:String,currency:Currency,theme:String,language:String)=execute {
        preferences.select(repository.createBook(name,currency,theme,language))
    }
    val reminderBook=MutableStateFlow<String?>(null)
    val reminderTarget=MutableStateFlow<com.yohaku.expensetracker.domain.reminders.ReminderItem?>(null)
    fun openReminderBook(bookId:String,itemJson:String?=null)=execute(onSuccess={entry.value=null;backStack.clear();route.value="home";reminderBook.value=bookId}) {
        val book=repository.dao.book(bookId)?:error("账本不存在 / 帳簿がありません")
        require(book.initialized)
        val item=itemJson?.let{repository.json.decodeFromString<com.yohaku.expensetracker.domain.reminders.ReminderItem>(it)}
        require(item==null||item.bookId==bookId)
        preferences.select(bookId);reminderTarget.value=item
    }
    fun selectBook(bookId:String)=execute({entry.value=null;backStack.clear();route.value="home"}) { require(repository.dao.book(bookId)!=null){"账本不可用 / 帳簿が利用できません"};preferences.select(bookId) }
    val bookOperationReview=MutableStateFlow<BookLifecycleReview?>(null)
    val bookOperationRefreshed=MutableStateFlow(false)
    fun previewBookOperation(bookId:String,operation:BookOperation)=execute {
        bookOperationReview.value=repository.previewBookOperation(bookId,operation);bookOperationRefreshed.value=false
    }
    fun commitBookOperation() {
        val review=bookOperationReview.value?:return
        var saved=false
        execute(onSuccess={
            if(saved){bookOperationReview.value=null;entry.value=null;backStack.clear();route.value="book-management"}
        }) {
            saved=repository.commitBookOperation(review)
            if(!saved) {
                bookOperationReview.value=repository.previewBookOperation(review.scope.book.id,review.operation);bookOperationRefreshed.value=true
            }else {
                if(preferences.activeBook.first()==review.scope.book.id&&review.operation in listOf(BookOperation.ARCHIVE,BookOperation.TRASH,BookOperation.PURGE))preferences.clearSelection()
                repository.collectUnusedMedia()
                preferences.pruneBookPreferences(repository.books.first().map{it.id}.toSet())
                reminders?.rebuildSafely(false)
            }
        }
    }
    fun recentCategories(bookId:String,kind:EntryKind)=repository.dao.recentCategoryIds(bookId,kind.name)
    fun updateBook(book:BookEntity)=execute { repository.updateBook(book) }
    fun appearance(bookId:String,theme:String?=null,language:String?=null)=execute {repository.updateAppearance(bookId,theme,language)}
    fun saveSetup(draft:SetupDraft)=execute {preferences.saveSetup(draft)}
    fun setupStep(bookId:String,step:Int)=execute {repository.setupStep(bookId,step)}
    fun leaveEntry(bookId:String,keep:Boolean,target:String="home",onDone:()->Unit={}) {
        val draft=entry.value
        execute({
            entry.value=null
            val previous=backStack.indexOfLast{it==target}
            if(previous>=0)while(backStack.size>previous)backStack.removeAt(backStack.lastIndex) else backStack.clear()
            route.value=target;onDone();if(!keep)execute{repository.collectUnusedMedia()}
        }) {
            if(keep&&draft!=null)repository.saveDraft(bookId,draft) else repository.clearDraft(bookId)
        }
    }
    fun startEntry(snapshot:BookSnapshot,kind:EntryKind=EntryKind.EXPENSE,activityId:String?=null,treatFriendId:String?=null,merchantId:String?=null) {
        if(kind==EntryKind.TRANSFER){startTransfer(snapshot);return}
        entryReturnRoute.value=route.value
        val sceneId=if(kind==EntryKind.EXPENSE)activityId?:snapshot.features?.takeIf{it.activityMode}?.activeActivityId else null
        val scene=snapshot.activities.find{it.id==sceneId}
        val today=LocalDate.now(repository.clock)
        val date=scene?.let{today.coerceIn(LocalDate.parse(it.startDate),LocalDate.parse(it.endDate))}?:today
        val merchant=merchantId?.let{id->snapshot.merchants.first{it.id==id&&it.active}}
        entry.value=EntryDraft(merchant=merchant?.name.orEmpty(),merchantId=merchant?.id,kind=kind,date=date.toString(),accountId=snapshot.entryAccounts(kind).firstOrNull()?.id,activityId=sceneId,treatEnabled=treatFriendId!=null,treatFriends=listOfNotNull(treatFriendId))
        navigate("category-picker");persistDraft(snapshot.book.id)
    }
    fun resumeEntry(snapshot:BookSnapshot) { entryReturnRoute.value=route.value;entry.value=snapshot.draft;route.value=if(snapshot.draft?.categoryId==null&&snapshot.draft?.kind!=EntryKind.TRANSFER)"category-picker" else "entry";persistDraft(snapshot.book.id) }
    fun editEntry(snapshot:BookSnapshot,event:EventEntity) {
        if(event.kind==EntryKind.TRANSFER.name){editTransfer(snapshot,event);return}
        entryReturnRoute.value=route.value
        val settlement=snapshot.settlements.find{it.eventId==event.id}
        val treat=snapshot.treats.find{it.eventId==event.id}
        val use=snapshot.reserveUses.firstOrNull{it.eventId==event.id}
        entry.value=EntryDraft(reserveId=use?.reserveId,reserveAmount=use?.let{Money(Currency.valueOf(snapshot.book.currency),it.amountMinor).decimal.toPlainString()}.orEmpty(),treatEnabled=treat!=null,treatFriends=snapshot.allocations.filter{it.treatId==treat?.id}.mapNotNull{it.friendId},treatMode=treat?.let{TreatSplitMode.valueOf(it.mode)}?:TreatSplitMode.EQUAL,treatWeights=treat?.let{repository.json.decodeFromString<Map<String,String>>(it.weightsJson)}?:emptyMap(),id=event.id,kind=EntryKind.valueOf(event.kind),categoryId=event.categoryId,accountId=event.accountId,
            amount=Money(Currency.valueOf(event.currency),event.amountMinor).decimal.toPlainString(),
            baseAmount=Money(Currency.valueOf(snapshot.book.currency),event.baseMinor).decimal.toPlainString(),date=event.date,cardPaymentChoice=event.cardPaymentChoice,cardPaymentPeriods=event.cardPaymentPeriods?.toString().orEmpty(),merchant=event.merchant,merchantId=snapshot.eventMerchants.find{it.eventId==event.id}?.merchantId,note=event.note,historicalIncluded=event.historicalIncluded,originalCurrency=Currency.valueOf(event.currency),
            accountAmount=settlement?.let{Money(Currency.valueOf(it.accountCurrency),it.accountMinor).decimal.toPlainString()}.orEmpty(),
            settlementConfirmed=settlement?.confirmed?:true,settlementDate=settlement?.settlementDate?:event.date,rateSource=settlement?.rateSource?:"manual",rateDate=settlement?.rateDate?:event.date,originalEventId=settlement?.originalEventId,manualRate=settlement?.manualRate.orEmpty(),referenceRates=settlement?.referenceRatesJson?.let{repository.json.decodeFromString(it)}?:emptyList(),activityId=snapshot.eventActivities.find{it.eventId==event.id}?.activityId,occurrenceId=snapshot.occurrences.firstOrNull{it.eventId==event.id&&it.status in listOf("PENDING","COMPLETED")}?.id)
        navigate("entry");persistDraft(snapshot.book.id)
    }
    fun offerLocatedMerchant(bookId:String,session:String,candidates:List<MerchantCandidate>) {
        val d=entry.value?:return
        val current=(state.value as? AppState.Ready)?.snapshot?.takeIf{it.book.id==bookId}?:return
        val candidate=candidates.singleOrNull()?.takeIf{it.trustworthy}?:return
        if(d.mediaSession!=session||d.id!=null||d.kind!=EntryKind.EXPENSE||d.merchantManuallyEdited||d.merchant.isNotBlank()||d.merchantId!=null)return
        val merchant=current.merchants.firstOrNull{it.bookId==bookId&&it.id==candidate.merchant.id&&it.active}?:return
        if(current.merchantLocations.none{it==candidate.location})return
        changeEntry(bookId,d.copy(merchantId=merchant.id,merchant=merchant.name))
    }
    fun changeEntry(bookId:String,draft:EntryDraft) {if(savingEntry)return;entry.value=draft;persistDraft(bookId)}
    fun persistDraft(bookId:String) {
        val original=entry.value?:return
        val snapshot=(state.value as? AppState.Ready)?.snapshot?.takeIf{it.book.id==bookId}
        val d=if(!original.mediaInitialized&&snapshot!=null)original.copy(mediaInitialized=true,attachmentIds=original.id?.let{snapshot.entryAttachments(it).map{f->f.id}}.orEmpty(),receiptFileIds=original.id?.let{snapshot.entryReceiptImages(it).map{f->f.id}}.orEmpty())else original
        entry.value=d;execute{repository.saveDraft(bookId,entry.value?.takeIf{it.mediaSession==d.mediaSession}?:d)}
    }
    val openBudgetMonth=MutableStateFlow<String?>(null)
    val openActivity=MutableStateFlow<String?>(null)
    val openActivityFriend=MutableStateFlow<Pair<String,String>?>(null)
    val budgetReview=MutableStateFlow<BudgetChangeReview?>(null)
    val budgetReviewRefreshed=MutableStateFlow(false)
    val occurrenceLinkReview=MutableStateFlow<OccurrenceLinkReview?>(null)
    val occurrenceLinkRefreshed=MutableStateFlow(false)
    fun materializeMonth(bookId:String,month:String)=execute{repository.ensureOccurrences(bookId,month)}
    fun previewBudget(bookId:String,month:String,categoryId:String?,amount:String,currency:Currency)=execute {
        budgetReview.value=repository.previewBudget(bookId,month,categoryId,amount.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency)})
        budgetReviewRefreshed.value=false
    }
    fun confirmBudget(onDone:()->Unit={}) {
        val review=budgetReview.value?:return
        var saved=false
        execute(onSuccess={if(saved){budgetReview.value=null;onDone()}}) {
            saved=repository.commitBudget(review)
            if(!saved){budgetReview.value=repository.previewBudget(review.setting.bookId,review.setting.month,review.setting.categoryId,review.setting.amountMinor?.let{Money(Currency.valueOf(review.snapshot.book.currency),it)});budgetReviewRefreshed.value=true}
        }
    }
    fun previewOccurrenceLink(bookId:String,occurrenceId:String,eventId:String)=execute {
        occurrenceLinkReview.value=repository.previewOccurrenceLink(bookId,occurrenceId,eventId);occurrenceLinkRefreshed.value=false
    }
    fun confirmOccurrenceLink(onDone:()->Unit={}) {
        val review=occurrenceLinkReview.value?:return
        val paymentDraft=entry.value?.takeIf{it.id==null&&it.occurrenceId==review.occurrence.id}
        var saved=false
        execute(onSuccess={if(saved){
            occurrenceLinkReview.value=null
            if(paymentDraft!=null){entry.value=null;backStack.clear();route.value=entryReturnRoute.value;execute{repository.collectUnusedMedia()}}
            onDone()
        }}) {
            saved=repository.commitOccurrenceLink(review,discardPaymentDraft=paymentDraft!=null)
            if(!saved){occurrenceLinkReview.value=repository.previewOccurrenceLink(review.occurrence.bookId,review.occurrence.id,review.event.id);occurrenceLinkRefreshed.value=true}
        }
    }
    fun startOccurrence(snapshot:BookSnapshot,occurrence:OccurrenceEntity) {
        entryReturnRoute.value=route.value
        entry.value=EntryDraft(kind=EntryKind.EXPENSE,categoryId=occurrence.categoryId,accountId=occurrence.accountId,occurrenceId=occurrence.id,activityId=occurrence.activityId,
            amount=occurrence.amountMinor?.let{Money(Currency.valueOf(occurrence.currency),it).decimal.toPlainString()}.orEmpty(),
            baseAmount=occurrence.baseMinor?.let{Money(Currency.valueOf(snapshot.book.currency),it).decimal.toPlainString()}.orEmpty(),
            date=LocalDate.now(repository.clock).toString(),merchant=occurrence.name)
        navigate("entry");persistDraft(snapshot.book.id)
    }
    fun changeTransferAmount(bookId:String,d:EntryDraft,from:Currency,to:Currency,direction:String) {
        val confirmed=if(direction=="TO")d.toConfirmed else d.fromConfirmed
        if(confirmed || from!=to&&d.manualRate.isBlank()){changeEntry(bookId,d);return}
        val estimated=try {com.yohaku.expensetracker.domain.ledger.estimateTransfer(d,from,to,direction)}
        catch(_:IllegalArgumentException){if(direction=="TO")d.copy(toAmount="")else d.copy(amount="")}
        catch(_:IllegalStateException){if(direction=="TO")d.copy(toAmount="")else d.copy(amount="")}
        catch(_:ArithmeticException){if(direction=="TO")d.copy(toAmount="")else d.copy(amount="")}
        // Incomplete numeric input leaves the unconfirmed counterpart unknown, never an old estimate.
        changeEntry(bookId,estimated)
    }
    fun startTransfer(snapshot:BookSnapshot,fromId:String?=null) {
        val eligible=snapshot.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}
        val from=eligible.find{it.id==fromId}?:eligible.firstOrNull()
        entryReturnRoute.value=route.value
        entry.value=EntryDraft(kind=EntryKind.TRANSFER,accountId=from?.id,toAccountId=eligible.firstOrNull{it.id!=from?.id}?.id,
            date=LocalDate.now(repository.clock).toString(),merchant="")
        navigate("entry");persistDraft(snapshot.book.id)
    }
    private fun editTransfer(snapshot:BookSnapshot,event:EventEntity) {
        entryReturnRoute.value=route.value
        val transfer=snapshot.transfers.find{it.eventId==event.id}
        val sourcePosting=snapshot.storedPostings.find{it.eventId==event.id&&it.accountId==event.accountId}
        val toPosting=snapshot.storedPostings.find{it.eventId==event.id&&it.accountId!=event.accountId}
        val toAccountId=transfer?.toAccountId?:toPosting?.accountId
        val toCurrency=transfer?.toCurrency?:toPosting?.currency?:event.currency
        val component=snapshot.expenseComponents.firstOrNull{it.eventId==event.id}
        val treat=snapshot.treats.find{it.eventId==event.id}
        val use=snapshot.reserveUses.firstOrNull{it.eventId==event.id}
        entry.value=EntryDraft(reserveId=use?.reserveId,reserveAmount=use?.let{Money(Currency.valueOf(snapshot.book.currency),it.amountMinor).decimal.toPlainString()}.orEmpty(),treatEnabled=treat!=null,treatFriends=snapshot.allocations.filter{it.treatId==treat?.id}.mapNotNull{it.friendId},treatMode=treat?.let{TreatSplitMode.valueOf(it.mode)}?:TreatSplitMode.EQUAL,treatWeights=treat?.let{repository.json.decodeFromString<Map<String,String>>(it.weightsJson)}?:emptyMap(),id=event.id,kind=EntryKind.TRANSFER,accountId=event.accountId,toAccountId=toAccountId,
            amount=Money(Currency.valueOf(event.currency),event.amountMinor).decimal.toPlainString(),
            toAmount=Money(Currency.valueOf(toCurrency),transfer?.toMinor?:toPosting?.deltaMinor?:0).decimal.toPlainString(),
            date=event.date,toDate=transfer?.toDate?:toPosting?.date?:event.date,
            fromConfirmed=transfer?.fromConfirmed?:sourcePosting?.confirmed?:false,toConfirmed=transfer?.toConfirmed?:toPosting?.confirmed?:false,
            merchant=event.merchant,merchantId=snapshot.eventMerchants.find{it.eventId==event.id}?.merchantId,note=event.note,rateSource=snapshot.settlements.firstOrNull{it.eventId==event.id}?.rateSource?:"manual",referenceRates=snapshot.settlements.firstOrNull{it.eventId==event.id}?.referenceRatesJson?.let{repository.json.decodeFromString(it)}?:emptyList(),manualRate=transfer?.manualRate.orEmpty(),rateDate=transfer?.rateDate?:event.date,
            feeAmount=component?.let{Money(Currency.valueOf(it.originalCurrency),it.originalMinor).decimal.toPlainString()}.orEmpty(),
            feeCurrency=component?.let{Currency.valueOf(it.originalCurrency)},
            feeAccountAmount=component?.let{Money(Currency.valueOf(it.currency),it.amountMinor).decimal.toPlainString()}.orEmpty(),
            feeBaseAmount=component?.let{Money(Currency.valueOf(snapshot.book.currency),it.baseMinor).decimal.toPlainString()}.orEmpty(),
            feePosition=component?.let{TransferFeePosition.valueOf(it.position)}?:TransferFeePosition.INCLUDED_FROM,
            feeAccountId=component?.accountId,feeDate=component?.date?:event.date,feeConfirmed=component?.confirmed?:false)
        navigate("entry");persistDraft(snapshot.book.id)
    }
    fun startRefund(snapshot:BookSnapshot,original:EventEntity) {
        val settlement=snapshot.settlements.find{it.eventId==original.id}
        val refundIds=snapshot.settlements.filter{it.originalEventId==original.id}.map{it.eventId}.toSet()
        val remaining=original.amountMinor-snapshot.visibleEvents.filter{it.id in refundIds}.sumOf{it.amountMinor}
        if(remaining<=0)return
        val account=snapshot.accounts.first{it.id==original.accountId}
        fun proportion(value:Long)=value.toBigDecimal().multiply(remaining.toBigDecimal()).divide(original.amountMinor.toBigDecimal(),0,java.math.RoundingMode.HALF_UP).longValueExact()
        entryReturnRoute.value=route.value
        entry.value=EntryDraft(kind=EntryKind.REFUND,categoryId=original.categoryId,accountId=original.accountId,
            originalEventId=original.id,originalCurrency=Currency.valueOf(original.currency),
            amount=Money(Currency.valueOf(original.currency),remaining).decimal.toPlainString(),
            accountAmount=Money(Currency.valueOf(account.currency),proportion(settlement?.accountMinor?:original.amountMinor)).decimal.toPlainString(),
            baseAmount=Money(Currency.valueOf(snapshot.book.currency),proportion(original.baseMinor)).decimal.toPlainString(),
            date=LocalDate.now(repository.clock).toString(),merchant=original.merchant)
        navigate("entry");persistDraft(snapshot.book.id)
    }
    fun confirmActual(snapshot:BookSnapshot,event:EventEntity) {
        editEntry(snapshot,event)
        entry.value=entry.value!!.copy(settlementConfirmed=true)
        persistDraft(snapshot.book.id)
    }
    fun importFiles(context:android.content.Context,bookId:String,uris:List<android.net.Uri>,receipt:Boolean) {
        val session=entry.value?.mediaSession?:return
        execute(onSuccess={}) {
            for(uri in uris) {
                val imported=repository.importUri(context,bookId,session,uri,receipt)
                val latest=withContext(Dispatchers.Main){entry.value?.takeIf{it.mediaSession==session}?.let{current->
                    val next=if(receipt)current.copy(receiptFileIds=(current.receiptFileIds+imported.id).distinct())else current.copy(attachmentIds=(current.attachmentIds+imported.id).distinct())
                    entry.value=next;next
                }}
                latest?.let{repository.saveDraft(bookId,it)}
            }
        }
    }
    private fun entrySaved(bookId:String,d:EntryDraft) {
        entryReview.value=null;reviewRefreshed.value=false
        entry.value=null;backStack.clear();route.value=if(entryReturnRoute.value=="statements")"statements"else"ledger"
        execute{repository.collectUnusedMedia()}
        notice.value=AppNotice(++noticeSequence,bookId,if(d.id==null)"记录已保存" else "修改已保存到原条目",if(d.id==null)"記録を保存しました" else "元の記録に変更を保存")
    }
    fun dismissEntryReview(){if(!savingEntry){entryReview.value=null;reviewRefreshed.value=false}}
    fun saveEntry(bookId:String) {
        if(savingEntry)return
        val d=entry.value ?: return
        savingEntry=true
        var saved=false
        var review:LedgerChangeReview?=null
        execute(onSuccess={if(saved)entrySaved(bookId,d)else {entryReview.value=review;reviewRefreshed.value=false}},onComplete={savingEntry=false}) {
            val old=d.id?.let{repository.dao.event(bookId,it)}
            val account=repository.dao.account(bookId,d.accountId?:"")
            val linked=old?.let{repository.dao.refunds(bookId,it.id)}.orEmpty()
            val settlement=old?.let{repository.dao.settlement(bookId,it.id)}
            val needsReview=d.occurrenceId!=null || d.activityId!=null || d.kind in listOf(EntryKind.REFUND,EntryKind.TRANSFER) || old!=null&&(old.currency!=account?.currency || account?.currency!=repository.dao.book(bookId)?.currency || linked.isNotEmpty() || settlement?.confirmed==false || repository.dao.eventActivity(bookId,old.id)!=null)
            if(needsReview)review=repository.previewEntry(bookId,d)else {repository.saveEntry(bookId,d);saved=true}
        }
    }
    fun confirmEntryReview(bookId:String) {
        if(savingEntry)return
        val review=entryReview.value ?: return
        val d=entry.value ?: return
        savingEntry=true
        var saved=false
        var refreshed:LedgerChangeReview?=null
        execute(onSuccess={if(saved)entrySaved(bookId,d)else {entryReview.value=refreshed;reviewRefreshed.value=true}},onComplete={savingEntry=false}) {
            saved=repository.commitEntryReview(bookId,review)!=null
            if(!saved)refreshed=repository.previewEntry(bookId,d)
        }
    }
    fun discardEntry(bookId:String) =execute({entry.value=null;route.value="home";execute{repository.collectUnusedMedia()}}) {repository.clearDraft(bookId)}
    fun keepEntry(bookId:String) {persistDraft(bookId);entry.value=null;route.value="home"}
    companion object {
        fun factory(application:YohakuApplication)=object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = AppViewModel(application.repository,application.preferences,reminders=application.reminders,locationSource=com.yohaku.expensetracker.platform.ForegroundLocation(application)) as T
        }
    }
}
