@file:Suppress(
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "LongParameterList",
    "MagicNumber",
    "TooManyFunctions",
)

package app.ledger.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.MoneyUiModel

@Composable
public fun AmountText(
    model: MoneyUiModel,
    size: AmountSize,
    modifier: Modifier = Modifier,
) {
    val hidden = model.visibility == AmountVisibility.HIDDEN
    val display = if (hidden) "••••" else model.formatted
    val accessible = if (hidden) stringResource(R.string.ledger_amount_hidden) else model.fullAccessibleText
    Text(
        text = display,
        modifier = modifier.testTag(LedgerTestTags.AMOUNT).clearAndSetSemantics { contentDescription = accessible },
        style = amountStyle(size),
        color = amountColor(model.semantic),
        textAlign = if (size == AmountSize.LIST) TextAlign.End else TextAlign.Start,
        maxLines = if (size == AmountSize.LIST) 2 else Int.MAX_VALUE,
    )
}

@Composable
private fun amountStyle(size: AmountSize): TextStyle = when (size) {
    AmountSize.HERO -> LedgerTheme.typography.amountHero
    AmountSize.LARGE -> LedgerTheme.typography.amountLarge
    AmountSize.MEDIUM -> LedgerTheme.typography.amountMedium
    AmountSize.LIST -> LedgerTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
}

@Composable
private fun amountColor(semantic: AmountSemantic): Color = when (semantic) {
    AmountSemantic.INFLOW -> LedgerTheme.colors.positive.base
    AmountSemantic.REFUND -> LedgerTheme.colors.info.base
    AmountSemantic.TRANSFER -> LedgerTheme.colors.neutralTransaction.base
    AmountSemantic.CANDIDATE -> LedgerTheme.colors.material.secondary
    AmountSemantic.NEUTRAL, AmountSemantic.OUTFLOW -> LedgerTheme.colors.material.onSurface
}

@Composable
public fun MoneyStack(
    primary: MoneyUiModel,
    modifier: Modifier = Modifier,
    historicalValuation: MoneyUiModel? = null,
    explanation: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
        AmountText(primary, AmountSize.MEDIUM)
        primary.secondaryFormatted?.let { secondary ->
            Text(
                if (primary.visibility == AmountVisibility.HIDDEN) "••••" else secondary,
                style = LedgerTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = LedgerTheme.colors.material.onSurfaceVariant,
            )
        }
        if (historicalValuation != null) AmountText(historicalValuation, AmountSize.LIST)
        if (explanation != null) Text(explanation, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
    }
}

@Composable
public fun MetricCard(
    title: String,
    value: MoneyUiModel,
    modifier: Modifier = Modifier,
    variant: MetricCardVariant = MetricCardVariant.STANDARD,
    comparison: String? = null,
    explanation: String? = null,
    onClick: (() -> Unit)? = null,
) {
    LedgerCard(
        modifier = modifier.heightIn(min = LedgerTheme.dimensions.cardMinHeight),
        onClick = onClick,
        containerColor = if (variant == MetricCardVariant.EMPHASIZED) LedgerTheme.colors.material.primaryContainer else LedgerTheme.colors.material.surfaceContainer,
    ) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
            Text(title, style = LedgerTheme.typography.labelMedium)
            AmountText(value, if (variant == MetricCardVariant.EMPHASIZED) AmountSize.LARGE else AmountSize.MEDIUM)
            if (comparison != null) Text(comparison, style = LedgerTheme.typography.bodyMedium)
            if (explanation != null) Text(explanation, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
        }
    }
}

@Composable
public fun StatusBadge(
    text: String,
    variant: LedgerStatusVariant,
    modifier: Modifier = Modifier,
) {
    val (container, content) = when (variant) {
        LedgerStatusVariant.POSITIVE -> LedgerTheme.colors.positive.container to LedgerTheme.colors.positive.onContainer
        LedgerStatusVariant.INFO -> LedgerTheme.colors.info.container to LedgerTheme.colors.info.onContainer
        LedgerStatusVariant.WARNING -> LedgerTheme.colors.warning.container to LedgerTheme.colors.warning.onContainer
        LedgerStatusVariant.DANGER -> LedgerTheme.colors.danger.container to LedgerTheme.colors.danger.onContainer
        LedgerStatusVariant.CANDIDATE -> Color.Transparent to LedgerTheme.colors.material.secondary
        LedgerStatusVariant.NEUTRAL, LedgerStatusVariant.ARCHIVED, LedgerStatusVariant.DELETED ->
            LedgerTheme.colors.material.surfaceContainerHigh to LedgerTheme.colors.material.onSurfaceVariant
    }
    Box(
        modifier
            .heightIn(min = LedgerTheme.dimensions.iconMd)
            .clip(LedgerTheme.shapes.full)
            .background(container)
            .padding(horizontal = LedgerTheme.spacing.xs, vertical = LedgerTheme.spacing.hairline),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = content,
            style = LedgerTheme.typography.labelMedium,
            textDecoration = if (variant == LedgerStatusVariant.DELETED) TextDecoration.LineThrough else null,
        )
    }
}

