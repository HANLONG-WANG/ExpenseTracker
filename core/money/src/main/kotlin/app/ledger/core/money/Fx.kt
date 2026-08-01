package app.ledger.core.money

import app.ledger.core.common.CurrencyError
import app.ledger.core.common.CurrencyErrorReason
import app.ledger.core.common.DomainResult
import app.ledger.core.common.ValidationError
import app.ledger.core.common.ValidationReason
import app.ledger.core.common.flatMap
import app.ledger.core.common.map
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

@JvmInline
value class FxProvider private constructor(val value: String) {
    companion object {
        fun of(value: String): DomainResult<FxProvider> = if (PROVIDER.matches(value)) {
            DomainResult.Success(FxProvider(value))
        } else {
            DomainResult.Failure(ValidationError("fxProvider", ValidationReason.INVALID_FORMAT))
        }

        private val PROVIDER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

enum class FxRateSource {
    ONLINE_LATEST,
    CACHE,
    MANUAL,
    IMPLIED_FROM_ACTUAL_AMOUNTS,
    OFFICIAL_SETTLEMENT,
    HISTORICAL_FALLBACK,
}

data class FxEvidenceInput(
    val sourceCurrency: CurrencyCode,
    val targetCurrency: CurrencyCode,
    val rate: BigDecimal,
    val provider: FxProvider,
    val quotedAt: Instant?,
    val fetchedAt: Instant?,
    val source: FxRateSource,
    val manuallyOverridden: Boolean,
)

@ConsistentCopyVisibility
data class FxEvidence private constructor(
    val sourceCurrency: CurrencyCode,
    val targetCurrency: CurrencyCode,
    val rate: BigDecimal,
    val provider: FxProvider,
    val quotedAt: Instant?,
    val fetchedAt: Instant?,
    val source: FxRateSource,
    val manuallyOverridden: Boolean,
) {
    companion object {
        fun create(input: FxEvidenceInput): DomainResult<FxEvidence> {
            if (input.rate.signum() <= 0) {
                return DomainResult.Failure(
                    CurrencyError(CurrencyErrorReason.INVALID_RATE, input.targetCurrency.value),
                )
            }
            return DomainResult.Success(
                FxEvidence(
                    sourceCurrency = input.sourceCurrency,
                    targetCurrency = input.targetCurrency,
                    rate = input.rate.stripTrailingZeros(),
                    provider = input.provider,
                    quotedAt = input.quotedAt,
                    fetchedAt = input.fetchedAt,
                    source = input.source,
                    manuallyOverridden = input.manuallyOverridden,
                ),
            )
        }
    }
}

data class FxConversion(
    val source: Money,
    val target: Money,
    val evidence: FxEvidence,
)

class FxConverter(
    private val mathContext: MathContext = MathContext(FX_INTERMEDIATE_PRECISION, RoundingMode.HALF_EVEN),
    private val outputRoundingMode: RoundingMode = RoundingMode.HALF_EVEN,
) {
    fun convert(
        source: Money,
        targetMetadata: CurrencyMetadata,
        evidence: FxEvidence,
        sourceMetadata: CurrencyMetadata,
    ): DomainResult<FxConversion> = if (
        source.currency != evidence.sourceCurrency ||
        targetMetadata.code != evidence.targetCurrency ||
        sourceMetadata.code != source.currency
    ) {
        DomainResult.Failure(
            CurrencyError(CurrencyErrorReason.CURRENCY_MISMATCH, source.currency.value),
        )
    } else {
        source.toMajor(sourceMetadata).flatMap { sourceMajor ->
            val targetMajor = sourceMajor.multiply(evidence.rate, mathContext)
            Money.fromMajor(targetMajor, targetMetadata, outputRoundingMode).map { target ->
                FxConversion(source, target, evidence)
            }
        }
    }
}

private const val FX_INTERMEDIATE_PRECISION = 34
