package com.smartledger.domain.usecase

import com.smartledger.domain.model.*
import java.time.Instant
import java.time.ZoneId

fun calculateBookBudget(
    ledger: Ledger,
    transactions: List<Transaction>,
    nowEpochMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): BookBudget? {
    val scoped = bookBudgetTransactions(ledger, transactions, nowEpochMillis, zoneId) ?: return null
    val spent = scoped.asSequence()
        .filter { it.currency == ledger.currency }
        .sumOf(Transaction::expenseStatisticsAmount)
        .coerceAtLeast(0L)
    return BookBudget(ledger.budgetType!!, ledger.currency, ledger.budgetAmountMinor, spent)
}

fun bookBudgetTransactions(
    ledger: Ledger,
    transactions: List<Transaction>,
    nowEpochMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<Transaction>? {
    val type = ledger.budgetType ?: return null
    if (ledger.budgetAmountMinor <= 0) return null
    val start = when (type) {
        BudgetType.MONTHLY -> Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
            .withDayOfMonth(1).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
        // Total budget uses every confirmed expense assigned to this ledger. Ledger
        // creation/start metadata must not silently exclude imported or reassigned rows.
        BudgetType.TOTAL -> Long.MIN_VALUE
    }
    val endExclusive = when (type) {
        BudgetType.MONTHLY -> Instant.ofEpochMilli(start).atZone(zoneId).plusMonths(1).toInstant().toEpochMilli()
        BudgetType.TOTAL -> Long.MAX_VALUE
    }
    return transactions.asSequence()
        .filter { it.ledgerId == ledger.id }
        .filter { it.status == TransactionStatus.CONFIRMED }
        .filter { it.occurredAtEpochMillis >= start && it.occurredAtEpochMillis < endExclusive }
        .filter { it.expenseStatisticsAmount() != 0L }
        .toList()
}
