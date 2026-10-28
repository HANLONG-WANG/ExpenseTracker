package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.*

@Composable internal fun limitLabel(value:String?,delayDays:Int=0)=when(value) {
    "TOTAL_ONLY"->word("共用总额度","総枠を共用")
    "SEPARATE_CAPS"->word("总额度与各子额度共同限制","総枠と個別枠で制限")
    "SHARED_SUBPOOL"->word("分期与リボ共用子额度","分割・リボの共通枠")
    "PRINCIPAL_ONLY"->word("只按本金预计占用","元金のみで使用額を予測")
    "PLATFORM_ONLY"->word("仅记录平台值，不做本地推算","返済先の値のみ記録")
    "TRANSACTION_DATE"->word("从交易日占用","取引日から使用")
    "ACCOUNT_POSTING_DATE"->word("从账户入账日占用","口座計上日から使用")
    "PAYMENT_DATE"->word("付款日后 ${delayDays} 天预计恢复","支払日の${delayDays}日後に回復と予測")
    "PLATFORM_CONFIRMATION"->word("平台确认清偿日预计恢复","返済先の確認日に回復と予測")
    "MANUAL"->word("逐笔确认额度恢复","枠回復を個別に確認")
    "TOTAL"->word("总额度","総枠")
    "INSTALLMENT"->word("分期额度","分割枠")
    "REVOLVING"->word("リボ额度","リボ枠")
    "limit.config"->word("尚未配置额度与占用基准","利用枠・使用額基準が未設定")
    "limit.basisChanged"->word("基准中已有记录改变或删除，请重新核对占用基准","基準に含まれる記録が変更・削除されました。使用額基準を再確認してください")
    "limit.backdated"->word("新增了基准日期之前的记录，请重新核对占用基准","基準日より前の記録が追加されました。使用額基準を再確認してください")
    "limit.unknownPrincipal"->word("相关占用 / 恢复变动待确认","利用・回復の変動額が未確定")
    "limit.unknownMode"->word("付款方式或本金分配未知，子额度无法推算","支払方式・元金充当が不明のため個別枠を予測できません")
    "limit.platformOnly"->word("当前设置只采用平台观测值","現在の設定では返済先の確認値のみ使用")
    "limit.recovery"->word("额度恢复规则未确定","枠回復の条件が未設定")
    "limit.usageStart"->word("额度开始占用时间未确定","枠の使用開始日が未設定")
    "limit.negativeUsage"->word("预计占用小于零，请核对基准或恢复记录","予測使用額が0未満です。基準・回復記録を確認してください")
    "limit.groupChanged"->word("成员或成员配置已变，请重新核对共享池基准","所属口座・条件が変更されています。共通枠の基準を再確認してください")
    else->word("未确定","未設定")
}
@Composable private fun limitMoney(currency:Currency,value:Long?)=value?.let{Money(currency,it).display()}?:word("未知","不明")
@Composable private fun LimitAmountFacts(title:String,value:LimitAmount,currency:Currency) {
    YText(title,14)
    RecordFacts(listOf(word("设置上限","設定上限") to limitMoney(currency,value.capacityMinor),word("预计占用","予測使用額") to limitMoney(currency,value.usedMinor),word("≈ 预计可用","≈ 利用可能額") to limitMoney(currency,value.availableMinor)))
    if(value.excessMinor?.let{it>0}==true)YText(word("预计超额：","予測超過額：")+limitMoney(currency,value.excessMinor),12,color=LocalYohaku.current.warm)
}
@Composable internal fun CreditLimitSection(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,vm:AppViewModel) {
    val graph by vm.creditLimitState.collectAsStateWithLifecycle()
    val l=graph?.takeIf{it.book.id==s.book.id}
    if(l==null){YText(word("正在读取额度资料…","利用枠情報を読込中…"),12);YButton(word("重新读取","再読込"),vm::retryRead);return}
    val date=LocalDate.now(vm.repository.clock).toString()
    val value=accountCreditLimit(s,f,l,a.id,date);val c=Currency.valueOf(a.currency)
    var editor by rememberSaveable(s.book.id,a.id){mutableStateOf<String?>(null)}
    var target by rememberSaveable(s.book.id,a.id){mutableStateOf<String?>(null)}
    var history by rememberSaveable(s.book.id,a.id){mutableStateOf(false)}
    var voidId by rememberSaveable(s.book.id,a.id){mutableStateOf<String?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YText(word("额度不是现金，不计入资产、收入或预算。以下为本地预计，平台确认值另列。","利用枠は現金ではなく、資産・収入・予算には加えません。以下の予測と返済先の確認値は別に表示します。"),12)
    Panel(Modifier.testTag("limit-estimate")) {
        LimitAmountFacts(limitLabel("TOTAL"),value.total,c)
        if(a.type==AccountType.CREDIT.name) {
            YText(limitLabel(value.config?.sharing),12)
            if(value.config?.sharing==CreditLimitSharing.TOTAL_ONLY.name)YText(word("分期与リボ受同一总额度限制；不另加子额度。","分割・リボは同じ総枠で制限されます。個別枠は加算しません。"),11)
            else {YText(word("以下可用值已考虑总额度及共享池限制。","以下の利用可能額は総枠・共通枠で制限済みです。"),11);LimitAmountFacts(limitLabel("INSTALLMENT"),value.installment,c);LimitAmountFacts(limitLabel("REVOLVING"),value.revolving,c)}
        }
        value.issues.forEach{YText(limitLabel(it),12,color=LocalYohaku.current.warm)}
        value.config?.let{LimitConfigFacts(it)}
        YButton(word("设置额度 / 更新占用基准","利用枠・使用額基準を設定"),{editor="config"},Modifier.testTag("limit-configure"),primary=true,enabled=!busy)
    }
    val group=value.groupId?.let{id->l.groups.first{it.id==id}}
    ThemeSection(word("跨账户共享额度","口座間の共通枠"),"SHARED CAPACITY")
    if(group!=null) {
        val estimate=groupCreditLimit(s,f,l,group.id,date)
        Panel {
            YText(group.name,18);LimitAmountFacts(word("独立的共享池","独立した共通枠"),estimate.total,c)
            YText(l.members.filter{it.groupId==group.id}.joinToString(" · "){m->s.accounts.first{it.id==m.accountId}.name},12)
            RecordFacts(listOf(word("本账户受共享池限制后 ≈ 可用","共通枠適用後の当口座 ≈ 利用可能額") to limitMoney(c,value.effectiveTotalMinor)))
            estimate.issues.forEach{YText(limitLabel(it),12,color=LocalYohaku.current.warm)}
            YButton(word("编辑共享额度与成员","共通枠・所属口座を編集"),{target=group.id;editor="group"},Modifier.testTag("limit-group-edit"),enabled=!busy)
            YButton(word("记录共享池平台可用值","共通枠の確認値を記録"),{target=group.id;editor="group-observation"},enabled=!busy)
            LimitObservations(s,f,l,vm,null,group.id,c)
        }
    }else YButton(word("建立共享额度池","共通枠を作成"),{target=null;editor="group"},Modifier.testTag("limit-group-add"),enabled=!busy)
    ThemeSection(word("平台可用值","返済先の利用可能額"),"OBSERVED, WITH A DATE")
    LimitObservations(s,f,l,vm,a.id,null,c)
    YButton(word("记录平台可用值","返済先の確認値を記録"),{target=null;editor="observation"},Modifier.testTag("limit-observe"),enabled=!busy)
    ThemeSection(word("实际额度恢复确认","実際の枠回復確認"),"RECOVERY CONFIRMATIONS")
    YText(word("规则推算可被逐笔实际确认替代。部分确认后，其余本金继续占用；不按实付总额恢复额度。","個別の実確認は条件による予測に優先します。一部確認後の残り元金は使用中として保持し、出金総額で枠を回復しません。"),11)
    val payments=limitPayments(s,f,a.id)
    payments.forEach{p->Panel {
        YText(p.date+" · "+limitMoney(c,p.principalMinor),14)
        YText(word("与此付款相关的额度本金","この支払に関する枠の元金"),11)
        YButton(word("确认这笔付款的额度恢复","この支払の枠回復を確認"),{target=p.eventId;editor="recovery"},Modifier.testTag("limit-recover-${p.eventId}"),enabled=!busy&&p.principalMinor?.let{it>0}==true)
    }}
    l.recoveries.filter{it.accountId==a.id}.sortedByDescending{it.createdAt}.forEach{r->Panel {
        YText(r.date+" · "+Money(c,r.principalMinor).display(),14)
        YText(when{r.voidedAt!=null->word("已作废 · 保留原始记录","無効・元の記録を保持");payments.none{it.eventId==r.eventId&&it.proof==r.sourceProof}->word("付款根据信息已变，需核对并作废后重录","支払根拠が変更されています。確認して無効化後に再記録してください");else->word("已确认实际恢复","実回復を確認済み")},12,color=LocalYohaku.current.warm)
        if(r.voidedAt==null)YButton(word("作废这次恢复确认","この回復確認を無効にする"),{voidId=r.id},enabled=!busy)
    }}
    YButton(word("配置版本历史","設定の履歴")+if(history)" −"else" +",{history=!history},Modifier.testTag("limit-history"))
    if(history) {
        l.configs.filter{it.accountId==a.id}.sortedByDescending{it.version}.forEach{Panel{LimitConfigFacts(it);LimitAmountFacts(word("当时配置与占用基准","当時の設定と使用額基準"),LimitAmount(it.totalMinor,it.usedTotalMinor,null,null),c)}}
        group?.let{g->val rows by remember(g.id){vm.repository.db.creditLimitDao().groupRevisions(s.book.id,g.id)}.collectAsStateWithLifecycle(emptyList())
            rows.forEach{row->val archived=vm.repository.json.decodeFromString<CreditLimitGroupArchive>(row.json);Panel {
                YText(archived.group.name+" · v${row.version}",14);YText(archived.group.basisDate,12)
                RecordFacts(listOf(word("共享上限 / 基准占用","共通上限・基準使用額") to (limitMoney(c,archived.group.totalMinor)+" / "+limitMoney(c,archived.group.usedMinor))))
                YText(archived.accountIds.joinToString(" · "){id->s.accounts.first{it.id==id}.name},12)
            }}
        }
    }
    editor?.let{kind->when(kind){
        "config"->CreditLimitConfigEditor(s,a,value.config,vm,{editor=null})
        "group"->CreditLimitGroupEditor(s,a,l.groups.firstOrNull{it.id==target},l,vm,{editor=null})
        else->CreditLimitFactEditor(s,a,l,kind,target,vm,{editor=null})
    }}
    voidId?.let{id->YSheet(word("作废恢复确认","回復確認を無効化"),{voidId=null}) {
        YText(word("保留原确认记录，额度重新按现有规则或其余有效确认计算。实际付款保持原样。","元の確認を保持し、現在の条件・残る有効な確認で枠を再計算します。実支払は保持します。"),12)
        YButton(word("确认作废","無効化を確定"),{vm.execute(onSuccess={voidId=null}){vm.repository.voidCreditLimitRecovery(s.book.id,id)}},Modifier.testTag("limit-void-confirm"),primary=true,enabled=!busy)
    }}
}
@Composable private fun LimitConfigFacts(c:CreditLimitConfigEntity) {
    RecordFacts(listOf(word("版本 / 生效日","版・適用日") to "v${c.version} · ${c.effectiveFrom}",word("占用基准日","基準日") to c.basisDate,word("时区","時間帯") to c.zoneId))
    YText(limitLabel(c.usageBasis)+" · "+limitLabel(c.usageStart)+" · "+limitLabel(c.recovery,c.recoveryDelayDays),11)
    c.sharing?.let{YText(limitLabel(it),12)}
    val currency=Currency.valueOf(c.currency)
    if(c.sharing=="SEPARATE_CAPS")RecordFacts(listOf(word("分期上限","分割上限") to limitMoney(currency,c.installmentMinor),word("リボ上限","リボ上限") to limitMoney(currency,c.revolvingMinor)))
    if(c.sharing=="SHARED_SUBPOOL")RecordFacts(listOf(word("分期与リボ共用上限","分割・リボの共通上限") to limitMoney(currency,c.combinedMinor)))
    if(c.sharing in listOf("SEPARATE_CAPS","SHARED_SUBPOOL"))RecordFacts(listOf(word("分期占用基准","分割使用額の基準") to limitMoney(currency,c.usedInstallmentMinor),word("リボ占用基准","リボ使用額の基準") to limitMoney(currency,c.usedRevolvingMinor)))
}
@Composable private fun LimitObservations(s:BookSnapshot,f:FinancialGraph,l:CreditLimitGraph,vm:AppViewModel,accountId:String?,groupId:String?,c:Currency) {
    val rows=l.observations.filter{it.accountId==accountId&&it.groupId==groupId}.sortedByDescending{it.observedAt}
    if(rows.isEmpty())YText(word("尚无平台确认值","返済先の確認値はまだありません"),12)
    rows.forEach{obs->
        val today=LocalDate.now(vm.repository.clock).toString()
        val changed=vm.repository.creditLimitObservationBasis(FinancialReviewState(s,f,l),accountId,groupId,today)!=obs.basisJson
        YText(limitLabel(obs.scope)+" · "+Money(c,obs.availableMinor).display(),14)
        YText(Instant.parse(obs.observedAt).atZone(vm.repository.clock.zone).toLocalDateTime().toString()+" · "+vm.repository.clock.zone.id,11)
        YText(if(changed)word("此后相关记录或配置已变；该值仅代表当时观测","その後、対象の記録・設定が変更されています。この値は確認時点のものです")else word("历史平台观测值，不自动视为实时可用","確認時点の値です。現在の利用可能額とは限りません"),11,color=LocalYohaku.current.muted)
    }
}

@Composable private fun showCreditLimitReview(review:CreditLimitReview?,vm:AppViewModel,onBack:()->Unit,onClose:()->Unit,onStale:()->Unit):Boolean {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    if(review==null)return false
    val busy by vm.busy.collectAsStateWithLifecycle()
    val c=Currency.valueOf(review.config?.currency?:review.group?.currency?:review.observation?.groupId?.let{id->review.before.limits.groups.first{it.id==id}.currency}?:review.before.ledger.accounts.first{it.id==(review.recovery?.accountId?:review.observation?.accountId)}.currency)
    review.config?.let{config->
        LimitConfigFacts(config)
        RecordFacts(listOf(word("总占用基准","総使用額基準") to limitMoney(c,config.usedTotalMinor)))
    }
    review.group?.let{group->
        YText(group.basisDate,12)
        YText(review.members.joinToString(" · "){id->review.before.ledger.accounts.first{it.id==id}.name},12)
        RecordFacts(listOf(word("共享池占用基准","基準使用額") to limitMoney(c,group.usedMinor)))
    }
    review.estimate?.let{LimitAmountFacts(word("保存后本地预计","保存後のローカル予測"),it.total,c);it.issues.forEach{issue->YText(limitLabel(issue),12,color=LocalYohaku.current.warm)}}
    review.groupEstimate?.let{YText(it.group.name,18);LimitAmountFacts(word("保存后共享池预计","保存後の共通枠予測"),it.total,c);it.issues.forEach{issue->YText(limitLabel(issue),12,color=LocalYohaku.current.warm)}}
    review.recovery?.let{RecordFacts(listOf(word("实际恢复本金","回復元金") to Money(c,it.principalMinor).display(),word("实际恢复日","回復日") to it.date))}
    review.observation?.let{val currency=it.groupId?.let{id->Currency.valueOf(review.before.limits.groups.first{g->g.id==id}.currency)}?:c;YText(limitLabel(it.scope)+" · "+Money(currency,it.availableMinor).display(),18);YText(it.observedAt,12)}
    YText(word("只保存额度资料，不生成资金流水。","枠の情報のみ保存し、資金明細は作成しません。"),12)
    YActionRow {
        YButton(word("确认保存","確定して保存"),{var saved=false;vm.execute(onSuccess={if(saved)onClose()else onStale()}){saved=commitFinancialDraft(draftSession){if(vm.repository.commitCreditLimit(review))"saved"else null}!=null}},Modifier.testTag("limit-confirm"),primary=true,enabled=!busy)
        YButton(word("返回修改","修正に戻る"),onBack,Modifier.testTag("limit-back"),enabled=!busy)
    }
    return true
}

@Composable internal fun CreditLimitConfigEditor(s:BookSnapshot,a:AccountEntity,old:CreditLimitConfigEntity?,vm:AppViewModel,onClose:()->Unit) {
    FinancialDraftHost(s,FinancialDraftContext("LIMIT_CONFIG",a.id),vm,onClose){CreditLimitConfigEditorContent(s,a,old,vm,onClose)}
}

@Composable private fun CreditLimitConfigEditorContent(s:BookSnapshot,a:AccountEntity,old:CreditLimitConfigEntity?,vm:AppViewModel,onClose:()->Unit) {
    val c=Currency.valueOf(a.currency);val today=LocalDate.now(vm.repository.clock).toString()
    fun text(n:Long?)=n?.let{Money(c,it).decimal.toPlainString()}.orEmpty()
    var total by financialField("total"){text(old?.totalMinor)};var split by financialField("split"){text(old?.installmentMinor)};var revo by financialField("revo"){text(old?.revolvingMinor)};var combined by financialField("combined"){text(old?.combinedMinor)}
    // A new observation of usage is required; an old baseline is never silently advanced to today.
    var used by financialField("used"){""};var usedSplit by financialField("usedSplit"){""};var usedRevo by financialField("usedRevo"){""}
    var basisDate by financialField("basisDate"){today};var effective by financialField("effective"){today};var zone by financialField("zone"){old?.zoneId?:if(a.currency=="CNY")"Asia/Shanghai"else"Asia/Tokyo"}
    var delay by financialField("recoveryDelayDays"){(old?.recoveryDelayDays?:0).toString()}
    var sharing by financialField("sharing"){old?.sharing};var recovery by financialField("recovery"){old?.recovery};var usage by financialField("usage"){old?.usageBasis};var start by financialField("start"){old?.usageStart}
    var owner by remember{mutableStateOf<Int?>(null)};var review by remember{mutableStateOf<CreditLimitReview?>(null)};var stale by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle();val formScroll=rememberScrollState();val reviewScroll=rememberScrollState()
    LaunchedEffect(review){if(review!=null)reviewScroll.scrollTo(0)}
    YSheet(if(review!=null)word("额度设置预览","利用枠設定の確認")else word("额度与占用基准","利用枠の設定"),{if(review!=null)review=null else onClose()},scrollState=if(review!=null)reviewScroll else formScroll) {
        if(stale)YText(word("资料已变化，请重新预览后确认。","情報が変更されています。再確認してから保存してください。"),12,color=LocalYohaku.current.warm)
        if(!showCreditLimitReview(review,vm,{review=null},onClose,{review=null;stale=true})) {
            FinancialDraftNotice()
            YText(a.name+" · "+a.currency,18)
            YText(word("留空表示未知；确认没有占用时请填写 0。基准包含目前已记、截至基准日的记录；此后新增同日记录按基准之后处理。","空欄は不明です。使用額がないと確認した場合は0を入力してください。基準には記帳済みの基準日以前の記録を含め、以後に追加する同日記録は基準後として扱います。"),12)
            YField(word("总额度 / 未知留空","総枠・不明は空欄"),total,{total=it},Modifier.testTag("limit-total"),numeric=true)
            YField(word("总占用基准 / 未知留空","総使用額の基準・不明は空欄"),used,{used=it},Modifier.testTag("limit-used"),numeric=true)
            if(a.type==AccountType.CREDIT.name) {
                YSelect(word("额度共享方式","枠の共用方法"),sharing,listOf(null to word("未确定","未設定"))+CreditLimitSharing.entries.map{it.name as String? to limitLabel(it.name)},{sharing=it})
                if(sharing==CreditLimitSharing.SEPARATE_CAPS.name) {YField(word("分期上限","分割上限"),split,{split=it},numeric=true);YField(word("リボ上限","リボ上限"),revo,{revo=it},numeric=true)}
                if(sharing==CreditLimitSharing.SHARED_SUBPOOL.name)YField(word("分期与リボ共用上限","分割・リボの共通上限"),combined,{combined=it},numeric=true)
                if(sharing!=CreditLimitSharing.TOTAL_ONLY.name) {YField(word("分期占用基准","分割使用額の基準"),usedSplit,{usedSplit=it},numeric=true);YField(word("リボ占用基准","リボ使用額の基準"),usedRevo,{usedRevo=it},numeric=true)}
            }
            YSelect(word("占用计算依据","使用額の計算根拠"),usage,listOf(null to word("未确定","未設定"))+CreditLimitUsageBasis.entries.map{it.name as String? to limitLabel(it.name)},{usage=it})
            YSelect(word("开始占用的时间","使用開始日"),start,listOf(null to word("未确定","未設定"))+CreditLimitUsageStart.entries.map{it.name as String? to limitLabel(it.name)},{start=it})
            YSelect(word("本金归还后的额度恢复","元金返済後の枠回復"),recovery,listOf(null to word("未确定","未設定"))+LimitRecovery.entries.map{it.name as String? to limitLabel(it.name,delay.toIntOrNull()?:0)},{recovery=it})
            if(recovery=="PAYMENT_DATE")YField(word("付款后第几天恢复（自然日，0 为当天）","支払後何日目に回復（暦日、0は当日）"),delay,{delay=it},numeric=true)
            YDateFields(listOf(word("占用基准日","使用額基準日") to basisDate,word("配置生效日","設定適用日") to effective),owner,{owner=it},{i,v->if(i==0)basisDate=v else effective=v})
            YField(word("时区","タイムゾーン"),zone,{zone=it})
            YText(word("贷款只预计循环额度本金；固定贷款不占用此循环额度。费用占用等特殊规则请选仅记录平台值。","ローンは循環枠の元金のみ予測し、固定ローンはこの枠を使用しません。手数料も枠を使用する等の条件は確認値のみを選択してください。"),11)
            fun amount(raw:String)=raw.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor}
            YButton(word("预览额度设置","利用枠設定を確認"),{vm.execute{review=vm.repository.previewCreditLimit(s.book.id,CreditLimitInput(a.id,amount(total),if(a.type==AccountType.CREDIT.name&&sharing=="SEPARATE_CAPS")amount(split)else null,if(a.type==AccountType.CREDIT.name&&sharing=="SEPARATE_CAPS")amount(revo)else null,sharing?.let{CreditLimitSharing.valueOf(it)},if(a.type==AccountType.CREDIT.name&&sharing=="SHARED_SUBPOOL")amount(combined)else null,effective,basisDate,amount(used),if(a.type==AccountType.CREDIT.name&&sharing!="TOTAL_ONLY")amount(usedSplit)else null,if(a.type==AccountType.CREDIT.name&&sharing!="TOTAL_ONLY")amount(usedRevo)else null,recovery?.let{LimitRecovery.valueOf(it)},usage?.let{CreditLimitUsageBasis.valueOf(it)},zone,start?.let{CreditLimitUsageStart.valueOf(it)},if(recovery=="PAYMENT_DATE")delay.toIntOrNull()?:error("请输入恢复天数 / 回復日数を入力してください")else 0))}},Modifier.testTag("limit-preview"),primary=true,enabled=!busy)
        }
    }
}

