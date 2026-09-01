@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package app.ledger.analytics.data

import app.ledger.analytics.domain.ComparisonMode
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.FilterExpression
import app.ledger.analytics.domain.FilterField
import app.ledger.analytics.domain.FilterOperator
import app.ledger.analytics.domain.FilterValue
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.QuerySource
import app.ledger.analytics.domain.ReportSort
import app.ledger.analytics.domain.ReportSpec
import app.ledger.analytics.domain.SortDirection
import app.ledger.analytics.domain.TimeGranularity
import app.ledger.core.database.AnalyticsProjectionEngine
import java.time.LocalDate

internal data class CompiledMeasure(
    val measure: Measure,
    val valueColumn: String,
    val secondaryColumn: String? = null,
    val currencyColumn: String? = null,
)

internal data class CompiledReportQuery(
    val sql: String,
    val arguments: Array<out Any?>,
    val dimensions: List<Dimension>,
    val measures: List<CompiledMeasure>,
)

internal object ReportQueryPlanner {
    private val rollupMeasures = setOf(
        Measure.INCOME,
        Measure.EXPENSE,
        Measure.CONSUMPTION,
        Measure.NON_CONSUMPTION_EXPENSE,
        Measure.CONTRA_EXPENSE,
        Measure.NET_CASH_FLOW,
        Measure.LOAN_INTEREST,
        Measure.TRANSACTION_COUNT,
        Measure.SAVINGS_RATE,
    )

    fun select(spec: ReportSpec): QuerySource {
        val rollupDimensions = spec.dimensions.all { it == Dimension.DATE }
        if (spec.measures.all { it in rollupMeasures } && rollupDimensions) {
            return if (spec.granularity in setOf(TimeGranularity.MONTH, TimeGranularity.QUARTER, TimeGranularity.YEAR)) {
                QuerySource.MONTHLY_ROLLUP
            } else {
                QuerySource.DAILY_ROLLUP
            }
        }
        return if (spec.measures.any { it in JOURNAL_MEASURES }) QuerySource.JOURNAL_POSTINGS else QuerySource.ECONOMIC_EFFECTS
    }

    private val JOURNAL_MEASURES = setOf(
        Measure.NET_CASH_FLOW,
        Measure.ACCOUNT_BALANCE,
        Measure.CORE_NET_FINANCIAL_ASSETS,
        Measure.ADJUSTED_NET_FINANCIAL_POSITION,
        Measure.FX_REVALUATION,
        Measure.CREDIT_DEBT,
        Measure.CREDIT_AVAILABLE_LIMIT,
        Measure.INSTALLMENT_PRINCIPAL,
        Measure.INSTALLMENT_FEES,
        Measure.LOAN_PRINCIPAL,
        Measure.SETTLEMENT_POSITION,
    )
}

internal object ReportSqlCompiler {
    fun compile(planSource: QuerySource, spec: ReportSpec, start: LocalDate, endInclusive: LocalDate): CompiledReportQuery {
        require(endInclusive >= start)
        validate(spec)
        return if (planSource in setOf(QuerySource.DAILY_ROLLUP, QuerySource.MONTHLY_ROLLUP)) {
            compileTotalRollup(planSource, spec, start, endInclusive)
        } else {
            compileEvents(spec, start, endInclusive)
        }
    }

