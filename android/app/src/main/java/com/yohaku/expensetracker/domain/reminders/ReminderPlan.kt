package com.yohaku.expensetracker.domain.reminders

import kotlinx.serialization.Serializable
import java.time.*

@Serializable data class ReminderPreferences(
    val enabled:Boolean=false,val repayment:Boolean=true,val deadline:Boolean=true,val recurring:Boolean=true,
    val budget:Boolean=true,val funds:Boolean=true,val repaymentDays:List<Int> = listOf(7,1),
    val deadlineDays:List<Int> = listOf(3,1),val recurringDays:List<Int> = listOf(1),val time:String="09:00"
) {
    fun validate() {
        LocalTime.parse(time)
        require(listOf(repaymentDays,deadlineDays,recurringDays).all{it.isNotEmpty()&&it.size<=10&&it.all{n->n in 0..365}}){"提前天数请输入0–365的整数，以逗号分隔 / 0〜365日前をカンマ区切りで指定してください"}
    }
    fun allows(kind:String)=enabled&&when(kind){"REPAYMENT"->repayment;"DEADLINE"->deadline;"RECURRING"->recurring;"BUDGET"->budget;"FUNDS"->funds;else->false}
}

/** Amounts are always in the item's currency. No sums across currencies or books. */
@Serializable data class ReminderItem(
    val bookId:String,val key:String,val kind:String,val date:String,val currency:String,val amountMinor:Long?,
    val title:String,val account:String="",val estimated:Boolean=false,val zoneId:String?=null,val deadlineInstant:Long?=null,
    val risk:Boolean=false,val basisMinor:Long=0,val mergeKey:String=key,val targetAccountId:String?=null,val targetMonth:String?=null
)
@Serializable data class ReminderSlot(val key:String,val at:Long,val item:ReminderItem)
@Serializable data class NotifiedRisk(val amountMinor:Long,val estimated:Boolean,val near:Boolean)
@Serializable data class ReminderDeliveryState(
    val sent:Map<String,Long> = emptyMap(),val risks:Map<String,NotifiedRisk> = emptyMap()
)
data class ReminderPlan(val slots:List<ReminderSlot>,val due:List<ReminderSlot>,val activeRiskKeys:Set<String>)

object RiskNotificationPolicy {
    const val NEAR_DAYS=3L
    const val INCREASE_PERCENT=5
    fun significantIncrease(before:Long,after:Long,basis:Long,currency:String):Boolean {
        val increase=after.toBigDecimal()-before.toBigDecimal()
        val floor=when(currency){"JPY"->100;"CNY"->500;"USD"->100;else->error("Unsupported currency")}.toBigDecimal()
        return increase>floor&&increase*100.toBigDecimal()>=basis.toBigDecimal()*INCREASE_PERCENT.toBigDecimal()
    }
}

/** Rebuild from current facts; missed earlier days never become a burst of overdue alarms. */
fun planReminders(items:List<ReminderItem>,preferences:Map<String,ReminderPreferences>,state:ReminderDeliveryState,now:Instant,zone:ZoneId):ReminderPlan {
    val slots=mutableListOf<ReminderSlot>();val active=mutableSetOf<String>()
    for(item in items) {
        val prefs=preferences[item.bookId]?:ReminderPreferences()
        if(!prefs.allows(item.kind))continue
        val itemZone=item.zoneId?.let(ZoneId::of)?:zone
        val today=now.atZone(itemZone).toLocalDate()
        val date=LocalDate.parse(item.date)
        val identity="${item.bookId}/${item.key}"
        if(item.risk) {
            val amount=item.amountMinor?:continue
            if(amount<=0)continue
            active+=identity
            val near=!date.isAfter(today.plusDays(RiskNotificationPolicy.NEAR_DAYS))
            val before=state.risks[identity]
            if(before==null||before.estimated&&!item.estimated||!before.near&&near||RiskNotificationPolicy.significantIncrease(before.amountMinor,amount,item.basisMinor,item.currency)) {
                val at=today.atTime(LocalTime.parse(prefs.time)).atZone(itemZone).toInstant().toEpochMilli()
                slots+=ReminderSlot(identity,at,item)
            }
        } else {
            if(item.deadlineInstant?.let{it<=now.toEpochMilli()}==true)continue
            val days=when(item.kind){"REPAYMENT"->prefs.repaymentDays;"DEADLINE"->prefs.deadlineDays;else->prefs.recurringDays}
            for(day in days.distinct()) {
                val reminderDate=date.minusDays(day.toLong())
                if(reminderDate<today)continue
                val at=reminderDate.atTime(LocalTime.parse(prefs.time)).atZone(itemZone).toInstant().toEpochMilli()
                if(item.deadlineInstant?.let{at>=it}==true)continue
                val key="$identity/${item.date}/$day"
                if(key !in state.sent)slots+=ReminderSlot(key,at,item)
            }
        }
    }
    return ReminderPlan(slots.sortedBy{it.at},slots.filter{it.at<=now.toEpochMilli()},active)
}

fun recordReminderDelivery(state:ReminderDeliveryState,slots:List<ReminderSlot>,now:Instant,zone:ZoneId):ReminderDeliveryState {
    val risks=state.risks.toMutableMap()
    slots.filter{it.item.risk}.forEach{slot->val i=slot.item
        val today=now.atZone(i.zoneId?.let(ZoneId::of)?:zone).toLocalDate()
        risks["${i.bookId}/${i.key}"]=NotifiedRisk(i.amountMinor!!,i.estimated,!LocalDate.parse(i.date).isAfter(today.plusDays(RiskNotificationPolicy.NEAR_DAYS)))
    }
    return ReminderDeliveryState(state.sent.filterValues{it>now.minusSeconds(400L*86400).toEpochMilli()}+slots.filterNot{it.item.risk}.associate{it.key to now.toEpochMilli()},risks)
}
