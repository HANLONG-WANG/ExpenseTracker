package com.yohaku.expensetracker.domain.category

import com.yohaku.expensetracker.data.db.CategoryEntity
import org.junit.Assert.*
import org.junit.Test

class CategoryIconsTest {
    @Test fun catalogHasUniqueDrawableIcons() {
        val icons=categoryIconGroups.flatMap{it.icons}
        assertEquals(450,icons.size);assertEquals(450,icons.map{it.id}.toSet().size)
        assertTrue(icons.all{it.paths.isNotEmpty() && it.paths.all(String::isNotBlank)})
        for(group in categoryIconGroups)for(icon in group.icons) {
            assertEquals(icon,categoryIconOf(icon.id));assertEquals(group,categoryIconGroupOf(icon.id))
        }
    }
    @Test fun recommendationsFollowNames() {
        assertEquals("coffee",suggestCategoryIcons(" 咖啡 ").first())
        assertEquals("train",suggestCategoryIcons("电车").first())
        assertEquals("cat",suggestCategoryIcons("猫咪").first())
        assertTrue(suggestCategoryIcons("  ").isEmpty())
    }
    @Test fun searchPreservesCatalogOrder() {
        assertNull(searchCategoryIcons("  "))
        assertEquals(listOf("coffee"),searchCategoryIcons(" COFFEE "))
        val expected=categoryIconGroups.flatMap{it.icons}.filter{listOf(it.id,it.zh,it.ja,it.keywords).joinToString(" ").contains("杂项")}.map{it.id}
        assertEquals(expected,searchCategoryIcons("杂项"))
    }
    @Test fun resolutionUsesOwnThenParentThenDefault() {
        val parent=CategoryEntity("b","p","餐饮","EXPENSE",icon="coffee")
        val child=CategoryEntity("b","c","子类","EXPENSE",parentId="p")
        assertEquals("coffee",resolveCategoryIcon("c",listOf(parent,child)))
        assertEquals("cat",resolveCategoryIcon("c",listOf(parent,child.copy(icon="cat"))))
        assertEquals("coffee",resolveCategoryIcon("c",listOf(parent,child.copy(icon="unknown"))))
        assertEquals(DEFAULT_CATEGORY_ICON,resolveCategoryIcon("c",listOf(parent.copy(icon="unknown"),child)))
        assertEquals(DEFAULT_CATEGORY_ICON,resolveCategoryIcon("missing",listOf(parent,child)))
        assertEquals(DEFAULT_CATEGORY_ICON,resolveCategoryIcon(null,listOf(parent,child)))
    }
}
