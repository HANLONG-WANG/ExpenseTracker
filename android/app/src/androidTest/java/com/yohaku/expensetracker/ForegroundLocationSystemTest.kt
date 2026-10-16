package com.yohaku.expensetracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.platform.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import androidx.test.core.app.ActivityScenario
import org.junit.runner.RunWith

/** Run each permission/system-state case separately; grants/revokes must happen outside instrumentation. */
@RunWith(AndroidJUnit4::class)
class ForegroundLocationSystemTest {
    private lateinit var activity:ActivityScenario<MainActivity>
    @Before fun foreground(){activity=ActivityScenario.launch(MainActivity::class.java)}
    @After fun close(){activity.close()}
    private val source=ForegroundLocation(InstrumentationRegistry.getInstrumentation().targetContext)
    @Test fun deniedPermissionIsExplicit()=runBlocking {
        try {source.currentFix();fail("Expected denied permission")}
        catch(e:LocationException){assertEquals(LocationFailure.PERMISSION,e.reason)}
    }
    @Test fun disabledSystemLocationIsExplicit()=runBlocking {
        try {source.currentFix();fail("Expected system location off")}
        catch(e:LocationException){assertEquals(LocationFailure.SYSTEM_OFF,e.reason)}
    }
    @Test fun receivesCurrentAndroidGpsFix()=runBlocking {
        val fix=source.currentFix()
        assertEquals(35.0,fix.latitude,0.001)
        assertEquals(139.0,fix.longitude,0.001)
        assertTrue(fix.accuracyMeters>0)
    }
}
