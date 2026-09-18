package com.smartledger.domain.currency

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CurrencyAmount(val amountMinor: Long, val currency: String, val occurredAtEpochMillis: Long)

data class ExchangeRateQuote(
    val base: String,
    val quote: String,
    val requestedDate: String,
    val rateDate: String,
    val rate: BigDecimal,
    val fetchedAtEpochMillis: Long,
    val stale: Boolean = false,
)

data class CurrencySummary(
    val displayCurrency: String,
    val convertedTotalMinor: Long,
    val originalTotals: Map<String, Long>,
    val convertedOriginalTotals: Map<String, Long>,
    val rateUpdatedAtEpochMillis: Long?,
    val rateDate: String?,
    val unresolvedCurrencies: Map<String, Long>,
    val isStale: Boolean,
) {
    val isComplete: Boolean get() = unresolvedCurrencies.isEmpty()
    val isConverted: Boolean get() = originalTotals.keys.any { it != displayCurrency }
}

object CurrencySummaryCalculator {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val zeroDecimalCurrencies = setOf("JPY", "KRW")

    fun dateKey(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().format(dateFormatter)

    fun summarize(
        amounts: List<CurrencyAmount>,
        displayCurrency: String,
        rate: (base: String, quote: String, requestedDate: String) -> ExchangeRateQuote?,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): CurrencySummary {
        val normalizedDisplay = displayCurrency.uppercase()
        val originals = amounts.groupBy { it.currency.uppercase() }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } }.toSortedMap()
        var converted = BigDecimal.ZERO
        val convertedByCurrency = linkedMapOf<String, BigDecimal>()
        val unresolved = linkedMapOf<String, Long>()
        val usedRates = mutableListOf<ExchangeRateQuote>()
        amounts.forEach { amount ->
            val base = amount.currency.uppercase()
            val major = BigDecimal.valueOf(amount.amountMinor).movePointLeft(fractionDigits(base))
            if (base == normalizedDisplay) {
                converted = converted.add(major)
                convertedByCurrency[base] = (convertedByCurrency[base] ?: BigDecimal.ZERO).add(major)
            }
            else {
                val quote = rate(base, normalizedDisplay, dateKey(amount.occurredAtEpochMillis, zoneId))
                if (quote == null) unresolved[base] = (unresolved[base] ?: 0L) + amount.amountMinor
                else {
                    val convertedAmount = major.multiply(quote.rate)
                    converted = converted.add(convertedAmount)
                    convertedByCurrency[base] = (convertedByCurrency[base] ?: BigDecimal.ZERO).add(convertedAmount)
                    usedRates += quote
                }
            }
        }
        val minor = converted.movePointRight(fractionDigits(normalizedDisplay)).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
        return CurrencySummary(
            displayCurrency = normalizedDisplay,
            convertedTotalMinor = minor,
            originalTotals = originals,
            convertedOriginalTotals = convertedByCurrency
                .filterKeys { it !in unresolved }
                .mapValues { (_, value) -> value.movePointRight(fractionDigits(normalizedDisplay)).setScale(0, RoundingMode.HALF_EVEN).longValueExact() }
                .toSortedMap(),
            rateUpdatedAtEpochMillis = usedRates.minOfOrNull { it.fetchedAtEpochMillis },
            rateDate = usedRates.minOfOrNull { it.rateDate },
            unresolvedCurrencies = unresolved.toSortedMap(),
            isStale = usedRates.any { it.stale },
        )
    }

    fun fractionDigits(currency: String): Int = if (currency.uppercase() in zeroDecimalCurrencies) 0 else 2
}
