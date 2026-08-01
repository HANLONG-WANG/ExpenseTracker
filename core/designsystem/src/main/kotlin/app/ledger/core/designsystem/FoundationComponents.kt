@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "LongParameterList", "TooManyFunctions")

package app.ledger.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
public fun LedgerScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() },
    banner: (@Composable () -> Unit)? = null,
    fixedAction: (@Composable BoxScope.() -> Unit)? = null,
    formContent: Boolean = false,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.testTag(LedgerTestTags.ROOT),
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = { LedgerSnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            val horizontal = LedgerTheme.dimensions.horizontalPadding(maxWidth)
            val maxContentWidth = if (formContent) LedgerTheme.dimensions.formMaxWidth else LedgerTheme.dimensions.contentMaxWidth
            Box(
                Modifier
                    .fillMaxSize()
                    .widthIn(max = maxContentWidth)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = horizontal)
                    .padding(bottom = if (fixedAction == null) LedgerTheme.spacing.none else LedgerTheme.dimensions.bottomActionInset),
            ) {
                content(PaddingValues())
            }
            if (banner != null) Box(Modifier.align(Alignment.TopCenter).widthIn(max = LedgerTheme.dimensions.contentMaxWidth)) { banner() }
            if (fixedAction != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(LedgerTheme.spacing.md),
                    content = fixedAction,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LedgerTopAppBar(
    title: String,
    variant: LedgerTopAppBarVariant,
    modifier: Modifier = Modifier,
    selectionCount: Int = 0,
    onNavigation: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val navigationLabel = when (variant) {
        LedgerTopAppBarVariant.BACK -> stringResource(R.string.ledger_back)
        LedgerTopAppBarVariant.MODAL_CLOSE -> stringResource(R.string.ledger_close)
        else -> null
    }
    TopAppBar(
        modifier = modifier.heightIn(min = LedgerTheme.dimensions.topAppBarHeight).testTag(LedgerTestTags.TOP_APP_BAR),
        title = {
            Text(
                text = if (variant == LedgerTopAppBarVariant.SELECTION) "$title · $selectionCount" else title,
                style = LedgerTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
                maxLines = 2,
            )
        },
        navigationIcon = {
            if (navigationLabel != null) {
                LedgerIconButton(
                    icon = if (variant == LedgerTopAppBarVariant.BACK) LedgerIcon.BACK else LedgerIcon.CLOSE,
                    contentDescription = navigationLabel,
                    onClick = onNavigation,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = LedgerTheme.colors.material.surface),
    )
}

@Immutable
public data class LedgerNavigationLabels(
    val record: String,
    val journal: String,
    val accounts: String,
    val budget: String,
    val analysis: String,
)

@Composable
public fun rememberLedgerNavigationLabels(): LedgerNavigationLabels = LedgerNavigationLabels(
    stringResource(R.string.ledger_record),
    stringResource(R.string.ledger_journal),
    stringResource(R.string.ledger_accounts),
    stringResource(R.string.ledger_budget),
    stringResource(R.string.ledger_analysis),
)

@Composable
public fun LedgerNavigationBar(
    selected: LedgerTopLevel,
    onSelected: (LedgerTopLevel) -> Unit,
    modifier: Modifier = Modifier,
    labels: LedgerNavigationLabels = rememberLedgerNavigationLabels(),
    badges: Map<LedgerTopLevel, Int> = emptyMap(),
) {
    val items = listOf(
        Triple(LedgerTopLevel.RECORD, labels.record, LedgerIcon.RECORD),
        Triple(LedgerTopLevel.JOURNAL, labels.journal, LedgerIcon.JOURNAL),
        Triple(LedgerTopLevel.ACCOUNTS, labels.accounts, LedgerIcon.ACCOUNT),
        Triple(LedgerTopLevel.BUDGET, labels.budget, LedgerIcon.BUDGET),
        Triple(LedgerTopLevel.ANALYSIS, labels.analysis, LedgerIcon.ANALYSIS),
    )
    NavigationBar(
        modifier = modifier.heightIn(min = LedgerTheme.dimensions.bottomNavigationHeight).testTag(LedgerTestTags.BOTTOM_NAVIGATION),
        containerColor = LedgerTheme.colors.material.surface,
    ) {
        items.forEach { (destination, label, icon) ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelected(destination) },
                icon = {
                    BadgedBox(
                        badge = {
                            badges[destination]?.takeIf { it > 0 }?.let { count ->
                                Badge(Modifier.semantics { contentDescription = "$label, $count" }) { Text(count.toString()) }
                            }
                        },
                    ) { LedgerIconView(icon, contentDescription = null, size = LedgerTheme.dimensions.bottomNavigationIcon) }
                },
                label = { Text(label, style = LedgerTheme.typography.labelSmall) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
public fun LedgerSaveFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    submitting: Boolean = false,
    enabled: Boolean = true,
) {
    val save = stringResource(if (submitting) R.string.ledger_saving else R.string.ledger_save)
    val icon: @Composable () -> Unit = {
        if (submitting) {
            CircularProgressIndicator(
                Modifier.size(LedgerTheme.dimensions.iconSm),
                color = LedgerTheme.colors.material.onPrimary,
                strokeWidth = LedgerTheme.dimensions.strokeSelected,
            )
        } else {
            LedgerIconView(LedgerIcon.SAVE, contentDescription = null, tint = LedgerTheme.colors.material.onPrimary)
        }
    }
    if (compact) {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier.size(LedgerTheme.dimensions.fab).testTag(LedgerTestTags.SAVE).semantics { contentDescription = save },
            containerColor = if (enabled) LedgerTheme.colors.material.primary else LedgerTheme.colors.material.surfaceContainerHighest,
        ) { icon() }
    } else {
        ExtendedFloatingActionButton(
            text = { Text(save) },
            icon = icon,
            onClick = onClick,
            modifier = modifier.heightIn(min = LedgerTheme.dimensions.fab).widthIn(min = LedgerTheme.dimensions.fabExtendedMinWidth).testTag(LedgerTestTags.SAVE),
            containerColor = if (enabled) LedgerTheme.colors.material.primary else LedgerTheme.colors.material.surfaceContainerHighest,
            contentColor = if (enabled) LedgerTheme.colors.material.onPrimary else LedgerTheme.colors.material.onSurfaceVariant,
        )
    }
}

@Composable
public fun LedgerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: LedgerButtonVariant = LedgerButtonVariant.PRIMARY,
    enabled: Boolean = true,
    compact: Boolean = false,
    leadingIcon: LedgerIcon? = null,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (leadingIcon != null) {
            LedgerIconView(
                leadingIcon,
                tint = when (variant) {
                    LedgerButtonVariant.DANGER -> LedgerTheme.colors.danger.onBase
                    LedgerButtonVariant.PRIMARY -> LedgerTheme.colors.material.onPrimary
                    else -> LedgerTheme.colors.material.onSurface
                },
            )
            Spacer(Modifier.size(LedgerTheme.spacing.xs))
        }
        Text(text, style = LedgerTheme.typography.labelLarge)
    }
    val sized = modifier.heightIn(min = if (compact) LedgerTheme.dimensions.buttonHeightCompact else LedgerTheme.dimensions.buttonHeight)
    when (variant) {
        LedgerButtonVariant.PRIMARY -> Button(onClick, sized, enabled = enabled, content = content)
        LedgerButtonVariant.SECONDARY -> OutlinedButton(onClick, sized, enabled = enabled, content = content)
        LedgerButtonVariant.TONAL -> ElevatedButton(onClick, sized, enabled = enabled, content = content)
        LedgerButtonVariant.TEXT -> TextButton(onClick, sized, enabled = enabled, content = content)
        LedgerButtonVariant.DANGER -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = LedgerTheme.colors.danger.base,
                contentColor = LedgerTheme.colors.danger.onBase,
            ),
            content = content,
        )
    }
}

