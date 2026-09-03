@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "LongParameterList", "TooManyFunctions")

package app.ledger.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Stable
public class LedgerSnackbarController internal constructor(
    internal val hostState: SnackbarHostState,
) {
    public suspend fun show(message: String) {
        hostState.showSnackbar(message)
    }
}

@Composable
public fun rememberLedgerSnackbarController(): LedgerSnackbarController = androidx.compose.runtime.remember { LedgerSnackbarController(SnackbarHostState()) }

private val LocalLedgerScaffoldDepth = staticCompositionLocalOf { 0 }
private val LocalLedgerContentHasHorizontalPadding = staticCompositionLocalOf { false }

@Composable
public fun LedgerScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarController: LedgerSnackbarController = rememberLedgerSnackbarController(),
    banner: (@Composable () -> Unit)? = null,
    fixedAction: (@Composable BoxScope.() -> Unit)? = null,
    fixedActionOverlaysContent: Boolean = false,
    formContent: Boolean = false,
    contentHorizontalPadding: Boolean = true,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val depth = LocalLedgerScaffoldDepth.current
    val parentHasHorizontalPadding = LocalLedgerContentHasHorizontalPadding.current
    val applyHorizontalPadding = contentHorizontalPadding && !parentHasHorizontalPadding
    CompositionLocalProvider(
        LocalLedgerScaffoldDepth provides depth + 1,
        LocalLedgerContentHasHorizontalPadding provides (parentHasHorizontalPadding || applyHorizontalPadding),
    ) {
        if (depth == 0) {
            Scaffold(
                modifier = modifier.testTag(LedgerTestTags.ROOT).semantics {
                    isTraversalGroup = true
                    testTagsAsResourceId = true
                },
                topBar = topBar,
                bottomBar = bottomBar,
                snackbarHost = { LedgerSnackbarHost(snackbarController.hostState) },
            ) { scaffoldPadding ->
                LedgerScaffoldBody(
                    scaffoldPadding = scaffoldPadding,
                    formContent = formContent,
                    applyHorizontalPadding = applyHorizontalPadding,
                    banner = banner,
                    fixedAction = fixedAction,
                    fixedActionOverlaysContent = fixedActionOverlaysContent,
                    content = content,
                )
            }
        } else {
            Column(
                modifier.fillMaxSize().testTag(LedgerTestTags.ROOT).semantics {
                    isTraversalGroup = true
                    testTagsAsResourceId = true
                },
            ) {
                topBar()
                LedgerScaffoldBody(
                    scaffoldPadding = PaddingValues(),
                    formContent = formContent,
                    applyHorizontalPadding = applyHorizontalPadding,
                    banner = banner,
                    fixedAction = fixedAction,
                    fixedActionOverlaysContent = fixedActionOverlaysContent,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    content = content,
                )
                bottomBar()
            }
        }
    }
}

