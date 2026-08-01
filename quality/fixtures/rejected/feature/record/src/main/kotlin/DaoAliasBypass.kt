package quality.fixture

typealias DaoStore = TransactionDao

fun aliasBypass(transactionDao: TransactionDao) {
    val store = transactionDao
    store.insertCurrent(command)
}

fun typedAliasBypass(store: DaoStore) = store.saveCurrent(command)

fun propertyAliasBypass(provider: DaoProvider) {
    val sink = provider.transactionDao
    sink.persistCurrent(command)
}
