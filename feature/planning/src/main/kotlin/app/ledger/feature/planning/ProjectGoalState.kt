@file:Suppress("MagicNumber", "TooManyFunctions")

package app.ledger.feature.planning

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.GoalView
import app.ledger.finance.application.ProjectGoalApplicationPort
import app.ledger.finance.application.ProjectGoalSnapshot
import app.ledger.finance.application.ProjectTransactionCursor
import app.ledger.finance.application.ProjectTransactionPageRequest
import app.ledger.finance.application.ProjectTransactionView
import app.ledger.finance.application.ProjectView
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.TransactionKind
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

public enum class ProjectGoalPresentation {
    CONTENT,
    EMPTY,
    ARCHIVED_ONLY,
    CREATE,
    EDIT,
    VALIDATION_ERROR,
    ACTIVE,
    ARCHIVED,
    OVER_BUDGET,
    NO_TRANSACTIONS,
    UNDERFUNDED,
    CURRENCY_LOCKED,
    COMPLETED,
    EMPTY_HISTORY,
    EDITING,
    INSUFFICIENT_ACTUAL_BALANCE_WARNING,
    SAVING,
}

public data class ProjectDraftState(
    val name: String,
    val description: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val budgetText: String,
    val includedInMonthlyBudget: Boolean,
    val goalId: StableId?,
)

public data class GoalDraftState(
    val name: String,
    val accountId: StableId?,
    val targetText: String,
    val dueDate: LocalDate?,
    val suggestedText: String,
)

public data class ProjectGoalFeatureState(
    val snapshot: ProjectGoalSnapshot,
    val presentation: ProjectGoalPresentation,
    val projectDraft: ProjectDraftState,
    val goalDraft: GoalDraftState,
    val selectedProjectId: StableId? = null,
    val selectedGoalId: StableId? = null,
    val movementKind: GoalMovementKind = GoalMovementKind.ALLOCATE,
    val movementAmountText: String = "",
    val movementDate: LocalDate,
    val projectErrors: Set<String> = emptySet(),
    val goalErrors: Set<String> = emptySet(),
    val failureCode: String? = null,
) {
    val project: ProjectView? get() = selectedProjectId?.let { id -> snapshot.projects.singleOrNull { it.id == id } }
    val goal: GoalView? get() = selectedGoalId?.let { id -> snapshot.goals.singleOrNull { it.id == id } }
}

public sealed interface ProjectGoalLoadState {
    public data object Loading : ProjectGoalLoadState
    public data class Content(val state: ProjectGoalFeatureState) : ProjectGoalLoadState
    public data class Failure(val code: String) : ProjectGoalLoadState
}

public object ProjectGoalPolicy {
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val formatter = LocaleCurrencyFormatter(catalog)

    public fun presentationFor(
        screenId: String,
        snapshot: ProjectGoalSnapshot,
        projectId: StableId? = null,
        goalId: StableId? = null,
    ): ProjectGoalPresentation = when {
        screenId.startsWith("PRJ-") -> projectPresentation(screenId, snapshot, projectId)
        screenId.startsWith("GOL-") -> goalPresentation(screenId, snapshot, goalId)
        else -> ProjectGoalPresentation.ACTIVE
    }

    private fun projectPresentation(
        screenId: String,
        snapshot: ProjectGoalSnapshot,
        projectId: StableId?,
    ): ProjectGoalPresentation {
        val project = projectId?.let { id -> snapshot.projects.singleOrNull { it.id == id } }
        return when (screenId) {
            "PRJ-001" -> when {
                snapshot.projects.isEmpty() -> ProjectGoalPresentation.EMPTY
                snapshot.projects.all { it.status == ProjectStatus.ARCHIVED } -> ProjectGoalPresentation.ARCHIVED_ONLY
                else -> ProjectGoalPresentation.CONTENT
            }
            "PRJ-002" -> if (project == null) ProjectGoalPresentation.CREATE else ProjectGoalPresentation.EDIT
            "PRJ-003" -> when {
                project?.status == ProjectStatus.ARCHIVED -> ProjectGoalPresentation.ARCHIVED
                project?.overBudget == true -> ProjectGoalPresentation.OVER_BUDGET
                project?.transactions?.isEmpty() == true -> ProjectGoalPresentation.NO_TRANSACTIONS
                else -> ProjectGoalPresentation.ACTIVE
            }
            "PRJ-004" -> if (project?.transactions?.isEmpty() == true) ProjectGoalPresentation.NO_TRANSACTIONS else ProjectGoalPresentation.CONTENT
            "PRJ-005" -> if (project?.cashflow?.isEmpty() == true) ProjectGoalPresentation.EMPTY else ProjectGoalPresentation.CONTENT
            "PRJ-006" -> if (project?.status == ProjectStatus.ARCHIVED) ProjectGoalPresentation.ARCHIVED else ProjectGoalPresentation.ACTIVE
            else -> ProjectGoalPresentation.ACTIVE
        }
    }

