package app.ledger.feature.transfer

import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, clearable UI-only recovery password value. It is never persisted or rendered into semantics. */
class RestorePasswordInput private constructor(chars: CharArray) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val value = chars.copyOf()

    val isBlank: Boolean
        @Synchronized get() {
            check(!closed.get()) { "restore password input is closed" }
            return value.all(Char::isWhitespace)
        }

    @Synchronized
    fun editableText(): String {
        check(!closed.get()) { "restore password input is closed" }
        return String(value)
    }

    @Synchronized
    fun copyChars(): CharArray {
        check(!closed.get()) { "restore password input is closed" }
        return value.copyOf()
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) value.fill('\u0000')
    }

    override fun toString(): String = "RestorePasswordInput(redacted,closed=${closed.get()})"

    companion object {
        const val MAX_CHARACTERS = 512

        fun empty(): RestorePasswordInput = RestorePasswordInput(CharArray(0))

        fun copyOf(text: String): RestorePasswordInput {
            val copy = CharArray(minOf(text.length, MAX_CHARACTERS)) { index -> text[index] }
            return try {
                RestorePasswordInput(copy)
            } finally {
                copy.fill('\u0000')
            }
        }
    }
}
