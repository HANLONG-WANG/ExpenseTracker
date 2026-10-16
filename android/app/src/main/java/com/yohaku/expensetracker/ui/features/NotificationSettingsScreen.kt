package com.yohaku.expensetracker.ui.features

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.domain.reminders.ReminderPreferences
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.word
import com.yohaku.expensetracker.ui.theme.LocalYohaku
import java.time.Instant
import java.time.ZoneId

@Composable internal fun NotificationSettingsScreen(s:BookSnapshot,vm:AppViewModel) {
    val settings by vm.preferences.reminderSettings.collectAsStateWithLifecycle(initialValue=null)
    val stored=settings?:return
    val saved=stored[s.book.id]?:ReminderPreferences()
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    var allowed by remember{mutableStateOf(vm.reminders?.allowed()==true)}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){allowed=vm.reminders?.allowed()==true;vm.execute{vm.reminders?.rebuildSafely(false)}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed=vm.reminders?.allowed()==true
        vm.execute{vm.reminders?.rebuildSafely(false)}
    }
    var enabled by rememberSaveable(s.book.id){mutableStateOf(saved.enabled)}
    var repayment by rememberSaveable(s.book.id){mutableStateOf(saved.repayment)}
    var deadline by rememberSaveable(s.book.id){mutableStateOf(saved.deadline)}
    var recurring by rememberSaveable(s.book.id){mutableStateOf(saved.recurring)}
    var budget by rememberSaveable(s.book.id){mutableStateOf(saved.budget)}
    var funds by rememberSaveable(s.book.id){mutableStateOf(saved.funds)}
    var repaymentDays by rememberSaveable(s.book.id){mutableStateOf(saved.repaymentDays.joinToString(","))}
    var deadlineDays by rememberSaveable(s.book.id){mutableStateOf(saved.deadlineDays.joinToString(","))}
    var recurringDays by rememberSaveable(s.book.id){mutableStateOf(saved.recurringDays.joinToString(","))}
    var time by rememberSaveable(s.book.id){mutableStateOf(saved.time)}
    var validation by remember{mutableStateOf<String?>(null)}
    YLead(word("只在重要的时候，提醒你。","大事なときだけ、お知らせ。"),word("金额直接显示。不提供应用锁，也不每天催你记账。","金額を表示。アプリロック・毎日の記録通知はありません。"),"ONLY WHAT MATTERS",pageFlow=true)
    Column(Modifier.fillMaxWidth()) {
        NotificationPreferenceToggle(word("开启本账本的重要提醒","この帳簿の重要な通知を有効にする"),enabled,{enabled=it},Modifier.testTag("reminder-enabled"))
        NotificationPreferenceToggle(word("信用卡 / 借款还款","カード・借入の返済"),repayment,{repayment=it})
        NotificationPreferenceToggle(word("还款方式变更截止","支払方法の変更締切"),deadline,{deadline=it})
        NotificationPreferenceToggle(word("未确认周期账单","未確認の定期支払"),recurring,{recurring=it})
        NotificationPreferenceToggle(word("预算超支风险","予算超過のリスク"),budget,{budget=it})
        NotificationPreferenceToggle(word("扣款账户资金缺口","引落口座の資金不足"),funds,{funds=it})
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        YField("repayment-days",repaymentDays,{repaymentDays=it},displayLabel=word("还款提前天数 / 逗号分隔","返済の何日前・カンマ区切り"))
        YField("deadline-days",deadlineDays,{deadlineDays=it},displayLabel=word("变更截止提前天数","変更締切の何日前"))
        YField("recurring-days",recurringDays,{recurringDays=it},displayLabel=word("周期账单提前天数","定期支払の何日前"))
        YField("reminder-time",time,{time=it},displayLabel=word("提醒时间 / HH:mm","通知時刻 / HH:mm"))
        YText(word("已完成事项取消后续提醒；同一缺口不每日重复。系统可能因省电而延迟通知，应用内事项始终可查看。","完了後は通知を取消。同じ不足を毎日通知しません。省電力により配信が遅れる場合も、アプリ内では確認できます。"),11)
    }
    validation?.let{YText(it,12)}
    val validationMessage=word("请核对提前天数（0–365，以逗号分隔）和时间（HH:mm）。","何日前（0〜365、カンマ区切り）と時刻（HH:mm）を確認してください。")
    YButton(word("保存提醒偏好","通知設定を保存"),{
        try {
            fun days(raw:String)=raw.replace('，',',').split(',').map{it.trim().toInt()}.distinct().sortedDescending()
            val value=ReminderPreferences(enabled,repayment,deadline,recurring,budget,funds,days(repaymentDays),days(deadlineDays),days(recurringDays),time.trim())
            value.validate();validation=null;focus.clearFocus();keyboard?.hide()
            vm.execute(onSuccess={if(value.enabled&&!allowed&&Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)}) {
                vm.preferences.saveReminderSettings(s.book.id,value);vm.reminders?.rebuildSafely(false)
            }
        }catch(e:Exception){validation=validationMessage}
    },Modifier.testTag("save-reminder-settings"),primary=true)
    if(!allowed)Panel(Modifier.testTag("reminder-permission-status")) {
        YText(word("通知尚未开启","通知は未許可"),16)
        YText(word("拒绝权限不会影响记账和应用内重要事项。允许通知后，系统会按当前未完成事项重建。","許可しなくても記録とアプリ内の予定は利用できます。許可後、未完了の予定から再構築します。"),11)
        YButton(word("打开系统通知设置","端末の通知設定を開く"),{context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))})
    }
    vm.reminders?.let{coordinator->
        val plan by coordinator.plan.collectAsStateWithLifecycle()
        val failure by coordinator.failure.collectAsStateWithLifecycle()
        failure?.let{YText(word("提醒重建未成功：","通知の再構築に失敗：")+it,12)}
        ThemeSection(word("当前提醒计划","現在の通知予定"),"SCHEDULE")
        val rows=plan.filter{it.item.bookId==s.book.id}
        if(rows.isEmpty())YEmpty(word("当前没有待调度通知","現在、配信予定はありません"))
        rows.forEach{row->YText(Instant.ofEpochMilli(row.at).atZone(row.item.zoneId?.let(ZoneId::of)?:ZoneId.systemDefault()).toString()+" · "+row.item.title+" · "+outlookAmount(row.item.currency,row.item.amountMinor,row.item.estimated),11,Modifier.testTag("reminder-plan-row"))}
    }
}