@Composable
private fun LedgerScaffoldBody(
    scaffoldPadding: PaddingValues,
    formContent: Boolean,
    applyHorizontalPadding: Boolean,
    banner: (@Composable () -> Unit)?,
    fixedAction: (@Composable BoxScope.() -> Unit)?,
    fixedActionOverlaysContent: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().padding(scaffoldPadding)) {
        val horizontal = if (applyHorizontalPadding) LedgerTheme.dimensions.horizontalPadding(maxWidth) else LedgerTheme.spacing.none
        val maxContentWidth = if (formContent) LedgerTheme.dimensions.formMaxWidth else LedgerTheme.dimensions.contentMaxWidth
        Box(
            Modifier
                .fillMaxSize()
                .widthIn(max = maxContentWidth)
                .align(Alignment.TopCenter)
                .testTag(LedgerTestTags.CONTENT)
                .semantics { traversalIndex = CONTENT_TRAVERSAL_INDEX }
                .padding(horizontal = horizontal)
                .padding(
                    bottom = if (fixedAction == null || fixedActionOverlaysContent) {
                        LedgerTheme.spacing.none
                    } else {
                        LedgerTheme.dimensions.bottomActionInset
                    },
                ),
        ) {
            content(PaddingValues())
        }
        if (banner != null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = LedgerTheme.dimensions.contentMaxWidth)
                    .semantics { traversalIndex = BANNER_TRAVERSAL_INDEX },
            ) { banner() }
        }
        if (fixedAction != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(LedgerTheme.spacing.md)
                    .testTag(LedgerTestTags.FIXED_ACTION)
                    .semantics { traversalIndex = FIXED_ACTION_TRAVERSAL_INDEX },
                content = fixedAction,
            )
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
        modifier = modifier
            .testTag(LedgerTestTags.TOP_APP_BAR)
            .semantics { traversalIndex = TOP_BAR_TRAVERSAL_INDEX },
        title = {
            Text(
                text = if (variant == LedgerTopAppBarVariant.SELECTION) "$title · $selectionCount" else title,
                style = LedgerTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
        modifier = modifier
            .heightIn(min = LedgerTheme.dimensions.bottomNavigationHeight)
            .testTag(LedgerTestTags.BOTTOM_NAVIGATION)
            .semantics {
                traversalIndex = BOTTOM_NAVIGATION_TRAVERSAL_INDEX
                testTagsAsResourceId = true
            },
        containerColor = LedgerTheme.colors.material.surface,
    ) {
        items.forEach { (destination, label, icon) ->
            NavigationBarItem(
                modifier = Modifier.testTag(destination.navigationTestTag()),
                selected = destination == selected,
                onClick = { onSelected(destination) },
                icon = {
                    BadgedBox(
                        badge = {
                            badges[destination]?.takeIf { it > 0 }?.let { count ->
                                Badge(Modifier.clearAndSetSemantics { text = AnnotatedString("$label, $count") }) { Text(count.toString()) }
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

private fun LedgerTopLevel.navigationTestTag(): String = when (this) {
    LedgerTopLevel.RECORD -> LedgerTestTags.NAVIGATION_RECORD
    LedgerTopLevel.JOURNAL -> LedgerTestTags.NAVIGATION_JOURNAL
    LedgerTopLevel.ACCOUNTS -> LedgerTestTags.NAVIGATION_ACCOUNTS
    LedgerTopLevel.BUDGET -> LedgerTestTags.NAVIGATION_BUDGET
    LedgerTopLevel.ANALYSIS -> LedgerTestTags.NAVIGATION_ANALYSIS
}

@Composable
public fun LedgerSaveFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    submitting: Boolean = false,
    enabled: Boolean = true,
) {
    val effectiveEnabled = enabled && !submitting
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
            onClick = if (effectiveEnabled) onClick else ({}),
            modifier = modifier.size(LedgerTheme.dimensions.fab).testTag(LedgerTestTags.SAVE).semantics {
                contentDescription = save
                if (!effectiveEnabled) disabled()
            },
            containerColor = if (effectiveEnabled) LedgerTheme.colors.material.primary else LedgerTheme.colors.material.surfaceContainerHighest,
        ) { icon() }
    } else {
        ExtendedFloatingActionButton(
            text = { Text(save) },
            icon = icon,
            onClick = if (effectiveEnabled) onClick else ({}),
            modifier = modifier
                .heightIn(min = LedgerTheme.dimensions.fab)
                .widthIn(min = LedgerTheme.dimensions.fabExtendedMinWidth)
                .testTag(LedgerTestTags.SAVE)
                .semantics {
                    contentDescription = save
                    if (!effectiveEnabled) disabled()
                },
            containerColor = if (effectiveEnabled) LedgerTheme.colors.material.primary else LedgerTheme.colors.material.surfaceContainerHighest,
            contentColor = if (effectiveEnabled) LedgerTheme.colors.material.onPrimary else LedgerTheme.colors.material.onSurfaceVariant,
        )
    }
}

private val LEDGER_CURRENCY_SYMBOL_VALUE = Regex(
    """(?iu)(?:[¥￥$€£₹₽₩]\s*[-+]?\d|[-+]?\d[\d\s,.'’]*\s*(?:元|円))""",
)
private val LEDGER_CURRENCY_CODE_VALUE = Regex(
    """(?iu)(?:\b([A-Z]{3})\s*[-+]?\d|[-+]?\d[\d\s,.'’]*\s*([A-Z]{3})\b)""",
)
private val LEDGER_PERCENT_VALUE = Regex("""[-+]?\d+(?:[.,]\d+)?\s*%""")
private val LEDGER_KNOWN_CURRENCY_CODES: Set<String> = java.util.Currency.getAvailableCurrencies().mapTo(linkedSetOf()) { it.currencyCode }

internal fun String.containsLedgerFinancialValue(): Boolean = LEDGER_CURRENCY_SYMBOL_VALUE.containsMatchIn(this) ||
    LEDGER_PERCENT_VALUE.containsMatchIn(this) ||
    LEDGER_CURRENCY_CODE_VALUE.findAll(this).any { match ->
        match.groupValues.drop(1).firstOrNull(String::isNotBlank)?.uppercase() in LEDGER_KNOWN_CURRENCY_CODES
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
    val privacyHidden = !LocalLedgerAmountsVisible.current && text.containsLedgerFinancialValue()
    val displayedText = if (privacyHidden) "••••" else text
    val hiddenAmountText = stringResource(R.string.ledger_amount_hidden)
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
        Text(
            displayedText,
            modifier = if (privacyHidden) Modifier.clearAndSetSemantics { this.text = AnnotatedString(hiddenAmountText) } else Modifier,
            style = LedgerTheme.typography.labelLarge,
        )
    }
    val sized = modifier.heightIn(min = if (compact) LedgerTheme.dimensions.buttonHeightCompact else LedgerTheme.dimensions.buttonHeight)
    when (variant) {
        LedgerButtonVariant.PRIMARY -> Button(onClick, sized, enabled = enabled, content = content)
        LedgerButtonVariant.SECONDARY -> OutlinedButton(onClick, sized, enabled = enabled, content = content)
        LedgerButtonVariant.TONAL -> FilledTonalButton(onClick, sized, enabled = enabled, content = content)
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
    onImeAction: (() -> Unit)? = null,
    sensitive: Boolean = false,
    hideValueFromSemantics: Boolean = false,
) {
    val reportFormChange = LocalLedgerFormChangeReporter.current
    val requiredText = if (required) " · ${stringResource(R.string.ledger_required)}" else ""
    val protectedValueState = stringResource(
        if (value.isEmpty()) R.string.ledger_sensitive_value_empty else R.string.ledger_sensitive_value_entered,
    )
    val focusManager = LocalFocusManager.current
    val effectiveImeAction = onImeAction ?: {
        when (imeAction) {
            ImeAction.Next -> focusManager.moveFocus(FocusDirection.Next)
            ImeAction.Previous -> focusManager.moveFocus(FocusDirection.Previous)
            else -> focusManager.clearFocus()
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = { changed ->
            if (changed != value) reportFormChange()
            onValueChange(changed)
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LedgerTheme.dimensions.formFieldMinHeight)
            .semantics {
                contentDescription = label + requiredText
                if (sensitive) password()
                if (sensitive || hideValueFromSemantics) {
                    contentDescription = label
                    inputText = AnnotatedString("")
                    editableText = AnnotatedString("")
                    stateDescription = protectedValueState
                }
            },
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
        keyboardActions = KeyboardActions(onAny = { effectiveImeAction() }),
        visualTransformation = if (sensitive) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
public fun LedgerTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    errorText: String? = null,
    required: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    val reportFormChange = LocalLedgerFormChangeReporter.current
    val requiredText = if (required) " · ${stringResource(R.string.ledger_required)}" else ""
    val focusManager = LocalFocusManager.current
    val effectiveImeAction = onImeAction ?: {
        when (imeAction) {
            ImeAction.Next -> focusManager.moveFocus(FocusDirection.Next)
            ImeAction.Previous -> focusManager.moveFocus(FocusDirection.Previous)
            else -> focusManager.clearFocus()
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = { changed ->
            if (changed.text != value.text) reportFormChange()
            onValueChange(changed)
        },
        modifier = modifier.fillMaxWidth().heightIn(min = LedgerTheme.dimensions.formFieldMinHeight).semantics {
            contentDescription = label + requiredText
        },
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
        keyboardActions = KeyboardActions(onAny = { effectiveImeAction() }),
    )
}

@Composable
public fun LedgerChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val reportFormChange = LocalLedgerFormChangeReporter.current
    val localizedSelectionState = if (selected) {
        stringResource(R.string.ledger_selected)
    } else {
        stringResource(R.string.ledger_not_selected)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LedgerTheme.dimensions.touchTargetMin)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                haptic.performLedgerHaptic(LedgerHaptic.SELECTION)
                if (!selected) reportFormChange()
                onClick()
            }
            .semantics {
                contentDescription = "$title, $localizedSelectionState"
                role = Role.RadioButton
                this.selected = selected
                stateDescription = localizedSelectionState
                if (!enabled) disabled()
            }
            .padding(vertical = LedgerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = LedgerTheme.typography.bodyLarge)
            if (supportingText != null) {
                Text(supportingText, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
            }
        }
    }
}

@Composable
public fun LedgerCheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val reportFormChange = LocalLedgerFormChangeReporter.current
    val localizedSelectionState = if (checked) {
        stringResource(R.string.ledger_selected)
    } else {
        stringResource(R.string.ledger_not_selected)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LedgerTheme.dimensions.touchTargetMin)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { value ->
                    haptic.performLedgerHaptic(if (value) LedgerHaptic.TOGGLE_ON else LedgerHaptic.TOGGLE_OFF)
                    reportFormChange()
                    onCheckedChange(value)
                },
            )
            .semantics {
                contentDescription = "$title, $localizedSelectionState"
                role = Role.Checkbox
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                stateDescription = localizedSelectionState
                if (!enabled) disabled()
            }
            .padding(vertical = LedgerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = LedgerTheme.typography.bodyLarge)
            if (supportingText != null) {
                Text(supportingText, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
            }
        }
    }
}

@Composable
public fun LedgerToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val localizedToggleState = stringResource(if (checked) R.string.ledger_on else R.string.ledger_off)
    val haptic = LocalHapticFeedback.current
    val reportFormChange = LocalLedgerFormChangeReporter.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LedgerTheme.dimensions.touchTargetMin)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { value ->
                    haptic.performLedgerHaptic(if (value) LedgerHaptic.TOGGLE_ON else LedgerHaptic.TOGGLE_OFF)
                    reportFormChange()
                    onCheckedChange(value)
                },
            )
            .semantics {
                contentDescription = "$title, $localizedToggleState"
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                stateDescription = localizedToggleState
                if (!enabled) disabled()
            }
            .padding(vertical = LedgerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LedgerTheme.typography.bodyLarge)
            if (supportingText != null) {
                Text(supportingText, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
public fun LedgerText(
    text: String,
    role: LedgerTextRole,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val privacyHidden = !LocalLedgerAmountsVisible.current && text.containsLedgerFinancialValue()
    val displayedText = if (privacyHidden) "••••" else text
    val hiddenAmountText = stringResource(R.string.ledger_amount_hidden)
    val style = when (role) {
        LedgerTextRole.DISPLAY -> LedgerTheme.typography.titleLarge
        LedgerTextRole.TITLE -> LedgerTheme.typography.titleLarge
        LedgerTextRole.SECTION -> LedgerTheme.typography.titleSmall
        LedgerTextRole.BODY -> LedgerTheme.typography.bodyLarge
        LedgerTextRole.SUPPORTING -> LedgerTheme.typography.bodySmall
        LedgerTextRole.LABEL -> LedgerTheme.typography.labelMedium
    }
    val color = if (role == LedgerTextRole.SUPPORTING) {
        LedgerTheme.colors.material.onSurfaceVariant
    } else {
        LedgerTheme.colors.material.onSurface
    }
    val accessibleText = if (privacyHidden) hiddenAmountText else text
    Text(
        text = displayedText,
        modifier = modifier.clearAndSetSemantics {
            this.text = AnnotatedString(accessibleText)
            if (role in setOf(LedgerTextRole.DISPLAY, LedgerTextRole.TITLE, LedgerTextRole.SECTION)) heading()
        },
        style = style,
        color = color,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
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
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focusRequester).fillMaxWidth().heightIn(min = LedgerTheme.dimensions.searchFieldHeight),
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
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
        onClick = if (enabled) {
            onClick
        } else {
            null
        },
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
    val localizedExpansionState = stringResource(if (expanded) R.string.ledger_expanded else R.string.ledger_collapsed)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = LedgerTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
            if (trailingAction != null) trailingAction()
            if (onToggle != null) {
                LedgerIconButton(LedgerIcon.CHEVRON, title, onToggle, Modifier.semantics { stateDescription = localizedExpansionState })
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
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(errors) {
        haptic.performLedgerHaptic(LedgerHaptic.ERROR)
        focusRequester.requestFocus()
    }
    LedgerCard(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable()
            .semantics { liveRegion = LiveRegionMode.Assertive },
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

public enum class LedgerCardVariant { STANDARD, EMPHASIZED, DANGER }

@Composable
public fun LedgerCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    variant: LedgerCardVariant = LedgerCardVariant.STANDARD,
    content: @Composable () -> Unit,
) {
    val (container, border) = when (variant) {
        LedgerCardVariant.STANDARD -> LedgerTheme.colors.material.surfaceContainer to LedgerTheme.colors.material.outlineVariant
        LedgerCardVariant.EMPHASIZED -> LedgerTheme.colors.material.primaryContainer to LedgerTheme.colors.material.primary
        LedgerCardVariant.DANGER -> LedgerTheme.colors.danger.container to LedgerTheme.colors.danger.base
    }
    LedgerCard(modifier, onClick, container, border, content)
}

@Composable
internal fun LedgerCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color,
    borderColor: Color,
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
    val haptic = LocalHapticFeedback.current
    val privacyHidden = !LocalLedgerAmountsVisible.current && label.containsLedgerFinancialValue()
    val displayedLabel = if (privacyHidden) "••••" else label
    val hiddenAmountText = stringResource(R.string.ledger_amount_hidden)
    val localizedSelectionState = if (selected) {
        stringResource(R.string.ledger_selected)
    } else {
        stringResource(R.string.ledger_not_selected)
    }
    AssistChip(
        onClick = {
            haptic.performLedgerHaptic(LedgerHaptic.SELECTION)
            onClick()
        },
        label = {
            Text(
                displayedLabel,
                modifier = if (privacyHidden) Modifier.clearAndSetSemantics { text = AnnotatedString(hiddenAmountText) } else Modifier,
                style = LedgerTheme.typography.labelMedium,
            )
        },
        modifier = modifier.heightIn(min = LedgerTheme.dimensions.touchTargetMin).semantics {
            role = Role.Checkbox
            this.selected = selected
            stateDescription = localizedSelectionState
        },
        enabled = enabled,
        border = BorderStroke(
            if (selected) LedgerTheme.dimensions.strokeSelected else LedgerTheme.dimensions.strokeStandard,
            if (selected) LedgerTheme.colors.material.primary else LedgerTheme.colors.material.outline,
        ),
    )
}

/** Presents the complete bounded choice set while adapting legacy cycle callbacks at the UI boundary. */
@Composable
public fun LedgerCycleChoiceSelector(
    label: String,
    selectedIndex: Int,
    options: List<String>,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    require(options.isNotEmpty())
    val safeIndex = selectedIndex.coerceIn(0, options.lastIndex)
    var expanded by remember(label) { mutableStateOf(false) }
    var query by remember(label) { mutableStateOf("") }
    SelectorField(label, options[safeIndex], { expanded = true }, modifier, supportingText = supportingText, enabled = enabled)
    if (expanded) {
        val select: (Int) -> Unit = { index ->
            repeat(Math.floorMod(index - safeIndex, options.size)) { onCycle() }
            expanded = false
            query = ""
        }
        if (options.size > 8) {
            LedgerModalDialog(label, onDismiss = { expanded = false }) {
                SearchField(query, { query = it }, onClear = { query = "" })
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(options.indices.filter { query.isBlank() || options[it].contains(query, ignoreCase = true) }) { index ->
                        LedgerChoiceRow(options[index], selected = index == safeIndex, onClick = { select(index) })
                    }
                }
            }
        } else {
            LedgerBottomSheet(onDismiss = { expanded = false }) {
                LedgerText(label, LedgerTextRole.SECTION, Modifier.padding(horizontal = LedgerTheme.spacing.md))
                options.forEachIndexed { index, option ->
                    LedgerChoiceRow(option, selected = index == safeIndex, onClick = { select(index) })
                }
            }
        }
    }
}

/** Complete bounded choice selector with searchable full-size modal for growing candidate sets. */
@Composable
public fun LedgerChoiceSelector(
    label: String,
    selectedIndex: Int,
    options: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    require(options.isNotEmpty())
    val safeIndex = selectedIndex.takeIf { it in options.indices }
    var expanded by remember(label) { mutableStateOf(false) }
    var query by remember(label) { mutableStateOf("") }
    val select: (Int) -> Unit = { index ->
        onSelected(index)
        expanded = false
        query = ""
    }
    SelectorField(label, safeIndex?.let(options::get) ?: placeholder ?: options.first(), { expanded = true }, modifier, supportingText = supportingText, enabled = enabled)
    if (expanded && options.size > 8) {
        LedgerModalDialog(label, onDismiss = { expanded = false }) {
            SearchField(query, { query = it }, onClear = { query = "" })
            LazyColumn(Modifier.fillMaxWidth()) {
                items(options.indices.filter { query.isBlank() || options[it].contains(query, ignoreCase = true) }) { index ->
                    LedgerChoiceRow(options[index], selected = index == safeIndex, onClick = { select(index) })
                }
            }
        }
    } else if (expanded) {
        LedgerBottomSheet(onDismiss = { expanded = false }) {
            LedgerText(label, LedgerTextRole.SECTION, Modifier.padding(horizontal = LedgerTheme.spacing.md))
            options.forEachIndexed { index, option ->
                LedgerChoiceRow(option, selected = index == safeIndex, onClick = { select(index) })
            }
        }
    }
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
    val privacyHidden = !LocalLedgerAmountsVisible.current && message.containsLedgerFinancialValue()
    val displayedMessage = if (privacyHidden) "••••" else message
    val hiddenAmountText = stringResource(R.string.ledger_amount_hidden)
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
            Text(
                displayedMessage,
                Modifier.weight(1f).padding(horizontal = LedgerTheme.spacing.sm).then(
                    if (privacyHidden) Modifier.clearAndSetSemantics { text = AnnotatedString(hiddenAmountText) } else Modifier,
                ),
                color = content,
            )
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
        Text(stringResource(R.string.ledger_error_what_happened), Modifier.padding(top = LedgerTheme.spacing.sm).semantics { heading() }, style = LedgerTheme.typography.titleSmall)
        Text(message, Modifier.padding(bottom = LedgerTheme.spacing.sm), style = LedgerTheme.typography.bodyLarge)
        Text(stringResource(R.string.ledger_error_write_status), Modifier.semantics { heading() }, style = LedgerTheme.typography.titleSmall)
        Text(stringResource(R.string.ledger_error_existing_data_unchanged), Modifier.padding(bottom = LedgerTheme.spacing.sm), style = LedgerTheme.typography.bodyMedium)
        Text(stringResource(R.string.ledger_error_next_step), Modifier.semantics { heading() }, style = LedgerTheme.typography.titleSmall)
        Text(stringResource(R.string.ledger_error_retry_next_step), style = LedgerTheme.typography.bodyMedium)
        Text(code.value, Modifier.padding(top = LedgerTheme.spacing.xs), style = LedgerTheme.typography.bodySmall)
        LedgerButton(stringResource(R.string.ledger_retry), onRetry, Modifier.padding(top = LedgerTheme.spacing.sm))
    }
}

@Composable
public fun LedgerErrorState(
    code: UiErrorCode,
    message: UiText.Resource,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LedgerErrorState(code, message.resolve(), onRetry, modifier)
}

@Composable
public fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> stringResource(resourceId, *arguments.toTypedArray())
}

