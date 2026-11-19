package com.amd.mdstudio

import android.app.IntentService
import android.content.Intent

/** Receives Termux RUN_COMMAND completion and forwards a package-scoped update to the UI. */
class TermuxResultService : IntentService("MDStudioTermuxResult") {
    companion object {
        const val EXTRA_EXECUTION_ID = "mdstudio_execution_id"
        const val EXTRA_BUILD_VARIANT = "mdstudio_build_variant"
        const val EXTRA_TASK_TYPE = "mdstudio_task_type"
        const val ACTION_RESULT = "com.amd.mdstudio.TERMUX_BUILD_RESULT"
    }

    override fun onHandleIntent(intent: Intent?) {
        val result = intent?.getBundleExtra("result")
        val stdout = result?.getString("stdout").orEmpty()
        val stderr = result?.getString("stderr").orEmpty()
        val exitCode = result?.getInt("exitCode", -1) ?: -1
        val errorMessage = result?.getString("errmsg").orEmpty()
        val variant = intent?.getStringExtra(EXTRA_BUILD_VARIANT) ?: "debug"
        val taskType = intent?.getStringExtra(EXTRA_TASK_TYPE) ?: "build"
        val broadcast = Intent(ACTION_RESULT).setPackage(packageName)
            .putExtra("task_type", taskType)
            .putExtra("build_variant", variant)
            .putExtra("exit_code", exitCode)
            .putExtra("stdout", stdout.takeLast(12000))
            .putExtra("stderr", stderr.takeLast(8000))
            .putExtra("error", errorMessage)
        sendBroadcast(broadcast)
    }
}
