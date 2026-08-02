package app.ledger.core.security

import java.security.SecureRandom

fun interface CryptographicRandomSource {
    fun nextBytes(destination: ByteArray)
}

object PlatformCryptographicRandomSource : CryptographicRandomSource {
    private val random = SecureRandom()

    override fun nextBytes(destination: ByteArray) = random.nextBytes(destination)
}
