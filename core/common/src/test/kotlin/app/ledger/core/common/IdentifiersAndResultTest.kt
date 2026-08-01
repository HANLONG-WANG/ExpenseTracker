package app.ledger.core.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class IdentifiersAndResultTest {
    @Test
    fun `stable ID is an immutable sixteen byte UUID value`() {
        val sourceBytes = ByteArray(StableId.BYTE_COUNT) { it.toByte() }
        val id = (StableId.fromBytes(sourceBytes) as DomainResult.Success).value

        sourceBytes[0] = 99
        val exposedBytes = id.bytes
        exposedBytes[1] = 99

        id.bytes.toList() shouldBe ByteArray(StableId.BYTE_COUNT) { it.toByte() }.toList()
        StableId.parse(id.toString()) shouldBe DomainResult.Success(id)
    }

    @Test
    fun `ID source is injected and deterministic in tests`() {
        val expected = UUID.fromString("018f67d2-2c93-7bde-8f31-4f627d135b9a")
        val source = UuidStableIdSource(UuidSource { expected })

        source.nextStableId().toUuid() shouldBe expected
        CommandId(source.nextStableId()).stableId.toUuid() shouldBe expected
        RevisionId(source.nextStableId()).stableId.toUuid() shouldBe expected
    }

    @Test
    fun `invalid IDs return typed failures`() {
        InternalId.of(0) shouldBe DomainResult.Failure(
            ValidationError("internalId", ValidationReason.MUST_BE_POSITIVE),
        )
        StableId.fromBytes(ByteArray(15)) shouldBe DomainResult.Failure(
            ValidationError("stableId", ValidationReason.INVALID_FORMAT),
        )
        StableId.parse("not-an-id") shouldBe DomainResult.Failure(
            ValidationError("stableId", ValidationReason.INVALID_FORMAT),
        )
    }

    @Test
    fun `domain result preserves typed failures through mapping`() {
        val success: DomainResult<Int> = DomainResult.Success(2)
        val failure: DomainResult<Int> = DomainResult.Failure(
            BudgetRuleError(BudgetRuleReason.CATEGORY_TOTAL_EXCEEDS_MONTH),
        )

        success.map { it * 3 } shouldBe DomainResult.Success(6)
        failure.map { it * 3 } shouldBe failure
        success.getOrNull() shouldBe 2
        failure.errorOrNull() shouldBe BudgetRuleError(BudgetRuleReason.CATEGORY_TOTAL_EXCEEDS_MONTH)
    }
}
