package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewModelScope
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.format.DateTimeFormatter
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal val LocalFinancialDraft=staticCompositionLocalOf<FinancialDraftSession?>{null}

internal class FinancialDraftSession(val vm:AppViewModel,val bookId:String,val context:FinancialDraftContext,basis:String,stored:FinancialDraftEntity?,var close:()->Unit) {
    internal val fields=mutableStateMapOf<String,JsonElement>().apply{if(stored!=null)putAll(vm.repository.json.decodeFromString<Map<String,JsonElement>>(stored.valuesJson))}
    internal val defaults=mutableMapOf<String,JsonElement>()
    var resumed by mutableStateOf(stored!=null);private set
    private var currentBasis by mutableStateOf(basis)
    val changed get()=originalBasis!=currentBasis
    var saving by mutableStateOf(false);private set
    var failure by mutableStateOf<String?>(null);private set
    private var generation=0
    @Volatile var consumed=false;private set
    var hasInput=stored!=null;private set
    private val originalBasis=stored?.basis?:basis
    val key=bookId+"/"+context.slot()
    fun resume(basis:String,onClose:()->Unit){resumed=true;currentBasis=basis;close=onClose}
    fun updateBasis(basis:String){currentBasis=basis}
    private val writes=Mutex()
    private fun payload()=vm.repository.json.encodeToString(defaults+fields.toMap())
    private suspend fun store(payload:String)=vm.repository.storeFinancialDraft(context,bookId,payload,originalBasis)
    suspend fun flush()=writes.withLock{if(hasInput&&!consumed)store(payload())}
    fun changed() {
        hasInput=true
        val version=++generation;val snapshot=payload();saving=true;failure=null
        vm.viewModelScope.launch(Dispatchers.IO) {
            try{writes.withLock{if(!consumed&&version==generation)store(snapshot)}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){withContext(Dispatchers.Main){failure=e.message?:"草稿保存失败 / 下書き保存失敗";vm.error.value=failure}}
            finally{withContext(Dispatchers.Main){if(version==generation)saving=false}}
        }
    }
    suspend fun commit(action:suspend()->String?):String?=writes.withLock {
        // Flush the latest input before trying the transaction; rejection/failure keeps it resumable.
        store(payload())
        vm.repository.commitFinancialDraft(bookId,context.slot(),action).also{if(it!=null){consumed=true;generation++;vm.financialDraftSessions.remove(key,this)}}
    }
    suspend fun discard()=writes.withLock {
        vm.repository.db.financialDao().clearDraft(bookId,context.slot());consumed=true;generation++;vm.financialDraftSessions.remove(key,this)
    }
}

@Composable internal inline fun <reified T> financialField(key:String,noinline initial:()->T):ReadWriteProperty<Any?,T> {
    val session=requireNotNull(LocalFinancialDraft.current)
    val json=session.vm.repository.json
    if(key !in session.defaults)session.defaults[key]=json.encodeToJsonElement(initial())
    return object:ReadWriteProperty<Any?,T> {
        override fun getValue(thisRef:Any?,property:KProperty<*>)=json.decodeFromJsonElement<T>(session.fields[key]?:session.defaults.getValue(key))
        override fun setValue(thisRef:Any?,property:KProperty<*>,value:T) {
            val encoded=json.encodeToJsonElement(value)
            if(encoded!=(session.fields[key]?:session.defaults[key])){session.fields[key]=encoded;session.changed()}
        }
    }
}

internal suspend fun commitFinancialDraft(session:FinancialDraftSession,action:suspend()->String?):String?=session.commit(action)
internal suspend fun discardFinancialDraft(vm:AppViewModel,bookId:String,context:FinancialDraftContext) {
    val session=vm.financialDraftSessions[bookId+"/"+context.slot()]
    if(session!=null)session.discard()else vm.repository.db.financialDao().clearDraft(bookId,context.slot())
}

private sealed interface DraftRead {
    data object Loading:DraftRead
    data class Ready(val draft:FinancialDraftEntity?):DraftRead
    data class Failure(val message:String):DraftRead
}
private sealed interface DraftRowsRead {
    data object Loading:DraftRowsRead
    data class Ready(val rows:List<FinancialDraftEntity>):DraftRowsRead
    data class Failure(val message:String):DraftRowsRead
}

