package com.amd.mdstudio.engine.ai

import com.amd.mdstudio.ai.AiConsumption
import com.amd.mdstudio.ai.AiOperatingMode
import com.amd.mdstudio.ai.AiUsageLedger
import com.amd.mdstudio.ai.GeminiRouter
import com.amd.mdstudio.engine.core.AiModelPort
import com.amd.mdstudio.engine.core.EngineResult

/** Connects the encrypted Gemini router to the agent using the user's live AI operating profile. */
class GeminiAiModelAdapter(
    private val router: GeminiRouter,
    private val model: String = "gemini-2.5-flash"
) : AiModelPort {
    @Volatile private var mode: AiOperatingMode = AiOperatingMode.NORMAL
    @Volatile private var consumption: AiConsumption = AiConsumption.ECONOMICAL
    @Volatile private var ledger: AiUsageLedger? = null

    fun configure(mode: AiOperatingMode, consumption: AiConsumption, ledger: AiUsageLedger?) {
        this.mode = mode
        this.consumption = if (mode == AiOperatingMode.NORMAL) AiConsumption.ECONOMICAL else consumption
        this.ledger = ledger
    }

    override fun generate(prompt: String): EngineResult {
        val result = router.generate(prompt, model, mode = mode, consumption = consumption, usageLedger = ledger)
        return if (result.success) EngineResult(true, result.text.orEmpty())
        else EngineResult(false, result.message ?: "فشل مزود Gemini")
    }
}
