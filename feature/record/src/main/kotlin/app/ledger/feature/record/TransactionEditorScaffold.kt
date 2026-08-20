package app.ledger.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.ledger.core.designsystem.LedgerTheme

/** Shared full-height editor surface for ordinary transactions, templates and complex batch rows. */
@Composable
internal fun TransactionEditorScaffold(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        content = content,
    )
}
