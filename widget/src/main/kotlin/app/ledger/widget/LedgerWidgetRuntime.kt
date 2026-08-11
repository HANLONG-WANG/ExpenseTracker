package app.ledger.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.ledger.core.common.DomainResult
import app.ledger.finance.application.WidgetQuickTarget
import app.ledger.finance.application.WidgetSnapshotApplicationPort
import app.ledger.finance.application.WidgetSnapshotBundle
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

object LedgerWidgetRuntime {
    private data class Dependencies(
        val snapshots: WidgetSnapshotApplicationPort,
        val configurations: LedgerWidgetConfigurationRepository,
        val localDate: suspend (app.ledger.core.common.StableId) -> LocalDate,
    )

    private val dependencies = AtomicReference<Dependencies?>()

    fun install(
        snapshots: WidgetSnapshotApplicationPort,
        configurations: LedgerWidgetConfigurationRepository,
        localDate: suspend (app.ledger.core.common.StableId) -> LocalDate,
    ) {
        dependencies.set(Dependencies(snapshots, configurations, localDate))
    }

    suspend fun activeBookId() = dependencies.get()?.configurations?.activeBookId()

    suspend fun readConfiguration(appWidgetId: Int): LedgerWidgetConfiguration? = dependencies.get()?.configurations?.read(appWidgetId)

    suspend fun saveConfiguration(configuration: LedgerWidgetConfiguration) {
        checkNotNull(dependencies.get()).configurations.save(configuration)
    }

    suspend fun deleteConfigurations(appWidgetIds: Set<Int>) {
        dependencies.get()?.configurations?.delete(appWidgetIds)
    }

    suspend fun quickTargets(bookId: app.ledger.core.common.StableId): List<WidgetQuickTarget> = when (
        val result = dependencies.get()?.snapshots?.quickTargets(bookId)
    ) {
        is DomainResult.Success -> result.value
        else -> emptyList()
    }

    suspend fun bundle(bookId: app.ledger.core.common.StableId): WidgetSnapshotBundle? = when (
        val result = dependencies.get()?.snapshots?.read(bookId)
    ) {
        is DomainResult.Success -> result.value
        else -> null
    }

    suspend fun resolve(configuration: LedgerWidgetConfiguration): LedgerWidgetContent {
        val installed = dependencies.get() ?: return LedgerWidgetContent.Locked
        return when (val result = installed.snapshots.read(configuration.bookId)) {
            is DomainResult.Failure -> LedgerWidgetContent.Locked
            is DomainResult.Success -> LedgerWidgetPolicy.resolve(
                configuration,
                result.value,
                installed.localDate(configuration.bookId),
            )
        }
    }

    suspend fun updateAll(context: Context) {
        LedgerGlanceWidget().updateAll(context.applicationContext)
    }
}