@Composable internal fun CreditLimitGroupEditor(s:BookSnapshot,a:AccountEntity,old:CreditLimitGroupEntity?,l:CreditLimitGraph,vm:AppViewModel,onClose:()->Unit) {
    FinancialDraftHost(s,FinancialDraftContext("LIMIT_GROUP",a.id,targetId=old?.id),vm,onClose){CreditLimitGroupEditorContent(s,a,old,l,vm,onClose)}
}

@Composable private fun CreditLimitGroupEditorContent(s:BookSnapshot,a:AccountEntity,old:CreditLimitGroupEntity?,l:CreditLimitGraph,vm:AppViewModel,onClose:()->Unit) {
    val c=Currency.valueOf(a.currency)
    var name by financialField("name"){old?.name.orEmpty()};var total by financialField("total"){old?.totalMinor?.let{Money(c,it).decimal.toPlainString()}.orEmpty()};var used by financialField("used"){""}
    var members by financialField("members"){old?.let{g->l.members.filter{it.groupId==g.id}.map{it.accountId}}?:listOf(a.id)}
    var date by financialField("date"){LocalDate.now(vm.repository.clock).toString()};var owner by remember{mutableStateOf<Int?>(null)}
    var review by remember{mutableStateOf<CreditLimitReview?>(null)};var stale by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle();val form=rememberScrollState();val preview=rememberScrollState()
    LaunchedEffect(review){if(review!=null)preview.scrollTo(0)}
    YSheet(word("跨账户共享额度","口座間の共通枠"),{if(review!=null)review=null else onClose()},scrollState=if(review!=null)preview else form) {
        if(stale)FinanceRefreshed()
        if(!showCreditLimitReview(review,vm,{review=null},onClose,{review=null;stale=true})) {
            FinancialDraftNotice()
            YText(word("共享池有独立上限和占用基准，不把成员额度相加。仅选择同币种债务账户。成员至少两个。","共通枠は独立した上限・使用額基準を持ち、各口座の枠を合算しません。同通貨の債務口座を2件以上選択してください。"),12)
            YField(word("共享池名称","共通枠の名前"),name,{name=it},Modifier.testTag("limit-group-name"))
            YField(word("共享额度上限 / 未知留空","共通枠の上限・不明は空欄"),total,{total=it},Modifier.testTag("limit-group-total"),numeric=true)
            YField(word("共享池占用基准 / 未知留空","共通枠の使用額基準・不明は空欄"),used,{used=it},Modifier.testTag("limit-group-used"),numeric=true)
            YText(word("基准包含目前已记、截至基准日的记录；此后新增同日记录视为基准之后。","基準は記帳済みの基準日以前の記録を含み、以後に追加する同日記録は基準後として扱います。"),11)
            s.accounts.filter{it.currency==a.currency&&AccountType.valueOf(it.type).debt&&(it.active||it.id in members)&&l.members.none{m->m.accountId==it.id&&m.groupId!=old?.id}}.forEach{account->YToggle(account.name,account.id in members,{selected->members=if(selected)members+account.id else members-account.id})}
            YText(word("成员配置或恢复方式改变后需重新核对共享基准。","所属口座の設定・回復方法を変更した場合、共通枠の基準を再確認してください。"),11)
            YDateFields(listOf(word("共享池基准日","共通枠の基準日") to date),owner,{owner=it},{_,v->date=v})
            YButton(word("预览共享额度","共通枠を確認"),{vm.execute{review=vm.repository.previewCreditLimitGroup(s.book.id,CreditLimitGroupInput(name,c,total.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor},used.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor},date,members,old?.id))}},Modifier.testTag("limit-group-preview"),primary=true,enabled=!busy)
        }
    }
}