@Composable internal fun FinancialDraftHost(s:BookSnapshot,context:FinancialDraftContext,vm:AppViewModel,onClose:()->Unit,content:@Composable ()->Unit) {
    key(s.book.id,context){FinancialDraftHostContent(s,context,vm,onClose,content)}
}

@Composable private fun FinancialDraftHostContent(s:BookSnapshot,context:FinancialDraftContext,vm:AppViewModel,onClose:()->Unit,content:@Composable ()->Unit) {
    val finance by vm.financialState.collectAsStateWithLifecycle()
    val limitGraph by vm.creditLimitState.collectAsStateWithLifecycle()
    var attempt by remember{mutableIntStateOf(0)}
    val read by produceState<DraftRead>(DraftRead.Loading,s.book.id,context,attempt) {
        value=DraftRead.Loading
        value=try{DraftRead.Ready(vm.repository.db.financialDao().draft(s.book.id,context.slot()))}
            catch(e:CancellationException){throw e}
            catch(e:Exception){DraftRead.Failure(e.message?:"草稿读取失败 / 下書き読込失敗")}
    }
    when(val result=read) {
        DraftRead.Loading->YSheet(word("金融草稿","金融の下書き"),onClose){YText(word("正在读取…","読込中…"),12)}
        is DraftRead.Failure->YSheet(word("金融草稿","金融の下書き"),onClose){YText(result.message,12);YButton(word("重新读取","再読込"),{attempt++})}
        is DraftRead.Ready->{
            val a=s.accounts.firstOrNull{it.id==context.accountId}
            val f=finance?.takeIf{it.book.id==s.book.id}
            val limits=limitGraph?.takeIf{it.book.id==s.book.id}
            if(a!=null&&(f==null||limits==null)){YSheet(word("金融草稿","金融の下書き"),onClose){YText(word("正在读取金融资料…","金融情報を読込中…"),12);YButton(word("重新读取","再読込"),vm::retryRead)};return}
            val basis=listOf(s.book.currency,a?.currency,a?.openingDate,a?.openingMinor,s.events.firstOrNull{it.id==context.targetId}?.revision,s.contracts.firstOrNull{it.id==context.contractId},f?.plans?.firstOrNull{it.id==context.targetId}?.revision,f?.bills?.firstOrNull{it.id==context.billId}?.revision,f?.rules?.filter{it.accountId==a?.id&&it.mode==context.mode}?.maxByOrNull{it.version}?.id,s.expectedIncomes.firstOrNull{it.id==context.targetId},limits?.configs?.filter{it.accountId==a?.id}?.maxByOrNull{it.version}?.id,limits?.groups?.firstOrNull{it.id==context.targetId}?.revision).joinToString("|")
            val session=remember(s.book.id,context,attempt){
                val key=s.book.id+"/"+context.slot()
                vm.financialDraftSessions[key]?.takeUnless{it.consumed||!it.hasInput}?.also{it.resume(basis,onClose)}
                    ?:FinancialDraftSession(vm,s.book.id,context,basis,result.draft,onClose).also{vm.financialDraftSessions[key]=it}
            }
            SideEffect{session.updateBasis(basis)}
            DisposableEffect(session){vm.activeFinancialDraft.value=session;onDispose{if(vm.activeFinancialDraft.value===session)vm.activeFinancialDraft.value=null}}
            CompositionLocalProvider(LocalFinancialDraft provides session){content()}
        }
    }
}

