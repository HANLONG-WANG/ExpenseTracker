package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun CategoryStepFooter(){
    val t=LocalYohaku.current
    // The sheet contributes 15dp of the original 25dp top margin.
    Row(Modifier.fillMaxWidth().padding(top=10.dp).testTag("category-step-footer"),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
        val line=when(t.id){"journal"->25f;"objects"->24f;else->19f}
        YText("01",16,number=true,weight=FontWeight.Bold,color=if(t.id=="journal")t.warm else t.ink,lineHeightMultiplier=line/16,cssLineBox=true)
        val size=if(t.id=="journal")12 else 9
        val smallLine=if(t.id=="journal")17f else 13f
        YText("—",size,color=t.muted,lineHeightMultiplier=smallLine/size,cssLineBox=true)
        YText("02 "+word("填写并保存","入力して保存"),size,color=t.muted,lineHeightMultiplier=smallLine/size,cssLineBox=true)
    }
}
