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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
    val hidden = model.visibility == AmountVisibility.HIDDEN || !LocalLedgerAmountsVisible.current
    val display = if (hidden) "••••" else model.formatted
    val accessible = if (hidden) stringResource(R.string.ledger_amount_hidden) else model.fullAccessibleText
    Text(
        text = display,
        modifier = modifier.testTag(LedgerTestTags.AMOUNT).clearAndSetSemantics { text = AnnotatedString(accessible) },
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
    val globallyHidden = !LocalLedgerAmountsVisible.current
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
        AmountText(primary, AmountSize.MEDIUM)
        primary.secondaryFormatted?.let { secondary ->
            val accessibleSecondary = if (primary.visibility == AmountVisibility.HIDDEN || globallyHidden) {
                stringResource(R.string.ledger_amount_hidden)
            } else {
                secondary
            }
            Text(
                if (primary.visibility == AmountVisibility.HIDDEN || globallyHidden) "••••" else secondary,
                modifier = Modifier.clearAndSetSemantics {
                    text = AnnotatedString(accessibleSecondary)
                },
                style = LedgerTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = LedgerTheme.colors.material.onSurfaceVariant,
            )
        }
        if (historicalValuation != null) {
            AmountText(
                if (primary.visibility == AmountVisibility.HIDDEN || globallyHidden) historicalValuation.copy(visibility = AmountVisibility.HIDDEN) else historicalValuation,
                AmountSize.LIST,
            )
        }
        if (explanation != null) {
            val explanationHidden = primary.visibility == AmountVisibility.HIDDEN || globallyHidden
            val accessibleExplanation = if (explanationHidden) stringResource(R.string.ledger_amount_hidden) else explanation
            Text(
                if (explanationHidden) "••••" else explanation,
                modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(accessibleExplanation) },
                style = LedgerTheme.typography.bodySmall,
                color = LedgerTheme.colors.material.onSurfaceVariant,
            )
        }
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
    val hidden = value.visibility == AmountVisibility.HIDDEN || !LocalLedgerAmountsVisible.current
    val accessibleValue = if (hidden) stringResource(R.string.ledger_amount_hidden) else value.fullAccessibleText
    val accessibleSummary = listOfNotNull(
        title,
        accessibleValue,
        comparison?.let { if (hidden) stringResource(R.string.ledger_amount_hidden) else it },
        explanation?.let { if (hidden) stringResource(R.string.ledger_amount_hidden) else it },
    ).joinToString(", ")
    LedgerCard(
        modifier = modifier
            .heightIn(min = LedgerTheme.dimensions.cardMinHeight)
            .semantics(mergeDescendants = true) { contentDescription = accessibleSummary },
        onClick = onClick,
        variant = if (variant == MetricCardVariant.EMPHASIZED) LedgerCardVariant.EMPHASIZED else LedgerCardVariant.STANDARD,
    ) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = LedgerTheme.typography.labelMedium)
                if (onClick != null) LedgerIconView(LedgerIcon.CHEVRON, size = LedgerTheme.dimensions.iconXs)
            }
            AmountText(value, if (variant == MetricCardVariant.EMPHASIZED) AmountSize.LARGE else AmountSize.MEDIUM)
            if (comparison != null) Text(if (hidden) "••••" else comparison, style = LedgerTheme.typography.bodyMedium)
            if (explanation != null) Text(if (hidden) "••••" else explanation, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
        }
    }
}

