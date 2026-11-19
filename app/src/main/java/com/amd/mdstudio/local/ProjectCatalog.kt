package com.amd.mdstudio.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent local project catalog and lifecycle journal. Source files remain in their own folders. */
class ProjectCatalog(context: Context) {
    data class Item(val path: String, val name: String, val status: String, val updatedAt: Long)
    data class Event(val projectPath: String, val projectName: String, val state: String, val detail: String, val at: Long)

    private val prefs = context.getSharedPreferences("mdstudio_project_catalog", Context.MODE_PRIVATE)
    private val knownStates = setOf("CREATED", "PLANNING", "DEVELOPMENT", "BUILDING", "TESTING", "COMPLETED", "ERROR", "IMPORTED", "ARCHIVED")

    @Synchronized fun register(project: File, status: String = "DEVELOPMENT"): Item {
        val root = project.canonicalFile
        require(root.isDirectory && (File(root, "settings.gradle").isFile || File(root, "settings.gradle.kts").isFile)) {
            "المجلد ليس مشروع Gradle صالحًا"
        }
        val existing = readRaw()
        val name = readProjectName(root)
        val prior = existing.firstOrNull { it.path == root.absolutePath }
        val normalized = normalizeState(status)
        val item = Item(root.absolutePath, name, normalized, System.currentTimeMillis())
        val updated = (existing.filterNot { it.path == item.path } + item).sortedByDescending { it.updatedAt }
        write(updated)
        if (prior == null) appendEvent(Event(root.absolutePath, name, normalized, "تم تسجيل المشروع في MD Studio", item.updatedAt))
        else if (prior.status != normalized) appendEvent(Event(root.absolutePath, name, normalized, "تم تحديث حالة المشروع عند فتحه أو استيراده", item.updatedAt))
        return item
    }

    @Synchronized fun updateStatus(project: File, status: String) = updateStatus(project, status, "")

    @Synchronized fun updateStatus(project: File, status: String, detail: String) {
        val path = project.canonicalPath
        val existing = readRaw()
        val prior = existing.firstOrNull { it.path == path }
        val normalized = normalizeState(status)
        val name = prior?.name ?: readProjectName(project)
        val now = System.currentTimeMillis()
        val updated = if (prior == null) existing + Item(path, name, normalized, now)
            else existing.map { if (it.path == path) it.copy(status = normalized, updatedAt = now) else it }
        write(updated.sortedByDescending { it.updatedAt })
        appendEvent(Event(path, name, normalized, detail.ifBlank { "تغيرت حالة المشروع إلى $normalized" }, now))
    }

    @Synchronized fun recordEvent(project: File, detail: String, state: String? = null) {
        val root = project.canonicalFile
        val item = readRaw().firstOrNull { it.path == root.path }
        val eventState = normalizeState(state ?: item?.status ?: "DEVELOPMENT")
        appendEvent(Event(root.path, item?.name ?: readProjectName(root), eventState, detail.take(500), System.currentTimeMillis()))
    }

    @Synchronized fun history(project: File, limit: Int = 50): List<Event> = readEvents()
        .filter { it.projectPath == runCatching { project.canonicalPath }.getOrDefault(project.absolutePath) }
        .sortedByDescending { it.at }.take(limit.coerceIn(1, 200))

    @Synchronized fun list(): List<Item> = readRaw().filter { File(it.path).isDirectory }
        .sortedByDescending { it.updatedAt }

    private fun normalizeState(value: String): String = value.trim().uppercase().takeIf { it in knownStates } ?: "DEVELOPMENT"

    private fun readRaw(): List<Item> = runCatching {
        val array = JSONArray(prefs.getString("projects", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optString("path"), o.optString("name"), normalizeState(o.optString("status", "DEVELOPMENT")), o.optLong("updated_at"))
                .takeIf { it.path.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<Item>) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().put("path", item.path).put("name", item.name)
            .put("status", item.status).put("updated_at", item.updatedAt)) }
        prefs.edit().putString("projects", array.toString()).apply()
    }

    private fun readEvents(): List<Event> = runCatching {
        val array = JSONArray(prefs.getString("project_events", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Event(o.optString("path"), o.optString("name"), normalizeState(o.optString("state")),
                o.optString("detail"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun appendEvent(event: Event) {
        val events = (readEvents() + event).takeLast(300)
        val array = JSONArray()
        events.forEach { e -> array.put(JSONObject().put("path", e.projectPath).put("name", e.projectName)
            .put("state", e.state).put("detail", e.detail).put("at", e.at)) }
        prefs.edit().putString("project_events", array.toString()).apply()
    }

    private fun readProjectName(root: File): String {
        val settings = listOf(File(root, "settings.gradle.kts"), File(root, "settings.gradle")).firstOrNull { it.isFile }
        val text = settings?.readText().orEmpty()
        val match = Regex("rootProject\\.name\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']").find(text)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: root.name
    }
}
