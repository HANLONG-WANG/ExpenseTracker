package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.LocalYohaku

/** Final theme CSS main gutters, including its <=360px breakpoint. */
@Composable internal fun pageContentPadding():PaddingValues {
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    return when(LocalYohaku.current.id){
        "journal"->PaddingValues(start=if(narrow)25.dp else 28.dp,end=if(narrow)16.dp else 19.dp)
        "objects"->PaddingValues(horizontal=if(narrow)17.dp else 20.dp)
        else->PaddingValues(horizontal=if(narrow)18.dp else 25.dp)
    }
}
