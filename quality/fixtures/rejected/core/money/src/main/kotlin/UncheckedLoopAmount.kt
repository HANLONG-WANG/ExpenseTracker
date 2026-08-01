package quality.fixture

fun uncheckedLoop(values: List<Long>): Long {
    var total = 0L
    for (value in values) {
        total += value
    }
    return total
}

fun uncheckedManual(values: List<Long>): Long {
    var amount = 0L
    for (value in values) {
        amount = amount + value
    }
    return amount
}

fun uncheckedObscure(values: List<Long>): Long {
    var x: Long = 0
    for (value in values) {
        x += value
    }
    return x
}
