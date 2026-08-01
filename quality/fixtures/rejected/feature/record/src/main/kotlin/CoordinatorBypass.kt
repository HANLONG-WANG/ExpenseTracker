package quality.fixture

fun bypass(transactionDao: TransactionDao) = transactionDao.insertCurrent(command)
