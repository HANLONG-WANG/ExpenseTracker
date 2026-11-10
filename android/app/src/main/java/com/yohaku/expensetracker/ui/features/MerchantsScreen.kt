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
    val busy by vm.busy.collectAsStateWithLifecycle()
    var locationFor by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var locationId by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    YTitle(word("常去的地方，留下原名。","いつものお店を、元の名前で。"),"PLACES OF EVERYDAY LIFE",pageFlow=true)
    val merchant=s.merchants.find{it.id==selected}
    if(merchant==null) {
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
        val names=s.visibleEvents.filter{it.kind==EntryKind.EXPENSE.name&&it.merchant.isNotBlank()&&s.eventMerchants.none{link->link.eventId==it.id}}
            .groupBy{normalizedMerchantName(it.merchant)}.filterKeys{key->s.merchants.none{normalizedMerchantName(it.name)==key}}
        if(names.isNotEmpty())ThemeSection(word("记录中出现过的原名","記録にある元の名前"),"FROM YOUR RECORDS")
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            names.values.toList().chunked(2).forEach{row->Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach{events->val name=events.first().merchant
                    MerchantPlaceCard("recorded-${events.first().id}",name,word("${events.size} 笔","${events.size} 件"),true,null,{newName=name;editing=""},Modifier.weight(1f).fillMaxHeight())
                }
                if(row.size==1)Spacer(Modifier.weight(1f))
            }}
        }
        MerchantLocationNotice()
        MerchantLocationPreferences(s,vm,onEntry)
    }else {
        YLink(word("全部商户","すべてのお店"),{selected=null})
        YLead(merchant.name,word("改名后，名下记录、模板和周期账单会同步。","名前の変更は関連する記録・テンプレート・定期支払いにも反映します。"),"YOUR SAVED PLACES",pageFlow=true)
        Panel {
            YText(merchant.note.ifBlank{word("暂无备注","メモなし")},13)
            YButton(word("编辑原名与备注","元の名前・メモを編集"),{editing=merchant.id})
            YText(word("保存会统一名下记录的商户名，原名不再保留。","保存時に関連記録のお店名を揃えます。元の名前は残りません。"),11,color=LocalYohaku.current.muted)
        }
        YButton(word("合并到其他商户","別のお店に統合"),{choosingMerge=true},Modifier.testTag("merchant-merge"),enabled=s.merchants.size>1)
        YLink(word("删除商户","お店を削除"),{deleting=true},Modifier.testTag("merchant-delete"))
        if(deleting)YSheet(word("删除商户","お店を削除"),{deleting=false}) {
            val count=s.visibleEvents.count{s.merchantKey(it)=="id:${merchant.id}"}
            YText(word("删除「${merchant.name}」后，保存地点也会一起删除。此操作不能撤销。","「${merchant.name}」を削除すると、保存した場所も削除されます。この操作は取り消せません。"),13)
            if(count>0)YText(word("关联的 $count 条记录会保留原来的商户文字，只是不再关联这个商户。","関連する ${count} 件の記録には元のお店名を残し、このお店との関連付けだけを解除します。"),13)
            YText(word("如果只是暂时不想在候选中出现，可以在商户资料中停用。","一時的に候補に表示したくない場合は、お店の情報から停止できます。"),13)
            YButton(word("确认删除商户","お店の削除を確定"),{vm.execute(onSuccess={
                vm.entry.value?.takeIf{it.merchantId==merchant.id}?.let{vm.entry.value=it.withoutMerchantLink()}
                deleting=false;selected=null
            }){vm.repository.deleteMerchant(s.book.id,merchant.id);vm.haptic(Haptic.Removed)}},Modifier.testTag("merchant-delete-confirm"),primary=true,enabled=!busy)
        }
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
                YButton(target.name,{choosingMerge=false;mergeTarget=target.id},Modifier.testTag("merchant-merge-target-${target.id}"))
            }
        }
    }
    val target=s.merchants.find{it.id==mergeTarget}
    if(merchant!=null&&target!=null)MerchantMergeConfirmation(s,merchant,target,!busy,{mergeTarget=null}) {
        vm.execute(onSuccess={vm.syncMerchantDraft(merchant.id,target.id,target.name);selected=target.id;mergeTarget=null;vm.haptic(Haptic.Done)}) {
            vm.repository.mergeMerchants(s.book.id,merchant.id,target.id)
        }
    }
    editing?.let{id->MerchantEditor(s,s.merchants.find{it.id==id},newName,vm,{editing=null},{selected=it})}
    locationFor?.let{id->LocationEditor(s,id,s.merchantLocations.find{it.id==locationId},vm,{locationFor=null;locationId=null})}
}
private data class RecordedPlaceChoice(val keep:Boolean=true,val name:String,val latitude:Double,val longitude:Double,val radius:String="100")

