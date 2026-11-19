package com.amd.mdstudio.local

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.amd.mdstudio.TermuxResultService
import java.util.concurrent.atomic.AtomicInteger

/** Optional local inference bridge using llama.cpp CLI compiled inside Termux. */
class TermuxLlamaBridge(private val context: Context) {
    companion object {
        private val requestIds = AtomicInteger(8100)
        private const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    }

    data class SharedModel(val sharedPath: String, val fileName: String)

    fun exportModel(modelUri: Uri, displayName: String, treeUri: Uri): Result<SharedModel> = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        check(documentId.startsWith("primary:")) { "اختر مجلدًا من الذاكرة الداخلية المشتركة" }
        val relative = documentId.removePrefix("primary:").trim('/')
        check(relative.split('/').none { it == ".." }) { "مسار مشاركة غير صالح" }
        val sharedPath = if (relative.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$relative"
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: error("تعذر الوصول إلى مجلد المشاركة")
        val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(100).ifBlank { "local-model.gguf" }.let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        tree.findFile(safeName)?.delete()
        val target = tree.createFile("application/octet-stream", safeName)
            ?: error("تعذر إنشاء ملف النموذج في مجلد المشاركة")
        context.contentResolver.openInputStream(modelUri)?.use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { output -> input.copyTo(output) }
                ?: error("تعذر الكتابة إلى مجلد المشاركة")
        } ?: error("تعذر فتح ملف GGUF الأصلي")
        SharedModel(sharedPath, safeName)
    }

    fun runPrompt(model: SharedModel, prompt: String): Result<Unit> = runCatching {
        check(context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "إذن Termux RUN_COMMAND غير ممنوح. امنحه من إعدادات أذونات MD Studio."
        }
        check(prompt.isNotBlank()) { "اكتب طلبًا للنموذج المحلي" }
        check(prompt.length <= 8000) { "الطلب طويل جدًا؛ الحد الحالي 8000 حرف" }
        val encoded = android.util.Base64.encodeToString(prompt.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val shared = "'" + model.sharedPath.replace("'", "'\\''") + "'"
        val file = "'" + model.fileName.replace("'", "'\\''") + "'"
        val command = """
            set -e
            SHARED=$shared
            FILE_NAME=$file
            MODEL=\"${'$'}SHARED/${'$'}FILE_NAME\"
            LLAMA=\"${'$'}HOME/llama.cpp/build/bin/llama-cli\"
            [ -x \"${'$'}LLAMA\" ] || { echo 'ERROR: llama-cli is not installed. Run scripts/setup-llama-termux.sh inside Termux.'; exit 31; }
            [ -s \"${'$'}MODEL\" ] || { echo 'ERROR: GGUF model file is missing from the shared folder.'; exit 32; }
            PROMPT=${'$'}(printf '%s' '$encoded' | base64 -d)
            \"${'$'}LLAMA\" -m \"${'$'}MODEL\" -c 2048 -n 256 --no-display-prompt -p \"${'$'}PROMPT\"
            echo 'MDSTUDIO_LLAMA_DONE'
        """.trimIndent()
        val id = requestIds.incrementAndGet()
        val callbackIntent = Intent(context, TermuxResultService::class.java)
            .putExtra(TermuxResultService.EXTRA_EXECUTION_ID, id)
            .putExtra(TermuxResultService.EXTRA_TASK_TYPE, "llama")
        val flags = PendingIntent.FLAG_ONE_SHOT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getService(context, id, callbackIntent, flags)
        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", BASH_PATH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "MD Studio Local Llama")
            putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Run local inference using the selected GGUF model.")
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        }
        context.startService(intent)
    }
}
