package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.time.LocalDate

@Composable internal fun ActivityScreen(s:BookSnapshot,vm:AppViewModel,onExpense:(String,String?)->Unit,onRecord:(EventEntity)->Unit) {
    val requestedActivity by vm.openActivity.collectAsStateWithLifecycle()
    val requestedFriend by vm.openActivityFriend.collectAsStateWithLifecycle()
    var selected by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var deleting by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var editing by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var reserveFor by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var treatFor by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var treatId by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var treatFriend by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var friendDetail by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var friendName by rememberSaveable(s.book.id){mutableStateOf("")}
    LaunchedEffect(requestedActivity){requestedActivity?.let{selected=it;vm.openActivity.value=null}}
    LaunchedEffect(requestedFriend){requestedFriend?.let{(activityId,friendId)->selected=activityId;friendDetail=friendId;vm.openActivityFriend.value=null}}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val activity=s.activities.find{it.id==selected}
    val currency=Currency.valueOf(s.book.currency)
    fun money(amount:Long)=Money(currency,amount).display()
    YTitle(word("把生活，记在一起。","暮らしも、一緒に。"),"LIFE BEYOND NUMBERS",pageFlow=true)
    YToggle(word("本账本活动功能","この帳簿のシーン機能"),s.features?.activityMode==true,{enabled->vm.execute{vm.repository.setActivityMode(s.book.id,enabled,s.features?.activeActivityId)}})
    s.features?.activeActivityId?.let{id->s.activities.find{it.id==id}?.let{active->Panel {
        YText(word("正在记录：","記録中：")+active.name,18)
        YText(word("新支出默认归入这个活动，直到退出。","終了するまで、新しい支出はこのシーンに入ります。"),11,color=LocalYohaku.current.muted)
        YButton(word("退出记录模式","記録モードを終了"),{vm.execute{vm.repository.setActivityMode(s.book.id,true)}})
    }}}
    if(activity==null) {
        if(s.availableActivities.isEmpty())YEmpty(word("一次旅行，一段生活。开启活动后开始记录。","旅や暮らしのひと区切り。シーンを有効にして記録しましょう。"))
        s.availableActivities.sortedWith(compareBy<ActivityEntity>{it.ended}.thenByDescending{it.startDate}).forEachIndexed{index,a->
            val spent=s.activitySpent(a.id)
            ActivityCard(index,a.name,a.startDate,a.endDate,money(spent),a.budgetMinor?.let{money(it-spent)},a.budgetMinor?.let{money(it)},spent,a.budgetMinor,
                if(a.ended)word("已结束 · 保留历史","終了済み・履歴を保存")else if(a.excludeMonthly)word("独立预算 · 不计入月预算","独立予算・月予算に含めない")else word("计入月预算","月予算に含む"),{selected=a.id})
        }
        if(s.features?.activityMode==true)YButton(word("＋ 创建活动","＋ シーンを作成"),{editing=""},primary=true)
        if(s.activities.any{it.deletedAt!=null}) {
            ThemeSection(word("已删除活动的历史","削除済みシーンの履歴"),"KEPT WITH THE ORIGINAL SCENE")
            s.activities.filter{it.deletedAt!=null}.forEach{a->YLink(a.name,{selected=a.id},Modifier.testTag("deleted-activity-${a.id}"))}
        }
    }else if(activity.deletedAt!=null) {
        YLink(word("返回全部活动","すべてのシーンへ"),{selected=null})
        YText(activity.name,20)
        YText(word("已删除 · 消费已计入日常，原活动互请保留。","削除済み・支出は日常に計上し、元のごちそうを保持。"),12)
        RecordFacts(listOf(word("原活动消费","元のシーンの支出") to money(s.activitySpent(activity.id))),audit=true)
        s.friendBalances(activity.id).forEach{pair->Panel {
            YText(pair.friend.name,16)
            RecordFacts(listOf(word("我替对方承担","自分が相手の分を負担") to money(pair.iGive),word("对方替我承担","相手が自分の分を負担") to money(pair.theyGive)))
        }}
        s.activityExpenses(activity.id).forEach{e->YLink(e.date+" · "+e.merchant.ifBlank{s.path(e.categoryId)}+" · "+money(s.netExpense(e)),{onRecord(e)})}
        s.liveTreats(activity.id).filter{it.eventId==null}.forEach{t->Panel {
            YText(s.friends.first{it.id==t.payerFriendId}.name+word("付款","の支払"),14)
            YText(t.date+" · "+t.name+" · "+Money(Currency.valueOf(t.currency),t.amountMinor).display(),12)
        }}
    }else {
        YLink(word("返回全部活动","すべてのシーンへ"),{selected=null})
        ActivityCover(activity.name,activity.startDate,activity.endDate,money(s.activitySpent(activity.id)),activity.budgetMinor?.let{money(it)}?:word("未设置","未設定"),activity.budgetMinor?.let{money(it-s.activitySpent(activity.id))}?:word("未知","不明"),s.activityExpenses(activity.id).size,activity.excludeMonthly)
        ThemeSection(word("活动与预算设置","シーンと予算の設定"),"SCENE PLANNING")
        Panel {
            YText(word("总预算：","総予算：")+(activity.budgetMinor?.let{money(it)}?:word("未设置","未設定")),12)
            activity.budgetMinor?.let{BudgetTrack(s.activitySpent(activity.id),it,12)}
            activity.budgetMinor?.let{YText(word("剩余：","残り：")+money(it-s.activitySpent(activity.id)),15,number=true)}
            s.activityBudgets.filter{it.activityId==activity.id}.forEach{b->YText((s.categories.find{it.id==b.categoryId}?.name.orEmpty())+" · "+money(s.activitySpent(activity.id,b.categoryId))+" / "+money(b.amountMinor),12)}
            YText(if(activity.excludeMonthly)word("活动消费从各历史月份预算排除，分析与资产仍保留。","シーン支出は各月予算から除外し、分析・資産には残します。")else word("活动消费计入各自然月预算。","シーン支出は各暦月の予算に含みます。"),11,color=LocalYohaku.current.muted)
            if(s.features?.activityMode==true) {
                YActionRow {
                    YButton(word("编辑活动与预算","シーンと予算を編集"),{editing=activity.id})
                    if(!activity.ended)YButton(word("进入记录模式","記録モードを開始"),{vm.execute{vm.repository.setActivityMode(s.book.id,true,activity.id)}})
                }
            }
            YLink(word("查看删除影响","削除の影響を見る"),{deleting=activity.id},Modifier.testTag("activity-delete-preview"))
            YButton(if(activity.ended)word("补录期间的支出","期間内の支出を追加")else word("记一笔活动支出","シーンの支出を記録"),{
                onExpense(activity.id,null)
            },enabled=s.features?.activityMode==true)
        }
        ThemeSection(word("主动预留","資金を確保"),"MONEY SET ASIDE")
        Panel {
            YText(money(s.activityHeld(activity.id)),26,number=true)
            YText(word("预留不扣账户余额；使用或释放后重新计算。","確保は口座残高を変更せず、使用・解放後に再計算します。"),11,color=LocalYohaku.current.muted)
            s.reserves.filter{it.activityId==activity.id}.forEach{r->
                YText(r.name+" · "+r.date+" · "+money(s.reserveRemaining(r)),14)
                s.reserveAccountRemaining(r)?.let{YText((s.accounts.find{it.id==r.accountId}?.name.orEmpty())+" · "+it.display(),11)}
                YActionRow {
                    if(r.active&&!activity.ended&&s.features?.activityMode==true)YLink(word("调整预留","確保を調整"),{reserveFor="edit:"+r.id})
                    if(s.reserveRemaining(r)>0)YLink(word("释放剩余预留","残りの確保を解放"),{reserveFor="release:"+r.id})
                }
            }
            if(!activity.ended&&s.features?.activityMode==true)YButton(word("＋ 预留资金","＋ 資金を確保"),{reserveFor=activity.id})
        }
        ThemeSection(word("这个活动的朋友","このシーンの友達"),"FRIENDS IN THIS SCENE")
        s.friendBalances(activity.id).forEachIndexed{index,b->
            val note=if(b.difference==0L)word("本活动已平衡","このシーンは均衡")else (if(b.difference>0)word("我多替对方承担：","自分が多く負担：")else word("对方多替我承担：","相手が多く負担："))+money(kotlin.math.abs(b.difference))
            val total=b.iGive.toDouble()+b.theyGive.toDouble()
            FriendBalanceCard(b.friend.name,money(b.iGive),money(b.theyGive),note,if(total==0.0)null else(b.iGive/total).toFloat(),index,{friendDetail=b.friend.id})
        }
        YText(word("仅本活动的互请，不作为必须追讨的欠款。","このシーン内のごちそうのみ。返済を求める債務ではありません。"),10,color=LocalYohaku.current.muted)
        Panel {
            if(s.features?.activityMode==true) {
                val memberIds=s.participants.filter{it.activityId==activity.id}.map{it.friendId}
                s.friends.filter{it.active&&it.id !in memberIds}.forEach{f->YLink(word("加入：","追加：")+f.name,{vm.execute{vm.repository.addParticipant(s.book.id,activity.id,f.id)}})}
                YField(word("新朋友名称","新しい友達の名前"),friendName,{friendName=it})
                YButton(word("添加朋友","友達を追加"),{vm.execute(onSuccess={friendName=""}){val id=vm.repository.saveFriend(s.book.id,friendName);vm.repository.addParticipant(s.book.id,activity.id,id)}},enabled=friendName.isNotBlank()&&!busy)

            }
        }
        if(s.features?.activityMode==true&&s.participants.any{it.activityId==activity.id})YButton(if(activity.ended)word("补录期间的朋友付款","期間内の友達の支払を追加")else word("别人付款 · 记录互请","友達の支払・ごちそうを記録"),{treatFriend=null;treatId=null;treatFor=activity.id})
        ThemeSection(word("活动内的事实","シーンの記録"),"THE SHARED MOMENTS")
        s.activityExpenses(activity.id).groupBy{it.date}.toSortedMap().forEach{(date,events)->Panel {
            YText(date,13)
            events.forEach{e->YLink(e.merchant.ifBlank{s.categories.find{it.id==e.categoryId}?.name.orEmpty()}+" · "+money(s.netExpense(e)),{onRecord(e)})}
        }}
        s.liveTreats(activity.id).filter{it.eventId==null}.forEach{t->Panel {
            YText((s.friends.find{it.id==t.payerFriendId}?.name.orEmpty())+word("付款","の支払"),17)
            YText(t.date+" · "+t.name+" · "+Money(Currency.valueOf(t.currency),t.amountMinor).display(),12)
            YText(word("仅互请份额；不记本人消费或账户扣款。","ごちそうの取り分のみ。自分の支出・口座引落は記録しません。"),10,color=LocalYohaku.current.muted)
            YActionRow {
                YButton(word("修正份额","取り分を修正"),{treatFriend=null;treatId=t.id;treatFor=activity.id})
                YLink(word("删除这次互请","このごちそうを削除"),{vm.execute{vm.repository.removeOtherTreat(s.book.id,t.id)}})
            }
        }}
    }
    friendDetail?.let{friendId->activity?.let{a->YSheet(s.friends.first{it.id==friendId}.name,{friendDetail=null}) {
        val pair=s.friendBalances(a.id).first{it.friend.id==friendId}
        YLead(word("我和","自分と")+pair.friend.name,a.name+" · "+a.startDate+" — "+a.endDate,"WITHIN THIS SCENE",money(kotlin.math.abs(pair.difference)),
            if(pair.difference==0L)word("本活动已平衡","このシーンは均衡")else if(pair.difference>0)word("你多替对方承担","自分が多く負担")else word("对方多替你承担","相手が多く負担"))
        RecordFacts(listOf(word("你替对方承担","自分が相手の分を負担") to money(pair.iGive),word("对方替你承担","相手が自分の分を負担") to money(pair.theyGive)))
        if(s.features?.activityMode==true&&a.deletedAt==null) {
            @Composable fun paymentButtons(first:Modifier,second:Modifier) {
                YButton(if(a.ended)word("补录期间的我付款","期間内の自分の支払を追加")else word("我请客","ごちそうした"),{friendDetail=null;onExpense(a.id,friendId)},first,primary=true)
                YButton(if(a.ended)word("补录期间的对方付款","期間内の友達の支払を追加")else word("别人请我","ごちそうになった"),{friendDetail=null;treatFriend=friendId;treatId=null;treatFor=a.id},second)
            }
            val config=LocalConfiguration.current
            if(a.ended||config.screenWidthDp<=360&&config.fontScale>1.2f)Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){paymentButtons(Modifier.fillMaxWidth(),Modifier.fillMaxWidth())}
            else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){paymentButtons(Modifier.weight(1f),Modifier.weight(1f))}
        }
        ThemeSection(word("本活动往来明细","このシーンのごちそう"),"ACTUAL CONTRIBUTIONS")
        s.liveTreats(a.id).filter{t->t.payerFriendId==friendId||t.payerFriendId==null&&s.allocations.any{it.treatId==t.id&&it.friendId==friendId}}.forEach{t->
            val portion=s.allocations.first{it.treatId==t.id&&if(t.payerFriendId==null)it.friendId==friendId else it.participant=="ME"}
            val original=t.eventId?.let{id->s.visibleEvents.find{it.id==id}}
            PairHistoryRow(t.date,t.name,t.payerFriendId==null,Money(Currency.valueOf(t.currency),t.amountMinor).display(),money(portion.baseMinor),original?.let{e->{friendDetail=null;onRecord(e)}})

        }
    }}}
    deleting?.let{id->s.activities.find{it.id==id}?.let{a->ActivityDeletionSheet(a,vm,{deleting=null},{deleting=null;selected=null})}}
    editing?.let{id->ActivityEditor(s,s.activities.find{it.id==id},vm,{editing=null})}
    reserveFor?.let{id->ReserveEditor(s,id,vm,{reserveFor=null})}
    treatFor?.let{id->OtherTreatEditor(s,id,treatId,vm,treatFriend,{treatFor=null;treatId=null;treatFriend=null})}
}