    private fun goalPresentation(
        screenId: String,
        snapshot: ProjectGoalSnapshot,
        goalId: StableId?,
    ): ProjectGoalPresentation {
        val goal = goalId?.let { id -> snapshot.goals.singleOrNull { it.id == id } }
        return when (screenId) {
            "GOL-001" -> when {
                snapshot.goals.isEmpty() -> ProjectGoalPresentation.EMPTY
                snapshot.goals.any(GoalView::accountUnderfunded) -> ProjectGoalPresentation.UNDERFUNDED
                else -> ProjectGoalPresentation.CONTENT
            }
            "GOL-002" -> if (goal == null) ProjectGoalPresentation.CREATE else ProjectGoalPresentation.EDIT
            "GOL-003" -> when {
                goal?.status == GoalStatus.COMPLETED -> ProjectGoalPresentation.COMPLETED
                goal?.accountUnderfunded == true -> ProjectGoalPresentation.UNDERFUNDED
                goal?.movements?.isEmpty() == true -> ProjectGoalPresentation.EMPTY_HISTORY
                else -> ProjectGoalPresentation.ACTIVE
            }
            "GOL-004" -> ProjectGoalPresentation.EDITING
            "GOL-005" -> ProjectGoalPresentation.CONTENT
            else -> ProjectGoalPresentation.ACTIVE
        }
    }

    public fun create(
        snapshot: ProjectGoalSnapshot,
        today: LocalDate,
        projectId: StableId? = null,
        goalId: StableId? = null,
        presentation: ProjectGoalPresentation = ProjectGoalPresentation.CONTENT,
    ): ProjectGoalFeatureState {
        val project = projectId?.let { id -> snapshot.projects.singleOrNull { it.id == id } }
        val goal = goalId?.let { id -> snapshot.goals.singleOrNull { it.id == id } }
        val account = goal?.accountId ?: snapshot.accounts.firstOrNull()?.id
        return ProjectGoalFeatureState(
            snapshot,
            presentation,
            ProjectDraftState(
                project?.name.orEmpty(),
                project?.description.orEmpty(),
                project?.startDate ?: today,
                project?.endDate,
                project?.budgetBaseMinor?.let { minorText(it, snapshot.baseCurrency) }.orEmpty(),
                project?.includedInMonthlyBudget ?: true,
                project?.goalId,
            ),
            GoalDraftState(
                goal?.name.orEmpty(),
                account,
                goal?.targetAmountMinor?.let { minorText(it, goal.currency) }.orEmpty(),
                goal?.dueDate,
                goal?.suggestedMonthlyAmountMinor?.let { minorText(it, goal.currency) }.orEmpty(),
            ),
            projectId,
            goalId,
            movementDate = today,
        )
    }

