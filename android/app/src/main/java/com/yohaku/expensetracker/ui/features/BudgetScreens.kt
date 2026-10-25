package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.YearMonth

@Composable internal fun MonthChooser(month:String,onMonth:(String)->Unit,previousTag:String="month-previous",nextTag:String="month-next",monthTag:String="month-current",previousEnabled:Boolean=true) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        YButton("‹",{onMonth(YearMonth.parse(month).minusMonths(1).toString())},Modifier.testTag(previousTag),enabled=previousEnabled)
        YText(month,19,Modifier.weight(1f).testTag(monthTag),number=true,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        YButton("›",{onMonth(YearMonth.parse(month).plusMonths(1).toString())},Modifier.testTag(nextTag))
    }
}
@Composable internal fun BudgetValues(projection:BudgetProjection) {
    val currency=projection.currency
    val unknown=word("未设置 / 待补齐","未設定・未補足")
    fun value(amount:Long?)=amount?.let{Money(currency,it).display()}?:unknown
    RecordFacts(listOf(
        word("总预算","全体予算") to value(projection.limitMinor),
        word("预算内已花","予算内の支出") to Money(currency,projection.spentMinor).display(),
        word("独立活动净消费","独立シーンの純支出") to Money(currency,projection.independentMinor).display(),
        word("全部净消费","すべての純支出") to Money(currency,projection.allExpenseMinor).display(),
        word("预算余额","予算残額") to value(projection.remainingMinor),
        word("待付固定支出预留","固定費の未払い予定") to value(projection.fixedReserveMinor),
        word("本月活动预算预留","今月のシーン予算の確保") to value(projection.activityReserveMinor),
        word("全部活动资金预留","全シーンの資金確保") to value(projection.fundReserveMinor),
        word("预留后可支配","予定を除いた残額") to value(projection.availableMinor)
    ),audit=true)
}
@Composable internal fun BudgetChanges(before:BudgetProjection,after:BudgetProjection) {
    val unknown=word("未设置 / 待补齐","未設定・未補足")
    fun value(n:Long?,currency:Currency=before.currency)=n?.let{Money(currency,it).display()}?:unknown
    RecordFacts(listOf(
        word("预算","予算") to (value(before.limitMinor)+" → "+value(after.limitMinor,after.currency)),
        word("预算内消费","予算内の支出") to (value(before.spentMinor)+" → "+value(after.spentMinor,after.currency)),
        word("待付预留","未払い予定") to (value(before.reserveMinor)+" → "+value(after.reserveMinor,after.currency)),
        word("预留后可支配","予定を除いた残額") to (value(before.availableMinor)+" → "+value(after.availableMinor,after.currency))
    ),audit=true)
}
@Composable internal fun BudgetScreen(s:BookSnapshot,vm:AppViewModel,onRecurring:()->Unit,onCategories:()->Unit) {
    val today by vm.today.collectAsStateWithLifecycle()
    var month by rememberSaveable(s.book.id){mutableStateOf(YearMonth.from(today).toString())}
    val requestedMonth by vm.openBudgetMonth.collectAsStateWithLifecycle()
    LaunchedEffect(requestedMonth){requestedMonth?.let{month=it;vm.openBudgetMonth.value=null}}
    var editor by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var editorAmount by rememberSaveable(s.book.id){mutableStateOf("")}
    fun openEditor(categoryId:String?) {
        editorAmount=s.budgetLimit(month,categoryId)?.let{Money(Currency.valueOf(s.book.currency),it).decimal.toPlainString()}.orEmpty()
        editor=categoryId?:""
    }
    val review by vm.budgetReview.collectAsStateWithLifecycle()
    val refreshed by vm.budgetReviewRefreshed.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(s.book.id,month,s.recurrences){vm.materializeMonth(s.book.id,month)}
    val projection=s.budgetProjection(month)
    val missing=word("未设置 / 待补齐","未設定・未補足")
    fun value(amount:Long?)=amount?.let{Money(projection.currency,it).display()}?:missing
    YLead(word("日常与活动，分开留。","日常とシーン、分けて残す。"),word("月预算内支出与独立活动支出分开表达。","月予算の支出と独立シーン支出を区別。"),"THE MONTHLY BALANCE",value(projection.remainingMinor),word("本月预算余额","今月の予算残額"),amountIsText=projection.remainingMinor==null,pageFlow=true)
    MonthChooser(month,{month=it})
    val provisional=s.provisionalExpenseCount(month)
    Box(Modifier.padding(top=5.dp,bottom=if(provisional>0)5.dp else 20.dp)) {
    BudgetEquation {
        RecordFacts(listOf(word("月预算","月予算") to value(projection.limitMinor),word("预算内已花","予算内支出") to ("− "+value(projection.spentMinor)),word("预算内待付预留","予算内予定の確保") to ("− "+value(projection.reserveMinor)),word("预留后还能花","確保後に使える") to value(projection.availableMinor)),total=true)
    }
    }
    if(provisional>0)BudgetEstimateNote(provisional)
    Box(Modifier.padding(top=if(provisional>0)15.dp else 0.dp)) {YButton(word("调整本月预算","この月の予算を変更"),{openEditor(null)},Modifier.testTag("budget-edit-total"),primary=true)}
    IndependentBudgetNote(Money(projection.currency,projection.independentMinor).display())
    ThemeSection(word("月分类预算","月のカテゴリ予算"),"CATEGORY LIMITS",word("管理分类","カテゴリ管理"),onCategories,previousBottomPadding=8)
    YText(word("一级预算含二级与未细分消费；二级预算是其中专项，不重复相加。","親予算は子と内訳未分類を含む。子予算は内訳で、二重加算しません。"),11,color=LocalYohaku.current.muted,lineHeightMultiplier=1.9f)
    val roots=s.categories.filter{s.categoryEnabled(it.id)&&it.kind==EntryKind.EXPENSE.name&&it.parentId==null}
    if(roots.isEmpty())YEmpty(word("先创建自己的支出分类。","自分の支出カテゴリから。"),onCategories,word("创建分类","カテゴリを作成"))
    val ordered=roots.flatMap{root->listOf(root)+s.categories.filter{it.active&&it.parentId==root.id}}
    val projections=ordered.associate{it.id to s.budgetProjection(month,it.id)}
    BudgetLimitList(ordered,ordered.map{projections.getValue(it.id).let{p->if(p.limitMinor==null)Money(p.currency,p.spentMinor).display()else p.remainingMinor?.let{n->Money(p.currency,n).display()}?:word("待补齐","未補足")}}) {category,modifier->
        val p=projections.getValue(category.id)
        BudgetLimitRow(category.id,category.name,category.parentId!=null,p.spentMinor,p.limitMinor,Money(p.currency,p.spentMinor).display(),p.limitMinor?.let{Money(p.currency,it).display()}?:word("未设置","未設定"),p.remainingMinor?.let{Money(p.currency,it).display()}?:word("待补齐","未補足"),{openEditor(category.id)},modifier)
    }
    roots.forEach{root->
        val parentLimit=s.budgetLimit(month,root.id)
        val childLimits=s.categories.filter{it.active&&it.parentId==root.id}.mapNotNull{s.budgetLimit(month,it.id)}.sum()
        if(parentLimit!=null&&childLimits>parentLimit)YText(word("已设置的子预算合计超过 ${root.name} 的父预算。金额保留，父预算仍包含所有子级。","設定済みの子予算合計が ${root.name} の親予算を超えています。金額を保持し、親にはすべての子を含みます。"),11,color=LocalYohaku.current.warm)
    }
    ThemeSection(word("预留来源明细","確保の内訳"),"PLANNED USES")
    RecordFacts(listOf(word("待付固定支出预留","固定費の未払い予定") to value(projection.fixedReserveMinor),word("本月活动预算预留","今月のシーン予算の確保") to value(projection.activityReserveMinor),word("全部活动资金预留","全シーンの資金確保") to value(projection.fundReserveMinor)),audit=true)
    YButton(word("查看固定支出预留","固定費の予定を見る"),onRecurring)
    YText(word("收入到账不会自动增加预算。未单独设置的后续月份沿用最近一次设置。","入金しても予算は自動で増えません。未設定の後続月は直近の設定を引き継ぎます。"),11,color=LocalYohaku.current.muted)
    if(editor!=null&&review==null)BudgetEditor(s,month,editor!!.ifEmpty{null},editorAmount,{editorAmount=it},vm,{editor=null})
    review?.takeIf{it.setting.bookId==s.book.id}?.let{r->YSheet(word("预算变化预览","予算の変化を確認"),{vm.budgetReview.value=null}) {
        if(refreshed)YText(word("资料已变化，请确认重新计算的结果。","情報が変わりました。再計算した結果を確認してください。"),12,color=LocalYohaku.current.warm)
        ThemeAuditPanel(r.setting.month+" · "+(r.setting.categoryId?.let{s.path(it)}?:word("总预算","全体予算")),Modifier.testTag("budget-review-panel")) {
            BudgetChanges(r.before,r.after)
        }
        r.setting.categoryId?.let{id->
            val category=s.categories.first{it.id==id};val parent=s.categories.first{it.id==(category.parentId?:id)}
            val next=s.copy(budgets=s.budgets.filter{it.month!=r.setting.month||it.scope!=r.setting.scope}+r.setting)
            val limit=next.budgetLimit(r.setting.month,parent.id)
            val childTotal=s.categories.filter{it.parentId==parent.id&&it.active}.mapNotNull{next.budgetLimit(r.setting.month,it.id)}.sum()
            if(limit!=null&&childTotal>limit)YText(word("子预算合计超过父预算。保留输入，父预算仍覆盖所有子级。","子予算合計が親予算を超えています。入力を保持し、親はすべての子を含みます。"),12,color=LocalYohaku.current.warm)
        }
        YText(word("后续未单独设置的月份会沿用此金额；不改变账户余额。","後続の未設定月はこの金額を引き継ぎます。口座残高は変わりません。"),11,color=LocalYohaku.current.muted)
        YButton(word("确认预算调整","予算変更を確定"),{vm.confirmBudget{editor=null}},primary=true,enabled=!busy)
        YLink(word("返回继续编辑","戻って編集"),{vm.budgetReview.value=null})
    }}
}
@Composable private fun BudgetEditor(s:BookSnapshot,month:String,categoryId:String?,amount:String,onAmount:(String)->Unit,vm:AppViewModel,onClose:()->Unit) {
    val currency=remember(s.book.id,month,categoryId){Currency.valueOf(s.book.currency)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(if(categoryId==null)word("调整月预算","月予算を変更")else s.path(categoryId),onClose) {
        YText(month,18,number=true)
        YField(word("预算金额 / 留空取消设置","予算額・空欄で未設定")+" · "+currency.name,amount,onAmount,numeric=true)
        val previous=s.budgetLimit(YearMonth.parse(month).minusMonths(1).toString(),categoryId)
        YButton(word("沿用上月金额","前月の金額を引継ぐ"),{onAmount(previous?.let{Money(currency,it).decimal.toPlainString()}.orEmpty())},enabled=!busy&&previous!=null)
        if(categoryId!=null)YText(word("父级覆盖子级与未细分消费，子级只是专项限制。","親は子と未分類分を含み、子は個別の上限です。"),11,color=LocalYohaku.current.muted)
        YButton(word("预览预算调整","予算変更を確認"),{vm.previewBudget(s.book.id,month,categoryId,amount,currency)},primary=true,enabled=!busy)
    }
}
