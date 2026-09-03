package app.ledger.app

/** Debug/device-test hook only; no samples are retained, logged, or exported in production. */
internal object P37ComposeRecompositionProbe {
    internal enum class Scope { READY_SHELL, ROUTE }

    @Volatile
    private var observer: ((Scope, String) -> Unit)? = null

    internal fun installForTest(callback: (Scope, String) -> Unit) {
        observer = callback
    }

    internal fun clearForTest() {
        observer = null
    }

    internal fun record(scope: Scope, screenId: String) {
        observer?.invoke(scope, screenId)
    }
}