@Composable internal fun FinancialDraftNotice() {
    val session=LocalFinancialDraft.current?:return
    val busy by session.vm.busy.collectAsStateWithLifecycle()
    YText(when{session.failure!=null->session.failure!!;session.saving->word("正在保存草稿…","下書きを保存中…");session.resumed->word("已恢复草稿","下書きを再開しました");else->word("输入自动保存为草稿","入力は下書きに自動保存")},11,Modifier.testTag("financial-draft-status"),color=LocalYohaku.current.muted)
    if(session.changed)YText(word("原资料已变化，保留草稿输入；请重新预览后核对。","元の情報が変わっています。下書きの入力を保持しています。再度プレビューして確認してください。"),11,color=LocalYohaku.current.warm)
    YButton(word("放弃草稿并关闭","下書きを破棄して閉じる"),{session.vm.execute(onSuccess=session.close){session.discard()}},Modifier.testTag("financial-draft-discard"),enabled=!busy)
}

@Composable private fun financialDraftLabel(kind:String)=when(kind) {
    "RULE"->word("金融规则","金融条件")
    "MANUAL"->word("已知逐期计划","既知の各回予定")
    "PREDICTION"->word("预测计划","予測プラン")
    "POOL"->word("账户余额池","口座の残高プール")
    "POOL_PAYMENT"->word("共同月付款变更","共通月払いの変更")
    "ARRANGEMENT"->word("付款方案变更","支払プランの変更")
    "ARRANGEMENT_REVIEW"->word("已办理安排重核","手続き済み予定の再確認")
    "BORROW"->word("实际借入 / 期初合同","実際の借入・期首契約")
    "REPAY"->word("实际还款","実際の返済")
    "EXPECTED"->word("预计收入 / 到账","収入予定・実入金")
    "BILL"->word("本期平台账单","当期の返済先請求")
    "LIMIT_CONFIG"->word("额度与占用基准","利用枠の設定")
    "LIMIT_GROUP"->word("跨账户共享额度","口座間の共通枠")
    "LIMIT_FACT"->word("平台额度与恢复","返済先の枠・回復")
    else->kind
}

@Composable internal fun FinancialDraftList(s:BookSnapshot,account:AccountEntity?,vm:AppViewModel,onResume:(FinancialDraftContext)->Unit) {
    var attempt by remember{mutableIntStateOf(0)}
    val read by remember(s.book.id,account?.id,attempt){vm.repository.db.financialDao().drafts(s.book.id,account?.id)
        .map<List<FinancialDraftEntity>,DraftRowsRead>{DraftRowsRead.Ready(it)}
        .catch{emit(DraftRowsRead.Failure(it.message?:"草稿读取失败 / 下書き読込失敗"))}
    }.collectAsStateWithLifecycle(DraftRowsRead.Loading)
    var expanded by rememberSaveable(s.book.id,account?.id){mutableStateOf(false)}
    val rows=when(val result=read){DraftRowsRead.Loading->return;is DraftRowsRead.Ready->result.rows;is DraftRowsRead.Failure->{YText(result.message,12);YButton(word("重新读取草稿","下書きを再読込"),{attempt++});return}}
    if(rows.isEmpty())return
    YButton(word("未完成金融草稿","未完了の金融下書き")+" · ${rows.size}"+if(expanded)" −"else" +",{expanded=!expanded},Modifier.testTag("financial-drafts-open"))
    if(expanded)rows.forEach{row->
        val context=vm.repository.json.decodeFromString<FinancialDraftContext>(row.contextJson)
        Panel {
            YText(financialDraftLabel(context.kind),14)
            YText(Instant.ofEpochMilli(row.updatedAt).atZone(vm.repository.clock.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),11)
            YButton(word("继续编辑","編集を続ける"),{onResume(context)},Modifier.testTag("financial-draft-resume-${context.kind}"))
        }
    }
}

