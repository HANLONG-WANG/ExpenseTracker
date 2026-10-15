package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

@Composable internal fun analysisModuleName(module:AnalysisModule)=when(module) {
    AnalysisModule.CATEGORIES->word("分类","カテゴリ");AnalysisModule.MERCHANTS->word("商户","お店");AnalysisModule.TREND->word("趋势","推移");AnalysisModule.ACTIVITIES->word("活动","シーン");AnalysisModule.COMPARISON->word("同期对比与原因","同期間の比較と理由")
}
@Composable internal fun analysisModuleNote(module:AnalysisModule)=when(module) {
    AnalysisModule.CATEGORIES->word("钱花在什么用途","お金の使い道")
    AnalysisModule.MERCHANTS->word("常去哪里，花了多少","よく行くお店と金額")
    AnalysisModule.TREND->word("每天的节奏与逐月变化","日々のペースと月ごとの変化")
    AnalysisModule.ACTIVITIES->word("每段生活单独看清","シーンごとの支出を見る")
    AnalysisModule.COMPARISON->word("同进度比较，追溯变化原因","同じ進捗で比較し、変化の理由を見る")
}
internal fun analysisModuleIcon(module:AnalysisModule)=when(module){AnalysisModule.CATEGORIES->"bag";AnalysisModule.MERCHANTS->"coffee";AnalysisModule.TREND,AnalysisModule.COMPARISON->"chart";AnalysisModule.ACTIVITIES->"life"}
@Composable private fun analysisCategoryItems(s:BookSnapshot,report:AnalysisReport,currency:Currency)=s.categoryGroups(report).map{g->AnalysisListItem(g.key,if(g.key=="FEES")word("明确费用","確定した手数料")else s.categories.first{it.id==g.key}.name,Money(currency,g.amount).display(),g.count,if(report.total==0L)"—"else g.amount.toBigDecimal().multiply(100.toBigDecimal()).divide(report.total.toBigDecimal(),0,java.math.RoundingMode.HALF_UP).toPlainString()+"%",if(g.key=="FEES")"transfer"else"bag")}
@Composable private fun analysisMerchantItems(s:BookSnapshot,report:AnalysisReport,currency:Currency)=report.groups{it.merchantKey}.map{g->AnalysisListItem(g.key,s.merchantLabel(g.key).ifBlank{word("未填写商户","お店未入力")},Money(currency,g.amount).display(),g.count)}
@Composable internal fun AnalysisScreen(s:BookSnapshot,vm:AppViewModel,onRecord:(EventEntity)->Unit,onCategories:()->Unit) {
    val today by vm.today.collectAsStateWithLifecycle()
    var start by rememberSaveable(s.book.id){mutableStateOf(YearMonth.from(today).atDay(1).toString())}
    var end by rememberSaveable(s.book.id){mutableStateOf(YearMonth.from(today).atEndOfMonth().toString())}
    var category by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var merchant by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var activity by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var tab by rememberSaveable(s.book.id){mutableStateOf("spend")}
    var editor by rememberSaveable(s.book.id){mutableStateOf(false)}
    var initialModule by rememberSaveable(s.book.id){mutableStateOf<AnalysisModule?>(null)}
    var filtering by rememberSaveable(s.book.id){mutableStateOf(false)}
    var detail by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var comparisonPeriod by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    val filter=AnalysisFilter(start,end,category,merchant,activity)
    val report=s.analysis(filter)
    val modules=vm.repository.analysisModules(s);val pinned=vm.repository.analysisPins(s)
    val ordered=modules.filter{it in pinned}+modules.filter{it !in pinned}
    val currency=Currency.valueOf(s.book.currency)
    LaunchedEffect(s.book.id,start,end,tab,s.recurrences) {
        if(tab!="spend")vm.execute {
            var month=YearMonth.parse(start.take(7));val last=YearMonth.parse(end.take(7))
            while(month<=last){vm.repository.ensureOccurrences(s.book.id,month.toString());month=month.plusMonths(1)}
        }
    }
    YTabs(listOf("spend" to word("消费","支出"),"budget" to word("预算","予算"),"future" to word("未来","未来")),tab,{tab=it})
    YText(start+" — "+end,13)
    YButton(word("筛选时间、分类与商户","期間・カテゴリ・お店で絞込"),{filtering=true},Modifier.testTag("analysis-filter"))
    if(category!=null||merchant!=null||activity!=null)RecordFacts(listOf(word("分类","カテゴリ") to (category?.let{s.path(it)}?:word("全部","すべて")),word("商户","お店") to (merchant?.let{s.merchantLabel(it).ifBlank{word("未填写","未入力")}}?:word("全部","すべて")),word("活动","シーン") to (activity?.let{id->s.activities.find{it.id==id}?.name?:word("日常","日常")}?:word("全部","すべて"))))
    when(tab) {
        "spend"->{
            if(s.analysisPreferences==null||modules.isEmpty()) {
                YLead(word("你想先看清什么？","まず、何を見たい？"),word("分类、商户、趋势和活动，选择你关心的内容与顺序。","カテゴリ・お店・推移・シーン。気になる内容と順序を選んで。"),"YOUR OWN PERSPECTIVE",pageFlow=true)
                @Composable fun choice(module:AnalysisModule,modifier:Modifier=Modifier){AnalysisChoice(module.name,module.ordinal,analysisModuleName(module),analysisModuleNote(module),analysisModuleIcon(module),modifier,{initialModule=module;editor=true})}
                if(LocalYohaku.current.id=="journal")AnalysisModule.entries.forEach{choice(it)}
                else AnalysisModule.entries.chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(if(LocalYohaku.current.id=="magazine")20.dp else 13.dp)){row.forEach{choice(it,Modifier.weight(1f))};if(row.size==1)Spacer(Modifier.weight(1f))}}
                YButton(word("选择分析模块","分析項目を選ぶ"),{initialModule=null;editor=true},primary=true)
                YText(word("未选择时不替你预设重点，偏好按账本保存。","未選択の視点は自動設定しません。帳簿ごとに保存。"),11,color=LocalYohaku.current.muted)
            }else {
                val provisional=report.events.any{event->s.settlements.any{it.eventId==event.id&&!it.confirmed}}
                YLead(word("从你的重点，看日常。","自分の視点で、日々を見る。"),"$start — $end · ${report.events.size} "+word("笔记录 · 含独立活动实际消费","件の記録・独立シーンの実支出を含む"),"MONEY IN CONTEXT",Money(currency,report.total).display(),if(provisional)word("净消费含预计换算","純支出に仮換算を含む")else word("本位币净消费","基準通貨の純支出"),pageFlow=true)
                YButton(word("自定义分析","分析をカスタマイズ"),{initialModule=null;editor=true},Modifier.testTag("analysis-customize"))
                RecordFacts(listOf(word("原消费","元の支出") to Money(currency,report.gross).display(),word("已到账退款 · 归原消费期间","入金済み返金・元の購入期間へ") to Money(currency,report.refunds).display(),word("明确实际费用","確定した実際の手数料") to Money(currency,report.fees).display()))
            }
            ordered.forEach{module->
                AnalysisModuleHeading(analysisModuleName(module),analysisModuleNote(module),module==ordered.firstOrNull()||module in pinned)
                when(module) {
                    AnalysisModule.CATEGORIES->{
                        val groups=s.categoryGroups(report)
                        if(groups.isEmpty())YEmpty(word("这个区间暂无分类消费。","この期間の支出はありません。"))
                        else AnalysisCategoryList(analysisCategoryItems(s,report,currency),{detail=if(it=="FEES")"fees"else"category:$it"})
                        YLink(word("管理分类","カテゴリ管理"),onCategories)
                    }
                    AnalysisModule.MERCHANTS->{
                        val groups=report.groups{it.merchantKey}
                        if(groups.isEmpty())YEmpty(word("记录商户后在这里查看。","お店を記録すると表示されます。"))
                        else AnalysisMerchantList(analysisMerchantItems(s,report,currency),Money(currency,report.total).display(),{detail="merchant:$it"})
                        YLink(word("商户与保存地点","お店と保存した場所"),{vm.navigate("merchants")})
                    }
                    AnalysisModule.ACTIVITIES->AnalysisActivityModule(s,report,currency,{detail="activity:$it"},{vm.openActivity.value=it;vm.navigate("life")})
                    AnalysisModule.TREND->AnalysisTrend(report,currency,{detail=it})
                    AnalysisModule.COMPARISON->AnalysisComparisonView(s,filter,today,currency,{period,key->comparisonPeriod=period;detail=key})
                }
            }
        }
        "budget"->AnalysisBudgetView(s,filter,{category=it},{month->vm.openBudgetMonth.value=month;vm.navigate("budget")})
        "future"->AnalysisFutureView(s,filter,{vm.navigate("recurring")},{vm.openActivity.value=it;vm.navigate("life")},onRecord)
    }
    YLink(word("商户资料与保存地点","お店の情報と保存した場所"),{vm.navigate("merchants")})
    YLink(word("朋友名册","友達の名簿"),{vm.navigate("friends")})
    YLink(word("小票原图工作台","レシート原画像の一覧"),{vm.navigate("receipts")})
    if(editor)AnalysisPreferenceEditor(s,vm,initialModule,filter,today,{editor=false;initialModule=null})
    if(filtering)AnalysisFilterEditor(s,filter,vm,{filtering=false},{value->start=value.start;end=value.end;category=value.categoryId;merchant=value.merchantKey;activity=value.activityId;filtering=false})
    detail?.let{key->
        val selectedReport=comparisonPeriod?.let{period->val comparison=s.compareAnalysis(filter,today);if(period=="previous")comparison.previous else comparison.current}?:report
        AnalysisDetail(s,selectedReport,key,onRecord,{detail=null;comparisonPeriod=null})
    }
}

