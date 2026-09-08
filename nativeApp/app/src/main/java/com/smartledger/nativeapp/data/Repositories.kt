package com.smartledger.nativeapp.data

import com.smartledger.domain.model.*
import com.smartledger.domain.repository.*
import com.smartledger.nativeapp.data.local.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class RoomTransactionRepository @Inject constructor(private val dao: TransactionDao, private val normalizer: com.smartledger.domain.merchant.MerchantNormalizer) : TransactionRepository {
    override fun observeAll() = dao.observeAll().map { rows -> rows.map(TransactionEntity::domain) }
    override fun observePending() = dao.observePending().map { rows -> rows.map(TransactionEntity::domain) }
    override suspend fun findById(id: String) = dao.findById(id)?.domain()
    override suspend fun findDuplicate(fingerprint: String, source: SourceType, amountMinor: Long, merchantKey: String, occurredAt: Long) = dao.duplicateCount(fingerprint, source.name, amountMinor, merchantKey, occurredAt - 300_000, occurredAt + 300_000) > 0
    override suspend fun findCorrelationCandidate(source: SourceType, amountMinor: Long, currency: String, direction: TransactionDirection, occurredAt: Long, windowMillis: Long) = dao.correlationCandidate(source.name, amountMinor, currency, direction.name, occurredAt - windowMillis.coerceAtLeast(1_000L), occurredAt + windowMillis.coerceAtLeast(1_000L), occurredAt)?.domain()
    override suspend fun findRefundCandidate(amountMinor: Long, currency: String, merchantKey: String, occurredAt: Long) = dao.refundCandidate(amountMinor, currency, merchantKey, occurredAt - 90L * 24 * 60 * 60 * 1000, occurredAt)?.domain()
    override suspend fun save(transaction: Transaction) = dao.insert(transaction.entity(normalizer.normalize(transaction.merchantName ?: "未知商户").key))
    override suspend fun update(transaction: Transaction) = dao.update(transaction.entity(normalizer.normalize(transaction.merchantName ?: "未知商户").key))
    override suspend fun updateCategoryForMerchant(merchantKey: String, categoryId: String, primaryCategoryId: String?, updatedAt: Long) = dao.updateCategoryForMerchant(merchantKey, categoryId, primaryCategoryId, updatedAt)
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun clearAll() = dao.clearAll()
}
@Singleton class RoomLedgerRepository @Inject constructor(private val dao: LedgerDao, private val clock: Clock, private val settings: com.smartledger.nativeapp.data.settings.AppSettingsStore) : LedgerRepository {
    override fun observeAll() = dao.observeAll().map { rows -> rows.map(LedgerEntity::domain) }
    override suspend fun activeDefault(): Ledger {
        val daily = dao.findById("daily")?.domain() ?: Ledger("daily", "日常开销", LedgerType.DEFAULT, "⌂", "CNY", createdAtEpochMillis = clock.nowEpochMillis(), updatedAtEpochMillis = clock.nowEpochMillis()).also { dao.save(it.entity()) }
        return dao.findById(settings.defaultLedgerId.first())?.domain() ?: daily.also { settings.setDefaultLedgerId(it.id) }
    }
    override suspend fun setDefault(id: String) { settings.setDefaultLedgerId(dao.findById(id)?.id ?: "daily") }
    override suspend fun save(ledger: Ledger) = dao.save(ledger.entity())
    override suspend fun delete(id: String) {
        if (id == "daily" || dao.findById(id)?.type == LedgerType.DEFAULT.name) return
        if (settings.defaultLedgerId.first() == id) settings.setDefaultLedgerId("daily")
        if (dao.deleteCustom(id) > 0) settings.clearLedgerCoverUri(id)
    }
    override suspend fun clearCustom() = dao.clearCustom()
}
@Singleton class RoomMerchantMemoryRepository @Inject constructor(private val dao: MerchantMemoryDao) : MerchantMemoryRepository {
    override fun observeAll() = dao.observeAll().map { rows -> rows.map(MerchantMemoryEntity::domain) }
    override suspend fun find(merchantKey: String) = dao.find(merchantKey)?.domain()
    override suspend fun save(memory: MerchantMemory) = dao.save(memory.entity())
    override suspend fun clearAll() = dao.clearAll()
}
@Singleton class RoomCategoryRepository @Inject constructor(private val dao: CategoryDao) : CategoryRepository {
    override fun observeAll(): Flow<List<Category>> = dao.observeAll().map { rows -> rows.map(CategoryEntity::domain) }
    override suspend fun findById(id: String) = dao.findById(id)?.domain()
    override suspend fun seedDefaults() {
        dao.insert(DefaultCategories.map { it.entity() })
    }
    override suspend fun save(category: Category) {
        val parentKey = category.parentId ?: "__root__:${category.type.name}"
        val existing = dao.findByNormalizedName(category.name.trim().lowercase(), parentKey)
        if (existing == null || existing.id == category.id) dao.save(category.entity())
    }
}
class UuidGenerator @Inject constructor() : IdGenerator { override fun newId() = UUID.randomUUID().toString() }
class SystemClock @Inject constructor() : Clock { override fun nowEpochMillis() = System.currentTimeMillis() }
class Sha256FingerprintFactory @Inject constructor() : FingerprintFactory {
    override fun create(parsed: ParsedTransaction, merchantKey: String): String {
        val bucket = parsed.occurredAtEpochMillis / 120_000
        val value = "${parsed.sourceType}|${parsed.amountMinor}|$merchantKey|$bucket|${parsed.direction}"
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
