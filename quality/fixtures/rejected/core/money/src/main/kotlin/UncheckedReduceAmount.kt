package quality.fixture

fun uncheckedReduce(values: List<Long>): Long = values.reduce { total, value -> total + value }
