package app.ledger.transfer.data

import app.ledger.core.common.DomainResult

internal fun <T> DomainResult<T>.requireValue(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> error(error.code)
}
