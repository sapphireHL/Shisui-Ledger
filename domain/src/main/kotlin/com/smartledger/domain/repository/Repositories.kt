package com.smartledger.domain.repository

import com.smartledger.domain.model.*
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    fun observeAll(): Flow<List<Transaction>>
    fun observePending(): Flow<List<Transaction>>
    suspend fun findById(id: String): Transaction?
    suspend fun findDuplicate(fingerprint: String, source: SourceType, amountMinor: Long, merchantKey: String, occurredAt: Long): Boolean
    suspend fun findCorrelationCandidate(source: SourceType, amountMinor: Long, currency: String, direction: TransactionDirection, occurredAt: Long, windowMillis: Long): Transaction?
    suspend fun findRefundCandidate(amountMinor: Long, currency: String, merchantKey: String, occurredAt: Long): Transaction?
    suspend fun save(transaction: Transaction)
    suspend fun update(transaction: Transaction)
    suspend fun updateCategoryForMerchant(merchantKey: String, categoryId: String, primaryCategoryId: String?, updatedAt: Long)
    suspend fun delete(id: String)
    suspend fun clearAll()
}
interface LedgerRepository { fun observeAll(): Flow<List<Ledger>>; suspend fun activeDefault(): Ledger; suspend fun setDefault(id: String); suspend fun save(ledger: Ledger); suspend fun delete(id: String); suspend fun clearCustom() }
interface MerchantMemoryRepository { fun observeAll(): Flow<List<MerchantMemory>>; suspend fun find(merchantKey: String): MerchantMemory?; suspend fun save(memory: MerchantMemory); suspend fun clearAll() }
interface CategoryRepository { fun observeAll(): Flow<List<Category>>; suspend fun findById(id: String): Category?; suspend fun seedDefaults(); suspend fun save(category: Category) }
interface IdGenerator { fun newId(): String }
interface FingerprintFactory { fun create(parsed: ParsedTransaction, merchantKey: String): String }
interface Clock { fun nowEpochMillis(): Long }
interface UserContextProvider { suspend fun current(): UserContext }
interface TransactionIntelligence { suspend fun classify(transaction: ParsedTransaction, context: UserContext): SceneDecision? }
