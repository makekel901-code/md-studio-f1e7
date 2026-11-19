package com.amd.mdstudio.engine.workspace

import com.amd.mdstudio.engine.core.EngineResult
import com.amd.mdstudio.engine.core.EngineTool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Real, bounded project operations for the autonomous agent and manual workspace. */
class WorkspaceEngine(private val projectRoot: File, private val stateRoot: File) {
    private val protected = setOf(".git", ".gradle", ".idea", "build", "local.properties")
    private val editable = setOf("kt", "kts", "java", "xml", "gradle", "properties", "md", "txt", "json", "yml", "yaml", "pro", "gitignore", "toml", "sh")
    private val maxFileBytes = 1024 * 1024

    fun list(relative: String = ""): EngineResult = runCatching {
        val dir = resolve(relative.ifBlank { "." })
        require(dir.isDirectory) { "المسار ليس مجلدًا" }
        val items = dir.listFiles().orEmpty().filterNot { it.name in protected }.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val json = JSONArray()
        items.take(500).forEach { f -> json.put(JSONObject().put("name", f.name).put("path", f.relativeTo(projectRoot).invariantSeparatorsPath).put("directory", f.isDirectory).put("bytes", if (f.isFile) f.length() else 0L)) }
        EngineResult(true, "تم عرض ${json.length()} عنصر", JSONObject().put("items", json))
    }.getOrElse { EngineResult(false, it.message ?: "تعذر عرض الملفات") }

    fun read(relative: String): EngineResult = runCatching {
        val f = resolve(relative)
        require(f.isFile) { "الملف غير موجود" }
        require(f.length() <= maxFileBytes) { "الملف أكبر من حد القراءة 1 MB" }
        val bytes = f.readBytes()
        require(bytes.none { it == 0.toByte() }) { "الملف ثنائي" }
        EngineResult(true, "تمت قراءة الملف", JSONObject().put("path", relative).put("content", String(bytes, Charsets.UTF_8)))
    }.getOrElse { EngineResult(false, it.message ?: "تعذرت القراءة") }

    fun write(relative: String, content: String): EngineResult = runCatching {
        require(content.toByteArray(Charsets.UTF_8).size <= maxFileBytes) { "المحتوى أكبر من 1 MB" }
        val f = resolve(relative)
        require(f.extension.lowercase() in editable || f.name in setOf(".gitignore", "gradlew")) { "امتداد الملف غير مسموح" }
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        EngineResult(true, "تم حفظ ${f.relativeTo(projectRoot).invariantSeparatorsPath}")
    }.getOrElse { EngineResult(false, it.message ?: "تعذر الحفظ") }

    fun patch(relative: String, oldText: String, newText: String): EngineResult = runCatching {
        require(oldText.isNotEmpty()) { "نص البحث لا يمكن أن يكون فارغًا" }
        val f = resolve(relative)
        require(f.isFile && f.length() <= maxFileBytes) { "الملف غير موجود أو أكبر من 1 MB" }
        val original = f.readText(Charsets.UTF_8)
        val occurrences = Regex(Regex.escape(oldText)).findAll(original).count()
        require(occurrences == 1) { "يجب أن يظهر نص التعديل مرة واحدة فقط؛ عدد مرات الظهور: $occurrences" }
        f.writeText(original.replace(oldText, newText), Charsets.UTF_8)
        EngineResult(true, "تم تطبيق تعديل محدد مرة واحدة")
    }.getOrElse { EngineResult(false, it.message ?: "فشل التعديل") }

