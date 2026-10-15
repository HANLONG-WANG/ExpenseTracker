package com.yohaku.expensetracker
import com.yohaku.expensetracker.domain.activity.apportion
import com.yohaku.expensetracker.domain.activity.apportionWithTail
import java.math.BigDecimal
import org.junit.Test
import org.junit.Assert.assertEquals
class AllocationTest {
    @Test fun amountsRatiosAndTailPreserveEveryMinorUnit() {
        assertEquals(listOf(2000L,2000L,2000L),apportion(6000,listOf(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE)))
        assertEquals(listOf(7000L,3000L),apportion(10000,listOf(BigDecimal("70"),BigDecimal("30"))))
        assertEquals(listOf(3000L,3000L),apportion(6000,listOf(BigDecimal("50"),BigDecimal("50"))))
        assertEquals(listOf(33L,33L,35L),apportionWithTail(101,listOf(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE)))
        assertEquals(listOf(35L,33L,33L),apportionWithTail(101,listOf(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE),0))
        assertEquals(listOf(34L,34L,33L),apportion(101,listOf(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE)))
    }
}