    private fun compileTotalRollup(
        source: QuerySource,
        spec: ReportSpec,
        start: LocalDate,
        endInclusive: LocalDate,
    ): CompiledReportQuery {
        val monthly = source == QuerySource.MONTHLY_ROLLUP
        val table = if (monthly) "analytics_monthly_total" else "analytics_daily_total"
        val periodColumn = if (monthly) "year_month" else "local_date"
        val startKey = if (monthly) start.year * 100 + start.monthValue else start.storageKey()
        val endKey = if (monthly) endInclusive.year * 100 + endInclusive.monthValue else endInclusive.storageKey()
        val args = mutableListOf<Any?>(startKey, endKey)
        val select = mutableListOf<String>()
        if (Dimension.DATE in spec.dimensions) {
            select += if (monthly) "$periodColumn*100+1 AS dimension_0" else dateBucket(periodColumn, spec.granularity) + " AS dimension_0"
        }
        val measures = mutableListOf<CompiledMeasure>()
        spec.measures.forEachIndexed { index, measure ->
            if (measure == Measure.SAVINGS_RATE) {
                select += "SUM(CASE WHEN metric=${AnalyticsProjectionEngine.INCOME_METRIC} THEN amount_base_minor ELSE 0 END) AS measure_${index}_income"
                select += "SUM(CASE WHEN metric=${AnalyticsProjectionEngine.EXPENSE_METRIC} THEN amount_base_minor ELSE 0 END) AS measure_${index}_expense"
                measures += CompiledMeasure(measure, "measure_${index}_income", "measure_${index}_expense")
            } else {
                val metric = rollupMetric(measure)
                select += "SUM(CASE WHEN metric=$metric THEN amount_base_minor ELSE 0 END) AS measure_$index"
                measures += CompiledMeasure(measure, "measure_$index")
            }
        }
        val group = if (Dimension.DATE in spec.dimensions) " GROUP BY dimension_0" else ""
        val order = compileSort(spec, spec.dimensions, spec.measures)
        val sql = "SELECT ${select.joinToString()} FROM $table WHERE $periodColumn BETWEEN ? AND ?$group$order LIMIT $MAX_REPORT_ROWS"
        return CompiledReportQuery(sql, args.toTypedArray(), spec.dimensions, measures)
    }

    private fun compileEvents(spec: ReportSpec, start: LocalDate, endInclusive: LocalDate): CompiledReportQuery {
        val args = mutableListOf<Any?>(endInclusive.storageKey(), start.storageKey(), endInclusive.storageKey())
        val select = mutableListOf<String>()
        spec.dimensions.forEachIndexed { index, dimension ->
            select += "${dimensionExpression(dimension, spec.granularity)} AS dimension_$index"
        }
        val measures = mutableListOf<CompiledMeasure>()
        spec.measures.forEachIndexed { index, measure ->
            val ratioComponents = ratioComponents(measure)
            if (ratioComponents != null) {
                select += "SUM(CASE WHEN measure=${ratioComponents.first.ordinal} THEN minor_value ELSE 0 END) AS measure_${index}_numerator"
                select += "SUM(CASE WHEN measure=${ratioComponents.second.ordinal} THEN minor_value ELSE 0 END) AS measure_${index}_denominator"
                measures += CompiledMeasure(measure, "measure_${index}_numerator", "measure_${index}_denominator")
            } else {
                select += "SUM(CASE WHEN measure=${measure.ordinal} THEN minor_value ELSE 0 END) AS measure_$index"
                val currencyColumn = if (measure in NATIVE_CURRENCY_MEASURES) "measure_${index}_currency" else null
                if (currencyColumn != null) {
                    select += "MAX(CASE WHEN measure=${measure.ordinal} THEN currency_code END) AS $currencyColumn"
                }
                measures += CompiledMeasure(measure, "measure_$index", currencyColumn = currencyColumn)
            }
        }
        val filter = compileFilter(spec.filters, args)
        val selectedMeasures = spec.measures.flatMap { measure ->
            ratioComponents(measure)?.toList() ?: listOf(measure)
        }.distinct()
        val where = "local_date BETWEEN ? AND ? AND measure IN (${selectedMeasures.joinToString { it.ordinal.toString() }})" +
            if (filter.isBlank()) "" else " AND ($filter)"
        val group = if (spec.dimensions.isEmpty()) "" else " GROUP BY " + spec.dimensions.indices.joinToString { "dimension_$it" }
        val order = compileSort(spec, spec.dimensions, spec.measures)
        val sql = EVENT_CTE + " SELECT ${select.joinToString()} FROM events WHERE $where$group$order LIMIT $MAX_REPORT_ROWS"
        return CompiledReportQuery(sql, args.toTypedArray(), spec.dimensions, measures)
    }

    private fun compileFilter(expression: FilterExpression, arguments: MutableList<Any?>): String = when (expression) {
        FilterExpression.All -> ""
        is FilterExpression.Not -> "NOT (${compileFilter(expression.operand, arguments)})"
        is FilterExpression.And -> expression.operands.joinToString(" AND ", "(", ")") { compileFilter(it, arguments) }
        is FilterExpression.Or -> expression.operands.joinToString(" OR ", "(", ")") { compileFilter(it, arguments) }
        is FilterExpression.Predicate -> compilePredicate(expression, arguments)
    }