@Composable
public fun LedgerProgressIndicator(
    progress: Float?,
    modifier: Modifier = Modifier,
    accessibleText: String,
) {
    val privacyHidden = !LocalLedgerAmountsVisible.current && accessibleText.containsLedgerFinancialValue()
    if (privacyHidden) {
        val hiddenAmountText = stringResource(R.string.ledger_amount_hidden)
        Text(
            "••••",
            modifier.clearAndSetSemantics { text = AnnotatedString(hiddenAmountText) },
            style = LedgerTheme.typography.amountMedium,
        )
    } else if (progress == null) {
        CircularProgressIndicator(modifier.clearAndSetSemantics { text = AnnotatedString(accessibleText) })
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth().clearAndSetSemantics { text = AnnotatedString(accessibleText) },
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
    labelMaxLines: Int = 1,
) {
    require(labels.isNotEmpty())
    require(labelMaxLines > 0)
    val tabs: @Composable () -> Unit = {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelected(index) },
                text = { Text(label, maxLines = labelMaxLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) },
            )
        }
    }
    if (labels.size > 4) {
        PrimaryScrollableTabRow(selectedIndex, modifier, tabs = tabs)
    } else {
        PrimaryTabRow(selectedIndex, modifier, tabs = tabs)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun LedgerDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val confirmFocusRequester = remember { FocusRequester() }
    val dismissFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (danger) dismissFocusRequester.requestFocus() else confirmFocusRequester.requestFocus()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LedgerButton(
                confirmLabel,
                onConfirm,
                Modifier.focusRequester(confirmFocusRequester),
                variant = if (danger) LedgerButtonVariant.DANGER else LedgerButtonVariant.PRIMARY,
                enabled = confirmEnabled,
            )
        },
        dismissButton = {
            LedgerButton(
                dismissLabel ?: stringResource(R.string.ledger_cancel),
                onDismiss,
                Modifier.focusRequester(dismissFocusRequester),
                variant = LedgerButtonVariant.SECONDARY,
            )
        },
        title = { Text(title, Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                if (message != null) Text(message)
                if (content != null) content()
            }
        },
        modifier = modifier
            .widthIn(max = LedgerTheme.dimensions.dialogMaxWidth)
            .focusRestorer()
            .focusGroup()
            .semantics { paneTitle = title },
    )
}

