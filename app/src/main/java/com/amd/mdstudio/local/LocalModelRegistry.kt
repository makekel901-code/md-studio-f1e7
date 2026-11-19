package com.amd.mdstudio.local

import android.content.Context
import android.net.Uri

/** Tracks imported GGUF files. This registry does not claim to run inference without a native backend. */
class LocalModelRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("mdstudio_local_models", Context.MODE_PRIVATE)
    fun register(uri: Uri, displayName: String) {
        prefs.edit().putString("model_uri", uri.toString()).putString("model_name", displayName).apply()
    }
    fun currentModelName(): String? = prefs.getString("model_name", null)
    fun currentModelUri(): Uri? = prefs.getString("model_uri", null)?.let(Uri::parse)
    fun isNativeInferenceEngineAvailable(): Boolean = runCatching {
        System.loadLibrary("mdstudio_llama")
        true
    }.getOrDefault(false)
}
