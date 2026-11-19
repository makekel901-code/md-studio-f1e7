package com.amd.mdstudio.ai

import android.content.Context
import org.json.JSONObject

/** The three modes share the same tools; they only change orchestration depth and AI budget. */
enum class AiOperatingMode(val key: String, val title: String, val description: String) {
    NORMAL("normal", "عادي", "تنفيذ محلي ومحركات MD Studio أولًا، مع أقل تدخل واستدعاءات AI ممكنة."),
    ADVANCED("advanced", "قوي", "تحليل هندسي أوسع وتنسيق ومراجعة موجهة للأجزاء المتأثرة."),
    PROFESSIONAL("professional", "احترافي", "تدخل مباشر أعمق في كتابة الأكواد وتعديلها وتحليل الأخطاء والإصلاح." );

    companion object { fun from(value: String?): AiOperatingMode = values().firstOrNull { it.key == value } ?: NORMAL }
}

enum class AiConsumption(val key: String, val title: String) {
    ECONOMICAL("economical", "اقتصادي"),
    BALANCED("balanced", "متوازن"),
    INTENSIVE("intensive", "مكثف");
    companion object { fun from(value: String?): AiConsumption = values().firstOrNull { it.key == value } ?: ECONOMICAL }
}

object AiPromptPolicy {
    fun instruction(mode: AiOperatingMode, consumption: AiConsumption): String {
        val modeText = when (mode) {
            AiOperatingMode.NORMAL -> "اعتمد على محركات وأدوات MD Studio أولًا. لا تطلب مراجعات AI إضافية بلا سبب. نفّذ المهمة كاملة بالأدوات المتاحة، ولا تخفض جودة المنتج النهائي. استخدم استدعاءً واحدًا موجزًا عند الحاجة فقط."
            AiOperatingMode.ADVANCED -> "حلل بنية المشروع والاعتماديات المرتبطة بالمهمة. نسق التنفيذ بين الأدوات، وراجع الملفات المتأثرة فقط. وجّه الإصلاح بنفسك عند الحاجة، ولا تعِد قراءة المشروع كله دون مبرر."
            AiOperatingMode.PROFESSIONAL -> "تصرّف كمهندس برمجيات مشارك بالتنفيذ. أنشئ خطوات مباشرة لقراءة الملفات ذات الصلة وكتابة أو تعديل الأكواد بنفسك عبر أدوات workspace.write وworkspace.patch، ثم نفّذ التشخيص والاختبارات المتاحة. أصلح السبب الجذري لا العرض فقط، واستخدم نقطة استعادة قبل أي تعديل. لا تكرر المراجعة؛ أعدها فقط بعد تغيير مؤثر أو فشل اختبار."
        }
        val budgetText = when (consumption) {
            AiConsumption.ECONOMICAL -> "ميزانية اقتصادية: قلل الشرح والسياق والاستدعاءات والمراحل غير الضرورية، لكن لا تختصر الأكواد أو الملفات أو الوظائف المطلوبة ولا تخفض جودة التطبيق."
            AiConsumption.BALANCED -> "ميزانية متوازنة: سياق كافٍ وتحليل ومراجعة موجهة دون تكرار، مع الحفاظ على اكتمال الكود والوظائف."
            AiConsumption.INTENSIVE -> "ميزانية مكثفة: تحليل أوسع ومراجعة إضافية مبررة، مع تجنب التكرار غير المفيد والحفاظ على اكتمال التنفيذ."
        }
        return "وضع التشغيل: ${mode.title}. $modeText $budgetText\nجميع الأوضاع تملك نفس قدرات المحركات والبناء والاختبار؛ لا تحجب أي وظيفة بسبب الوضع. لا تدّع نجاحًا دون نتيجة فعلية."
    }
}

class AiUsageLedger(context: Context) {
    private val prefs = context.getSharedPreferences("mdstudio_ai_usage", Context.MODE_PRIVATE)
    @Synchronized fun record(inputTokens: Int, outputTokens: Int, estimated: Boolean) {
        prefs.edit()
            .putLong("requests", prefs.getLong("requests", 0) + 1)
            .putLong("input", prefs.getLong("input", 0) + inputTokens.coerceAtLeast(0))
            .putLong("output", prefs.getLong("output", 0) + outputTokens.coerceAtLeast(0))
            .putLong("estimated_requests", prefs.getLong("estimated_requests", 0) + if (estimated) 1 else 0)
            .apply()
    }
    fun snapshot(): JSONObject = JSONObject()
        .put("requests", prefs.getLong("requests", 0))
        .put("input_tokens", prefs.getLong("input", 0))
        .put("output_tokens", prefs.getLong("output", 0))
        .put("estimated_requests", prefs.getLong("estimated_requests", 0))
    fun reset() { prefs.edit().clear().apply() }
}
