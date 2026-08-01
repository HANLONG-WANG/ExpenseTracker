package quality.fixture

class DecoyFinancialMutationCoordinator

fun decoyBypass(postingDao: PostingDao) = postingDao.updateCurrent(command)