@Composable internal fun CreditLimitFactEditor(s:BookSnapshot,a:AccountEntity,l:CreditLimitGraph,kind:String,target:String?,vm:AppViewModel,onClose:()->Unit) {
    FinancialDraftHost(s,FinancialDraftContext("LIMIT_FACT",a.id,targetId=target,mode=kind),vm,onClose){CreditLimitFactEditorContent(s,a,l,kind,target,vm,onClose)}
}

@Composable private fun CreditLimitFactEditorContent(s:BookSnapshot,a:AccountEntity,l:CreditLimitGraph,kind:String,target:String?,vm:AppViewModel,onClose:()->Unit) {
    val c=Currency.valueOf(a.currency);val recovery=kind=="recovery";val groupId=target.takeIf{kind=="group-observation"}
    var amount by financialField("amount"){""};var split by financialField("split"){""};var revo by financialField("revo"){""}
    var scope by financialField("scope"){"TOTAL"}
    var date by financialField("date"){if(recovery)LocalDate.now(vm.repository.clock).toString()else LocalDateTime.now(vm.repository.clock).withSecond(0).withNano(0).toString()}
    var owner by remember{mutableStateOf<Int?>(null)};var review by remember{mutableStateOf<CreditLimitReview?>(null)};var stale by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle();val form=rememberScrollState();val preview=rememberScrollState()
    LaunchedEffect(review){if(review!=null)preview.scrollTo(0)}
    YSheet(if(recovery)word("确认实际额度恢复","枠回復の確認")else word("记录平台可用值","返済先の確認値を記録"),{if(review!=null)review=null else onClose()},scrollState=if(review!=null)preview else form) {
        if(stale)FinanceRefreshed()
        if(!showCreditLimitReview(review,vm,{review=null},onClose,{review=null;stale=true})) {
            FinancialDraftNotice()
            YText((groupId?.let{id->l.groups.first{it.id==id}.name}?:a.name)+" · "+a.currency,18)
            if(!recovery&&groupId==null&&a.type==AccountType.CREDIT.name)YSelect(word("观测范围","確認対象"),scope,listOf("TOTAL","INSTALLMENT","REVOLVING").map{it to limitLabel(it)},{scope=it})
            YField(if(recovery)word("已实际恢复本金","実際に回復した元金")else word("平台显示可用金额","返済先の利用可能額"),amount,{amount=it},Modifier.testTag("limit-fact-amount"),numeric=true)
            if(recovery&&a.type==AccountType.CREDIT.name){YField(word("其中分期恢复 / 未知留空","うち分割の回復額・不明は空欄"),split,{split=it},numeric=true);YField(word("其中リボ恢复 / 未知留空","うちリボの回復額・不明は空欄"),revo,{revo=it},numeric=true)}
            YDateFields(listOf((if(recovery)word("实际恢复日","実回復日")else word("平台观测时间","返済先の確認時刻")) to date),owner,{owner=it},{_,v->date=v})
            if(!recovery)YText(word("时间按设备时区记录：","端末の時間帯で記録：")+vm.repository.clock.zone.id,11)
            YButton(word("预览确认","確認内容を見る"),{vm.execute{review=if(recovery)vm.repository.previewCreditLimitRecovery(s.book.id,CreditLimitRecoveryInput(a.id,target!!,date,Money.parse(amount,c).minor,split.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor},revo.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor}))else vm.repository.previewCreditLimitObservation(s.book.id,a.id.takeIf{groupId==null},groupId,scope,Money.parse(amount,c).minor,LocalDateTime.parse(date).atZone(vm.repository.clock.zone).toInstant().toString())}},Modifier.testTag("limit-fact-preview"),primary=true,enabled=!busy)
        }
    }
}
