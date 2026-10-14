package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ReadFailureScreen(message:String,onRetry:()->Unit){
    var details by remember(message){mutableStateOf(false)}
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(pageContentPadding()).padding(bottom=28.dp).testTag("read-failure"),verticalArrangement=Arrangement.spacedBy(15.dp)){
        YTitle(word("无法读取账本","帳簿を読み込めません"),"YOHAKU / LOCAL RECORDS")
        YText(word("请重新读取。如果仍然失败，可以查看错误详情。","再読み込みしてください。解決しない場合はエラー詳細を確認できます。"),12,color=LocalYohaku.current.muted)
        YButton(word("重新读取账本","再読み込み"),onRetry,Modifier.testTag("retry-read"),primary=true)
        YLink(if(details)word("收起错误详情","エラー詳細を閉じる") else word("展开错误详情","エラー詳細を表示"),{details=!details})
        if(details)YText(message,11,Modifier.testTag("read-error-details"),color=LocalYohaku.current.muted)
    }
}
