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
class AppViewModel(val repository:LedgerRepository,val preferences:AppPreferences):ViewModel() {
    private val writes=Mutex()
    private val readRequests=MutableStateFlow(0L)
    fun retryRead(){readRequests.update{it+1}}
    val error=MutableStateFlow<String?>(null)
    val notice=MutableStateFlow<AppNotice?>(null)
    private var noticeSequence=0L
    fun dismissNotice(id:Long){if(notice.value?.id==id)notice.value=null}
    val selectedBookMetadata=MutableStateFlow<BookEntity?>(null)
    val route=MutableStateFlow("home")
    val entry=MutableStateFlow<EntryDraft?>(null)
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
    val state=readRequests.flatMapLatest{
        combine(repository.dao.bookOverviews(),preferences.activeBook,preferences.setupDraft){overviews,id,first->Triple(overviews,overviews.map{it.book}.let{books->books.find{it.id==id} ?: books.firstOrNull()},first).also{selectedBookMetadata.value=it.second}}
        .flatMapLatest{(overviews,book,first)-> val books=overviews.map{it.book};if(book==null)flowOf<AppState>(AppState.Ready(books,null,first,overviews)) else repository.snapshot(book).map{AppState.Ready(books,it,first,overviews)}}
        .catch{emit(AppState.Failure(it.message ?: "数据库读取失败 / データ読込失敗"))}
        .onStart{emit(AppState.Loading)}
    }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),AppState.Loading)
    val setupDraft=state.mapNotNull{(it as? AppState.Ready)?.firstSetup}.map<SetupDraft,SetupDraft?>{it}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    init { execute { repository.purgeExpired() } }
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
    fun selectBook(bookId:String)=execute({entry.value=null;backStack.clear();route.value="home"}) { preferences.select(bookId) }
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
            route.value=target;onDone()
        }) {
            if(keep&&draft!=null)repository.saveDraft(bookId,draft) else repository.clearDraft(bookId)
        }
    }
    fun startEntry(snapshot:BookSnapshot,kind:EntryKind=EntryKind.EXPENSE) {
        entryReturnRoute.value=route.value
        entry.value=EntryDraft(kind=kind,date=LocalDate.now(repository.clock).toString(),accountId=snapshot.entryAccounts(kind).firstOrNull()?.id)
        navigate("category-picker");persistDraft(snapshot.book.id)
    }
    fun resumeEntry(snapshot:BookSnapshot) { entryReturnRoute.value=route.value;entry.value=snapshot.draft;route.value=if(snapshot.draft?.categoryId==null)"category-picker" else "entry" }
    fun editEntry(snapshot:BookSnapshot,event:EventEntity) {
        entryReturnRoute.value=route.value
        entry.value=EntryDraft(id=event.id,kind=EntryKind.valueOf(event.kind),categoryId=event.categoryId,accountId=event.accountId,
            amount=Money(Currency.valueOf(event.currency),event.amountMinor).decimal.toPlainString(),
            baseAmount=Money(Currency.valueOf(snapshot.book.currency),event.baseMinor).decimal.toPlainString(),date=event.date,merchant=event.merchant,note=event.note,historicalIncluded=event.historicalIncluded)
        navigate("entry");persistDraft(snapshot.book.id)
    }
    fun changeEntry(bookId:String,draft:EntryDraft) {if(savingEntry)return;entry.value=draft;persistDraft(bookId)}
    fun persistDraft(bookId:String) {val d=entry.value ?: return;execute {repository.saveDraft(bookId,d)} }
    fun saveEntry(bookId:String) {
        if(savingEntry)return
        val d=entry.value ?: return
        savingEntry=true
        execute(onSuccess={
            entry.value=null;backStack.clear();route.value="ledger"
            notice.value=AppNotice(++noticeSequence,bookId,if(d.id==null)"记录已保存" else "修改已保存到原条目",if(d.id==null)"記録を保存しました" else "元の記録に変更を保存")
        },onComplete={savingEntry=false}) {
            repository.saveEntry(bookId,d)
        }
    }
    fun discardEntry(bookId:String) =execute({entry.value=null;route.value="home"}) {repository.clearDraft(bookId)}
    fun keepEntry(bookId:String) {persistDraft(bookId);entry.value=null;route.value="home"}
    companion object {
        fun factory(application:YohakuApplication)=object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = AppViewModel(application.repository,application.preferences) as T
        }
    }
}
