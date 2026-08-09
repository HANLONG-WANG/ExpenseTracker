package app.ledger.transfer.domain

import app.ledger.core.common.DomainResult
import app.ledger.finance.domain.LocalRevision
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BackupPolicyTest {
    @Test
    fun dailyBackupRunsOnlyOnceAfterAChangedRevision() {
        val today = LocalDate.of(2026, 8, 9)
        val revision = LocalRevision.of(42).success()

        assertTrue(AutomaticBackupPolicy.shouldCreateDailyBackup(today, revision, null, null))
        assertFalse(AutomaticBackupPolicy.shouldCreateDailyBackup(today, revision, today, revision))
        assertFalse(AutomaticBackupPolicy.shouldCreateDailyBackup(today, revision, today.minusDays(1), revision))
        assertTrue(
            AutomaticBackupPolicy.shouldCreateDailyBackup(
                today,
                revision,
                today.minusDays(1),
                LocalRevision.of(41).success(),
            ),
        )
    }

    private fun <T> DomainResult<T>.success(): T = (this as DomainResult.Success).value
}