    private fun compilePredicate(predicate: FilterExpression.Predicate, arguments: MutableList<Any?>): String {
        val column = FILTER_COLUMNS[predicate.field] ?: throw IllegalArgumentException("unsupported filter field")
        val values = boundValues(predicate.field, predicate.value)
        return when (predicate.operator) {
            FilterOperator.EQUALS, FilterOperator.NOT_EQUALS -> {
                require(values.size == 1)
                arguments += values.single()
                "$column ${if (predicate.operator == FilterOperator.EQUALS) "=" else "<>"} ?"
            }
            FilterOperator.IN, FilterOperator.NOT_IN -> {
                require(values.isNotEmpty())
                arguments.addAll(values)
                "$column ${if (predicate.operator == FilterOperator.IN) "IN" else "NOT IN"} (${values.joinToString { "?" }})"
            }
            FilterOperator.BETWEEN -> {
                require(values.size == 2)
                arguments.addAll(values)
                "$column BETWEEN ? AND ?"
            }
            FilterOperator.GREATER_THAN_OR_EQUAL, FilterOperator.LESS_THAN_OR_EQUAL -> {
                require(values.size == 1)
                arguments += values.single()
                "$column ${if (predicate.operator == FilterOperator.GREATER_THAN_OR_EQUAL) ">=" else "<="} ?"
            }
            FilterOperator.IS_TRUE, FilterOperator.IS_FALSE -> {
                require(predicate.value is FilterValue.Flag)
                "$column = ${if (predicate.operator == FilterOperator.IS_TRUE) 1 else 0}"
            }
        }
    }

    private fun boundValues(field: FilterField, value: FilterValue): List<Any?> = when (value) {
        is FilterValue.DateRange -> listOf(value.start.storageKey(), value.endInclusive.storageKey())
        is FilterValue.AmountRange -> listOfNotNull(value.minimumMinor, value.maximumMinor)
        is FilterValue.Accounts -> value.values.map { it.value.bytes }
        is FilterValue.Categories -> value.values.map { it.value.bytes }
        is FilterValue.Merchants -> value.values.map { it.value.bytes }
        is FilterValue.Projects -> value.values.map { it.value.bytes }
        is FilterValue.Places -> value.values.map { it.value.bytes }
        is FilterValue.Participants -> value.values.map { it.value.bytes }
        is FilterValue.Currencies -> value.values.map { it.value }
        is FilterValue.ClosedKeys -> value.values.map { key -> closedKey(field, key) }
        is FilterValue.Flag -> listOf(if (value.value) 1 else 0)
    }

    private fun closedKey(field: FilterField, key: String): Any = when (field) {
        FilterField.TRANSACTION_KIND -> TRANSACTION_KINDS[key] ?: throw IllegalArgumentException("unknown transaction kind")
        FilterField.ECONOMIC_NATURE -> ECONOMIC_NATURES[key] ?: throw IllegalArgumentException("unknown economic nature")
        FilterField.LIFECYCLE_STATE -> LIFECYCLES[key] ?: throw IllegalArgumentException("unknown lifecycle")
        FilterField.TRANSACTION_SOURCE -> key.takeIf { SOURCE_KEY.matches(it) } ?: throw IllegalArgumentException("invalid source key")
        else -> key.takeIf { CLOSED_KEY.matches(it) } ?: throw IllegalArgumentException("invalid closed key")
    }

    private fun compileSort(spec: ReportSpec, dimensions: List<Dimension>, measures: List<Measure>): String {
        if (spec.sorting.isEmpty()) return if (dimensions.isEmpty()) "" else " ORDER BY dimension_0"
        val entries = spec.sorting.map { sort ->
            val alias = when (sort) {
                is ReportSort.ByDimension -> "dimension_${dimensions.indexOf(sort.dimension).also { require(it >= 0) }}"
                is ReportSort.ByMeasure -> "measure_${measures.indexOf(sort.measure).also { require(it >= 0) }}"
            }
            "$alias ${if (sort.direction == SortDirection.ASCENDING) "ASC" else "DESC"}"
        }
        return " ORDER BY ${entries.joinToString()}"
    }

