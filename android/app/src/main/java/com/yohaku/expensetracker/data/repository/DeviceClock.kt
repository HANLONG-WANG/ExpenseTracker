package com.yohaku.expensetracker.data.repository

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Follow system timezone changes even while the repository stays alive. Explicit test clocks remain injectable. */
class DeviceClock:Clock() {
    override fun instant():Instant=Instant.now()
    override fun getZone():ZoneId=ZoneId.systemDefault()
    override fun withZone(zone:ZoneId):Clock=Clock.system(zone)
}
