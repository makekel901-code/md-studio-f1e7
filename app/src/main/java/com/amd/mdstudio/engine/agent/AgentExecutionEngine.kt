package com.amd.mdstudio.engine.agent

import com.amd.mdstudio.engine.core.AiModelPort
import com.amd.mdstudio.engine.core.EngineResult
import com.amd.mdstudio.engine.core.EngineTool
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Clean-room task orchestration layer for MD Studio.
 * It deliberately separates planning, the single user approval, and tool execution.
 */
class AgentExecutionEngine(private val model: AiModelPort) {
    data class PlannedStep(val tool: String, val arguments: JSONObject, val purpose: String)
    data class Plan(val id: String, val title: String, val summary: String, val steps: List<PlannedStep>)
    data class RunReport(val planId: String, val completed: Int, val failed: Int, val events: List<String>)

    private val tools = ConcurrentHashMap<String, EngineTool>()
    private val pending = ConcurrentHashMap<String, Plan>()

    fun register(tool: EngineTool) { tools[tool.name] = tool }
    fun registeredTools(): List<String> = tools.keys.sorted()

    /** Planning text is generated without changing project files. */
    fun preparePlan(title: String, request: String, suggestedSteps: List<PlannedStep>): EngineResult {
        if (title.isBlank() || request.isBlank()) return EngineResult(false, "اسم المهمة ووصفها مطلوبان")
        val modelResult = model.generate(
            "أنت مدير هندسي داخل MD Studio. حلل الطلب التالي واقترح ترتيب التنفيذ والمخاطر والاختبارات. " +
                "لا تنفذ أي تعديل ولا تدّع إنشاء ملفات. أعد ملخصًا عربيًا واضحًا.\n\n$request"
        )
        if (!modelResult.ok) return modelResult
        val id = "mds-${System.currentTimeMillis()}-${(1000..9999).random()}"
        val plan = Plan(id, title, modelResult.message, suggestedSteps.toList())
        pending[id] = plan
        val stepJson = JSONArray()
        plan.steps.forEach { step ->
            stepJson.put(JSONObject().put("tool", step.tool).put("purpose", step.purpose).put("arguments", step.arguments))
        }
        return EngineResult(true, "تم تجهيز الخطة. لا يبدأ التنفيذ قبل موافقة المستخدم.",
            JSONObject().put("plan_id", id).put("title", title).put("summary", plan.summary).put("steps", stepJson))
    }

