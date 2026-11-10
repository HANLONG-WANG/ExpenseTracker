package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.merchant.*
import com.yohaku.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class MerchantFoundationTest {
    private val shop=MerchantEntity("book","shop","Coffee")
    private fun resolve(name:String?="Coffee",id:String?=null,kind:EntryKind=EntryKind.EXPENSE,
        merchants:List<MerchantEntity> = listOf(shop))=resolveMerchant("book",kind,id,name,merchants)

    @Test fun normalizedUniqueNamesIncludeDisabledMerchantsAndStayBookScoped() {
        assertEquals(MerchantResolution.Resolved(shop),resolve("  ＣＯＦＦＥＥ  "))
        val disabled=shop.copy(active=false)
        assertEquals(MerchantResolution.Resolved(disabled),resolve("coffee",merchants=listOf(disabled,shop.copy(bookId="other"))))
        assertEquals(MerchantResolution.None,resolve("Tea"))
        val duplicate=shop.copy(id="second",name="coffee")
        assertEquals(setOf(shop,duplicate),(resolve(merchants=listOf(duplicate,shop)) as MerchantResolution.Ambiguous).merchants.toSet())
    }

    @Test fun explicitIdUsesFormalNameAndRejectsInvalidOwnershipOrContradictoryText() {
        assertEquals(MerchantResolution.Resolved(shop),resolve("ＣＯＦＦＥＥ",shop.id))
        assertEquals(MerchantResolution.Resolved(shop),resolve(null,shop.id))
        assertEquals(MerchantResolution.Resolved(shop),resolve(id=shop.id,merchants=listOf(shop,shop.copy(id="duplicate"))))
        assertEquals(MerchantResolution.Invalid(MerchantResolutionError.INVALID_MERCHANT_ID),
            resolve(id=shop.id,merchants=listOf(shop.copy(bookId="other"))))
        assertEquals(MerchantResolution.Invalid(MerchantResolutionError.ID_NAME_MISMATCH),resolve("Tea",shop.id))
    }

    @Test fun ordinaryIncomeAndTransfersDoNotAutoResolveButExplicitIncomeIsSupported() {
        assertEquals(MerchantResolution.None,resolve(kind=EntryKind.INCOME))
        assertEquals(MerchantResolution.None,resolve(kind=EntryKind.TRANSFER))
        assertEquals(MerchantResolution.Resolved(shop),resolve(id=shop.id,kind=EntryKind.INCOME))
    }

    @Test fun refundUsesOriginalExpenseInsteadOfItsOwnNameOrId() {
        assertEquals(MerchantResolution.Invalid(MerchantResolutionError.ORIGINAL_EXPENSE_REQUIRED),resolve(kind=EntryKind.REFUND))
        assertEquals(MerchantResolution.Resolved(shop),resolveMerchant("book",EntryKind.REFUND,"unrelated","Tea",listOf(shop),
            OriginalExpenseMerchant("book",shop.id,"stale display")))
        assertEquals(MerchantResolution.None,resolveMerchant("book",EntryKind.REFUND,null,"Coffee",listOf(shop),
            OriginalExpenseMerchant("book",null,"Tea")))
    }

    @Test fun fingerprintIgnoresCollectionOrderButDetectsContentScopeAndIntentChanges() {
        val merchant=MerchantObjectState(MerchantObjectType.MERCHANT,"shop",mapOf("name" to "Coffee","active" to "true"))
        val place=MerchantObjectState(MerchantObjectType.PENDING_PLACE,"event",mapOf("latitude" to "35.0"))
        val basis=MerchantPreviewBasis("book",MerchantOperationType.RENAME,mapOf("name" to "Tea"),listOf(merchant,place),
            listOf(MerchantObjectChange(merchant,merchant.copy(fields=mapOf("name" to "Tea","active" to "true")))))
        val fingerprint=merchantImpactFingerprint(basis)
        assertEquals(fingerprint,merchantImpactFingerprint(basis.copy(dependencies=listOf(place,merchant.copy(fields=linkedMapOf("active" to "true","name" to "Coffee"))))))
        val changed= basis.copy(dependencies=listOf(merchant,place.copy(fields=mapOf("latitude" to "36.0"))))
        assertNotEquals(fingerprint,merchantImpactFingerprint(changed))
        assertNotEquals(fingerprint,merchantImpactFingerprint(basis.copy(dependencies=listOf(merchant))))
        assertNotEquals(fingerprint,merchantImpactFingerprint(basis.copy(intent=mapOf("name" to "Other"))))
        assertNotEquals(fingerprint,merchantImpactFingerprint(basis.copy(changes=emptyList())))
        requireCurrentMerchantPreview(fingerprint,MerchantOperationPreview(basis,MerchantImpactCounts()))
        assertThrows(IllegalStateException::class.java){requireCurrentMerchantPreview(fingerprint,MerchantOperationPreview(changed,MerchantImpactCounts()))}
    }

    @Test fun savedSelectionUsesFormalNameAndErrorsRemainExplicit() {
        assertEquals(shop,resolveSavedMerchant("book",EntryKind.EXPENSE,shop.id,"old display",listOf(shop)))
        assertEquals(shop,resolveSavedMerchant("book",EntryKind.INCOME,shop.id,"old display",listOf(shop)))
        assertNull(resolveSavedMerchant("book",EntryKind.INCOME,null,shop.name,listOf(shop)))
        assertTrue(runCatching{resolve(name="Other",id=shop.id).merchantOrThrow()}.exceptionOrNull()!!.message!!.contains("ID 与名称不一致"))
    }

    private fun record(id:String,name:String,kind:String="EXPENSE",trash:Boolean=false)=EventEntity("book",id,kind,"account",null,100,"JPY",100,"2026-10-01",name,"",deletedAt=if(trash)1 else null,createdAt=10)
    private fun ledger(records:List<EventEntity>,shops:List<MerchantEntity> = emptyList(),links:Map<String,String> = emptyMap())=
        BookSnapshot(BookEntity("book","Test","JPY","2026-10-01"),emptyList(),emptyList(),records,emptyList(),null,
            merchants=shops,eventMerchants=links.map{EventMerchantEntity("book",it.key,it.value)})
    private fun template(name:String,id:String="template",kind:String="EXPENSE")=
        EntryTemplateEntity("book",id,id,kind,"category","account","JPY",merchant=name,createdAt=1)
    private fun rule(name:String)=RecurrenceEntity("book","rule",name,"account","category","JPY",100,100,false,"MONTHLY","2026-10-01",null)

    @Test fun originalGroupRetainsItsIdentityAndAdoptsFinalGroupWithRefundsReferencesAndPendingPlaces() {
        val records=listOf(record("source","星巴克咖啡"),record("trash","星巴克咖啡",trash=true),record("final","星巴克"),
            record("protected","星巴克咖啡"),record("income","星巴克咖啡","INCOME"),record("transfer","星巴克咖啡","TRANSFER"),record("refund","stale","REFUND",true))
        val s=ledger(records,listOf(shop),mapOf("protected" to shop.id)).copy(
            settlements=listOf(SettlementEntity("book","refund",20,"JPY",true,"2026-10-02","manual","2026-10-02",20,20,"source")),
            templates=listOf(template("星巴克咖啡"),template("星巴克咖啡","income-template","INCOME")),recurrences=listOf(rule("星巴克咖啡")),
            draft=EntryDraft(merchant="星巴克咖啡"),eventPlaces=listOf(EventPlaceEntity("book","source",35.0,139.0,5.0)))
        val input=MerchantOperationInput(MerchantOperationType.CREATE_FROM_ORIGINAL_NAME,name="星巴克",originalName="星巴克咖啡",createdId="new")
        val p=s.prepareMerchantOperation(input);val a=p.after;val c=p.preview.counts
        assertEquals(setOf("source","trash","final"),a.eventMerchants.filter{it.merchantId=="new"}.map{it.eventId}.toSet())
        assertEquals(shop.id,a.eventMerchants.single{it.eventId=="protected"}.merchantId)
        assertEquals("星巴克咖啡",a.events.single{it.id=="protected"}.merchant)
        assertEquals("星巴克",a.events.single{it.id=="refund"}.merchant)
        assertEquals("new",a.templates.first().merchantId);assertNull(a.templates.last().merchantId)
        assertEquals("new",a.recurrences.single().merchantId);assertEquals("星巴克",a.recurrences.single().merchant)
        assertEquals("new",a.draft!!.merchantId);assertEquals("星巴克",a.draft!!.merchant)
        assertEquals(3,c.newlyLinkedRecords);assertEquals(2,c.renamedRecords);assertEquals(1,c.trashedRecords);assertEquals(1,c.linkedRefunds)
        assertEquals(1,c.templates);assertEquals(1,c.recurrenceRules);assertEquals(1,c.pendingPlaces)
        assertEquals(s.eventPlaces,a.eventPlaces)
        assertEquals(1,a.recordedPlaces("星巴克").size)
        assertTrue(a.merchantCandidates(35.0,139.0).isEmpty())
        val kept=s.prepareMerchantOperation(input.copy(places=listOf(MerchantPlaceDecision(setOf("source"),MerchantPlaceInput("入口",35.0,139.0)))))
        assertTrue(kept.after.eventPlaces.isEmpty());assertEquals("new",kept.after.merchantLocations.single().merchantId)
        val discarded=s.prepareMerchantOperation(input.copy(places=listOf(MerchantPlaceDecision(setOf("source"),null))))
        assertTrue(discarded.after.eventPlaces.isEmpty());assertTrue(discarded.after.merchantLocations.isEmpty())
        assertEquals(records.map{it.revision},a.events.map{it.revision});assertEquals(records.map{it.createdAt},a.events.map{it.createdAt})
    }

    @Test fun profileOnlyChangesDoNotSynchronizeLegacyRowsOrPendingLocations() {
        val s=ledger(listOf(record("unlinked","Coffee"),record("linked","old")),listOf(shop),mapOf("linked" to shop.id)).copy(
            templates=listOf(template("Coffee")),recurrences=listOf(rule("Coffee")),draft=EntryDraft(merchant="Coffee"),
            eventPlaces=listOf(EventPlaceEntity("book","linked",35.0,139.0,5.0)))
        for(input in listOf(MerchantOperationInput(MerchantOperationType.CHANGE_NOTE,shop.id,note="memo"),MerchantOperationInput(MerchantOperationType.CHANGE_ACTIVE,shop.id,active=false))) {
            val p=s.prepareMerchantOperation(input)
            assertEquals(s.copy(merchants=p.after.merchants),p.after)
            assertEquals(MerchantImpactCounts(),p.preview.counts)
            assertEquals(listOf(MerchantObjectType.MERCHANT),p.preview.basis.changes.map{it.before.type})
        }
        assertTrue(s.prepareMerchantOperation(MerchantOperationInput(MerchantOperationType.CHANGE_ACTIVE,shop.id,active=true)).preview.basis.changes.isEmpty())
    }

    @Test fun renameMergeAndDeleteRecomputeAmbiguousOldGroupsFromFinalState() {
        val a=shop.copy(id="a",name="X");val b=a.copy(id="b");val target=shop.copy(id="target",name="Y")
        val s=ledger(listOf(record("free","X"),record("a-owned","X")),listOf(a,b,target),mapOf("a-owned" to "a")).copy(templates=listOf(template("X")),recurrences=listOf(rule("X")),draft=EntryDraft(merchant="X"))
        for(input in listOf(MerchantOperationInput(MerchantOperationType.RENAME,"a",name="Z"),MerchantOperationInput(MerchantOperationType.MERGE,"a","target"),MerchantOperationInput(MerchantOperationType.DELETE,"a"))) {
            val p=s.prepareMerchantOperation(input)
            assertEquals("b",p.after.eventMerchants.single{it.eventId=="free"}.merchantId)
            assertEquals("b",p.after.templates.single().merchantId);assertEquals("b",p.after.recurrences.single().merchantId);assertEquals("b",p.after.draft!!.merchantId)
            assertEquals("b",p.preview.basis.changes.single{it.before.type==MerchantObjectType.TEMPLATE}.after.fields!!["merchantId"])
            assertEquals(if(input.type==MerchantOperationType.DELETE)"b"else if(input.type==MerchantOperationType.MERGE)"target"else"a",p.after.eventMerchants.single{it.eventId=="a-owned"}.merchantId)
        }
        val three=s.copy(merchants=listOf(a,b,b.copy(id="c"))).prepareMerchantOperation(MerchantOperationInput(MerchantOperationType.MERGE,"a","b"))
        assertFalse(three.after.eventMerchants.any{it.eventId=="free"});assertNull(three.after.templates.single().merchantId)
        assertNull(three.after.draft!!.merchantId)
        val spelling=s.prepareMerchantOperation(MerchantOperationInput(MerchantOperationType.RENAME,"a",name="ｘ"))
        assertEquals("ｘ",spelling.after.events.single{it.id=="a-owned"}.merchant);assertFalse(spelling.after.eventMerchants.any{it.eventId=="free"})
    }

    @Test fun assignmentAndMergeRetainTargetStateNotesAndAllDuplicateLocations() {
        val target=shop.copy(id="target",active=false,note="common\ntarget")
        val source=shop.copy(id="source",name="Old",note="common\nsource")
        val s=ledger(listOf(record("raw","原名"),record("owned","Old"),record("income","old display","INCOME")),listOf(source,target),mapOf("owned" to source.id,"income" to source.id)).copy(
            merchantLocations=listOf(MerchantLocationEntity("book","l1",source.id,"Door",35.0,139.0),MerchantLocationEntity("book","l2",target.id,"Door",35.0,139.0)))
        val assigned=s.prepareMerchantOperation(MerchantOperationInput(MerchantOperationType.ASSIGN_ORIGINAL_NAME,targetId=target.id,originalName="原名",note="common\nnew",active=true))
        assertFalse(assigned.after.merchants.single{it.id==target.id}.active)
        assertEquals("common\ntarget\nnew",assigned.after.merchants.single{it.id==target.id}.note)
        val merged=s.prepareMerchantOperation(MerchantOperationInput(MerchantOperationType.MERGE,source.id,target.id))
        assertEquals(listOf(target.copy(note="common\ntarget\nsource")),merged.after.merchants)
        assertEquals(setOf("l1","l2"),merged.preview.duplicateLocationIds.toSet())
        assertEquals(2,merged.after.merchantLocations.size);assertTrue(merged.after.merchantLocations.all{it.merchantId==target.id})
        assertEquals("Coffee",merged.after.events.single{it.id=="income"}.merchant)
        val ambiguous=s.copy(merchants=s.merchants+target.copy(id="duplicate"))
        assertThrows(IllegalArgumentException::class.java){ambiguous.prepareMerchantOperation(assigned.input)}
    }

    @Test fun operationFingerprintRejectsAddedRecordsChangedPlacesAndTargetsButIgnoresUnrelatedRows() {
        val s=ledger(listOf(record("one","Raw")),listOf(shop)).copy(eventPlaces=listOf(EventPlaceEntity("book","one",35.0,139.0,5.0)))
        val input=MerchantOperationInput(MerchantOperationType.ASSIGN_ORIGINAL_NAME,targetId=shop.id,originalName="Raw")
        val p=s.prepareMerchantOperation(input)
        val changed=listOf(s.copy(events=s.events+record("two","Raw")),s.copy(eventPlaces=listOf(s.eventPlaces.single().copy(latitude=36.0))),s.copy(merchants=listOf(shop.copy(active=false))))
        changed.forEach{assertThrows(IllegalStateException::class.java){requireCurrentMerchantPreview(p.preview.fingerprint,it.prepareMerchantOperation(input).preview)}}
        requireCurrentMerchantPreview(p.preview.fingerprint,s.copy(events=s.events+record("unrelated","Other")).prepareMerchantOperation(input).preview)
    }
    @Test fun draftOwnershipUsesClaimedGroupsWhileEditingDraftsKeepTheirBaselineText() {
        val a=MerchantEntity("book","a","Raw");val target=a.copy(id="target",name="Target")
        val original=EntryDraft(id="entry",merchant="Raw",merchantId="a",merchantBaseline=MerchantBaseline("Raw","a"))
        val s=ledger(listOf(record("entry","Raw")),listOf(a,target),mapOf("entry" to "a"))
        val rename=MerchantOperationInput(MerchantOperationType.RENAME,"a",name="Renamed")
        assertEquals(original,s.copy(draft=original).prepareMerchantOperation(rename).after.draft)
        val merge=MerchantOperationInput(MerchantOperationType.MERGE,"a","target")
        val edited=s.copy(draft=original).prepareMerchantOperation(merge).after.draft!!
        assertEquals("Raw",edited.merchant);assertNull(edited.merchantId);assertEquals(original.merchantBaseline,edited.merchantBaseline)
        val fresh=original.copy(id=null,merchantBaseline=null)
        val merged=s.copy(draft=fresh).prepareMerchantOperation(merge).after.draft!!
        assertEquals("target",merged.merchantId);assertEquals("Target",merged.merchant);assertEquals(1L,merged.merchantSyncVersion)
        val removed=s.copy(merchants=listOf(a,a.copy(id="duplicate")),draft=fresh).prepareMerchantOperation(MerchantOperationInput(MerchantOperationType.DELETE,"a")).after.draft!!
        assertNull(removed.merchantId);assertEquals("Raw",removed.merchant)
        val raw=fresh.copy(merchantId=null,merchant=" raw ")
        assertEquals("Target",s.copy(draft=raw).prepareMerchantOperation(merge).after.draft!!.merchant)
        val ambiguous=s.copy(merchants=s.merchants+a.copy(id="duplicate"),draft=raw).prepareMerchantOperation(merge).after.draft!!
        assertEquals("duplicate",ambiguous.merchantId);assertEquals("Raw",ambiguous.merchant)
        for(kind in listOf(EntryKind.INCOME,EntryKind.TRANSFER)) {
            val draft=raw.copy(kind=kind)
            assertEquals(draft,s.copy(draft=draft).prepareMerchantOperation(merge).after.draft)
        }
    }

}