    private fun validate(spec: ReportSpec) {
        spec.sorting.forEach { sort ->
            when (sort) {
                is ReportSort.ByDimension -> require(sort.dimension in spec.dimensions)
                is ReportSort.ByMeasure -> require(sort.measure in spec.measures)
            }
        }
        require(spec.comparison == null || spec.comparison in ComparisonMode.entries)
    }

    private fun dimensionExpression(dimension: Dimension, granularity: TimeGranularity): String = when (dimension) {
        Dimension.DATE -> dateBucket("local_date", granularity)
        Dimension.CATEGORY -> "category_uid"
        Dimension.MERCHANT -> "merchant_uid"
        Dimension.ACCOUNT -> "account_uid"
        Dimension.CARD -> "card_uid"
        Dimension.PROJECT -> "project_uid"
        Dimension.GOAL -> "goal_uid"
        Dimension.CURRENCY -> "currency_code"
        Dimension.PLACE -> "place_uid"
        Dimension.SETTLEMENT_ACTIVITY -> "settlement_activity_uid"
        Dimension.PARTICIPANT -> "participant_uid"
        Dimension.TRANSACTION_SOURCE -> "source_key"
    }

    private fun dateBucket(column: String, granularity: TimeGranularity): String = when (granularity) {
        TimeGranularity.DAY -> column
        TimeGranularity.WEEK ->
            "CAST(replace(date(substr($column,1,4)||'-'||substr($column,5,2)||'-'||substr($column,7,2)," +
                " '-'||((CAST(strftime('%w',substr($column,1,4)||'-'||substr($column,5,2)||'-'||substr($column,7,2)) AS INTEGER)+6)%7)||' days'),'-','') AS INTEGER)"
        TimeGranularity.MONTH -> "($column/100)*100+1"
        TimeGranularity.QUARTER -> "($column/10000)*10000+((((($column/100)%100)-1)/3)*3)+1)*100+1"
        TimeGranularity.YEAR -> "($column/10000)*10000+101"
    }

    private fun rollupMetric(measure: Measure): Int = when (measure) {
        Measure.INCOME -> AnalyticsProjectionEngine.INCOME_METRIC
        Measure.EXPENSE -> AnalyticsProjectionEngine.EXPENSE_METRIC
        Measure.CONSUMPTION -> AnalyticsProjectionEngine.CONSUMPTION_METRIC
        Measure.NON_CONSUMPTION_EXPENSE -> AnalyticsProjectionEngine.NON_CONSUMPTION_EXPENSE_METRIC
        Measure.CONTRA_EXPENSE -> AnalyticsProjectionEngine.CONTRA_EXPENSE_METRIC
        Measure.NET_CASH_FLOW -> AnalyticsProjectionEngine.NET_CASH_FLOW_METRIC
        Measure.LOAN_INTEREST -> AnalyticsProjectionEngine.LOAN_INTEREST_METRIC
        Measure.TRANSACTION_COUNT -> AnalyticsProjectionEngine.TRANSACTION_COUNT_METRIC
        else -> throw IllegalArgumentException("measure has no total rollup")
    }

    private fun ratioComponents(measure: Measure): Pair<Measure, Measure>? = when (measure) {
        Measure.SAVINGS_RATE -> Measure.INCOME to Measure.EXPENSE
        Measure.BUDGET_USAGE -> Measure.BUDGET_USED to Measure.BUDGET_AVAILABLE
        else -> null
    }

    private fun LocalDate.storageKey(): Int = year * 10_000 + monthValue * 100 + dayOfMonth