@Composable private fun ActivityEditor(s:BookSnapshot,old:ActivityEntity?,vm:AppViewModel,onDismiss:()->Unit) {
    val currency=Currency.valueOf(s.book.currency)
    var name by rememberSaveable{mutableStateOf(old?.name.orEmpty())}
    var start by rememberSaveable{mutableStateOf(old?.startDate?:LocalDate.now(vm.repository.clock).toString())}
    var end by rememberSaveable{mutableStateOf(old?.endDate?:start)}
    var amount by rememberSaveable{mutableStateOf(old?.budgetMinor?.let{Money(currency,it).decimal.toPlainString()}.orEmpty())}
    var categoryValues by remember{mutableStateOf(s.activityBudgets.filter{it.activityId==old?.id}.associate{it.categoryId to Money(currency,it.amountMinor).decimal.toPlainString()})}
    var excluded by rememberSaveable{mutableStateOf(old?.excludeMonthly?:false)}
    var ended by rememberSaveable{mutableStateOf(old?.ended?:false)}
    var review by remember{mutableStateOf<ActivityReview?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(word("活动与预算","シーンと予算"),onDismiss) {
        if(review==null) {
            YField(word("活动名称","シーン名"),name,{name=it})
            ActivityDate(word("开始日期","開始日"),start,{start=it});ActivityDate(word("结束日期","終了日"),end,{end=it})
            YField(word("总预算 / 可留空","総予算・空欄可"),amount,{amount=it},numeric=true)
            s.categories.filter{(s.categoryEnabled(it.id)||it.id in categoryValues)&&it.kind==EntryKind.EXPENSE.name}.forEach{c->YField(c.name+word("预算 / 可留空","予算・空欄可"),categoryValues[c.id].orEmpty(),{categoryValues=categoryValues+(c.id to it)},numeric=true)}
            YToggle(word("从月预算排除","月予算から除外"),excluded,{excluded=it})
            YToggle(word("结束活动并释放预留","終了して確保を解放"),ended,{if(old?.ended!=true)ended=it})
            YButton(word("预览影响","影響を確認"),{vm.execute{review=vm.repository.previewActivity(s.book.id,ActivityInput(name,start,end,amount.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency)},categoryValues.filterValues{it.isNotBlank()}.mapValues{Money.parse(it.value,currency)},excluded,ended,old?.id))}},primary=true,enabled=!busy)
        }else {
            YText(name,21);YText(start+" — "+end,12)
            review!!.months.forEach{(before,after)->YText(before.month+word(" 月预算消费："," 月予算の支出：")+Money(currency,before.spentMinor).display()+" → "+Money(currency,after.spentMinor).display(),12)}
            if(ended)YText(word("结束保留全部历史，并释放剩余预留。","終了後も履歴を保存し、残りの確保を解放します。"),12)
            YActionRow {
                YButton(word("确认保存","保存を確定"),{val r=review!!;vm.execute(onSuccess=onDismiss){check(vm.repository.commitActivity(r)){"资料已变化，请重新预览 / 情報が変わりました。再確認してください"}}},primary=true,enabled=!busy)
                YLink(word("返回修改","編集に戻る"),{review=null})
            }
        }
    }
}

