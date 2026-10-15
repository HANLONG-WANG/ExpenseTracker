package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.yohaku.expensetracker.ui.theme.LocalYohaku
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun MiniCalendar(date:String) {
    val t=LocalYohaku.current;val value=LocalDate.parse(date)
    val height=maxOf(45.dp,with(LocalDensity.current){38.5.sp.toDp()}+6.5.dp)
    val frame=when(t.id) {
        "journal"->Modifier.border(1.dp,t.line)
        "objects"->Modifier.background(Color(0xfff0e4ce),RoundedCornerShape(9.dp))
        else->Modifier.background(t.soft,RoundedCornerShape(3.dp))
    }
    Column(Modifier.width(42.dp).height(height).then(frame).clearAndSetSemantics{contentDescription=date},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        YText(value.format(DateTimeFormatter.ofPattern("MMM",Locale.ENGLISH)).uppercase(Locale.ENGLISH),8,cssLineBox=true)
        YText(value.dayOfMonth.toString().padStart(2,'0'),23,number=true,lineHeightMultiplier=1.1f,cssLineBox=true)
    }
}