    private val FILTER_COLUMNS = mapOf(
        FilterField.OCCURRED_DATE to "local_date",
        FilterField.ACCRUAL_DATE to "local_date",
        FilterField.TRANSACTION_KIND to "transaction_kind",
        FilterField.ACCOUNT to "account_uid",
        FilterField.CARD to "card_uid",
        FilterField.CATEGORY to "category_uid",
        FilterField.MERCHANT to "merchant_uid",
        FilterField.PROJECT to "project_uid",
        FilterField.GOAL to "goal_uid",
        FilterField.SETTLEMENT_ACTIVITY to "settlement_activity_uid",
        FilterField.PARTICIPANT to "participant_uid",
        FilterField.CURRENCY to "currency_code",
        FilterField.BASE_AMOUNT to "ABS(minor_value)",
        FilterField.PLACE to "place_uid",
        FilterField.HAS_ATTACHMENT to "has_attachment",
        FilterField.IS_REFUND to "is_refund",
        FilterField.HAS_INSTALLMENT to "has_installment",
        FilterField.TRANSACTION_SOURCE to "source_key",
        FilterField.ECONOMIC_NATURE to "economic_nature",
        FilterField.LIFECYCLE_STATE to "lifecycle_state",
    )
    private val TRANSACTION_KINDS = listOf(
        "EXPENSE", "INCOME", "TRANSFER", "REFUND", "CREDIT_PAYMENT", "LOAN_DISBURSEMENT", "LOAN_PAYMENT",
        "BALANCE_ADJUSTMENT", "FX_EXCHANGE", "SETTLEMENT_PAYMENT", "OPENING_BALANCE",
    ).withIndex().associate { it.value to it.index }
    private val ECONOMIC_NATURES = listOf("INCOME", "EXPENSE", "CONTRA_EXPENSE", "EQUITY").withIndex().associate { it.value to it.index }
    private val LIFECYCLES = mapOf("ACTIVE" to 0, "TRASHED" to 1)
    private val SOURCE_KEY = Regex("[A-Z][A-Z0-9_]{0,31}")
    private val CLOSED_KEY = Regex("[A-Za-z0-9_.:-]{1,64}")
    private const val MAX_REPORT_ROWS = 500
    private val NATIVE_CURRENCY_MEASURES = setOf(
        Measure.GOAL_BALANCE,
        Measure.ACCOUNT_BALANCE,
        Measure.CREDIT_DEBT,
        Measure.CREDIT_AVAILABLE_LIMIT,
        Measure.INSTALLMENT_PRINCIPAL,
        Measure.INSTALLMENT_FEES,
        Measure.SETTLEMENT_POSITION,
    )