@Composable
public fun LedgerIconButton(
    icon: LedgerIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(LedgerTheme.dimensions.touchTargetMin).semantics { this.contentDescription = contentDescription },
        enabled = enabled,
    ) { LedgerIconView(icon, contentDescription = null, tint = LedgerTheme.colors.material.onSurface) }
}

@Composable
public fun LedgerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    errorText: String? = null,
    required: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    val requiredText = if (required) " · ${stringResource(R.string.ledger_required)}" else ""
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = LedgerTheme.dimensions.formFieldMinHeight),
        label = { Text(label + requiredText) },
        supportingText = {
            val message = errorText ?: supportingText
            if (message != null) Text(message)
        },
        isError = errorText != null,
        enabled = enabled,
        singleLine = singleLine,
        shape = LedgerTheme.shapes.md,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
    )
}

@Composable
public fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.ledger_search),
    onClear: () -> Unit = {},
    onFilter: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = LedgerTheme.dimensions.searchFieldHeight),
        placeholder = { Text(placeholder) },
        leadingIcon = { LedgerIconView(LedgerIcon.SEARCH, contentDescription = null) },
        trailingIcon = {
            Row {
                if (value.isNotEmpty()) LedgerIconButton(LedgerIcon.CLEAR, stringResource(R.string.ledger_clear), onClear)
                if (onFilter != null) LedgerIconButton(LedgerIcon.MORE, stringResource(R.string.ledger_more), onFilter)
            }
        },
        singleLine = true,
        shape = LedgerTheme.shapes.md,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