@Composable
public fun StatusBadge(
    text: String,
    variant: LedgerStatusVariant,
    modifier: Modifier = Modifier,
) {
    val displayedText = if (variant == LedgerStatusVariant.CANDIDATE) stringResource(R.string.ledger_candidate) else text
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
            .then(
                if (variant == LedgerStatusVariant.CANDIDATE) {
                    Modifier.border(
                        LedgerTheme.dimensions.strokeStandard,
                        LedgerTheme.colors.material.secondary,
                        LedgerTheme.shapes.full,
                    )
                } else {
                    Modifier
                },
            )
            .background(container)
            .padding(horizontal = LedgerTheme.spacing.xs, vertical = LedgerTheme.spacing.hairline),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            displayedText,
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
    state: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
) {
    BoxWithConstraints(modifier.testTag(LedgerTestTags.CATEGORY_GRID)) {
        val columns = LedgerTheme.dimensions.categoryColumns(maxWidth)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
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
    val haptic = LocalHapticFeedback.current
    val palette = LedgerTheme.colors.categoryPalette.firstOrNull { it.id == model.paletteId }
        ?: LedgerTheme.colors.categoryPalette.first { it.id == "slate" }
    Card(
        onClick = {
            haptic.performLedgerHaptic(LedgerHaptic.SELECTION)
            onClick()
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LedgerTheme.dimensions.categoryTileMinHeight)
            .semantics {
                this.selected = selected
                text = AnnotatedString(model.accessibleLabel)
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
        Box(Modifier.fillMaxWidth()) {
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
                model.supportingText?.let { supporting ->
                    Text(
                        supporting,
                        style = LedgerTheme.typography.labelSmall,
                        color = LedgerTheme.colors.material.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
                if (model.isTopLevel) {
                    Text(
                        stringResource(R.string.ledger_top_level_category),
                        style = LedgerTheme.typography.labelSmall,
                        color = LedgerTheme.colors.material.onSurfaceVariant,
                    )
                }
                if (model.deleted) {
                    Text(
                        stringResource(R.string.ledger_deleted),
                        style = LedgerTheme.typography.labelSmall,
                        color = LedgerTheme.colors.material.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                LedgerIconView(
                    LedgerIcon.CHECK,
                    Modifier.align(Alignment.TopEnd).padding(LedgerTheme.spacing.xs),
                    tint = LedgerTheme.colors.material.primary,
                    size = LedgerTheme.dimensions.iconXs,
                )
            }
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
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showRunningBalance: Boolean = model.runningBalance != null,
    enabled: Boolean = true,
    selected: Boolean? = null,
) {
    val amountsVisible = LocalLedgerAmountsVisible.current
    val openLabel = stringResource(R.string.ledger_open_transaction)
    val selectLabel = stringResource(R.string.ledger_select_transaction)
    val selectedLabel = stringResource(R.string.ledger_selected)
    val notSelectedLabel = stringResource(R.string.ledger_not_selected)
    val hiddenAmountLabel = stringResource(R.string.ledger_amount_hidden)
    val accessibleDescription = if (amountsVisible) {
        model.accessibleText
    } else {
        listOf(
            model.typeLabel,
            model.categoryOrType,
            model.summary,
            model.accountAndCard,
            hiddenAmountLabel,
            model.badges.joinToString(),
        ).filter(String::isNotBlank).joinToString(". ")
    }
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = LedgerTheme.dimensions.listRowStandard)
        .testTag(LedgerTestTags.JOURNAL_ROW)
    val interactionModifier = if (enabled) {
        rowModifier
            .semantics(mergeDescendants = true) {
                role = if (selected == null) Role.Button else Role.Checkbox
                contentDescription = accessibleDescription
                if (selected != null) {
                    this.selected = selected
                    stateDescription = if (selected) selectedLabel else notSelectedLabel
                }
            }
            .combinedClickable(
                onClick = onClick,
                onClickLabel = openLabel,
                onLongClick = onLongClick,
                onLongClickLabel = selectLabel.takeIf { onLongClick != null },
            )
    } else {
        rowModifier.semantics(mergeDescendants = true) {
            contentDescription = accessibleDescription
        }
    }
    Row(
        interactionModifier
            .then(if (selected == true) Modifier.background(LedgerTheme.colors.material.primaryContainer, LedgerTheme.shapes.md) else Modifier)
            .padding(vertical = LedgerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        if (selected != null) Checkbox(selected, onCheckedChange = null, Modifier.clearAndSetSemantics { })
        Box(
            Modifier.size(LedgerTheme.dimensions.accountIconContainer).clip(LedgerTheme.shapes.full),
            contentAlignment = Alignment.Center,
        ) { LedgerIconView(model.icon) }
        Column(Modifier.weight(1f)) {
            val title = if (model.categoryOrType.isBlank() || model.categoryOrType == model.typeLabel) {
                model.typeLabel
            } else {
                "${model.typeLabel} · ${model.categoryOrType}"
            }
            Text(title, style = LedgerTheme.typography.bodyLarge, maxLines = 2)
            if (model.summary.isNotBlank() && model.summary != model.categoryOrType && model.summary != model.typeLabel) {
                Text(model.summary, style = LedgerTheme.typography.bodyMedium, maxLines = 1, color = LedgerTheme.colors.material.onSurfaceVariant)
            }
            if (model.accountAndCard.isNotBlank()) Text(model.accountAndCard, style = LedgerTheme.typography.bodySmall, maxLines = 1)
            if (model.badges.isNotEmpty()) Text(formatBadgeSummary(model.badges), style = LedgerTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            AmountText(model.amount, AmountSize.LIST)
            if (showRunningBalance && model.runningBalance != null) AmountText(model.runningBalance, AmountSize.LIST)
        }
    }
}

@Composable
private fun formatBadgeSummary(badges: List<String>): String = if (badges.size <= 4) {
    badges.joinToString(" · ")
} else {
    badges.take(3).joinToString(" · ") + " · " + stringResource(R.string.ledger_more_badges, badges.size - 3)
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
    onClick: (() -> Unit)?,
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
            if (model.status != LedgerStatusVariant.NEUTRAL) StatusBadge(ledgerStatusLabel(model.status), model.status)
            if (onClick != null) LedgerIconView(LedgerIcon.CHEVRON)
        }
    }
}

@Composable
private fun ledgerStatusLabel(status: LedgerStatusVariant): String = stringResource(
    when (status) {
        LedgerStatusVariant.NEUTRAL -> R.string.ledger_status_neutral
        LedgerStatusVariant.POSITIVE -> R.string.ledger_status_positive
        LedgerStatusVariant.INFO -> R.string.ledger_status_info
        LedgerStatusVariant.WARNING -> R.string.ledger_status_warning
        LedgerStatusVariant.DANGER -> R.string.ledger_status_danger
        LedgerStatusVariant.CANDIDATE -> R.string.ledger_candidate
        LedgerStatusVariant.ARCHIVED -> R.string.ledger_status_archived
        LedgerStatusVariant.DELETED -> R.string.ledger_deleted
    },
)

@Composable
public fun ProgressSummary(
    model: ProgressSummaryUiModel,
    modifier: Modifier = Modifier,
) {
    val privacyHidden = !LocalLedgerAmountsVisible.current && listOf(
        model.valueText,
        model.statusText,
        model.accessibleText,
        model.excessText.orEmpty(),
    ).any(String::containsLedgerFinancialValue)
    if (privacyHidden) {
        val hiddenAmountText = stringResource(R.string.ledger_amount_hidden)
        Column(modifier.fillMaxWidth().clearAndSetSemantics { text = AnnotatedString(hiddenAmountText) }) {
            Text(model.title, style = LedgerTheme.typography.titleSmall)
            Text("••••", style = LedgerTheme.typography.labelLarge)
        }
        return
    }
    val semantic = when (model.state) {
        LedgerProgressState.NORMAL -> LedgerTheme.colors.positive
        LedgerProgressState.WARNING -> LedgerTheme.colors.warning
        LedgerProgressState.OVER_LIMIT -> LedgerTheme.colors.danger
    }
    Column(modifier.fillMaxWidth().clearAndSetSemantics { text = AnnotatedString(model.accessibleText) }) {
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
    autoFocus: Boolean = false,
    onAutoFocusConsumed: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(expression, selection = TextRange(expression.length)))
    }
    LaunchedEffect(expression) {
        if (fieldValue.text != expression) {
            val cursor = fieldValue.selection.end.coerceIn(0, expression.length)
            fieldValue = TextFieldValue(expression, selection = TextRange(cursor))
        }
    }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            onAutoFocusConsumed()
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
        LedgerTextField(
            value = fieldValue,
            onValueChange = { changed ->
                fieldValue = changed
                onExpressionChange(changed.text)
            },
            label = currencyCode,
            errorText = errorText,
            keyboardType = KeyboardType.Decimal,
            required = true,
            modifier = Modifier.focusRequester(focusRequester),
        )
        if (normalizedExpression.isNotBlank()) Text(normalizedExpression, style = LedgerTheme.typography.bodySmall)
        if (result != null) AmountText(result, AmountSize.MEDIUM)
        if (showOperatorToolbar) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
                listOf("+", "−", "×", "÷", "(", ")").forEach { operator ->
                    LedgerButton(
                        operator,
                        {
                            fieldValue = MoneyExpressionEditing.apply(fieldValue, operator)
                            onExpressionChange(fieldValue.text)
                        },
                        compact = true,
                        variant = LedgerButtonVariant.SECONDARY,
                    )
                }
                LedgerIconButton(
                    LedgerIcon.CLEAR,
                    stringResource(R.string.ledger_delete_operator),
                    {
                        fieldValue = MoneyExpressionEditing.apply(fieldValue, "DELETE")
                        onExpressionChange(fieldValue.text)
                    },
                )
            }
        }
        if (roundingExplanation != null) Text(roundingExplanation, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
    }
}

@Composable
public fun MoneyExpressionField(
    expression: TextFieldValue,
    normalizedExpression: String,
    result: MoneyUiModel?,
    onExpressionChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    currencyCode: String,
    errorText: String? = null,
    roundingExplanation: String? = null,
    showOperatorToolbar: Boolean = true,
    autoFocus: Boolean = false,
    onAutoFocusConsumed: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            onAutoFocusConsumed()
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
        LedgerTextField(
            value = expression,
            onValueChange = onExpressionChange,
            label = currencyCode,
            errorText = errorText,
            keyboardType = KeyboardType.Decimal,
            required = true,
            modifier = Modifier.focusRequester(focusRequester),
        )
        if (normalizedExpression.isNotBlank()) Text(normalizedExpression, style = LedgerTheme.typography.bodySmall)
        if (result != null) AmountText(result, AmountSize.MEDIUM)
        if (showOperatorToolbar) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
                listOf("+", "−", "×", "÷", "(", ")").forEach { operator ->
                    LedgerButton(
                        operator,
                        { onExpressionChange(MoneyExpressionEditing.apply(expression, operator)) },
                        compact = true,
                        variant = LedgerButtonVariant.SECONDARY,
                    )
                }
                LedgerIconButton(
                    LedgerIcon.CLEAR,
                    stringResource(R.string.ledger_delete_operator),
                    { onExpressionChange(MoneyExpressionEditing.apply(expression, "DELETE")) },
                )
            }
        }
        if (roundingExplanation != null) Text(roundingExplanation, style = LedgerTheme.typography.bodySmall, color = LedgerTheme.colors.material.onSurfaceVariant)
    }
}

