@file:Suppress("FunctionNaming", "MagicNumber", "LongMethod", "ktlint:standard:function-naming")

package app.ledger.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.ledger.core.designsystem.LedgerGlanceTokens
import app.ledger.finance.application.WidgetBookSnapshot
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import androidx.glance.color.ColorProvider as dayNightColorProvider

class LedgerGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val configuration = LedgerWidgetRuntime.readConfiguration(appWidgetId)
        val content = configuration?.let { LedgerWidgetRuntime.resolve(it) }
            ?: LedgerWidgetContent.NotConfigured
        provideContent { LedgerWidgetContent(configuration, content) }
    }
}

class LedgerGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LedgerGlanceWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val completion = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                LedgerWidgetRuntime.deleteConfigurations(appWidgetIds.toSet())
            } finally {
                completion.finish()
            }
        }
    }
}

@Composable
private fun LedgerWidgetContent(
    configuration: LedgerWidgetConfiguration?,
    content: LedgerWidgetContent,
) {
    val context = LocalContext.current
    val tokens = LedgerGlanceTokens.light
    val colors = dayNightColorProvider(tokens.surface, LedgerGlanceTokens.dark.surface)
    val onSurface = dayNightColorProvider(tokens.onSurface, LedgerGlanceTokens.dark.onSurface)
    val action = configuration?.let { actionStartActivity(deepLinkIntent(context, it)) }
    val modifier = GlanceModifier
        .fillMaxSize()
        .background(colors)
        .padding(12.dp)
        .let { base -> if (action == null) base else base.clickable(action) }
    Column(modifier, verticalAlignment = Alignment.Vertical.CenterVertically) {
        when (content) {
            LedgerWidgetContent.NotConfigured -> StateText(R.string.widget_not_configured, onSurface)
            LedgerWidgetContent.NoEligibleData -> StateText(R.string.widget_no_data, onSurface)
            LedgerWidgetContent.Locked -> StateText(R.string.widget_locked, onSurface)
            LedgerWidgetContent.Stale -> StateText(R.string.widget_stale, onSurface)
            is LedgerWidgetContent.Ready -> ReadyContent(requireNotNull(configuration), content, onSurface)
        }
    }
}

@Composable
private fun StateText(resource: Int, color: ColorProvider) {
    val context = LocalContext.current
    Text(context.getString(resource), style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.bodySizeSp.sp))
}

@Composable
private fun ReadyContent(
    configuration: LedgerWidgetConfiguration,
    content: LedgerWidgetContent.Ready,
    color: ColorProvider,
) {
    val context = LocalContext.current
    val title = context.getString(configuration.type.titleResource())
    Text(
        title,
        style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp, fontWeight = FontWeight.Medium),
    )
    when (configuration.type) {
        LedgerWidgetType.QUICK_ENTRY -> Text(
            context.getString(R.string.widget_open_full_form),
            style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.bodySizeSp.sp),
        )
        LedgerWidgetType.ACCOUNT -> content.account?.let { account ->
            Text(account.displayName, style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp))
            AmountText(account.balanceMinor, account.currency, configuration.revealAmounts, color)
            Text(
                context.getString(
                    R.string.widget_available_value,
                    formattedAmount(account.availableMinor, account.currency, configuration.revealAmounts),
                ),
                style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp),
            )
        }
        LedgerWidgetType.CREDIT_CARD -> content.credit?.let { credit ->
            Text(credit.displayName, style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp))
            AmountText(credit.statementRemainingMinor ?: credit.debtMinor, credit.currency, configuration.revealAmounts, color)
            credit.statementDueDate?.let { due ->
                Text(
                    context.getString(R.string.widget_due_date_value, due.toDisplayDate()),
                    style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp),
                )
            }
        }
        LedgerWidgetType.GOAL -> content.goal?.let { goal ->
            Text(goal.displayName, style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp))
            AmountText(goal.balanceMinor, goal.currency, configuration.revealAmounts, color)
            Text(
                context.getString(R.string.widget_progress_value, goalProgress(goal.balanceMinor, goal.targetMinor)),
                style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp),
            )
        }
        LedgerWidgetType.FINANCIAL_OVERVIEW -> Overview(content.bundle, configuration.revealAmounts, color)
        else -> BookAmount(configuration, requireNotNull(content.bundle.book), color)
    }
}

