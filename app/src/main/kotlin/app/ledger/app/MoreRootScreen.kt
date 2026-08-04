@file:Suppress(
    "FunctionNaming",
    "LongParameterList",
    "MatchingDeclarationName",
    "ktlint:standard:function-naming",
)

package app.ledger.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.navigation.LedgerDestinationKey

internal enum class MorePresentation { CONTENT, BADGE_UPDATES, OPERATION_IN_PROGRESS }

@Composable
internal fun MoreRootDestination(
    viewModel: AppRootViewModel,
    key: LedgerDestinationKey,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    onNavigationChanged: () -> Unit,
) {
    MoreContent(
        MorePresentation.CONTENT,
        onOperations,
        onHelp,
        onManagement = {
            viewModel.navigateP12(key, "MGT-001", emptyMap())
            onNavigationChanged()
        },
        onCurrencies = {
            viewModel.navigateP12(key, "SETG-004", emptyMap())
            onNavigationChanged()
        },
        onProjects = {
            viewModel.navigateProjectGoal("PRJ-001", null, null)
            onNavigationChanged()
        },
        onGoals = {
            viewModel.navigateProjectGoal("GOL-001", null, null)
            onNavigationChanged()
        },
        onCredit = {
            viewModel.navigateCredit("CRD-001", null)
            onNavigationChanged()
        },
        onInstallments = {
            viewModel.navigateInstallment("INS-001", null)
            onNavigationChanged()
        },
    )
}

@Composable
internal fun MoreScreen(
    presentation: MorePresentation,
    onBack: () -> Unit,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    onManagement: () -> Unit = {},
    onProjects: () -> Unit = {},
    onGoals: () -> Unit = {},
    onCredit: () -> Unit = {},
    onInstallments: () -> Unit = {},
) {
    LedgerScaffold(
        Modifier.fillMaxSize(),
        topBar = {
            LedgerTopAppBar(
                stringResource(R.string.global_more_title),
                LedgerTopAppBarVariant.BACK,
                onNavigation = onBack,
            )
        },
    ) { padding ->
        MoreContent(
            presentation = presentation,
            onOperations = onOperations,
            onHelp = onHelp,
            modifier = Modifier.padding(padding),
            onManagement = onManagement,
            onProjects = onProjects,
            onGoals = onGoals,
            onCredit = onCredit,
            onInstallments = onInstallments,
        )
    }
}

@Composable
internal fun MoreContent(
    presentation: MorePresentation,
    onOperations: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    onManagement: () -> Unit = {},
    onCurrencies: () -> Unit = {},
    onProjects: () -> Unit = {},
    onGoals: () -> Unit = {},
    onCredit: () -> Unit = {},
    onInstallments: () -> Unit = {},
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        if (presentation == MorePresentation.BADGE_UPDATES) {
            LedgerBanner(stringResource(R.string.global_badge_updates), LedgerBannerVariant.INFO)
        }
        if (presentation == MorePresentation.OPERATION_IN_PROGRESS) {
            LedgerBanner(stringResource(R.string.global_active_operation), LedgerBannerVariant.WARNING)
        }
        FeatureHubItem(stringResource(R.string.global_operations), stringResource(R.string.global_operations_explanation), onOperations)
        FeatureHubItem(stringResource(R.string.global_help), stringResource(R.string.global_help_explanation), onHelp)
        FeatureHubItem(stringResource(R.string.global_management), stringResource(R.string.global_management_explanation), onManagement)
        FeatureHubItem(stringResource(R.string.global_currencies), stringResource(R.string.global_currencies_explanation), onCurrencies)
        FeatureHubItem(stringResource(R.string.global_projects), stringResource(R.string.global_projects_explanation), onProjects)
        FeatureHubItem(stringResource(R.string.global_goals), stringResource(R.string.global_goals_explanation), onGoals)
        FeatureHubItem(stringResource(R.string.global_credit), stringResource(R.string.global_credit_explanation), onCredit)
        FeatureHubItem(stringResource(R.string.global_installments), stringResource(R.string.global_installments_explanation), onInstallments)
    }
}

@Composable
private fun FeatureHubItem(title: String, explanation: String, onClick: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerText(title, LedgerTextRole.SECTION)
            LedgerText(explanation, LedgerTextRole.SUPPORTING)
        }
    }
}
