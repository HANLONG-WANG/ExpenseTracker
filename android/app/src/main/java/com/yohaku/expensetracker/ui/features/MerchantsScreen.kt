package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.merchant.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import com.yohaku.expensetracker.ui.haptics.Haptic

@Composable internal fun MerchantsScreen(s:BookSnapshot,vm:AppViewModel,onRecord:(EventEntity)->Unit,onEntry:(String)->Unit) {
    var editing by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var newName by rememberSaveable(s.book.id){mutableStateOf("")}
    var selected by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var expanded by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var choosingMerge by rememberSaveable(s.book.id){mutableStateOf(false)}
    var mergeTarget by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var deleting by rememberSaveable(s.book.id){mutableStateOf(false)}
    var organizing by rememberSaveable(s.book.id){mutableStateOf(false)}
    var history by remember(s.book.id){mutableStateOf<MerchantHistoryScope?>(null)}
    val duplicateGroups=s.merchants.groupBy{normalizedMerchantName(it.name)}.filterValues{it.size>1}
    var locationFor by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var locationId by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    YTitle(word("常去的地方，留下名字。","いつものお店を、その名前で。"),"PLACES OF EVERYDAY LIFE",pageFlow=true)
    val merchant=s.merchants.find{it.id==selected}
    if(merchant==null) {
        YLink(word("操作历史","操作履歴"),{history=MerchantHistoryScope()},Modifier.testTag("merchant-history-open"),arrow=true)
        duplicateGroups.values.forEach { group->
            MerchantHistoryPaper(modifier=Modifier.testTag("merchant-duplicate-group"),heading=word("注意","注意")) {
                YText(word("存在多个同名商户，需先合并或改名区分","同名のお店が複数あります。統合するか名前を変えて区別してください"),14)
                group.forEach { m->
                    YLink(m.name,{selected=m.id},Modifier.testTag("merchant-duplicate-${m.id}"))
                    YText(listOf(m.note.ifBlank{word("暂无备注","メモなし")},s.merchantLocations.filter{it.merchantId==m.id}.joinToString(" · "){it.name}.ifBlank{word("未保存地点","場所未保存")},if(m.active)word("启用","有効")else word("停用","停止")).joinToString(" · "),12)
                }
            }
        }
        if(s.merchants.isEmpty())YEmpty(word("保存常去商户与地点，记账时可以主动选择。","いつものお店と場所を保存すると、記録で選べます。"))
        val counts=s.visibleEvents.groupingBy{s.merchantKey(it)}.eachCount()
        val locations=s.merchantLocations.groupBy{it.merchantId}
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            s.merchants.chunked(2).forEach{row->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    row.forEach{m->
                        val places=locations[m.id].orEmpty()
                        val count=counts["id:${m.id}"]?:0
                        val secondary=word("$count 笔","${count} 件")+when(places.size){0->"";1->" · "+places.first().name;else->word(" · ${places.size} 个地点"," · ${places.size} か所")}
                        MerchantPlaceCard(m.id,m.name,secondary,m.active,expanded==m.id,{expanded=if(expanded==m.id)null else m.id},Modifier.weight(1f).fillMaxHeight())
                    }
                    if(row.size==1)Spacer(Modifier.weight(1f))
                }
                row.firstOrNull{it.id==expanded}?.let{m->MerchantExpanded(m.id,locations[m.id].orEmpty().map{it.name},m.note,m.active,{selected=m.id},{onEntry(m.id)})}
            }
        }
        YButton(word("＋ 保存商户","＋ お店を保存"),{newName="";editing=""},Modifier.testTag("merchant-add"),primary=true)
        val names=s.visibleEvents.filter{it.kind==EntryKind.EXPENSE.name&&it.merchant.isNotBlank()&&it.id !in s.merchantByEvent}
            .groupBy{normalizedMerchantName(it.merchant)}
        if(names.isNotEmpty())ThemeSection(word("记录中出现过的原名","記録にある元の名前"),"FROM YOUR RECORDS")
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            names.values.toList().chunked(2).forEach{row->Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach{events->val name=events.first().merchant
                    MerchantPlaceCard("recorded-${events.first().id}",name,word("${events.size} 笔","${events.size} 件"),true,null,{newName=name;editing=""},Modifier.weight(1f).fillMaxHeight(),ambiguous=normalizedMerchantName(name) in duplicateGroups)
                }
                if(row.size==1)Spacer(Modifier.weight(1f))
            }}
        }
        MerchantLocationNotice()
        MerchantLocationPreferences(s,vm,onEntry)
    }else {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            YLink(word("全部商户","すべてのお店"),{selected=null},back=true)
            YLink(word("相关历史","関連する履歴"),{history=MerchantHistoryScope(merchant.id,merchant.name)},Modifier.testTag("merchant-related-history"),arrow=true)
        }
        YLead(merchant.name,word("改名后，名下记录、模板和周期账单会同步。","名前の変更は関連する記録・テンプレート・定期支払いにも反映します。"),"YOUR SAVED PLACES",pageFlow=true)
        Panel {
            YText(merchant.note.ifBlank{word("暂无备注","メモなし")},13)
            YText(word("改名会同步记录；只改备注或启停不会整理记录。","名前の変更は記録に反映します。メモ・有効状態だけの変更は記録を整理しません。"),11,color=LocalYohaku.current.muted)
        }
        YActionRow {
            YButton(word("编辑资料","情報を編集"),{editing=merchant.id},Modifier.testTag("merchant-edit"))
            YButton(word("合并商户","お店を統合"),{choosingMerge=true},Modifier.testTag("merchant-merge"),enabled=s.merchants.size>1)
            YLink(word("删除商户","お店を削除"),{deleting=true},Modifier.testTag("merchant-delete"))
        }
        if(deleting)MerchantOperationConfirmation(s,MerchantOperationInput(MerchantOperationType.DELETE,merchant.id),vm,
            word("删除商户","お店を削除"),{deleting=false},{selected=null;deleting=false},delete=true)
        val pending=s.eventPlaces.count{p->s.merchantByEvent[p.eventId]==merchant.id}
        if(pending>0)YButton(word("整理待处理位置（$pending）","未整理の場所（$pending）"),{organizing=true},Modifier.testTag("merchant-organize-places"))
        if(organizing)MerchantOperationConfirmation(s,MerchantOperationInput(MerchantOperationType.ORGANIZE_LOCATIONS,merchant.id),vm,
            word("整理待处理位置","未整理の場所を整理"),{organizing=false},{organizing=false})
        ThemeSection(word("保存地点","保存した場所"),"SAVED PLACES")
        s.merchantLocations.filter{it.merchantId==merchant.id}.forEach{l->Panel {
            YText(l.name,18)
            YText(l.latitude?.let{"$it, ${l.longitude} · ${l.radiusMeters}m"}?:word("仅地点名称，未填写坐标","場所名のみ・座標未入力"),11)
            YActionRow {
                YButton(word("编辑地点","場所を編集"),{locationId=l.id;locationFor=merchant.id})
                YLink(word("移除保存地点","保存した場所を削除"),{vm.execute{vm.repository.removeMerchantLocation(s.book.id,l.id);vm.haptic(Haptic.Removed)}})
            }
        }}
        YButton(word("＋ 添加地点","＋ 場所を追加"),{locationId=null;locationFor=merchant.id},primary=true)
        MerchantLocationPreferences(s,vm,onEntry)
        ThemeSection(word("关联这个商户的记录","このお店に関連する記録"),"LINKED RECORDS")
        val events=s.visibleEvents.filter{e->e.kind!=EntryKind.REFUND.name&&s.merchantKey(e)=="id:${merchant.id}"}
        if(events.isEmpty())YEmpty(word("选择这个商户记账后，记录会显示在这里。","このお店を選んで記録すると表示されます。"))
        events.forEach{e->EventRow(s,e,{onRecord(e)},last=e===events.last())}
        val refunds=s.visibleEvents.filter{it.kind==EntryKind.REFUND.name&&s.merchantKey(it)=="id:${merchant.id}"}
        if(refunds.isNotEmpty())ThemeSection(word("归属这个商户的退款","このお店の返金"),"REFUNDS")
        refunds.forEach{e->EventRow(s,e,{onRecord(e)},last=e===refunds.last())}
    }
    if(merchant!=null&&choosingMerge) {
        var query by rememberSaveable{mutableStateOf("")}
        YSheet(word("选择合并到的商户","統合先のお店を選択"),{choosingMerge=false}) {
            YField(word("搜索商户","お店を検索"),query,{query=it})
            s.merchants.filter{it.id!=merchant.id&&normalizedMerchantName(it.name).contains(normalizedMerchantName(query))}.forEach{target->
                MerchantHistoryPaper(heading=word("可合并商户","統合先候補")) {
                    YButton(target.name,{choosingMerge=false;mergeTarget=target.id},Modifier.testTag("merchant-merge-target-${target.id}"))
                    YText(listOf(target.note,s.merchantLocations.filter{it.merchantId==target.id}.joinToString(" · "){it.name},if(target.active)word("启用","有効")else word("停用","停止")).filter{it.isNotBlank()}.joinToString(" · "),12)
                }
            }
        }
    }
    history?.let{MerchantHistorySheet(s.book.id,vm,it,{history=null})}
    val target=s.merchants.find{it.id==mergeTarget}
    if(merchant!=null&&target!=null)MerchantOperationConfirmation(s,MerchantOperationInput(MerchantOperationType.MERGE,merchant.id,target.id),vm,
        word("合并商户","お店を統合"),{mergeTarget=null},{selected=target.id;mergeTarget=null},merge=true)
    editing?.let{id->MerchantEditor(s,s.merchants.find{it.id==id},newName,vm,{editing=null},{selected=it})}
    locationFor?.let{id->LocationEditor(s,id,s.merchantLocations.find{it.id==locationId},vm,{locationFor=null;locationId=null})}
}
private data class RecordedPlaceChoice(val keep:Boolean=true,val name:String,val latitude:Double,val longitude:Double,val radius:String="100")

