package app.ledger.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTestInfrastructureDeviceTest {
    @Test
    fun migrationHelperIsAvailableToDeviceTests() {
        assertEquals("androidx.room.testing.MigrationTestHelper", MigrationTestHelper::class.java.name)
    }
}
