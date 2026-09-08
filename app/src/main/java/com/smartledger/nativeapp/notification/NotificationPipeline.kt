package com.smartledger.nativeapp.notification

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.smartledger.domain.merchant.MerchantNormalizer
import com.smartledger.domain.model.*
import com.smartledger.domain.parser.CompositeNotificationParser
import com.smartledger.domain.repository.CategoryRepository
import com.smartledger.domain.repository.MerchantMemoryRepository
import com.smartledger.domain.repository.TransactionRepository
import com.smartledger.domain.usecase.ProcessParsedTransaction
import com.smartledger.domain.usecase.TransactionCorrelationPolicy
import com.smartledger.nativeapp.MainActivity
import com.smartledger.nativeapp.R
import com.smartledger.nativeapp.data.local.RawNotificationDao
import com.smartledger.nativeapp.data.local.RawNotificationEntity
import com.smartledger.nativeapp.data.settings.AppSettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object NotificationListenerHealth {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    fun markConnected() { _connected.value = true }
    fun markDisconnected() { _connected.value = false }
}

data class NotificationDebugEntry(
    val time: Long,
    val packageName: String,
    val stage: String,
    val status: String,
    val detail: String,
    val title: String?,
    val raw: String,
    val correlationId: String,
)

@Singleton class NotificationDebugStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("notification_debug", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(readEntries())
    val entries: StateFlow<List<NotificationDebugEntry>> = _entries.asStateFlow()

    @Synchronized fun log(packageName: String, stage: String, status: String, detail: String, title: String? = null, raw: String = "", correlationId: String = "") {
        val old = runCatching { JSONArray(prefs.getString("logs", "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray().put(JSONObject().put("time", System.currentTimeMillis()).put("packageName", packageName).put("stage", stage).put("status", status).put("detail", detail).put("title", title).put("raw", raw.take(1200)).put("correlationId", correlationId))
        for (i in 0 until minOf(old.length(), 99)) next.put(old.getJSONObject(i))
        prefs.edit().putString("logs", next.toString()).apply()
        _entries.value = readEntries(next)
    }
    @Synchronized fun clear() { prefs.edit().remove("logs").apply(); _entries.value = emptyList() }
    private fun readEntries(values: JSONArray = runCatching { JSONArray(prefs.getString("logs", "[]")) }.getOrDefault(JSONArray())) = (0 until values.length()).map { index ->
        values.getJSONObject(index).let { row ->
            NotificationDebugEntry(
                time = row.optLong("time"),
                packageName = row.optString("packageName", row.optString("source")),
                stage = row.optString("stage", "PARSER"),
                status = row.optString("status"),
                detail = row.optString("detail"),
                title = row.optString("title").takeIf { it.isNotBlank() && it != "null" },
                raw = row.optString("raw"),
                correlationId = row.optString("correlationId"),
            )
        }
    }
}

@Singleton class NotificationProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: CompositeNotificationParser,
    private val process: ProcessParsedTransaction,
    private val debug: NotificationDebugStore,
    private val settings: AppSettingsStore,
    private val rawDao: RawNotificationDao,
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val memories: MerchantMemoryRepository,
    private val normalizer: MerchantNormalizer,
) {
    private val processMutex = Mutex()
    private val resultNotificationHistory = context.getSharedPreferences("result_notification_history", Context.MODE_PRIVATE)
    private val resultNotificationDue = context.getSharedPreferences("result_notification_due", Context.MODE_PRIVATE)
    private val resultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preciseResultJobs = ConcurrentHashMap<String, Job>()
    suspend fun processNow(raw: RawNotification, notificationKey: String, attempt: Int = 1, force: Boolean = false) = processMutex.withLock { processNowLocked(raw, notificationKey, attempt, force) }
    private suspend fun processNowLocked(raw: RawNotification, notificationKey: String, attempt: Int, force: Boolean) {
        val eventFingerprint = sha256("${raw.packageName}|$notificationKey|${raw.postedAt}|${raw.title}|${raw.text}")
        val previous = rawDao.findByFingerprint(eventFingerprint)
        if (!force && previous != null && previous.status in setOf("CREATED", "MERGED", "DUPLICATE", "REJECTED")) {
            debug.log(raw.packageName, "PROCESSOR", "DUPLICATE_EVENT", "相同系统通知已经处理", raw.title, raw.text.orEmpty(), notificationKey)
            return
        }
        val eventId = previous?.id ?: UUID.randomUUID().toString()
        if (previous == null) {
            val inserted = rawDao.insert(RawNotificationEntity(eventId, eventFingerprint, notificationKey, raw.packageName, raw.title, raw.text, raw.postedAt, "PROCESSING", attempt, null, null, System.currentTimeMillis(), null))
            if (inserted == -1L) return
        } else rawDao.markProcessing(eventId, attempt)
        try {
            if (!settings.isPackageEnabled(raw.packageName)) {
                val reason = "该通知来源已在设置中关闭"
                rawDao.finish(eventId, "REJECTED", attempt, null, reason, System.currentTimeMillis())
                debug.log(raw.packageName, "SETTINGS", "FILTERED", reason, raw.title, raw.text.orEmpty(), notificationKey)
                return
            }
            val parsed = parser.parse(raw)
            if (parsed == null) {
                val reason = "不是账务通知、属于活动推广或缺少有效金额"
                rawDao.finish(eventId, "REJECTED", attempt, null, reason, System.currentTimeMillis())
                debug.log(raw.packageName, "PARSER", "REJECTED", reason, raw.title, raw.text.orEmpty(), notificationKey)
                return
            }
            val correlationWindowMillis = settings.correlationWindowMillis()
            val existing = transactions.findCorrelationCandidate(parsed.sourceType, parsed.amountMinor, parsed.currency, parsed.direction, parsed.occurredAtEpochMillis, correlationWindowMillis)
            if (existing != null && TransactionCorrelationPolicy.sourcesCompatible(existing.sourceType, parsed.sourceType) && clusterPaymentCompatible(existing, parsed) && merchantsCompatible(existing, parsed)) {
                val enriched = enrich(existing, parsed)
                transactions.update(enriched)
                rawDao.finish(eventId, "MERGED", attempt, existing.id, null, System.currentTimeMillis())
                debug.log(raw.packageName, "CORRELATION", "MERGED", "${parsed.sourceApp} 关联到${existing.sourceApp}；窗口=${correlationWindowMillis / 1_000}秒；簇=${correlationKey(parsed, correlationWindowMillis)}；商户=${enriched.merchantName ?: "未知"}", raw.title, parsed.rawText, notificationKey)
                scheduleResult(enriched.id)
                return
            }
            val saved = process(parsed)
            if (saved == null) {
                rawDao.finish(eventId, "DUPLICATE", attempt, null, "相同来源交易已存在", System.currentTimeMillis())
                debug.log(raw.packageName, "DEDUP", "DUPLICATE", "${parsed.sourceApp} 相同交易已存在", raw.title, parsed.rawText, notificationKey)
                return
            }
            rawDao.finish(eventId, "CREATED", attempt, saved.id, null, System.currentTimeMillis())
            debug.log(raw.packageName, "PARSER", saved.status.name, "${parsed.sourceApp}；金额=${saved.amountMinor} ${saved.currency}；商户=${saved.merchantName ?: "未知"}", raw.title, parsed.rawText, notificationKey)
            scheduleResult(saved.id)
        } catch (error: Throwable) {
            val message = "${error::class.java.simpleName}: ${error.message ?: "未知异常"}"
            rawDao.finish(eventId, "FAILED", attempt, null, message.take(500), System.currentTimeMillis())
            debug.log(raw.packageName, "PROCESSOR", "FAILED", message, raw.title, raw.text.orEmpty(), notificationKey)
            throw error
        }
    }
    private fun merchantsCompatible(old: Transaction, new: ParsedTransaction): Boolean {
        val oldMerchant = old.merchantName
        val newMerchant = new.merchantName
        if (oldMerchant == null || newMerchant == null) return true
        if (normalizer.normalize(oldMerchant).key == normalizer.normalize(newMerchant).key) return true
        val hasRichBankEvidence = old.sourceType == SourceType.CMB_LIFE || new.sourceType == SourceType.CMB_LIFE
        return hasRichBankEvidence && kotlin.math.abs(old.occurredAtEpochMillis - new.occurredAtEpochMillis) <= 90_000
    }
    private fun clusterPaymentCompatible(old: Transaction, new: ParsedTransaction): Boolean {
        val oldChannel = when {
            old.sourceType in setOf(SourceType.WECHAT, SourceType.ALIPAY) -> old.sourceType
            Regex("财付通|微信支付").containsMatchIn(old.rawNotification.orEmpty()) -> SourceType.WECHAT
            Regex("支付宝").containsMatchIn(old.rawNotification.orEmpty()) -> SourceType.ALIPAY
            else -> null
        }
        val newChannel = new.sourceType.takeIf { it in setOf(SourceType.WECHAT, SourceType.ALIPAY) }
        return oldChannel == null || newChannel == null || oldChannel == newChannel
    }
    private fun correlationKey(value: ParsedTransaction, windowMillis: Long): String {
        val bucket = value.occurredAtEpochMillis / windowMillis.coerceAtLeast(1_000L)
        return sha256("${value.amountMinor}|${value.currency}|${value.direction}|$bucket").take(12)
    }
    private suspend fun enrich(old: Transaction, new: ParsedTransaction): Transaction {
        val newWins = sourcePriority(new.sourceType) > sourcePriority(old.sourceType)
        val merchant = if (new.merchantName != null && (old.merchantName == null || newWins)) new.merchantName else old.merchantName
        val memory = merchant?.let { memories.find(normalizer.normalize(it).key) }
        val rememberedCategory = memory?.categoryId?.let { categories.findById(it) ?: DefaultCategories.firstOrNull { category -> category.id == it } }
        val resolvedType = TransactionCorrelationPolicy.resolvePrimarySource(old.sourceType, new.sourceType, newWins)
        val resolvedApp = if (resolvedType == new.sourceType) new.sourceApp else old.sourceApp
        val evidence = listOfNotNull(old.rawNotification, "[来源:${new.sourceType.name}] ${new.rawText}").distinct().joinToString("\n---\n")
        val isEleme = merchant?.let { normalizer.normalize(it).canonicalName == "饿了么" } == true
        val delivery = if (isEleme) categories.observeAll().first().firstOrNull { it.name == "外卖" && it.parentId != null } else null
        val categoryId = memory?.categoryId
            ?: if (delivery != null && (old.categoryId == null || old.categoryId == fallbackCategoryId(CategoryType.EXPENSE))) delivery.id else old.categoryId
        return old.copy(
            sourceApp = resolvedApp,
            sourceType = resolvedType,
            merchantName = merchant,
            categoryId = categoryId,
            primaryCategoryId = rememberedCategory?.let { it.parentId ?: it.id } ?: delivery?.parentId ?: old.primaryCategoryId,
            ledgerId = old.ledgerId,
            sceneConfidence = memory?.let { maxOf(old.sceneConfidence, it.confidence) } ?: if (isEleme) maxOf(old.sceneConfidence, .92f) else old.sceneConfidence,
            status = if (memory?.categoryId != null || isEleme) TransactionStatus.CONFIRMED else old.status,
            parserConfidence = maxOf(old.parserConfidence, new.confidence),
            rawNotification = evidence.take(4000),
            bankCardLast4 = old.bankCardLast4 ?: new.bankCardLast4,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }
    private fun sourcePriority(source: SourceType) = when (source) { SourceType.CMB_LIFE -> 100; SourceType.WECHAT, SourceType.ALIPAY -> 80; SourceType.CMB -> 60; else -> 20 }
    private suspend fun scheduleResult(transactionId: String) {
        val delayMillis = settings.correlationWindowMillis()
        val dueAt = System.currentTimeMillis() + delayMillis
        resultNotificationDue.edit().putLong(transactionId, dueAt).apply()
        preciseResultJobs.remove(transactionId)?.cancel()
        preciseResultJobs[transactionId] = resultScope.launch {
            delay(delayMillis)
            showFinalResult(transactionId)
            preciseResultJobs.remove(transactionId)
        }
        val request = OneTimeWorkRequestBuilder<TransactionResultNotificationWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(TransactionResultNotificationWorker.KEY_TRANSACTION_ID to transactionId))
            .addTag(TransactionResultNotificationWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "transaction-result-$transactionId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
        debug.log(context.packageName, "RESULT_NOTIFICATION", "DELAYED", "等待${delayMillis / 1_000}秒合并窗口后发送最终结果", correlationId = transactionId)
    }
    suspend fun showFinalResult(transactionId: String) {
        transactions.findById(transactionId)?.let(::showResult)
    }
    suspend fun flushDueResultNotifications(now: Long = System.currentTimeMillis()) {
        resultNotificationDue.all.entries
            .mapNotNull { (transactionId, value) -> (value as? Long)?.takeIf { it <= now }?.let { transactionId } }
            .forEach { transactionId ->
                preciseResultJobs.remove(transactionId)?.cancel()
                showFinalResult(transactionId)
            }
    }
    fun cancelScheduledResult(transactionId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("transaction-result-$transactionId")
        claimResultNotification(transactionId)
    }
    fun showResult(value: Transaction) {
        if (!claimResultNotification(value.id)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "自动记账", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java).putExtra("openPending", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pending = value.status == TransactionStatus.PENDING_CONFIRMATION
        manager.notify(value.id.hashCode(), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_ledger).setContentTitle(if (pending) "有一笔交易需要确认" else "已自动记账").setContentText("${value.merchantName ?: "未知商户"} · ${formatMoney(value.amountMinor, value.currency)}").setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }
    @Synchronized private fun claimResultNotification(transactionId: String): Boolean {
        if (resultNotificationHistory.contains(transactionId)) {
            resultNotificationDue.edit().remove(transactionId).apply()
            return false
        }
        resultNotificationDue.edit().remove(transactionId).apply()
        val editor = resultNotificationHistory.edit().putLong(transactionId, System.currentTimeMillis())
        if (resultNotificationHistory.all.size >= 1_000) {
            resultNotificationHistory.all.entries
                .mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }
                .sortedBy { it.second }
                .take(200)
                .forEach { editor.remove(it.first) }
        }
        editor.apply()
        return true
    }
    private fun formatMoney(value: Long, currency: String): String {
        val symbol = mapOf("CNY" to "¥", "JPY" to "¥", "USD" to "\$", "GBP" to "£", "EUR" to "€", "HKD" to "HK\$", "AUD" to "A\$", "CAD" to "C\$", "SGD" to "S\$", "KRW" to "₩", "THB" to "฿")[currency] ?: "$currency "
        return if (currency in setOf("JPY", "KRW")) "$symbol$value" else "$symbol${value / 100}.${(value % 100).toString().padStart(2, '0')}"
    }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    companion object { const val CHANNEL = "ledger_pipeline" }
}

private data class RealtimeNotificationJob(val raw: RawNotification, val notificationKey: String)

@Singleton class NotificationRealtimeConsumer @Inject constructor(
    private val processor: NotificationProcessor,
    private val debug: NotificationDebugStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<RealtimeNotificationJob>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in queue) {
                debug.log(job.raw.packageName, "REALTIME", "RUNNING", "进程内 Actor 开始消费", job.raw.title, job.raw.text.orEmpty(), job.notificationKey)
                runCatching { processor.processNow(job.raw, job.notificationKey) }
                    .onSuccess { debug.log(job.raw.packageName, "REALTIME", "SUCCEEDED", "进程内 Actor 消费完成；WorkManager 保留兜底", job.raw.title, correlationId = job.notificationKey) }
                    .onFailure { error -> debug.log(job.raw.packageName, "REALTIME", "FAILED", "${error::class.java.simpleName}: ${error.message ?: "未知异常"}；等待 WorkManager 重试", job.raw.title, job.raw.text.orEmpty(), job.notificationKey) }
            }
        }
    }

    fun submit(raw: RawNotification, notificationKey: String) {
        val result = queue.trySend(RealtimeNotificationJob(raw, notificationKey))
        debug.log(raw.packageName, "REALTIME", if (result.isSuccess) "ENQUEUED" else "FAILED", if (result.isSuccess) "已进入进程内 Actor 队列" else "Actor 队列不可用；由 WorkManager 兜底", raw.title, raw.text.orEmpty(), notificationKey)
    }
}

