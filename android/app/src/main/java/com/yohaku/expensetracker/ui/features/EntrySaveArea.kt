package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun EntrySaveArea(editing:Boolean,busy:Boolean,canSave:Boolean,onSave:()->Unit,onCancel:()->Unit,saveLabel:String?=null){
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth()){
        YButton(saveLabel?:if(editing)word("保存修改","変更を保存") else word("保存记录","記録を保存"),onSave,Modifier.testTag("entry-save-button"),primary=true,enabled=!busy&&canSave)
        if(editing)Box(Modifier.fillMaxWidth().padding(vertical=17.dp),contentAlignment=Alignment.Center){
            YText(word("取消，保留原记录","取消・元の記録を保持"),12,Modifier.testTag("entry-cancel-edit").clickable(enabled=!busy,role=Role.Button,onClick=onCancel).padding(horizontal=6.dp,vertical=1.dp),color=t.muted,lineHeightMultiplier=17f/12,cssLineBox=true)
        }
    }
}
