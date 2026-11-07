package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.CategoryEntity
import com.yohaku.expensetracker.domain.budget.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityCategoryBudgetsTest {
    private val categories=listOf(
        CategoryEntity("book","food","餐饮","EXPENSE"),
        CategoryEntity("book","stay","住宿","EXPENSE"),
        CategoryEntity("book","coffee","咖啡","EXPENSE",parentId="food"),
        CategoryEntity("book","breakfast","早餐","EXPENSE",parentId="food")
    )
    @Test fun parentLimitIncludesChildrenOtherwiseChildrenAddUp() {
        val children=mapOf("coffee" to 2000L,"breakfast" to 1000L,"stay" to 8000L)
        assertEquals(11000L,activityCategoryBudgetTotal(categories,children))
        assertEquals(14000L,activityCategoryBudgetTotal(categories,children+("food" to 6000L)))
        assertEquals(8000L,activityCategoryBudgetTotal(categories,children+("food" to 0L)))
        assertEquals(0L,activityCategoryBudgetTotal(categories,emptyMap()))
        assertEquals(listOf("food","coffee","breakfast","stay"),orderedActivityCategories(categories).map{it.id})
    }
}
