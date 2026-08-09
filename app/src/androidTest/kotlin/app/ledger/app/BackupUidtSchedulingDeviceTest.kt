package app.ledger.app

import android.app.job.JobScheduler
import android.content.Context
import android.net.NetworkCapabilities
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.ledger.core.common.StableId
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34, maxSdkVersion = 36)
class BackupUidtSchedulingDeviceTest {
    @Test
    fun api34ManualDriveBackupSchedulesUserInitiatedJobWithOpaqueOperationIdOnly() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val operationId = StableId.fromUuid(UUID(30, 30_001))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobId = operationId.hashCode() and Int.MAX_VALUE
        ActivityScenario.launch(MainActivity::class.java).use {
            BackupWorkScheduler.enqueue(context, operationId, drive = true, userInitiated = true, unmetered = true)
            val pending = scheduler.getPendingJob(jobId)
            assertNotNull(pending)
            assertTrue(requireNotNull(pending).isUserInitiated)
            assertTrue(
                requireNotNull(pending.requiredNetwork)
                    .hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
            assertTrue(pending.extras.keySet() == setOf(BackupWorker.INPUT_OPERATION_ID))
            assertTrue(operationId.toString() == pending.extras.getString(BackupWorker.INPUT_OPERATION_ID))
        }
        scheduler.cancel(jobId)
    }
}
