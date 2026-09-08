package com.smartledger.domain

import com.smartledger.domain.model.SourceType
import com.smartledger.domain.usecase.TransactionCorrelationPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionCorrelationPolicyTest {
    @Test fun wechatAndAlipayNeverMerge() {
        assertFalse(TransactionCorrelationPolicy.sourcesCompatible(SourceType.WECHAT, SourceType.ALIPAY))
        assertFalse(TransactionCorrelationPolicy.sourcesCompatible(SourceType.ALIPAY, SourceType.WECHAT))
    }

    @Test fun paymentAndBankSignalsCanMerge() {
        assertTrue(TransactionCorrelationPolicy.sourcesCompatible(SourceType.WECHAT, SourceType.CMB))
        assertTrue(TransactionCorrelationPolicy.sourcesCompatible(SourceType.ALIPAY, SourceType.CMB_LIFE))
        assertTrue(TransactionCorrelationPolicy.sourcesCompatible(SourceType.CMB, SourceType.CMB_LIFE))
    }

    @Test fun paymentChannelRemainsClusterIdentityAfterBankEnrichment() {
        assertTrue(TransactionCorrelationPolicy.resolvePrimarySource(SourceType.WECHAT, SourceType.CMB_LIFE, true) == SourceType.WECHAT)
        assertTrue(TransactionCorrelationPolicy.resolvePrimarySource(SourceType.CMB, SourceType.ALIPAY, true) == SourceType.ALIPAY)
    }
}
