@file:Suppress(
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "LongParameterList",
    "MatchingDeclarationName",
)

package app.ledger.core.designsystem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

public data class BatchSummaryRowUiModel(
    val stableKey: String,
    val rowNumber: String,
    val category: String,
    val amount: String,
    val accountAndCard: String,
    val merchant: String,
    val date: String,
    val project: String,
    val complexSummary: String,
    val status: String,
    val accessibilitySummary: String,
) {
    init {
        LedgerTestTags.requireStable(stableKey)
    }
}

/** Controlled phone table: row number and validation status stay fixed while summary cells scroll. */
@Composable
public fun BatchSummaryTable(
    rows: List<BatchSummaryRowUiModel>,
    headers: List<String>,
    onRowClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) = BatchSummaryTable(rows.size, { index -> rows[index] }, headers, onRowClick, modifier)

/** Indexed provider keeps large batches virtualized; callers do not prebuild 100k presentation rows. */
@Composable
public fun BatchSummaryTable(
    rowCount: Int,
    rowAt: @Composable (Int) -> BatchSummaryRowUiModel,
    headers: List<String>,
    onRowClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(rowCount >= 0)
    require(headers.size == SUMMARY_COLUMN_COUNT)
    val horizontalState = rememberScrollState()
    Column(modifier.fillMaxWidth().testTag(LedgerTestTags.BATCH_SUMMARY_TABLE)) {
        Row(Modifier.fillMaxWidth().padding(vertical = LedgerTheme.spacing.xs)) {
            BatchFixedCell(headers.first())
            Row(Modifier.weight(1f).horizontalScroll(horizontalState)) {
                headers.drop(1).dropLast(1).forEach { BatchScrollingCell(it, LedgerTextRole.LABEL) }
            }
            BatchFixedCell(headers.last())
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rowCount, key = { index -> index }) { index ->
                val row = rowAt(index)
                LedgerCard(
                    modifier = Modifier.fillMaxWidth().padding(vertical = LedgerTheme.spacing.xxs).clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = row.accessibilitySummary
                    },
                    onClick = { onRowClick(index) },
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = LedgerTheme.dimensions.listRowStandard)) {
                        BatchFixedCell(row.rowNumber)
                        Row(Modifier.weight(1f).horizontalScroll(horizontalState)) {
                            listOf(
                                row.category,
                                row.amount,
                                row.accountAndCard,
                                row.merchant,
                                row.date,
                                row.project,
                                row.complexSummary,
                            ).forEach { BatchScrollingCell(it, LedgerTextRole.BODY) }
                        }
                        BatchFixedCell(row.status)
                    }
                }
            }
        }
    }
}

@Composable
public fun BatchToolbar(
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        actions.forEach { (label, action) -> LedgerButton(label, action, variant = LedgerButtonVariant.TEXT, compact = true) }
    }
}

@Composable
public fun BatchCommitBar(
    validationLabel: String,
    commitLabel: String,
    discardLabel: String,
    onValidate: () -> Unit,
    onCommit: () -> Unit,
    onDiscard: () -> Unit,
    committing: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth().testTag(LedgerTestTags.BATCH_COMMIT),
        horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        LedgerButton(validationLabel, onValidate, variant = LedgerButtonVariant.SECONDARY, enabled = !committing)
        LedgerButton(commitLabel, onCommit, enabled = !committing)
        LedgerButton(discardLabel, onDiscard, variant = LedgerButtonVariant.TEXT, enabled = !committing)
    }
}

@Composable
private fun BatchFixedCell(value: String) {
    LedgerText(
        text = value,
        role = LedgerTextRole.LABEL,
        modifier = Modifier.widthIn(min = LedgerTheme.dimensions.listRowCompact).padding(LedgerTheme.spacing.xs),
    )
}

@Composable
private fun BatchScrollingCell(value: String, role: LedgerTextRole) {
    LedgerText(
        text = value,
        role = role,
        modifier = Modifier.widthIn(min = LedgerTheme.dimensions.cardMinHeight).padding(LedgerTheme.spacing.xs),
    )
}

private const val SUMMARY_COLUMN_COUNT = 9
