package app.ledger.app

/** Makes launcher and lifecycle permission callbacks safe when Android delivers both for one grant. */
internal object LocationPermissionCompletionPolicy {
    fun shouldHandle(currentScreenId: String): Boolean = currentScreenId == PERMISSION_SCREEN_ID

    private const val PERMISSION_SCREEN_ID = "SYS-001"
}
