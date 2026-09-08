package com.smartledger.domain

import com.smartledger.domain.merchant.*
import com.smartledger.domain.model.*
import com.smartledger.domain.repository.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOf
import kotlin.test.*

class MerchantMemoryTest {
    private class MemoryRepo : MerchantMemoryRepository { val values = mutableMapOf<String, MerchantMemory>(); override fun observeAll() = flowOf(values.values.toList()); override suspend fun find(merchantKey: String) = values[merchantKey]; override suspend fun save(memory: MerchantMemory) { values[memory.merchantKey] = memory }; override suspend fun clearAll() { values.clear() } }
    private val normalizer = RuleMerchantNormalizer()
    @Test fun fuzzyMerchantNormalization() { assertEquals("星巴克", normalizer.normalize("星巴克咖啡(陆家嘴店)").canonicalName) }
    @Test fun punctuationVariantUsesSameMerchantKey() { assertEquals(normalizer.normalize("衢·面拾捌").key, normalizer.normalize("衢面拾捌").key) }
    @Test fun elemeCompanyVariantsShareOneMerchantKey() { val network = normalizer.normalize("拉扎斯网络科技（上海）有限公司"); val information = normalizer.normalize("上海拉扎斯信息科技有限公司"); assertEquals("饿了么", information.canonicalName); assertEquals(network.key, information.key) }
    @Test fun firstConfirmAndRecall() = runTest { val repo = MemoryRepo(); val service = MerchantMemoryService(repo, object : IdGenerator { override fun newId() = "id" }, object : Clock { override fun nowEpochMillis() = 1L }); val m = normalizer.normalize("XXX生活超市"); assertNull(service.recall(m.key)); service.remember(m, "shopping", "daily"); assertEquals("shopping", service.recall(m.key)?.categoryId) }
    @Test fun userModificationUpdatesMemory() = runTest { val repo = MemoryRepo(); val service = MerchantMemoryService(repo, object : IdGenerator { override fun newId() = "id" }, object : Clock { override fun nowEpochMillis() = 2L }); val m = normalizer.normalize("Steam Games"); service.remember(m, "gaming", "daily"); service.remember(m, "subscription", "daily"); assertEquals("subscription", service.recall(m.key)?.categoryId); assertEquals(2, service.recall(m.key)?.useCount) }
}
