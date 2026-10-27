package com.yohaku.expensetracker.domain.category

import com.yohaku.expensetracker.data.db.CategoryEntity

const val DEFAULT_CATEGORY_ICON = "category"

sealed interface CategoryIconChoice {
    data object Auto:CategoryIconChoice
    data class Set(val icon:String?):CategoryIconChoice
}

fun suggestCategoryIcons(name:String):List<String> {
    val query=name.trim().lowercase()
    if(query.isEmpty())return emptyList()
    return categoryIconGroups.flatMap{it.icons}.mapNotNull { icon ->
        val tokens=(icon.zh.split(" / ")+icon.ja.split('・')+icon.keywords.split(' ')).map{it.lowercase()}.filter{it.isNotEmpty()}
        val score=tokens.maxOfOrNull { token -> when {
            token==query->100
            query.contains(token)->20+token.length
            query.length>=2 && token.contains(query)->10
            else->0
        } }?:0
        if(score>0)icon.id to score else null
    }.sortedByDescending{it.second}.take(6).map{it.first}
}

fun searchCategoryIcons(query:String):List<String>? {
    val text=query.trim().lowercase()
    if(text.isEmpty())return null
    return categoryIconGroups.flatMap{it.icons}.filter {
        listOf(it.id,it.zh,it.ja,it.keywords).joinToString(" ").lowercase().contains(text)
    }.map{it.id}
}

fun resolveCategoryIcon(categoryId:String?,categories:List<CategoryEntity>):String {
    val category=categories.find{it.id==categoryId}?:return DEFAULT_CATEGORY_ICON
    categoryIconOf(category.icon)?.let{return it.id}
    val parent=categories.find{it.bookId==category.bookId && it.id==category.parentId}
    return categoryIconOf(parent?.icon)?.id?:DEFAULT_CATEGORY_ICON
}