    public fun projectName(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = validateProject(
        state.copy(projectDraft = state.projectDraft.copy(name = value.take(MAX_NAME)), presentation = ProjectGoalPresentation.EDIT),
    )

    public fun projectDescription(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = state.copy(
        projectDraft = state.projectDraft.copy(description = value.take(MAX_DESCRIPTION)),
    )

    public fun projectStartDate(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = runCatching { LocalDate.parse(value) }
        .fold(
            { date -> validateProject(state.copy(projectDraft = state.projectDraft.copy(startDate = date))) },
            { state.copy(projectErrors = state.projectErrors + "startDate", presentation = ProjectGoalPresentation.VALIDATION_ERROR) },
        )

    public fun projectEndDate(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = if (value.isBlank()) {
        validateProject(state.copy(projectDraft = state.projectDraft.copy(endDate = null)))
    } else {
        runCatching { LocalDate.parse(value) }.fold(
            { date -> validateProject(state.copy(projectDraft = state.projectDraft.copy(endDate = date))) },
            { state.copy(projectErrors = state.projectErrors + "endDate", presentation = ProjectGoalPresentation.VALIDATION_ERROR) },
        )
    }

    public fun projectBudget(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = validateProject(
        state.copy(projectDraft = state.projectDraft.copy(budgetText = value.take(MAX_AMOUNT_TEXT)), presentation = ProjectGoalPresentation.EDIT),
    )

    public fun toggleMonthlyBudget(state: ProjectGoalFeatureState): ProjectGoalFeatureState = state.copy(
        projectDraft = state.projectDraft.copy(includedInMonthlyBudget = !state.projectDraft.includedInMonthlyBudget),
    )

    public fun selectNextGoal(state: ProjectGoalFeatureState): ProjectGoalFeatureState {
        val values = listOf<StableId?>(null) + state.snapshot.goals.filter { it.status != GoalStatus.ARCHIVED }.map { it.id }
        return state.copy(projectDraft = state.projectDraft.copy(goalId = values[(values.indexOf(state.projectDraft.goalId).takeIf { it >= 0 } ?: 0).plus(1) % values.size]))
    }

    public fun selectGoal(state: ProjectGoalFeatureState, goalId: StableId?): ProjectGoalFeatureState {
        val allowed = goalId == null || state.snapshot.goals.any { it.id == goalId && it.status != GoalStatus.ARCHIVED }
        return if (allowed) state.copy(projectDraft = state.projectDraft.copy(goalId = goalId)) else state
    }

    public fun goalName(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = validateGoal(
        state.copy(goalDraft = state.goalDraft.copy(name = value.take(MAX_NAME)), presentation = ProjectGoalPresentation.EDIT),
    )

    public fun goalTarget(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = validateGoal(
        state.copy(goalDraft = state.goalDraft.copy(targetText = value.take(MAX_AMOUNT_TEXT)), presentation = ProjectGoalPresentation.EDIT),
    )

    public fun goalSuggested(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = validateGoal(
        state.copy(goalDraft = state.goalDraft.copy(suggestedText = value.take(MAX_AMOUNT_TEXT)), presentation = ProjectGoalPresentation.EDIT),
    )

    public fun goalDueDate(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = if (value.isBlank()) {
        validateGoal(state.copy(goalDraft = state.goalDraft.copy(dueDate = null)))
    } else {
        runCatching { LocalDate.parse(value) }.fold(
            { date -> validateGoal(state.copy(goalDraft = state.goalDraft.copy(dueDate = date))) },
            { state.copy(goalErrors = state.goalErrors + "dueDate", presentation = ProjectGoalPresentation.VALIDATION_ERROR) },
        )
    }

    public fun selectNextAccount(state: ProjectGoalFeatureState): ProjectGoalFeatureState {
        val ids = state.snapshot.accounts.map { it.id }
        return when {
            state.goal != null -> state.copy(presentation = ProjectGoalPresentation.CURRENCY_LOCKED)
            ids.isEmpty() -> state
            else -> {
                val index = ids.indexOf(state.goalDraft.accountId)
                validateGoal(
                    state.copy(
                        goalDraft = state.goalDraft.copy(
                            accountId = ids[(if (index < 0) 0 else index + 1) % ids.size],
                        ),
                    ),
                )
            }
        }
    }

    public fun selectAccount(state: ProjectGoalFeatureState, accountId: StableId): ProjectGoalFeatureState {
        if (state.goal != null || state.snapshot.accounts.none { it.id == accountId }) return state
        return validateGoal(state.copy(goalDraft = state.goalDraft.copy(accountId = accountId)))
    }

    public fun movementAmount(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState {
        val updated = state.copy(movementAmountText = value.take(MAX_AMOUNT_TEXT))
        val amount = movementMinor(updated)
        val exceedsReserved = updated.movementKind == GoalMovementKind.RELEASE && amount != null &&
            updated.goal?.let { amount > it.balanceMinor } == true
        val warning = updated.movementKind == GoalMovementKind.ALLOCATE && amount != null &&
            updated.goal?.let { goal ->
                runCatching { Math.addExact(goal.totalAccountReservedMinor, amount) > goal.actualAccountBalanceMinor }
                    .getOrDefault(true)
            } == true
        return updated.copy(
            goalErrors = if (exceedsReserved) updated.goalErrors + "movementAmount" else updated.goalErrors - "movementAmount",
            presentation = when {
                exceedsReserved -> ProjectGoalPresentation.VALIDATION_ERROR
                warning -> ProjectGoalPresentation.INSUFFICIENT_ACTUAL_BALANCE_WARNING
                else -> ProjectGoalPresentation.EDITING
            },
        )
    }

    public fun movementDate(state: ProjectGoalFeatureState, value: String): ProjectGoalFeatureState = runCatching { LocalDate.parse(value) }
        .fold(
            { date -> state.copy(movementDate = date, goalErrors = state.goalErrors - "movementDate") },
            { state.copy(goalErrors = state.goalErrors + "movementDate", presentation = ProjectGoalPresentation.VALIDATION_ERROR) },
        )

    public fun movementMinor(state: ProjectGoalFeatureState): Long? = state.goal?.currency?.let { parseMinor(state.movementAmountText, it) }?.takeIf { it > 0L }

    public fun validateProject(state: ProjectGoalFeatureState): ProjectGoalFeatureState {
        val errors = buildSet {
            if (state.projectDraft.name.isBlank()) add("name")
            if (parseMinor(state.projectDraft.budgetText, state.snapshot.baseCurrency) == null) add("budget")
            if (state.projectDraft.endDate?.isBefore(state.projectDraft.startDate) == true) add("endDate")
        }
        return state.copy(projectErrors = errors, presentation = if (errors.isEmpty()) state.presentation else ProjectGoalPresentation.VALIDATION_ERROR)
    }

    public fun validateGoal(state: ProjectGoalFeatureState): ProjectGoalFeatureState {
        val currency = state.goal?.currency ?: state.snapshot.accounts.singleOrNull { it.id == state.goalDraft.accountId }?.currency
        val errors = buildSet {
            if (state.goalDraft.name.isBlank()) add("name")
            if (state.goalDraft.accountId == null || currency == null) add("account")
            if (currency == null || parseMinor(state.goalDraft.targetText, currency)?.let { it > 0L } != true) add("target")
            if (state.goalDraft.suggestedText.isNotBlank() && (currency == null || parseMinor(state.goalDraft.suggestedText, currency)?.let { it > 0L } != true)) add("suggested")
        }
        return state.copy(goalErrors = errors, presentation = if (errors.isEmpty()) state.presentation else ProjectGoalPresentation.VALIDATION_ERROR)
    }

    public fun projectBudgetMinor(state: ProjectGoalFeatureState): Long? = parseMinor(state.projectDraft.budgetText, state.snapshot.baseCurrency)
    public fun goalTargetMinor(state: ProjectGoalFeatureState): Long? = state.goalCurrency()?.let { parseMinor(state.goalDraft.targetText, it) }
    public fun goalSuggestedMinor(state: ProjectGoalFeatureState): Long? {
        val text = state.goalDraft.suggestedText.takeIf(String::isNotBlank)
        val currency = state.goalCurrency()
        return if (text == null || currency == null) null else parseMinor(text, currency)
    }

    public fun money(
        minor: Long,
        currency: app.ledger.core.money.CurrencyCode,
        locale: Locale,
        semantic: AmountSemantic = AmountSemantic.NEUTRAL,
    ): MoneyUiModel = (
        formatter.format(MoneyFormatRequest(Money(minor, currency), locale, semantic, AmountVisibility.VISIBLE))
            as DomainResult.Success
        ).value

    private fun ProjectGoalFeatureState.goalCurrency() = goal?.currency ?: snapshot.accounts.singleOrNull { it.id == goalDraft.accountId }?.currency

    private fun parseMinor(text: String, currency: app.ledger.core.money.CurrencyCode): Long? = runCatching {
        val scale = requireNotNull(catalog.find(currency)).fractionDigits
        BigDecimal(text.trim().replace(',', '.')).movePointRight(scale).setScale(0, RoundingMode.HALF_EVEN).longValueExact().takeIf { it >= 0L }
    }.getOrNull()

    private fun minorText(minor: Long, currency: app.ledger.core.money.CurrencyCode): String {
        val scale = requireNotNull(catalog.find(currency)).fractionDigits
        return BigDecimal.valueOf(minor, scale).stripTrailingZeros().toPlainString()
    }

    private const val MAX_NAME = 80
    private const val MAX_DESCRIPTION = 500
    private const val MAX_AMOUNT_TEXT = 32
}

public class ProjectTransactionPagingSource(
    private val port: ProjectGoalApplicationPort,
    private val bookId: StableId,
    private val projectId: StableId,
    private val kind: TransactionKind? = null,
) : PagingSource<ProjectTransactionCursor, ProjectTransactionView>() {
    override suspend fun load(
        params: LoadParams<ProjectTransactionCursor>,
    ): LoadResult<ProjectTransactionCursor, ProjectTransactionView> = when (
        val result = port.projectTransactionPage(
            ProjectTransactionPageRequest(
                bookId = bookId,
                projectId = projectId,
                limit = params.loadSize.coerceIn(1, MAX_PROJECT_PAGE_SIZE),
                cursor = params.key,
                kind = kind,
            ),
        )
    ) {
        is DomainResult.Success -> LoadResult.Page(result.value.items, prevKey = null, nextKey = result.value.nextCursor)
        is DomainResult.Failure -> LoadResult.Error(ProjectTransactionQueryException(result.error.code))
    }

    override fun getRefreshKey(
        state: PagingState<ProjectTransactionCursor, ProjectTransactionView>,
    ): ProjectTransactionCursor? = state.anchorPosition
        ?.let(state::closestItemToPosition)
        ?.let { ProjectTransactionCursor(it.occurredAt.toEpochMilli(), it.id) }
}

public class ProjectTransactionQueryException(public val code: String) : IllegalStateException("Project transaction query failed: $code")

private const val MAX_PROJECT_PAGE_SIZE = 200