@Composable
public fun CategoryGrid(
    groups: List<CategoryGroupUiModel>,
    selectedStableKey: String?,
    onSelect: (CategoryTileUiModel) -> Unit,
    modifier: Modifier = Modifier,
    onCreate: (() -> Unit)? = null,
    createLabel: String? = null,
) {
    BoxWithConstraints(modifier.testTag(LedgerTestTags.CATEGORY_GRID)) {
        val columns = LedgerTheme.dimensions.categoryColumns(maxWidth)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            groups.forEach { group ->
                item(key = "group-${group.stableKey}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(group.title, Modifier.padding(top = LedgerTheme.spacing.sm).semantics { heading() }, style = LedgerTheme.typography.titleSmall)
                }
                items(group.categories, key = CategoryTileUiModel::stableKey) { category ->
                    CategoryTile(category, category.stableKey == selectedStableKey, { onSelect(category) })
                }
            }
            if (onCreate != null && createLabel != null) {
                item(key = "create-category", span = { GridItemSpan(maxLineSpan) }) {
                    LedgerButton(createLabel, onCreate, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.SECONDARY, leadingIcon = LedgerIcon.ADD)
                }
            }
        }
    }
}

@Composable
public fun CategoryTile(
    model: CategoryTileUiModel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LedgerTheme.colors.categoryPalette.firstOrNull { it.id == model.paletteId }
        ?: LedgerTheme.colors.categoryPalette.first { it.id == "slate" }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LedgerTheme.dimensions.categoryTileMinHeight)
            .semantics {
                this.selected = selected
                contentDescription = model.accessibleLabel
                role = Role.Button
            },
        shape = LedgerTheme.shapes.lg,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) LedgerTheme.colors.material.primaryContainer.copy(alpha = .22f) else LedgerTheme.colors.material.surfaceContainer,
        ),
        border = BorderStroke(
            if (selected) LedgerTheme.dimensions.strokeSelected else LedgerTheme.dimensions.strokeStandard,
            if (selected) LedgerTheme.colors.material.primary else LedgerTheme.colors.material.outlineVariant,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(LedgerTheme.spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs),
        ) {
            Box(
                Modifier.size(LedgerTheme.dimensions.categoryIconContainer).clip(LedgerTheme.shapes.full).background(
                    if (model.deleted) LedgerTheme.colors.material.surfaceContainerHighest else palette.container,
                ),
                contentAlignment = Alignment.Center,
            ) { LedgerIconView(model.icon, tint = if (model.deleted) LedgerTheme.colors.material.onSurfaceVariant else palette.foreground) }
            Text(
                model.name,
                style = LedgerTheme.typography.bodyMedium,
                maxLines = 2,
                textAlign = TextAlign.Center,
                textDecoration = if (model.deleted) TextDecoration.LineThrough else null,
            )
            if (model.isTopLevel) {
                Text(
                    stringResource(R.string.ledger_top_level_category),
                    style = LedgerTheme.typography.labelSmall,
                    color = LedgerTheme.colors.material.onSurfaceVariant,
                )
            }
            if (selected) LedgerIconView(LedgerIcon.CHECK, tint = LedgerTheme.colors.material.primary, size = LedgerTheme.dimensions.iconXs)
        }
    }
}

