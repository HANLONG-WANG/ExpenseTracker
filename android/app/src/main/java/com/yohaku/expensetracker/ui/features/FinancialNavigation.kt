package com.yohaku.expensetracker.ui.features

/** UI destinations only; the application route remains "rules". */
internal data class FinancialDestination(val tab: String, val page: String? = null, val planId: String? = null, val periodId: String? = null) {
    val path: String get() = listOfNotNull(tab, page, planId, periodId).joinToString("/")

    companion object {
        fun from(target: String): FinancialDestination = when (target) {
            "rules", "manage/rules" -> FinancialDestination("manage", "rules")
            "movements", "manage/records" -> FinancialDestination("manage", "records")
            "limits", "manage/limits" -> FinancialDestination("manage", "limits")
            "manage/contracts" -> FinancialDestination("manage", "contracts")
            "manage/history" -> FinancialDestination("manage", "history")
            "manage/drafts" -> FinancialDestination("manage", "drafts")
            "overview", "statement", "plans", "manage" -> FinancialDestination(target)
            else -> {
                val parts=target.split("/")
                require(parts.first()=="plans" && parts.size in 2..3 && parts.drop(1).all{it.isNotBlank()}) {"Unknown financial destination: $target"}
                FinancialDestination("plans",planId=parts[1],periodId=parts.getOrNull(2))
            }
        }
    }
}