@Composable
private fun BookAmount(
    configuration: LedgerWidgetConfiguration,
    book: WidgetBookSnapshot,
    color: ColorProvider,
) {
    val amount = when (configuration.type) {
        LedgerWidgetType.MONTH_CONSUMPTION -> book.monthConsumptionBaseMinor
        LedgerWidgetType.MONTH_BUDGET -> requireNotNull(book.monthBudgetAvailableBaseMinor)
        LedgerWidgetType.TODAY_AVAILABLE -> requireNotNull(book.todayAvailableBaseMinor)
        LedgerWidgetType.CORE_NET_ASSETS -> book.coreNetFinancialAssetsBaseMinor
        else -> 0L
    }
    AmountText(amount, book.baseCurrency, configuration.revealAmounts, color)
    if (configuration.type == LedgerWidgetType.MONTH_BUDGET) {
        val context = LocalContext.current
        Text(
            context.getString(
                R.string.widget_used_value,
                formattedAmount(requireNotNull(book.monthBudgetUsedBaseMinor), book.baseCurrency, configuration.revealAmounts),
            ),
            style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp),
        )
    }
    if (configuration.type == LedgerWidgetType.MONTH_CONSUMPTION) {
        val context = LocalContext.current
        val comparison = runCatching {
            Math.subtractExact(book.monthConsumptionBaseMinor, book.previousMonthConsumptionBaseMinor)
        }.getOrNull()
        Text(
            context.getString(
                R.string.widget_previous_month_comparison,
                comparison?.let { formattedAmount(it, book.baseCurrency, configuration.revealAmounts) }
                    ?: context.getString(R.string.widget_no_data),
            ),
            style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp),
        )
    }
    if (configuration.type == LedgerWidgetType.CORE_NET_ASSETS) {
        val context = LocalContext.current
        val change = runCatching {
            Math.subtractExact(book.coreNetFinancialAssetsBaseMinor, book.previousCoreNetFinancialAssetsBaseMinor)
        }.getOrNull()
        Text(
            context.getString(
                R.string.widget_snapshot_change,
                change?.let { formattedAmount(it, book.baseCurrency, configuration.revealAmounts) }
                    ?: context.getString(R.string.widget_no_data),
            ),
            style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp),
        )
    }
}

@Composable
private fun Overview(bundle: app.ledger.finance.application.WidgetSnapshotBundle, reveal: Boolean, color: ColorProvider) {
    val snapshot = requireNotNull(bundle.book)
    val context = LocalContext.current
    val rows = listOf(
        R.string.widget_month_consumption to snapshot.monthConsumptionBaseMinor,
        R.string.widget_month_budget to snapshot.monthBudgetAvailableBaseMinor,
        R.string.widget_core_net_assets to snapshot.coreNetFinancialAssetsBaseMinor,
    )
    rows.forEach { (label, amount) ->
        Text(
            context.getString(
                R.string.widget_overview_line,
                context.getString(label),
                amount?.let { formattedAmount(it, snapshot.baseCurrency, reveal) } ?: context.getString(R.string.widget_no_data),
            ),
            style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp),
        )
    }
    bundle.creditAccounts.firstOrNull()?.let { credit ->
        Text(
            context.getString(
                R.string.widget_overview_line,
                context.getString(R.string.widget_credit_card),
                formattedAmount(credit.statementRemainingMinor ?: credit.debtMinor, credit.currency, reveal),
            ),
            style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.labelSizeSp.sp),
        )
    }
}

@Composable
private fun AmountText(minor: Long, currency: String, reveal: Boolean, color: ColorProvider) {
    Text(
        formattedAmount(minor, currency, reveal),
        style = TextStyle(color = color, fontSize = LedgerGlanceTokens.light.bodySizeSp.sp, fontWeight = FontWeight.Bold),
    )
}

private fun formattedAmount(minor: Long, code: String, reveal: Boolean): String {
    if (!reveal) return "••••"
    val currency = Currency.getInstance(code)
    val formatter = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply { this.currency = currency }
    val amount = BigDecimal.valueOf(minor).movePointLeft(currency.defaultFractionDigits.coerceAtLeast(0))
    return formatter.format(amount.setScale(currency.defaultFractionDigits.coerceAtLeast(0), RoundingMode.UNNECESSARY))
}

private fun goalProgress(balance: Long, target: Long): String = if (target <= 0L) {
    "0%"
} else {
    BigDecimal.valueOf(balance).multiply(BigDecimal.valueOf(100L))
        .divide(BigDecimal.valueOf(target), 0, RoundingMode.DOWN)
        .coerceIn(BigDecimal.ZERO, BigDecimal.valueOf(999L)).toPlainString() + "%"
}

private fun Int.toDisplayDate(): String = "%04d-%02d-%02d".format(this / 10_000, this / 100 % 100, this % 100)

private fun deepLinkIntent(context: Context, configuration: LedgerWidgetConfiguration): Intent {
    val uri = Uri.Builder().scheme("ledger").authority("widget")
    when (configuration.type) {
        LedgerWidgetType.QUICK_ENTRY -> uri.appendPath("quick")
            .appendPath(requireNotNull(configuration.quickTargetKind).name)
            .appendPath(requireNotNull(configuration.quickDirection).name)
            .appendPath(requireNotNull(configuration.selectedId).toString())
        LedgerWidgetType.MONTH_CONSUMPTION -> uri.appendPath("open").appendPath("ANA-003").appendPath("consumption-category-structure")
        LedgerWidgetType.MONTH_BUDGET, LedgerWidgetType.TODAY_AVAILABLE -> uri.appendPath("open").appendPath("BUD-001")
        LedgerWidgetType.ACCOUNT -> uri.appendPath("open").appendPath("ACC-005").appendPath(requireNotNull(configuration.selectedId).toString())
        LedgerWidgetType.CORE_NET_ASSETS -> uri.appendPath("open").appendPath("ACC-001")
        LedgerWidgetType.CREDIT_CARD -> uri.appendPath("open").appendPath("CRD-001").appendPath(requireNotNull(configuration.selectedId).toString())
        LedgerWidgetType.GOAL -> uri.appendPath("open").appendPath("GOL-003").appendPath(requireNotNull(configuration.selectedId).toString())
        LedgerWidgetType.FINANCIAL_OVERVIEW -> uri.appendPath("open").appendPath("ANA-001")
    }
    return Intent(Intent.ACTION_VIEW, uri.build())
        .setClassName(context.packageName, "app.ledger.app.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
