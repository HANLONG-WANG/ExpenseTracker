package quality.fixture

data class MoneyEnvelope(val amount: Long)

data class EditRoute(val payload: MoneyEnvelope)
