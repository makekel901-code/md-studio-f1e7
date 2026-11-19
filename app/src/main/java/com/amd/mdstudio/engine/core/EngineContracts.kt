package com.amd.mdstudio.engine.core

import org.json.JSONObject

/** Stable contracts used by MD Studio engines. Providers are adapters, not copied PocketBuilder code. */
data class EngineResult(
    val ok: Boolean,
    val message: String,
    val data: JSONObject = JSONObject()
)

data class EngineDescriptor(
    val id: String,
    val name: String,
    val sourceCapability: String,
    val implementation: String,
    val status: Status,
    val requirements: String = ""
) {
    enum class Status { IMPLEMENTED, ADAPTER_READY, REQUIRES_PROVIDER, NOT_PORTABLE_FROM_APK }
}

interface StudioEngine {
    val id: String
    fun inspect(): EngineResult
}

interface AiModelPort {
    fun generate(prompt: String): EngineResult
}

interface EngineTool {
    val name: String
    val description: String
    fun execute(arguments: JSONObject): EngineResult
}