@Composable internal fun AnalysisRankRow(name:String,amount:Long,count:Int,index:Int,largest:Long,currency:Currency,onClick:()->Unit) {
    val t=LocalYohaku.current
    when(t.id) {
        "magazine"->Column(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=15.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){YText("${index+1}".padStart(2,'0'),14,color=t.muted);Column(Modifier.weight(1f)){YText(name,16);YText("$count "+word("笔记录","件の記録"),9,color=t.muted)};YText(Money(currency,amount).display(),22,number=true)}
            SpendMeter(abs(amount.toFloat())/largest,Modifier.padding(top=12.dp),index)
        }
        "journal"->Panel(Modifier.clickable(onClick=onClick)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){YText("☑",20,color=t.accent);Column(Modifier.weight(1f)){YText(name,18);YText("$count "+word("次 · 留下的记录","回・残した記録"),11,color=t.muted)};YText(Money(currency,amount).display(),21,number=true,color=t.accent)}
            SpendMeter(abs(amount.toFloat())/largest,Modifier.padding(top=8.dp),index)
        }
        else->Panel(Modifier.clickable(onClick=onClick)) {
            YText("${index+1} / MY LITTLE RECEIPTS",8,color=t.muted)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){Column(Modifier.weight(1f)){YText(name,18);YText("$count "+word("笔日常","件の日常"),10,color=t.muted)};YText(Money(currency,amount).display(),23,number=true)}
            SpendMeter(abs(amount.toFloat())/largest,Modifier.padding(top=10.dp),index)
        }
    }
}
@Composable private fun AnalysisTrend(report:AnalysisReport,currency:Currency,onDetail:(String)->Unit) {
    var mode by rememberSaveable{mutableStateOf("month")}
    YSelect(word("趋势粒度","推移の単位"),mode,listOf("month" to word("逐月","月ごと"),"day" to word("逐日","日ごと")),{mode=it})
    val values=report.groups{if(mode=="month")it.date.take(7)else it.date}.sortedBy{it.key}
    if(values.isEmpty())YEmpty(word("有记录后，时间里的变化会显示在这里。","記録すると時間の変化が表示されます。"))
    var selected by rememberSaveable(mode){mutableStateOf<String?>(null)}
    if(values.isNotEmpty())AnalysisTrendChart(values.map{TrendPoint(it.key,it.amount,Money(currency,it.amount).display())},selected?.takeIf{key->values.any{it.key==key}}?:values.last().key,{key->selected=key;onDetail((if(mode=="month")"month:"else "day:")+key)})
}
@Composable private fun AnalysisComparisonView(s:BookSnapshot,filter:AnalysisFilter,today:LocalDate,currency:Currency,onDetail:((String,String)->Unit)?=null) {
    val comparison=s.compareAnalysis(filter,today)
    Panel {
        YText(if(comparison.partialMonth)word("本月按相同已过天数比较","今月は同じ経過日数で比較")else if(filter.start.endsWith("-01")&&filter.end==YearMonth.parse(filter.start.take(7)).atEndOfMonth().toString())word("完整月份比较","月全体を比較")else word("相同长度期间比较","同じ長さの期間を比較"),17)
        YText(comparison.current.filter.start+" — "+comparison.current.filter.end,11,Modifier.testTag("comparison-current-period"))
        YText(Money(currency,comparison.current.total).display(),27,number=true)
        YText(word("对照：","比較：")+comparison.previousStart+" — "+comparison.previousEnd,11,Modifier.testTag("comparison-previous-period"))
        YText(Money(currency,comparison.previous.total).display()+" → "+Money(currency,comparison.current.total).display(),18,number=true)
        YText(word("变化：","変化：")+Money(currency,comparison.change).display(),20,number=true)
    }
    val categoryCauses=analysisCauses(s.categoryGroups(comparison.current),s.categoryGroups(comparison.previous))
    val merchantCauses=analysisCauses(comparison.current.groups{it.merchantKey},comparison.previous.groups{it.merchantKey})
    val activityCauses=analysisCauses(comparison.current.groups{it.activityId.orEmpty()},comparison.previous.groups{it.activityId.orEmpty()})
    if(categoryCauses.isEmpty()&&merchantCauses.isEmpty())YText(word("这个区间暂无净消费变化。","この期間の純支出に変化はありません。"),12)
    fun display(n:Long)=Money(currency,n).display()
    @Composable fun cause(c:AnalysisCause,key:String,name:String) {
        ComparisonCauseCard(key,name,display(c.before),display(c.after),display(c.change),onDetail?.let{action->{action("current",key)}},onDetail?.let{action->{action("previous",key)}})
    }
    if(categoryCauses.isNotEmpty())ThemeSection(word("主要分类变化","主なカテゴリの変化"),"BY CATEGORY")
    categoryCauses.take(5).forEach{c->cause(c,if(c.key=="FEES")"fees"else"category:${c.key}",s.categories.find{it.id==c.key}?.name?:word("明确费用","確定手数料"))}
    if(merchantCauses.isNotEmpty())ThemeSection(word("主要商户变化","主なお店の変化"),"BY MERCHANT")
    merchantCauses.take(5).forEach{c->cause(c,"merchant:${c.key}",s.merchantLabel(c.key).ifBlank{word("未填写商户","お店未入力")})}
    if(activityCauses.isNotEmpty())ThemeSection(word("活动与日常的变化","シーンと日常の変化"),"BY SCENE")
    activityCauses.take(5).forEach{c->cause(c,"activity:${c.key}",s.activities.find{it.id==c.key}?.name?:word("日常","日常"))}
    YText(word("分类、商户和活动是同一批消费的不同视角，不将这些变化相加。","カテゴリ・お店・シーンは同じ支出の別の視点です。変化を合算しません。"),10,color=LocalYohaku.current.muted)
}
/** Activity budgets cover the complete scene; the drill link retains analysis filters. */
@Composable private fun AnalysisActivityModule(s:BookSnapshot,report:AnalysisReport,currency:Currency,onPeriod:((String)->Unit)?,onActivity:((String)->Unit)?) {
    val groups=report.groups{it.activityId.orEmpty()}.associateBy{it.key}
    fun money(amount:Long)=Money(currency,amount).display()
    YText(word("活动卡片展示全程消费与预算；期间明细沿用当前筛选。","シーンカードは全期間の支出と予算。期間明細には現在の絞込を適用。"),11,color=LocalYohaku.current.muted)
    if(s.activities.isEmpty())YEmpty(word("创建活动后，在这里查看每段生活。","シーンを作成すると、ひと区切りごとに表示されます。"))
    s.activities.sortedWith(compareBy<ActivityEntity>{it.ended}.thenByDescending{it.startDate}).forEachIndexed{index,a->
        val spent=s.activitySpent(a.id)
        val group=groups[a.id]
        Column(Modifier.fillMaxWidth().testTag("analysis-activity-${a.id}")) {
            ActivityCard(index,a.name,a.startDate,a.endDate,money(spent),a.budgetMinor?.let{money(it-spent)},a.budgetMinor?.let{money(it)},spent,a.budgetMinor,
                (if(a.excludeMonthly)word("独立预算 · 不计入月预算","独立予算・月予算外")else word("计入发生月预算","利用月予算に計上"))+(if(a.ended)word(" · 已结束","・終了済み")else ""),onActivity?.let{action->{action(a.id)}},"analysis-activity-open-${a.id}")
            val periodLabel=word("筛选期间净消费：","絞込期間の純支出：")+money(group?.amount?:0L)+" · "+(group?.count?:0)+word("笔","件")
            if(onPeriod!=null)YLink(periodLabel,{onPeriod(a.id)},Modifier.testTag("analysis-activity-period-${a.id}"))else YText(periodLabel,12,Modifier.testTag("analysis-activity-period-${a.id}"))
        }
    }
    groups[""]?.let{daily->
        ThemeSection(word("日常 · 未归入活动","日常・シーン外"),"EVERYDAY")
        val dailyLabel=word("筛选期间净消费：","絞込期間の純支出：")+money(daily.amount)+" · "+daily.count+word("笔","件")
        if(onPeriod!=null)YLink(dailyLabel,{onPeriod("")},Modifier.testTag("analysis-activity-period-daily"))else YText(dailyLabel,12,Modifier.testTag("analysis-activity-period-daily"))
    }
}
@Composable private fun AnalysisDetail(s:BookSnapshot,report:AnalysisReport,key:String,onRecord:(EventEntity)->Unit,onDismiss:()->Unit) {
    val facts=when {
        key.startsWith("category:")->report.facts.filter{it.categoryId==key.removePrefix("category:")||s.categories.any{c->c.id==it.categoryId&&c.parentId==key.removePrefix("category:")}}
        key.startsWith("merchant:")->report.facts.filter{it.merchantKey==key.removePrefix("merchant:")}
        key.startsWith("activity:")->report.facts.filter{it.activityId.orEmpty()==key.removePrefix("activity:")}
        key.startsWith("month:")->report.facts.filter{it.date.startsWith(key.removePrefix("month:"))}
        key.startsWith("day:")->report.facts.filter{it.date==key.removePrefix("day:")}
        else->report.facts.filter{it.fee>0}
    }
    val selected=AnalysisReport(report.filter,facts)
    val currency=Currency.valueOf(s.book.currency)
    val title=when {
        key.startsWith("category:")->s.categories.first{it.id==key.removePrefix("category:")}.name
        key.startsWith("merchant:")->s.merchantLabel(key.removePrefix("merchant:")).ifBlank{word("未填写商户","お店未入力")}
        key.startsWith("activity:")->s.activities.find{it.id==key.removePrefix("activity:")}?.name?:word("日常","日常")
        else->key.substringAfter(':',word("明确费用","確定手数料"))
    }
    var child by rememberSaveable(key){mutableStateOf<String?>(null)}
    val visible=if(child==null)selected else AnalysisReport(report.filter,facts.filter{it.categoryId==(if(child=="UNSPECIFIED")key.removePrefix("category:")else child)})
    val unspecified=word("未细分 · 直接记入一级","内訳未分類・親への直接記録")
    val selectedTitle=when{child=="UNSPECIFIED"->title+" / "+unspecified;child!=null->s.path(child);key.startsWith("category:")->s.path(key.removePrefix("category:"));else->title}
    val tier=if(key.startsWith("category:"))when{child=="UNSPECIFIED"->unspecified;child!=null||s.categories.first{it.id==key.removePrefix("category:")}.parentId!=null->word("二级分类","子カテゴリ");else->word("一级汇总 · 含子分类","親カテゴリ合計・子を含む")}else null
    YSheet(selectedTitle,onDismiss) {
        AnalysisDetailCover(Money(currency,visible.total).display(),report.filter.start+" — "+report.filter.end+" · "+visible.events.size+word("笔记录 · 已扣除到账退款","件・入金済み返金を差引"),tier)
        RecordFacts(listOf(word("原消费","元の支出") to Money(currency,visible.gross).display(),word("已到账退款","入金済み返金") to Money(currency,visible.refunds).display(),word("明确费用","確定手数料") to Money(currency,visible.fees).display()))
        if(key.startsWith("category:")&&child==null) {
            val parent=key.removePrefix("category:")
            val groups=s.categoryChildren(selected,parent).associateBy{it.key}
            val children=s.categories.filter{it.parentId==parent&&(it.active||it.id in groups)}
            if(children.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(if(LocalYohaku.current.id=="objects")8.dp else 0.dp)) {
                children.forEach{c->AnalysisChildRow(c.id,c.name,Money(currency,groups[c.id]?.amount?:0L).display(),{child=c.id})}
                AnalysisChildRow("UNSPECIFIED",unspecified,Money(currency,groups["UNSPECIFIED"]?.amount?:0L).display(),{child="UNSPECIFIED"})
            }
        }
        if(child!=null)YLink(word("返回一级分类汇总","親カテゴリの合計へ"),{child=null},Modifier.testTag("analysis-child-back"))
        ThemeSection(word("原消费与实际费用","元の支出と実際の手数料"),"RECORDED FACTS")
        if(visible.facts.isEmpty())YEmpty(word("此区间没有记录","この期間の記録はありません"))
        visible.events.filter{it.kind==EntryKind.EXPENSE.name}.forEach{e->EventRow(s,e,{onDismiss();onRecord(e)})}
        visible.facts.filter{it.fee>0}.forEach{f->Panel {
            YText(f.date+" · "+word("实际费用","実際の手数料"),13)
            YText(Money(currency,f.fee).display(),22,number=true)
            YLink(word("查看关联转账","関連する振替を見る"),{onDismiss();onRecord(f.event)})
        }}
        val refunds=s.confirmedRefunds(visible)
        if(refunds.isNotEmpty()) {
            ThemeSection(word("已到账退款 · 按原消费归属","入金済み返金・元の支出に帰属"),"REFUNDS")
            refunds.forEach{e->EventRow(s,e,{onDismiss();onRecord(e)})}
        }
    }
}
@Composable private fun AnalysisPreferenceEditor(s:BookSnapshot,vm:AppViewModel,initial:AnalysisModule?,filter:AnalysisFilter,today:LocalDate,onDismiss:()->Unit) {
    val moduleSaver=listSaver<List<AnalysisModule>,String>(save={it.map{module->module.name}},restore={it.map(AnalysisModule::valueOf)})
    var modules by rememberSaveable(s.book.id,initial,stateSaver=moduleSaver){mutableStateOf((listOfNotNull(initial)+vm.repository.analysisModules(s)).distinct())}
    val pinSaver=listSaver<Set<AnalysisModule>,String>(save={it.map{module->module.name}},restore={it.map(AnalysisModule::valueOf).toSet()})
    var pinned by rememberSaveable(s.book.id,stateSaver=pinSaver){mutableStateOf(vm.repository.analysisPins(s))}
    var preview by rememberSaveable(s.book.id){mutableStateOf<AnalysisModule?>(null)}
    val displayOrder=modules.filter{it in pinned}+modules.filter{it !in pinned}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(word("自定义分析","分析をカスタマイズ"),onDismiss) {
        YLead(word("把重点，摆在前面。","見たいものを、先頭へ。"),word("勾选想看的模块，调整顺序。置顶项优先，显示首项作为重点。","見たい内容と順序を選択。固定項目が優先され、表示の先頭が注目項目。"),"MY ANALYSIS / EDIT",pageFlow=true)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            YText(word("已选 ","選択 ")+modules.size,10,Modifier.weight(1f),color=LocalYohaku.current.muted)
            YText(word("当前账本 · ","現在の帳簿・")+s.book.name,10,Modifier.weight(1f),color=LocalYohaku.current.muted)
        }
        (modules+AnalysisModule.entries.filter{it !in modules}).forEachIndexed{index,module->key(module) {
            val position=modules.indexOf(module)
            fun move(delta:Int){val values=modules.toMutableList();values[position]=values[position+delta];values[position+delta]=module;modules=values}
            AnalysisPreferenceCard(module.name,analysisModuleName(module),analysisModuleNote(module),position,module in modules,module in pinned,position==modules.lastIndex,index,
                {enabled->modules=if(enabled)modules+module else modules-module;if(!enabled)pinned=pinned-module},{move(-1)},{move(1)},{enabled->pinned=if(enabled)pinned+module else pinned-module},focused=module==displayOrder.firstOrNull())
        }}
        AnalysisOrderSummary(displayOrder.map{analysisModuleName(it)})
        AnalysisModule.entries.forEach{m->YLink(word("预览：","プレビュー：")+analysisModuleName(m),{preview=if(preview==m)null else m},Modifier.testTag("analysis-preview-${m.name}"))}
        preview?.let{m->
            val report=s.analysis(filter)
            val currency=Currency.valueOf(s.book.currency)
            Column(Modifier.fillMaxWidth().testTag("analysis-preview-content"),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                AnalysisModuleHeading(analysisModuleName(m),analysisModuleNote(m),m==displayOrder.firstOrNull()||m in pinned)
                YText(filter.start+" — "+filter.end,11,Modifier.testTag("analysis-preview-period"),color=LocalYohaku.current.muted)
                val context=listOfNotNull(filter.categoryId?.let{s.path(it)},filter.merchantKey?.let{s.merchantLabel(it).ifBlank{word("未填写商户","お店未入力")}},filter.activityId?.let{id->s.activities.find{it.id==id}?.name?:word("日常","日常")})
                if(context.isNotEmpty())YText(context.joinToString(" · "),11,Modifier.testTag("analysis-preview-context"),color=LocalYohaku.current.muted)
                when(m) {
                    AnalysisModule.TREND->AnalysisTrend(report,currency,{})
                    AnalysisModule.COMPARISON->AnalysisComparisonView(s,filter,today,currency)
                    AnalysisModule.ACTIVITIES->AnalysisActivityModule(s,report,currency,null,null)
                    AnalysisModule.CATEGORIES->if(report.facts.isEmpty())YEmpty(word("这个期间没有相关消费。","この期間の関連支出はありません。"))else AnalysisCategoryList(analysisCategoryItems(s,report,currency),null)
                    AnalysisModule.MERCHANTS->if(report.facts.isEmpty())YEmpty(word("这个期间没有相关消费。","この期間の関連支出はありません。"))else AnalysisMerchantList(analysisMerchantItems(s,report,currency),Money(currency,report.total).display(),null)
                }
            }
        }
        YText(word("保存后对本账本生效，切换主题保留选择。","この帳簿に保存します。テーマを変えても選択を保持します。"),11,color=LocalYohaku.current.muted)
        YButton(word("保存分析偏好","分析設定を保存"),{vm.execute(onSuccess=onDismiss){vm.repository.saveAnalysisPreferences(s.book.id,modules,pinned)}},Modifier.testTag("analysis-preferences-save"),primary=true,enabled=!busy)
    }
}
@Composable private fun AnalysisFilterEditor(s:BookSnapshot,old:AnalysisFilter,vm:AppViewModel,onDismiss:()->Unit,onApply:(AnalysisFilter)->Unit) {
    var start by rememberSaveable{mutableStateOf(old.start)};var end by rememberSaveable{mutableStateOf(old.end)}
    var category by rememberSaveable{mutableStateOf(old.categoryId)};var merchant by rememberSaveable{mutableStateOf(old.merchantKey)};var activity by rememberSaveable{mutableStateOf(old.activityId)}
    var owner by remember{mutableStateOf<Int?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(word("分析筛选","分析の絞込"),onDismiss) {
        YDateFields(listOf(word("开始日期","開始日") to start,word("结束日期","終了日") to end),owner,{owner=it},{index,value->if(index==0)start=value else end=value})
        YSelect(word("分类","カテゴリ"),category,listOf(null to word("全部","すべて"))+s.categories.filter{it.kind==EntryKind.EXPENSE.name}.map{it.id as String? to s.path(it.id)},{category=it})
        val keys=s.visibleEvents.filter{it.kind==EntryKind.EXPENSE.name||it.kind==EntryKind.TRANSFER.name}.map{s.merchantKey(it)}.distinct()
        YSelect(word("商户","お店"),merchant,listOf(null to word("全部","すべて"))+keys.map{it as String? to s.merchantLabel(it).ifBlank{word("未填写商户","お店未入力")}},{merchant=it})
        YSelect(word("活动","シーン"),activity,listOf(null to word("全部","すべて"),"" to word("日常","日常"))+s.activities.map{it.id as String? to it.name},{activity=it})
        val value=AnalysisFilter(start,end,category,merchant,activity)
        YButton(word("应用筛选","絞込を適用"),{vm.execute(onSuccess={onApply(value)}){s.analysis(value)}},Modifier.testTag("analysis-filter-apply"),primary=true,enabled=!busy)
        YLink(word("清除分类、商户和活动筛选","カテゴリ・お店・シーンの絞込を解除"),{category=null;merchant=null;activity=null})
    }
}
