package com.yohaku.expensetracker.ui.features

internal enum class ReceiptScanImportStatus { IMPORTING, FAILED, IMPORTED }
internal data class ReceiptScanImport(val path:String,val status:ReceiptScanImportStatus)