@Composable internal fun MerchantImpactCountsText(c:MerchantImpactCounts,modifier:Modifier=Modifier) {
    val rows=listOf(
        listOf(word("正常记录","通常の記録") to c.affectedRecords-c.trashedRecords,word("回收站","ごみ箱") to c.trashedRecords),
        listOf(word("新关联","新規関連") to c.newlyLinkedRecords,word("改名","名前変更") to c.renamedRecords,word("退款","返金") to c.linkedRefunds),
        listOf(word("模板","テンプレート") to c.templates,word("周期","定期") to c.recurrenceRules,word("草稿","下書き") to c.drafts),
        listOf(word("保存地点","保存場所") to c.savedLocations,word("待整理位置","未整理の位置") to c.pendingPlaces),
    ).mapNotNull { row->row.filter{it.second>0}.takeIf{it.isNotEmpty()}?.joinToString(" · "){"${it.first} ${it.second}"} }
    YText(rows.joinToString("\n").ifEmpty{word("不影响其他记录","ほかの記録には影響しません")},12,modifier)
}

@Composable private fun MerchantEditor(s:BookSnapshot,old:MerchantEntity?,initialName:String,vm:AppViewModel,onDismiss:()->Unit,onMerged:(String)->Unit) {
    var name by rememberSaveable{mutableStateOf(old?.name?:initialName)}
    var note by rememberSaveable{mutableStateOf(old?.note.orEmpty())}
    var active by rememberSaveable{mutableStateOf(old?.active?:true)}
    val createdId=remember{vm.repository.id()}
    var confirming by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val originalName=initialName.takeIf{old==null&&it.isNotBlank()}
    val matches=s.merchants.filter{it.id!=old?.id&&normalizedMerchantName(it.name)==normalizedMerchantName(name)}
    // A spelling-only update retains the selected identity, even with historical duplicates.
    val collision=(old==null||normalizedMerchantName(old.name)!=normalizedMerchantName(name))&&matches.isNotEmpty()
    val target=if(collision)matches.singleOrNull()else null
    val input=when {
        target!=null&&old!=null->MerchantOperationInput(MerchantOperationType.MERGE,old.id,target.id,note=note,createdId=createdId)
        target!=null&&originalName!=null->MerchantOperationInput(MerchantOperationType.ASSIGN_ORIGINAL_NAME,targetId=target.id,originalName=originalName,note=note,createdId=createdId)
        else->merchantSaveInput(name,note,old,active,originalName,createdId)
    }
    val calculated=runCatching{s.prepareMerchantOperation(input)}
    if(!confirming)YSheet(word("商户资料","お店の情報"),onDismiss) {
        originalName?.let{YText(word("来源原名组：$it","元の名前グループ：$it"),13,Modifier.testTag("merchant-original-group"))}
        YField(word("商户名称","お店の名前"),name,{name=it})
        calculated.getOrNull()?.let{MerchantImpactCountsText(it.preview.counts,Modifier.testTag("merchant-name-impact"))}
        YField(word("备注","メモ"),note,{note=it},multiline=true)
        if(target==null)YToggle(word("启用候选选择","選択候補として有効"),active,{active=it})
        else YText(word("沿用「${target.name}」的名称和启停；备注加入目标资料。","「${target.name}」の名前・有効状態を保持し、メモを追加します。"),12)
        if(name.isNotBlank())calculated.exceptionOrNull()?.message?.let{YText(it,12)}
        YButton(word("保存商户资料","お店の情報を保存"),{
            val prepared=calculated.getOrThrow()
            if(input.type in setOf(MerchantOperationType.MERGE,MerchantOperationType.ASSIGN_ORIGINAL_NAME)||prepared.preview.counts.newlyLinkedRecords>0||prepared.preview.counts.pendingPlaces>0||
                input.type==MerchantOperationType.RENAME&&(prepared.preview.counts.templates>0||prepared.preview.counts.recurrenceRules>0))confirming=true
            else vm.execute(onSuccess={vm.haptic(Haptic.Done);onDismiss()}){vm.repository.commitMerchantEdits(prepared)}
        },Modifier.testTag("merchant-save"),primary=true,enabled=!busy&&calculated.isSuccess)
    }
    if(confirming)MerchantOperationConfirmation(s,input,vm,
        if(input.type==MerchantOperationType.MERGE)word("合并商户","お店を統合")else word("归入同名记录","同名の記録を取り込む"),
        {confirming=false},{id->if(target!=null)onMerged(requireNotNull(id));onDismiss()},merge=input.type==MerchantOperationType.MERGE)
}

