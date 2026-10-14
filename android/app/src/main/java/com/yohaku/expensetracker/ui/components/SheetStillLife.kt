package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The original objects motif is shared; the sheet defines its own size and stroke color. */
@Composable internal fun SheetStillLife(){
    AnalysisArtwork(Modifier.size(68.dp,46.dp).testTag("sheet-still-life"),tint=Color(0xffbb936d))
}
