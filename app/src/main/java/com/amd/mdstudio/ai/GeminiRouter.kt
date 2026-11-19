package com.amd.mdstudio.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Sequential key fallback for Gemini REST calls. Never logs API keys or request headers. */
class GeminiRouter(private val keyStore: GeminiKeyStore) {
    private val http = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Result(val success: Boolean, val text: String? = null, val message: String? = null, val inputTokens: Int = 0, val outputTokens: Int = 0, val usageEstimated: Boolean = false)

    fun generate(
        prompt: String,
        model: String = "gemini-2.5-flash",
        responseStyle: String = "احترافي متوازن",
        mode: AiOperatingMode = AiOperatingMode.NORMAL,
        consumption: AiConsumption = AiConsumption.ECONOMICAL,
        usageLedger: AiUsageLedger? = null
    ): Result {
        val keys = keyStore.readKeys()
        if (keys.isEmpty()) return Result(false, message = "لم تتم إضافة أي مفاتيح Google AI")
        var lastError = "تعذر تنفيذ الطلب"
        for (key in keys) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
            val styleInstruction = when (responseStyle) {
                "مختصر ومباشر" -> "أجب باختصار ووضوح، وابدأ بالنتيجة ثم أهم النقاط فقط."
                "تفصيلي تعليمي" -> "اشرح بالتفصيل وبترتيب تعليمي، ووضح الأسباب والخطوات والأمثلة عند الحاجة."
                "مطور Android" -> "تصرف كمساعد هندسي متخصص في Android وKotlin وGradle. قدم قرارات تقنية دقيقة، واذكر القيود والاختبارات، ولا تدّع تنفيذ شيء لم يحدث."
                "مصري مبسط" -> "استخدم العربية المصرية الواضحة والمحترمة، مع الحفاظ على الدقة والمصطلحات التقنية الصحيحة."
                else -> "استخدم أسلوبًا احترافيًا متوازنًا ومنظمًا، دون إطالة غير لازمة."
            }
            val modeInstruction = AiPromptPolicy.instruction(mode, consumption)
            val fullPrompt = "تعليمات أسلوب الإجابة: $styleInstruction\n$modeInstruction\nالتزم بالدقة، وميّز بين ما تم تنفيذه وما هو اقتراح.\n\n$prompt"
            val payload = JSONObject().put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", fullPrompt)))
            )).toString()
            val request = Request.Builder().url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val root = JSONObject(body)
                        val text = root.optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                        val usage = root.optJSONObject("usageMetadata")
                        val input = usage?.optInt("promptTokenCount", -1) ?: -1
                        val output = usage?.optInt("candidatesTokenCount", -1) ?: -1
                        val estimated = input < 0 || output < 0
                        val inputCount = if (input >= 0) input else (fullPrompt.length / 4).coerceAtLeast(1)
                        val outputCount = if (output >= 0) output else ((text?.length ?: 0) / 4)
                        usageLedger?.record(inputCount, outputCount, estimated)
                        return Result(true, text = text, inputTokens = inputCount, outputTokens = outputCount, usageEstimated = estimated)
                    }
                    lastError = "استجابة Google: HTTP ${response.code}"
                    // Retry another configured key only for quota/rate/transient server errors.
                    if (response.code !in setOf(429, 500, 502, 503, 504)) return Result(false, message = lastError)
                }
            } catch (_: Exception) { lastError = "تعذر الاتصال بخدمة Google" }
        }
        return Result(false, message = lastError)
    }
}
