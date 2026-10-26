package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.LocalYohaku

/** Inside an action row: the primary action's width modifier (fills the rest of its line). */
internal val LocalActionRow = staticCompositionLocalOf<Modifier?> { null }

/** Keep each action at its natural width; wrap whole actions on narrow screens. */
@Composable fun YActionRow(content:@Composable ()->Unit) {
    val gap=when(LocalYohaku.current.id){"journal"->10.dp;"objects"->12.dp;else->8.dp}
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap),
        verticalArrangement=Arrangement.spacedBy(gap),itemVerticalAlignment=Alignment.CenterVertically) {
        CompositionLocalProvider(LocalActionRow provides Modifier.weight(1f)) {
            content()
        }
    }
}
