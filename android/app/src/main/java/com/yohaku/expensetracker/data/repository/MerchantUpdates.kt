package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.merchant.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

/** The caller keeps this intent (including the source group) throughout preview and confirmation. */
data class MerchantOperationInput(
    val type:MerchantOperationType,
    val merchantId:String?=null,
    val targetId:String?=null,
    val name:String="",
    val originalName:String?=null,
    val note:String?=null,
    val active:Boolean?=null,
    val createdId:String=java.util.UUID.randomUUID().toString(),
    // An absent decision leaves the captured position pending. Null place explicitly discards it.
    val places:List<MerchantPlaceDecision> = emptyList(),
)
data class MerchantPlaceDecision(val eventIds:Set<String>,val place:MerchantPlaceInput?)
data class MerchantHistoryContext(val source:MerchantOperationSource=MerchantOperationSource.LOCAL,
    val planId:String?=null,val executionId:String?=null,val commandId:String?=null)
data class PreparedMerchantOperation internal constructor(
    val input:MerchantOperationInput,val preview:MerchantOperationPreview,
    val before:BookSnapshot,val after:BookSnapshot,val targetId:String?,val pendingEventIds:Set<String>,
)

private fun MerchantEntity.profile()=MerchantProfile(name,note,active)
internal fun mergedMerchantNote(target:String,source:String):String =
    (target.lines()+source.lines()).map{it.trim()}.filter{it.isNotEmpty()}.distinct().joinToString("\n")

/** Explicit links only. Refunds follow their purchase, never their own spelling. */
fun BookSnapshot.merchantEvents(id:String):List<EventEntity> {
    val originals=events.filter{it.kind!="REFUND"&&merchantByEvent[it.id]==id}.map{it.id}.toSet()
    return events.filter{it.id in originals||it.kind=="REFUND"&&settlementByEvent[it.id]?.originalEventId in originals}
}
fun merchantSaveInput(name:String,note:String,old:MerchantEntity?,active:Boolean,originalName:String?=null,
    createdId:String=java.util.UUID.randomUUID().toString()):MerchantOperationInput = MerchantOperationInput(
    when {old==null&&originalName!=null->MerchantOperationType.CREATE_FROM_ORIGINAL_NAME
        old==null->MerchantOperationType.CREATE
        old.name!=name.trim()->MerchantOperationType.RENAME
        old.note!=note->MerchantOperationType.CHANGE_NOTE
        else->MerchantOperationType.CHANGE_ACTIVE},old?.id,name=name,originalName=originalName,note=note,active=active,createdId=createdId)

