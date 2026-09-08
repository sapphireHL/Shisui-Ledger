package com.smartledger.nativeapp.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smartledger.domain.model.*

@Entity(tableName = "transactions", indices = [Index(value = ["fingerprint"], unique = true), Index("occurredAtEpochMillis"), Index("ledgerId"), Index("sourceApp"), Index("bankCardLast4")])
data class TransactionEntity(
    @PrimaryKey val id: String, val sourceApp: String, val sourceType: String, val amountMinor: Long, val currency: String,
    val merchantName: String?, val merchantKey: String, val direction: String, val categoryId: String?, val ledgerId: String?,
    val occurredAtEpochMillis: Long, val latitude: Double?, val longitude: Double?, val countryCode: String?, val city: String?,
    val rawNotification: String?, val parserConfidence: Float, val sceneConfidence: Float, val status: String,
    val fingerprint: String, val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long,
    val primaryCategoryId: String?, val linkedTransactionId: String?, val reimbursementStatus: String, val bankCardLast4: String?,
)
@Entity(tableName = "ledgers")
data class LedgerEntity(@PrimaryKey val id: String, val name: String, val type: String, val icon: String, val currency: String, val startAtEpochMillis: Long?, val endAtEpochMillis: Long?, val isActive: Boolean, val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long)
@Entity(tableName = "merchant_memory", indices = [Index(value = ["merchantKey"], unique = true)])
data class MerchantMemoryEntity(@PrimaryKey val id: String, val merchantKey: String, val canonicalName: String, val categoryId: String?, val preferredLedgerId: String?, val confidence: Float, val source: String, val useCount: Int, val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long)
@Entity(tableName = "categories", indices = [Index("parentId"), Index(value = ["parentKey", "normalizedName"], unique = true)])
data class CategoryEntity(
    @PrimaryKey val id: String, val name: String, val icon: String, val builtIn: Boolean, val parentId: String?,
    val categoryType: String, val iconKey: String, val sortOrder: Int, val enabled: Boolean, val userCustom: Boolean,
    val normalizedName: String, val parentKey: String, val createdAt: Long, val updatedAt: Long,
)
@Entity(tableName = "location_places")
data class LocationPlaceEntity(@PrimaryKey val id: String, val name: String, val type: String, val latitude: Double, val longitude: Double, val radiusMeters: Int)
@Entity(tableName = "raw_notification_events", indices = [Index(value = ["eventFingerprint"], unique = true), Index("status"), Index("postedAt")])
data class RawNotificationEntity(
    @PrimaryKey val id: String, val eventFingerprint: String, val notificationKey: String,
    val packageName: String, val title: String?, val text: String?, val postedAt: Long,
    val status: String, val attemptCount: Int, val transactionId: String?, val errorReason: String?,
    val createdAt: Long, val processedAt: Long?,
)

fun TransactionEntity.domain() = Transaction(id, sourceApp, SourceType.valueOf(sourceType), amountMinor, currency, merchantName, TransactionDirection.valueOf(direction), categoryId, ledgerId, occurredAtEpochMillis, latitude, longitude, countryCode, city, rawNotification, parserConfidence, sceneConfidence, TransactionStatus.valueOf(status), fingerprint, createdAtEpochMillis, updatedAtEpochMillis, primaryCategoryId, linkedTransactionId, ReimbursementStatus.valueOf(reimbursementStatus), bankCardLast4)
fun Transaction.entity(merchantKey: String) = TransactionEntity(id, sourceApp, sourceType.name, amountMinor, currency, merchantName, merchantKey, direction.name, categoryId, ledgerId, occurredAtEpochMillis, latitude, longitude, countryCode, city, rawNotification, parserConfidence, sceneConfidence, status.name, fingerprint, createdAtEpochMillis, updatedAtEpochMillis, primaryCategoryId, linkedTransactionId, reimbursementStatus.name, bankCardLast4)
fun LedgerEntity.domain() = Ledger(id, name, LedgerType.valueOf(type), icon, currency, startAtEpochMillis, endAtEpochMillis, isActive, createdAtEpochMillis, updatedAtEpochMillis)
fun Ledger.entity() = LedgerEntity(id, name, type.name, icon, currency, startAtEpochMillis, endAtEpochMillis, isActive, createdAtEpochMillis, updatedAtEpochMillis)
fun MerchantMemoryEntity.domain() = MerchantMemory(id, merchantKey, canonicalName, categoryId, preferredLedgerId, confidence, MemorySource.valueOf(source), useCount, createdAtEpochMillis, updatedAtEpochMillis)
fun MerchantMemory.entity() = MerchantMemoryEntity(id, merchantKey, canonicalName, categoryId, preferredLedgerId, confidence, source.name, useCount, createdAtEpochMillis, updatedAtEpochMillis)
fun CategoryEntity.domain() = Category(id, name, icon, builtIn, parentId, CategoryType.valueOf(categoryType), iconKey, sortOrder, enabled, userCustom, createdAt, updatedAt)
fun Category.entity(now: Long = System.currentTimeMillis()) = CategoryEntity(id, name, iconKey, builtIn, parentId, type.name, iconKey, sortOrder, enabled, userCustom, name.trim().lowercase(), parentId ?: "__root__:${type.name}", createdAtEpochMillis.takeIf { it > 0 } ?: now, now)