    private val EVENT_CTE = """
        WITH params(report_end_date) AS (SELECT ?),
        financial_position AS (
          SELECT
            COALESCE(SUM(CASE WHEN ua.type IN (0,1) THEN
              CASE WHEN abc.currency_code=b.base_currency THEN COALESCE(abc.normal_balance_minor,0)
                   ELSE COALESCE(avc.current_base_value_minor,0) END
            ELSE -CASE WHEN abc.currency_code=b.base_currency THEN ABS(COALESCE(abc.normal_balance_minor,0))
                       ELSE ABS(COALESCE(avc.current_base_value_minor,0)) END END),0) AS core_value,
            COALESCE((SELECT SUM(spp.net_position_minor) FROM settlement_position_projection spp
              JOIN participant p ON p.id=spp.participant_id WHERE p.is_self=1),0) AS settlement_value,
            b.base_currency
          FROM book b JOIN user_account ua ON 1=1
            LEFT JOIN account_balance_current abc ON abc.account_id=ua.id
            LEFT JOIN account_valuation_current avc ON avc.account_id=ua.id
          WHERE b.id=1
        ),
        economic_events AS (
          SELECT ee.accrual_local_date AS local_date,m.measure,
            c.uid AS category_uid,mer.uid AS merchant_uid,ua.uid AS account_uid,pc.uid AS card_uid,
            pr.uid AS project_uid,g.uid AS goal_uid,ctp.input_currency AS currency_code,pl.uid AS place_uid,
            sa.uid AS settlement_activity_uid,part.uid AS participant_uid,CAST(tr.source_type AS TEXT) AS source_key,
            bt.uid AS transaction_uid,tr.occurred_at,bt.kind AS transaction_kind,bt.lifecycle_state,
            ctp.has_attachment,ctp.is_refund,ctp.has_installment,ee.nature AS economic_nature,
            CASE m.measure
              WHEN 0 THEN ee.polarity*ee.base_amount_minor
              WHEN 1 THEN CASE ee.nature WHEN 1 THEN ee.polarity*ee.base_amount_minor ELSE -ee.polarity*ee.base_amount_minor END
              WHEN 2 THEN CASE ee.nature WHEN 1 THEN ee.polarity*ee.base_amount_minor ELSE -ee.polarity*ee.base_amount_minor END
              WHEN 3 THEN CASE ee.nature WHEN 1 THEN ee.polarity*ee.base_amount_minor ELSE -ee.polarity*ee.base_amount_minor END
              ELSE ee.polarity*ee.base_amount_minor END AS minor_value
          FROM economic_effect ee JOIN transaction_revision tr ON tr.id=ee.source_revision_id
            JOIN business_transaction bt ON bt.id=tr.transaction_id
            LEFT JOIN current_transaction_projection ctp ON ctp.transaction_id=bt.id
            LEFT JOIN category c ON c.id=ee.category_id LEFT JOIN merchant mer ON mer.id=ee.merchant_id
            LEFT JOIN project pr ON pr.id=ee.project_id LEFT JOIN goal g ON g.id=tr.goal_id
            LEFT JOIN location_record lr ON lr.id=tr.location_record_id LEFT JOIN place pl ON pl.id=lr.place_id
            LEFT JOIN expense_revision_detail ex ON ex.revision_id=tr.id LEFT JOIN income_revision_detail inc ON inc.revision_id=tr.id
            LEFT JOIN user_account ua ON ua.id=COALESCE(ex.payer_account_id,inc.receiving_account_id,ctp.primary_account_id)
            LEFT JOIN payment_card pc ON pc.id=COALESCE(ex.payer_card_id,ctp.card_id)
            LEFT JOIN settlement_activity sa ON sa.id=COALESCE(ex.settlement_activity_id,ctp.settlement_activity_id)
            LEFT JOIN participant part ON part.id=COALESCE(ex.payer_participant_id,ctp.payer_participant_id)
            JOIN (SELECT 0 AS measure UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 19) m
              ON (m.measure=0 AND ee.nature=0)
              OR (m.measure=1 AND ee.nature IN (1,2))
              OR (m.measure=2 AND ee.nature IN (1,2) AND ee.is_consumption=1)
              OR (m.measure=3 AND ee.nature IN (1,2) AND ee.is_consumption=0)
              OR (m.measure=4 AND ee.nature=2)
              OR (m.measure=19 AND ee.nature=1 AND ee.component=1)
        ),
        other_events AS (
          SELECT je.local_date,5 AS measure,NULL,NULL,ua.uid,NULL,NULL,NULL,ua.currency_code,NULL,NULL,NULL,
            CAST(tr.source_type AS TEXT),bt.uid,tr.occurred_at,bt.kind,bt.lifecycle_state,ctp.has_attachment,ctp.is_refund,
            ctp.has_installment,NULL,
            CASE WHEN p.side=la.normal_side THEN p.base_amount_minor ELSE -p.base_amount_minor END
          FROM posting p JOIN journal_entry je ON je.id=p.journal_entry_id JOIN transaction_revision tr ON tr.id=je.source_revision_id
            JOIN business_transaction bt ON bt.id=tr.transaction_id JOIN ledger_account la ON la.id=p.ledger_account_id
            JOIN user_account ua ON ua.ledger_account_id=la.id AND ua.type IN (0,1)
            LEFT JOIN current_transaction_projection ctp ON ctp.transaction_id=bt.id
          UNION ALL
          SELECT tr.local_date,7,NULL,NULL,NULL,NULL,NULL,NULL,b.base_currency,NULL,NULL,NULL,CAST(tr.source_type AS TEXT),
            bt.uid,tr.occurred_at,bt.kind,bt.lifecycle_state,ctp.has_attachment,ctp.is_refund,ctp.has_installment,NULL,
            CASE be.kind WHEN 0 THEN be.polarity*be.base_amount_minor ELSE -be.polarity*be.base_amount_minor END
          FROM budget_effect be JOIN transaction_revision tr ON tr.id=be.source_revision_id JOIN business_transaction bt ON bt.id=tr.transaction_id
            JOIN book b ON b.id=1 LEFT JOIN current_transaction_projection ctp ON ctp.transaction_id=bt.id
          UNION ALL
          SELECT bup.year_month*100+1,m.measure,
            CASE WHEN bup.category_id IS NULL THEN zeroblob(16) ELSE c.uid END,NULL,NULL,NULL,NULL,NULL,b.base_currency,NULL,NULL,NULL,
            'CURRENT',NULL,0,NULL,0,0,0,0,NULL,
            CASE m.measure
              WHEN ${Measure.BUDGET_BASE.ordinal} THEN bup.base_budget_minor
              WHEN ${Measure.BUDGET_ROLLOVER.ordinal} THEN bup.rollover_minor
              WHEN ${Measure.BUDGET_ADJUSTMENT.ordinal} THEN bup.adjustment_minor
              WHEN ${Measure.BUDGET_USED.ordinal} THEN bup.used_minor
              ELSE bup.base_budget_minor+bup.rollover_minor+bup.adjustment_minor
            END
          FROM budget_usage_projection bup LEFT JOIN category c ON c.id=bup.category_id JOIN book b ON b.id=1
            JOIN (SELECT ${Measure.BUDGET_BASE.ordinal} AS measure
              UNION ALL SELECT ${Measure.BUDGET_ROLLOVER.ordinal}
              UNION ALL SELECT ${Measure.BUDGET_ADJUSTMENT.ordinal}
              UNION ALL SELECT ${Measure.BUDGET_USED.ordinal}
              UNION ALL SELECT ${Measure.BUDGET_AVAILABLE.ordinal}) m
          UNION ALL
          SELECT tr.local_date,8,NULL,NULL,NULL,NULL,pr.uid,NULL,b.base_currency,NULL,NULL,NULL,CAST(tr.source_type AS TEXT),
            bt.uid,tr.occurred_at,bt.kind,bt.lifecycle_state,ctp.has_attachment,ctp.is_refund,ctp.has_installment,NULL,
            CASE pe.kind WHEN 0 THEN pe.polarity*pe.base_amount_minor ELSE -pe.polarity*pe.base_amount_minor END
          FROM project_effect pe JOIN transaction_revision tr ON tr.id=pe.source_revision_id JOIN business_transaction bt ON bt.id=tr.transaction_id
            JOIN project pr ON pr.id=pe.project_id JOIN book b ON b.id=1 LEFT JOIN current_transaction_projection ctp ON ctp.transaction_id=bt.id
          UNION ALL
          SELECT params.report_end_date,9,NULL,NULL,ua.uid,NULL,NULL,g.uid,gbp.currency_code,NULL,NULL,NULL,'CURRENT',NULL,0,NULL,0,0,0,0,NULL,gbp.balance_minor
          FROM goal_balance_projection gbp JOIN goal g ON g.id=gbp.goal_id JOIN user_account ua ON ua.id=g.account_id,params
          UNION ALL
          SELECT params.report_end_date,10,NULL,NULL,ua.uid,NULL,NULL,NULL,abc.currency_code,NULL,NULL,NULL,'CURRENT',NULL,0,NULL,0,0,0,0,NULL,abc.normal_balance_minor
          FROM account_balance_current abc JOIN user_account ua ON ua.id=abc.account_id,params
          UNION ALL
          SELECT params.report_end_date,m.measure,NULL,NULL,NULL,NULL,NULL,NULL,fp.base_currency,NULL,NULL,NULL,'CURRENT',NULL,0,NULL,0,0,0,0,NULL,
            CASE m.measure WHEN 11 THEN fp.core_value ELSE fp.core_value+fp.settlement_value END
          FROM financial_position fp JOIN (SELECT 11 AS measure UNION ALL SELECT 12) m,params
          UNION ALL
          SELECT params.report_end_date,13,NULL,NULL,ua.uid,NULL,NULL,NULL,ua.currency_code,NULL,NULL,NULL,'CURRENT',NULL,0,NULL,0,0,0,0,NULL,
            av.current_base_value_minor-COALESCE((
              SELECT SUM(CASE WHEN p.side=la.normal_side THEN p.base_amount_minor ELSE -p.base_amount_minor END)
              FROM posting p JOIN ledger_account la ON la.id=p.ledger_account_id WHERE la.id=ua.ledger_account_id
            ),0)
          FROM account_valuation_current av JOIN user_account ua ON ua.id=av.account_id
            JOIN book b ON b.id=1,params WHERE ua.currency_code<>b.base_currency
          UNION ALL
          SELECT params.report_end_date,m.measure,NULL,NULL,ua.uid,NULL,NULL,NULL,cap.currency_code,NULL,NULL,NULL,'CURRENT',NULL,0,NULL,0,0,0,0,NULL,
            CASE m.measure WHEN 14 THEN cap.debt_minor ELSE COALESCE(cap.available_limit_minor,0) END
          FROM credit_account_projection cap JOIN user_account ua ON ua.id=cap.account_id
            JOIN (SELECT 14 AS measure UNION ALL SELECT 15) m,params
          UNION ALL
          SELECT params.report_end_date,m.measure,NULL,NULL,NULL,NULL,NULL,NULL,ip.currency_code,NULL,NULL,NULL,'CURRENT',NULL,0,NULL,0,0,0,0,NULL,
            CASE m.measure WHEN 16 THEN ipp.unposted_committed_principal_minor ELSE ipp.fees_minor END
          FROM installment_progress_projection ipp JOIN installment_plan ip ON ip.id=ipp.plan_id
            JOIN (SELECT 16 AS measure UNION ALL SELECT 17) m,params
          UNION ALL
          SELECT params.report_end_date,18,NULL,NULL,NULL,NULL,NULL,NULL,b.base_currency,NULL,NULL,NULL,'CURRENT',NULL,0,NULL,0,0,0,0,NULL,
            CASE WHEN la.currency_code=b.base_currency THEN lpp.remaining_principal_minor ELSE av.current_base_value_minor END
          FROM loan_progress_projection lpp JOIN loan_tranche ltr ON ltr.id=lpp.tranche_id
            JOIN ledger_account la ON la.id=ltr.ledger_account_id JOIN book b ON b.id=1
            LEFT JOIN user_account ua ON ua.ledger_account_id=la.id LEFT JOIN account_valuation_current av ON av.account_id=ua.id,params
          WHERE la.currency_code=b.base_currency OR av.current_base_value_minor IS NOT NULL
          UNION ALL
          SELECT params.report_end_date,20,NULL,NULL,NULL,NULL,NULL,NULL,sa.settlement_currency,NULL,sa.uid,p.uid,'CURRENT',NULL,0,NULL,0,0,0,0,NULL,spp.net_position_minor
          FROM settlement_position_projection spp JOIN settlement_activity sa ON sa.id=spp.activity_id JOIN participant p ON p.id=spp.participant_id,params
          UNION ALL
          SELECT ctp.local_date,21,c.uid,mer.uid,ua.uid,pc.uid,pr.uid,g.uid,ctp.input_currency,pl.uid,sa.uid,part.uid,
            CAST(ctp.source_type AS TEXT),ctp.transaction_uid,ctp.occurred_at,ctp.kind,ctp.state,ctp.has_attachment,ctp.is_refund,ctp.has_installment,NULL,1
          FROM current_transaction_projection ctp LEFT JOIN category c ON c.id=ctp.category_id LEFT JOIN merchant mer ON mer.id=ctp.merchant_id
            LEFT JOIN user_account ua ON ua.id=ctp.primary_account_id LEFT JOIN payment_card pc ON pc.id=ctp.card_id
            LEFT JOIN project pr ON pr.id=ctp.project_id LEFT JOIN goal g ON g.id=ctp.goal_id
            LEFT JOIN transaction_revision tr ON tr.id=ctp.current_revision_id LEFT JOIN location_record lr ON lr.id=tr.location_record_id
            LEFT JOIN place pl ON pl.id=lr.place_id LEFT JOIN settlement_activity sa ON sa.id=ctp.settlement_activity_id
            LEFT JOIN participant part ON part.id=ctp.payer_participant_id WHERE ctp.state=0
        ),
        events AS (SELECT * FROM economic_events UNION ALL SELECT * FROM other_events)
    """.trimIndent()
}
