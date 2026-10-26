package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.AccountEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun AccountManagementSheet(account:AccountEntity,vm:AppViewModel,onClose:()->Unit,onDeleted:()->Unit) {
    var review by remember(account.bookId,account.id){mutableStateOf<AccountOperationReview?>(null)}
    var refreshed by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val scroll=rememberScrollState()
    LaunchedEffect(review){scroll.scrollTo(0)}
    fun load(operation:AccountOperation)=vm.execute {
        review=vm.repository.previewAccountOperation(account.bookId,account.id,operation);refreshed=false
    }
    val toggle=if(account.active)AccountOperation.DEACTIVATE else AccountOperation.ACTIVATE
    LaunchedEffect(account.id){load(toggle)}
    val deleting=review?.operation==AccountOperation.DELETE
    val title=if(deleting)word("删除账户","口座を削除")else word("账户状态与停用","口座の状態・停止")
    YSheet(title,{if(!busy)onClose()},scrollState=scroll) {
        when(val value=review) {
            null->YText(word("正在核对账户与关联资料…","口座と関連情報を確認中…"),12)
            else->{
                val a=value.account;val currency=Currency.valueOf(a.currency)
                YText(a.name,18,Modifier.testTag("account-operation-name"))
                YText(if(a.active)word("正在使用","使用中")else word("已停用 · 历史保留","停止済み・履歴を保持"),12,color=LocalYohaku.current.warm)
                if(refreshed)YText(word("资料已变化，请按当前影响重新确认。","情報が変わりました。現在の影響を再確認してください。"),12)
                RecordFacts(listOf(
                    (if(AccountType.valueOf(a.type).debt)word("账面欠款","記録上の借入残高")else word("截至今日余额","本日までの残高")) to Money(currency,value.balanceMinor).display(),
                    word("期初信息","期首情報") to (a.openingDate+" · "+Money(currency,a.openingMinor).display()),
                    word("未清偿的已知账单","既知の未返済請求") to Money(currency,value.knownOutstandingMinor).display(),
                    word("未来一年待处理付款","今後1年の未完了支払") to value.pendingCount.toString(),
                    word("历史记录（含回收）","履歴（ごみ箱を含む）") to value.recordCount.toString(),
                    word("核对资料 / 草稿引用","照合資料・下書き参照") to "${value.usage.references["statement_batches"]?:0} / ${value.usage.draftKeys.size}"
                ),audit=true)
                YText(if(deleting)word("删除账户及其期初资料，无法恢复；不生成余额调整，也不影响其他账户。","口座と期首情報を削除し、復元できません。残高調整は作成せず、他の口座に影響しません。")else if(a.active)word("从日常选择隐藏，历史、欠款与未完成事项保留。仍可处理已有债务的还款；不会自动结清或取消安排。","通常の選択から非表示。履歴・債務・未完了予定を保持。既存債務の返済は可能で、完済や予定取消にはしません。")else word("重新加入日常选择，原记录与余额保留。","通常の選択へ戻し、元の記録と残高を保持します。"),12)
                if(deleting&&value.usage.inUse)YText(word("仍有历史、计划、核对、预留或草稿引用，请优先停用。","履歴・予定・照合・確保・下書きの参照があるため、停止を利用してください。"),12,color=LocalYohaku.current.warm)
                val label=if(deleting)word("确认删除账户","口座の削除を確定")else if(a.active)word("确认停用账户","口座を停止")else word("确认重新启用","口座を再開")
                YActionRow {
                    YButton(label,{
                        var saved=false
                        vm.execute(onSuccess={if(saved){onClose();if(value.operation==AccountOperation.DELETE)onDeleted()}}) {
                            saved=vm.repository.commitAccountOperation(value)
                            if(!saved){review=vm.repository.previewAccountOperation(a.bookId,a.id,value.operation);refreshed=true}
                            else vm.reminders?.rebuildSafely(false)
                        }
                    },Modifier.testTag("confirm-account-operation"),primary=true,enabled=!busy&&(!deleting||!value.usage.inUse))
                    if(deleting)YLink(word("返回状态管理","状態管理へ戻る"),{load(toggle)},Modifier.testTag("account-operation-back"))
                    else YLink(word("查看删除资格与影响","削除の可否・影響を確認"),{load(AccountOperation.DELETE)},Modifier.testTag("account-delete-preview"))
                }
            }
        }
    }
}
