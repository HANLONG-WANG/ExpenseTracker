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
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val storedFirstSetup by vm.setupDraft.collectAsStateWithLifecycle()
    val openingBook by vm.selectedBookMetadata.collectAsStateWithLifecycle()
    val ready=state as? AppState.Ready;val snapshot=ready?.snapshot
    val firstSetup=ready?.firstSetup ?: storedFirstSetup
    var sheet by rememberSaveable{mutableStateOf<String?>(null)}
    var requestedEntryKind by rememberSaveable{mutableStateOf(EntryKind.EXPENSE.name)}
    var detailReturnRoute by rememberSaveable{mutableStateOf("home")}
    var selectedEventId by rememberSaveable{mutableStateOf<String?>(null)}
    val selectedEvent=snapshot?.events?.find{it.id==selectedEventId}
    var selectedAccountId by rememberSaveable{mutableStateOf<String?>(null)}
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
            fun requestEntry(kind:EntryKind=EntryKind.EXPENSE){requestedEntryKind=kind.name;if(snapshot.draft!=null)sheet="existing-draft" else vm.startEntry(snapshot,kind)}
            BackHandler(enabled=sheet!=null || route !in listOf("home","analysis","life","accounts")) {
                if(sheet!=null)sheet=null else if(openEntry){leaveTarget=entryReturnRoute;sheet="leave-entry"} else vm.back()
            }
            val contentScroll=rememberSaveable(baseRoute,book.id,saver=ScrollState.Saver){ScrollState(0)}
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.weight(1f).verticalScroll(contentScroll).testTag("page-scroll")) {
                YAppHeader(book.name,{if(openEntry)sheet="books" else vm.navigate("books")},{vm.appearance(book.id,language=if(book.language=="zh")"ja" else "zh")},{if(openEntry){leaveTarget="settings";sheet="leave-entry"}else vm.navigate("settings")})
                Column(Modifier.padding(pageContentPadding()).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                    PageEdition(baseRoute)
                    if(baseRoute !in listOf("home","analysis","life","accounts")) YBackBar(book.name,{if(openEntry){leaveTarget="home";sheet="leave-entry"}else vm.back()})
                    when(baseRoute) {
                        "home"->HomeScreen(snapshot,{vm.navigate(it)},{requestEntry()},{vm.resumeEntry(snapshot)},{openRecord(it)},{editBookId=book.id;sheet="rename-book"})
                        "books"->BooksScreen(ready.overviews,book.id,{vm.selectBook(it)},{sheet="new-book"},{editBookId=it;sheet="rename-book"})
                        "accounts"->AccountsScreen(snapshot,{editAccount=null;sheet="account"},{selectedAccountId=it.id;vm.navigate("account-detail")})
                        "account-detail"->snapshot.accounts.find{it.id==selectedAccountId}?.let{a->AccountDetail(snapshot,a,{editAccount=a;sheet="account"},{openRecord(it)})}
                        "ledger"->LedgerScreen(snapshot,{openRecord(it)},{requestEntry(it)})
                        "categories"->CategoryManagement(snapshot,{parent,kind->categoryParent=parent;categoryKind=kind;editCategory=null;sheet="category"},{c->editCategory=c;categoryParent=c.parentId;categoryKind=EntryKind.valueOf(c.kind);sheet="category"},{c->vm.execute{vm.repository.toggleCategoryFavorite(book.id,c.id)}})
                        "settings"->SettingsScreen(snapshot,{vm.appearance(book.id,theme=it)},{vm.appearance(book.id,language=if(book.language=="zh")"ja" else "zh")},{vm.navigate("categories")},{vm.navigate("books")},{vm.navigate("trash")},{editBookId=book.id;sheet="rename-book"})
                        "trash"->TrashScreen(snapshot,vm)
                        "analysis"->BasicAnalysis(snapshot,{openRecord(it)},{vm.navigate("categories")})
                        "life"->{YTitle(word("把生活，记在一起。","暮らしも、一緒に。"),"LIFE BEYOND NUMBERS",pageFlow=true);Panel{YText(word("还没有活动","シーンはまだありません"),17);YText(word("活动中的消费与请客往来会显示在这里。","シーンの支出とごちそうをここにまとめます。"),12,color=t.muted)}}
                    }
                    PageFootnote(baseRoute)
                }
                }
                YBottomNav(baseRoute,{p->if(openEntry){leaveTarget=p;sheet="leave-entry"}else vm.navigate(p)},{if(!openEntry)requestEntry()})
            }
            if(route=="detail"&&sheet==null)selectedEvent?.let{event->
                YSheet(word("这一笔的详情","この一筆の詳細"),{vm.back()}){Detail(snapshot,event,{vm.editEntry(snapshot,event)},{sheet="delete"},vm)}
            }
            if(openEntry&&sheet==null) {
                val activeDraft=draft!!
                YSheet(if(route=="category-picker")word("先选一个分类","まずカテゴリを選ぶ") else if(draft?.id!=null)word("编辑这笔记录","この記録を編集") else word("记一笔","一筆を記録"),{leaveTarget=entryReturnRoute;sheet="leave-entry"}){
                    if(route=="category-picker")CategoryPicker(vm,snapshot,activeDraft,{vm.changeEntry(book.id,it)},{categoryParent=it;categoryKind=activeDraft.kind;editCategory=null;sheet="category"},{vm.navigate("entry")})
                    else EntryForm(snapshot,activeDraft,{vm.changeEntry(book.id,it)},{vm.saveEntry(book.id)},{editAccount=null;sheet="account"},{vm.navigate("category-picker")},busy,{sheet="entry-themes"},{sheet="books"},{
                        if(entryReturnRoute!="detail"){selectedEventId=activeDraft.id;detailReturnRoute=entryReturnRoute}
                        vm.leaveEntry(book.id,false,entryReturnRoute){if(entryReturnRoute!="detail")vm.navigate("detail")}
                    })
                }
            }
            when(sheet) {
                "entry-themes"->YSheet(word("切换主题，保留这笔输入","入力を保持してテーマを切替"),{sheet=null}) {
                    Column(Modifier.fillMaxWidth()){themeChoices().forEach{(id,label)->EntryThemeOption(label,{vm.appearance(book.id,theme=id);sheet=null})}}
                }
                "account"->AccountEditor(snapshot,vm,{sheet=null},editAccount,debtOnly=if(openEntry&&draft?.kind==EntryKind.INCOME)false else null)
                "category"->CategoryEditor(snapshot,categoryKind,categoryParent,editCategory,vm,{sheet=null},
                    onUse=if(openEntry&&route=="category-picker"&&editCategory==null){id->vm.entry.value?.let{vm.changeEntry(book.id,it.copy(categoryId=id));vm.navigate("entry")};sheet=null} else null)
                "books"->YSheet(word("切换账本","帳簿を切替"),{sheet=null}) {
                    ready.books.forEach{b->YButton(b.name+(if(b.id==book.id)" ✓" else ""),{if(b.id==book.id)sheet=null else if(openEntry){switchTarget=b.id;sheet="switch-entry"}else{vm.selectBook(b.id);sheet=null}},Modifier.fillMaxWidth())}
                    YButton(word("＋ 创建独立账本","＋ 独立した帳簿を作成"),{if(openEntry){leaveTarget="new-book";sheet="leave-entry"}else sheet="new-book"},primary=true)
                }
                "existing-draft"->YSheet(word("还有一笔草稿","下書きが残っています"),{sheet=null}) {
                    YButton(word("继续这笔草稿","下書きを続ける"),{vm.resumeEntry(snapshot);sheet=null},primary=true)
                    YButton(word("放弃草稿，记新的一笔","下書きを破棄して新しく記録"),{vm.execute({vm.startEntry(snapshot.copy(draft=null),EntryKind.valueOf(requestedEntryKind));sheet=null}){vm.repository.clearDraft(book.id)}})
                }
                "new-book"->BookEditor(null,book,vm,{sheet=null})
                "rename-book"->(ready.books.find{it.id==editBookId} ?: book).let{editing->BookEditor(editing,book,vm,{sheet=null},hasRecords=ready.overviews.firstOrNull{it.book.id==editing.id}?.recordCount?.let{it>0} ?: snapshot.events.isNotEmpty())}
                "leave-entry"->YSheet(word("这笔还未保存","この記録は未保存です"),{sheet=null}) {
                    YText(word("可以保留草稿，下次继续。原记录不会被未保存内容修改。","下書きを残して後で続行できます。元の記録は変更されません。"))
                    YButton(word("保留草稿并返回","下書きを残して戻る"),{vm.leaveEntry(book.id,true,if(leaveTarget=="new-book")"home" else leaveTarget){sheet=if(leaveTarget=="new-book")"new-book" else null}},primary=true)
                    YButton(word("放弃修改","変更を破棄"),{vm.leaveEntry(book.id,false,if(leaveTarget=="new-book")"home" else leaveTarget){sheet=if(leaveTarget=="new-book")"new-book" else null}})
                    YLink(word("继续编辑","編集を続ける"),{sheet=null})
                }
                "switch-entry"->YSheet(word("切换前保存草稿？","切替前に下書きを残しますか？"),{sheet=null}) {
                    val target=switchTarget!!
                    YButton(word("保留草稿并切换","下書きを残して切替"),{val d=draft!!;vm.execute({vm.entry.value=null;vm.navigate("home");sheet=null}){vm.repository.saveDraft(book.id,d);vm.preferences.select(target)}},primary=true)
                    YButton(word("放弃草稿并切换","下書きを破棄して切替"),{vm.execute({vm.entry.value=null;vm.navigate("home");sheet=null}){vm.repository.clearDraft(book.id);vm.preferences.select(target)}})
                    YLink(word("继续编辑","編集を続ける"),{sheet=null})
                }
                "delete"->selectedEvent?.takeIf{it.deletedAt==null}?.let{event->YSheet(word("移至回收站","ごみ箱に移動"),{sheet=null}) {
                    YText(word("确认后从正常记录与统计中移除，30天内可以恢复。","通常の記録と集計から除外。30日以内は復元できます。"))
                    YText(snapshot.path(event.categoryId)+" · "+Money(Currency.valueOf(event.currency),event.amountMinor).display(),18,number=true)
                    EntryImpactPreview(snapshot,event,snapshot.removalImpact(event.id),YearMonth.now(vm.repository.clock).toString())
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

@Composable private fun Detail(s:BookSnapshot,e:EventEntity,onEdit:()->Unit,onDelete:()->Unit,vm:AppViewModel) {
    RecordDetailCore(s,e,onEdit)
    if(e.currency!=s.book.currency)YText(word("本位币统计：","基準通貨集計：")+Money(Currency.valueOf(s.book.currency),e.baseMinor).display(),12,number=true)
    if(e.historicalIncluded)YText(word("已包含在期初余额","期首残高に含まれます"),11)
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

@Composable internal fun EntryForm(s:BookSnapshot,d:EntryDraft,onChange:(EntryDraft)->Unit,onSave:()->Unit,onAccount:()->Unit,onCategory:()->Unit,busy:Boolean,onTheme:()->Unit,onBooks:()->Unit,onCancelEdit:()->Unit) {
    val t=LocalYohaku.current;val eligible=s.entryAccounts(d.kind);val a=eligible.find{it.id==d.accountId};val currency=a?.currency ?: s.book.currency
    EntrySummary(s.book.name,s.path(d.categoryId),d.id!=null,onCategory)
    EntryTools(s.book.name,onBooks,onTheme)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(bottom=4.dp)){
            YField(word("金额","金額")+" · "+currency,d.amount,{onChange(d.copy(amount=it))},numeric=true,emphasizeAmount=true,amountSymbol=Currency.valueOf(currency).symbol.takeLast(1),displayLabel=word("账户实际入账金额","口座の実際の計上額")+" · "+currency)
            val baseText=if(currency==s.book.currency)d.amount else d.baseAmount
            val base=if(baseText.isBlank()){if(currency==s.book.currency)Money(Currency.valueOf(s.book.currency),0) else null} else runCatching{Money.parse(baseText,Currency.valueOf(s.book.currency))}.getOrNull()
            YText(word("本位币参考","基準通貨の参考")+" "+(base?.display()?:"—"),if(t.id=="journal")11 else 9,Modifier.padding(top=16.dp).testTag("entry-base-caption"),color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
            if(d.kind==EntryKind.EXPENSE&&currency=="JPY")Box(Modifier.padding(top=13.dp)){EntryQuickAmount(d.amount){onChange(d.copy(amount=it))}}
        }
        if(currency!=s.book.currency)YField(word("本位币统计金额","基準通貨の集計額")+" · "+s.book.currency,d.baseAmount,{onChange(d.copy(baseAmount=it))},numeric=true)
        if(eligible.isNotEmpty())YSelect(if(d.kind==EntryKind.INCOME)word("到账账户","入金口座") else word("付款账户","支払口座"),d.accountId,eligible.map{it.id as String? to it.name},{onChange(d.copy(accountId=it))})
        else {YText(if(d.kind==EntryKind.INCOME)word("还没有到账账户","入金口座がありません") else word("还没有付款账户","支払口座がありません"),14);YButton(word("添加第一个账户","最初の口座を追加"),onAccount,primary=true)}
        YField(if(d.kind==EntryKind.INCOME)word("名称 / 说明","名称 / 内容") else word("商户 / 买了什么","お店 / 買ったもの"),d.merchant,{onChange(d.copy(merchant=it))})
        var activeDate by remember{mutableStateOf<Int?>(null)}
        YDateFields(listOf(word("日期","日付") to d.date),activeDate,{activeDate=it},{_,date->onChange(d.copy(date=date))})
        Column(Modifier.fillMaxWidth()){
            EntryNotes(d.id,d.note,{onChange(d.copy(note=it))})
            HistoricalOpeningToggle(s.book.startDate,d.historicalIncluded){onChange(d.copy(historicalIncluded=it))}
        }
        Box(Modifier.padding(top=25.dp)){EntrySaveArea(d.id!=null,busy,a!=null,onSave,onCancelEdit)}
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

@Composable private fun BookEditor(original:BookEntity?,current:BookEntity,vm:AppViewModel,onClose:()->Unit,hasRecords:Boolean=false) {
    var name by rememberSaveable(original?.id){mutableStateOf(original?.name.orEmpty())};var currency by rememberSaveable(original?.id){mutableStateOf(Currency.valueOf(original?.currency?:current.currency))}
    var budget by rememberSaveable(original?.id){mutableStateOf(original?.budgetMinor?.let{Money(currency,it).decimal.toPlainString()}.orEmpty())}
    var saving by remember(original?.id){mutableStateOf(false)}
    YSheet(if(original==null)word("创建独立账本","独立した帳簿を作成") else word("账本资料","帳簿情報"),onClose) {
        YField(word("账本名称","帳簿名"),name,{name=it})
        if(original==null||!hasRecords)YSelect(word("统计本位币","基準通貨"),currency,Currency.entries.map{it to it.name},{currency=it})
        else YText(word("本位币：","基準通貨：")+original.currency+word(" · 已有记录，不可更改"," · 記録済みのため変更できません"))
        if(original!=null)YField(word("月预算 / 可稍后设置","月予算・後から設定可"),budget,{budget=it},numeric=true)
        YText(word("账户、分类、余额和记录全都独立。","口座、カテゴリ、残高、記録はすべて独立です。"),12)
        YButton(word("保存账本","帳簿を保存"),{
            if(saving)return@YButton
            saving=true
            val submittedName=name;val submittedCurrency=currency;val submittedBudget=budget
            vm.execute(onSuccess=onClose,onComplete={saving=false}){if(original==null)vm.preferences.select(vm.repository.createBook(submittedName,submittedCurrency,current.theme,current.language)) else vm.repository.updateBook(original.copy(name=submittedName,currency=submittedCurrency.name,budgetMinor=submittedBudget.takeIf{it.isNotBlank()}?.let{Money.parse(it,submittedCurrency).minor}))}
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

@Composable internal fun AccountDetail(s:BookSnapshot,a:AccountEntity,onEdit:()->Unit,onEvent:(EventEntity)->Unit) {
    val amount=Money(Currency.valueOf(a.currency),s.balance(a)).display()
    YLead(a.name,a.currency+" · "+if(AccountType.valueOf(a.type).debt)word("账户原币欠款","口座の元通貨借入残高") else word("账户原币余额","口座の元通貨残高"),"AVAILABLE BALANCE",amount,word("本位币参考","基準通貨参考")+" "+if(a.currency==s.book.currency)amount else "—",pageFlow=true)
    Box(Modifier.padding(top=12.dp)){YButton(word("编辑账户","口座を編集"),onEdit,Modifier.fillMaxWidth().testTag("account-detail-edit"))}
    ThemeSection(word("账户流水","口座の記録"),"RECENT")
    val entries=s.visibleEvents.filter{it.accountId==a.id}
    TransactionList(s,entries,onEvent,emptyContent={YEmpty(word("还没有流水，余额来自期初状态。","記録はまだありません。残高は期首情報から。"))})
}

@Composable internal fun entryKindName(kind:EntryKind)=when(kind){
    EntryKind.EXPENSE->word("支出","支出")
    EntryKind.INCOME->word("收入","収入")
    EntryKind.TRANSFER->word("转账","振替")
    EntryKind.REPAYMENT->word("还款","返済")
    EntryKind.BORROWING->word("借入","借入")
    EntryKind.REFUND->word("退款","返金")
}
