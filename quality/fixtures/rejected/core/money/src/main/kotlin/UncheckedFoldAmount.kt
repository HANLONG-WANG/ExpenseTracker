package quality.fixture

fun uncheckedFold(values: List<Long>): Long = values.fold(0L) { total, value -> total + value }