@Composable
public fun ReferenceDisplayStylePicker(
    selectedIcon: LedgerIcon,
    selectedPaletteId: String,
    iconSectionLabel: String,
    colorSectionLabel: String,
    onIconSelected: (LedgerIcon) -> Unit,
    onPaletteSelected: (paletteId: String, colorArgb: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val icons = REFERENCE_STYLE_ICONS
    val palette = LedgerTheme.colors.categoryPalette
    val iconLabels = referenceStyleIconLabels()
    val colorLabels = referenceStyleColorLabels()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        LedgerText(iconSectionLabel, LedgerTextRole.SECTION)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            icons.forEachIndexed { index, icon ->
                val label = iconLabels[index]
                val selected = icon == selectedIcon
                Card(
                    Modifier
                        .size(LedgerTheme.dimensions.touchTargetMin)
                        .clickable { onIconSelected(icon) }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = selected
                            contentDescription = label
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            LedgerTheme.colors.material.primaryContainer
                        } else {
                            LedgerTheme.colors.material.surfaceContainer
                        },
                    ),
                    border = BorderStroke(
                        if (selected) LedgerTheme.dimensions.strokeSelected else LedgerTheme.dimensions.strokeStandard,
                        if (selected) LedgerTheme.colors.material.primary else LedgerTheme.colors.material.outlineVariant,
                    ),
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = LedgerTheme.dimensions.touchTargetMin), contentAlignment = Alignment.Center) {
                        LedgerIconView(icon, contentDescription = null)
                    }
                }
            }
        }
        LedgerText(colorSectionLabel, LedgerTextRole.SECTION)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            palette.forEachIndexed { index, option ->
                val label = colorLabels[index]
                val selected = option.id == selectedPaletteId
                Card(
                    Modifier
                        .size(LedgerTheme.dimensions.touchTargetMin)
                        .clickable {
                            onPaletteSelected(
                                option.id,
                                requireNotNull(LedgerReferenceDisplayDefaults.categoryPaletteArgb[option.id]),
                            )
                        }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = selected
                            contentDescription = label
                        },
                    colors = CardDefaults.cardColors(containerColor = option.container),
                    border = BorderStroke(
                        if (selected) LedgerTheme.dimensions.strokeSelected else LedgerTheme.dimensions.strokeStandard,
                        if (selected) LedgerTheme.colors.material.primary else option.foreground,
                    ),
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = LedgerTheme.dimensions.touchTargetMin), contentAlignment = Alignment.Center) {
                        LedgerIconView(selectedIcon, tint = option.foreground, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun referenceStyleIconLabels(): List<String> = listOf(
    stringResource(R.string.ledger_style_icon_record),
    stringResource(R.string.ledger_style_icon_account),
    stringResource(R.string.ledger_style_icon_budget),
    stringResource(R.string.ledger_style_icon_analysis),
    stringResource(R.string.ledger_style_icon_journal),
    stringResource(R.string.ledger_style_icon_location),
    stringResource(R.string.ledger_style_icon_transfer),
    stringResource(R.string.ledger_style_icon_refund),
)

@Composable
private fun referenceStyleColorLabels(): List<String> = listOf(
    stringResource(R.string.ledger_style_color_red),
    stringResource(R.string.ledger_style_color_orange),
    stringResource(R.string.ledger_style_color_amber),
    stringResource(R.string.ledger_style_color_yellow),
    stringResource(R.string.ledger_style_color_lime),
    stringResource(R.string.ledger_style_color_green),
    stringResource(R.string.ledger_style_color_emerald),
    stringResource(R.string.ledger_style_color_teal),
    stringResource(R.string.ledger_style_color_cyan),
    stringResource(R.string.ledger_style_color_sky),
    stringResource(R.string.ledger_style_color_blue),
    stringResource(R.string.ledger_style_color_indigo),
    stringResource(R.string.ledger_style_color_violet),
    stringResource(R.string.ledger_style_color_purple),
    stringResource(R.string.ledger_style_color_pink),
    stringResource(R.string.ledger_style_color_slate),
)

public val ReferenceDisplayStyleIcons: List<LedgerIcon>
    get() = REFERENCE_STYLE_ICONS

private val REFERENCE_STYLE_ICONS: List<LedgerIcon> = listOf(
    LedgerIcon.RECORD,
    LedgerIcon.ACCOUNT,
    LedgerIcon.BUDGET,
    LedgerIcon.ANALYSIS,
    LedgerIcon.JOURNAL,
    LedgerIcon.LOCATION,
    LedgerIcon.TRANSFER,
    LedgerIcon.REFUND,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun JournalTransactionRow(
    model: JournalTransactionUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRunningBalance: Boolean = model.runningBalance != null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = LedgerTheme.dimensions.listRowStandard)
            .testTag(LedgerTestTags.JOURNAL_ROW)
            .clearAndSetSemantics {
                contentDescription = model.accessibleText
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
                onLongClick {
                    onLongClick()
                    true
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = LedgerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        Box(
            Modifier.size(LedgerTheme.dimensions.accountIconContainer).clip(LedgerTheme.shapes.full),
            contentAlignment = Alignment.Center,
        ) { LedgerIconView(model.icon) }
        Column(Modifier.weight(1f)) {
            Text("${model.typeLabel} · ${model.categoryOrType}", style = LedgerTheme.typography.bodyLarge, maxLines = 2)
            Text(model.summary, style = LedgerTheme.typography.bodyMedium, maxLines = 1, color = LedgerTheme.colors.material.onSurfaceVariant)
            Text(model.accountAndCard, style = LedgerTheme.typography.bodySmall, maxLines = 1)
            if (model.badges.isNotEmpty()) Text(model.badges.take(4).joinToString(" · "), style = LedgerTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            AmountText(model.amount, AmountSize.LIST)
            if (showRunningBalance && model.runningBalance != null) AmountText(model.runningBalance, AmountSize.LIST)
        }
    }
}

@Composable
public fun AccountSummaryCard(
    model: AccountSummaryUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LedgerTheme.colors.categoryPalette.single { it.id == model.paletteId }
    LedgerCard(modifier.alpha(if (model.archived) .72f else 1f), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        ) {
            Box(
                Modifier.size(LedgerTheme.dimensions.accountIconContainer).clip(LedgerTheme.shapes.full).background(palette.container),
                contentAlignment = Alignment.Center,
            ) {
                LedgerIconView(model.icon, tint = palette.foreground)
            }
            Column(Modifier.weight(1f)) {
                Text(model.name, style = LedgerTheme.typography.titleSmall)
                Text(model.typeLabel, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
                if (model.secondaryValue != null) Text(model.secondaryValue, style = LedgerTheme.typography.bodySmall)
                if (model.status != null) Text(model.status, style = LedgerTheme.typography.labelSmall)
            }
            AmountText(model.balance, AmountSize.LIST)
        }
    }
}

@Composable
public fun ReferenceDataRow(
    model: ReferenceDataRowUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deleted = model.status == LedgerStatusVariant.DELETED
    val archived = model.status == LedgerStatusVariant.ARCHIVED
    val palette = model.paletteId?.let { id -> LedgerTheme.colors.categoryPalette.single { it.id == id } }
    LedgerCard(
        modifier = modifier.fillMaxWidth().alpha(if (archived || deleted) .72f else 1f),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = if (model.hierarchyLevel == 2) LedgerTheme.spacing.lg else LedgerTheme.spacing.sm)
                .padding(end = LedgerTheme.spacing.sm, top = LedgerTheme.spacing.xs, bottom = LedgerTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            if (model.icon != null && palette != null) {
                Box(
                    Modifier.size(LedgerTheme.dimensions.categoryIconContainer).clip(LedgerTheme.shapes.full)
                        .background(if (deleted) LedgerTheme.colors.material.surfaceContainerHighest else palette.container),
                    contentAlignment = Alignment.Center,
                ) {
                    LedgerIconView(
                        model.icon,
                        tint = if (deleted) LedgerTheme.colors.material.onSurfaceVariant else palette.foreground,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    model.title,
                    style = LedgerTheme.typography.bodyLarge,
                    color = if (deleted) LedgerTheme.colors.material.onSurfaceVariant else LedgerTheme.colors.material.onSurface,
                    textDecoration = if (deleted) TextDecoration.LineThrough else null,
                )
                if (model.supportingText != null) {
                    Text(model.supportingText, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
                }
            }
            if (model.status != LedgerStatusVariant.NEUTRAL) StatusBadge(model.status.name, model.status)
            LedgerIconView(LedgerIcon.CHEVRON)
        }
    }
}

@Composable
public fun ProgressSummary(
    model: ProgressSummaryUiModel,
    modifier: Modifier = Modifier,
) {
    val semantic = when (model.state) {
        LedgerProgressState.NORMAL -> LedgerTheme.colors.positive
        LedgerProgressState.WARNING -> LedgerTheme.colors.warning
        LedgerProgressState.OVER_LIMIT -> LedgerTheme.colors.danger
    }
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = model.accessibleText }) {
        Row(Modifier.fillMaxWidth()) {
            Text(model.title, Modifier.weight(1f), style = LedgerTheme.typography.titleSmall)
            Text(model.valueText, style = LedgerTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { model.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = LedgerTheme.spacing.xs).height(LedgerTheme.spacing.xs),
            color = semantic.base,
            trackColor = semantic.container,
        )
        Text(model.statusText, style = LedgerTheme.typography.bodySmall)
        if (model.excessText != null) Text(model.excessText, color = LedgerTheme.colors.danger.base, style = LedgerTheme.typography.bodySmall)
    }
}

@Composable
public fun MoneyExpressionField(
    expression: String,
    normalizedExpression: String,
    result: MoneyUiModel?,
    onExpressionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    currencyCode: String,
    errorText: String? = null,
    roundingExplanation: String? = null,
    showOperatorToolbar: Boolean = true,
    onOperator: (String) -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
        LedgerTextField(
            value = expression,
            onValueChange = onExpressionChange,
            label = currencyCode,
            errorText = errorText,
            keyboardType = KeyboardType.Decimal,
            required = true,
        )
        if (normalizedExpression.isNotBlank()) Text(normalizedExpression, style = LedgerTheme.typography.bodySmall)
        if (result != null) AmountText(result, AmountSize.MEDIUM)
        if (showOperatorToolbar) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
                listOf("+", "−", "×", "÷", "(", ")").forEach { operator ->
                    LedgerButton(operator, { onOperator(operator) }, compact = true, variant = LedgerButtonVariant.SECONDARY)
                }
                LedgerIconButton(LedgerIcon.CLEAR, stringResource(R.string.ledger_delete_operator), { onOperator("DELETE") })
            }
        }
        if (roundingExplanation != null) Text(roundingExplanation, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
    }
}

@Composable
public fun FilterBuilder(
    dimensions: List<FilterDimensionUiModel>,
    naturalLanguageSummary: String,
    onRemove: (FilterChipUiModel) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.lg)) {
        Text(naturalLanguageSummary, style = LedgerTheme.typography.bodyMedium)
        dimensions.forEach { dimension ->
            FormSection(dimension.title) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
                    dimension.chips.forEach { chip -> LedgerChip(chip.label, { onRemove(chip) }, selected = true) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm, Alignment.End)) {
            LedgerButton(stringResource(R.string.ledger_reset), onReset, variant = LedgerButtonVariant.SECONDARY)
            LedgerButton(stringResource(R.string.ledger_apply), onApply)
        }
    }
}