@EntryPoint @InstallIn(SingletonComponent::class)
interface NotificationWorkerEntryPoint { fun processor(): NotificationProcessor; fun debugStore(): NotificationDebugStore }

class TransactionResultNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val transactionId = inputData.getString(KEY_TRANSACTION_ID) ?: return Result.failure()
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, NotificationWorkerEntryPoint::class.java)
        return runCatching {
            entryPoint.processor().showFinalResult(transactionId)
            Result.success()
        }.getOrElse { Result.retry() }
    }
    companion object {
        const val KEY_TRANSACTION_ID = "transactionId"
        const val TAG = "transaction-result-notification"
    }
}

class NotificationQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_WORK, "后台识别", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_ledger).setContentTitle("拾穗正在识别交易")
            .setSilent(true).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        return ForegroundInfo(id.hashCode(), notification)
    }
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, NotificationWorkerEntryPoint::class.java)
        val processor = entryPoint.processor()
        val debug = entryPoint.debugStore()
        val packageName = inputData.getString(KEY_PACKAGE) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE)
        val text = inputData.getString(KEY_TEXT)
        val attempt = runAttemptCount + 1
        val correlationId = inputData.getString(KEY_NOTIFICATION).orEmpty()
        debug.log(packageName, "WORKER", "RUNNING", "任务=$id；第${attempt}次执行", title, text.orEmpty(), correlationId)
        return runCatching {
            processor.processNow(RawNotification(packageName, title, text, inputData.getLong(KEY_TIME, System.currentTimeMillis())), inputData.getString(KEY_NOTIFICATION) ?: "unknown-$id", attempt)
            debug.log(packageName, "WORKER", "SUCCEEDED", "任务=$id；第${attempt}次执行完成", title, correlationId = correlationId)
            Result.success()
        }.getOrElse { error ->
            val retry = runAttemptCount < 3
            debug.log(packageName, "WORKER", if (retry) "RETRY" else "FAILED", "任务=$id；${error::class.java.simpleName}: ${error.message ?: "未知异常"}", title, text.orEmpty(), correlationId)
            if (retry) Result.retry() else Result.failure()
        }
    }
    companion object { const val TAG = "notification-pipeline"; const val CHANNEL_WORK = "ledger_worker"; const val KEY_PACKAGE = "package"; const val KEY_TITLE = "title"; const val KEY_TEXT = "text"; const val KEY_TIME = "time"; const val KEY_NOTIFICATION = "notificationKey" }
}

