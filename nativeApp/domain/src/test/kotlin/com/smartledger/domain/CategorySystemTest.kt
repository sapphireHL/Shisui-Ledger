package com.smartledger.domain

import com.smartledger.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CategorySystemTest {
    @Test fun defaultsAreStableUniqueAndTwoLevel() {
        assertEquals(DefaultCategories.size, DefaultCategories.map { it.id }.distinct().size)
        assertEquals(DefaultCategories.size, DefaultCategories.map { (it.parentId ?: "__root__:${it.type}") to it.name.trim().lowercase() }.distinct().size)
        val ids = DefaultCategories.map { it.id }.toSet()
        DefaultCategories.filter { it.parentId != null }.forEach { child ->
            val parent = assertNotNull(DefaultCategories.firstOrNull { it.id == child.parentId })
            assertEquals(null, parent.parentId)
            assertEquals(parent.type, child.type)
            assertEquals(parent.iconKey, child.iconKey)
            assertTrue(child.parentId in ids)
        }
    }

    @Test fun requiredLegacyCategoriesMapToCanonicalChildren() {
        assertEquals(defaultCategoryByName("外卖")!!.id, legacyCategoryTargetId("food_delivery"))
        assertEquals(defaultCategoryByName("打车")!!.id, legacyCategoryTargetId("transport_taxi"))
        assertEquals(defaultCategoryByName("游戏")!!.id, legacyCategoryTargetId("gaming"))
        assertEquals(defaultCategoryByName("其他餐饮")!!.id, legacyCategoryTargetId("food"))
        assertEquals(defaultCategoryByName("其他交通")!!.id, legacyCategoryTargetId("transport"))
    }

    @Test fun specialDirectionsAreNotOrdinaryExpenseOrIncome() {
        val excluded = setOf(TransactionDirection.TRANSFER, TransactionDirection.CREDIT_CARD_REPAYMENT, TransactionDirection.ACCOUNT_TOP_UP)
        assertTrue(excluded.none { it == TransactionDirection.EXPENSE || it == TransactionDirection.INCOME })
    }

    @Test fun primaryTotalsEqualChildren() {
        val food = defaultCategoryByName("餐饮")!!
        val children = DefaultCategories.filter { it.parentId == food.id }
        val amounts = children.associate { it.id to (it.sortOrder.toLong() + 100) }
        assertEquals(amounts.values.sum(), children.sumOf { amounts[it.id] ?: 0 })
    }

    @Test fun everyDefaultUsesLucideKeyNotEmoji() {
        val valid = setOf("utensils-crossed", "motorbike", "shopping-cart", "house", "wrench", "gamepad-2", "heart-pulse", "graduation-cap", "gift", "luggage", "paw-print", "shapes", "briefcase", "badge-dollar-sign", "store", "laptop", "trending-up", "circle-plus", "arrow-left-right", "rotate-ccw", "receipt-text", "handshake", "chart-no-axes-combined")
        assertTrue(DefaultCategories.all { it.iconKey in valid })
    }

    @Test fun expenseRootNamesAreTwoCharactersAndSubscriptionIsAbsent() {
        val roots = DefaultCategories.filter { it.type == CategoryType.EXPENSE && it.parentId == null }
        assertTrue(roots.all { it.name.length == 2 })
        assertTrue(DefaultCategories.none { it.name == "订阅" })
        assertNotNull(roots.firstOrNull { it.name == "餐饮" })
        assertNotNull(roots.firstOrNull { it.name == "交通" })
    }

    @Test fun transfersAndRepaymentsDoNotEnterExpenseStatistics() {
        assertEquals(0L, transaction(TransactionDirection.TRANSFER).expenseStatisticsAmount())
        assertEquals(0L, transaction(TransactionDirection.CREDIT_CARD_REPAYMENT).expenseStatisticsAmount())
        assertEquals(0L, transaction(TransactionDirection.ACCOUNT_TOP_UP).expenseStatisticsAmount())
    }

    @Test fun linkedRefundOffsetsOriginalCategoryButUnlinkedRefundDoesNot() {
        assertEquals(-1000L, transaction(TransactionDirection.REFUND, "original").expenseStatisticsAmount())
        assertEquals(0L, transaction(TransactionDirection.REFUND).expenseStatisticsAmount())
    }

    private fun transaction(direction: TransactionDirection, linked: String? = null) = Transaction("id", "test", SourceType.MANUAL, 1000, "CNY", "merchant", direction, defaultCategoryByName("外卖")!!.id, "daily", 1, parserConfidence = 1f, sceneConfidence = 1f, status = TransactionStatus.CONFIRMED, fingerprint = "fp", createdAtEpochMillis = 1, updatedAtEpochMillis = 1, linkedTransactionId = linked)
}