    /**
     * Requests a structured plan from the configured model, validates every tool name,
     * and stores it for the single explicit approval step. No files are changed here.
     */
    fun preparePlanFromModel(title: String, request: String): EngineResult {
        if (title.isBlank() || request.isBlank()) return EngineResult(false, "اسم المهمة ووصفها مطلوبان")
        val available = tools.values.sortedBy { it.name }
        if (available.isEmpty()) return EngineResult(false, "لا توجد أدوات تنفيذ مسجلة")
        val toolCatalog = available.joinToString("\n") { "- ${it.name}: ${it.description}" }
        val prompt = """
            أنت مدير التنفيذ في MD Studio. أنشئ خطة تنفيذ منظمة للطلب التالي.
            الأدوات المسموح بها فقط:
            $toolCatalog

            أعد JSON صالحًا فقط دون Markdown بالشكل:
            {"summary":"ملخص عربي","steps":[{"tool":"اسم أداة مسجلة","purpose":"الهدف","arguments":{}}]}
            لا تخترع أدوات. لا تتجاوز 20 خطوة. استخدم خطوات قراءة وتشخيص قبل التعديل عند الحاجة.
            الطلب:
            $request
        """.trimIndent()
        val generated = model.generate(prompt)
        if (!generated.ok) return generated
        return runCatching {
            val raw = generated.message
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start >= 0 && end > start) { "لم يرجع النموذج JSON صالحًا" }
            val json = JSONObject(raw.substring(start, end + 1))
            val summary = json.optString("summary").ifBlank { "خطة تنفيذ مولدة بالذكاء الاصطناعي" }
            val array = json.optJSONArray("steps") ?: org.json.JSONArray()
            require(array.length() in 1..20) { "الخطة يجب أن تحتوي من خطوة إلى 20 خطوة" }
            val steps = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val toolName = item.getString("tool")
                require(tools.containsKey(toolName)) { "النموذج اقترح أداة غير مسجلة: $toolName" }
                PlannedStep(toolName, item.optJSONObject("arguments") ?: JSONObject(), item.optString("purpose"))
            }.toMutableList()
            // Every planned source mutation must have a recovery checkpoint before it.
            val firstMutation = steps.indexOfFirst { it.tool in setOf("workspace.write", "workspace.patch") }
            if (firstMutation >= 0 && tools.containsKey("workspace.checkpoint") &&
                steps.take(firstMutation).none { it.tool == "workspace.checkpoint" }) {
                steps.add(firstMutation, PlannedStep("workspace.checkpoint",
                    JSONObject().put("label", "agent-auto-checkpoint"),
                    "حفظ نقطة استعادة تلقائية قبل أول تعديل على ملفات المشروع"))
            }
            require(steps.size <= 21) { "الخطة بعد إضافة نقطة الاستعادة تجاوزت الحد المسموح" }
            val id = "mds-${System.currentTimeMillis()}-${(1000..9999).random()}"
            val plan = Plan(id, title, summary, steps)
            pending[id] = plan
            val stepJson = JSONArray()
            steps.forEach { step -> stepJson.put(JSONObject()
                .put("tool", step.tool).put("purpose", step.purpose).put("arguments", step.arguments)) }
            EngineResult(true, "تم تجهيز خطة تنفيذ قابلة للمراجعة. لا يبدأ التنفيذ قبل موافقتك.",
                JSONObject().put("plan_id", id).put("title", title).put("summary", summary).put("steps", stepJson))
        }.getOrElse { EngineResult(false, it.message ?: "تعذر تحليل خطة التنفيذ") }
    }

    /** Executes the approved plan, then performs only a bounded number of AI repair rounds. */
    fun executeApproved(planId: String, approved: Boolean, maxRepairRounds: Int = 0): Pair<EngineResult, RunReport?> {
        val plan = pending[planId] ?: return EngineResult(false, "الخطة غير موجودة أو انتهت صلاحيتها") to null
        if (!approved) {
            pending.remove(planId)
            return EngineResult(false, "ألغى المستخدم تنفيذ الخطة") to null
        }
        val events = mutableListOf<String>()
        var completed = 0
        var unresolvedFailures = 0
        var lastFailure = ""
        var success = false

        fun runSteps(steps: List<PlannedStep>, roundLabel: String): Boolean {
            for ((index, step) in steps.withIndex()) {
                val tool = tools[step.tool]
                if (tool == null) {
                    lastFailure = "الأداة غير مسجلة: ${step.tool}"
                    unresolvedFailures++
                    events += "$roundLabel ${index + 1}. ${step.tool}: $lastFailure"
                    return false
                }
                val result = runCatching { tool.execute(step.arguments) }
                    .getOrElse { EngineResult(false, it.message ?: "فشل غير معروف") }
                events += "$roundLabel ${index + 1}. ${step.tool}: ${result.message}"
                if (!result.ok) {
                    lastFailure = "${step.tool}: ${result.message}"
                    unresolvedFailures++
                    return false
                }
                completed++
            }
            return true
        }

        success = runSteps(plan.steps, "التنفيذ")
        val repairLimit = maxRepairRounds.coerceIn(0, 3)
        for (round in 1..repairLimit) {
            if (success || lastFailure.isBlank()) break
            val catalog = tools.values.sortedBy { it.name }.joinToString("\n") { "- ${it.name}: ${it.description}" }
            val repairPrompt = """
                أنت مهندس إصلاح داخل MD Studio. فشلت خطوة أثناء تنفيذ خطة وافق عليها المستخدم.
                المشكلة الفعلية: $lastFailure
                طلب المستخدم الأصلي: ${plan.title}
                الأدوات المسموح بها فقط:
                $catalog
                أعد JSON صالحًا فقط: {"summary":"سبب الإصلاح","steps":[{"tool":"اسم أداة مسجلة","purpose":"الهدف","arguments":{}}]}
                أنشئ خطوات إصلاح محددة لا تتجاوز 8 خطوات. اقرأ الملف أو ابحث قبل التعديل عند الحاجة.
                لا تكرر الخطوة الفاشلة بنفس المعاملات. استخدم workspace.patch أو workspace.write فقط عبر الأدوات المسجلة.
                لا تنفذ أوامر خارج الأدوات ولا تدّع نجاحًا قبل نتيجة الأداة.
                جولة الإصلاح: $round من $repairLimit.
            """.trimIndent()
            val generated = model.generate(repairPrompt)
            if (!generated.ok) {
                events += "الإصلاح $round: تعذر طلب إصلاح إضافي: ${generated.message}"
                break
            }
            val repairSteps = runCatching {
                val raw = generated.message
                val begin = raw.indexOf('{')
                val finish = raw.lastIndexOf('}')
                require(begin >= 0 && finish > begin) { "استجابة الإصلاح ليست JSON" }
                val json = JSONObject(raw.substring(begin, finish + 1))
                val array = json.optJSONArray("steps") ?: JSONArray()
                require(array.length() in 1..8) { "خطة الإصلاح يجب أن تحتوي من 1 إلى 8 خطوات" }
                val parsed = (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    val name = item.getString("tool")
                    require(tools.containsKey(name)) { "أداة إصلاح غير مسجلة: $name" }
                    PlannedStep(name, item.optJSONObject("arguments") ?: JSONObject(), item.optString("purpose"))
                }.toMutableList()
                val firstMutation = parsed.indexOfFirst { it.tool in setOf("workspace.write", "workspace.patch") }
                if (firstMutation >= 0 && tools.containsKey("workspace.checkpoint") &&
                    parsed.take(firstMutation).none { it.tool == "workspace.checkpoint" }) {
                    parsed.add(firstMutation, PlannedStep("workspace.checkpoint",
                        JSONObject().put("label", "agent-repair-$round"), "نقطة استعادة قبل إصلاح الملفات"))
                }
                parsed
            }.getOrElse {
                events += "الإصلاح $round: ${it.message ?: "تعذر تحليل خطة الإصلاح"}"
                emptyList()
            }
            if (repairSteps.isEmpty()) break
            events += "بدء دورة إصلاح تلقائي $round: ${repairSteps.size} خطوة"
            lastFailure = ""
            success = runSteps(repairSteps, "الإصلاح $round")
            if (success) {
                unresolvedFailures = 0
                events += "تم تجاوز الخطأ السابق بنجاح في دورة الإصلاح $round"
            }
        }

        pending.remove(planId)
        val failed = if (success) 0 else maxOf(1, unresolvedFailures)
        val report = RunReport(planId, completed, failed, events)
        return EngineResult(success, if (success) "اكتمل تنفيذ الخطة" else "توقف التنفيذ؛ راجع سبب الخطأ ونتائج محاولات الإصلاح",
            JSONObject().put("completed", completed).put("failed", failed).put("events", JSONArray(events))) to report
    }

}