public object MoneyExpressionEditing {
    public fun apply(value: TextFieldValue, operator: String): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
        if (operator == "DELETE") {
            val deleteStart = if (start != end) start else (start - 1).coerceAtLeast(0)
            val next = value.text.removeRange(deleteStart, end)
            return TextFieldValue(next, selection = TextRange(deleteStart))
        }
        val normalized = operator.replace('−', '-').replace('×', '*').replace('÷', '/')
        val next = value.text.replaceRange(start, end, normalized)
        return TextFieldValue(next, selection = TextRange(start + normalized.length))
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
    showActions: Boolean = true,
) {
    if (showActions) {
        LedgerScaffold(
            modifier.fillMaxSize(),
            fixedAction = { FilterBuilderActionBar(onReset, onApply) },
        ) { padding ->
            FilterBuilderContent(
                dimensions,
                naturalLanguageSummary,
                onRemove,
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            )
        }
    } else {
        FilterBuilderContent(dimensions, naturalLanguageSummary, onRemove, modifier)
    }
}

@Composable
private fun FilterBuilderContent(
    dimensions: List<FilterDimensionUiModel>,
    naturalLanguageSummary: String,
    onRemove: (FilterChipUiModel) -> Unit,
    modifier: Modifier,
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
    }
}

@Composable
public fun FilterBuilderActionBar(onReset: () -> Unit, onApply: () -> Unit, modifier: Modifier = Modifier) {
    LedgerCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm, Alignment.End),
        ) {
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
    onRetry: (AttachmentUiModel) -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        attachments.forEach { attachment ->
            LedgerCard(onClick = if (attachment.state == AttachmentTransferState.READY) ({ onOpen(attachment) }) else null) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    LedgerIconView(attachment.icon)
                    Column(Modifier.weight(1f).padding(horizontal = LedgerTheme.spacing.xs)) {
                        Text(attachment.displayName, maxLines = 2)
                        Text("${attachment.typeLabel} · ${attachment.sizeText}", style = LedgerTheme.typography.bodySmall)
                        if (attachment.progress != null) {
                            LedgerProgressIndicator(
                                attachment.progress,
                                accessibleText = stringResource(R.string.ledger_attachment_import_progress),
                            )
                        }
                        if (attachment.state == AttachmentTransferState.FAILED) {
                            Text(
                                stringResource(R.string.ledger_attachment_failed),
                                style = LedgerTheme.typography.bodySmall,
                                color = LedgerTheme.colors.danger.base,
                            )
                        }
                    }
                    if (attachment.state == AttachmentTransferState.IMPORTING) {
                        LedgerIconButton(LedgerIcon.CLOSE, stringResource(R.string.ledger_cancel), { onCancel(attachment) })
                    }
                    if (attachment.state == AttachmentTransferState.FAILED) {
                        LedgerButton(
                            stringResource(R.string.ledger_retry),
                            { onRetry(attachment) },
                            variant = LedgerButtonVariant.TEXT,
                            compact = true,
                        )
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
        LocationFieldState.ReadyAtSave -> stringResource(R.string.ledger_location_ready_at_save)
        is LocationFieldState.Located -> stringResource(R.string.ledger_location_acquired, state.summaryText)
        LocationFieldState.TimedOut -> stringResource(R.string.ledger_location_timed_out)
        LocationFieldState.ServiceUnavailable -> stringResource(R.string.ledger_location_service_unavailable)
        LocationFieldState.NotRecorded -> stringResource(R.string.ledger_location_not_recorded)
        LocationFieldState.Unavailable -> stringResource(R.string.ledger_location_unavailable)
        LocationFieldState.PermissionDenied -> stringResource(R.string.ledger_location_denied)
        is LocationFieldState.ManuallyAdjusted -> stringResource(R.string.ledger_location_manual, state.summaryText)
    }
    LedgerCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            LedgerIconView(LedgerIcon.LOCATION)
            Text(text, Modifier.weight(1f).padding(horizontal = LedgerTheme.spacing.xs), style = LedgerTheme.typography.bodyMedium)
            LedgerButton(mapLabel, onOpenMap, variant = LedgerButtonVariant.TEXT)
        }
    }
}
