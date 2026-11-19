package com.amd.mdstudio.engine.libraries

import com.amd.mdstudio.engine.core.EngineResult
import org.json.JSONArray
import org.json.JSONObject

/** Catalog and dependency resolver abstraction. Remote catalogs must be explicitly configured. */
data class LibraryEntry(val id: String, val title: String, val coordinate: String, val description: String, val repository: String)

interface LibraryCatalogProvider {
    fun search(query: String): Result<List<LibraryEntry>>
    fun details(id: String): Result<LibraryEntry>
}

class LibraryEngine(private val providers: List<LibraryCatalogProvider>) {
    fun search(query: String): EngineResult {
        if (query.isBlank()) return EngineResult(false, "اكتب اسم المكتبة أو التقنية")
        val results = providers.flatMap { it.search(query).getOrDefault(emptyList()) }.distinctBy { it.coordinate }.take(100)
        val array = JSONArray()
        results.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("coordinate", it.coordinate).put("description", it.description).put("repository", it.repository)) }
        return EngineResult(true, "تم العثور على ${results.size} نتيجة من المصادر المتصلة", JSONObject().put("items", array))
    }

    fun dependencySnippet(entry: LibraryEntry, gradleKts: Boolean = true): EngineResult {
        val coordinate = entry.coordinate.trim()
        if (!Regex("^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+$").matches(coordinate))
            return EngineResult(false, "صيغة Maven Coordinate غير صالحة")
        val snippet = if (gradleKts) "implementation(\"$coordinate\")" else "implementation '$coordinate'"
        return EngineResult(true, "تم تجهيز سطر الاعتماد؛ لم تتم إضافته تلقائيًا", JSONObject().put("dependency", snippet).put("repository", entry.repository))
    }
    /** Adds one Maven dependency to an Android app module after an explicit user action. */
    fun addDependency(projectRoot: java.io.File, coordinate: String): EngineResult = runCatching {
        require(Regex("^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+$").matches(coordinate)) {
            "صيغة Maven Coordinate غير صالحة"
        }
        val kts = java.io.File(projectRoot, "app/build.gradle.kts")
        val groovy = java.io.File(projectRoot, "app/build.gradle")
        val target = when {
            kts.isFile -> kts
            groovy.isFile -> groovy
            else -> error("لم يتم العثور على ملف Gradle لوحدة app")
        }
        val original = target.readText(Charsets.UTF_8)
        val notation = if (target.extension == "kts") "implementation(\"$coordinate\")" else "implementation '$coordinate'"
        if (original.contains(coordinate)) return EngineResult(true, "الاعتماد موجود بالفعل؛ لم يتم تكراره")
        val dependenciesBlock = Regex("(?s)dependencies\\s*\\{(.*?)\\}")
        val match = dependenciesBlock.find(original)
        val updated = if (match != null) {
            val body = match.groupValues[1]
            original.replaceRange(match.range, "dependencies {" + body.trimEnd() + "\n    $notation\n}")
        } else {
            original.trimEnd() + "\n\ndependencies {\n    $notation\n}\n"
        }
        val backup = java.io.File(target.parentFile, target.name + ".mdstudio-backup")
        if (!backup.exists()) target.copyTo(backup, overwrite = false)
        target.writeText(updated, Charsets.UTF_8)
        EngineResult(true, "تمت إضافة $coordinate إلى ${target.name} مع حفظ نسخة احتياطية")
    }.getOrElse { EngineResult(false, it.message ?: "تعذرت إضافة الاعتماد") }

}
