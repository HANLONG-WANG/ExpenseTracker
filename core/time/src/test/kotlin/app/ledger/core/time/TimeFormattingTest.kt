package app.ledger.core.time

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class TimeFormattingTest {
    private val formatter = LocaleLedgerDateTimeFormatter()

    @Test
    fun `formatted UI model exposes original zone when it differs from the default`() {
        val model = formatter.format(
            DateTimeFormatRequest(
                effectiveTime = EffectiveTime.fromInstant(
                    Instant.parse("2026-08-01T00:30:00Z"),
                    ZoneId.of("America/Los_Angeles"),
                ),
                defaultZoneId = ZoneId.of("Asia/Tokyo"),
                locale = Locale.SIMPLIFIED_CHINESE,
            ),
        )

        model.zoneSupplement shouldBe "America/Los_Angeles -07:00"
        model.formatted.isNotBlank() shouldBe true
        model.fullAccessibleText.isNotBlank() shouldBe true
    }

    @Test
    fun `month formatting is centralized for all three product locales`() {
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.JAPAN, Locale.US).forEach { locale ->
            val model = formatter.formatMonth(YearMonth.of(2026, 8), locale)
            model.formatted.contains("2026") shouldBe true
            model.fullAccessibleText shouldBe model.formatted
        }
    }
}
