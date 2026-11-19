package com.amd.mdstudio.engine.project

import com.amd.mdstudio.ai.GeminiRouter
import com.amd.mdstudio.ai.AiConsumption
import com.amd.mdstudio.ai.AiOperatingMode
import com.amd.mdstudio.ai.AiUsageLedger
import com.amd.mdstudio.engine.core.EngineResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Two-phase AI project generation: prepare a bounded source patch, show its summary,
 * then write it only after one explicit user approval.
 * The base Gradle project is created by AndroidProjectScaffolder; model output is
 * restricted to app/src/main source/resources and README.md.
 */
class AiProjectGenerator(
    private val router: GeminiRouter,
    private val scaffolder: AndroidProjectScaffolder
) {
    data class GeneratedFile(val path: String, val content: String)
    data class PreparedProject(
        val id: String,
        val name: String,
        val packageName: String,
        val summary: String,
        val files: List<GeneratedFile>
    )

    fun prepare(
        name: String,
        packageName: String,
        idea: String,
        responseStyle: String = "مطور Android",
        mode: AiOperatingMode = AiOperatingMode.NORMAL,
        consumption: AiConsumption = AiConsumption.ECONOMICAL,
        usageLedger: AiUsageLedger? = null
    ): Result<PreparedProject> = runCatching {
        require(name.isNotBlank()) { "اسم التطبيق مطلوب" }
        require(idea.isNotBlank()) { "وصف فكرة التطبيق مطلوب" }
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){1,5}$").matches(packageName)) {
            "اسم الحزمة غير صالح"
        }
        val packagePath = packageName.replace('.', '/')
        val prompt = """
            أنت مهندس Android داخل AMD Studio. جهّز ملفات مصدر فعلية لتطبيق Android باسم: $name
            اسم الحزمة: $packageName
            فكرة التطبيق ومتطلباته:
            $idea

            أعد JSON صالحًا فقط دون Markdown أو أسوار كود، بالشكل:
            {"summary":"ملخص التنفيذ بالعربية","files":[{"path":"app/src/main/java/$packagePath/MainActivity.kt","content":"محتوى الملف كاملًا"}]}

            قواعد إلزامية:
            - أنشئ تطبيقًا حقيقيًا يعمل، لا واجهات وهمية أو أزرار بلا وظائف.
            - استخدم Kotlin وواجهات Android/Jetpack Compose المتاحة في المشروع الأساسي.
            - استخدم اسم الحزمة المحدد حرفيًا في package declarations لكل ملفات Kotlin/Java، واجعل MainActivity نقطة الدخول، ولا تضف Activity غير معلنة في Manifest.
            - لا تستخدم مكتبات خارجية جديدة؛ اعتمد على المكتبات الموجودة في المشروع الأساسي.
            - لا تنشئ صورًا أو فيديو أو محتوى مرئي مولدًا.
            - لا تضف مفاتيح API أو كلمات مرور أو بيانات اعتماد أو أسرارًا.
            - أعد من 1 إلى 18 ملفًا فقط. الملفات المسموحة داخل app/src/main أو README.md.
            - أعد محتوى كل ملف كاملًا، وليس مقتطفات أو تعليمات.
            - اجعل الواجهة عربية RTL افتراضيًا، ومتوافقة مع Android 9 فأحدث.
            - لا تعدّل ملفات Gradle أو Manifest؛ سيتم الاحتفاظ بإعدادات المشروع الأساسية.
        """.trimIndent()
        val response = router.generate(prompt, "gemini-2.5-flash", responseStyle, mode, if (mode == AiOperatingMode.NORMAL) AiConsumption.ECONOMICAL else consumption, usageLedger)
        check(response.success) { response.message ?: "فشل توليد ملفات المشروع" }
        val raw = response.text.orEmpty()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "لم يرجع النموذج JSON صالحًا" }
        val json = JSONObject(raw.substring(start, end + 1))
        val array = json.optJSONArray("files") ?: JSONArray()
        require(array.length() in 1..18) { "يجب أن تحتوي الخطة على 1 إلى 18 ملفًا" }
        var totalBytes = 0L
        val files = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val path = item.getString("path").trim().replace('\\', '/')
            val content = item.getString("content")
            validatePath(path)
            val bytes = content.toByteArray(Charsets.UTF_8).size
            require(bytes in 1..180_000) { "حجم الملف غير مسموح: $path" }
            totalBytes += bytes
            require(totalBytes <= 900_000) { "إجمالي ملفات التوليد تجاوز 900 KB" }
            GeneratedFile(path, content)
        }.distinctBy { it.path }
        require(files.isNotEmpty()) { "لم يتم توليد ملفات صالحة" }
        PreparedProject(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            packageName = packageName,
            summary = json.optString("summary").ifBlank { "تم تجهيز ${files.size} ملف مصدر للتطبيق" },
            files = files
        )
    }

    /** Writes the prepared files into a new scaffolded project after user approval. */
    fun apply(prepared: PreparedProject): EngineResult = runCatching {
        val scaffold = scaffolder.create(prepared.name, prepared.packageName)
        check(scaffold.ok) { scaffold.message }
        val root = File(scaffold.data.getString("project_path")).canonicalFile
        prepared.files.forEach { generated ->
            validatePath(generated.path)
            val destination = File(root, generated.path).canonicalFile
            check(destination.path.startsWith(root.path + File.separator)) { "مسار ملف خارج المشروع" }
            destination.parentFile?.mkdirs()
            destination.writeText(generated.content, Charsets.UTF_8)
        }
        EngineResult(true, "تم إنشاء المشروع وكتابة ${prepared.files.size} ملفًا بعد الموافقة", JSONObject()
            .put("project_path", root.absolutePath)
            .put("project_name", prepared.name)
            .put("package_name", prepared.packageName)
            .put("files_written", prepared.files.size)
            .put("generated_paths", JSONArray(prepared.files.map { it.path })))
    }.getOrElse { EngineResult(false, it.message ?: "فشل تطبيق ملفات المشروع") }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains(':')) { "مسار ملف غير صالح" }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "مسار ملف غير آمن" }
        require(parts.none { it in setOf(".git", ".gradle", "build", "local.properties", "secrets") }) {
            "المسار يطابق مجلدًا محميًا"
        }
        val allowed = path == "README.md" || path.startsWith("app/src/main/java/") || path.startsWith("app/src/main/res/")
        require(allowed) { "يسمح بتوليد ملفات المصدر والموارد داخل app/src/main وREADME.md فقط" }
        val extension = path.substringAfterLast('.', "").lowercase()
        require(extension in setOf("kt", "java", "xml", "md", "json", "txt")) { "امتداد الملف غير مسموح: $path" }
    }
}