/** Pure scope calculation shared by UI, cloud, and the transaction recheck. No merchantKey fallback. */
fun BookSnapshot.prepareMerchantOperation(input:MerchantOperationInput):PreparedMerchantOperation {
    val op=input.type
    val old=input.merchantId?.let{id->requireNotNull(merchants.singleOrNull{it.id==id}){"商户不属于本账本 / 別の帳簿のお店です"}}
    val structural=op in setOf(MerchantOperationType.CREATE,MerchantOperationType.CREATE_FROM_ORIGINAL_NAME,
        MerchantOperationType.ASSIGN_ORIGINAL_NAME,MerchantOperationType.RENAME,MerchantOperationType.MERGE,MerchantOperationType.DELETE)
    val creating=op in setOf(MerchantOperationType.CREATE,MerchantOperationType.CREATE_FROM_ORIGINAL_NAME)
    if(creating||op==MerchantOperationType.RENAME)require(input.name.isNotBlank()){"请输入商户名称 / お店の名前を入力してください"}
    val matches=merchants.filter{it.id!=old?.id&&normalizedMerchantName(it.name)==normalizedMerchantName(input.name)}
    if(creating||op==MerchantOperationType.RENAME) {
        require(matches.size<=1||old!=null&&normalizedMerchantName(old.name)==normalizedMerchantName(input.name)){"先在商户管理中合并或改名区分 / お店の管理で統合するか名前を変更してください"}
        require(matches.isEmpty()||old!=null&&normalizedMerchantName(old.name)==normalizedMerchantName(input.name)) {
            "已有同名商户，请在应用内合并或归入 / 同名のお店があります。アプリ内で統合・取り込みを行ってください"
        }
    }
    val target=when(op) {
        MerchantOperationType.CREATE,MerchantOperationType.CREATE_FROM_ORIGINAL_NAME->MerchantEntity(book.id,input.createdId,input.name.trim(),input.note.orEmpty(),input.active?:true)
        MerchantOperationType.ASSIGN_ORIGINAL_NAME,MerchantOperationType.MERGE->merchants.single{it.id==input.targetId}.let {
            require(op!=MerchantOperationType.MERGE||old!=null&&old.id!=it.id)
            if(op==MerchantOperationType.ASSIGN_ORIGINAL_NAME) {
                require(!input.originalName.isNullOrBlank())
                require(merchants.count{m->normalizedMerchantName(m.name)==normalizedMerchantName(it.name)}==1){"先在商户管理中合并或改名区分 / お店の管理で統合するか名前を変更してください"}
            }
            it.copy(note=mergedMerchantNote(it.note,input.note?:old?.note.orEmpty()))
        }
        MerchantOperationType.RENAME->requireNotNull(old).copy(name=input.name.trim(),note=input.note?:old.note,active=input.active?:old.active)
        MerchantOperationType.CHANGE_NOTE->requireNotNull(old).copy(note=input.note?:old.note)
        MerchantOperationType.CHANGE_ACTIVE->requireNotNull(old).copy(active=input.active?:old.active)
        MerchantOperationType.ORGANIZE_LOCATIONS->requireNotNull(old)
        MerchantOperationType.DELETE->null
        else->error("Unsupported merchant operation: $op")
    }
    val removed=if(op in setOf(MerchantOperationType.MERGE,MerchantOperationType.DELETE))old?.id else null
    val finalMerchants=merchants.filter{it.id!=removed&&it.id!=target?.id}+listOfNotNull(target)
    val groups=(listOfNotNull(old?.name,target?.name,input.originalName)).map(::normalizedMerchantName).filter{it.isNotEmpty()}.toSet()
    val finalMerchantsById=finalMerchants.associateBy{it.id}
    val finalGroups=finalMerchants.groupBy{normalizedMerchantName(it.name)}
    val explicitIds=setOfNotNull(old?.id,target?.id)
    val originalKey=input.originalName?.let(::normalizedMerchantName)
    // Before-identity ownership matters for legacy unlinked rows: only a unique old name
    // belongs to the renamed/merged merchant. Ambiguous old groups are resolved after removal.
    val claimedGroups=if(structural&&op!=MerchantOperationType.DELETE)merchants.filter { m->
        m.id in explicitIds&&merchantsByName[normalizedMerchantName(m.name)]?.size==1
    }.associate { m->normalizedMerchantName(m.name) to if(m.id==removed)target!! else finalMerchantsById.getValue(m.id) }else emptyMap()
    fun destination(id:String?,name:String,canAdopt:Boolean):Pair<String?,String> {
        if(!structural)return id to name
        if(id!=null) {
            if(id==removed) {
                if(op==MerchantOperationType.MERGE)return target!!.id to target.name
                // Deletion retains text; a now-unique surviving name group can own it.
                val remaining=if(canAdopt)finalGroups[normalizedMerchantName(name)]?.singleOrNull()else null
                return remaining?.id to (remaining?.name?:name)
            }
            if(id in explicitIds)return id to finalMerchantsById.getValue(id).name
            return id to name
        }
        if(!canAdopt)return null to name
        val key=normalizedMerchantName(name)
        if(originalKey!=null&&key==originalKey&&op in setOf(MerchantOperationType.CREATE_FROM_ORIGINAL_NAME,MerchantOperationType.ASSIGN_ORIGINAL_NAME))return target!!.id to target.name
        val resolved=claimedGroups[key]?:if(key in groups)finalGroups[key]?.singleOrNull()else null
        return resolved?.id to (resolved?.name?:name)
    }
    val scope=events.filter{it.purgedAt==null&&it.kind in setOf("EXPENSE","INCOME")&&
        (merchantByEvent[it.id] in explicitIds || structural&&it.kind=="EXPENSE"&&it.id !in merchantByEvent&&normalizedMerchantName(it.merchant) in groups)}
    val destinations=scope.associate{it.id to destination(merchantByEvent[it.id],it.merchant,it.kind=="EXPENSE")}
    val refundIds=if(structural)events.filter{it.kind=="REFUND"&&settlementByEvent[it.id]?.originalEventId in destinations}.map{it.id}.toSet()else emptySet()
    val nextEvents=events.map { e->
        val next=if(e.id in refundIds)destinations[settlementByEvent[e.id]?.originalEventId]?.second else destinations[e.id]?.second
        if(next!=null&&next!=e.merchant)e.copy(merchant=next)else e
    }
    val nextLinks=if(structural)eventMerchants.filter{it.eventId !in destinations&&it.eventId !in refundIds}+
        destinations.mapNotNull{(id,to)->to.first?.let{EventMerchantEntity(book.id,id,it)}} else eventMerchants
    val nextTemplates=templates.map{t->val (id,name)=destination(t.merchantId,t.merchant,t.kind=="EXPENSE");t.copy(merchantId=id,merchant=name)}
    val nextRules=recurrences.map{r->val (id,name)=destination(r.merchantId,r.merchant.ifBlank{r.name},true)
        if(id==r.merchantId&&name==r.merchant.ifBlank{r.name})r else r.copy(merchantId=id,merchant=name)}
    val nextDraft=draft?.let { d->
        // Editing-record baselines are batch D. Preserve existing explicit-ID sync in this batch.
        if(d.kind==EntryKind.REFUND&&structural&&d.originalEventId in destinations)
            d.copy(merchant=destinations.getValue(requireNotNull(d.originalEventId)).second,merchantId=null)
        else if(d.kind!=EntryKind.EXPENSE&&d.merchantId==null)d else {
            val (id,name)=destination(d.merchantId,d.merchant,d.kind==EntryKind.EXPENSE&&d.id==null)
            if(id==d.merchantId&&name==d.merchant)d else d.copy(merchantId=id,merchant=name,merchantLocated=if(id==null)false else d.merchantLocated)
        }
    }
    val pending=if(structural&&op!=MerchantOperationType.DELETE||op==MerchantOperationType.ORGANIZE_LOCATIONS)
        eventPlaces.filter{p->p.eventId in destinations&&(destinations[p.eventId]?.first?:merchantByEvent[p.eventId])!=null}.map{it.eventId}.toSet() else emptySet()
    val handled=input.places.flatMap{it.eventIds}.toSet()
    require(handled.all{it in pending}){"待整理位置已变化，请刷新预览 / 未整理の場所が変わりました。更新してください"}
    require(input.places.sumOf{it.eventIds.size}==handled.size)
    val saved=input.places.mapNotNull { decision->
        require(decision.eventIds.isNotEmpty())
        val owners=decision.eventIds.map{destinations[it]?.first?:merchantByEvent[it]}.distinct()
        require(owners.size==1)
        decision.place?.let { p->
            require(p.name.isNotBlank()&&p.latitude.isFinite()&&p.latitude in -90.0..90.0&&p.longitude.isFinite()&&p.longitude in -180.0..180.0&&p.radiusMeters>0){"地点名称、坐标或半径无效 / 場所名・座標・範囲が無効です"}
            MerchantLocationEntity(book.id,"${input.createdId}:place:${decision.eventIds.min()}",requireNotNull(owners.single()),p.name.trim(),p.latitude,p.longitude,p.radiusMeters)
        }
    }
    val nextLocations=merchantLocations.mapNotNull { l->when {
        l.merchantId!=removed->l
        op==MerchantOperationType.MERGE->l.copy(merchantId=target!!.id)
        else->null
    }}+saved
    val after=copy(merchants=finalMerchants,events=nextEvents,eventMerchants=nextLinks,templates=nextTemplates,recurrences=nextRules,
        draft=nextDraft,merchantLocations=nextLocations,eventPlaces=eventPlaces.filter{it.eventId !in handled})
    val savedIds=saved.mapTo(hashSetOf()){it.id}
    fun BookSnapshot.states():List<MerchantObjectState> = buildList {
        val merchantsById=merchants.associateBy{it.id}
        fun add(type:MerchantObjectType,id:String,name:String?,merchantId:String?,value:Any) {
            add(MerchantObjectState(type,id,mapOf("name" to name,"merchantId" to merchantId,"value" to value.toString())))
        }
        merchants.filter{it.id in explicitIds||normalizedMerchantName(it.name) in groups}.forEach{add(MerchantObjectType.MERCHANT,it.id,it.name,it.id,it)}
        events.filter{it.id in destinations||it.id in refundIds}.forEach{
            add(if(it.kind=="REFUND")MerchantObjectType.REFUND else MerchantObjectType.EVENT,it.id,it.merchant,
                if(it.kind=="REFUND")settlementByEvent[it.id]?.originalEventId?.let(merchantByEvent::get)else merchantByEvent[it.id],
                listOf(it,merchantByEvent[it.id],settlementByEvent[it.id]?.originalEventId))
        }
        templates.filter{it.merchantId in explicitIds||it.kind=="EXPENSE"&&it.merchantId==null&&normalizedMerchantName(it.merchant) in groups}.forEach{add(MerchantObjectType.TEMPLATE,it.id,it.merchant,it.merchantId,it)}
        recurrences.filter{it.merchantId in explicitIds||it.merchantId==null&&normalizedMerchantName(it.merchant.ifBlank{it.name}) in groups}.forEach{add(MerchantObjectType.RECURRENCE,it.id,it.merchant,it.merchantId,it)}
        merchantLocations.filter{it.merchantId in explicitIds||it.id in savedIds}.forEach{add(MerchantObjectType.SAVED_LOCATION,it.id,merchantsById[it.merchantId]?.name,it.merchantId,it)}
        eventPlaces.filter{it.eventId in pending}.forEach{add(MerchantObjectType.PENDING_PLACE,it.eventId,eventById[it.eventId]?.merchant,merchantByEvent[it.eventId],it)}
        draft?.takeIf{it.merchantId in explicitIds||it.kind==EntryKind.EXPENSE&&it.id==null&&normalizedMerchantName(it.merchant) in groups||it.kind==EntryKind.REFUND&&it.originalEventId in destinations}?.let{add(MerchantObjectType.DRAFT,it.mediaSession,it.merchant,it.merchantId,it)}
    }
    // Use the union of relevant identities on both sides; a reassignment to a surviving old-name
    // merchant must not look like deletion merely because it leaves the original ID filter.
    val beforeStates=states().associateBy{it.type to it.id}
    val afterStates=after.states().associateBy{it.type to it.id}.toMutableMap()
    fun fill(type:MerchantObjectType,id:String,name:String,mid:String?,value:Any) {
        if(type to id in beforeStates)afterStates[type to id]=MerchantObjectState(type,id,mapOf("name" to name,"merchantId" to mid,"value" to value.toString()))
    }
    after.templates.forEach{fill(MerchantObjectType.TEMPLATE,it.id,it.merchant,it.merchantId,it)}
    after.recurrences.forEach{fill(MerchantObjectType.RECURRENCE,it.id,it.merchant,it.merchantId,it)}
    after.draft?.let{fill(MerchantObjectType.DRAFT,it.mediaSession,it.merchant,it.merchantId,it)}
    val changes=(beforeStates.keys+afterStates.keys).mapNotNull { key->
        val a=beforeStates[key]?:MerchantObjectState(key.first,key.second,null)
        val b=afterStates[key]?:MerchantObjectState(key.first,key.second,null)
        if(a==b)null else MerchantObjectChange(a,b)
    }
    val recordChanges=changes.filter{it.before.type==MerchantObjectType.EVENT}
    val counts=MerchantImpactCounts(recordChanges.size,
        recordChanges.count{it.before.fields?.get("merchantId")==null&&it.after.fields?.get("merchantId")!=null},
        recordChanges.count{it.before.fields?.get("name")!=it.after.fields?.get("name")},
        recordChanges.count{eventById[it.before.id]?.deletedAt!=null},
        changes.count{it.before.type==MerchantObjectType.REFUND},changes.count{it.before.type==MerchantObjectType.TEMPLATE},
        changes.count{it.before.type==MerchantObjectType.RECURRENCE},changes.count{it.before.type==MerchantObjectType.SAVED_LOCATION},
        pending.size,changes.count{it.before.type==MerchantObjectType.DRAFT})
    val duplicates=if(op==MerchantOperationType.MERGE)nextLocations.filter{it.merchantId==target!!.id}
        .groupBy{listOf(it.name,it.latitude,it.longitude,it.radiusMeters)}.values.filter{it.size>1}.flatten().map{it.id}.sorted()else emptyList()
    val dependencies=if(structural||op==MerchantOperationType.ORGANIZE_LOCATIONS)beforeStates.values.toList()
        else beforeStates.values.filter{it.type==MerchantObjectType.MERCHANT&&it.id==old?.id}
    val intent=mapOf("merchantId" to input.merchantId,"targetId" to input.targetId,"name" to input.name.trim(),"originalName" to input.originalName,
        "note" to input.note,"active" to input.active?.toString(),"createdId" to input.createdId,
        "places" to input.places.sortedBy{it.eventIds.sorted().joinToString()}.joinToString(";"){it.eventIds.sorted().toString()+":"+it.place.toString()})
    return PreparedMerchantOperation(input,MerchantOperationPreview(MerchantPreviewBasis(book.id,op,intent,dependencies,changes),counts,duplicates),this,after,target?.id,pending)
}