@Composable private fun ReserveEditor(s:BookSnapshot,target:String,vm:AppViewModel,onDismiss:()->Unit) {
    val releasing=target.startsWith("release:")
    val old=s.reserves.find{it.id==target.removePrefix("release:").removePrefix("edit:")}
    val activityId=old?.activityId?:target
    val currency=Currency.valueOf(s.book.currency)
    var name by rememberSaveable{mutableStateOf(old?.name.orEmpty())}
    var date by rememberSaveable{mutableStateOf(old?.date?:LocalDate.now(vm.repository.clock).toString())}
    var amount by rememberSaveable{mutableStateOf(if(releasing)Money(currency,s.reserveRemaining(old!!)).decimal.toPlainString()else old?.let{Money(currency,it.amountMinor).decimal.toPlainString()}.orEmpty())}
    var accountId by rememberSaveable{mutableStateOf(old?.accountId)}
    var accountAmount by rememberSaveable{mutableStateOf(old?.accountMinor?.let{Money(Currency.valueOf(old.accountCurrency!!),it).decimal.toPlainString()}.orEmpty())}
    var occurrenceId by rememberSaveable{mutableStateOf(old?.occurrenceId)}
    var review by remember{mutableStateOf<ReserveReview?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(if(releasing)word("释放预留","確保を解放")else word("主动预留资金","資金を確保"),onDismiss) {
        if(review==null) {
            if(!releasing) {
                YField(word("预留名称","確保の名前"),name,{name=it});ActivityDate(word("使用月份日期","使用予定日"),date,{date=it})
                YSelect(word("来源账户 / 可选","元の口座・任意"),accountId,listOf(null to word("未指定","未指定"))+s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}.map{it.id as String? to it.name},{accountId=it;accountAmount=""})
                accountId?.let{id->YField(word("账户币种预留金额","口座通貨の確保額")+" · "+s.accounts.first{it.id==id}.currency,accountAmount,{accountAmount=it},numeric=true)}
                YSelect(word("关联固定账单 / 可选","定期予定との関連・任意"),occurrenceId,listOf(null to word("无","なし"))+s.occurrences.filter{it.activityId==activityId&&(it.status=="PENDING"||it.id==old?.occurrenceId)}.map{it.id as String? to it.name+" · "+it.scheduledDate},{occurrenceId=it})
            }
            YField(word("本位币金额","基準通貨の金額")+" · "+currency.name,amount,{amount=it},numeric=true)
            YButton(word("预览影响","影響を確認"),{vm.execute{review=if(releasing)vm.repository.previewRelease(s.book.id,old!!.id,Money.parse(amount,currency))else vm.repository.previewReserve(s.book.id,ReserveInput(activityId,name,date,Money.parse(amount,currency),accountId,accountId?.let{id->Money.parse(accountAmount,Currency.valueOf(s.accounts.first{it.id==id}.currency))},occurrenceId,old?.id))}},primary=true,enabled=!busy)
        }else {
            val r=review!!
            YText(r.before.month,17)
            YText(word("月预算预留：","月予算の確保：")+(r.before.reserveMinor?.let{Money(currency,it).display()}?:"—")+" → "+(r.after.reserveMinor?.let{Money(currency,it).display()}?:"—"),15)
            YText(word("资金总预留：","資金の確保総額：")+Money(currency,r.before.fundReserveMinor).display()+" → "+Money(currency,r.after.fundReserveMinor).display(),15)
            YText(word("账户余额保持不变。","口座残高は変わりません。"),12)
            YActionRow {
                YButton(word("确认","確定"),{vm.execute(onSuccess=onDismiss){check(vm.repository.commitReserve(r)){"资料已变化，请重新预览 / 情報が変わりました。再確認してください"}}},primary=true,enabled=!busy)
                YLink(word("返回修改","編集に戻る"),{review=null})
            }
        }
    }
}

