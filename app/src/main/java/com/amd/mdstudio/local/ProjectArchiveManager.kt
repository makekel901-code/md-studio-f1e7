package com.amd.mdstudio.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Safely imports an Android/Gradle source ZIP into the app-private workspace. */
class ProjectArchiveManager(private val context: Context) {
    private val workspace = File(context.filesDir, "projects").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("mdstudio_workspace", Context.MODE_PRIVATE)

    fun currentProject(): File? = prefs.getString("current_project", null)?.let(::File)?.takeIf { it.isDirectory }

    fun activateProject(project: File): Result<File> = runCatching {
        val canonical = project.canonicalFile
        check(canonical.isDirectory) { "مجلد المشروع غير موجود" }
        check(File(canonical, "settings.gradle.kts").isFile || File(canonical, "settings.gradle").isFile) {
            "المجلد ليس مشروع Gradle صالحًا"
        }
        prefs.edit().putString("current_project", canonical.absolutePath).apply()
        canonical
    }

    fun importZip(uri: Uri): Result<File> = runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: error("تعذر فتح ملف ZIP")
        input.use { importZipStream(it) }
    }

    fun importZip(zipFile: File): Result<File> = runCatching {
        check(zipFile.isFile) { "ملف ZIP غير موجود" }
        zipFile.inputStream().buffered().use { importZipStream(it) }
    }

    private fun importZipStream(input: java.io.InputStream): File {
        val staging = File(context.cacheDir, "project-import").apply { deleteRecursively(); mkdirs() }
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(staging, entry.name).canonicalFile
                check(output.path.startsWith(staging.canonicalPath + File.separator) || output == staging.canonicalFile) {
                    "ملف ZIP يحتوي على مسار غير آمن"
                }
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile?.mkdirs()
                    output.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 1024L * 1024 * 1024) { "حجم المشروع بعد فك الضغط تجاوز 1 GB" }
                            sink.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val projectRoot = findProjectRoot(staging) ?: error("لم يتم العثور على settings.gradle أو settings.gradle.kts داخل ZIP")
        val destination = File(workspace, "project-${System.currentTimeMillis()}")
        check(projectRoot.copyRecursively(destination, overwrite = true))
        staging.deleteRecursively()
        prefs.edit().putString("current_project", destination.absolutePath).apply()
        return destination
    }

    fun createProjectZip(project: File, destination: File): Result<File> = runCatching {
        check(project.isDirectory) { "لا يوجد مشروع صالح لتصديره" }
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            project.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(project).invariantSeparatorsPath
                val lowerName = file.name.lowercase()
                if (relative.startsWith(".gradle/") || relative.startsWith(".git/") ||
                    relative.split('/').any { it in setOf("build", ".idea", "legacy-reference") } ||
                    lowerName in setOf("local.properties", "secrets.properties", "google-services.json", ".npmrc") ||
                    lowerName.startsWith(".env") || lowerName.startsWith("service-account") ||
                    listOf(".jks", ".keystore", ".p12", ".pfx", ".pem", ".apk", ".aab", ".gguf").any { lowerName.endsWith(it) }) return@forEach
                zip.putNextEntry(java.util.zip.ZipEntry(relative))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        destination
    }

    private fun findProjectRoot(root: File): File? {
        if (File(root, "settings.gradle").isFile || File(root, "settings.gradle.kts").isFile) return root
        return root.walkTopDown().maxDepth(3).firstOrNull {
            it.isDirectory && (File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile)
        }
    }
}