/** A governed dialog host for destination content that already owns its business actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun LedgerModalDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = LedgerTheme.dimensions.dialogMaxWidth)
                .focusRestorer()
                .focusGroup()
                .semantics { paneTitle = title },
            shape = LedgerTheme.shapes.xl,
            color = LedgerTheme.colors.material.surface,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(LedgerTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f).semantics { heading() }, style = LedgerTheme.typography.titleLarge)
                    LedgerIconButton(LedgerIcon.CLEAR, stringResource(R.string.ledger_close), onDismiss)
                }
                Box(Modifier.fillMaxWidth().heightIn(max = 720.dp).focusRequester(focusRequester).focusable()) {
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
public fun LedgerBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val modalFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { modalFocusRequester.requestFocus() }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier, shape = LedgerTheme.shapes.xxl) {
        Column(
            Modifier
                .fillMaxWidth()
                .focusRestorer()
                .focusGroup()
                .focusRequester(modalFocusRequester)
                .focusable(),
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
        modifier = modifier.focusRestorer().focusGroup(),
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
public fun LedgerTimePickerDialog(
    state: TimePickerState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modalFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { modalFocusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { LedgerButton(stringResource(R.string.ledger_apply), onConfirm, Modifier.focusRequester(modalFocusRequester)) },
        dismissButton = { LedgerButton(stringResource(R.string.ledger_cancel), onDismiss, variant = LedgerButtonVariant.TEXT) },
        text = { TimePicker(state) },
        modifier = modifier.focusRestorer().focusGroup(),
    )
}

/**
 * Governed two-step Material date/time picker. Feature modules only exchange primitive,
 * non-persisted values and never need to import a raw Material component or picker state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LedgerDateTimePickerFlow(
    initialDateMillis: Long,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (dateMillis: Long, hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var choosingTime by remember { mutableStateOf(false) }
    val date = rememberLedgerDatePickerState(initialDateMillis)
    val time = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    if (choosingTime) {
        LedgerTimePickerDialog(
            state = time,
            onConfirm = { onConfirm(date.selectedDateMillis ?: initialDateMillis, time.hour, time.minute) },
            onDismiss = onDismiss,
        )
    } else {
        LedgerDatePickerDialog(
            state = date,
            onConfirm = { choosingTime = true },
            onDismiss = onDismiss,
        )
    }
}

/** Date-only wrapper for domain fields that must not expose raw Material picker state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun LedgerDatePickerFlow(
    initialDateMillis: Long,
    onConfirm: (dateMillis: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val date = rememberLedgerDatePickerState(initialDateMillis)
    LedgerDatePickerDialog(
        state = date,
        onConfirm = { onConfirm(date.selectedDateMillis ?: initialDateMillis) },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberLedgerDatePickerState(initialDateMillis: Long): DatePickerState {
    val locale = LocalLocale.current.platformLocale
    val weekStart = LedgerTheme.weekStart
    val calendarLocale = remember(locale, weekStart) {
        when (weekStart) {
            LedgerWeekStart.LOCALE_DEFAULT -> locale
            LedgerWeekStart.MONDAY -> java.util.Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("fw", "mon").build()
            LedgerWeekStart.SUNDAY -> java.util.Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("fw", "sun").build()
        }
    }
    return remember(calendarLocale, initialDateMillis) {
        DatePickerState(locale = calendarLocale, initialSelectedDateMillis = initialDateMillis)
    }
}

@Composable
public fun LedgerListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = LedgerTheme.colors.material.outlineVariant)
}

private const val TOP_BAR_TRAVERSAL_INDEX = -1f
private const val CONTENT_TRAVERSAL_INDEX = 0f
private const val BANNER_TRAVERSAL_INDEX = .5f
private const val FIXED_ACTION_TRAVERSAL_INDEX = 1f
private const val BOTTOM_NAVIGATION_TRAVERSAL_INDEX = 2f
