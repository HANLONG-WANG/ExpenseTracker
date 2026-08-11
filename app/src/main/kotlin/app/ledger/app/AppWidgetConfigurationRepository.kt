package app.ledger.app

import app.ledger.app.settings.WidgetConfigurationProto
import app.ledger.app.settings.WidgetQuickDirectionProto
import app.ledger.app.settings.WidgetQuickTargetKindProto
import app.ledger.app.settings.WidgetTypeProto
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.finance.application.WidgetQuickDirection
import app.ledger.finance.application.WidgetQuickTargetKind
import app.ledger.widget.LedgerWidgetConfiguration
import app.ledger.widget.LedgerWidgetConfigurationRepository
import app.ledger.widget.LedgerWidgetType
import com.google.protobuf.ByteString

internal class AppWidgetConfigurationRepository(
    private val settings: AppSettingsRepository,
) : LedgerWidgetConfigurationRepository {
    override suspend fun activeBookId(): StableId? = settings.current().bookId.toByteArray()
        .takeIf { it.size == StableId.BYTE_COUNT }
        ?.let(StableId::fromBytes)
        ?.getOrNull()

    override suspend fun read(appWidgetId: Int): LedgerWidgetConfiguration? = settings.current()
        .widgetConfigurationsList
        .firstOrNull { it.appWidgetId == appWidgetId }
        ?.toDomain()

    override suspend fun save(configuration: LedgerWidgetConfiguration) {
        settings.update { builder ->
            val retained = builder.widgetConfigurationsList.filterNot { it.appWidgetId == configuration.appWidgetId }
            builder.clearWidgetConfigurations()
            builder.addAllWidgetConfigurations(retained + configuration.toProto())
        }
    }

    override suspend fun delete(appWidgetIds: Set<Int>) {
        if (appWidgetIds.isEmpty()) return
        settings.update { builder ->
            val retained = builder.widgetConfigurationsList.filterNot { it.appWidgetId in appWidgetIds }
            builder.clearWidgetConfigurations()
            builder.addAllWidgetConfigurations(retained)
        }
    }
}

private fun LedgerWidgetConfiguration.toProto(): WidgetConfigurationProto = WidgetConfigurationProto.newBuilder()
    .setAppWidgetId(appWidgetId)
    .setBookId(ByteString.copyFrom(bookId.bytes))
    .setType(type.toProto())
    .setRevealAmounts(revealAmounts)
    .also { builder ->
        selectedId?.let { builder.selectedId = ByteString.copyFrom(it.bytes) }
        quickTargetKind?.let { builder.quickTargetKind = it.toProto() }
        quickDirection?.let { builder.quickDirection = it.toProto() }
    }
    .build()

private fun WidgetConfigurationProto.toDomain(): LedgerWidgetConfiguration? = runCatching {
    val parsedBook = requireNotNull(StableId.fromBytes(bookId.toByteArray()).getOrNull())
    val selected = selectedId.toByteArray().takeIf { it.size == StableId.BYTE_COUNT }
        ?.let(StableId::fromBytes)?.getOrNull()
    val parsedType = requireNotNull(type.toDomain())
    require(parsedType !in SELECTION_TYPES || selected != null)
    LedgerWidgetConfiguration(
        appWidgetId = appWidgetId,
        bookId = parsedBook,
        type = parsedType,
        selectedId = selected,
        quickTargetKind = quickTargetKind.toDomain().takeIf { parsedType == LedgerWidgetType.QUICK_ENTRY },
        quickDirection = quickDirection.toDomain().takeIf { parsedType == LedgerWidgetType.QUICK_ENTRY },
        revealAmounts = revealAmounts,
    )
}.getOrNull()

private fun LedgerWidgetType.toProto(): WidgetTypeProto = WidgetTypeProto.entries[ordinal]

private fun WidgetTypeProto.toDomain(): LedgerWidgetType? = if (this == WidgetTypeProto.UNRECOGNIZED) {
    null
} else {
    LedgerWidgetType.entries.getOrNull(number)
}

private fun WidgetQuickTargetKind.toProto(): WidgetQuickTargetKindProto = when (this) {
    WidgetQuickTargetKind.CATEGORY -> WidgetQuickTargetKindProto.WIDGET_QUICK_TARGET_CATEGORY
    WidgetQuickTargetKind.TEMPLATE -> WidgetQuickTargetKindProto.WIDGET_QUICK_TARGET_TEMPLATE
}

private fun WidgetQuickTargetKindProto.toDomain(): WidgetQuickTargetKind = if (
    this == WidgetQuickTargetKindProto.WIDGET_QUICK_TARGET_TEMPLATE
) {
    WidgetQuickTargetKind.TEMPLATE
} else {
    WidgetQuickTargetKind.CATEGORY
}

private fun WidgetQuickDirection.toProto(): WidgetQuickDirectionProto = when (this) {
    WidgetQuickDirection.EXPENSE -> WidgetQuickDirectionProto.WIDGET_QUICK_DIRECTION_EXPENSE
    WidgetQuickDirection.INCOME -> WidgetQuickDirectionProto.WIDGET_QUICK_DIRECTION_INCOME
}

private fun WidgetQuickDirectionProto.toDomain(): WidgetQuickDirection = if (
    this == WidgetQuickDirectionProto.WIDGET_QUICK_DIRECTION_INCOME
) {
    WidgetQuickDirection.INCOME
} else {
    WidgetQuickDirection.EXPENSE
}

private val SELECTION_TYPES = setOf(
    LedgerWidgetType.QUICK_ENTRY,
    LedgerWidgetType.ACCOUNT,
    LedgerWidgetType.CREDIT_CARD,
    LedgerWidgetType.GOAL,
)
