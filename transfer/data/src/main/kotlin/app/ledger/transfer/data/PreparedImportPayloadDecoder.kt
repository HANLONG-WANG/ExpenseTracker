package app.ledger.transfer.data

import app.ledger.transfer.domain.PreparedCommandPayload
import app.ledger.transfer.domain.PreparedImportPayload
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets

object PreparedImportPayloadDecoder {
    fun decode(payload: PreparedCommandPayload): PreparedImportPayload = DataInputStream(
        ByteArrayInputStream(payload.bytes),
    ).use { input ->
        val type = input.readSizedUtf8()
        val count = input.readInt()
        require(count in 1..MAX_FIELDS)
        val values = buildMap {
            repeat(count) {
                val name = input.readSizedUtf8()
                require(put(name, input.readSizedUtf8()) == null)
            }
        }
        require(input.read() == -1)
        PreparedImportPayload(type, values)
    }

    private fun DataInputStream.readSizedUtf8(): String {
        val size = readInt()
        require(size in 0..MAX_FIELD_BYTES)
        return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8)
    }

    private const val MAX_FIELDS = 4_096
    private const val MAX_FIELD_BYTES = 16 * 1024 * 1024
}
