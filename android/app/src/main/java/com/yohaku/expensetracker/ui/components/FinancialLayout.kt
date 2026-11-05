package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun financialTaskLabel(path: String): String = when (path) {
    "overview" -> word("总览", "概要")
    "statement" -> word("账单", "請求")
    "plans" -> word("计划", "返済予定")
    "manage" -> word("管理", "管理")
    "manage/rules" -> word("计算规则", "計算条件")
    "manage/records" -> word("还款记录", "返済の記録")
    "manage/contracts" -> word("借入与合同", "借入・契約")
    "manage/limits" -> word("额度与恢复", "利用枠と回復")
    "manage/history" -> word("办理与版本历史", "手続き・バージョン履歴")
    "manage/drafts" -> word("未完成金融草稿", "未完了の金融下書き")
    else -> error("Unknown financial task: $path")
}

@Composable internal fun FinancialAccountBar(account: String, task: String?, onBack: () -> Unit) {
    val t = LocalYohaku.current
    Row(Modifier.fillMaxWidth().testTag("finance-account-bar").padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        YBackLink(onBack, Modifier.testTag("finance-back"))
        when (t.id) {
            "journal" -> Column(Modifier.weight(1f).background(t.surface).drawBehind {
                drawLine(t.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }.padding(start = 10.dp, bottom = 4.dp)) {
                YText(account, 17, Modifier.testTag("finance-account-name"))
                if(task!=null)Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    YText("↳", 12, Modifier.clearAndSetSemantics {}, color = t.accent)
                    YText(task, 12, Modifier.testTag("finance-task-title"), color = t.accent)
                }
            }
            "objects" -> {
                Box(Modifier.size(28.dp).background(t.soft, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    YText(account.take(1), 12, color = t.accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    YText(account, 14, Modifier.testTag("finance-account-name"))
                    if(task!=null)YText(task, 12, Modifier.testTag("finance-task-title").background(t.surface, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp), color = t.accent)
                }
            }
            else -> {
                YText(account, 17, Modifier.weight(1f).testTag("finance-account-name"), weight = FontWeight.Medium)
                if(task!=null)Box(Modifier.weight(0.7f).heightIn(min = 42.dp * LocalDensity.current.fontScale), contentAlignment = Alignment.CenterEnd) {
                    YText(task, 12, Modifier.testTag("finance-task-title"), color = t.muted)
                }
            }
        }
    }
}

@Composable internal fun FinancialTaskTabs(current: String, onSelect: (String) -> Unit) {
    val t = LocalYohaku.current
    val journal = t.id == "journal"
    val objects = t.id == "objects"
    Row(Modifier.fillMaxWidth().testTag("finance-navigation").selectableGroup()
        .then(if (objects) Modifier.background(t.soft, RoundedCornerShape(24.dp)).padding(horizontal = 5.dp, vertical = 4.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(if (journal) 5.dp else if (objects) 4.dp else 12.dp), verticalAlignment = Alignment.Bottom) {
        listOf("overview", "statement", "plans", "manage").forEachIndexed { index, id ->
            val selected = current == id
            val surface = when {
                journal -> Modifier.background(if (selected) t.surface else t.soft, RoundedCornerShape(5.dp, 5.dp, 0.dp, 0.dp)).drawBehind {
                    drawLine(if (selected) t.accent else t.line, Offset(0f, 0f), Offset(size.width, 0f), if (selected) 3.dp.toPx() else 1.dp.toPx())
                    if (!selected) drawLine(t.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
                objects -> Modifier.background(if (selected) t.surface else t.soft, RoundedCornerShape(20.dp))
                else -> Modifier.drawBehind {
                    if (selected) drawLine(t.ink, Offset(0f, size.height), Offset(size.width, size.height), 3.dp.toPx())
                }
            }
            Column(Modifier.weight(1f).testTag("finance-tab-$id").selectable(selected, role = Role.Tab, onClick = { onSelect(id) })
                .then(surface).padding(horizontal = 3.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!journal && !objects) YText("0${index + 1}", 9, color = t.muted, number = true)
                YText(financialTaskLabel(id), 12, color = if (selected) t.ink else t.muted, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable internal fun FinancialDirectoryRow(id: String, title: String, onOpen: () -> Unit) {
    val t = LocalYohaku.current
    val surface = when (t.id) {
        "journal" -> Modifier.background(t.surface).drawBehind {
            drawLine(t.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            drawLine(t.line, Offset(22.dp.toPx(), 0f), Offset(22.dp.toPx(), size.height), 1.dp.toPx())
        }
        "objects" -> Modifier.background(t.surface, RoundedCornerShape(12.dp))
        else -> Modifier
    }
    Row(Modifier.fillMaxWidth().testTag("finance-manage-$id").clickable(role = Role.Button, onClick = onOpen).then(surface).padding(vertical = 15.dp, horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.id == "journal") YText("·", 14, Modifier.width(22.dp), color = t.accent)
        YText(title, 15, Modifier.weight(1f))
        YText(if (t.id == "objects") "▸" else "→", 16, color = t.accent)
    }
}

/** Compact navigation row: editorial rule, ledger margin, or a softly rounded object. */
@Composable internal fun FinancialCompactRow(name:String,amount:String,detail:String,tag:String,onOpen:()->Unit) {
    val t=LocalYohaku.current
    val surface=when(t.id) {
        "journal"->Modifier.background(t.surface).drawBehind {
            drawLine(t.line,Offset(18.dp.toPx(),0f),Offset(18.dp.toPx(),size.height),1.dp.toPx())
            drawLine(t.line,Offset.Zero.copy(y=size.height),Offset(size.width,size.height),1.dp.toPx())
        }
        "objects"->Modifier.background(t.surface,RoundedCornerShape(14.dp))
        else->Modifier.drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}
    }
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(role=Role.Button,onClick=onOpen).then(surface)
        .padding(start=if(t.id=="journal")26.dp else 10.dp,end=10.dp,top=9.dp,bottom=9.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            YText(name,13)
            YText(detail,10,color=t.muted)
        }
        YText(amount,14,number=true)
        YText(if(t.id=="objects")"▸"else"→",12,color=t.accent)
    }
}
