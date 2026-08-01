package quality.fixture

import android.util.Log as AuditLog

fun leakLog() = AuditLog.d("ledger", "sensitive")
