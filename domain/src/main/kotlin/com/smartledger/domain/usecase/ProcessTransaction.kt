package com.smartledger.domain.usecase

import com.smartledger.domain.merchant.*
import com.smartledger.domain.model.*
import com.smartledger.domain.repository.*
import com.smartledger.domain.scene.SceneEngine
import kotlinx.coroutines.flow.first

class ProcessParsedTransaction(
    private val transactions: TransactionRepository,
    private val ledgers: LedgerRepository,
    private val memories: MerchantMemoryRepository,
    private val categories: CategoryRepository,
    private val normalizer: MerchantNormalizer,
    private val sceneEngine: SceneEngine,
    private val contextProvider: UserContextProvider,
    private val ids: IdGenerator,
    private val fingerprints: FingerprintFactory,
    private val clock: Clock,
) {
    suspend operator fun invoke(parsed: ParsedTransaction): Transaction? {
        val normalized = normalizer.normalize(parsed.merchantName ?: "未知商户")
        val fingerprint = fingerprints.create(parsed, normalized.key)
        if (transactions.findDuplicate(fingerprint, parsed.sourceType, parsed.amountMinor, normalized.key, parsed.occurredAtEpochMillis)) return null
        val defaultLedger = ledgers.activeDefault()
        val memory = parsed.merchantName?.let {
            memories.find(normalized.key) ?: memories.observeAll().first().firstOrNull { saved ->
                normalizer.normalize(saved.canonicalName).key == normalized.key
            }
        }
        val decision = sceneEngine.resolve(parsed, contextProvider.current(), memory, defaultLedger.id)
        val refunded = if (parsed.direction in setOf(TransactionDirection.REFUND, TransactionDirection.REIMBURSEMENT)) transactions.findRefundCandidate(parsed.amountMinor, parsed.currency, normalized.key, parsed.occurredAtEpochMillis) else null
        val now = clock.nowEpochMillis()
        val mappedMerchant = memory?.categoryId != null
        val status = if (mappedMerchant || (!decision.requiresConfirmation && parsed.confidence >= .70f)) TransactionStatus.CONFIRMED else TransactionStatus.PENDING_CONFIRMATION
        val categoryId = refunded?.categoryId ?: decision.categoryId ?: fallbackCategoryId(if (parsed.direction == TransactionDirection.INCOME) CategoryType.INCOME else CategoryType.EXPENSE)
        val category = categories.findById(categoryId) ?: DefaultCategories.firstOrNull { it.id == categoryId }
        val transaction = Transaction(ids.newId(), parsed.sourceApp, parsed.sourceType, parsed.amountMinor, parsed.currency,
            parsed.merchantName, parsed.direction, categoryId, decision.ledgerId, parsed.occurredAtEpochMillis,
            rawNotification = parsed.rawText, parserConfidence = parsed.confidence, sceneConfidence = decision.confidence,
            status = status, fingerprint = fingerprint, createdAtEpochMillis = now, updatedAtEpochMillis = now,
            primaryCategoryId = refunded?.primaryCategoryId ?: category?.parentId ?: category?.id, linkedTransactionId = refunded?.id,
            bankCardLast4 = parsed.bankCardLast4)
        transactions.save(transaction)
        if (parsed.direction == TransactionDirection.REIMBURSEMENT && refunded != null) transactions.update(refunded.copy(reimbursementStatus = ReimbursementStatus.REIMBURSED, updatedAtEpochMillis = now))
        return transaction
    }
}

class ConfirmTransaction(
    private val transactions: TransactionRepository,
    private val memory: MerchantMemoryService,
    private val categories: CategoryRepository,
    private val normalizer: MerchantNormalizer,
    private val clock: Clock,
) {
    suspend operator fun invoke(transaction: Transaction, categoryId: String, ledgerId: String, remember: Boolean) {
        val category = categories.findById(categoryId) ?: DefaultCategories.firstOrNull { it.id == categoryId }
        transactions.update(transaction.copy(categoryId = categoryId, primaryCategoryId = category?.parentId ?: category?.id, ledgerId = ledgerId, status = TransactionStatus.CONFIRMED, updatedAtEpochMillis = clock.nowEpochMillis()))
        if (remember && transaction.merchantName != null) {
            val merchant = normalizer.normalize(transaction.merchantName)
            memory.remember(merchant, categoryId, ledgerId)
            transactions.updateCategoryForMerchant(merchant.key, categoryId, category?.parentId ?: category?.id, clock.nowEpochMillis())
        }
    }
}