@Composable internal fun FinancialDraftResume(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,context:FinancialDraftContext,vm:AppViewModel,onClose:()->Unit,onBill:((String)->Unit)?=null,onParent:((String)->Unit)?=null,onLimits:(()->Unit)?=null) {
    val plan=f.plans.firstOrNull{it.id==context.targetId&&it.accountId==a.id&&(it.active||context.kind=="ARRANGEMENT_REVIEW")}
    val contract=f.contracts.firstOrNull{it.id==context.contractId&&it.accountId==a.id}
    val event=s.visibleEvents.firstOrNull{it.id==context.targetId}
    val limits by vm.creditLimitState.collectAsStateWithLifecycle()
    val l=limits?.takeIf{it.book.id==s.book.id}
    if(context.kind.startsWith("LIMIT_")&&l==null){YSheet(word("金融草稿","金融の下書き"),onClose){YText(word("正在读取额度资料…","利用枠情報を読込中…"),12);YButton(word("重新读取","再読込"),vm::retryRead)};return}
    val available=when(context.kind) {
        "ARRANGEMENT"->plan!=null&&(plan.origin=="POOL"||f.arrangements.none{it.originalPlanId==plan.id})
        "ARRANGEMENT_REVIEW"->plan!=null&&f.arrangements.any{it.targetPlanId==plan.id}
        "POOL_PAYMENT"->plan?.origin=="POOL"
        "PREDICTION","POOL"->context.targetId==null||plan!=null
        "BORROW","REPAY"->(context.targetId==null||event!=null)&&(context.contractId==null||contract!=null)&&(context.billId==null||f.bills.any{it.id==context.billId&&it.active})
        "RULE","MANUAL"->true
        "BILL"->f.bills.any{it.id==context.billId&&it.active}
        "LIMIT_CONFIG"->true
        "LIMIT_GROUP"->context.targetId==null||l?.groups?.any{it.id==context.targetId}==true
        "LIMIT_FACT"->when(context.mode){"recovery"->event!=null;"group-observation"->l?.groups?.any{it.id==context.targetId}==true;else->true}
        else->false
    }
    if(!available) {
        YSheet(word("原资料待核对","元の情報を要確認"),onClose) {
            YText(word("草稿仍保留。原记录已删除、计划已替换或清偿目标已不可用；请先恢复或核对原资料。","下書きは保持しています。元の記録が削除済み、予定が置換済み、または返済先が利用できません。元の情報を復元・確認してください。"),12)
            YButton(word("放弃这份草稿","この下書きを破棄"),{vm.execute(onSuccess=onClose){discardFinancialDraft(vm,s.book.id,context)}},Modifier.testTag("financial-draft-unavailable-discard"))
        };return
    }
    when(context.kind) {
        "RULE"->{val mode=FinanceMode.valueOf(requireNotNull(context.mode));val rule=f.rules.filter{it.accountId==a.id&&it.mode==mode.name}.maxByOrNull{it.version};FinancialRuleEditor(s,a,mode,rule?.let{vm.repository.json.decodeFromString(it.json)},vm,onClose,onLimits)}
        "MANUAL"->ManualFinancialPlanEditor(s,a,vm,onClose)
        "PREDICTION"->FinancialPredictionEditor(s,a,f,plan,vm,onClose)
        "POOL"->FinancialPoolEditor(s,a,f,plan,vm,onClose,onBill)
        "POOL_PAYMENT"->FinancialPoolPaymentEditor(s,a,f,requireNotNull(plan),vm,onClose)
        "ARRANGEMENT"->FinancialArrangementEditor(s,a,f,requireNotNull(plan),vm,onClose)
        "ARRANGEMENT_REVIEW"->FinancialReconciliationEditor(s,a,f,requireNotNull(plan),vm,onClose,onBill,onParent)
        "BORROW"->FinancialBorrowingEditor(s,a,vm,onClose,context.opening,contract,context.targetId)
        "REPAY"->FinancialRepaymentEditor(s,a,f,vm,onClose,context.targetId,context.billId,context.contractId)
        "BILL"->FinancialBillConfirmationEditor(s,a.id,f.bills.first{it.id==context.billId},Currency.valueOf(a.currency),vm,onClose)
        "LIMIT_CONFIG"->if(l!=null)CreditLimitConfigEditor(s,a,l.configs.filter{it.accountId==a.id}.maxByOrNull{it.version},vm,onClose)
        "LIMIT_GROUP"->if(l!=null)CreditLimitGroupEditor(s,a,l.groups.firstOrNull{it.id==context.targetId},l,vm,onClose)
        "LIMIT_FACT"->if(l!=null)CreditLimitFactEditor(s,a,l,requireNotNull(context.mode),context.targetId,vm,onClose)
    }
}
