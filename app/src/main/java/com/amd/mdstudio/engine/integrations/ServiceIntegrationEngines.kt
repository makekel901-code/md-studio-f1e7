package com.amd.mdstudio.engine.integrations

import com.amd.mdstudio.engine.core.EngineResult
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Owner-configured service integration. No PocketBuilder production endpoints or credentials are embedded. */
class HttpServiceProbe {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    fun healthCheck(baseUrl: String, healthPath: String = "/health"): EngineResult = runCatching {
        val base = baseUrl.trim().removeSuffix("/")
        val parsed = java.net.URI(base)
        val localHost = parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        require(parsed.scheme == "https" || (parsed.scheme == "http" && localHost)) {
            "استخدم HTTPS أو عنوان localhost لبيئة التطوير"
        }
        require(parsed.userInfo == null && parsed.fragment == null) { "عنوان الخدمة يحتوي على بيانات غير مسموح بها" }
        require(healthPath.startsWith("/") && !healthPath.contains("..")) { "مسار فحص غير صالح" }
        val request = Request.Builder().url(base + healthPath).get().build()
        client.newCall(request).execute().use { response ->
            EngineResult(response.isSuccessful, "استجابة الخدمة: HTTP ${response.code}")
        }
    }.getOrElse { EngineResult(false, it.message ?: "تعذر الاتصال بالخدمة") }
}

/** Separate adapters for app-backend and multiplayer services; both require an owner-controlled provider. */
interface AppBackendProvider {
    fun inspectConfiguration(): EngineResult
    fun testConnection(): EngineResult
    fun publishBackend(projectId: String): EngineResult
}

interface MultiplayerProvider {
    fun inspectConfiguration(): EngineResult
    fun testServer(): EngineResult
    fun publishRules(projectId: String): EngineResult
}

class AppBackendEngine(private val provider: AppBackendProvider?) {
    fun inspect() = provider?.inspectConfiguration() ?: EngineResult(false, "لم يتم إعداد مزود App Backend")
    fun test() = provider?.testConnection() ?: EngineResult(false, "يلزم ربط مزود App Backend مملوك للمستخدم")
    fun publish(projectId: String) = provider?.publishBackend(projectId) ?: EngineResult(false, "النشر يحتاج مزود Backend وإعداداته")
}

class MultiplayerEngine(private val provider: MultiplayerProvider?) {
    fun inspect() = provider?.inspectConfiguration() ?: EngineResult(false, "لم يتم إعداد مزود Multiplayer")
    fun test() = provider?.testServer() ?: EngineResult(false, "يلزم ربط خادم Multiplayer")
    fun publishRules(projectId: String) = provider?.publishRules(projectId) ?: EngineResult(false, "نشر القواعد يحتاج خادمًا مملوكًا وإعداداته")
}

/** Optional owner-configured Runtime AI and Runtime Search provider contracts. */
interface RuntimeAiProvider {
    fun generate(prompt: String, model: String? = null): EngineResult
}

interface RuntimeSearchProvider {
    fun search(query: String): EngineResult
}

/**
 * Generic HTTPS JSON adapter for owner-operated services. Endpoint paths and schemas are
 * configured by the service owner; MD Studio does not assume or embed third-party APIs.
 */
data class JsonEndpointConfig(
    val baseUrl: String,
    val healthPath: String = "/health",
    val operationPath: String,
    val bearerToken: String? = null
)

class ConfiguredJsonEndpoint(private val config: JsonEndpointConfig) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun configurationStatus(): EngineResult = runCatching {
        validateBase()
        validatePath(config.healthPath)
        validatePath(config.operationPath)
        EngineResult(true, "إعدادات HTTPS ومسارات الخدمة صالحة")
    }.getOrElse { EngineResult(false, it.message ?: "إعدادات الخدمة غير صالحة") }

    fun inspect(): EngineResult = runCatching {
        val base = validateBase()
        val path = validatePath(config.healthPath)
        val request = requestBuilder(base + path).get().build()
        client.newCall(request).execute().use { response ->
            EngineResult(response.isSuccessful, "Health HTTP ${response.code}",
                JSONObject().put("http_status", response.code))
        }
    }.getOrElse { EngineResult(false, it.message ?: "تعذر فحص الخدمة") }

    fun post(payload: JSONObject): EngineResult = runCatching {
        val base = validateBase()
        val path = validatePath(config.operationPath)
        val request = requestBuilder(base + path)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("raw", raw.take(12000)) }
            EngineResult(response.isSuccessful,
                if (response.isSuccessful) "تم تنفيذ طلب الخدمة" else "فشل طلب الخدمة: HTTP ${response.code}",
                parsed.put("http_status", response.code))
        }
    }.getOrElse { EngineResult(false, it.message ?: "تعذر الاتصال بالخدمة") }

    private fun validateBase(): String {
        val parsed = java.net.URI(config.baseUrl.trim().removeSuffix("/"))
        require(parsed.scheme == "https" && !parsed.host.isNullOrBlank()) { "يجب ضبط عنوان خدمة HTTPS صالح" }
        require(parsed.userInfo == null && parsed.query == null && parsed.fragment == null) { "عنوان الخدمة يحتوي بيانات غير مسموحة" }
        return parsed.toString().removeSuffix("/")
    }

    private fun validatePath(path: String): String {
        require(path.startsWith("/") && !path.contains("..") && !path.contains('\\')) { "مسار API غير صالح" }
        return path
    }

    private fun requestBuilder(url: String): Request.Builder = Request.Builder().url(url)
        .header("Accept", "application/json")
        .apply { config.bearerToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
}

class ConfiguredRuntimeAiProvider(private val endpoint: ConfiguredJsonEndpoint) : RuntimeAiProvider {
    override fun generate(prompt: String, model: String?): EngineResult {
        if (prompt.isBlank()) return EngineResult(false, "اكتب طلب الذكاء الاصطناعي")
        return endpoint.post(JSONObject().put("prompt", prompt).put("model", model ?: ""))
    }
}

class ConfiguredRuntimeSearchProvider(private val endpoint: ConfiguredJsonEndpoint) : RuntimeSearchProvider {
    override fun search(query: String): EngineResult {
        if (query.isBlank()) return EngineResult(false, "اكتب عبارة البحث")
        return endpoint.post(JSONObject().put("query", query))
    }
}

class ConfiguredAppBackendProvider(
    private val health: ConfiguredJsonEndpoint,
    private val publish: ConfiguredJsonEndpoint
) : AppBackendProvider {
    override fun inspectConfiguration() = publish.configurationStatus()
    override fun testConnection() = health.inspect()
    override fun publishBackend(projectId: String): EngineResult = publish.post(JSONObject().put("project_id", projectId))
}

class ConfiguredMultiplayerProvider(
    private val health: ConfiguredJsonEndpoint,
    private val publish: ConfiguredJsonEndpoint
) : MultiplayerProvider {
    override fun inspectConfiguration() = publish.configurationStatus()
    override fun testServer() = health.inspect()
    override fun publishRules(projectId: String) = publish.post(JSONObject().put("project_id", projectId))
}