suspend fun LedgerRepository.previewMerchantOperation(bookId:String,input:MerchantOperationInput)=
    snapshot(requireNotNull(dao.book(bookId))).first().prepareMerchantOperation(input)

/** No revisions, save timestamps, receipts, postings or financial facts are touched here. */
suspend fun LedgerRepository.commitMerchantOperation(prepared:PreparedMerchantOperation,context:MerchantHistoryContext=MerchantHistoryContext()):String?=db.withTransaction {
    val current=previewMerchantOperation(prepared.before.book.id,prepared.input)
    requireCurrentMerchantPreview(prepared.preview.fingerprint,current.preview)
    val s=current.before;val a=current.after;val bookId=s.book.id
    val changes=current.preview.basis.changes
    if(changes.isEmpty())return@withTransaction current.targetId
    // Compare by identity once per table. Link-only changes must not rewrite event rows.
    val merchantsBefore=s.merchants.associateBy{it.id}
    val merchantsAfter=a.merchants.associateBy{it.id}
    a.merchants.filter{it!=merchantsBefore[it.id]}.forEach{dao.putMerchant(it)}
    a.events.filter{it!=s.eventById[it.id]}.forEach{dao.putEvent(it)}
    s.eventMerchants.filter{it.eventId !in a.merchantByEvent}.forEach{dao.clearEventMerchant(bookId,it.eventId)}
    a.eventMerchants.filter{it.merchantId!=s.merchantByEvent[it.eventId]}.forEach{dao.putEventMerchant(it)}
    val templatesBefore=s.templates.associateBy{it.id}
    a.templates.filter{it!=templatesBefore[it.id]}.forEach{dao.putTemplate(it)}
    val rulesBefore=s.recurrences.associateBy{it.id}
    a.recurrences.filter{it!=rulesBefore[it.id]}.forEach{dao.putRecurrence(it)}
    if(a.draft!=s.draft)dao.storedDraft(bookId)?.let{dao.putDraft(it.copy(json=json.encodeToString(requireNotNull(a.draft))))}
    val locationsBefore=s.merchantLocations.associateBy{it.id}
    val locationsAfter=a.merchantLocations.associateBy{it.id}
    a.merchantLocations.filter{it!=locationsBefore[it.id]}.forEach{dao.putMerchantLocation(it)}
    s.merchantLocations.filter{it.id !in locationsAfter}.forEach{dao.removeMerchantLocation(bookId,it.id)}
    val remainingPlaces=a.eventPlaces.mapTo(hashSetOf()){it.eventId}
    s.eventPlaces.filter{it.eventId !in remainingPlaces}.forEach{dao.clearEventPlace(bookId,it.eventId)}
    s.merchants.filter{it.id !in merchantsAfter}.forEach{dao.deleteMerchant(bookId,it.id)}
    val source=s.merchants.find{it.id==current.input.merchantId}
    val target=a.merchants.find{it.id==current.targetId}
    val operationId=id()
    writeMerchantOperationInTransaction(MerchantOperationEntity(bookId,operationId,clock.millis(),current.input.type,context.source,
        source?.id,source?.name,target?.id,target?.name,current.input.originalName,source?.profile(),a.merchants.find{it.id==source?.id}?.profile(),
        s.merchants.find{it.id==target?.id}?.profile(),target?.profile(),current.preview.counts,context.planId,context.executionId,context.commandId),
        changes.map{MerchantOperationImpactEntity(bookId,operationId,it.before.type,it.before.id,it.before.fields?.get("name"),it.after.fields?.get("name"),it.before.fields?.get("merchantId"),it.after.fields?.get("merchantId"))},true)
    current.targetId
}

