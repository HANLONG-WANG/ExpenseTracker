package com.yohaku.expensetracker.domain.budget

import com.yohaku.expensetracker.data.db.CategoryEntity

/** Same sibling order as monthly budgets; keep archived parents for their historical children. */
fun orderedActivityCategories(categories:List<CategoryEntity>):List<CategoryEntity> =
    categories.filter{it.parentId==null}.flatMap{root->listOf(root)+categories.filter{it.parentId==root.id}}

/** A parent limit includes its children. An unset parent contributes only its children's limits. */
fun activityCategoryBudgetTotal(categories:List<CategoryEntity>,limits:Map<String,Long>):Long =
    categories.filter{it.id in limits&&(it.parentId==null||it.parentId !in limits)}.sumOf{limits.getValue(it.id)}
