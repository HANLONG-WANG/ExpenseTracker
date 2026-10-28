package com.yohaku.expensetracker

import androidx.test.platform.app.InstrumentationRegistry

/** Explicit filters select all matching looks; otherwise each class gets one stable look. */
internal fun testLooks(owner: Any): List<Pair<String, String>> {
    val args = InstrumentationRegistry.getArguments()
    val theme = args.getString("theme")
    val language = args.getString("language")
    val looks = listOf("magazine", "journal", "objects").flatMap { t ->
        listOf("zh", "ja").map { l -> t to l }
    }.filter { (t, l) -> (theme == null || theme == t) && (language == null || language == l) }
    if (args.getString("looks") == "all" || theme != null || language != null) return looks
    return listOf(looks[Math.floorMod(owner.javaClass.name.hashCode(), looks.size)])
}

internal fun visualEnabled() = testOption("visual")
internal fun testOption(name: String) = InstrumentationRegistry.getArguments().getString(name) == "true"
