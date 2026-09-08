package com.smartledger.domain.usecase

import com.smartledger.domain.model.SourceType

/** Cross-app correlation is deliberately narrow: a payment channel may merge with a bank signal,
 * but two competing payment channels must never be merged solely because amount/time match. */
object TransactionCorrelationPolicy {
    private val paymentChannels = setOf(SourceType.WECHAT, SourceType.ALIPAY)
    private val bankSignals = setOf(SourceType.CMB, SourceType.CMB_LIFE)

    fun sourcesCompatible(first: SourceType, second: SourceType): Boolean =
        (first in paymentChannels && second in bankSignals) ||
            (first in bankSignals && second in paymentChannels) ||
            (first in bankSignals && second in bankSignals)

    /** Keep the real payment rail as the cluster identity while bank notifications enrich it. */
    fun resolvePrimarySource(first: SourceType, second: SourceType, secondHasRicherEvidence: Boolean): SourceType =
        when {
            first in paymentChannels -> first
            second in paymentChannels -> second
            secondHasRicherEvidence -> second
            else -> first
        }
}
