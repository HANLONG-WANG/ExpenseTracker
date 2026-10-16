package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.platform.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocationEntryFlowTest {
    @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private class ControlledLocation:CurrentLocationSource {
        val requests=Channel<CompletableDeferred<LocationFix>>(Channel.UNLIMITED)
        override suspend fun currentFix():LocationFix {
            val fix=CompletableDeferred<LocationFix>();requests.send(fix)
            try{return fix.await()}finally{if(!fix.isCompleted)fix.cancel()}
        }
    }
    private fun nextRequest(source:ControlledLocation):CompletableDeferred<LocationFix> {
        var pending:CompletableDeferred<LocationFix>?=null
        ui.waitUntil(10000){pending=source.requests.tryReceive().getOrNull();pending!=null}
        return pending!!
    }
    private fun visible(tag:String):SemanticsNodeInteraction {
        val node=ui.onNodeWithTag(tag).performScrollTo();ui.waitForIdle()
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val top=node.getUnclippedBoundsInRoot().top.value
        val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
        if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        ui.waitForIdle();return node
    }
    private fun shot(name:String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(350,5000)
        val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"phase-e-location-entry").apply{mkdirs()}
        val image=instrumentation.uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun uniqueLocationFillsAllAppearancesButManualChangesAndOldEntriesWin()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YohakuApplication
        val db=Room.inMemoryDatabaseBuilder(app,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(app.cacheDir,"location-ui-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(app,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val source=ControlledLocation();val vm=AppViewModel(repo,prefs,locationSource=source)
        try {
            val b=repo.createBook("位置 / 場所",Currency.JPY)
            repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
            val a=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val c=repo.saveCategory(b,"食事",EntryKind.EXPENSE,null)
            val merchant=repo.saveMerchant(b,"喫茶 原名")
            repo.saveMerchantLocation(b,merchant,"店頭",35.0,139.0)
            prefs.select(b);prefs.setForegroundLocation(b,true)
            ui.setContent{NativeApp(vm)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b}
            fun draft()=EntryDraft(categoryId=c,accountId=a,amount="500",merchant="")
            for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
                repo.updateAppearance(b,theme,language)
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
                ui.runOnIdle{vm.changeEntry(b,draft());vm.navigate("entry")}
                val request=nextRequest(source)
                ui.waitUntil(10000){ui.onAllNodesWithTag("foreground-location-status").fetchSemanticsNodes().isNotEmpty()}
                visible("entry-location");shot("$theme-$language-locating")
                request.complete(LocationFix(35.0,139.0,10.0))
                ui.waitUntil(10000){vm.entry.value?.merchantId==merchant}
                assertEquals("喫茶 原名",vm.entry.value!!.merchant)
                assertFalse(vm.entry.value!!.merchantManuallyEdited)
                visible("entry-location")
                shot("$theme-$language")
                ui.runOnIdle{vm.changeEntry(b,draft())}
                nextRequest(source).completeExceptionally(LocationException(LocationFailure.PERMISSION))
                ui.waitUntil(10000){ui.onAllNodes(hasTestTag("foreground-location-status") and hasText(if(language=="ja")"未許可"else"未授权",substring=true)).fetchSemanticsNodes().isNotEmpty()}
                visible("entry-location");shot("$theme-$language-denied")
                ui.runOnIdle{vm.changeEntry(b,draft())}
                nextRequest(source).complete(LocationFix(0.0,0.0,10.0))
                ui.waitUntil(10000){ui.onAllNodesWithTag("location-no-candidates").fetchSemanticsNodes().isNotEmpty()}
                visible("entry-location");shot("$theme-$language-none")
                ui.runOnIdle{vm.navigate("merchants")}
                ui.onNodeWithTag("merchant-locate").performScrollTo().performClick()
                nextRequest(source).complete(LocationFix(35.0,139.0,10.0))
                ui.waitUntil(10000){ui.onAllNodesWithTag("nearby-merchant-$merchant").fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("merchant-location-panel").performScrollTo();shot("$theme-$language-merchant")
            }
            ui.runOnIdle{vm.changeEntry(b,draft());vm.navigate("entry")}
            val pending=nextRequest(source)
            val field="field-お店 / 買ったもの"
            visible(field).performTextReplacement("手入力の原名")
            pending.complete(LocationFix(35.0,139.0,10.0))
            ui.waitUntil(10000){ui.onAllNodesWithTag("located-merchant-$merchant").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("手入力の原名",vm.entry.value!!.merchant)
            assertTrue(vm.entry.value!!.merchantManuallyEdited)
            visible(field).performTextClearance()
            val snapshot=repo.snapshot(repo.dao.book(b)!!).first()
            ui.runOnIdle{vm.offerLocatedMerchant(b,vm.entry.value!!.mediaSession,snapshot.merchantCandidates(35.0,139.0,10.0))}
            assertEquals("",vm.entry.value!!.merchant)
            val neighbor=repo.saveMerchant(b,"隣のお店")
            repo.saveMerchantLocation(b,neighbor,"隣",35.0003,139.0)
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.merchants?.size==2}
            ui.runOnIdle{vm.changeEntry(b,draft())}
            nextRequest(source).complete(LocationFix(35.0,139.0,10.0))
            ui.waitUntil(10000){ui.onAllNodesWithTag("located-merchant-$neighbor").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("",vm.entry.value!!.merchant)
            visible("located-merchant-$neighbor").performClick()
            assertEquals(neighbor,vm.entry.value!!.merchantId)
            assertTrue(vm.entry.value!!.merchantManuallyEdited)
            ui.runOnIdle{vm.changeEntry(b,draft())}
            nextRequest(source).complete(LocationFix(35.0,139.0,200.0))
            ui.waitUntil(10000){ui.onAllNodesWithTag("located-merchant-$neighbor").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("",vm.entry.value!!.merchant)
            ui.runOnIdle{vm.changeEntry(b,draft())}
            nextRequest(source).complete(LocationFix(0.0,0.0,10.0))
            ui.waitUntil(10000){ui.onAllNodesWithTag("location-no-candidates").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("",vm.entry.value!!.merchant)
            ui.runOnIdle{vm.changeEntry(b,draft())}
            nextRequest(source).completeExceptionally(LocationException(LocationFailure.TIMEOUT))
            ui.waitUntil(10000){ui.onAllNodes(hasTestTag("foreground-location-status") and hasText("タイムアウト",substring=true)).fetchSemanticsNodes().isNotEmpty()}
            assertEquals("",vm.entry.value!!.merchant)
            ui.runOnIdle{vm.changeEntry(b,draft())}
            val background=nextRequest(source)
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            ui.waitUntil(10000){background.isCancelled}
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            nextRequest(source).complete(LocationFix(0.0,0.0,10.0))
            ui.waitUntil(10000){ui.onAllNodesWithTag("location-no-candidates").fetchSemanticsNodes().isNotEmpty()}
            ui.runOnIdle{vm.changeEntry(b,draft())}
            val abandoned=nextRequest(source)
            ui.runOnIdle{vm.navigate("home")}
            ui.waitUntil(10000){abandoned.isCancelled}
            val existing=repo.saveEntry(b,draft().copy(merchant="過去の原名"))
            // Use the actual persisted snapshot for editing rather than synthetic UI facts.
            val saved=repo.snapshot(repo.dao.book(b)!!).first()
            ui.runOnIdle{vm.editEntry(saved,saved.events.first{it.id==existing})}
            ui.runOnIdle{vm.offerLocatedMerchant(b,vm.entry.value!!.mediaSession,saved.merchantCandidates(35.0,139.0,10.0))}
            assertEquals(existing,vm.entry.value!!.id)
            assertEquals("過去の原名",vm.entry.value!!.merchant)
            assertEquals("過去の原名",repo.dao.event(b,existing)!!.merchant)
        }finally {vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()}
    }
    @Test fun userEnablesPermissionAndAndroidGpsFillsThenSavesTheOriginalName()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as YohakuApplication
        val db=Room.inMemoryDatabaseBuilder(app,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(app.cacheDir,"location-real-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(app,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db)
        val vm=AppViewModel(repo,prefs,locationSource=ForegroundLocation(app))
        val automation=instrumentation.uiAutomation
        val info=automation.serviceInfo;val originalFlags=info.flags
        try {
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION))
            val book=repo.createBook("位置の確認",Currency.JPY,theme="objects",language="ja")
            repo.dao.putBook(repo.dao.book(book)!!.copy(initialized=true))
            val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val category=repo.saveCategory(book,"食事",EntryKind.EXPENSE,null)
            val merchant=repo.saveMerchant(book,"喫茶 原名")
            repo.saveMerchantLocation(book,merchant,"店頭",35.0,139.0)
            prefs.select(book)
            ui.setContent{NativeApp(vm)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.changeEntry(book,EntryDraft(accountId=account,categoryId=category,amount="500"));vm.navigate("entry")}
            visible("entry-enable-location").performClick()
            info.flags=originalFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo=info
            withTimeout(10000) {
                while(true) {
                    val allow=automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_foreground_only_button")?.firstOrNull()
                    if(allow!=null){assertTrue(allow.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));break}
                    delay(100)
                }
            }
            ui.waitUntil(25000){vm.entry.value?.merchantId==merchant}
            assertEquals("喫茶 原名",vm.entry.value!!.merchant)
            assertTrue(book in prefs.foregroundLocationBooks.first())
            assertTrue(repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty())
            ui.runOnIdle{vm.saveEntry(book)}
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val saved=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals("喫茶 原名",saved.events.single().merchant)
            assertEquals(merchant,saved.eventMerchants.single().merchantId)
            assertEquals(9500L,saved.balance(saved.accounts.single()))
            ui.runOnIdle{vm.navigate("merchants")}
            ui.onNodeWithTag("merchant-open-$merchant").performScrollTo().performClick()
            ui.onNode(hasText("＋ 場所を追加") and hasClickAction()).performScrollTo().performClick()
            visible("field-場所の名前").performTextReplacement("GPS保存場所")
            visible("place-use-location").performClick()
            ui.waitUntil(25000){
                val field=ui.onAllNodesWithTag("field-緯度・空欄可").fetchSemanticsNodes().firstOrNull()
                field?.config?.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.EditableText){androidx.compose.ui.text.AnnotatedString("")}?.text?.toDoubleOrNull()?.let{kotlin.math.abs(it-35.0)<0.001}==true
            }
            ui.onNodeWithTag("field-選択範囲・メートル").assertTextEquals("100")
            visible("merchant-location-save").performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.merchantLocations?.size==2&&!vm.busy.value}
            val place=repo.snapshot(repo.dao.book(book)!!).first().merchantLocations.first{it.name=="GPS保存場所"}
            assertEquals(35.0,place.latitude!!,0.001);assertEquals(139.0,place.longitude!!,0.001)
            assertEquals(100,place.radiusMeters)
        }finally {
            info.flags=originalFlags;automation.serviceInfo=info
            vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()
        }
    }

}
