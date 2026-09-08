package com.smartledger.nativeapp.ui

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.asFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.smartledger.domain.model.*
import com.smartledger.domain.merchant.MerchantMemoryService
import com.smartledger.domain.merchant.MerchantNormalizer
import com.smartledger.domain.repository.*
import com.smartledger.domain.usecase.ConfirmTransaction
import com.smartledger.domain.parser.extractBankCardLast4
import com.smartledger.nativeapp.data.local.RawNotificationDao
import com.smartledger.nativeapp.data.local.RawNotificationEntity
import com.smartledger.nativeapp.notification.*
import com.smartledger.nativeapp.location.LocationTransitionStore
import com.smartledger.nativeapp.data.settings.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

data class LedgerUiState(val transactions: List<Transaction> = emptyList(), val pending: List<Transaction> = emptyList(), val ledgers: List<Ledger> = emptyList(), val categories: List<Category> = DefaultCategories, val isLoading: Boolean = true)
data class WorkQueueStats(val enqueued: Int = 0, val running: Int = 0, val succeeded: Int = 0, val failed: Int = 0, val blocked: Int = 0, val cancelled: Int = 0)
data class NotificationDiagnosticsState(val rawEvents: List<RawNotificationEntity> = emptyList(), val chainLogs: List<NotificationDebugEntry> = emptyList(), val queue: WorkQueueStats = WorkQueueStats())
data class DiagnosticPermissions(val listenerEnabled: Boolean, val listenerConnected: Boolean, val appNotificationsEnabled: Boolean, val batteryOptimizationIgnored: Boolean)
enum class LocalDataScope { TRANSACTIONS, MERCHANT_MEMORY, CUSTOM_LEDGERS, LOCATION_SCENES, DIAGNOSTICS, SETTINGS }

