package com.amd.mdstudio.engine.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.amd.mdstudio.engine.core.EngineResult
import org.json.JSONObject
import java.io.File

/** Android runtime adapter. Installation always remains a visible, user-confirmed system action. */
class AndroidRuntimeDevicePort(private val context: Context) : RuntimeDevicePort {
    @Volatile private var lastPackageName: String? = null

    override fun inspectDevice(): EngineResult = runCatching {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val data = JSONObject()
            .put("android_release", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("abis", org.json.JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("total_memory_bytes", memory.totalMem)
            .put("available_memory_bytes", memory.availMem)
            .put("low_memory", memory.lowMemory)
        EngineResult(true, "تم فحص بيئة Android", data)
    }.getOrElse { EngineResult(false, it.message ?: "تعذر فحص الجهاز") }

    override fun installApk(apk: File): EngineResult = runCatching {
        require(apk.isFile && apk.length() > 0 && apk.extension.equals("apk", true)) { "ملف APK غير صالح" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return EngineResult(false, "افتح السماح بتثبيت التطبيقات من هذا المصدر، ثم أعد طلب التثبيت.",
                JSONObject().put("stage", "unknown_sources_permission_required"))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        EngineResult(true, "تم فتح شاشة تثبيت Android. يجب تأكيد التثبيت يدويًا؛ لم يُعتبر APK مثبتًا بعد.",
            JSONObject().put("stage", "user_confirmation_required").put("apk", apk.name))
    }.getOrElse { EngineResult(false, it.message ?: "تعذر فتح مثبت Android") }

    override fun launch(packageName: String): EngineResult = runCatching {
        require(packageName.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"))) { "اسم حزمة غير صالح" }
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("التطبيق غير مثبت أو لا يحتوي على شاشة تشغيل: $packageName")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        lastPackageName = packageName
        EngineResult(true, "تم إرسال أمر تشغيل التطبيق", JSONObject().put("package_name", packageName))
    }.getOrElse { EngineResult(false, it.message ?: "تعذر تشغيل التطبيق") }

    override fun captureEvidence(): EngineResult = runCatching {
        val packageName = lastPackageName ?: error("شغّل تطبيقًا أولًا قبل جمع دليل التحقق")
        val info = packageInfo(packageName) ?: error("لم يعد التطبيق مثبتًا: $packageName")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else legacyVersionCode(info)
        val data = JSONObject()
            .put("package_name", packageName)
            .put("version_name", info.versionName ?: "")
            .put("version_code", versionCode)
            .put("last_update_time", info.lastUpdateTime)
            .put("source_dir", info.applicationInfo?.sourceDir ?: "")
            .put("verified_at_epoch_ms", System.currentTimeMillis())
        EngineResult(true, "تم جمع بيانات تحقق الحزمة المثبتة. هذا ليس تسجيل شاشة أو إثباتًا لاجتياز اختبارات الوظائف.", data)
    }.getOrElse { EngineResult(false, it.message ?: "تعذر جمع بيانات التحقق") }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long = info.versionCode.toLong()

    private fun packageInfo(name: String): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        runCatching { context.packageManager.getPackageInfo(name, android.content.pm.PackageManager.PackageInfoFlags.of(0)) }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        runCatching { context.packageManager.getPackageInfo(name, 0) }.getOrNull()
    }
}
