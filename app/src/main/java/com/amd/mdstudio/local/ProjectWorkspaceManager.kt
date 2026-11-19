package com.amd.mdstudio.local

import java.io.File

/** Bounded project file browser/editor. It never exposes Gradle caches or generated build outputs. */
class ProjectWorkspaceManager {
    data class Entry(val relativePath: String, val directory: Boolean, val bytes: Long)

    fun list(project: File, maxEntries: Int = 250): List<Entry> {
        if (!project.isDirectory) return emptyList()
        val ignored = setOf(".git", ".gradle", "build", ".idea")
        return project.walkTopDown()
            .onEnter { dir -> dir == project || dir.name !in ignored }
            .filter { it != project }
            .take(maxEntries)
            .map { file -> Entry(file.relativeTo(project).invariantSeparatorsPath, file.isDirectory, if (file.isFile) file.length() else 0L) }
            .toList()
    }

    fun read(project: File, relativePath: String): Result<String> = runCatching {
        val file = safeFile(project, relativePath)
        check(file.isFile) { "الملف غير موجود" }
        check(file.length() <= 512 * 1024) { "الملف أكبر من حد المحرر النصي (512 KB)" }
        val bytes = file.readBytes()
        check(bytes.none { it == 0.toByte() }) { "الملف ثنائي ولا يمكن فتحه كمحرر نصي" }
        String(bytes, Charsets.UTF_8)
    }

    fun write(project: File, relativePath: String, content: String): Result<Unit> = runCatching {
        check(content.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "المحتوى أكبر من حد الحفظ (512 KB)" }
        val file = safeFile(project, relativePath)
        check(file.extension.lowercase() in setOf("kt", "kts", "java", "xml", "gradle", "properties", "md", "txt", "json", "yml", "yaml", "pro", "gitignore")) {
            "امتداد الملف غير مسموح للمحرر النصي"
        }
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun safeFile(project: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/")) { "مسار ملف غير صالح" }
        val root = project.canonicalFile
        val file = File(root, relativePath).canonicalFile
        check(file.path.startsWith(root.path + File.separator)) { "المسار خارج مجلد المشروع" }
        check(relativePath.split('/').none { it in setOf(".git", ".gradle", "build") }) { "هذا المسار محمي" }
        return file
    }
}
