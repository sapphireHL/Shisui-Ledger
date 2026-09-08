package com.smartledger.domain.merchant

import com.smartledger.domain.model.*
import com.smartledger.domain.repository.*

interface MerchantNormalizer { fun normalize(raw: String): NormalizedMerchant }

class RuleMerchantNormalizer : MerchantNormalizer {
    private val aliases = mapOf(
        "starbucks" to "星巴克", "星巴克咖啡" to "星巴克", "滴滴出行" to "滴滴", "steamgames" to "Steam",
        "财付通" to "微信支付", "深圳市腾讯计算机系统" to "微信支付", "拉扎斯" to "饿了么",
        "北京三快在线科技" to "美团", "上海公共交通卡" to "上海交通卡",
    )
    override fun normalize(raw: String): NormalizedMerchant {
        val stripped = raw.replace(Regex("^(?:财付通|微信支付|支付宝)[-－—:：\\s]*"), "").substringBefore("【").trim()
        var value = stripped.lowercase().replace(Regex("[\\s·•._—-]+"), "")
            .replace(Regex("[（(].{0,18}?(店|分店|门店)[）)]"), "")
            .replace(Regex("(有限责任公司|股份有限公司|有限公司|公司)$"), "")
        val alias = aliases.entries.firstOrNull { value.contains(it.key) }
        val canonical = alias?.value ?: stripped.replace(Regex("[（(].{0,18}?[）)]"), "").replace(Regex("(有限责任公司|股份有限公司|有限公司|公司)$"), "").trim()
        val stableKey = canonical.lowercase().replace(Regex("[\\s·•._—-]+"), "")
        return NormalizedMerchant(stableKey.ifBlank { value.ifBlank { "unknown" } }, canonical.ifBlank { "未知商户" })
    }
}

class MerchantMemoryService(
    private val repository: MerchantMemoryRepository,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
) {
    suspend fun recall(key: String) = repository.find(key)
    suspend fun remember(merchant: NormalizedMerchant, categoryId: String?, ledgerId: String?) {
        val now = clock.nowEpochMillis()
        val old = repository.find(merchant.key)
        repository.save(
            MerchantMemory(old?.id ?: idGenerator.newId(), merchant.key, merchant.canonicalName, categoryId, ledgerId,
                1f, MemorySource.USER, (old?.useCount ?: 0) + 1, old?.createdAtEpochMillis ?: now, now),
        )
    }
}
