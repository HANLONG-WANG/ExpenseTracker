package com.yohaku.expensetracker

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.LedgerRepository
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.SetupDraft
import com.yohaku.expensetracker.domain.reminders.ReminderPreferences
import com.yohaku.expensetracker.ui.features.AppViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TestAppPreferencesRuleTest {
    private fun withPreferences(rule: TestAppPreferencesRule, block: () -> Unit) {
        rule.apply(object : Statement() {
            override fun evaluate() = block()
        }, Description.createTestDescription(javaClass, "preferences fixture")).evaluate()
    }

    @Test fun viewModelPruningAndFakeRatesOnlyChangeTheInjectedStore() {
        val fixture = TestAppPreferencesRule()
        val unrelated = TestAppPreferencesRule()
        withPreferences(unrelated) {
            withPreferences(fixture) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val db = Room.inMemoryDatabaseBuilder(context, YohakuDatabase::class.java).build()
                try {
                    runBlocking {
                        val savedSetup = SetupDraft(step = 1, name = "Existing draft", language = "ja")
                        // Seed a separate stand-in for user preferences; never touch the real app file.
                        for (prefs in listOf(unrelated.preferences, fixture.preferences)) {
                            prefs.select("existing-book")
                            prefs.saveReminderSettings("existing-book", ReminderPreferences())
                            prefs.setForegroundLocation("existing-book", true)
                        }
                        unrelated.preferences.saveSetup(savedSetup)
                        val repository = LedgerRepository(db)
                        val rates = testReferenceRates(fixture.preferences, repository.clock)
                        val vm = AppViewModel(repository, fixture.preferences, rates)
                        try {
                            withTimeout(10_000) {
                                fixture.preferences.foregroundLocationBooks.first { it.isEmpty() }
                            }
                            assertNull(fixture.preferences.activeBook.first())
                            assertTrue(fixture.preferences.reminderSettings.first().isEmpty())
                            rates.lookup(Currency.JPY, Currency.CNY)
                            assertEquals("1", fixture.preferences.referenceRateCache().rates.single().rate)
                            fixture.preferences.setHapticsEnabled(false)
                            fixture.preferences.setPhotoSaveOriginal(true)
                            fixture.preferences.saveSetup(SetupDraft(name = "Test draft"))
                            assertSame(vm.preferences, rates.store)
                            assertEquals("existing-book", unrelated.preferences.activeBook.first())
                            assertEquals(setOf("existing-book"), unrelated.preferences.foregroundLocationBooks.first())
                            assertEquals(setOf("existing-book"), unrelated.preferences.reminderSettings.first().keys)
                            assertEquals(savedSetup, unrelated.preferences.setupDraft.first())
                            assertTrue(unrelated.preferences.hapticsEnabled.first())
                            assertFalse(unrelated.preferences.photoSaveOriginal.first())
                            assertTrue(unrelated.preferences.referenceRateCache().rates.isEmpty())
                            assertTrue(unrelated.preferences.referenceRateCache().attempts.isEmpty())
                        } finally {
                            vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
                        }
                    }
                } finally {
                    db.close()
                }
            }
        }
        assertFalse(fixture.file.parentFile!!.exists())
        assertFalse(unrelated.file.parentFile!!.exists())
    }

    @Test fun failedTestStillClosesAndDeletesItsStoreAndNextTestStartsClean() {
        val failed = TestAppPreferencesRule()
        val failure = IllegalStateException("test body failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            withPreferences(failed) {
                runBlocking {
                    failed.preferences.saveSetup(SetupDraft(step = 2, name = "Unfinished"))
                    failed.preferences.setPhotoSaveOriginal(true)
                    failed.preferences.setHapticsEnabled(false)
                    failed.preferences.select("test-book")
                }
                throw failure
            }
        })
        assertFalse(failed.file.parentFile!!.exists())
        val next = TestAppPreferencesRule()
        withPreferences(next) {
            runBlocking {
                assertNotEquals(failed.file, next.file)
                assertNull(next.preferences.activeBook.first())
                assertEquals("", next.preferences.setupDraft.first().name)
                assertEquals(0, next.preferences.setupDraft.first().step)
                assertTrue(next.preferences.hapticsEnabled.first())
                assertFalse(next.preferences.photoSaveOriginal.first())
            }
        }
    }

    @Test fun namedRecoveryStoreSurvivesCloseAndIsDeletedOnlyAfterFinalPhase() {
        val name = "rule-recovery-${UUID.randomUUID()}"
        val seed = TestAppPreferencesRule(name)
        try {
            withPreferences(seed) {
                runBlocking {
                    seed.preferences.select("recovery-book")
                    seed.preferences.saveSetup(SetupDraft(step = 2, name = "Recovery"))
                }
            }
            assertTrue(seed.file.exists())
            val resumed = TestAppPreferencesRule(name)
            withPreferences(resumed) {
                resumed.deleteAfterTest = true
                runBlocking {
                    assertEquals(seed.file, resumed.file)
                    assertEquals("recovery-book", resumed.preferences.activeBook.first())
                    assertEquals("Recovery", resumed.preferences.setupDraft.first().name)
                    assertEquals(2, resumed.preferences.setupDraft.first().step)
                }
            }
            assertFalse(seed.file.parentFile!!.exists())
        } finally {
            // Also remove the fixture if an assertion in an intermediate phase fails.
            seed.file.parentFile!!.deleteRecursively()
        }
    }
}
