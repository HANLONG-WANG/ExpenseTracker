package app.ledger.core.common

sealed interface DomainResult<out T> {
    data class Success<T>(val value: T) : DomainResult<T>

    data class Failure(val error: DomainError) : DomainResult<Nothing>
}

inline fun <T, R> DomainResult<T>.map(transform: (T) -> R): DomainResult<R> = when (this) {
    is DomainResult.Success -> DomainResult.Success(transform(value))
    is DomainResult.Failure -> this
}

inline fun <T, R> DomainResult<T>.flatMap(transform: (T) -> DomainResult<R>): DomainResult<R> = when (this) {
    is DomainResult.Success -> transform(value)
    is DomainResult.Failure -> this
}

fun <T> DomainResult<T>.getOrNull(): T? = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> null
}

fun <T> DomainResult<T>.errorOrNull(): DomainError? = when (this) {
    is DomainResult.Success -> null
    is DomainResult.Failure -> error
}
