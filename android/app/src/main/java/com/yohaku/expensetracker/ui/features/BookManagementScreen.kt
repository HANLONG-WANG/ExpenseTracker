package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable internal fun BookManagementScreen(overviews:List<BookOverview>,current:String?,vm:AppViewModel,onCreate:()->Unit) {
    var trash by rememberSaveable{mutableStateOf(overviews.none{it.book.deletedAt==null})}
    var emptyReview by remember{mutableStateOf<List<BookLifecycleReview>?>(null)}
    var emptyChanged by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    if(trash)YLead(word("给误删，留一点余地。","消しすぎにも、余白を。"),word("这里是应用内恢复，不是外部备份。到期后自动永久清理。","アプリ内の復元です。外部バックアップではありません。期限後は自動で完全削除。"),"RECOVER WITHIN 30 DAYS",pageFlow=true)
    else YLead(word("每一本，都妥善安放。","帳簿を、きちんとしまう。"),word("归档保留资料与未完提醒；整本回收保留30天。","アーカイブは情報・未完了の通知を保持。ごみ箱は30日間保存。"),"BOOKS / KEEP & RECOVER",pageFlow=true)
    YLink(if(trash)word("查看正常与归档账本","通常・アーカイブ済みの帳簿へ")else word("查看整本回收站","帳簿のごみ箱を見る"),{trash=!trash},Modifier.testTag("book-management-toggle-trash"))
    val shown=overviews.filter{(it.book.deletedAt!=null)==trash}
    if(shown.isEmpty())YEmpty(if(trash)word("整本回收站是空的","帳簿のごみ箱は空です")else word("尚无正常或归档账本","通常・アーカイブ済みの帳簿はありません"))
    for(row in shown) {
        val book=row.book
        ThemeAuditPanel(book.name,Modifier.testTag("managed-book-${book.id}")) {
            YText(when{book.deletedAt!=null->word("整本已回收","帳簿をごみ箱に移動済み");book.archived->word("已归档","アーカイブ済み");book.id==current->word("正在使用","使用中");else->word("正常账本","通常の帳簿")},if(LocalYohaku.current.id=="journal")12 else 10,if(LocalYohaku.current.id=="objects")Modifier.background(LocalYohaku.current.soft,RoundedCornerShape(8.dp)).padding(horizontal=9.dp,vertical=5.dp)else Modifier,color=LocalYohaku.current.warm,letterSpacing=.5f,lineHeightMultiplier=1.5f,cssLineBox=true)
            BookScopeFacts(BookScope(book,row.accountCount,row.recordCount,row.fileCount,row.financialPlanCount,row.statementCount))
            if(book.deletedAt!=null) {
                val expiry=Instant.ofEpochMilli(book.deletedAt).plus(recoveryDuration)
                val available=expiry.isAfter(vm.repository.clock.instant())
                YText(word("保留至：","保存期限：")+expiry.atZone(vm.repository.clock.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),11)
                if(book.archived)YText(word("恢复后仍保持归档，可再取消归档。","復元後もアーカイブを保持。後から解除できます。"),11)
                YActionRow {
                    YButton(word("恢复账本","帳簿を復元"),{vm.previewBookOperation(book.id,BookOperation.RESTORE)},Modifier.testTag("book-restore-${book.id}"),enabled=available&&!busy)
                    YLink(word("永久删除","完全削除"),{vm.previewBookOperation(book.id,BookOperation.PURGE)},Modifier.testTag("book-purge-${book.id}"))
                }
            }else {
                if(book.archived)YText(word("从日常切换隐藏，未完成付款仍按本账本提醒。","通常の切替から非表示。未完了の支払はこの帳簿の名前で通知。"),11)
                YActionRow {
                    YButton(if(book.archived)word("取消归档","アーカイブを解除")else word("归档","アーカイブ"),{vm.previewBookOperation(book.id,if(book.archived)BookOperation.UNARCHIVE else BookOperation.ARCHIVE)},Modifier.testTag("book-archive-${book.id}"),enabled=!busy)
                    YLink(word("查看资料","情報を見る"),{vm.selectBook(book.id)},Modifier.testTag("book-open-${book.id}"))
                    YLink(word("移至回收站","ごみ箱へ"),{vm.previewBookOperation(book.id,BookOperation.TRASH)},Modifier.testTag("book-trash-${book.id}"))
                }
            }
        }
    }
    if(trash&&shown.isNotEmpty())YButton(word("清空整本回收站","帳簿のごみ箱を空にする"),{vm.execute{emptyReview=vm.repository.previewEmptyBookTrash();emptyChanged=false}},Modifier.testTag("empty-book-trash"),enabled=!busy)
    YButton(word("＋ 创建独立账本","＋ 独立した帳簿を作成"),onCreate,Modifier.testTag("book-management-create"),primary=true)
    emptyReview?.let{reviews->YSheet(word("永久清空整本回收站","帳簿のごみ箱を完全に空にする"),{if(!busy)emptyReview=null}) {
        YText(word("这些账本及全部资料、文件将永久删除，无法恢复。其他账本保留。","次の帳簿と全情報・ファイルを完全削除し、復元できません。他の帳簿は保持します。"),12)
        if(emptyChanged)YText(word("回收站已变化，请重新确认。","ごみ箱が変わりました。再確認してください。"),12)
        reviews.forEach{YText(it.scope.book.name,15);BookScopeFacts(it.scope)}
        YButton(word("确认永久清空","完全に空にする"),{vm.execute {
            if(vm.repository.commitEmptyBookTrash(reviews)) {
                emptyReview=null;vm.repository.collectUnusedMedia();vm.preferences.pruneBookPreferences(vm.repository.books.first().map{it.id}.toSet());vm.reminders?.rebuildSafely(false)
            }else {emptyReview=vm.repository.previewEmptyBookTrash();emptyChanged=true}
        }},Modifier.testTag("confirm-empty-book-trash"),primary=true,enabled=!busy)
    }}
}

