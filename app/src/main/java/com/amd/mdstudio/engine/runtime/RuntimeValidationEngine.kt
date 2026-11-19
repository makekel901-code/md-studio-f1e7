package com.amd.mdstudio.engine.runtime

import com.amd.mdstudio.engine.core.EngineResult
import java.io.File

/** Device-specific actions are injected because Android installation/testing requires user/device consent. */
interface RuntimeDevicePort {
    fun inspectDevice(): EngineResult
    fun installApk(apk: File): EngineResult
    fun launch(packageName: String): EngineResult
    fun captureEvidence(): EngineResult
}

class RuntimeValidationEngine(private val device: RuntimeDevicePort?) {
    fun inspect(): EngineResult = device?.inspectDevice() ?: EngineResult(false, "لم يتم ربط موصل جهاز Android بعد")

    fun validateArtifact(apk: File, packageName: String): EngineResult {
        if (!apk.isFile || apk.length() <= 0) return EngineResult(false, "ملف APK غير موجود أو فارغ")
        if (!apk.name.endsWith(".apk", true)) return EngineResult(false, "الملف المحدد ليس APK")
        val active = device ?: return EngineResult(false, "يلزم ربط موصل التثبيت والتشغيل على الجهاز")
        val installRequest = active.installApk(apk)
        if (!installRequest.ok) return installRequest
        return EngineResult(true,
            "تم إرسال طلب التثبيت إلى Android. بعد تأكيد التثبيت من شاشة النظام، استخدم خطوة التشغيل والتحقق.",
            org.json.JSONObject().put("stage", "awaiting_user_install_confirmation")
                .put("package_name", packageName).put("install_request", installRequest.data))
    }

    fun launchAndCapture(packageName: String): EngineResult {
        val active = device ?: return EngineResult(false, "يلزم ربط موصل جهاز Android")
        val launched = active.launch(packageName)
        if (!launched.ok) return launched
        return active.captureEvidence()
    }
}