/** Frozen UI24 experience switches: each theme owns its row, caption and switch shape. */
@Composable private fun NotificationPreferenceToggle(label:String,value:Boolean,onChange:(Boolean)->Unit,modifier:Modifier=Modifier) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val narrow=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp<=360
    val edge=Modifier.drawBehind {
        val effect=if(journal||objects)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))else null
        if(!objects)drawLine(t.line,Offset(0f,.5.dp.toPx()),Offset(size.width,.5.dp.toPx()),1.dp.toPx(),pathEffect=effect)
        drawLine(if(objects)Color(0xffd9c4a0)else t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=effect)
    }
    Row(modifier.fillMaxWidth().then(edge).toggleable(value,role=if(journal)Role.Checkbox else Role.Switch,onValueChange=onChange).padding(vertical=13.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            YText(label,if(narrow)12 else if(journal)16 else 13,weight=if(journal)FontWeight.Normal else FontWeight.Medium,lineHeightMultiplier=if(narrow)1.5f else if(journal)24f/16 else 19f/13,cssLineBox=true)
            Spacer(Modifier.height(7.dp))
            YText(word("只提醒未完成事项；风险首次出现或明显加重才推送。","未完了のみ。リスクは初回・悪化時に通知。"),if(narrow)10 else if(journal)11 else 9,color=t.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
        }
        when(t.id) {
            "journal"->Box(Modifier.size(25.dp).rotate(-3f).background(Color(0xfffff7e2),RoundedCornerShape(1.dp)).border(1.dp,t.accent,RoundedCornerShape(1.dp)),contentAlignment=Alignment.Center) {
                if(value)androidx.compose.material3.Text("✓",Modifier.offset(y=(-4).dp).rotate(-9f).wrapContentHeight(Alignment.Top,unbounded=true),color=t.warm,fontFamily=t.body,fontSize=with(androidx.compose.ui.platform.LocalDensity.current){30.dp.toSp()},lineHeight=with(androidx.compose.ui.platform.LocalDensity.current){30.dp.toSp()})
            }
            "objects"->Box(Modifier.size(39.dp,23.dp).background(if(value)Color(0xffb8825f)else Color(0xffd9c8ad),RoundedCornerShape(9.dp))) {
                Box(Modifier.offset(x=if(value)19.dp else 3.dp,y=3.dp).size(17.dp).background(Color(0xfffff8e6),RoundedCornerShape(6.dp)))
            }
            else->Box(Modifier.size(36.dp,20.dp).background(if(value)t.soft else t.bg).border(1.dp,t.ink)) {
                Box(Modifier.offset(x=if(value)19.dp else 3.dp,y=3.dp).size(12.dp).background(if(value)t.ink else t.muted))
            }
        }
    }
}