@Composable private fun BookScopeFacts(scope:BookScope) {
    RecordFacts(listOf(word("账户","口座") to scope.accounts.toString(),word("记录（含回收记录）","記録（ごみ箱を含む）") to scope.records.toString(),
        word("附件与小票原图","添付・レシート原画像") to scope.files.toString(),word("金融计划 / 核对资料","金融プラン・照合資料") to "${scope.financialPlans} / ${scope.statements}"),audit=true)
}
@Composable internal fun BookOperationConfirmation(vm:AppViewModel) {
    val review by vm.bookOperationReview.collectAsStateWithLifecycle()
    val refreshed by vm.bookOperationRefreshed.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    review?.let{value->
        val title=when(value.operation) {
            BookOperation.ARCHIVE->word("归档账本","帳簿をアーカイブ")
            BookOperation.UNARCHIVE->word("取消归档","アーカイブを解除")
            BookOperation.TRASH->word("整本移至回收站","帳簿をごみ箱へ")
            BookOperation.RESTORE->word("恢复原账本","元の帳簿を復元")
            BookOperation.PURGE->word("永久删除账本","帳簿を完全削除")
        }
        YSheet(title,{if(!busy)vm.bookOperationReview.value=null}) {
            YText(value.scope.book.name,18)
            if(refreshed)YText(word("资料已变化，请按当前范围重新确认。","情報が変わりました。現在の対象を再確認してください。"),12)
            BookScopeFacts(value.scope)
            YText(when(value.operation) {
                BookOperation.ARCHIVE->word("移出日常切换列表。资料、欠款和未完成提醒保留。","通常の切替から非表示。情報・債務・未完了の通知を保持します。")
                BookOperation.UNARCHIVE->word("重新加入日常账本切换列表，原资料不变。","通常の帳簿切替へ戻します。元の情報を保持します。")
                BookOperation.TRASH->word("立即退出正常视图并取消本账本通知。30天内可恢复原ID和全部资料，到期永久清理。其他账本不受影响。","通常表示から外し、この帳簿の通知を取消。30日以内は元のIDと全情報を復元。期限後は完全削除。他の帳簿に影響しません。")
                BookOperation.RESTORE->word("恢复原ID、资料和文件，重建未完成提醒。归档状态也会保留。","元のID・情報・ファイルを復元し、未完了の通知を再構築。アーカイブ状態も保持します。")
                BookOperation.PURGE->word("账本及全部账务、金融、核对与附件资料永久删除，无法撤销。其他账本不受影响。","帳簿と全記録・金融・照合・添付情報を完全削除し、元に戻せません。他の帳簿に影響しません。")
            },12)
            YButton(word("确认","確定"),vm::commitBookOperation,Modifier.testTag("confirm-book-operation"),primary=true,enabled=!busy)
        }
    }
}

/** Retained books remain reachable even when there is no ordinary book left to display. */
@Composable internal fun BookLibrary(ready:AppState.Ready,vm:AppViewModel) {
    var creating by rememberSaveable{mutableStateOf(false)}
    val fallback=ready.books.firstOrNull{it.deletedAt==null}?:ready.books.first()
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).testTag("book-library")) {
        YAppHeader(null,{},{},showActions=false)
        Column(Modifier.padding(pageContentPadding()).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
            PageEdition("book-management")
            BookManagementScreen(ready.overviews,null,vm,{creating=true})
            PageFootnote("book-management")
        }
    }
    if(creating)BookEditor(null,fallback,vm,{creating=false})
}
