@file:Suppress("MagicNumber", "MaxLineLength")

package app.ledger.feature.planning

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.BudgetAdjustmentView
import app.ledger.finance.application.BudgetCategoryLimitDraft
import app.ledger.finance.application.BudgetCategoryReference
import app.ledger.finance.application.BudgetCompositionView
import app.ledger.finance.application.BudgetProjectionReadiness
import app.ledger.finance.application.BudgetRevisionView
import app.ledger.finance.application.BudgetSnapshot
import app.ledger.finance.application.BudgetTemplateView
import app.ledger.finance.domain.BudgetAdjustmentKind
import app.ledger.finance.domain.BudgetAdjustmentScope
import app.ledger.finance.domain.DailyAvailableBudget
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.LocalRevision
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

internal object BudgetDeviceFixtures {
    data class Configuration(
        val month: YearMonth = YearMonth.of(2026, 8),
        val today: LocalDate = LocalDate.of(2026, 8, 4),
        val readiness: BudgetProjectionReadiness = BudgetProjectionReadiness.CURRENT,
        val historyCount: Int = 2,
        val withAdjustments: Boolean = true,
        val withTemplates: Boolean = true,
    )

    val root = id(10)
    val child = id(11)
    val revision = (LocalRevision.of(17) as DomainResult.Success).value
    val currency = (CurrencyCode.parse("JPY") as DomainResult.Success).value
    val actions: (BudgetScreenAction) -> Unit = {}

    fun configured(configuration: Configuration = Configuration()): BudgetSnapshot {
        val month = configuration.month
        val today = configuration.today
        val readiness = configuration.readiness
        val historyCount = configuration.historyCount
        val withAdjustments = configuration.withAdjustments
        val withTemplates = configuration.withTemplates
        val revisions = (1..historyCount).map { index ->
            BudgetRevisionView(id(100L + index), index, 10_000L + index * 1_000L, limits(), null, NOW.plusSeconds(index.toLong()))
        }.reversed()
        val current = revisions.first()
        val composition = if (readiness == BudgetProjectionReadiness.CURRENT) {
            listOf(
                BudgetCompositionView(null, null, null, 0, 11_000L, -1_000L, 500L, 7_500L, 3_000L, revision),
                BudgetCompositionView(root, "Food", null, 1, 7_000L, -500L, -200L, 5_300L, 1_000L, revision),
                BudgetCompositionView(child, "Lunch", root, 2, 5_000L, 300L, 200L, 5_700L, -200L, revision),
            )
        } else {
            emptyList()
        }
        val adjustments = if (withAdjustments) {
            listOf(
                BudgetAdjustmentView(id(200), BudgetAdjustmentScope.TOTAL, null, 500L, BudgetAdjustmentKind.INCREASE_AVAILABLE, NOW, null),
                BudgetAdjustmentView(id(201), BudgetAdjustmentScope.CATEGORY, root, -200L, BudgetAdjustmentKind.DECREASE_AVAILABLE, NOW.plusSeconds(1), null),
            )
        } else {
            emptyList()
        }
        val templates = if (withTemplates) listOf(BudgetTemplateView(id(300), "Normal month", EntityStatus.ACTIVE, current)) else emptyList()
        val daily = if (readiness == BudgetProjectionReadiness.CURRENT && month >= YearMonth.from(today)) {
            DailyAvailableBudget(month, 3_000L, 300L, 27, 100L, revision)
        } else {
            null
        }
        return BudgetSnapshot(id(1), currency, month, today, revision, readiness, id(90), current, revisions, categories(), composition, adjustments, templates, daily)
    }

    fun notConfigured(): BudgetSnapshot = configured(Configuration(historyCount = 1)).copy(
        monthId = null,
        currentRevision = null,
        revisionHistory = emptyList(),
        composition = emptyList(),
        dailyAvailable = null,
    )

    fun state(snapshot: BudgetSnapshot = configured(), presentation: BudgetPresentation = BudgetPresentation.CONFIGURED): BudgetFeatureState = BudgetPolicy.create(snapshot, presentation)

    fun constraintState(presentation: BudgetPresentation = BudgetPresentation.CONSTRAINT_ERROR): BudgetFeatureState {
        val base = BudgetPolicy.create(configured(), presentation)
        return BudgetPolicy.updateTotal(BudgetPolicy.updateCategory(base, root, "12000"), "10000")
    }

    private fun limits() = listOf(BudgetCategoryLimitDraft(root, 7_000L), BudgetCategoryLimitDraft(child, 5_000L))
    private fun categories() = listOf(
        BudgetCategoryReference(root, "Food", root, null, 1, EntityStatus.ACTIVE),
        BudgetCategoryReference(child, "Lunch", root, root, 2, EntityStatus.ACTIVE),
    )

    private val NOW = Instant.parse("2026-08-04T03:04:05Z")
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1718L, value))
}