/** Keep the displayed preview fixed until confirmation; stale commits refresh instead of expanding scope. */
@Composable private fun MerchantOperationConfirmation(s:BookSnapshot,initial:MerchantOperationInput,vm:AppViewModel,title:String,
    onDismiss:()->Unit,onSaved:(String?)->Unit,merge:Boolean=false,delete:Boolean=false) {
    val stable=remember(initial.type,initial.merchantId,initial.targetId,initial.name,initial.note){initial}
    var base by remember(stable){mutableStateOf(s.prepareMerchantOperation(stable))}
    val places=remember(base){base.after.recordedPlacesForEvents(base.pendingEventIds)}
    val defaults=places.map{RecordedPlaceChoice(name=word("${it.latestDate} 记账处","${it.latestDate} の記録場所"),latitude=it.latitude,longitude=it.longitude)}
    val choices=remember(base){mutableStateListOf<RecordedPlaceChoice>().apply{addAll(defaults)}}
    var adjusting by remember(base){mutableStateOf<Int?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val decisions=places.mapIndexed{index,p->val c=choices[index];MerchantPlaceDecision(p.eventIds,
        if(c.keep)MerchantPlaceInput(c.name,c.latitude,c.longitude,c.radius.toIntOrNull()?:0)else null)}
    val prepared=runCatching{base.before.prepareMerchantOperation(stable.copy(places=decisions))}
    if(adjusting==null)YSheet(title,onDismiss) {
        val source=base.before.merchants.find{it.id==stable.merchantId}
        val target=base.after.merchants.find{it.id==base.targetId}
        if(delete) {
            YText(word("删除「${source?.name}」及保存地点，保留记录文字与操作历史。此操作不能撤销；同名组如变为唯一商户，会重新归属。",
                "「${source?.name}」と保存場所を削除し、記録の文字と操作履歴を保持します。取り消せません。同名のお店が一つになると再関連付けします。"),13)
        }else {
            stable.originalName?.let{YText(word("来源原名组：$it","元の名前グループ：$it"),13)}
            YText(word("最终商户：${target?.name} · ${if(target?.active==true)"启用"else"停用"}",
                "保存先：${target?.name} · ${if(target?.active==true)"有効"else"停止"}"),14,Modifier.testTag(if(merge)"merchant-merge-message"else"merchant-adoption-message"))
            if(merge)YText(word("来源「${source?.name}」会被删除，全部地点迁移；备注去重后加入目标，保留目标启停。合并后不能拆开。",
                "統合元「${source?.name}」を削除し、場所をすべて移動します。重複を除いたメモを追加し、統合先の有効状態を保持します。分離できません。"),13)
            if(stable.type==MerchantOperationType.ASSIGN_ORIGINAL_NAME)YText(word("填写的备注及保留地点加入目标；目标名称和启停不变。","入力したメモと保存する場所を追加し、保存先の名前と有効状態を保持します。"),13)
        }
        prepared.getOrNull()?.let{MerchantImpactCountsText(it.preview.counts,Modifier.testTag("merchant-name-impact"))
            if(it.preview.duplicateLocationIds.isNotEmpty())YText(word("有 ${it.preview.duplicateLocationIds.size} 个完全重复地点，全部保留，可稍后整理。","完全に重複する場所が ${it.preview.duplicateLocationIds.size} 件あります。すべて保持します。"),12)}
        places.forEachIndexed { index,p->val choice=choices[index]
            Panel(Modifier.testTag("recorded-place-$index")) {
                YText(base.after.merchants.find{it.id==p.merchantId}?.name.orEmpty(),13)
                YToggle(word("保留地点","場所を保存"),choice.keep,{choices[index]=choice.copy(keep=it)})
                YField(word("地点名称","場所の名前"),choice.name,{choices[index]=choices[index].copy(name=it)})
                YText(word("记了 ${p.count} 次；关闭保留即放弃这些临时位置。","${p.count} 回記録。保存をオフにすると一時位置を破棄します。"),11)
                YLink(word("在地图上调整","地図で調整"),{adjusting=index})
            }
        }
        prepared.exceptionOrNull()?.message?.let{YText(it,12)}
        YButton(if(delete)word("确认删除商户","お店の削除を確定")else if(merge)word("合并","統合")else if(stable.type==MerchantOperationType.ORGANIZE_LOCATIONS)word("完成位置整理","場所の整理を完了")else word("确认归入并保存","取り込んで保存"),{
            val review=prepared.getOrThrow();var result:String?=null
            var refreshed:PreparedMerchantOperation?=null;var failed=false
            vm.execute(onSuccess={vm.haptic(Haptic.Done);onSaved(result)},onComplete={
                refreshed?.let{base=it}?:run{if(failed)onDismiss()}
            }){
                try { result=vm.repository.commitMerchantEdits(review) }
                catch(error:kotlinx.coroutines.CancellationException){throw error}
                catch(error:Exception) {
                    failed=true
                    refreshed=runCatching{vm.repository.previewMerchantOperation(s.book.id,stable)}.getOrNull()
                    throw error
                }
            }
        },Modifier.testTag(if(delete)"merchant-delete-confirm"else if(merge)"merchant-merge-confirm"else"merchant-adoption-confirm"),primary=true,enabled=!busy&&prepared.isSuccess)
        YButton(word("取消","キャンセル"),onDismiss,Modifier.testTag(if(merge)"merchant-merge-cancel"else"merchant-adoption-cancel"))
    }
    adjusting?.let{index->val choice=choices[index]
        YSheet(word("调整地点","場所を調整"),{adjusting=null}) {
            PlacePicker(vm,choice.latitude,choice.longitude,choice.radius,{choices[index]=choices[index].copy(radius=it)}){lat,lon->choices[index]=choices[index].copy(latitude=lat,longitude=lon)}
            YButton(word("完成","完了"),{adjusting=null},primary=true)
        }
    }
}

@Composable private fun LocationEditor(s:BookSnapshot,merchantId:String,old:MerchantLocationEntity?,vm:AppViewModel,onDismiss:()->Unit) {
    var name by rememberSaveable{mutableStateOf(old?.name.orEmpty())}
    var latitude by rememberSaveable{mutableStateOf(old?.latitude)}
    var longitude by rememberSaveable{mutableStateOf(old?.longitude)}
    var radius by rememberSaveable{mutableStateOf((old?.radiusMeters?:100).toString())}
    val busy by vm.busy.collectAsStateWithLifecycle()
    YSheet(word("保存商户地点","お店の場所を保存"),onDismiss) {
        YField(word("地点名称","場所の名前"),name,{name=it})
        PlacePicker(vm,latitude,longitude,radius,{radius=it}){lat,lon->latitude=lat;longitude=lon}
        YButton(word("保存地点","場所を保存"),{vm.execute(onSuccess={vm.haptic(Haptic.Done);onDismiss()}){
            vm.repository.saveMerchantLocation(s.book.id,merchantId,name,requireNotNull(latitude),requireNotNull(longitude),radius.toIntOrNull()?:error("匹配半径请输入整数 / 選択範囲は整数で入力してください"),old?.id)
        }},Modifier.testTag("merchant-location-save"),primary=true,enabled=!busy&&latitude!=null&&longitude!=null)
    }
}

@Composable internal fun FriendsScreen(s:BookSnapshot,vm:AppViewModel) {
    var editing by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    YTitle(word("一起生活过的人。","暮らしをともにする人。"),"FRIENDS, SCENE BY SCENE",pageFlow=true)
    if(s.friends.isEmpty())YEmpty(word("添加朋友，在活动中记录相互承担的份额。","友達を追加し、シーンで取り分を記録しましょう。"))
    var selected by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    val friend=s.friends.find{it.id==selected}
    fun activityIds(id:String)=(s.participants.filter{it.friendId==id}.map{it.activityId}+s.treats.filter{it.payerFriendId==id}.map{it.activityId}).distinct()
    if(friend==null) {
        Column(Modifier.fillMaxWidth().padding(top=11.dp,bottom=8.dp)) {
            ThemeArtwork(Modifier.align(Alignment.End),118,93)
            YText(word("一起出游的人。","一緒に出かける人。"),if(LocalYohaku.current.id=="journal")29 else 27)
            YText(word("朋友资料可以复用；请客差额只在各自活动里查看，不做长期累计。","友人情報は再利用。ごちそう差額は各シーン内で、長期合算しません。"),if(LocalYohaku.current.id=="journal")13 else 11,color=LocalYohaku.current.muted,lineHeightMultiplier=2f)
        }
        s.friends.forEach{f->FriendDirectoryRow(f.id,f.name,activityIds(f.id).size,{selected=f.id})}
    }else {
        YLink(word("返回朋友名册","友達の名簿へ"),{selected=null})
        YLead(friend.name,word("每个活动单独查看，差额不跨活动累计。","シーンごとに確認。差額は合算しません。"),"SHARED MOMENTS")
        val ids=activityIds(friend.id)
        if(ids.isEmpty())YEmpty(word("还没有一起参加的活动","一緒のシーンはまだありません"))
        ids.forEach{id->s.activities.find{it.id==id}?.let{a->YLink(a.name+" · "+a.startDate+" — "+a.endDate,{vm.openActivityFriend.value=a.id to friend.id;vm.navigate("life")})}}
        YText(if(friend.active)word("可加入活动","シーンに追加できます")else word("已停用 · 历史保留","停止済み・履歴を保持"),11,color=LocalYohaku.current.muted)
        YButton(word("编辑朋友","友達を編集"),{editing=friend.id})
    }
    YText(word("往来仅在各活动内查看，不跨活动累计差额。","往来は各シーンで確認し、シーンをまたぐ差額は合算しません。"),11,color=LocalYohaku.current.muted)
    YButton(word("＋ 添加朋友","＋ 友達を追加"),{editing=""},primary=true)
    editing?.let{id->
        val old=s.friends.find{it.id==id}
        var name by rememberSaveable(id){mutableStateOf(old?.name.orEmpty())}
        var active by rememberSaveable(id){mutableStateOf(old?.active?:true)}
        val busy by vm.busy.collectAsStateWithLifecycle()
        YSheet(word("朋友资料","友達の情報"),{editing=null}) {
            YField(word("朋友名称","友達の名前"),name,{name=it});YToggle(word("启用名册选择","名簿の選択を有効"),active,{active=it})
            YButton(word("保存朋友资料","友達の情報を保存"),{vm.execute(onSuccess={vm.haptic(Haptic.Done);editing=null}){val saved=vm.repository.saveFriend(s.book.id,name,old?.id);vm.repository.setFriendActive(s.book.id,saved,active)}},primary=true,enabled=!busy)
        }
    }
}
