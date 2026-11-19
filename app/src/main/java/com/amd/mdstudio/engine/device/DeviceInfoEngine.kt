package com.amd.mdstudio.engine.device

import android.os.Build
import com.amd.mdstudio.engine.core.EngineResult
import com.amd.mdstudio.engine.core.EngineTool
import org.json.JSONArray
import org.json.JSONObject

/** Read-only Android device and runtime diagnostics. No privileged permissions are requested. */
class DeviceInfoEngine {
    fun inspect(): EngineResult {
        val memory = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        val json = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("android_release", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("app_heap_limit_mb", memory)
            .put("processors", Runtime.getRuntime().availableProcessors())
        return EngineResult(true, "تم جمع معلومات الجهاز المتاحة للتطبيق", json)
    }

    fun tool(): EngineTool = object : EngineTool {
        override val name = "device.inspect"
        override val description = "قراءة مواصفات Android الأساسية دون صلاحيات مرتفعة"
        override fun execute(arguments: JSONObject) = inspect()
    }
}
