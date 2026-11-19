package com.amd.mdstudio.local

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.amd.mdstudio.TermuxResultService
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Bridges a project ZIP to Termux, where an Android/ARM64-native JDK, Gradle and SDK can run. */
class TermuxBuildBridge(private val context: Context, private val archives: ProjectArchiveManager) {
    companion object {
        private val requestIds = AtomicInteger(4100)
        const val TERMUX_PACKAGE = "com.termux"
        const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    }

    data class Prepared(val sharedPath: String, val zipName: String)

    fun exportProject(project: File, treeUri: Uri): Result<Prepared> = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        check(documentId.startsWith("primary:")) {
            "اختَر مجلدًا من الذاكرة الداخلية المشتركة (Primary storage) حتى يستطيع Termux الوصول إليه"
        }
        val relative = documentId.removePrefix("primary:").trim('/')
        check(relative.split('/').none { it == ".." }) { "مسار مجلد غير صالح" }
        val sharedPath = if (relative.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$relative"
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: error("تعذر الوصول إلى المجلد المحدد")
        val zipFile = File(context.cacheDir, "mdstudio-termux-project.zip")
        archives.createProjectZip(project, zipFile).getOrThrow()
        val existing = tree.findFile("mdstudio-project.zip")
        existing?.delete()
        val target = tree.createFile("application/zip", "mdstudio-project.zip") ?: error("تعذر إنشاء ملف المشروع في المجلد المحدد")
        context.contentResolver.openOutputStream(target.uri)?.use { output -> zipFile.inputStream().use { it.copyTo(output) } }
            ?: error("تعذر كتابة ملف المشروع")
        zipFile.delete()
        Prepared(sharedPath, "mdstudio-project.zip")
    }

    fun startBuild(prepared: Prepared, variant: String): Result<Unit> = runCatching {
        require(variant.lowercase() in setOf("debug", "release")) { "نوع بناء غير صالح" }
        val permission = context.checkSelfPermission("com.termux.permission.RUN_COMMAND")
        check(permission == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "إذن تشغيل أوامر Termux غير ممنوح. افتح إعدادات MD Studio ← الأذونات الإضافية واسمح بـ Run commands in Termux environment."
        }
        val sharedQuoted = "'" + prepared.sharedPath.replace("'", "'\\''") + "'"
        val command = """
            set -e
            SHARED=$sharedQuoted
            WORK=\"${'$'}SHARED/MDStudioBuild\"
            mkdir -p \"${'$'}WORK/project\" \"${'$'}WORK/output\"
            unzip -oq \"${'$'}SHARED/${prepared.zipName}\" -d \"${'$'}WORK/project\"
            cd \"${'$'}WORK/project\"
            export JAVA_HOME=\"${'$'}PREFIX/lib/jvm/java-21-openjdk\"
            [ -d \"${'$'}JAVA_HOME\" ] || export JAVA_HOME=\"${'$'}PREFIX/lib/jvm/java-17-openjdk\"
            export ANDROID_HOME=\"${'$'}PREFIX/opt/android-sdk\"
            export ANDROID_SDK_ROOT=\"${'$'}ANDROID_HOME\"
            export PATH=\"${'$'}HOME/.mdstudio/gradle-8.13/bin:${'$'}PREFIX/bin:${'$'}ANDROID_HOME/platform-tools:${'$'}ANDROID_HOME/build-tools/35.0.0:${'$'}ANDROID_HOME/build-tools/36.1.0:${'$'}PATH\"
            export TMPDIR=\"${'$'}HOME/tmp\"
            mkdir -p \"${'$'}TMPDIR\"
            command -v java >/dev/null || { echo 'ERROR: JDK غير مثبت. شغّل scripts/setup-termux-mdstudio.sh داخل Termux.'; exit 21; }
            command -v gradle >/dev/null || { echo 'ERROR: Gradle غير مثبت.'; exit 22; }
            [ -f \"${'$'}ANDROID_HOME/platforms/android-35/android.jar\" ] || { echo 'ERROR: Android SDK Platform 35 غير مثبت.'; exit 23; }
            [ -x \"${'$'}PREFIX/bin/aapt2\" ] || { echo 'ERROR: Termux-native aapt2 غير موجود.'; exit 24; }
            command -v d8 >/dev/null || { echo 'ERROR: D8 غير مثبت.'; exit 26; }
            command -v apksigner >/dev/null || { echo 'ERROR: apksigner غير مثبت.'; exit 27; }
            command -v zipalign >/dev/null || { echo 'ERROR: zipalign غير مثبت.'; exit 28; }
            mkdir -p \"${'$'}HOME/.gradle\"
            grep -q '^android.aapt2FromMavenOverride=' \"${'$'}HOME/.gradle/gradle.properties\" 2>/dev/null || echo \"android.aapt2FromMavenOverride=${'$'}PREFIX/bin/aapt2\" >> \"${'$'}HOME/.gradle/gradle.properties\"
            gradle --no-daemon --stacktrace :app:assemble${variant.replaceFirstChar { it.uppercase() }}
            APK=\"app/build/outputs/apk/${variant.lowercase()}/app-${variant.lowercase()}${if (variant.equals("release", true)) "-unsigned" else ""}.apk\"
            [ -s \"${'$'}APK\" ] || { echo 'ERROR: Gradle انتهى دون APK.'; exit 25; }
            cp \"${'$'}APK\" \"${'$'}WORK/output/MD-Studio-${variant.lowercase()}.apk\"
            echo 'MDSTUDIO_BUILD_SUCCESS'
            echo \"APK_PATH=${'$'}WORK/output/MD-Studio-${variant.lowercase()}.apk\"
        """.trimIndent()
        val id = requestIds.incrementAndGet()
        val callbackIntent = Intent(context, TermuxResultService::class.java)
            .putExtra(TermuxResultService.EXTRA_EXECUTION_ID, id)
            .putExtra(TermuxResultService.EXTRA_BUILD_VARIANT, variant.lowercase())
        val pendingFlags = PendingIntent.FLAG_ONE_SHOT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getService(context, id, callbackIntent, pendingFlags)
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", BASH_PATH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "MD Studio Android Build")
            putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Build the selected Android project using the Termux toolchain.")
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        }
        context.startService(intent)
    }
}