@Composable private fun MerchantEditor(s:BookSnapshot,old:MerchantEntity?,initialName:String,vm:AppViewModel,onDismiss:()->Unit,onMerged:(String)->Unit) {
    var name by rememberSaveable{mutableStateOf(old?.name?:initialName)}
    var note by rememberSaveable{mutableStateOf(old?.note.orEmpty())}
    var active by rememberSaveable{mutableStateOf(old?.active?:true)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val places=remember(s.events,s.eventPlaces,s.eventMerchants,name,old){if(old==null)s.recordedPlaces(name)else emptyList()}
    val defaults=places.map{p->RecordedPlaceChoice(name=word("${p.latestDate.substring(5).replace('-','/')} 记账处","${p.latestDate.substring(5).replace('-','/')} の記録場所"),latitude=p.latitude,longitude=p.longitude)}
    val choices=remember(name.trim(),places){mutableStateListOf<RecordedPlaceChoice>().apply{addAll(defaults)}}
    var adjusting by remember(name.trim(),places){mutableStateOf<Int?>(null)}
    var adoption by remember{mutableStateOf(false)}
    var merge by remember{mutableStateOf<MerchantEntity?>(null)}
    val adopted=s.merchantAdoptions(name,old)
    val changed=s.merchantNameChanges(name,old)
    fun save(confirmed:Boolean=false) {
        vm.execute(onSuccess={old?.let{vm.syncMerchantDraft(it.id,it.id,name.trim())};vm.haptic(Haptic.Done);onDismiss()}) {
            vm.repository.saveMerchantWithLocations(s.book.id,name,note,old?.id,active,choices.filter{it.keep}.map{
                MerchantPlaceInput(it.name,it.latitude,it.longitude,it.radius.toIntOrNull()?:error("匹配半径请输入整数 / 選択範囲は整数で入力してください"))
            },confirmAdoption=confirmed)
        }
    }
    if(!adoption&&merge==null&&adjusting==null)YSheet(word("商户资料","お店の情報"),onDismiss) {
        YField(word("商户原名","お店の元の名前"),name,{name=it})
        if(changed>0)YText(word("保存后会有 $changed 条记录的商户名改为新名称。","保存すると ${changed} 件の記録のお店名が新しい名前になります。"),12,Modifier.testTag("merchant-name-impact"))
        YField(word("备注","メモ"),note,{note=it},multiline=true)
        YToggle(word("启用候选选择","選択候補として有効"),active,{active=it})
        if(places.isNotEmpty()) {
            ThemeSection(word("记账时的位置","記録したときの場所"),"RECORDED PLACES")
            places.forEachIndexed{index,place->
                val choice=choices[index]
                Panel(Modifier.testTag("recorded-place-$index")) {
                    YToggle(word("保留地点","場所を保存"),choice.keep,{choices[index]=choices[index].copy(keep=it)})
                    YField(word("地点名称","場所の名前"),choice.name,{choices[index]=choices[index].copy(name=it)})
                    YText(word("记了 ${place.count} 次","${place.count} 回記録"),11)
                    YLink(word("在地图上调整","地図で調整"),{adjusting=index})
                }
            }
        }
        YButton(word("保存商户资料","お店の情報を保存"),{
            val renamed=old==null||normalizedMerchantName(old.name)!=normalizedMerchantName(name)
            val duplicate=s.merchants.firstOrNull{it.id!=old?.id&&normalizedMerchantName(it.name)==normalizedMerchantName(name)}
            when {
                renamed&&duplicate!=null&&old!=null->merge=duplicate
                renamed&&duplicate!=null->save() // Repository supplies the bilingual duplicate error.
                old!=null&&adopted.isNotEmpty()->adoption=true
                else->save()
            }
        },Modifier.testTag("merchant-save"),primary=true,enabled=!busy)
    }
    if(adoption)YSheet(word("归入同名记录","同名の記録を取り込む"),{adoption=false}) {
        YText(word("有 ${adopted.size} 条记录的商户写的是「${name.trim()}」。改名后它们会归到这个商户，并统一名称；支出会正式关联。","${adopted.size} 件の記録に「${name.trim()}」と書かれています。変更するとこのお店に取り込み、名前を揃え、支出を関連付けます。"),14,Modifier.testTag("merchant-adoption-message"))
        YButton(word("确认归入并保存","取り込んで保存"),{save(true)},Modifier.testTag("merchant-adoption-confirm"),primary=true,enabled=!busy)
        YButton(word("取消","キャンセル"),{adoption=false},Modifier.testTag("merchant-adoption-cancel"))
    }
    merge?.let{target->if(old!=null)MerchantMergeConfirmation(s,old,target,!busy,{merge=null},collision=true) {
        vm.execute(onSuccess={vm.syncMerchantDraft(old.id,target.id,target.name);vm.syncMerchantDraft(target.id,target.id,target.name);onMerged(target.id);onDismiss();vm.haptic(Haptic.Done)}) {
            vm.repository.mergeMerchants(s.book.id,old.id,target.id,sourceNote=note,sourceActive=active)
        }
    }}
    adjusting?.let{index->
        val choice=choices[index]
        YSheet(word("调整地点","場所を調整"),{adjusting=null}) {
            PlacePicker(vm,choice.latitude,choice.longitude,choice.radius,{choices[index]=choices[index].copy(radius=it)}){lat,lon->choices[index]=choices[index].copy(latitude=lat,longitude=lon)}
            YButton(word("完成","完了"),{adjusting=null},primary=true)
        }
    }
}
@Composable private fun MerchantMergeConfirmation(s:BookSnapshot,source:MerchantEntity,target:MerchantEntity,enabled:Boolean,onDismiss:()->Unit,collision:Boolean=false,onConfirm:()->Unit) {
    YSheet(word("合并商户","お店を統合"),onDismiss) {
        if(collision)YText(word("已有同名商户「${target.name}」。要把这两个商户合并吗？","同名のお店「${target.name}」があります。この2つのお店を統合しますか？"),14)
        val count=s.merchantMergeCount(source.id,target.id)
        val places=s.merchantLocations.count{it.merchantId==source.id}
        YText(word("把「${source.name}」并入「${target.name}」：$count 条记录、$places 个地点会归到「${target.name}」，记录上的商户名改为「${target.name}」，「${source.name}」会被删除，合并后不能拆开。","「${source.name}」を「${target.name}」に統合：${count} 件の記録と ${places} か所を「${target.name}」に移し、記録のお店名も「${target.name}」に変更します。「${source.name}」は削除され、統合後は分離できません。"),14,Modifier.testTag("merchant-merge-message"))
        YButton(word("合并","統合"),onConfirm,Modifier.testTag("merchant-merge-confirm"),primary=true,enabled=enabled)
        YButton(word("取消","キャンセル"),onDismiss,Modifier.testTag("merchant-merge-cancel"))
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
