package com.amd.mdstudio.engine

import android.content.Context
import com.amd.mdstudio.ai.GeminiRouter
import com.amd.mdstudio.engine.agent.AgentExecutionEngine
import com.amd.mdstudio.engine.ai.GeminiAiModelAdapter
import com.amd.mdstudio.engine.build.BuildCoordinator
import com.amd.mdstudio.engine.build.LocalBuildProvider
import com.amd.mdstudio.engine.build.GitHubWorkflowBuildProvider
import com.amd.mdstudio.github.GitHubClient
import com.amd.mdstudio.engine.device.DeviceInfoEngine
import com.amd.mdstudio.engine.libraries.LibraryEngine
import com.amd.mdstudio.engine.libraries.MavenCentralCatalogProvider
import com.amd.mdstudio.engine.project.AndroidProjectScaffolder
import com.amd.mdstudio.engine.runtime.AndroidRuntimeDevicePort
import com.amd.mdstudio.engine.runtime.RuntimeValidationEngine
import com.amd.mdstudio.engine.workspace.WorkspaceEngine
import com.amd.mdstudio.local.LocalBuildEngine
import java.io.File

/** Composition root for concrete MD Studio engines. */
class EngineRuntime(
    context: Context,
    projectRoot: File?,
    geminiRouter: GeminiRouter,
    githubClient: GitHubClient? = null,
    githubRepository: GitHubClient.Repository? = null
) {
    val workspace: WorkspaceEngine? = projectRoot?.let {
        WorkspaceEngine(it, File(context.filesDir, "mdstudio-engine-state").apply { mkdirs() })
    }
    val projectScaffolder = AndroidProjectScaffolder(File(context.filesDir, "projects"))
    val deviceInfo = DeviceInfoEngine()
    val runtimeValidation = RuntimeValidationEngine(AndroidRuntimeDevicePort(context))
    val libraries = LibraryEngine(listOf(MavenCentralCatalogProvider()))
    val agent = AgentExecutionEngine(GeminiAiModelAdapter(geminiRouter)).apply {
        workspace?.tools()?.forEach(::register)
        register(projectScaffolder.tool())
        register(deviceInfo.tool())
    }
    val builds = BuildCoordinator(buildList {
        add(LocalBuildProvider(LocalBuildEngine(context)))
        if (githubClient != null && githubRepository != null) {
            add(GitHubWorkflowBuildProvider(
                githubClient,
                githubRepository.owner,
                githubRepository.name,
                githubRepository.defaultBranch,
                File(context.filesDir, "builds/github")
            ))
        }
    })
}
