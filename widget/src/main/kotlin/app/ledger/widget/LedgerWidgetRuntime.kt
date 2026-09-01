package app.ledger.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.ledger.core.common.DomainResult
import app.ledger.finance.application.WidgetQuickTarget
import app.ledger.finance.application.WidgetSnapshotApplicationPort
import app.ledger.finance.application.WidgetSnapshotBundle
import app.ledger.finance.application.WidgetSnapshotRefreshApplicationPort
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

object LedgerWidgetRuntime {
    private data class Dependencies(
        val snapshots: WidgetSnapshotApplicationPort,
        val configurations: LedgerWidgetConfigurationRepository,
        val languageTag: suspend () -> String,
        val dateFormat: suspend () -> String,
        val localDate: suspend (app.ledger.core.common.StableId) -> LocalDate,
    )

    private val dependencies = AtomicReference<Dependencies?>()
    private val savedConfigurations = ConcurrentHashMap<Int, LedgerWidgetConfiguration>()

    fun install(
        snapshots: WidgetSnapshotApplicationPort,
        configurations: LedgerWidgetConfigurationRepository,
        languageTag: suspend () -> String = { Locale.getDefault().toLanguageTag() },
        dateFormat: suspend () -> String = { "DATE_FORMAT_LOCALE_DEFAULT" },
        localDate: suspend (app.ledger.core.common.StableId) -> LocalDate,
    ) {
        savedConfigurations.clear()
        dependencies.set(Dependencies(snapshots, configurations, languageTag, dateFormat, localDate))
    }

    suspend fun activeBookId() = dependencies.get()?.configurations?.activeBookId()

    suspend fun readConfiguration(appWidgetId: Int): LedgerWidgetConfiguration? {
        val persisted = dependencies.get()?.configurations?.read(appWidgetId)
        return persisted?.also { savedConfigurations[appWidgetId] = it }
            ?: savedConfigurations[appWidgetId]
    }

    suspend fun saveConfiguration(configuration: LedgerWidgetConfiguration) {
        checkNotNull(dependencies.get()).configurations.save(configuration)
        savedConfigurations[configuration.appWidgetId] = configuration
    }

    suspend fun deleteConfigurations(appWidgetIds: Set<Int>) {
        dependencies.get()?.configurations?.delete(appWidgetIds)
        appWidgetIds.forEach(savedConfigurations::remove)
    }

    suspend fun languageTag(): String = dependencies.get()?.languageTag?.invoke()
        ?.takeIf(String::isNotBlank)
        ?: Locale.getDefault().toLanguageTag()

    suspend fun dateFormat(): String = dependencies.get()?.dateFormat?.invoke()
        ?.takeIf(String::isNotBlank)
        ?: "DATE_FORMAT_LOCALE_DEFAULT"

    suspend fun quickTargets(bookId: app.ledger.core.common.StableId): List<WidgetQuickTarget> = when (
        val result = dependencies.get()?.snapshots?.quickTargets(bookId)
    ) {
        is DomainResult.Success -> result.value
        else -> emptyList()
    }

    suspend fun bundle(bookId: app.ledger.core.common.StableId): WidgetSnapshotBundle? {
        val installed = dependencies.get() ?: return null
        (installed.snapshots as? WidgetSnapshotRefreshApplicationPort)?.refreshIfStale(bookId, installed.localDate(bookId))
        return when (val result = installed.snapshots.read(bookId)) {
        is DomainResult.Success -> result.value
        else -> null
        }
    }

    suspend fun resolve(configuration: LedgerWidgetConfiguration): LedgerWidgetContent {
        val installed = dependencies.get() ?: return LedgerWidgetContent.Locked
        (installed.snapshots as? WidgetSnapshotRefreshApplicationPort)?.refreshIfStale(
            configuration.bookId,
            installed.localDate(configuration.bookId),
        )
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