public fun SelectorField(
    label: String,
    selectedText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: LedgerIcon? = null,
    enabled: Boolean = true,
) {
    LedgerCard(
        modifier = modifier.fillMaxWidth().heightIn(min = LedgerTheme.dimensions.formFieldMinHeight),
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        ) {
            if (icon != null) LedgerIconView(icon)
            Column(Modifier.weight(1f)) {
                Text(label, style = LedgerTheme.typography.labelMedium, color = LedgerTheme.colors.material.onSurfaceVariant)
                Text(selectedText, style = LedgerTheme.typography.bodyLarge)
                if (supportingText != null) Text(supportingText, style = LedgerTheme.typography.bodySmall)
            }
            LedgerIconView(LedgerIcon.CHEVRON)
        }
    }
}

@Composable
public fun DateTimeZoneField(
    label: String,
    localDateTime: String,
    zoneText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    zoneIsDifferent: Boolean = false,
) {
    SelectorField(
        label = label,
        selectedText = localDateTime,
        onClick = onClick,
        modifier = modifier,
        supportingText = zoneText.takeIf { zoneIsDifferent || it.isNotEmpty() },
    )
}

@Composable
public fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = LedgerTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
            if (trailingAction != null) trailingAction()
            if (onToggle != null) {
                LedgerIconButton(LedgerIcon.CHEVRON, title, onToggle, Modifier.semantics { stateDescription = if (expanded) "expanded" else "collapsed" })
            }
        }
        if (description != null) Text(description, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
        if (expanded) content()
    }
}

@Composable
public fun ValidationSummary(
    errors: List<ValidationItemUiModel>,
    onErrorClick: (ValidationItemUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (errors.isEmpty()) return
    LedgerCard(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        containerColor = LedgerTheme.colors.danger.container,
        borderColor = LedgerTheme.colors.danger.base,
    ) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Text(stringResource(R.string.ledger_error_summary, errors.size), style = LedgerTheme.typography.titleSmall, color = LedgerTheme.colors.danger.onContainer)
            errors.forEach { error ->
                TextButton(onClick = { onErrorClick(error) }) { Text(error.message, color = LedgerTheme.colors.danger.onContainer) }
            }
        }
    }
}

@Composable
public fun LedgerCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = LedgerTheme.colors.material.surfaceContainer,
    borderColor: Color = LedgerTheme.colors.material.outlineVariant,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val border = BorderStroke(LedgerTheme.dimensions.strokeStandard, borderColor)
    if (onClick == null) {
        Card(modifier, shape = LedgerTheme.shapes.lg, colors = colors, border = border, content = { content() })
    } else {
        Card(onClick, modifier, shape = LedgerTheme.shapes.lg, colors = colors, border = border, content = { content() })
    }
}

@Composable
public fun LedgerChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = LedgerTheme.typography.labelMedium) },
        modifier = modifier.heightIn(min = LedgerTheme.dimensions.chipHeight).semantics {
            stateDescription = if (selected) "selected" else "not selected"
        },
        enabled = enabled,
        border = BorderStroke(
            if (selected) LedgerTheme.dimensions.strokeSelected else LedgerTheme.dimensions.strokeStandard,
            if (selected) LedgerTheme.colors.material.primary else LedgerTheme.colors.material.outline,
        ),
    )
}

@Composable
public fun LedgerBanner(
    message: String,
    variant: LedgerBannerVariant,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val semantic = when (variant) {
        LedgerBannerVariant.INFO -> LedgerTheme.colors.info
        LedgerBannerVariant.WARNING -> LedgerTheme.colors.warning
        LedgerBannerVariant.DANGER -> LedgerTheme.colors.danger
        LedgerBannerVariant.NEUTRAL -> null
    }
    val container = semantic?.container ?: LedgerTheme.colors.material.surfaceContainerHigh
    val content = semantic?.onContainer ?: LedgerTheme.colors.material.onSurface
    LedgerCard(modifier.fillMaxWidth(), containerColor = container, borderColor = semantic?.base ?: LedgerTheme.colors.material.outline) {
        Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            LedgerIconView(
                when (variant) {
                    LedgerBannerVariant.INFO -> LedgerIcon.INFO
                    LedgerBannerVariant.WARNING -> LedgerIcon.WARNING
                    LedgerBannerVariant.DANGER -> LedgerIcon.ERROR
                    LedgerBannerVariant.NEUTRAL -> LedgerIcon.INFO
                },
                tint = content,
            )
            Text(message, Modifier.weight(1f).padding(horizontal = LedgerTheme.spacing.sm), color = content)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel, color = content) }
        }
    }
}

