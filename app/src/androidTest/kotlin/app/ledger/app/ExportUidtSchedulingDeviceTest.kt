package app.ledger.app

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.ledger.core.common.StableId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34, maxSdkVersion = 36)
class ExportUidtSchedulingDeviceTest {
    @Test
    fun api34RemoteSafExportSchedulesUserInitiatedJobWithOpaqueIdOnly() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val operationId = StableId.fromUuid(UUID(0x29L, 0x29001L))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobId = operationId.hashCode() and Int.MAX_VALUE
        ActivityScenario.launch(MainActivity::class.java).use {
            ExportWorkScheduler.enqueue(context, operationId, remoteProvider = true)
            val pending = scheduler.getPendingJob(jobId)
            assertNotNull(pending)
            assertTrue(requireNotNull(pending).isUserInitiated)
            assertEquals(setOf(ExportWorker.INPUT_OPERATION_ID), pending.extras.keySet())
            assertEquals(operationId.toString(), pending.extras.getString(ExportWorker.INPUT_OPERATION_ID))
        }
        scheduler.cancel(jobId)
    }
}