/** Compatibility for noninteractive callers: UI/cloud keep the preview across their confirmation. */
suspend fun LedgerRepository.saveMerchant(bookId:String,name:String,note:String="",id:String?=null,active:Boolean=true,confirmAdoption:Boolean=false,
    context:MerchantHistoryContext=MerchantHistoryContext()):String=db.withTransaction {
    val old=id?.let{requireNotNull(dao.merchant(bookId,it))}
    val prepared=previewMerchantOperation(bookId,merchantSaveInput(name,note,old,active))
    require(old==null||prepared.input.type!=MerchantOperationType.RENAME||!prepared.preview.basis.changes.any{it.before.type in setOf(MerchantObjectType.EVENT,MerchantObjectType.TEMPLATE,MerchantObjectType.RECURRENCE)&&
        it.before.fields?.get("merchantId")==null&&it.after.fields?.get("merchantId")!=null&&
        normalizedMerchantName(it.before.fields?.get("name").orEmpty())==normalizedMerchantName(name)&&normalizedMerchantName(name)!=normalizedMerchantName(old.name)}||confirmAdoption){"请先确认归入同名记录 / 同名の記録の取り込みを確認してください"}
    val result=requireNotNull(commitMerchantOperation(prepared,context))
    if(old!=null&&old.name==name.trim()&&old.note!=note&&old.active!=active)setMerchantActive(bookId,result,active,context)
    result
}
suspend fun LedgerRepository.commitMerchantEdits(prepared:PreparedMerchantOperation):String?=db.withTransaction {
    val result=commitMerchantOperation(prepared)
    if(prepared.input.type==MerchantOperationType.CHANGE_NOTE&&prepared.input.active!=null&&prepared.input.active!=prepared.before.merchants.single{it.id==result}.active)
        setMerchantActive(prepared.before.book.id,requireNotNull(result),prepared.input.active)
    result
}
suspend fun LedgerRepository.setMerchantActive(bookId:String,id:String,active:Boolean,context:MerchantHistoryContext=MerchantHistoryContext())=
    commitMerchantOperation(previewMerchantOperation(bookId,MerchantOperationInput(MerchantOperationType.CHANGE_ACTIVE,id,active=active)),context)
suspend fun LedgerRepository.changeMerchantNote(bookId:String,id:String,note:String,context:MerchantHistoryContext=MerchantHistoryContext())=
    commitMerchantOperation(previewMerchantOperation(bookId,MerchantOperationInput(MerchantOperationType.CHANGE_NOTE,id,note=note)),context)
suspend fun LedgerRepository.mergeMerchants(bookId:String,sourceId:String,targetId:String,sourceNote:String?=null):String =
    requireNotNull(commitMerchantOperation(previewMerchantOperation(bookId,MerchantOperationInput(MerchantOperationType.MERGE,sourceId,targetId,note=sourceNote))))
suspend fun LedgerRepository.deleteMerchant(bookId:String,merchantId:String) {
    commitMerchantOperation(previewMerchantOperation(bookId,MerchantOperationInput(MerchantOperationType.DELETE,merchantId)))
}

/** Refund display copies follow their purchase, including recoverable refunds, without revisions. */
internal suspend fun LedgerRepository.synchronizeRefundMerchant(original:EventEntity) {
    dao.refunds(original.bookId,original.id).filter{it.purgedAt==null&&it.merchant!=original.merchant}.forEach {
        dao.putEvent(it.copy(merchant=original.merchant))
    }
}
