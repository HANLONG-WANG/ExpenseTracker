package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.reminders.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReminderPlanTest {
    private val zone=ZoneId.of("Asia/Tokyo")
    private val now=Instant.parse("2026-10-16T01:00:00Z")
    private val prefs=mapOf("a" to ReminderPreferences(enabled=true),"b" to ReminderPreferences(enabled=true))
    private fun bill(book:String="a")=ReminderItem(book,"bill:one","REPAYMENT","2026-10-17","JPY",10000,"Card")
    @Test fun daysDoNotCatchUpAndDeliverySurvivesRestart() {
        val plan=planReminders(listOf(bill(),bill("b")),prefs,ReminderDeliveryState(),now,zone)
        assertEquals(2,plan.due.size) // yesterday's seven-day alert is never replayed
        assertEquals(setOf("a","b"),plan.due.map{it.item.bookId}.toSet())
        val saved=recordReminderDelivery(ReminderDeliveryState(),plan.due,now,zone)
        assertTrue(planReminders(listOf(bill(),bill("b")),prefs,saved,now.plusSeconds(3600),zone).slots.isEmpty())
        assertTrue(planReminders(emptyList(),prefs,saved,now,zone).slots.isEmpty())
    }
    @Test fun changedDateDisabledKindAndDeadlineTimezoneRebuild() {
        val original=planReminders(listOf(bill()),prefs,ReminderDeliveryState(),now,zone)
        val changed=planReminders(listOf(bill().copy(date="2026-10-24")),prefs,ReminderDeliveryState(),now,zone)
        assertFalse(original.slots.map{it.key}.any{it in changed.slots.map{r->r.key}})
        assertTrue(planReminders(listOf(bill()),mapOf("a" to ReminderPreferences(enabled=true,repayment=false)),ReminderDeliveryState(),now,zone).slots.isEmpty())
        val deadline=bill().copy(kind="DEADLINE",zoneId="America/New_York",deadlineInstant=Instant.parse("2026-10-17T12:00:00Z").toEpochMilli())
        val plan=planReminders(listOf(deadline),prefs,ReminderDeliveryState(),now,zone)
        assertEquals(Instant.parse("2026-10-16T13:00:00Z").toEpochMilli(),plan.slots.single().at)
        assertTrue(plan.due.isEmpty())
    }
    @Test fun riskEscalationIgnoresSmallChangesAndRetainsLastNotifiedBaseline() {
        val risk=bill().copy(key="funds:bank",kind="FUNDS",date="2026-10-25",risk=true,estimated=true,amountMinor=1000,basisMinor=10000)
        val first=planReminders(listOf(risk),prefs,ReminderDeliveryState(),now,zone)
        val saved=recordReminderDelivery(ReminderDeliveryState(),first.due,now,zone)
        assertTrue(planReminders(listOf(risk.copy(amountMinor=1101)),prefs,saved,now,zone).due.isEmpty())
        assertEquals(1,planReminders(listOf(risk.copy(amountMinor=1500)),prefs,saved,now,zone).due.size)
        assertEquals(1,planReminders(listOf(risk.copy(estimated=false)),prefs,saved,now,zone).due.size)
        assertEquals(1,planReminders(listOf(risk),prefs,saved,now.plusSeconds(6*86400),zone).due.size)
        assertFalse(RiskNotificationPolicy.significantIncrease(1000,1500,10000,"CNY")) // must exceed CN¥5
        assertTrue(RiskNotificationPolicy.significantIncrease(1000,1501,10000,"CNY"))
    }
}