@HiltViewModel class LedgerViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val ledgerRepository: LedgerRepository,
    private val merchantMemoryRepository: MerchantMemoryRepository,
    private val merchantMemoryService: MerchantMemoryService,
    private val merchantNormalizer: MerchantNormalizer,
    private val categoryRepository: CategoryRepository,
    private val confirmTransaction: ConfirmTransaction,
    private val clock: Clock,
    private val processor: NotificationProcessor,
    private val debugStore: NotificationDebugStore,
    private val rawNotificationDao: RawNotificationDao,
    private val locationPlaceDao: com.smartledger.nativeapp.data.local.LocationPlaceDao,
    private val settingsStore: AppSettingsStore,
    private val locationTransitionStore: LocationTransitionStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val workManager = WorkManager.getInstance(context)
    val state = combine(transactionRepository.observeAll(), transactionRepository.observePending(), ledgerRepository.observeAll(), categoryRepository.observeAll()) { tx, pending, ledgers, categories ->
        LedgerUiState(tx, pending, ledgers, categories.ifEmpty { DefaultCategories }, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LedgerUiState())
    val sourceSettings = settingsStore.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SourceSettings())
    val defaultLedgerId = settingsStore.defaultLedgerId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "daily")
    val ledgerCoverUris = settingsStore.ledgerCoverUris.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val merchantMappings = merchantMemoryRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val listenerConnected = NotificationListenerHealth.connected
    val locationTransition = locationTransitionStore.transition
    val locationConsent = locationTransitionStore.consent
    val locationConsentPromptNeeded = locationTransitionStore.consentPromptNeeded
    val diagnostics = combine(rawNotificationDao.observeRecent(), debugStore.entries, workManager.getWorkInfosByTagLiveData(NotificationQueueWorker.TAG).asFlow()) { raw, logs, work ->
        NotificationDiagnosticsState(
            rawEvents = raw,
            chainLogs = logs,
            queue = WorkQueueStats(
                enqueued = work.count { it.state == WorkInfo.State.ENQUEUED },
                running = work.count { it.state == WorkInfo.State.RUNNING },
                succeeded = work.count { it.state == WorkInfo.State.SUCCEEDED },
                failed = work.count { it.state == WorkInfo.State.FAILED },
                blocked = work.count { it.state == WorkInfo.State.BLOCKED },
                cancelled = work.count { it.state == WorkInfo.State.CANCELLED },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationDiagnosticsState())

    init { viewModelScope.launch {
        categoryRepository.seedDefaults(); ledgerRepository.activeDefault(); locationTransitionStore.refresh()
        val categories = categoryRepository.observeAll().first()
        val deliveryCategoryId = categories.firstOrNull { it.name == "外卖" && it.parentId != null }?.id ?: defaultCategoryByName("外卖")!!.id
        val existingTransactions = transactionRepository.observeAll().first()
        val backfills = context.getSharedPreferences("data_backfills", Context.MODE_PRIVATE)
        if (!backfills.getBoolean("normalized_merchant_keys_v1", false)) {
            existingTransactions.forEach { transactionRepository.update(it) }
            backfills.edit().putBoolean("normalized_merchant_keys_v1", true).apply()
        }
        existingTransactions.filter { it.bankCardLast4 == null && !it.rawNotification.isNullOrBlank() }.forEach { transaction ->
            extractBankCardLast4(transaction.rawNotification!!)?.let { last4 ->
                transactionRepository.update(transaction.copy(bankCardLast4 = last4, updatedAtEpochMillis = clock.nowEpochMillis()))
            }
        }
        existingTransactions.filter { it.status == TransactionStatus.PENDING_CONFIRMATION && it.merchantName != null }.forEach { transaction ->
            merchantMemoryRepository.find(merchantNormalizer.normalize(transaction.merchantName!!).key)?.let { memory ->
                memory.categoryId?.let { categoryId ->
                    confirmTransaction(transaction, categoryId, memory.preferredLedgerId ?: transaction.ledgerId ?: "daily", false)
                }
            }
        }
        val elemeTransactions = existingTransactions.filter { transaction ->
            transaction.merchantName?.let { merchantNormalizer.normalize(it).canonicalName == "饿了么" } == true
        }
        elemeTransactions.filter {
            it.status == TransactionStatus.CONFIRMED && it.categoryId in setOf(null, fallbackCategoryId(CategoryType.EXPENSE))
        }.forEach { transaction ->
            confirmTransaction(transaction, deliveryCategoryId, transaction.ledgerId ?: "daily", true)
        }
        elemeTransactions.filter { it.status == TransactionStatus.PENDING_CONFIRMATION }.forEach { transaction ->
            confirmTransaction(transaction, deliveryCategoryId, transaction.ledgerId ?: "daily", true)
        }
        elemeTransactions.firstOrNull()?.let { transaction ->
            merchantMemoryService.remember(
                merchantNormalizer.normalize(transaction.merchantName!!),
                deliveryCategoryId,
                transaction.ledgerId ?: "daily",
            )
        }
        existingTransactions.filter { it.status == TransactionStatus.CONFIRMED && it.merchantName != null && it.categoryId != null }
            .filterNot { merchantNormalizer.normalize(it.merchantName!!).canonicalName == "饿了么" }
            .forEach { transaction ->
                val merchant = merchantNormalizer.normalize(transaction.merchantName!!)
                if (merchantMemoryRepository.find(merchant.key) == null) {
                    merchantMemoryService.remember(merchant, transaction.categoryId, transaction.ledgerId ?: "daily")
                }
            }
    } }
    fun confirm(value: Transaction, categoryId: String, ledgerId: String, remember: Boolean = true) = viewModelScope.launch {
        processor.cancelScheduledResult(value.id)
        confirmTransaction(value, categoryId, ledgerId, remember)
    }
    fun ignore(value: Transaction) = viewModelScope.launch { processor.cancelScheduledResult(value.id); transactionRepository.update(value.copy(status = TransactionStatus.IGNORED, updatedAtEpochMillis = clock.nowEpochMillis())) }
    fun delete(value: Transaction) = viewModelScope.launch { processor.cancelScheduledResult(value.id); transactionRepository.delete(value.id) }
    fun updateTransaction(value: Transaction, categoryId: String, ledgerId: String) = viewModelScope.launch { processor.cancelScheduledResult(value.id); confirmTransaction(value, categoryId, ledgerId, false) }
    fun confirmAll() = viewModelScope.launch {
        state.value.pending.forEach {
            processor.cancelScheduledResult(it.id)
            val categoryId = it.categoryId ?: fallbackCategoryId(if (it.direction == TransactionDirection.INCOME) CategoryType.INCOME else CategoryType.EXPENSE)
            confirmTransaction(it, categoryId, it.ledgerId ?: "daily", true)
        }
    }
    fun addManualTransaction(amountMinor: Long, merchant: String, direction: TransactionDirection, categoryId: String, ledgerId: String) = viewModelScope.launch {
        if (amountMinor <= 0) return@launch
        val now = clock.nowEpochMillis()
        transactionRepository.save(Transaction(
            id = UUID.randomUUID().toString(), sourceApp = "手动记账", sourceType = SourceType.MANUAL,
            amountMinor = amountMinor, currency = "CNY", merchantName = merchant.trim().ifBlank { "未填写商户" },
            direction = direction, categoryId = categoryId, ledgerId = ledgerId.ifBlank { "daily" },
            occurredAtEpochMillis = now, rawNotification = null, parserConfidence = 1f, sceneConfidence = 1f,
            status = TransactionStatus.CONFIRMED, fingerprint = "manual-${UUID.randomUUID()}",
            createdAtEpochMillis = now, updatedAtEpochMillis = now,
            primaryCategoryId = state.value.categories.firstOrNull { it.id == categoryId }?.let { it.parentId ?: it.id },
        ))
    }
    fun createLedger(name: String, icon: String) = viewModelScope.launch {
        val now = clock.nowEpochMillis(); val id = "ledger-$now"
        ledgerRepository.save(Ledger(id, name.trim().ifBlank { "新账本" }, LedgerType.CUSTOM, icon.ifBlank { "●" }, "CNY", isActive = true, createdAtEpochMillis = now, updatedAtEpochMillis = now))
    }
    fun deleteEmptyLedger(ledger: Ledger) = viewModelScope.launch {
        if (ledger.id == "daily" || ledger.type == LedgerType.DEFAULT) return@launch
        if (state.value.transactions.none { it.ledgerId == ledger.id }) ledgerRepository.delete(ledger.id)
    }
    fun refreshLocationTransition() = viewModelScope.launch { locationTransitionStore.refresh() }
    fun setLocationConsent(enabled: Boolean) { locationTransitionStore.setConsent(enabled); if (enabled) refreshLocationTransition() }
    fun clearLocationHistory() = locationTransitionStore.clearHistory()
    fun keepCurrentLocationLedger() = locationTransitionStore.keepCurrentLedger()
    fun switchLocationLedger(ledgerId: String) = viewModelScope.launch { ledgerRepository.setDefault(ledgerId); locationTransitionStore.activateLedger(ledgerId) }
    fun createLocationLedger(name: String) = viewModelScope.launch {
        val now = clock.nowEpochMillis()
        val ledger = Ledger("ledger-$now", name.trim().ifBlank { "旅行账本" }, LedgerType.TRAVEL, "●", "CNY", isActive = true, createdAtEpochMillis = now, updatedAtEpochMillis = now)
        ledgerRepository.save(ledger)
        ledgerRepository.setDefault(ledger.id)
        locationTransitionStore.activateLedger(ledger.id)
    }
    fun createCategory(name: String, icon: String, parentId: String?) = viewModelScope.launch {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return@launch
        val parent = parentId?.let { categoryRepository.findById(it) }
        val type = parent?.type ?: CategoryType.EXPENSE
        val iconKey = parent?.iconKey ?: "circle-question-mark"
        categoryRepository.save(Category("custom-${UUID.randomUUID()}", cleanName, iconKey, builtIn = false, parentId = parentId, type = type, iconKey = iconKey, sortOrder = 9000, userCustom = true, createdAtEpochMillis = clock.nowEpochMillis(), updatedAtEpochMillis = clock.nowEpochMillis()))
    }
    fun updateMerchantMapping(mapping: MerchantMemory, categoryId: String) = viewModelScope.launch {
        val category = categoryRepository.findById(categoryId) ?: DefaultCategories.firstOrNull { it.id == categoryId }
        val now = clock.nowEpochMillis()
        merchantMemoryRepository.save(
            mapping.copy(
                categoryId = categoryId,
                confidence = 1f,
                source = MemorySource.USER,
                updatedAtEpochMillis = now,
            ),
        )
        transactionRepository.updateCategoryForMerchant(mapping.merchantKey, categoryId, category?.parentId ?: category?.id, now)
    }
    fun runParserSelfTest() {
        val now = clock.nowEpochMillis()
        viewModelScope.launch {
            processor.processNow(RawNotification("com.tencent.mm", "微信支付", "商户：星巴克 支付成功 ¥38.00", now), "mock-wechat-$now")
            processor.processNow(RawNotification("com.eg.android.AlipayGphone", "支付宝", "收款方：测试超市 付款成功 68.50元", now + 1), "mock-alipay-$now")
            processor.processNow(RawNotification("cmb.pb", "招商银行", "快捷支付人民币1,280.60元 商户：测试酒店", now + 2), "mock-cmb-$now")
            processor.processNow(RawNotification("com.cmbchina.ccd.pluto.cmbActivity", "交易提醒", "您在支付宝-测试餐厅有一笔106.50人民币的消费已成功", now + 3), "mock-cmb-life-$now")
        }
    }
    fun diagnosticPermissions() = DiagnosticPermissions(
        listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        listenerConnected = NotificationListenerHealth.connected.value,
        appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        batteryOptimizationIgnored = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    )
    fun forceResetNotificationListener() = viewModelScope.launch {
        val component = ComponentName(context, LedgerNotificationListener::class.java)
        debugStore.log(context.packageName, "LISTENER_CONTROL", "RESETTING", "请求系统真正解绑通知监听，再重新绑定")
        NotificationListenerHealth.markDisconnected()
        val systemUnbound = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching {
            NotificationListenerService.requestUnbind(component)
        }.isSuccess else false
        if (!systemUnbound) {
            context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            delay(500)
            context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
        delay(800)
        NotificationListenerService.requestRebind(component)
        delay(1_500)
        if (!NotificationListenerHealth.connected.value) NotificationListenerService.requestRebind(component)
        debugStore.log(context.packageName, "LISTENER_CONTROL", "REBIND_REQUESTED", if (systemUnbound) "已执行系统解绑→重绑" else "旧系统已执行组件重置→重绑")
    }
    fun openNotificationListenerSettings() {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun clearDiagnostics() = viewModelScope.launch { debugStore.clear(); rawNotificationDao.clearCompleted() }
    fun resolveDuplicate(eventId: String) = viewModelScope.launch { rawNotificationDao.resolveDuplicate(eventId, clock.nowEpochMillis()) }
    fun retryRawEvent(event: RawNotificationEntity) = viewModelScope.launch {
        retryRawEventNow(event)
    }
    fun retryRawEvents(events: List<RawNotificationEntity>) = viewModelScope.launch {
        events.sortedBy { it.postedAt }.forEach { retryRawEventNow(it) }
    }
    private suspend fun retryRawEventNow(event: RawNotificationEntity) = processor.processNow(
        RawNotification(event.packageName, event.title, event.text, event.postedAt),
        event.notificationKey,
        event.attemptCount + 1,
        force = true,
    )
    fun setSource(name: String, enabled: Boolean) = viewModelScope.launch { settingsStore.setSource(name, enabled) }
    fun setCorrelationWindowSeconds(seconds: Int) = viewModelScope.launch { settingsStore.setCorrelationWindowSeconds(seconds) }
    fun setDefaultLedger(ledgerId: String) = viewModelScope.launch { ledgerRepository.setDefault(ledgerId); locationTransitionStore.activateLedger(ledgerId) }
    fun setLedgerCover(ledgerId: String, uri: String) = viewModelScope.launch { settingsStore.setLedgerCoverUri(ledgerId, uri) }
    fun clearLocalData(scopes: Set<LocalDataScope>) = viewModelScope.launch {
        if (LocalDataScope.TRANSACTIONS in scopes) transactionRepository.clearAll()
        if (LocalDataScope.MERCHANT_MEMORY in scopes) merchantMemoryRepository.clearAll()
        if (LocalDataScope.CUSTOM_LEDGERS in scopes) ledgerRepository.clearCustom()
        if (LocalDataScope.LOCATION_SCENES in scopes) {
            locationPlaceDao.clearAll()
            locationTransitionStore.clearHistory()
            context.getSharedPreferences("scene_context", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("travel_detector", Context.MODE_PRIVATE).edit().clear().apply()
        }
        if (LocalDataScope.DIAGNOSTICS in scopes) { debugStore.clear(); rawNotificationDao.clearAll(); workManager.pruneWork() }
        if (LocalDataScope.SETTINGS in scopes) settingsStore.clear()
        ledgerRepository.activeDefault()
    }
}