@Composable
public fun AttachmentField(
    attachments: List<AttachmentUiModel>,
    onAdd: () -> Unit,
    onOpen: (AttachmentUiModel) -> Unit,
    onCancel: (AttachmentUiModel) -> Unit,
    modifier: Modifier = Modifier,
    addLabel: String,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        attachments.forEach { attachment ->
            LedgerCard(onClick = { onOpen(attachment) }) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    LedgerIconView(LedgerIcon.ATTACHMENT)
                    Column(Modifier.weight(1f).padding(horizontal = LedgerTheme.spacing.xs)) {
                        Text(attachment.displayName, maxLines = 2)
                        Text("${attachment.typeLabel} · ${attachment.sizeText}", style = LedgerTheme.typography.bodySmall)
                        if (attachment.progress != null) {
                            LedgerProgressIndicator(
                                attachment.progress,
                                accessibleText = stringResource(R.string.ledger_attachment_import_progress),
                            )
                        }
                    }
                    if (attachment.state == AttachmentTransferState.IMPORTING) {
                        LedgerIconButton(LedgerIcon.CLOSE, stringResource(R.string.ledger_cancel), { onCancel(attachment) })
                    }
                }
            }
        }
        LedgerButton(addLabel, onAdd, variant = LedgerButtonVariant.SECONDARY, leadingIcon = LedgerIcon.ADD)
    }
}

@Composable
public fun LocationField(
    state: LocationFieldState,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
    mapLabel: String,
) {
    val text = when (state) {
        LocationFieldState.Locating -> stringResource(R.string.ledger_locating)
        is LocationFieldState.Located -> stringResource(R.string.ledger_location_acquired, state.accuracyText)
        LocationFieldState.Unavailable -> stringResource(R.string.ledger_location_unavailable)
        LocationFieldState.PermissionDenied -> stringResource(R.string.ledger_location_denied)
        LocationFieldState.ManuallyAdjusted -> stringResource(R.string.ledger_location_manual)
    }
    LedgerCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            LedgerIconView(LedgerIcon.LOCATION)
            Text(text, Modifier.weight(1f).padding(horizontal = LedgerTheme.spacing.xs), style = LedgerTheme.typography.bodyMedium)
            LedgerButton(mapLabel, onOpenMap, variant = LedgerButtonVariant.TEXT)
        }
    }
}