    fun search(query: String, maxMatches: Int = 100): EngineResult = runCatching {
        require(query.isNotBlank()) { "اكتب نص البحث" }
        val matches = JSONArray()
        projectRoot.walkTopDown().onEnter { it == projectRoot || it.name !in protected }
            .filter { it.isFile && it.length() <= 256 * 1024 && it.extension.lowercase() in editable }
            .forEach { file ->
                if (matches.length() < maxMatches) runCatching { file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                    if (matches.length() < maxMatches && line.contains(query, ignoreCase = true)) matches.put(JSONObject().put("path", file.relativeTo(projectRoot).invariantSeparatorsPath).put("line", index + 1).put("text", line.take(400)))
                } }
            }
        EngineResult(true, "نتائج البحث: ${matches.length()}", JSONObject().put("matches", matches))
    }.getOrElse { EngineResult(false, it.message ?: "فشل البحث") }

    fun checkpoint(label: String): EngineResult = runCatching {
        require(label.isNotBlank()) { "اسم نقطة الاستعادة مطلوب" }
        val safe = label.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "checkpoint" }
        val target = File(stateRoot, "checkpoints/${System.currentTimeMillis()}_$safe").apply { mkdirs() }
        var copied = 0
        projectRoot.walkTopDown().onEnter { it == projectRoot || it.name !in protected }
            .filter { it.isFile && it.length() <= 2L * 1024 * 1024 }
            .forEach { src ->
                val dest = File(target, src.relativeTo(projectRoot).path)
                dest.parentFile?.mkdirs(); src.copyTo(dest, overwrite = true); copied++
            }
        EngineResult(true, "تم إنشاء نقطة استعادة تضم $copied ملفًا", JSONObject().put("checkpoint", target.absolutePath).put("files", copied))
    }.getOrElse { EngineResult(false, it.message ?: "فشل إنشاء نقطة الاستعادة") }

    fun restore(checkpointPath: String): EngineResult = runCatching {
        val checkpointsRoot = File(stateRoot, "checkpoints").canonicalFile
        val snapshot = File(checkpointPath).canonicalFile
        require(snapshot.path.startsWith(checkpointsRoot.path + File.separator) && snapshot.isDirectory) { "نقطة الاستعادة غير صالحة" }
        var restored = 0
        snapshot.walkTopDown().filter { it.isFile }.forEach { src ->
            val dest = resolve(src.relativeTo(snapshot).invariantSeparatorsPath)
            dest.parentFile?.mkdirs(); src.copyTo(dest, overwrite = true); restored++
        }
        EngineResult(true, "تمت استعادة $restored ملفًا؛ الملفات التي أُنشئت بعد نقطة الاستعادة لم تُحذف", JSONObject().put("restored", restored))
    }.getOrElse { EngineResult(false, it.message ?: "فشلت الاستعادة") }

    fun diagnostics(): EngineResult = runCatching {
        val checks = JSONArray()
        fun check(name: String, ok: Boolean, detail: String) { checks.put(JSONObject().put("name", name).put("ok", ok).put("detail", detail)) }
        check("Gradle settings", File(projectRoot, "settings.gradle").isFile || File(projectRoot, "settings.gradle.kts").isFile, "settings.gradle[.kts]")
        check("Android app module", File(projectRoot, "app/build.gradle").isFile || File(projectRoot, "app/build.gradle.kts").isFile, "app/build.gradle[.kts]")
        check("Android manifest", File(projectRoot, "app/src/main/AndroidManifest.xml").isFile, "app/src/main/AndroidManifest.xml")
        val sourceCount = projectRoot.walkTopDown().onEnter { it == projectRoot || it.name !in protected }.count { it.isFile && it.extension in setOf("kt", "java") }
        check("Source files", sourceCount > 0, "$sourceCount Kotlin/Java files")
        EngineResult(true, "اكتمل الفحص البنيوي؛ لم يتم تنفيذ Gradle أو تشغيل التطبيق", JSONObject().put("checks", checks))
    }.getOrElse { EngineResult(false, it.message ?: "تعذر التشخيص") }

    private fun resolve(relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith("/")) { "مسار غير صالح" }
        require(relative.split('/').none { it == ".." || it in protected }) { "المسار محمي" }
        val root = projectRoot.canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "المسار خارج المشروع" }
        return file
    }

    fun tools(): List<com.amd.mdstudio.engine.core.EngineTool> = listOf(
        object : com.amd.mdstudio.engine.core.EngineTool {
            override val name = "workspace.list"; override val description = "عرض ملفات المشروع"
            override fun execute(arguments: JSONObject) = list(arguments.optString("path", ""))
        },
        object : com.amd.mdstudio.engine.core.EngineTool {
            override val name = "workspace.read"; override val description = "قراءة ملف نصي"
            override fun execute(arguments: JSONObject) = read(arguments.getString("path"))
        },
        object : com.amd.mdstudio.engine.core.EngineTool {
            override val name = "workspace.write"; override val description = "إنشاء أو كتابة ملف نصي"
            override fun execute(arguments: JSONObject) = write(arguments.getString("path"), arguments.getString("content"))
        },
        object : com.amd.mdstudio.engine.core.EngineTool {
            override val name = "workspace.patch"; override val description = "تعديل نص محدد مرة واحدة"
            override fun execute(arguments: JSONObject) = patch(arguments.getString("path"), arguments.getString("old"), arguments.getString("new"))
        },
        object : com.amd.mdstudio.engine.core.EngineTool {
            override val name = "workspace.search"; override val description = "البحث داخل ملفات المصدر"
            override fun execute(arguments: JSONObject) = search(arguments.getString("query"))
        },
        object : com.amd.mdstudio.engine.core.EngineTool {
            override val name = "workspace.checkpoint"; override val description = "إنشاء نقطة استعادة"
            override fun execute(arguments: JSONObject) = checkpoint(arguments.optString("label", "agent"))
        },
        object : com.amd.mdstudio.engine.core.EngineTool {
            override val name = "project.diagnostics"; override val description = "فحص بنية مشروع Android"
            override fun execute(arguments: JSONObject) = diagnostics()
        }
    )
}