@Composable
public fun LedgerLoadingState(modifier: Modifier = Modifier, label: String = stringResource(R.string.ledger_loading)) {
    Column(modifier.fillMaxWidth().padding(LedgerTheme.spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(LedgerTheme.dimensions.iconLg))
        Text(label, Modifier.padding(top = LedgerTheme.spacing.sm))
    }
}

@Composable
public fun LedgerEmptyState(
    title: String,
    explanation: String,
    primaryAction: String,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(LedgerTheme.spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        LedgerIconView(LedgerIcon.INFO, size = LedgerTheme.dimensions.touchTargetMin)
        Text(title, Modifier.padding(top = LedgerTheme.spacing.sm).semantics { heading() }, style = LedgerTheme.typography.titleMedium)
        Text(explanation, Modifier.padding(vertical = LedgerTheme.spacing.sm), style = LedgerTheme.typography.bodyMedium)
        LedgerButton(primaryAction, onPrimaryAction)
        if (secondaryAction != null && onSecondaryAction != null) LedgerButton(secondaryAction, onSecondaryAction, variant = LedgerButtonVariant.TEXT)
    }
}

@Composable
public fun LedgerErrorState(
    code: UiErrorCode,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(LedgerTheme.spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        LedgerIconView(LedgerIcon.ERROR, tint = LedgerTheme.colors.danger.base, size = LedgerTheme.dimensions.touchTargetMin)
        Text(message, Modifier.padding(vertical = LedgerTheme.spacing.sm), style = LedgerTheme.typography.bodyLarge)
        Text(code.value, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
        LedgerButton(stringResource(R.string.ledger_retry), onRetry, Modifier.padding(top = LedgerTheme.spacing.sm))
    }
}

@Composable
public fun LedgerProgressIndicator(
    progress: Float?,
    modifier: Modifier = Modifier,
    accessibleText: String,
) {
    if (progress == null) {
        CircularProgressIndicator(modifier.semantics { contentDescription = accessibleText })
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth().semantics { contentDescription = accessibleText },
        )
    }
}

@Composable
public fun LedgerSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LedgerTabRow(
    selectedIndex: Int,
    labels: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(labels.isNotEmpty())
    PrimaryTabRow(selectedIndex, modifier) {
        labels.forEachIndexed { index, label ->
            Tab(selected = index == selectedIndex, onClick = { onSelected(index) }, text = { Text(label) })
        }
    }
}

@Composable
public fun LedgerDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LedgerButton(confirmLabel, onConfirm, variant = if (danger) LedgerButtonVariant.DANGER else LedgerButtonVariant.PRIMARY)
        },
        dismissButton = { LedgerButton(stringResource(R.string.ledger_cancel), onDismiss, variant = LedgerButtonVariant.SECONDARY) },
        title = { Text(title, Modifier.semantics { heading() }) },
        text = { Text(message) },
        modifier = modifier.widthIn(max = LedgerTheme.dimensions.dialogMaxWidth).semantics { paneTitle = title },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LedgerBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier, shape = LedgerTheme.shapes.xxl) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LedgerDatePickerDialog(
    state: DatePickerState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { LedgerButton(stringResource(R.string.ledger_apply), onConfirm) },
        dismissButton = { LedgerButton(stringResource(R.string.ledger_cancel), onDismiss, variant = LedgerButtonVariant.TEXT) },
        modifier = modifier,
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LedgerTimePickerDialog(
    state: TimePickerState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { LedgerButton(stringResource(R.string.ledger_apply), onConfirm) },
        dismissButton = { LedgerButton(stringResource(R.string.ledger_cancel), onDismiss, variant = LedgerButtonVariant.TEXT) },
        text = { TimePicker(state) },
        modifier = modifier,
    )
}

@Composable
public fun LedgerListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = LedgerTheme.colors.material.outlineVariant)
}
