package com.yohaku.expensetracker.receipt.scan

/** The visible paper cannot supply a usable fit. Not a model, I/O or programming failure.
 * preview/detect/process translate this into a normal missing detection; manual render callers
 * keep the draft open so the user can adjust it.
 */
class ScanGeometryUnavailable internal constructor(reason: String, cause: Throwable? = null) : Exception(reason, cause)

internal inline fun requireGeometry(condition: Boolean, reason: () -> String) {
    if (!condition) throw ScanGeometryUnavailable(reason())
}