@AndroidEntryPoint class LedgerNotificationListener : NotificationListenerService() {
    @Inject lateinit var parser: CompositeNotificationParser
    @Inject lateinit var debug: NotificationDebugStore
    @Inject lateinit var realtime: NotificationRealtimeConsumer
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationListenerHealth.markConnected()
        val active = runCatching { activeNotifications.orEmpty().filter { parser.supports(it.packageName) } }.getOrDefault(emptyList())
        runCatching { debug.log(packageName, "LISTENER", "CONNECTED", "系统通知监听服务已连接；补捞${active.size}条仍在通知栏的支持来源通知") }
        active.forEach { notification ->
            runCatching { handlePosted(notification, recovered = true) }
                .onFailure { error -> safeCallbackFailure(notification.packageName, notification.key, "RECOVERY", error) }
        }
    }
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationListenerHealth.markDisconnected()
        runCatching { debug.log(packageName, "LISTENER", "DISCONNECTED", "系统通知监听服务断开；请求重新绑定") }
        runCatching { requestRebind(ComponentName(this, LedgerNotificationListener::class.java)) }
    }
    override fun onDestroy() {
        NotificationListenerHealth.markDisconnected()
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handlePosted(sbn, recovered = false) }
            .onFailure { error -> safeCallbackFailure(sbn.packageName, sbn.key, "POSTED", error) }
    }
    private fun safeCallbackFailure(sourcePackage: String, notificationKey: String, callback: String, error: Throwable) {
        runCatching { debug.log(sourcePackage, "LISTENER_CALLBACK", "FAILED", "$callback ${error::class.java.simpleName}: ${error.message ?: "未知异常"}", correlationId = notificationKey) }
    }
    private fun handlePosted(sbn: StatusBarNotification, recovered: Boolean) {
        if (!parser.supports(sbn.packageName)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT), extras.getCharSequence(Notification.EXTRA_BIG_TEXT), extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")).joinToString(" ")
        debug.log(sbn.packageName, "PRODUCER", if (recovered) "RECOVERED" else "RECEIVED", "key=${sbn.key}; postTime=${sbn.postTime}", title, text, sbn.key)
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            debug.log(sbn.packageName, "PRODUCER", "FILTERED", "通知分组摘要，不进入解析队列", title, text, sbn.key)
            return
        }
        if (title.isNullOrBlank() && text.isBlank()) {
            debug.log(sbn.packageName, "PRODUCER", "FILTERED", "标题和正文均为空", title, correlationId = sbn.key)
            return
        }
        val data = workDataOf(NotificationQueueWorker.KEY_PACKAGE to sbn.packageName, NotificationQueueWorker.KEY_TITLE to title?.take(240), NotificationQueueWorker.KEY_TEXT to text.take(3500), NotificationQueueWorker.KEY_TIME to sbn.postTime, NotificationQueueWorker.KEY_NOTIFICATION to sbn.key.take(500))
        val request = OneTimeWorkRequestBuilder<NotificationQueueWorker>().setInputData(data).addTag(NotificationQueueWorker.TAG).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(this).enqueueUniqueWork("notification-${sbn.key.hashCode()}-${sbn.postTime}", ExistingWorkPolicy.KEEP, request)
        debug.log(sbn.packageName, "PRODUCER", "ENQUEUED", "任务=${request.id}", title, text, sbn.key)
        realtime.submit(RawNotification(sbn.packageName, title, text, sbn.postTime), sbn.key)
    }
}
