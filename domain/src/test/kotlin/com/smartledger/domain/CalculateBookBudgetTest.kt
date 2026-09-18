package com.smartledger.domain

import com.smartledger.domain.model.*
import com.smartledger.domain.usecase.calculateBookBudget
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*

class CalculateBookBudgetTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ts(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
    private fun ledger(type: BudgetType?, amount: Long = 10_000, start: Long? = null) = Ledger("book", "账本", LedgerType.CUSTOM, "●", "CNY", createdAtEpochMillis = 1, updatedAtEpochMillis = 1, budgetType = type, budgetAmountMinor = amount, totalBudgetStartTs = start)
    private fun tx(time: Long, amount: Long = 2_000, currency: String = "CNY", ledgerId: String = "book") = Transaction("$time-$currency-$ledgerId", "手动", SourceType.MANUAL, amount, currency, "测试", TransactionDirection.EXPENSE, "food", ledgerId, time, parserConfidence = 1f, sceneConfidence = 1f, status = TransactionStatus.CONFIRMED, fingerprint = "$time-$currency-$ledgerId", createdAtEpochMillis = time, updatedAtEpochMillis = time)

    @Test fun monthlyBudgetUsesPhoneLocalCalendarMonth() {
        val result = calculateBookBudget(ledger(BudgetType.MONTHLY), listOf(tx(ts("2026-03-01T00:00:00")), tx(ts("2026-02-28T23:59:59"))), ts("2026-03-31T23:00:00"), zone)
        assertEquals(2_000, result?.spentAmountMinor)
    }

    @Test fun totalBudgetCoversAllAssignedRowsRegardlessOfBudgetOrLedgerStart() {
        val start = ts("2026-01-15T12:00:00")
        val result = calculateBookBudget(ledger(BudgetType.TOTAL, start = start).copy(startAtEpochMillis = start), listOf(tx(start - 1), tx(start), tx(start + 1)), ts("2026-03-01T00:00:00"), zone)
        assertEquals(6_000, result?.spentAmountMinor)
    }

    @Test fun disabledAndZeroBudgetsReturnNull() {
        assertNull(calculateBookBudget(ledger(null), emptyList(), zoneId = zone))
        assertNull(calculateBookBudget(ledger(BudgetType.MONTHLY, 0), emptyList(), zoneId = zone))
        assertNotNull(calculateBookBudget(ledger(BudgetType.TOTAL, start = null), emptyList(), zoneId = zone))
    }

    @Test fun ignoresOtherLedgerCurrencyAndUnconfirmedRows() {
        val pending = tx(ts("2026-03-02T00:00:00")).copy(id = "pending", status = TransactionStatus.PENDING_CONFIRMATION)
        val result = calculateBookBudget(ledger(BudgetType.MONTHLY), listOf(tx(ts("2026-03-01T00:00:00")), tx(ts("2026-03-02T00:00:00"), currency = "USD"), tx(ts("2026-03-03T00:00:00"), ledgerId = "other"), pending), ts("2026-03-15T00:00:00"), zone)
        assertEquals(2_000, result?.spentAmountMinor)
    }
}
