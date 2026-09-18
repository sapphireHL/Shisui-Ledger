package com.smartledger.nativeapp.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.smartledger.domain.model.Category
import com.smartledger.domain.model.CategoryType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONArray

data class AiConnectionState(
    val zhipuConnected: Boolean = false,
    val zhipuModels: List<String> = emptyList(),
    val defaultZhipuModel: String = "",
    val doubaoConnected: Boolean = false,
    val doubaoModels: List<String> = emptyList(),
    val defaultDoubaoModel: String = "",
    val activeProvider: AiProvider = AiProvider.ZHIPU,
    val loadingModels: Boolean = false,
    val modelsError: String? = null,
)

enum class AiProvider(val displayName: String) { ZHIPU("智谱 AI"), DOUBAO("火山方舟 AI") }

/** 火山方舟 Responses API 预置模型；也允许使用控制台创建的 ep- 推理接入点。 */
val arkPresetModelOptions = listOf(
    "doubao-seed-2-1-turbo-260628",
    "doubao-seed-2-1-pro-260628",
    "doubao-seed-2-0-lite-260215",
    "glm-5.3-flash",
    "deepseek-v3-flash",
)

data class AiCategoryTestResult(
    val model: String,
    val normalizedMerchantName: String,
    val primaryCategoryId: String?,
    val primaryCategory: String,
    val secondaryCategoryId: String?,
    val secondaryCategory: String?,
    val primaryIsNew: Boolean,
    val secondaryIsNew: Boolean,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val merchantMatchTerms: List<String> = emptyList(),
    val confidence: Double = 0.0,
    val rawResponse: String = "",
)

data class AiPromptPreview(val systemPrompt: String, val userPrompt: String)

data class AiRequestLog(
    val id: String,
    val createdAt: Long,
    val merchant: String,
    val provider: String,
    val model: String,
    val systemPrompt: String,
    val userPrompt: String,
    val response: String,
    val error: String?,
)

@Singleton class AiRequestLogStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.noBackupFilesDir, "ai_request_logs_v1.json")
    private val _logs = MutableStateFlow(load())
    val logs: StateFlow<List<AiRequestLog>> = _logs.asStateFlow()

    @Synchronized fun record(log: AiRequestLog) {
        val next = (listOf(log) + _logs.value).take(100)
        file.writeText(JSONArray(next.map { item -> JSONObject()
            .put("id", item.id).put("createdAt", item.createdAt).put("merchant", item.merchant)
            .put("provider", item.provider).put("model", item.model).put("systemPrompt", item.systemPrompt)
            .put("userPrompt", item.userPrompt).put("response", item.response).put("error", item.error)
        }).toString())
        _logs.value = next
    }

    @Synchronized fun clear() { file.delete(); _logs.value = emptyList() }

    private fun load(): List<AiRequestLog> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let { value -> add(AiRequestLog(
                id = value.optString("id"), createdAt = value.optLong("createdAt"), merchant = value.optString("merchant"),
                provider = value.optString("provider"), model = value.optString("model"), systemPrompt = value.optString("systemPrompt"),
                userPrompt = value.optString("userPrompt"), response = value.optString("response"),
                error = value.optString("error").takeIf { it.isNotBlank() && it != "null" },
            )) }
        }
    }.getOrDefault(emptyList())
}

