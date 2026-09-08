package com.smartledger.domain.scene

import com.smartledger.domain.model.*

class TravelDetector(
    private val enterThresholdMillis: Long = 6 * 60 * 60 * 1000L,
    private val returnThresholdMillis: Long = 2 * 60 * 60 * 1000L,
) {
    fun update(previous: TravelSnapshot, countryCode: String?, now: Long, userConfirmed: Boolean = false): TravelSnapshot {
        if (countryCode == null) return previous
        return when (previous.state) {
            TravelState.NORMAL -> if (countryCode != previous.homeCountry) TravelSnapshot(TravelState.POSSIBLE_TRAVEL, previous.homeCountry, countryCode, now) else previous
            TravelState.POSSIBLE_TRAVEL -> when {
                countryCode == previous.homeCountry -> TravelSnapshot(TravelState.NORMAL, previous.homeCountry, countryCode, now)
                userConfirmed || now - previous.changedAtEpochMillis >= enterThresholdMillis -> previous.copy(state = TravelState.TRAVELING)
                else -> previous.copy(observedCountry = countryCode)
            }
            TravelState.TRAVELING -> if (countryCode == previous.homeCountry) TravelSnapshot(TravelState.POSSIBLE_RETURN, previous.homeCountry, countryCode, now) else previous.copy(observedCountry = countryCode)
            TravelState.POSSIBLE_RETURN -> when {
                countryCode != previous.homeCountry -> previous.copy(state = TravelState.TRAVELING, observedCountry = countryCode, changedAtEpochMillis = now)
                userConfirmed || now - previous.changedAtEpochMillis >= returnThresholdMillis -> TravelSnapshot(TravelState.NORMAL, previous.homeCountry, countryCode, now)
                else -> previous
            }
        }
    }
}