@Composable private fun ActivityDate(label:String,date:String,onDate:(String)->Unit) {
    var owner by remember{mutableStateOf<Int?>(null)}
    YDateFields(listOf(label to date),owner,{owner=it},{_,value->onDate(value)})
}

@Composable internal fun TreatSplitFields(s:BookSnapshot,activityId:String,selected:List<String>,mode:TreatSplitMode,weights:Map<String,String>,onFriends:(List<String>)->Unit,onMode:(TreatSplitMode)->Unit,onWeights:(Map<String,String>)->Unit) {
    val friends=s.participants.filter{it.activityId==activityId}.mapNotNull{p->s.friends.find{it.id==p.friendId}}
    friends.forEach{f->YToggle(f.name,f.id in selected,{included->onFriends(if(included)selected+f.id else selected-f.id);if(!included&&weights["__tail"]==f.id)onWeights(weights-"__tail")})}
    YSelect(word("份额分配","取り分の分け方"),mode.name,TreatSplitMode.entries.map{it.name to when(it){TreatSplitMode.EQUAL->word("均分","均等");TreatSplitMode.AMOUNTS->word("指定金额","金額指定");TreatSplitMode.PERCENT->word("指定比例","割合指定")}}, {value->onMode(TreatSplitMode.valueOf(value))})
    if(mode!=TreatSplitMode.EQUAL)(listOf("ME" to word("本人","自分"))+friends.filter{it.id in selected}.map{it.id to it.name}).forEach{(id,name)->
        YField(name+if(mode==TreatSplitMode.PERCENT)" %" else word("份额","の取り分"),weights[id].orEmpty(),{onWeights(weights+(id to it))},numeric=true)
    }
    if(mode==TreatSplitMode.PERCENT) {
        val choices=listOf("ME" to word("本人","自分"))+friends.filter{it.id in selected}.map{it.id to it.name}
        YSelect(word("尾差归属","端数の受取人"),weights["__tail"]?.takeIf{key->choices.any{it.first==key}}?:choices.last().first,choices,{onWeights(weights+("__tail" to it))})
    }
    YText(word("本人付款的整单只记一次；按份额计算本活动内与每位朋友的往来。","自分の支払は全体額を一度だけ記録。取り分から、このシーンの友達ごとの往来を計算します。"),11,color=LocalYohaku.current.muted)
}

