package com.yohaku.expensetracker.receipt.scan

import kotlin.math.PI

/** EXIF-upright image top is authoritative. Exactly +/-45 degrees keeps the given axis. */
internal object ScanOrientation {
    fun deskewRadians(angle: Double): Double {
        var upright = angle
        if (upright > PI / 2) upright -= PI
        if (upright < -PI / 2) upright += PI
        if (upright > PI / 4) upright -= PI / 2
        if (upright < -PI / 4) upright += PI / 2
        return upright
    }
}
