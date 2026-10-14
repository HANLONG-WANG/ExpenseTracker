package com.yohaku.expensetracker.domain.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Persisted only while there is no book; later steps live with their book. */
@Serializable
data class SetupDraft(
    val step: Int = 0,
    val name: String = "",
    val currency: String = "JPY",
    val date: String = LocalDate.now().toString(),
    val budget: String = "",
    val theme: String = "magazine",
    val language: String = "zh"
)