@Composable private fun OtherTreatEditor(s:BookSnapshot,activityId:String,treatId:String?,vm:AppViewModel,initialFriendId:String?,onDismiss:()->Unit) {
    val old=s.treats.find{it.id==treatId}
    var payer by rememberSaveable{mutableStateOf(old?.payerFriendId?:initialFriendId?:s.participants.firstOrNull{it.activityId==activityId}?.friendId.orEmpty())}
    var name by rememberSaveable{mutableStateOf(old?.name.orEmpty())}
    val activity=s.activities.first{it.id==activityId}
    var date by rememberSaveable{mutableStateOf(old?.date?:LocalDate.now(vm.repository.clock).coerceIn(LocalDate.parse(activity.startDate),LocalDate.parse(activity.endDate)).toString())}
    var currency by rememberSaveable{mutableStateOf(old?.currency?:s.book.currency)}
    var amount by rememberSaveable{mutableStateOf(old?.let{Money(Currency.valueOf(it.currency),it.amountMinor).decimal.toPlainString()}.orEmpty())}
    var base by rememberSaveable{mutableStateOf(old?.let{Money(Currency.valueOf(s.book.currency),it.baseMinor).decimal.toPlainString()}.orEmpty())}
    var selected by remember{mutableStateOf(if(old==null)listOf(payer)else s.allocations.filter{it.treatId==old.id}.mapNotNull{it.friendId})}
    var mode by rememberSaveable{mutableStateOf(old?.mode?:TreatSplitMode.EQUAL.name)}
    var weights by remember{mutableStateOf(old?.let{vm.repository.json.decodeFromString<Map<String,String>>(it.weightsJson)}?:emptyMap())}
    var review by remember{mutableStateOf<TreatReview?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(word("别人付款 · 活动内互请","友達の支払・シーンのごちそう"),onDismiss) {
        if(review==null) {
            YField(word("这次是什么","何の支払？"),name,{name=it});ActivityDate(word("发生日期","記録日"),date,{date=it})
            YSelect(word("付款朋友","支払った友達"),payer,s.participants.filter{it.activityId==activityId}.map{p->p.friendId to s.friends.first{it.id==p.friendId}.name},{payer=it;if(it !in selected)selected=selected+it})
            YSelect(word("原始币种","元の通貨"),currency,Currency.entries.map{it.name to it.name},{currency=it})
            YField(word("整单金额","全体額"),amount,{amount=it},numeric=true)
            if(currency!=s.book.currency)YField(word("本位币折算","基準通貨への換算")+" · "+s.book.currency,base,{base=it},numeric=true)
            TreatSplitFields(s,activityId,selected,TreatSplitMode.valueOf(mode),weights,{selected=it},{mode=it.name},{weights=it})
            YButton(word("预览份额","取り分を確認"),{vm.execute {
                val total=Money.parse(amount,Currency.valueOf(currency))
                review=vm.repository.previewOtherTreat(s.book.id,OtherTreatInput(activityId,payer,total,if(currency==s.book.currency)total else Money.parse(base,Currency.valueOf(s.book.currency)),date,name,selected,TreatSplitMode.valueOf(mode),weights,old?.id))
            }},primary=true,enabled=!busy)
        }else {
            val r=review!!
            YText(word("不产生本人消费或账户扣款。","自分の支出・口座引落は発生しません。"),13)
            r.change.allocations.forEach{a->YText((if(a.participant=="ME")word("本人","自分")else s.friends.first{it.id==a.friendId}.name)+" · "+Money(Currency.valueOf(r.change.treat.currency),a.amountMinor).display()+" / "+Money(Currency.valueOf(s.book.currency),a.baseMinor).display(),14)}
            r.after.forEach{b->YText(b.friend.name+word("差额：","の差額：")+Money(Currency.valueOf(s.book.currency),r.before.find{it.friend.id==b.friend.id}?.difference?:0).display()+" → "+Money(Currency.valueOf(s.book.currency),b.difference).display(),15)}
            YActionRow {
                YButton(word("确认互请记录","ごちそう記録を確定"),{vm.execute(onSuccess=onDismiss){check(vm.repository.commitOtherTreat(r)){"资料已变化，请重新预览 / 情報が変わりました。再確認してください"}}},primary=true,enabled=!busy)
                YLink(word("返回修改","編集に戻る"),{review=null})
            }
        }
    }
}
