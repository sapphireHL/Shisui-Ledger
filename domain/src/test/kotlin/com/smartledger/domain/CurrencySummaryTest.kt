package com.smartledger.domain

import com.smartledger.domain.currency.*
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.test.*

class CurrencySummaryTest {
    private val utc = ZoneId.of("UTC")
    private fun amount(minor: Long, currency: String, day: String) = CurrencyAmount(minor, currency, java.time.LocalDate.parse(day).atStartOfDay(utc).toInstant().toEpochMilli())
    private fun quote(base: String, requested: String, value: String, stale: Boolean = false) = ExchangeRateQuote(base, "CNY", requested, requested, BigDecimal(value), 123L, stale)

    @Test fun sameCurrencyNeedsNoRate() {
        val result = CurrencySummaryCalculator.summarize(listOf(amount(1234, "CNY", "2026-09-01")), "CNY", { _, _, _ -> error("unused") }, utc)
        assertEquals(1234, result.convertedTotalMinor); assertTrue(result.isComplete); assertFalse(result.isConverted)
    }

    @Test fun cnyAndGbpConvertWithoutAddingMinorUnitsDirectly() {
        val result = CurrencySummaryCalculator.summarize(listOf(amount(1000, "CNY", "2026-09-01"), amount(100, "GBP", "2026-09-01")), "CNY", { base, _, date -> quote(base, date, "9.25") }, utc)
        assertEquals(1925, result.convertedTotalMinor); assertEquals(mapOf("CNY" to 1000L, "GBP" to 100L), result.originalTotals)
        assertEquals(925L, result.convertedOriginalTotals["GBP"])
    }

    @Test fun historicalDatesUseDifferentRates() {
        val result = CurrencySummaryCalculator.summarize(listOf(amount(100, "GBP", "2026-08-01"), amount(100, "GBP", "2026-09-01")), "CNY", { base, _, date -> quote(base, date, if (date == "2026-08-01") "9.0" else "10.0") }, utc)
        assertEquals(1900, result.convertedTotalMinor)
        assertEquals(1900L, result.convertedOriginalTotals["GBP"])
    }

    @Test fun missingRateRemainsUnresolved() {
        val result = CurrencySummaryCalculator.summarize(listOf(amount(500, "CNY", "2026-09-01"), amount(250, "GBP", "2026-09-01")), "CNY", { _, _, _ -> null }, utc)
        assertEquals(500, result.convertedTotalMinor); assertEquals(mapOf("GBP" to 250L), result.unresolvedCurrencies); assertFalse(result.isComplete)
        assertNull(result.convertedOriginalTotals["GBP"])
    }

    @Test fun staleRateMarksApproximate() {
        val result = CurrencySummaryCalculator.summarize(listOf(amount(100, "GBP", "2026-09-01")), "CNY", { base, _, date -> quote(base, date, "9.0", stale = true) }, utc)
        assertTrue(result.isStale)
    }

    @Test fun refundsAndBankersRoundingStayExact() {
        val result = CurrencySummaryCalculator.summarize(listOf(amount(100, "USD", "2026-09-01"), amount(-25, "USD", "2026-09-01")), "CNY", { base, _, date -> quote(base, date, "7.333") }, utc)
        assertEquals(550, result.convertedTotalMinor)
    }
}
