package quality.fixture

fun invalidDestination() = ScreenId("BAD-999")

fun nondeterministic() = Instant.now() to UUID.randomUUID()
