package com.smartledger.nativeapp.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.smartledger.domain.model.*
import com.smartledger.nativeapp.data.local.*
import com.smartledger.nativeapp.data.settings.AppSettingsStore
import com.smartledger.nativeapp.data.settings.PortableAppSettings
import com.smartledger.nativeapp.data.settings.SourceSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

data class LocalDataTransferResult(
    val transactionCount: Int,
    val ledgerCount: Int,
    val categoryCount: Int,
    val merchantMemoryCount: Int,
    val exchangeRateCount: Int,
)

private data class LocalDataBackup(
    val transactions: List<TransactionEntity>,
    val ledgers: List<LedgerEntity>,
    val categories: List<CategoryEntity>,
    val merchantMemories: List<MerchantMemoryEntity>,
    val exchangeRates: List<ExchangeRateEntity>,
    val settings: PortableAppSettings,
) {
    fun result() = LocalDataTransferResult(transactions.size, ledgers.size, categories.size, merchantMemories.size, exchangeRates.size)
}

@Singleton
class LocalDataTransferService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: AppSettingsStore,
) {
    suspend fun exportTo(uri: Uri): LocalDataTransferResult = withContext(Dispatchers.IO) {
        val backup = database.withTransaction {
            LocalDataBackup(
                transactions = database.transactions().snapshotAll(),
                ledgers = database.ledgers().snapshotAll(),
                categories = database.categories().snapshotAll(),
                merchantMemories = database.memories().snapshotAll(),
                exchangeRates = database.exchangeRates().snapshotAll(),
                settings = settingsStore.portableSnapshot(),
            )
        }
        val text = backup.json().toString(2)
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: error("无法写入所选文件")
        backup.result()
    }

    suspend fun importFrom(uri: Uri): LocalDataTransferResult = withContext(Dispatchers.IO) {
        val backup = parseBackup(readLimited(uri))
        validate(backup)
        val previousSettings = settingsStore.portableSnapshot()
        try {
            database.withTransaction {
                database.transactions().clearAll()
                database.memories().clearAll()
                database.exchangeRates().clearAll()
                database.categories().clearAll()
                database.ledgers().clearAll()
                database.categories().replaceAll(backup.categories)
                database.ledgers().replaceAll(backup.ledgers)
                database.memories().replaceAll(backup.merchantMemories)
                database.transactions().replaceAll(backup.transactions)
                database.exchangeRates().saveAll(backup.exchangeRates)
                settingsStore.restorePortable(backup.settings)
            }
        } catch (error: Throwable) {
            runCatching { settingsStore.restorePortable(previousSettings) }
            throw error
        }
        backup.result()
    }

    private fun readLimited(uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BACKUP_BYTES) { "备份文件超过 32 MB，已拒绝导入" }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun validate(value: LocalDataBackup) {
        require(value.ledgers.isNotEmpty()) { "备份中没有账本" }
        require(value.ledgers.any { it.type == LedgerType.DEFAULT.name }) { "备份缺少默认账本" }
        requireUnique(value.transactions.map { it.id }, "明细 ID")
        requireUnique(value.transactions.map { it.fingerprint }, "明细指纹")
        requireUnique(value.ledgers.map { it.id }, "账本 ID")
        requireUnique(value.categories.map { it.id }, "分类 ID")
        requireUnique(value.categories.map { "${it.parentKey}\u001F${it.normalizedName}" }, "分类名称")
        requireUnique(value.merchantMemories.map { it.id }, "商户分类 ID")
        requireUnique(value.merchantMemories.map { it.merchantKey }, "商户键")

        value.transactions.forEach { it.domain() }
        value.ledgers.forEach { it.domain() }
        value.categories.forEach { it.domain() }
        value.merchantMemories.forEach { it.domain() }
        val ledgerIds = value.ledgers.mapTo(mutableSetOf()) { it.id }
        val categoryIds = value.categories.mapTo(mutableSetOf()) { it.id }
        val transactionIds = value.transactions.mapTo(mutableSetOf()) { it.id }
        require(value.settings.defaultLedgerId in ledgerIds) { "默认账本关系已断裂" }
        value.categories.forEach { row -> require(row.parentId == null || row.parentId in categoryIds) { "分类“${row.name}”的父分类不存在" } }
        value.transactions.forEach { row ->
            require(row.ledgerId == null || row.ledgerId in ledgerIds) { "明细 ${row.id} 的账本不存在" }
            require(row.categoryId == null || row.categoryId in categoryIds) { "明细 ${row.id} 的分类不存在" }
            require(row.primaryCategoryId == null || row.primaryCategoryId in categoryIds) { "明细 ${row.id} 的一级分类不存在" }
            require(row.aiSuggestedCategoryId == null || row.aiSuggestedCategoryId in categoryIds) { "明细 ${row.id} 的 AI 分类不存在" }
            require(row.linkedTransactionId == null || row.linkedTransactionId in transactionIds) { "明细 ${row.id} 的关联明细不存在" }
        }
        value.merchantMemories.forEach { row ->
            require(row.categoryId == null || row.categoryId in categoryIds) { "商户“${row.canonicalName}”的分类不存在" }
            require(row.preferredLedgerId == null || row.preferredLedgerId in ledgerIds) { "商户“${row.canonicalName}”的账本不存在" }
        }
        value.exchangeRates.forEach { row ->
            require(row.base.isNotBlank() && row.quote.isNotBlank() && row.requestedDate.isNotBlank()) { "汇率记录字段不完整" }
            require(runCatching { BigDecimal(row.rate) }.getOrNull()?.signum() == 1) { "汇率记录数值无效" }
        }
    }

    private fun requireUnique(values: List<String>, label: String) {
        require(values.size == values.toSet().size) { "$label 重复" }
    }

    companion object {
        private const val FORMAT = "smart-ledger-local-backup"
        private const val SCHEMA_VERSION = 1
        private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    }

    private fun LocalDataBackup.json() = JSONObject()
        .put("format", FORMAT)
        .put("schemaVersion", SCHEMA_VERSION)
        .put("exportedAtEpochMillis", System.currentTimeMillis())
        .put("transactions", JSONArray().also { array -> transactions.forEach { array.put(it.json()) } })
        .put("ledgers", JSONArray().also { array -> ledgers.forEach { array.put(it.json()) } })
        .put("categories", JSONArray().also { array -> categories.forEach { array.put(it.json()) } })
        .put("merchantMemories", JSONArray().also { array -> merchantMemories.forEach { array.put(it.json()) } })
        .put("exchangeRates", JSONArray().also { array -> exchangeRates.forEach { array.put(it.json()) } })
        .put("settings", settings.json())

    private fun parseBackup(text: String): LocalDataBackup {
        val root = runCatching { JSONObject(text) }.getOrElse { error("不是有效的 JSON 备份") }
        require(root.getString("format") == FORMAT) { "不是拾穗本地数据备份" }
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "不支持此备份版本" }
        return LocalDataBackup(
            transactions = root.getJSONArray("transactions").objects(::transaction),
            ledgers = root.getJSONArray("ledgers").objects(::ledger),
            categories = root.getJSONArray("categories").objects(::category),
            merchantMemories = root.getJSONArray("merchantMemories").objects(::merchantMemory),
            exchangeRates = root.optJSONArray("exchangeRates")?.objects(::exchangeRate).orEmpty(),
            settings = settings(root.getJSONObject("settings")),
        )
    }

    private fun TransactionEntity.json() = JSONObject()
        .put("id", id).put("sourceApp", sourceApp).put("sourceType", sourceType).put("amountMinor", amountMinor).put("currency", currency)
        .putNullable("merchantName", merchantName).put("merchantKey", merchantKey).put("direction", direction).putNullable("categoryId", categoryId).putNullable("ledgerId", ledgerId)
        .put("occurredAtEpochMillis", occurredAtEpochMillis).putNullable("latitude", latitude).putNullable("longitude", longitude).putNullable("countryCode", countryCode).putNullable("city", city)
        .putNullable("rawNotification", rawNotification).put("parserConfidence", parserConfidence.toDouble()).put("sceneConfidence", sceneConfidence.toDouble()).put("status", status)
        .put("fingerprint", fingerprint).put("createdAtEpochMillis", createdAtEpochMillis).put("updatedAtEpochMillis", updatedAtEpochMillis)
        .putNullable("primaryCategoryId", primaryCategoryId).putNullable("linkedTransactionId", linkedTransactionId).put("reimbursementStatus", reimbursementStatus).putNullable("bankCardLast4", bankCardLast4)
        .putNullable("normalizedMerchantName", normalizedMerchantName).put("aiAnalysisStatus", aiAnalysisStatus).putNullable("aiSuggestedCategoryId", aiSuggestedCategoryId)
        .putNullable("aiSuggestedCategoryName", aiSuggestedCategoryName).putNullable("aiAnalyzedAtEpochMillis", aiAnalyzedAtEpochMillis).putNullable("aiAnalysisError", aiAnalysisError)
        .put("aiSuggestedMerchantTerms", aiSuggestedMerchantTerms)

    private fun transaction(value: JSONObject) = TransactionEntity(
        value.getString("id"), value.getString("sourceApp"), value.getString("sourceType"), value.getLong("amountMinor"), value.getString("currency"),
        value.nullableString("merchantName"), value.getString("merchantKey"), value.getString("direction"), value.nullableString("categoryId"), value.nullableString("ledgerId"),
        value.getLong("occurredAtEpochMillis"), value.nullableDouble("latitude"), value.nullableDouble("longitude"), value.nullableString("countryCode"), value.nullableString("city"),
        value.nullableString("rawNotification"), value.getDouble("parserConfidence").toFloat(), value.getDouble("sceneConfidence").toFloat(), value.getString("status"),
        value.getString("fingerprint"), value.getLong("createdAtEpochMillis"), value.getLong("updatedAtEpochMillis"), value.nullableString("primaryCategoryId"),
        value.nullableString("linkedTransactionId"), value.getString("reimbursementStatus"), value.nullableString("bankCardLast4"), value.nullableString("normalizedMerchantName"),
        value.getString("aiAnalysisStatus"), value.nullableString("aiSuggestedCategoryId"), value.nullableString("aiSuggestedCategoryName"), value.nullableLong("aiAnalyzedAtEpochMillis"),
        value.nullableString("aiAnalysisError"), value.optString("aiSuggestedMerchantTerms", ""),
    )

    private fun LedgerEntity.json() = JSONObject().put("id", id).put("name", name).put("type", type).put("icon", icon).put("currency", currency)
        .putNullable("startAtEpochMillis", startAtEpochMillis).putNullable("endAtEpochMillis", endAtEpochMillis).put("isActive", isActive)
        .put("createdAtEpochMillis", createdAtEpochMillis).put("updatedAtEpochMillis", updatedAtEpochMillis).putNullable("budgetType", budgetType)
        .put("budgetAmountMinor", budgetAmountMinor).putNullable("totalBudgetStartTs", totalBudgetStartTs)

    private fun ledger(value: JSONObject) = LedgerEntity(
        value.getString("id"), value.getString("name"), value.getString("type"), value.getString("icon"), value.getString("currency"),
        value.nullableLong("startAtEpochMillis"), value.nullableLong("endAtEpochMillis"), value.getBoolean("isActive"), value.getLong("createdAtEpochMillis"),
        value.getLong("updatedAtEpochMillis"), value.nullableString("budgetType"), value.getLong("budgetAmountMinor"), value.nullableLong("totalBudgetStartTs"),
    )

    private fun CategoryEntity.json() = JSONObject().put("id", id).put("name", name).put("icon", icon).put("builtIn", builtIn).putNullable("parentId", parentId)
        .put("categoryType", categoryType).put("iconKey", iconKey).put("sortOrder", sortOrder).put("enabled", enabled).put("userCustom", userCustom)
        .put("normalizedName", normalizedName).put("parentKey", parentKey).put("createdAt", createdAt).put("updatedAt", updatedAt)

    private fun category(value: JSONObject) = CategoryEntity(
        value.getString("id"), value.getString("name"), value.getString("icon"), value.getBoolean("builtIn"), value.nullableString("parentId"),
        value.getString("categoryType"), value.getString("iconKey"), value.getInt("sortOrder"), value.getBoolean("enabled"), value.getBoolean("userCustom"),
        value.getString("normalizedName"), value.getString("parentKey"), value.getLong("createdAt"), value.getLong("updatedAt"),
    )

    private fun MerchantMemoryEntity.json() = JSONObject().put("id", id).put("merchantKey", merchantKey).put("canonicalName", canonicalName)
        .putNullable("categoryId", categoryId).putNullable("preferredLedgerId", preferredLedgerId).put("confidence", confidence.toDouble()).put("source", source)
        .put("useCount", useCount).put("createdAtEpochMillis", createdAtEpochMillis).put("updatedAtEpochMillis", updatedAtEpochMillis)
        .putNullable("normalizedMerchantName", normalizedMerchantName).put("aiAnalyzed", aiAnalyzed).put("matchTerms", matchTerms)

    private fun merchantMemory(value: JSONObject) = MerchantMemoryEntity(
        value.getString("id"), value.getString("merchantKey"), value.getString("canonicalName"), value.nullableString("categoryId"),
        value.nullableString("preferredLedgerId"), value.getDouble("confidence").toFloat(), value.getString("source"), value.getInt("useCount"),
        value.getLong("createdAtEpochMillis"), value.getLong("updatedAtEpochMillis"), value.nullableString("normalizedMerchantName"),
        value.getBoolean("aiAnalyzed"), value.optString("matchTerms", ""),
    )

    private fun ExchangeRateEntity.json() = JSONObject().put("base", base).put("quote", quote).put("requestedDate", requestedDate)
        .put("rateDate", rateDate).put("rate", rate).put("fetchedAtEpochMillis", fetchedAtEpochMillis)

    private fun exchangeRate(value: JSONObject) = ExchangeRateEntity(value.getString("base"), value.getString("quote"), value.getString("requestedDate"), value.getString("rateDate"), value.getString("rate"), value.getLong("fetchedAtEpochMillis"))

    private fun PortableAppSettings.json() = JSONObject().put("sourceWechat", sources.wechat).put("sourceAlipay", sources.alipay).put("sourceCmb", sources.cmb)
        .put("correlationWindowSeconds", sources.correlationWindowSeconds).put("defaultLedgerId", defaultLedgerId).put("displayCurrency", displayCurrency)

    private fun settings(value: JSONObject) = PortableAppSettings(
        SourceSettings(value.getBoolean("sourceWechat"), value.getBoolean("sourceAlipay"), value.getBoolean("sourceCmb"), value.getInt("correlationWindowSeconds")),
        value.getString("defaultLedgerId"), value.getString("displayCurrency"),
    )
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
private fun JSONObject.nullableString(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
private fun JSONObject.nullableLong(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)
private fun JSONObject.nullableDouble(name: String): Double? = if (!has(name) || isNull(name)) null else getDouble(name)
private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> = (0 until length()).map { transform(getJSONObject(it)) }
