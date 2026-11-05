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
data class OccurrenceConfirmation(val bookId:String,val occurrenceId:String,val draft:EntryDraft,val foreignCurrency:Boolean,val review:LedgerChangeReview?=null,val issue:String?=null,val refreshed:Boolean=false,val confirmingNegative:Boolean=false)
data class PendingEntrySave(val review:LedgerChangeReview,val needsReview:Boolean)
data class MediaImportProgress(val session:String,val current:Int,val total:Int)
data class MediaImportOption(val session:String,val preserveOriginal:Boolean)
data class AppNotice(val id:Long,val bookId:String,val zh:String,val ja:String)
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(val repository:LedgerRepository,val preferences:AppPreferences,val referenceRates:ReferenceRates=ReferenceRates(preferences,clock=repository.clock),val reminders:com.yohaku.expensetracker.platform.ReminderCoordinator?=null,val locationSource:com.yohaku.expensetracker.platform.CurrentLocationSource?=null,val cloud:com.yohaku.expensetracker.data.cloud.CloudRepository?=null):ViewModel() {
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
    val occurrenceConfirmation=MutableStateFlow<OccurrenceConfirmation?>(null)
    val amountFocusSession=MutableStateFlow<String?>(null)
    private var occurrencePreviewJob:Job?=null
    val entryReview=MutableStateFlow<LedgerChangeReview?>(null)
    val reviewRefreshed=MutableStateFlow(false)
    val entryReturnRoute=MutableStateFlow("home")
    val busy=MutableStateFlow(false)
    val photoSaveOriginal=preferences.photoSaveOriginal.map<Boolean,Boolean?>{it}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val mediaImportOption=MutableStateFlow<MediaImportOption?>(null)
    val mediaImportProgress=MutableStateFlow<MediaImportProgress?>(null)
    fun setMediaImportOption(session:String,preserveOriginal:Boolean){mediaImportOption.value=MediaImportOption(session,preserveOriginal)}
    private var savingEntry=false
    private var entryBaseline:EntryDraft?=null
    val negativeBalanceReview=MutableStateFlow<PendingEntrySave?>(null)
    fun entryHasManualInput()=entry.value?.hasManualInput(entryBaseline)==true
    fun markEntryBaseline(){entryBaseline=entry.value}
    private fun openDraft(bookId:String){persistDraft(bookId);markEntryBaseline()}
    private val backStack=mutableListOf<String>()
    fun navigate(page:String) {
        if(page==route.value)return
        if(page in listOf("home","analysis","life","accounts"))backStack.clear() else backStack.add(route.value)
        route.value=page
    }
    fun replaceRoute(page:String){route.value=page}
    fun back(){route.value=if(backStack.isEmpty())"home" else backStack.removeAt(backStack.lastIndex)}
    // Loading appears only initially and for an explicit retry. A subscription restarted after the app returns
    // from the background (camera, another app) keeps the last state, so open sheets and page positions stay composed.
    private var servedReadRequest=0L
    val state=combine(readRequests,today){request,date->request to date}.flatMapLatest{(request,date)->
        val retry=request!=servedReadRequest;servedReadRequest=request
        combine(repository.dao.bookOverviews(java.time.YearMonth.from(date).toString()),preferences.activeBook,preferences.setupDraft){overviews,id,first->Triple(overviews,overviews.map{it.book}.let{books->books.find{it.id==id&&it.deletedAt==null} ?: books.firstOrNull{!it.archived&&it.deletedAt==null}},first).also{selectedBookMetadata.value=it.second}}
        .flatMapLatest{(overviews,book,first)-> val books=overviews.map{it.book};if(book==null)flowOf<AppState>(AppState.Ready(books,null,first,overviews)) else repository.snapshot(book).map{AppState.Ready(books,it,first,overviews)}}
        .catch{emit(AppState.Failure(it.message ?: "数据库读取失败 / データ読込失敗"))}
        .onStart{if(retry)emit(AppState.Loading)}
    }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),AppState.Loading)
    val financialReadFailure=MutableStateFlow<String?>(null)
    val financialState=combine(state.map{(it as? AppState.Ready)?.snapshot?.book?.id}.distinctUntilChanged(),readRequests){bookId,_->bookId}
        .flatMapLatest{bookId->
            financialReadFailure.value=null
            if(bookId==null)flowOf(null)else repository.db.financialDao().graph(bookId)
                .catch{financialReadFailure.value=it.message ?: "金融资料读取失败 / 金融情報の読込に失敗しました";error.value=financialReadFailure.value;emit(null)}
        }
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
            entry.value=null;entryBaseline=null;negativeBalanceReview.value=null;entryReview.value=null;reviewRefreshed.value=false
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
        entry.value=snapshot.newEntryDraft(kind,LocalDate.now(repository.clock).toString(),activityId,treatFriendId,merchantId)
        navigate("category-picker");openDraft(snapshot.book.id)
    }
    fun resumeEntry(snapshot:BookSnapshot) { entryBaseline=null;entryReturnRoute.value=route.value;entry.value=snapshot.draft;route.value=if(snapshot.draft?.categoryId==null&&snapshot.draft?.kind!=EntryKind.TRANSFER)"category-picker" else "entry";persistDraft(snapshot.book.id) }
    fun editEntry(snapshot:BookSnapshot,event:EventEntity) {
        if(event.kind==EntryKind.TRANSFER.name){editTransfer(snapshot,event);return}
        entryReturnRoute.value=route.value
        entry.value=snapshot.editingEntryDraft(event)
        navigate("entry");openDraft(snapshot.book.id)
    }
    fun offerLocatedMerchant(bookId:String,session:String,candidates:List<MerchantCandidate>) {
        val d=entry.value?:return
        val current=(state.value as? AppState.Ready)?.snapshot?.takeIf{it.book.id==bookId}?:return
        val candidate=candidates.singleOrNull()?.takeIf{it.trustworthy}?:return
        if(d.mediaSession!=session||d.id!=null||d.kind!=EntryKind.EXPENSE||d.date!=LocalDate.now(repository.clock).toString()||d.merchantManuallyEdited||d.merchant.isNotBlank()||d.merchantId!=null)return
        val merchant=current.merchants.firstOrNull{it.bookId==bookId&&it.id==candidate.merchant.id&&it.active}?:return
        if(current.merchantLocations.none{it==candidate.location})return
        changeEntry(bookId,d.copy(merchantId=merchant.id,merchant=merchant.name,merchantLocated=true))
    }
    fun changeEntry(bookId:String,draft:EntryDraft) {
        if(savingEntry)return
        val snapshot=(state.value as? AppState.Ready)?.snapshot?.takeIf{it.book.id==bookId}
        var next=if(entry.value?.accountId!=draft.accountId&&snapshot!=null)snapshot.withRememberedCardPayment(draft)else draft
        if(next.date!=LocalDate.now(repository.clock).toString()&&next.merchantLocated&&!next.merchantManuallyEdited)next=next.copy(merchant="",merchantId=null,merchantLocated=false)
        if(next.date!=LocalDate.now(repository.clock).toString()||next.kind!=EntryKind.EXPENSE)next=next.copy(capturedPlace=null)
        negativeBalanceReview.value=null;entry.value=next;persistDraft(bookId)
    }
    fun switchEntryKind(snapshot:BookSnapshot,kind:EntryKind) {
        if(savingEntry)return
        val current=entry.value?:return
        if(current.id!=null||current.occurrenceId!=null||current.kind==EntryKind.REFUND||current.kind==kind)return
        val next=if(kind==EntryKind.TRANSFER)snapshot.newTransferDraft(current.date)else snapshot.withRememberedCardPayment(EntryDraft(kind=kind,date=current.date,accountId=snapshot.defaultEntryAccount(kind),activityId=if(kind==EntryKind.EXPENSE)snapshot.features?.takeIf{it.activityMode}?.activeActivityId else null))
        entry.value=next.copy(note=current.note,mediaSession=current.mediaSession,mediaInitialized=current.mediaInitialized,attachmentIds=current.attachmentIds,receiptFileIds=current.receiptFileIds)
        route.value=if(kind==EntryKind.TRANSFER)"entry"else"category-picker";openDraft(snapshot.book.id)
    }
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
        val paymentDraft=entry.value?.takeIf{it.isOccurrencePaymentDraft(review.occurrence.id)}
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
    fun startTemplate(snapshot:BookSnapshot,template:EntryTemplateEntity) {
        entryReturnRoute.value=route.value
        entry.value=snapshot.templateDraft(template,LocalDate.now(repository.clock).toString())
        amountFocusSession.value=entry.value!!.mediaSession
        navigate("entry");openDraft(snapshot.book.id)
    }
    fun previewOccurrence(snapshot:BookSnapshot,occurrence:OccurrenceEntity) {
        val draft=snapshot.occurrencePaymentDraft(occurrence)
        occurrenceConfirmation.value=OccurrenceConfirmation(snapshot.book.id,occurrence.id,draft,occurrence.currency!=snapshot.book.currency)
        refreshOccurrencePreview()
    }
    fun changeOccurrenceAmount(amount:String) {
        val current=occurrenceConfirmation.value?:return
        if(savingEntry)return
        // An edited foreign amount invalidates the rule's base-currency estimate.
        occurrenceConfirmation.value=current.copy(draft=current.draft.withOccurrenceAmount(amount,current.foreignCurrency),review=null,issue=null,confirmingNegative=false)
        refreshOccurrencePreview()
    }
    private fun refreshOccurrencePreview() {
        occurrencePreviewJob?.cancel()
        val current=occurrenceConfirmation.value?:return
        occurrencePreviewJob=viewModelScope.launch {
            delay(150)
            try {
                require(!current.foreignCurrency||current.draft.baseAmount.isNotBlank()){"请在完整表单中填写本位币金额 / フォームで基準通貨額を入力してください"}
                require(current.draft.cardPaymentChoice!="DEFERRED"){"指定月付款需要逐笔填写付款月与手续费，请修改后再记 / 指定月払いは支払月と手数料の入力が必要です。編集して記録してください"}
                val review=withContext(Dispatchers.IO){repository.previewEntry(current.bookId,current.draft)}
                if(occurrenceConfirmation.value==current)occurrenceConfirmation.value=current.copy(review=review,issue=null)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(occurrenceConfirmation.value==current)occurrenceConfirmation.value=current.copy(review=null,issue=e.message?:"无法预览，请修改后再记 / プレビューできません。編集して記録してください")}
        }
    }
    fun dismissOccurrenceConfirmation() {if(!savingEntry){occurrencePreviewJob?.cancel();occurrenceConfirmation.value=null}}
    fun dismissOccurrenceNegative() {if(!savingEntry)occurrenceConfirmation.value=occurrenceConfirmation.value?.copy(confirmingNegative=false)}
    fun confirmOccurrence(negativeAccepted:Boolean=false) {
        if(savingEntry)return
        val current=occurrenceConfirmation.value?:return
        val review=current.review?:return
        if(!negativeAccepted&&review.negativeBalanceChanges().isNotEmpty()) {
            occurrenceConfirmation.value=current.copy(confirmingNegative=true);return
        }
        savingEntry=true
        var saved=false
        execute(onSuccess={
            if(saved){occurrenceConfirmation.value=null;notice.value=AppNotice(++noticeSequence,current.bookId,"记录已保存","記録を保存しました")}
            else {occurrenceConfirmation.value=current.copy(review=null,refreshed=true,confirmingNegative=false);refreshOccurrencePreview()}
        },onComplete={savingEntry=false}) {
            saved=repository.commitOccurrencePayment(current.bookId,review)!=null
        }
    }
    fun startOccurrence(snapshot:BookSnapshot,occurrence:OccurrenceEntity,amount:String?=null,baseAmount:String?=null) {
        entryReturnRoute.value=route.value
        val initial=snapshot.occurrencePaymentDraft(occurrence)
        entry.value=initial.copy(amount=amount?:initial.amount,baseAmount=baseAmount?:initial.baseAmount)
        navigate("entry");openDraft(snapshot.book.id)
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
    fun startTransfer(snapshot:BookSnapshot,fromId:String?=null,toAccountId:String?=null) {
        entryReturnRoute.value=route.value
        entry.value=snapshot.newTransferDraft(LocalDate.now(repository.clock).toString(),fromId,toAccountId)
        navigate("entry");openDraft(snapshot.book.id)
    }
    private fun editTransfer(snapshot:BookSnapshot,event:EventEntity) {
        entryReturnRoute.value=route.value
        entry.value=snapshot.editingEntryDraft(event)
        navigate("entry");openDraft(snapshot.book.id)
    }
    fun startRefund(snapshot:BookSnapshot,original:EventEntity) {
        entryReturnRoute.value=route.value
        if(snapshot.settlements.filter{it.originalEventId==original.id}.map{it.eventId}.let{ids->snapshot.visibleEvents.filter{it.id in ids}.sumOf{it.amountMinor}}>=original.amountMinor)return
        entry.value=snapshot.refundDraft(original,LocalDate.now(repository.clock).toString())
        navigate("entry");openDraft(snapshot.book.id)
    }
    fun confirmActual(snapshot:BookSnapshot,event:EventEntity) {
        editEntry(snapshot,event)
        entry.value=entry.value!!.copy(settlementConfirmed=true)
        persistDraft(snapshot.book.id)
    }
    /** [session] is passed explicitly when a camera result arrives after process recreation; the stored draft keeps the same session. */
    fun importFiles(context:android.content.Context,bookId:String,uris:List<android.net.Uri>,receipt:Boolean,session:String?=entry.value?.mediaSession,onFinish:()->Unit={}) {
        if(uris.isEmpty()||session==null){onFinish();return}
        val selected=mediaImportOption.value?.takeIf{it.session==session}?.preserveOriginal?:photoSaveOriginal.value
        execute {
            val preserveOriginal=selected?:preferences.photoSaveOriginal.first()
            var success=0;var fallback=0;var failed=0;var firstFailure:String?=null
            try {
                uris.forEachIndexed{index,uri->
                    currentCoroutineContext().ensureActive();mediaImportProgress.value=MediaImportProgress(session,index+1,uris.size)
                    try {
                        val result=repository.importUri(context,bookId,session,uri,receipt,preserveOriginal);val imported=result.blob
                        success++;if(result.optimizationFailed)fallback++
                        withContext(Dispatchers.Main){entry.value?.takeIf{it.mediaSession==session}?.let{current->
                            entry.value=if(receipt)current.copy(receiptFileIds=(current.receiptFileIds+imported.id).distinct())else current.copy(attachmentIds=(current.attachmentIds+imported.id).distinct())
                        }}
                    }catch(e:CancellationException){throw e}catch(e:Exception){failed++;if(firstFailure==null)firstFailure=e.message?:"无法导入文件 / ファイルを取り込めません"}
                }
                withContext(Dispatchers.Main){
                    val zh=listOfNotNull("已导入 $success 个文件",fallback.takeIf{it>0}?.let{"$it 张图片无法优化，已原样保存"},firstFailure?.let{"失败 $failed 个：${it.substringBefore(" / ")}"}).joinToString("；")
                    val ja=listOfNotNull("$success 件を取り込みました",fallback.takeIf{it>0}?.let{"$it 枚は最適化できず、そのまま保存しました"},firstFailure?.let{"失敗 $failed 件：${it.substringAfter(" / ",it)}"}).joinToString("。")
                    notice.value=AppNotice(++noticeSequence,bookId,zh,ja)
                }
            }finally{mediaImportProgress.value=null;onFinish()}
        }
    }
    private fun entrySaved(bookId:String,d:EntryDraft) {
        negativeBalanceReview.value=null;entryBaseline=null;entryReview.value=null;reviewRefreshed.value=false
        entry.value=null;backStack.clear();route.value=if(entryReturnRoute.value=="statements")"statements"else"ledger"
        execute{repository.collectUnusedMedia()}
        notice.value=AppNotice(++noticeSequence,bookId,if(d.id==null)"记录已保存" else "修改已保存到原条目",if(d.id==null)"記録を保存しました" else "元の記録に変更を保存")
    }
    fun dismissEntryReview(){if(!savingEntry){entryReview.value=null;reviewRefreshed.value=false}}
    val commandPlanId=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val monthlyReviewMonth=MutableStateFlow<String?>(null)
    fun openMonthlyReview(month:String){monthlyReviewMonth.value=month;navigate("monthly-submit")}
    fun openCommandPlan(id:String){commandPlanId.value=id;navigate("command-preview")}
    fun saveEntry(bookId:String) {
        if(savingEntry)return
        val d=entry.value ?: return
        savingEntry=true
        var saved=false
        var review:LedgerChangeReview?=null
        var negative:PendingEntrySave?=null
        execute(onSuccess={if(saved)entrySaved(bookId,d)else {negativeBalanceReview.value=negative;entryReview.value=review;reviewRefreshed.value=false}},onComplete={savingEntry=false}) {
            val needsReview=repository.entryNeedsReview(bookId,d)
            val prepared=repository.previewEntry(bookId,d)
            if(prepared.negativeBalanceChanges().isNotEmpty())negative=PendingEntrySave(prepared,needsReview)
            else if(needsReview)review=prepared else {
                saved=repository.commitEntryReview(bookId,prepared)!=null
                if(!saved){val fresh=repository.previewEntry(bookId,d);if(fresh.negativeBalanceChanges().isNotEmpty())negative=PendingEntrySave(fresh,false)else review=fresh}
            }
        }
    }
    fun dismissNegativeBalance(){if(!savingEntry)negativeBalanceReview.value=null}
    fun confirmNegativeBalance(bookId:String) {
        val pending=negativeBalanceReview.value?:return
        if(savingEntry)return
        if(pending.needsReview){negativeBalanceReview.value=null;entryReview.value=pending.review;reviewRefreshed.value=false;return}
        val d=entry.value?:return
        savingEntry=true
        var saved=false;var stale=false
        execute(onSuccess={negativeBalanceReview.value=null;if(saved)entrySaved(bookId,d)},onComplete={savingEntry=false;if(stale)saveEntry(bookId)}){saved=repository.commitEntryReview(bookId,pending.review)!=null;stale=!saved}
    }
    fun confirmEntryReview(bookId:String) {
        if(savingEntry)return
        val review=entryReview.value ?: return
        val d=entry.value ?: return
        savingEntry=true
        var saved=false
        var refreshed:LedgerChangeReview?=null
        execute(onSuccess={if(saved)entrySaved(bookId,d)else {entryReview.value=refreshed?.takeIf{it.negativeBalanceChanges().isEmpty()};negativeBalanceReview.value=refreshed?.takeIf{it.negativeBalanceChanges().isNotEmpty()}?.let{PendingEntrySave(it,true)};reviewRefreshed.value=true}},onComplete={savingEntry=false}) {
            saved=repository.commitEntryReview(bookId,review)!=null
            if(!saved)refreshed=repository.previewEntry(bookId,d)
        }
    }
    fun discardEntry(bookId:String) =execute({entry.value=null;route.value="home";execute{repository.collectUnusedMedia()}}) {repository.clearDraft(bookId)}
    fun keepEntry(bookId:String) {persistDraft(bookId);entry.value=null;route.value="home"}
    companion object {
        fun factory(application:YohakuApplication)=object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = AppViewModel(application.repository,application.preferences,reminders=application.reminders,locationSource=com.yohaku.expensetracker.platform.ForegroundLocation(application),cloud=application.cloud) as T
        }
    }
}
