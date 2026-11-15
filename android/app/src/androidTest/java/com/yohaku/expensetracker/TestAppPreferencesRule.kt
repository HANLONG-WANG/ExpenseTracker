package com.yohaku.expensetracker

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.repository.AppPreferences
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.io.File
import java.util.UUID

/**
 * One isolated store per test, shared by the ViewModel, rates and cloud commands.
 * Use @Rule(order=-2) so it outlives Compose and @After cleanup of its consumers.
 * Recovery phases opt into a named file and set deleteAfterTest only in the final phase.
 */
class TestAppPreferencesRule(private val persistentName: String? = null) : ExternalResource() {
    lateinit var preferences: AppPreferences
        private set
    lateinit var storage: DataStore<Preferences>
        private set
    lateinit var file: File
        private set
    var deleteAfterTest: Boolean = persistentName == null
    private lateinit var job: CompletableJob

    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = if (persistentName == null) {
            File(context.cacheDir, "test-preferences/${UUID.randomUUID()}")
        } else {
            File(context.filesDir, "verification/test-preferences/$persistentName")
        }
        file = File(directory, "test.preferences_pb")
        job = SupervisorJob()
        storage = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { file }
        )
        preferences = AppPreferences(context, storage)
    }

    override fun after() {
        // Cancellation alone can leave a writer alive while its file is being deleted/reopened.
        runBlocking { job.cancelAndJoin() }
        if (deleteAfterTest) {
            check(file.parentFile!!.deleteRecursively()) { "Could not delete test preferences: $file" }
        }
    }
}
