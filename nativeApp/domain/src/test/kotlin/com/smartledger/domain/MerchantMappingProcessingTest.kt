package com.smartledger.domain

import com.smartledger.domain.merchant.RuleMerchantNormalizer
import com.smartledger.domain.model.*
import com.smartledger.domain.repository.*
import com.smartledger.domain.scene.RuleSceneEngine
import com.smartledger.domain.usecase.ProcessParsedTransaction
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MerchantMappingProcessingTest {
    @Test fun mappedMerchantConfirmsWithLowParserConfidenceAndLegacyKey() = runTest {
        val saved = mutableListOf<Transaction>()
        val transactions = object : TransactionRepository {
            override fun observeAll() = flowOf(saved)
            override fun observePending() = flowOf(emptyList<Transaction>())
            override suspend fun findById(id: String) = saved.firstOrNull { it.id == id }
            override suspend fun findDuplicate(fingerprint: String, source: SourceType, amountMinor: Long, merchantKey: String, occurredAt: Long) = false
            override suspend fun findCorrelationCandidate(source: SourceType, amountMinor: Long, currency: String, direction: TransactionDirection, occurredAt: Long, windowMillis: Long) = null
            override suspend fun findRefundCandidate(amountMinor: Long, currency: String, merchantKey: String, occurredAt: Long) = null
            override suspend fun save(transaction: Transaction) { saved += transaction }
            override suspend fun update(transaction: Transaction) = Unit
            override suspend fun updateCategoryForMerchant(merchantKey: String, categoryId: String, primaryCategoryId: String?, updatedAt: Long) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun clearAll() = Unit
        }
        val ledger = Ledger("daily", "日常开销", LedgerType.DEFAULT, "house", "CNY", createdAtEpochMillis = 1, updatedAtEpochMillis = 1)
        val ledgers = object : LedgerRepository {
            override fun observeAll() = flowOf(listOf(ledger))
            override suspend fun activeDefault() = ledger
            override suspend fun setDefault(id: String) = Unit
            override suspend fun save(ledger: Ledger) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun clearCustom() = Unit
        }
        val drink = defaultCategoryByName("饮品")!!
        val mapping = MerchantMemory("memory", "legacy-key", "星巴克", drink.id, "daily", 1f, MemorySource.USER, 1, 1, 1)
        val memories = object : MerchantMemoryRepository {
            override fun observeAll() = flowOf(listOf(mapping))
            override suspend fun find(merchantKey: String) = null
            override suspend fun save(memory: MerchantMemory) = Unit
            override suspend fun clearAll() = Unit
        }
        val categories = object : CategoryRepository {
            override fun observeAll() = flowOf(DefaultCategories)
            override suspend fun findById(id: String) = DefaultCategories.firstOrNull { it.id == id }
            override suspend fun seedDefaults() = Unit
            override suspend fun save(category: Category) = Unit
        }
        val processor = ProcessParsedTransaction(
            transactions, ledgers, memories, categories, RuleMerchantNormalizer(), RuleSceneEngine(),
            object : UserContextProvider { override suspend fun current() = UserContext(1, 1, 12) },
            object : IdGenerator { override fun newId() = "transaction" },
            object : FingerprintFactory { override fun create(parsed: ParsedTransaction, merchantKey: String) = "fingerprint" },
            object : Clock { override fun nowEpochMillis() = 2L },
        )

        val result = processor(ParsedTransaction("微信", SourceType.WECHAT, 3_800, "CNY", "星巴克咖啡（人民广场店）", TransactionDirection.EXPENSE, 1, .45f, "支付38元"))!!

        assertEquals(TransactionStatus.CONFIRMED, result.status)
        assertEquals(drink.id, result.categoryId)
    }
}
