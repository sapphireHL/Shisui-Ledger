package com.smartledger.nativeapp.currency

import com.smartledger.nativeapp.data.local.ExchangeRateEntity
import kotlin.test.*

class CurrencyRateCachePolicyTest {
    private fun row(fetched: Long) = ExchangeRateEntity("GBP", "CNY", LATEST_RATE_KEY, "2026-09-09", "9.1", fetched)

    @Test fun latestCacheFallsBackAfterExpiryAndIsMarkedStale() {
        val now = LATEST_RATE_TTL_MILLIS + 10L
        assertFalse(CurrencyRateCachePolicy.usable(row(0), false, now))
        assertTrue(CurrencyRateCachePolicy.stale(row(0), false, now))
    }

    @Test fun historicalCacheNeverExpires() {
        val old = row(0)
        assertTrue(CurrencyRateCachePolicy.usable(old, true, Long.MAX_VALUE))
        assertFalse(CurrencyRateCachePolicy.stale(old, true, Long.MAX_VALUE))
    }
}