@Singleton class AiCredentialStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.noBackupFilesDir, "ai_credentials_v1")
    private val modelFile = File(context.noBackupFilesDir, "ai_zhipu_model_v1")
    private val doubaoFile = File(context.noBackupFilesDir, "ai_doubao_credentials_v1")
    private val doubaoModelFile = File(context.noBackupFilesDir, "ai_doubao_model_v1")
    private val activeProviderFile = File(context.noBackupFilesDir, "ai_active_provider_v1")
    private val alias = "smart_ledger_ai_credentials_v1"

    fun hasZhipuKey(): Boolean = file.exists() && runCatching { readZhipuKey().isNotBlank() }.getOrDefault(false)
    fun clearZhipuKey() { file.delete(); modelFile.delete() }

    fun saveZhipuKey(apiKey: String) = writeEncrypted(file, apiKey.trim())

    fun readZhipuKey(): String = readEncrypted(file)

    fun saveDefaultZhipuModel(model: String) = writeEncrypted(modelFile, model.trim())
    fun readDefaultZhipuModel(): String = if (modelFile.exists()) readEncrypted(modelFile) else ""
    fun hasDoubaoKey(): Boolean = doubaoFile.exists() && runCatching { readDoubaoKey().isNotBlank() }.getOrDefault(false)
    fun clearDoubaoKey() { doubaoFile.delete(); doubaoModelFile.delete() }
    fun saveDoubaoKey(apiKey: String) = writeEncrypted(doubaoFile, apiKey.trim())
    fun readDoubaoKey(): String = readEncrypted(doubaoFile)
    fun saveDefaultDoubaoModel(model: String) = writeEncrypted(doubaoModelFile, model.trim())
    fun readDefaultDoubaoModel(): String = if (doubaoModelFile.exists()) readEncrypted(doubaoModelFile) else ""
    fun saveActiveProvider(provider: AiProvider) = writeEncrypted(activeProviderFile, provider.name)
    fun readActiveProvider(): AiProvider = if (!activeProviderFile.exists()) AiProvider.ZHIPU else runCatching { AiProvider.valueOf(readEncrypted(activeProviderFile)) }.getOrDefault(AiProvider.ZHIPU)

    private fun writeEncrypted(target: File, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        target.writeText(b64(cipher.iv) + "." + b64(body))
    }

    private fun readEncrypted(target: File): String {
        val parts = target.readText().split('.', limit = 2)
        require(parts.size == 2) { "凭据格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[0]))) }
        return cipher.doFinal(unb64(parts[1])).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    private fun b64(value: ByteArray) = android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
    private fun unb64(value: String) = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
}

@Singleton class ZhipuConnectionService @Inject constructor(private val credentials: AiCredentialStore, private val requestLogStore: AiRequestLogStore) {
    val requestLogs: StateFlow<List<AiRequestLog>> = requestLogStore.logs
    fun clearRequestLogs() = requestLogStore.clear()
    suspend fun verifyAndSave(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) { runCatching {
        val clean = apiKey.trim()
        require(clean.isNotBlank()) { "请输入 API Key" }
        val models = fetchModels(clean)
        require(models.isNotEmpty()) { "该账号未返回可用对话模型" }
        credentials.saveZhipuKey(clean)
        val saved = credentials.readDefaultZhipuModel()
        credentials.saveDefaultZhipuModel(saved.takeIf(models::contains) ?: preferredModel(models))
        credentials.saveActiveProvider(AiProvider.ZHIPU)
        models
    } }

    suspend fun models(): Result<List<String>> = withContext(Dispatchers.IO) { runCatching { fetchModels(credentials.readZhipuKey()) } }

    suspend fun verifyDoubaoAndSave(apiKey: String, modelId: String): Result<List<String>> = withContext(Dispatchers.IO) { runCatching {
        val clean = apiKey.trim()
        val cleanModel = modelId.trim()
        require(clean.isNotBlank()) { "请输入 API Key" }
        require(cleanModel.isNotBlank()) { "请选择或添加模型" }
        verifyDoubaoModel(clean, cleanModel)
        credentials.saveDoubaoKey(clean)
        credentials.saveDefaultDoubaoModel(cleanModel)
        credentials.saveActiveProvider(AiProvider.DOUBAO)
        listOf(cleanModel)
    } }

    suspend fun doubaoModels(): Result<List<String>> = withContext(Dispatchers.IO) { runCatching {
        listOf(credentials.readDefaultDoubaoModel()).filter { it.isNotBlank() }
    } }

    suspend fun classifyMerchant(merchant: String, categories: List<Category>, provider: AiProvider = activeProvider(), recordRequest: Boolean = true): Result<AiCategoryTestResult> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        var rawResponse = ""
        val result = runCatching {
        val cleanMerchant = merchant.trim()
        require(cleanMerchant.isNotBlank()) { "请输入商户名称" }
        val available = categories.filter { it.enabled && it.type == CategoryType.EXPENSE }
        val roots = available.filter { it.parentId == null }.sortedBy { it.sortOrder }
        val prompts = merchantClassificationPrompt(cleanMerchant, available)
        val apiKey = if (provider == AiProvider.DOUBAO) credentials.readDoubaoKey() else credentials.readZhipuKey()
        val model = defaultModel(provider).ifBlank { error("请先选择默认模型") }
        val request = if (provider == AiProvider.DOUBAO) JSONObject()
            .put("model", model)
            .put("input", JSONArray()
                .put(JSONObject().put("type", "message").put("role", "system").put("content", prompts.systemPrompt))
                .put(JSONObject().put("type", "message").put("role", "user").put("content", prompts.userPrompt)))
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        else JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompts.systemPrompt))
                .put(JSONObject().put("role", "user").put("content", prompts.userPrompt)))
        if (provider == AiProvider.ZHIPU) request.put("tools", JSONArray().put(JSONObject()
            .put("type", "web_search")
            .put("web_search", JSONObject()
                .put("enable", true)
                .put("search_result", true)
                .put("search_prompt", "仅当商户品牌无法可靠辨认时联网核实；优先参考百度百科、大众点评，其次品牌官网和可信商业资料。"))))
        val endpoint = if (provider == AiProvider.DOUBAO) "$DOUBAO_BASE_URL/responses" else "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 30_000; connection.readTimeout = if (provider == AiProvider.DOUBAO) 180_000 else 90_000; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            rawResponse = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = apiErrorDetail(rawResponse)
                error(when (code) {
                    401, 403 -> "API Key 无效或无权限${detailSuffix(detail)}"
                    404 -> if (provider == AiProvider.DOUBAO) "方舟 Responses API 返回 404${detailSuffix(detail)}" else "AI 请求失败（HTTP 404）${detailSuffix(detail)}"
                    429 -> "请求过于频繁，请稍后重试${detailSuffix(detail)}"
                    else -> "AI 请求失败（HTTP $code）${detailSuffix(detail)}"
                })
            }
            val response = JSONObject(rawResponse)
            val usage = response.optJSONObject("usage")
            val content = if (provider == AiProvider.DOUBAO) responsesOutputText(response)
                else response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val parsed = JSONObject(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1))
            val normalizedMerchant = parsed.optString("m", parsed.optString("normalizedMerchantName")).trim().ifBlank { error("AI 未返回商户显示名称") }
            val primaryIsNew = parsed.optInt("np", if (parsed.optBoolean("primaryIsNew", false)) 1 else 0) == 1
            val secondaryIsNew = parsed.optInt("ns", if (parsed.optBoolean("secondaryIsNew", false)) 1 else 0) == 1
            val primaryName = parsed.optString("p", parsed.optString("primaryCategory")).trim()
            val primary = if (primaryIsNew) null else (roots.firstOrNull { it.name == primaryName }
                ?: error("AI 返回的一级类目不在本机类目中"))
            require(primaryName.isNotBlank()) { "AI 未返回一级类目" }
            val secondaryName = parsed.optString("s", parsed.optString("secondaryCategory")).trim()
            val secondary = when {
                secondaryName.isBlank() -> null
                secondaryIsNew -> null
                primary == null -> error("新一级类目下的二级类目必须标记为新类目")
                else -> available.firstOrNull { it.name == secondaryName && it.parentId == primary.id }
                    ?: error("AI 返回的二级类目与一级类目不匹配")
            }
            val aliases = parsed.optJSONArray("a")?.let { array -> buildList { for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add) } }.orEmpty()
            AiCategoryTestResult(
                model, normalizedMerchant, primary?.id, primaryName, secondary?.id, secondary?.name ?: secondaryName.ifBlank { null }, primaryIsNew, secondaryIsNew,
                usage?.optInt(if (provider == AiProvider.DOUBAO) "input_tokens" else "prompt_tokens") ?: 0,
                usage?.optInt(if (provider == AiProvider.DOUBAO) "output_tokens" else "completion_tokens") ?: 0,
                usage?.optInt("total_tokens") ?: 0,
                merchantMatchTerms = aliases,
                confidence = parsed.optDouble("c", 0.0).coerceIn(0.0, 1.0),
                rawResponse = rawResponse,
            )
        } finally { connection.disconnect() }
        }
        if (recordRequest) {
            val preview = merchantClassificationPrompt(merchant, categories)
            runCatching { requestLogStore.record(AiRequestLog(
                id = UUID.randomUUID().toString(), createdAt = startedAt, merchant = merchant.trim(),
                provider = provider.displayName, model = runCatching { defaultModel(provider) }.getOrDefault(""),
                systemPrompt = preview.systemPrompt, userPrompt = preview.userPrompt,
                response = rawResponse, error = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" },
            )) }
        }
        result
    }

    fun merchantClassificationPrompt(merchant: String, categories: List<Category>): AiPromptPreview {
        val available = categories.filter { it.enabled && it.type == CategoryType.EXPENSE }
        val roots = available.filter { it.parentId == null }.sortedBy { it.sortOrder }
        val categoryTree = roots.joinToString("\n") { root ->
            val children = available.filter { it.parentId == root.id }.sortedBy { it.sortOrder }.joinToString(",") { it.name }
            if (children.isBlank()) root.name else "${root.name}:$children"
        }
        val systemPrompt = """
            你是记账商户分类器。输出朴素品牌名并选择给定一级/二级类目；不匹配时才建议简短通用新类目。不确定品牌时联网核实，优先百度百科、大众点评和品牌官网。另给2至6个可稳定识别该品牌的短别名，不含地区、门店号、公司后缀或通用词。
            只输出紧凑JSON：{"m":"商户名","p":"一级","s":"二级或空","np":0,"ns":0,"a":["匹配词"],"c":0.95}。c为0至1置信度；p/s选现有类目时名称必须完全一致；新类目对应n值填1。禁止输出ID、Markdown或解释。
        """.trimIndent()
        val userPrompt = "商户=${merchant.trim()}\n类目:\n$categoryTree"
        return AiPromptPreview(systemPrompt, userPrompt)
    }

    private fun fetchModels(apiKey: String): List<String> {
        val connection = URL("https://open.bigmodel.cn/api/paas/v4/models").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"; connection.connectTimeout = 12_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) error(when (code) { 401, 403 -> "API Key 无效或无权限"; 429 -> "请求过于频繁，请稍后重试"; else -> "验证失败（HTTP $code）" })
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val data = root.optJSONArray("data") ?: error("模型列表格式无效")
            return buildList {
                for (index in 0 until data.length()) {
                    val id = data.optJSONObject(index)?.optString("id").orEmpty().trim()
                    if (id.isNotEmpty() && isChatModel(id)) add(id)
                }
            }.distinct().sorted()
        } finally { connection.disconnect() }
    }

    private fun verifyDoubaoModel(apiKey: String, modelId: String) {
        val connection = URL("$DOUBAO_BASE_URL/responses").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 15_000; connection.readTimeout = 60_000; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val request = JSONObject()
                .put("model", modelId)
                .put("input", "仅回复 OK")
                .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = apiErrorDetail(body)
                error(when (code) {
                    401, 403 -> "API Key 无效，或无权调用该模型${detailSuffix(detail)}"
                    404 -> "方舟 Responses API 找不到模型${detailSuffix(detail)}"
                    429 -> "请求过于频繁，请稍后重试${detailSuffix(detail)}"
                    else -> "方舟模型验证失败（HTTP $code）${detailSuffix(detail)}"
                })
            }
        } finally { connection.disconnect() }
    }

    private fun apiErrorDetail(body: String): String = runCatching {
        val root = JSONObject(body)
        root.optJSONObject("error")?.optString("message").orEmpty().ifBlank { root.optString("message") }.trim()
    }.getOrDefault("")

    private fun detailSuffix(detail: String): String = detail.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()

    private fun responsesOutputText(response: JSONObject): String {
        val output = response.optJSONArray("output") ?: error("方舟 Responses API 未返回 output")
        for (outputIndex in 0 until output.length()) {
            val item = output.optJSONObject(outputIndex) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val part = content.optJSONObject(contentIndex) ?: continue
                if (part.optString("type") == "output_text") part.optString("text").takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        error("方舟 Responses API 未返回文本结果")
    }

    fun isConnected(provider: AiProvider = activeProvider()) = when (provider) { AiProvider.ZHIPU -> credentials.hasZhipuKey(); AiProvider.DOUBAO -> credentials.hasDoubaoKey() }
    fun disconnect() { credentials.clearZhipuKey(); if (activeProvider() == AiProvider.ZHIPU && credentials.hasDoubaoKey()) credentials.saveActiveProvider(AiProvider.DOUBAO) }
    fun disconnectDoubao() { credentials.clearDoubaoKey(); if (activeProvider() == AiProvider.DOUBAO) credentials.saveActiveProvider(AiProvider.ZHIPU) }
    fun activeProvider() = credentials.readActiveProvider()
    fun setActiveProvider(provider: AiProvider) { require(isConnected(provider)); credentials.saveActiveProvider(provider) }
    fun defaultModel(provider: AiProvider = activeProvider()) = when (provider) { AiProvider.ZHIPU -> credentials.readDefaultZhipuModel(); AiProvider.DOUBAO -> credentials.readDefaultDoubaoModel() }
    fun setDefaultModel(model: String) { require(model.isNotBlank()); credentials.saveDefaultZhipuModel(model) }
    fun setDefaultDoubaoModel(model: String) { require(model.isNotBlank()); credentials.saveDefaultDoubaoModel(model) }

    private fun preferredModel(models: List<String>) = listOf("glm-5.3", "glm-5.2", "glm-5", "glm-4.5-flash", "glm-4-flash-250414").firstOrNull(models::contains) ?: models.first()
    private fun isChatModel(id: String): Boolean {
        val value = id.lowercase()
        return value.startsWith("glm-") && listOf("embedding", "image", "cogview", "tts", "asr").none { it in value }
    }
    companion object { private const val DOUBAO_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3" }
}
