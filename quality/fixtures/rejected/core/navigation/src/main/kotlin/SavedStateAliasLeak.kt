package quality.fixture

data class SavedMoneyEnvelope(val amount: Long)

fun retainSensitiveState(handle: SavedStateHandle, envelope: SavedMoneyEnvelope) {
    val store = handle
    store["draft"] = envelope
}
