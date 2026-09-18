package com.smartledger.nativeapp.currency

import com.smartledger.domain.currency.*
import com.smartledger.domain.model.Transaction
import com.smartledger.domain.model.expenseStatisticsAmount
import com.smartledger.nativeapp.data.local.ExchangeRateDao
import com.smartledger.nativeapp.data.local.ExchangeRateEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal const val LATEST_RATE_KEY = "__latest__"
internal const val LATEST_RATE_TTL_MILLIS = 8L * 60L * 60L * 1_000L

internal object CurrencyRateCachePolicy {
    fun usable(entity: ExchangeRateEntity?, historical: Boolean, now: Long): Boolean =
        entity != null && (historical || now - entity.fetchedAtEpochMillis <= LATEST_RATE_TTL_MILLIS)
    fun stale(entity: ExchangeRateEntity?, historical: Boolean, now: Long): Boolean =
        entity != null && !historical && now - entity.fetchedAtEpochMillis > LATEST_RATE_TTL_MILLIS
}

@Singleton class CurrencySummaryService @Inject constructor(private val dao: ExchangeRateDao) {
    private val mutex = Mutex()
    val cachedRates = dao.observeAll()

    suspend fun summarize(transactions: List<Transaction>, displayCurrency: String): CurrencySummary = mutex.withLock {
        val amounts = transactions.map { CurrencyAmount(it.expenseStatisticsAmount(), it.currency, it.occurredAtEpochMillis) }.filter { it.amountMinor != 0L }
        val target = displayCurrency.uppercase()
        val today = LocalDate.now().toString()
        val now = System.currentTimeMillis()
        val requested = amounts.filter { it.currency.uppercase() != target }.groupBy { amount ->
            CurrencySummaryCalculator.dateKey(amount.occurredAtEpochMillis).let { if (it == today) LATEST_RATE_KEY else it }
        }.mapValues { (_, rows) -> rows.map { it.currency.uppercase() }.toSet() }
        val cache = mutableMapOf<Triple<String, String, String>, ExchangeRateEntity>()
        requested.forEach { (dateKey, bases) ->
            val missing = bases.filter { base ->
                val existing = dao.find(base, target, dateKey)
                if (existing != null) cache[Triple(base, target, dateKey)] = existing
                !CurrencyRateCachePolicy.usable(existing, dateKey != LATEST_RATE_KEY, now)
            }.toSet()
            if (missing.isNotEmpty()) {
                runCatching { fetchRates(target, missing, dateKey.takeUnless { it == LATEST_RATE_KEY }, now) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }?.let { rows ->
                        dao.saveAll(rows)
                        rows.forEach { cache[Triple(it.base, it.quote, it.requestedDate)] = it }
                    }
            }
        }
        CurrencySummaryCalculator.summarize(amounts, target, { base, quote, requestedDate ->
            val key = if (requestedDate == today) LATEST_RATE_KEY else requestedDate
            cache[Triple(base, quote, key)]?.let { row ->
                ExchangeRateQuote(row.base, row.quote, requestedDate, row.rateDate, BigDecimal(row.rate), row.fetchedAtEpochMillis, CurrencyRateCachePolicy.stale(row, key != LATEST_RATE_KEY, now))
            }
        })
    }

    suspend fun refreshLatest(currencies: Set<String>, displayCurrency: String, force: Boolean = false) = mutex.withLock {
        val target = displayCurrency.uppercase()
        val now = System.currentTimeMillis()
        val missing = currencies.map(String::uppercase).filter { it != target }.distinct().filter { base ->
            val cached = dao.find(base, target, LATEST_RATE_KEY)
            force || !CurrencyRateCachePolicy.usable(cached, false, now)
        }.toSet()
        if (missing.isNotEmpty()) runCatching { fetchRates(target, missing, null, now).also { if (it.isNotEmpty()) dao.saveAll(it) } }
    }

    private suspend fun fetchRates(displayCurrency: String, sourceCurrencies: Set<String>, date: String?, fetchedAt: Long): List<ExchangeRateEntity> = withContext(Dispatchers.IO) {
        val query = buildString {
            append("base=").append(displayCurrency)
            append("&quotes=").append(sourceCurrencies.sorted().joinToString(","))
            date?.let { append("&date=").append(it) }
        }
        val connection = URL("https://api.frankfurter.dev/v2/rates?$query").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) error("Frankfurter HTTP ${connection.responseCode}")
            val rows = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val sourceCurrency = row.getString("quote").uppercase()
                val directRate = BigDecimal.ONE.divide(BigDecimal(row.get("rate").toString()), 18, RoundingMode.HALF_EVEN)
                ExchangeRateEntity(
                    base = sourceCurrency, quote = displayCurrency,
                    requestedDate = date ?: LATEST_RATE_KEY, rateDate = row.getString("date"),
                    rate = directRate.stripTrailingZeros().toPlainString(), fetchedAtEpochMillis = fetchedAt,
                )
            }
        } finally { connection.disconnect() }
    }
}
