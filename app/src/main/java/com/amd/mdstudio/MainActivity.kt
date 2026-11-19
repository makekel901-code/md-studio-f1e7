package com.amd.mdstudio

import android.os.Bundle
import android.os.Build
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.amd.mdstudio.ai.GeminiKeyStore
import com.amd.mdstudio.ai.GeminiRouter
import com.amd.mdstudio.ai.AiOperatingMode
import com.amd.mdstudio.ai.AiConsumption
import com.amd.mdstudio.ai.AiUsageLedger
import com.amd.mdstudio.github.GitHubClient
import com.amd.mdstudio.local.LocalModelRegistry
import com.amd.mdstudio.local.LocalBuildEngine
import com.amd.mdstudio.local.ProjectArchiveManager
import com.amd.mdstudio.local.TermuxBuildBridge
import com.amd.mdstudio.local.TermuxLlamaBridge
import com.amd.mdstudio.local.ProjectWorkspaceManager
import com.amd.mdstudio.local.ProjectCatalog
import com.amd.mdstudio.TermuxResultService
import androidx.documentfile.provider.DocumentFile
import java.io.File
import com.amd.mdstudio.ui.MDTheme
import com.amd.mdstudio.engine.EngineRegistry
import com.amd.mdstudio.engine.core.EngineDescriptor
import com.amd.mdstudio.engine.project.AndroidProjectScaffolder
import com.amd.mdstudio.engine.project.AiProjectGenerator
import com.amd.mdstudio.engine.runtime.AndroidRuntimeDevicePort
import com.amd.mdstudio.engine.libraries.LibraryEngine
import com.amd.mdstudio.engine.libraries.LibraryEntry
import com.amd.mdstudio.engine.libraries.MavenCentralCatalogProvider
import com.amd.mdstudio.engine.build.GitHubWorkflowBuildProvider
import com.amd.mdstudio.engine.build.BuildRequest
import com.amd.mdstudio.engine.agent.AgentExecutionEngine
import com.amd.mdstudio.engine.ai.GeminiAiModelAdapter
import com.amd.mdstudio.engine.workspace.WorkspaceEngine
import com.amd.mdstudio.engine.device.DeviceInfoEngine
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyStore = GeminiKeyStore(this)
        val localModels = LocalModelRegistry(this)
        val studioPrefs = getSharedPreferences("mdstudio_core_preferences", MODE_PRIVATE)
        setContent {
            var themeMode by remember { mutableStateOf(studioPrefs.getString("theme_mode", "system") ?: "system") }
            MDTheme(themeMode) {
                MDStudioHome(
                    keyStore = keyStore,
                    localModels = localModels,
                    githubClient = GitHubClient(this),
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        studioPrefs.edit().putString("theme_mode", mode).apply()
                    }
                )
            }
        }
    }
}

private enum class StudioTab(val label: String) {
    Projects("المشروعات"), Tests("الاختبارات"), Workspace("مساحة العمل"),
    Build("البناء"), Store("المكتبة"), Settings("الإعدادات")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MDStudioHome(
    keyStore: GeminiKeyStore,
    localModels: LocalModelRegistry,
    githubClient: GitHubClient,
    themeMode: String,
    onThemeModeChange: (String) -> Unit
) {
    val context = LocalContext.current
    val corePrefs = remember { context.getSharedPreferences("mdstudio_core_preferences", android.content.Context.MODE_PRIVATE) }
    var detailedEngineStatus by remember { mutableStateOf(corePrefs.getBoolean("detailed_engine_status", true)) }
    var selected by remember { mutableStateOf(StudioTab.Projects) }
    var prompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("جاهز لاستقبال فكرة مشروعك") }
    var apiKeys by remember { mutableStateOf(keyStore.readKeys()) }
    var githubToken by remember { mutableStateOf("") }
    var githubStatus by remember { mutableStateOf("غير متصل") }
    var githubAccounts by remember { mutableStateOf(githubClient.accountNames()) }
    var activeGithubAccount by remember { mutableStateOf(githubClient.activeAccount()) }
    var repoStatus by remember { mutableStateOf("لم يتم تحميل المستودعات") }
    var repositories by remember { mutableStateOf(emptyList<GitHubClient.Repository>()) }
    var selectedRepository by remember { mutableStateOf<GitHubClient.Repository?>(null) }
    var workflowStatus by remember { mutableStateOf("لم يتم تشغيل بناء سحابي") }
    var cloudPublishStatus by remember { mutableStateOf("لم يتم رفع المشروع الحالي إلى GitHub") }
    var cloudBuildBusy by remember { mutableStateOf(false) }
    var workflowRuns by remember { mutableStateOf(emptyList<GitHubClient.WorkflowRun>()) }
    var workflowArtifacts by remember { mutableStateOf(emptyList<GitHubClient.Artifact>()) }
    var selectedVariant by remember { mutableStateOf(LocalBuildEngine.Variant.DEBUG) }
    var latestApk by remember { mutableStateOf<File?>(null) }
    var cloudDownloadStatus by remember { mutableStateOf("لا يوجد APK سحابي تم تنزيله") }
    var termuxStatus by remember { mutableStateOf("لم يتم إعداد البناء عبر Termux") }
    val buildPrefs = remember { context.getSharedPreferences("mdstudio_build_preferences", android.content.Context.MODE_PRIVATE) }
    var termuxTreeUri by remember { mutableStateOf(buildPrefs.getString("termux_tree_uri", null)?.let(android.net.Uri::parse)) }
    var localAiPrompt by remember { mutableStateOf("") }
    var localAiStatus by remember { mutableStateOf("محرك Llama المحلي يحتاج إعداد llama.cpp داخل Termux") }
    var localAiResponse by remember { mutableStateOf("") }
    val projectArchiveManager = remember { ProjectArchiveManager(context) }
    val projectCatalog = remember { ProjectCatalog(context) }
    var projectItems by remember { mutableStateOf(projectCatalog.list()) }
    var historyProject by remember { mutableStateOf<ProjectCatalog.Item?>(null) }
    var projectHistory by remember { mutableStateOf(emptyList<ProjectCatalog.Event>()) }
    val termuxBridge = remember { TermuxBuildBridge(context, projectArchiveManager) }
    val termuxLlamaBridge = remember { TermuxLlamaBridge(context) }
    var localBuildStatus by remember { mutableStateOf("لم يتم فحص أدوات البناء") }
    val localBuildEngine = remember { LocalBuildEngine(context) }
    var importedProject by remember { mutableStateOf(projectArchiveManager.currentProject()) }
    LaunchedEffect(importedProject?.absolutePath) {
        importedProject?.let { active ->
            runCatching { projectCatalog.register(active) }
            projectItems = projectCatalog.list()
        }
    }
    val workspaceManager = remember { ProjectWorkspaceManager() }
    var workspaceFiles by remember { mutableStateOf(emptyList<ProjectWorkspaceManager.Entry>()) }
    var selectedSourcePath by remember { mutableStateOf<String?>(null) }
    var sourceContent by remember { mutableStateOf("") }
    var workspaceStatus by remember { mutableStateOf("استورد مشروعًا لعرض ملفاته") }
    var diagnostics by remember { mutableStateOf(emptyList<String>()) }
    var runtimePackageName by remember { mutableStateOf("") }
    var runtimeStatus by remember { mutableStateOf("أنشئ أو نزّل APK ثم أدخل اسم حزمتها لاختبار التثبيت والتشغيل") }
    val runtimeDevice = remember { AndroidRuntimeDevicePort(context) }
    var modelName by remember { mutableStateOf(localModels.currentModelName() ?: "لم يتم اختيار نموذج") }
    var isPreparing by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var newProjectPackage by remember { mutableStateOf("com.example.myapp") }
    var scaffoldStatus by remember { mutableStateOf("أنشئ مشروع Android جديدًا أو استورد مشروعًا موجودًا") }
    var aiProjectStatus by remember { mutableStateOf("اكتب اسم التطبيق وفكرته لتجهيز ملفات فعلية") }
    var preparedAiProject by remember { mutableStateOf<AiProjectGenerator.PreparedProject?>(null) }
    val projectScaffolder = remember { AndroidProjectScaffolder(File(context.filesDir, "projects")) }
    val mavenProvider = remember { MavenCentralCatalogProvider() }
    val libraryEngine = remember { LibraryEngine(listOf(mavenProvider)) }
    var libraryQuery by remember { mutableStateOf("") }
    var libraryStatus by remember { mutableStateOf("ابحث عن مكتبات Android وMaven") }
    var libraryEntries by remember { mutableStateOf(emptyList<LibraryEntry>()) }
    val stylePrefs = remember { context.getSharedPreferences("mdstudio_ai_preferences", android.content.Context.MODE_PRIVATE) }
    var responseStyle by remember { mutableStateOf(stylePrefs.getString("response_style", "احترافي متوازن") ?: "احترافي متوازن") }
    var responseStyleExpanded by remember { mutableStateOf(false) }
    var aiModeExpanded by remember { mutableStateOf(false) }
    var advancedBudgetExpanded by remember { mutableStateOf(false) }
    var professionalBudgetExpanded by remember { mutableStateOf(false) }
    var aiMode by remember { mutableStateOf(AiOperatingMode.from(stylePrefs.getString("operating_mode", "normal"))) }
    var advancedConsumption by remember { mutableStateOf(AiConsumption.from(stylePrefs.getString("advanced_consumption", "economical"))) }
    var professionalConsumption by remember { mutableStateOf(AiConsumption.from(stylePrefs.getString("professional_consumption", "economical"))) }
    val aiUsageLedger = remember { AiUsageLedger(context) }
    var aiUsageSnapshot by remember { mutableStateOf(aiUsageLedger.snapshot()) }
    val scope = rememberCoroutineScope()
    val geminiRouter = remember { GeminiRouter(keyStore) }
    val aiAdapter = remember { GeminiAiModelAdapter(geminiRouter) }
    val aiProjectGenerator = remember { AiProjectGenerator(geminiRouter, projectScaffolder) }
    fun currentAiConsumption(): AiConsumption = when (aiMode) {
        AiOperatingMode.NORMAL -> AiConsumption.ECONOMICAL
        AiOperatingMode.ADVANCED -> advancedConsumption
        AiOperatingMode.PROFESSIONAL -> professionalConsumption
    }
    fun currentRepairRounds(): Int = when (aiMode) {
        AiOperatingMode.NORMAL -> 0
        AiOperatingMode.ADVANCED -> when (advancedConsumption) {
            AiConsumption.ECONOMICAL -> 0
            AiConsumption.BALANCED -> 1
            AiConsumption.INTENSIVE -> 2
        }
        AiOperatingMode.PROFESSIONAL -> when (professionalConsumption) {
            AiConsumption.ECONOMICAL -> 1
            AiConsumption.BALANCED -> 2
            AiConsumption.INTENSIVE -> 3
        }
    }
    var agentTaskTitle by remember { mutableStateOf("") }
    var agentTaskRequest by remember { mutableStateOf("") }
    var agentStatus by remember { mutableStateOf("اكتب مهمة هندسية؛ سيجهز المدير خطة ثم ينتظر موافقة واحدة قبل التنفيذ") }
    var agentBusy by remember { mutableStateOf(false) }
    var agentPendingPlan by remember { mutableStateOf<JSONObject?>(null) }
    val agentEngine = remember(importedProject?.absolutePath) {
        AgentExecutionEngine(aiAdapter).apply {
            importedProject?.let { project ->
                WorkspaceEngine(project, File(context.filesDir, "agent-state/${project.name}")).tools().forEach(::register)
            }
            register(projectScaffolder.tool())
            register(DeviceInfoEngine().tool())
        }
    }
    LaunchedEffect(importedProject?.absolutePath) {
        agentPendingPlan = null
        agentStatus = if (importedProject == null) "استورد أو أنشئ مشروعًا لتفعيل أدوات مساحة العمل" else "المدير الهندسي جاهز للمشروع: ${importedProject?.name}"
    }
    var keyInput by remember { mutableStateOf("") }
    var showKeyInput by remember { mutableStateOf(false) }
    val toolchainPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            localBuildStatus = "جارٍ تثبيت حزمة أدوات البناء..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openInputStream(uri) ?: error("تعذر فتح الحزمة")
                    stream.use { localBuildEngine.installToolchain(it).getOrThrow() }
                }
            }
            localBuildStatus = result.getOrElse { it.message ?: "فشل تثبيت أدوات البناء" }
        }
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            localBuildStatus = "جارٍ استيراد مشروع Gradle..."
            val result = withContext(Dispatchers.IO) { projectArchiveManager.importZip(uri) }
            result.onSuccess { importedProject = it; projectCatalog.register(it); projectItems = projectCatalog.list(); localBuildStatus = "تم استيراد المشروع: ${it.name}" }
                .onFailure { localBuildStatus = it.message ?: "فشل استيراد المشروع" }
        }
    }
    var projectPendingExport by remember { mutableStateOf<File?>(null) }
    val projectExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = projectPendingExport
        if (uri != null && source != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(context.cacheDir, "${source.name}-backup.zip")
                    projectArchiveManager.createProjectZip(source, temp).getOrThrow()
                    context.contentResolver.openOutputStream(uri)?.use { output -> temp.inputStream().use { it.copyTo(output) } }
                        ?: error("تعذر الكتابة إلى ملف النسخة الاحتياطية")
                    temp.delete()
                }
            }
            result.onSuccess { projectCatalog.recordEvent(source, "تم تصدير نسخة احتياطية آمنة من المشروع") }
                .onFailure { status = it.message ?: "فشل تصدير النسخة الاحتياطية" }
            projectPendingExport = null
        } else projectPendingExport = null
    }
    val termuxFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            termuxTreeUri = uri
            buildPrefs.edit().putString("termux_tree_uri", uri.toString()).apply()
            termuxStatus = "تم اختيار مجلد مشاركة Termux. تأكد من تنفيذ إعداد Termux مرة واحدة."
        }
    }
    val termuxPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val project = importedProject
            val tree = termuxTreeUri
            if (project == null || tree == null) termuxStatus = "استورد مشروعًا واختر مجلد مشاركة أولًا"
            else {
                termuxStatus = "جارٍ تجهيز المشروع وإرساله إلى Termux..."
                scope.launch {
                    val prepared = withContext(Dispatchers.IO) { termuxBridge.exportProject(project, tree) }
                    prepared.onSuccess { exported ->
                        termuxStatus = "تم تصدير المشروع. جارٍ تشغيل Gradle داخل Termux..."
                        termuxBridge.startBuild(exported, selectedVariant.name.lowercase())
                            .onSuccess { termuxStatus = "تم إرسال أمر البناء إلى Termux. انتظر نتيجة التنفيذ." }
                            .onFailure { termuxStatus = it.message ?: "تعذر بدء بناء Termux" }
                    }.onFailure { termuxStatus = it.message ?: "تعذر تجهيز المشروع لـ Termux" }
                }
            }
        } else termuxStatus = "لم يتم منح إذن RUN_COMMAND. يمكن منحه من إعدادات أذونات MD Studio."
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "GGUF model"
            localModels.register(uri, name)
            modelName = name
            status = if (localModels.isNativeInferenceEngineAvailable()) "تم اختيار النموذج والمحرك الأصلي متاح" else "تم تسجيل النموذج؛ يمكن تشغيله عبر Termux بعد إعداد llama.cpp"
        }
    }
    val apkExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        val source = latestApk
        if (uri != null && source != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("تعذر فتح ملف الحفظ")
            }.onSuccess { cloudDownloadStatus = "تم تصدير APK إلى المكان المحدد" }
                .onFailure { cloudDownloadStatus = it.message ?: "فشل تصدير APK" }
        }
    }

    fun startCloudBuildForRepository(repo: GitHubClient.Repository, project: File) {
        projectCatalog.updateStatus(project, "BUILDING", "تم بدء رفع المشروع وتشغيل البناء السحابي على GitHub Actions")
        projectItems = projectCatalog.list()
        cloudBuildBusy = true
        selectedRepository = repo
        cloudPublishStatus = "تم تجهيز المستودع ${repo.fullName}. جارٍ رفع ملفات المشروع..."
        workflowStatus = "جارٍ تشغيل GitHub Actions ومتابعة البناء..."
        val provider = GitHubWorkflowBuildProvider(
            githubClient, repo.owner, repo.name, repo.defaultBranch,
            File(context.filesDir, "builds/cloud")
        )
        val request = BuildRequest(
            project,
            if (selectedVariant == LocalBuildEngine.Variant.DEBUG) BuildRequest.Variant.DEBUG else BuildRequest.Variant.RELEASE,
            runTests = true
        )
        provider.build(request) { receipt ->
            cloudBuildBusy = false
            projectCatalog.updateStatus(project, if (receipt.success) "COMPLETED" else "ERROR", receipt.log.take(450))
            projectItems = projectCatalog.list()
            cloudPublishStatus = if (receipt.success) "تم رفع المشروع وبناء APK بنجاح داخل ${repo.fullName}." else "تعذر إكمال البناء داخل ${repo.fullName}."
            workflowStatus = receipt.log
            if (receipt.success && !receipt.artifactPath.isNullOrBlank()) {
                latestApk = File(receipt.artifactPath)
                cloudDownloadStatus = "تم تنزيل APK تلقائيًا: ${latestApk?.name}"
            }
        }
    }

    DisposableEffect(context, termuxTreeUri, selectedVariant) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action != TermuxResultService.ACTION_RESULT) return
                val exit = intent.getIntExtra("exit_code", -1)
                val stdout = intent.getStringExtra("stdout").orEmpty()
                val stderr = intent.getStringExtra("stderr").orEmpty()
                val error = intent.getStringExtra("error").orEmpty()
                val builtVariant = intent.getStringExtra("build_variant") ?: "debug"
                val taskType = intent.getStringExtra("task_type") ?: "build"
                if (taskType == "llama") {
                    localAiStatus = if (exit == 0 && stdout.contains("MDSTUDIO_LLAMA_DONE")) "اكتمل الاستدلال المحلي." else "فشل تشغيل Llama (exit=$exit): $error\n${stderr.takeLast(2500)}"
                    localAiResponse = stdout.replace("MDSTUDIO_LLAMA_DONE", "").trim().takeLast(10000)
                } else if (exit == 0 && stdout.contains("MDSTUDIO_BUILD_SUCCESS")) {
                    termuxStatus = "نجح البناء المحلي عبر Termux.\n${stdout.takeLast(2500)}"
                    val tree = termuxTreeUri
                    if (tree != null) {
                        val root = DocumentFile.fromTreeUri(context, tree)
                        val name = "MD-Studio-$builtVariant.apk"
                        val doc = root?.findFile("MDStudioBuild")?.findFile("output")?.findFile(name)
                        if (doc != null) {
                            val saved = File(context.filesDir, "builds/termux/$name").apply { parentFile?.mkdirs() }
                            runCatching { context.contentResolver.openInputStream(doc.uri)?.use { input -> saved.outputStream().use { input.copyTo(it) } } }
                                .onSuccess { latestApk = saved; termuxStatus += "\nتم نسخ APK إلى مساحة MD Studio الداخلية." }
                                .onFailure { termuxStatus += "\nتعذر نسخ APK تلقائيًا: ${it.message}" }
                        }
                    }
                } else termuxStatus = "فشل بناء Termux (exit=$exit).\n${error}\n${stderr.takeLast(3500)}\n${stdout.takeLast(3500)}"
            }
        }
        val filter = IntentFilter(TermuxResultService.ACTION_RESULT)
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MD Studio", fontWeight = FontWeight.Bold)
                        Text("بيئة تطوير Android • AMD Studio", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    AssistChip(onClick = { status = "المحركات المحلية: تحتاج إلى تهيئة أدوات البناء" },
                        label = { Text("حالة المحركات") }, leadingIcon = { Icon(Icons.Default.Memory, null) })
                }
            )
        },
        bottomBar = {
            NavigationBar {
                StudioTab.entries.forEach { tab ->
                    val icon = when (tab) {
                        StudioTab.Projects -> Icons.Default.Folder
                        StudioTab.Tests -> Icons.Default.BugReport
                        StudioTab.Workspace -> Icons.Default.Code
                        StudioTab.Build -> Icons.Default.Build
                        StudioTab.Store -> Icons.Default.Extension
                        StudioTab.Settings -> Icons.Default.Settings
                    }
                    NavigationBarItem(selected == tab, onClick = { selected = tab },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(tab.label, maxLines = 1) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(14.dp)) {
            when (selected) {
                StudioTab.Projects -> {
                    StatusCard(status)
                    Spacer(Modifier.height(12.dp))
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("ابدأ مشروع Android", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("اكتب اسم التطبيق وفكرته. يتولى مدير المشروع تجهيز المتطلبات والخطة الأولية قبل تنفيذ البناء.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(value = prompt, onValueChange = { prompt = it },
                                modifier = Modifier.fillMaxWidth(), minLines = 3,
                                label = { Text("اسم التطبيق وفكرته") })
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = {
                                if (prompt.isBlank()) status = "اكتب اسم التطبيق وفكرته أولًا"
                                else {
                                    isPreparing = true
                                    status = "جارٍ إرسال الفكرة إلى محرك الذكاء الاصطناعي..."
                                    val selectedMode = aiMode
                                    val selectedConsumption = currentAiConsumption()
                                    val selectedStyle = responseStyle
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { geminiRouter.generate(
                                            "حلل فكرة تطبيق Android التالية، واكتب خطة تنفيذ عملية ومكونات الشاشات والبيانات والاختبارات. لا تدّع تنفيذ الملفات.\n\n$prompt",
                                            responseStyle = selectedStyle, mode = selectedMode, consumption = selectedConsumption, usageLedger = aiUsageLedger
                                        ) }
                                        aiUsageSnapshot = aiUsageLedger.snapshot()
                                        isPreparing = false
                                        status = if (result.success) "اكتملت صياغة الخطة الأولية: ${result.text?.take(220) ?: "تم استلام رد"}" else (result.message ?: "فشل طلب الذكاء الاصطناعي")
                                    }
                                }
                            }, enabled = !isPreparing, modifier = Modifier.fillMaxWidth()) {
                                if (isPreparing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.AutoAwesome, null)
                                Spacer(Modifier.width(8.dp)); Text(if (isPreparing) "جارٍ التحليل..." else "تحليل الفكرة")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("تجهيز تطبيق كامل بالذكاء الاصطناعي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("يولّد ملفات Kotlin وموارد فعلية، يعرض ملخصها أولًا، ثم ينتظر موافقة واحدة قبل إنشاء المشروع وكتابة الملفات.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = newProjectName, onValueChange = { newProjectName = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("اسم التطبيق") }, singleLine = true)
                            OutlinedTextField(value = newProjectPackage, onValueChange = { newProjectPackage = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("اسم الحزمة") }, singleLine = true)
                            Text(aiProjectStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                if (newProjectName.isBlank() || prompt.isBlank()) {
                                    aiProjectStatus = "أدخل اسم التطبيق وفكرته أولًا"
                                } else scope.launch {
                                    preparedAiProject = null
                                    aiProjectStatus = "جارٍ تحليل الفكرة وتوليد ملفات المصدر..."
                                    val selectedMode = aiMode
                                    val selectedConsumption = currentAiConsumption()
                                    val selectedStyle = responseStyle
                                    val selectedName = newProjectName
                                    val selectedPackage = newProjectPackage
                                    val selectedIdea = prompt
                                    val prepared = withContext(Dispatchers.IO) {
                                        aiProjectGenerator.prepare(selectedName, selectedPackage, selectedIdea, selectedStyle, selectedMode, selectedConsumption, aiUsageLedger)
                                    }
                                    aiUsageSnapshot = aiUsageLedger.snapshot()
                                    prepared.onSuccess { plan ->
                                        preparedAiProject = plan
                                        aiProjectStatus = "تم تجهيز ${plan.files.size} ملفًا. راجع الملخص ثم وافق على إنشاء المشروع.\n${plan.summary}\n${plan.files.joinToString("\n") { "• ${it.path}" }}"
                                    }.onFailure { aiProjectStatus = it.message ?: "فشل تجهيز المشروع" }
                                }
                            }, enabled = !isPreparing && newProjectName.isNotBlank() && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("تجهيز الملفات والخطة")
                            }
                            val plan = preparedAiProject
                            if (plan != null) {
                                Button(onClick = {
                                    scope.launch {
                                        aiProjectStatus = "تمت الموافقة؛ جارٍ إنشاء المشروع وكتابة الملفات..."
                                        val applied = withContext(Dispatchers.IO) { aiProjectGenerator.apply(plan) }
                                        if (applied.ok) {
                                            val project = File(applied.data.optString("project_path"))
                                            projectArchiveManager.activateProject(project).onSuccess {
                                                importedProject = it
                                                projectCatalog.register(it)
                                                projectItems = projectCatalog.list()
                                                workspaceFiles = workspaceManager.list(it)
                                                aiProjectStatus = "اكتمل إنشاء المشروع وكتابة ${plan.files.size} ملفًا. يمكنك الآن بناء APK سحابيًا عبر GitHub Actions."
                                                preparedAiProject = null
                                                selected = StudioTab.Build
                                            }.onFailure { aiProjectStatus = it.message ?: "تعذر تفعيل المشروع" }
                                        } else aiProjectStatus = applied.message
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("موافقة وتنفيذ")
                                }
                                OutlinedButton(onClick = {
                                    preparedAiProject = null
                                    aiProjectStatus = "تم إلغاء الخطة؛ لم تُكتب ملفات المشروع."
                                }, modifier = Modifier.fillMaxWidth()) { Text("إلغاء الخطة") }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("إنشاء مشروع Android فعلي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = newProjectName, onValueChange = { newProjectName = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("اسم التطبيق") }, singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = newProjectPackage, onValueChange = { newProjectPackage = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("اسم الحزمة") }, singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            Text(scaffoldStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Button(onClick = {
                                scope.launch {
                                    scaffoldStatus = "جارٍ إنشاء ملفات مشروع Kotlin/Compose..."
                                    val created = withContext(Dispatchers.IO) { projectScaffolder.create(newProjectName, newProjectPackage) }
                                    if (created.ok) {
                                        val path = created.data.optString("project_path")
                                        val activated = projectArchiveManager.activateProject(File(path))
                                        activated.onSuccess {
                                            importedProject = it
                                            projectCatalog.register(it)
                                            projectItems = projectCatalog.list()
                                            workspaceFiles = workspaceManager.list(it)
                                            scaffoldStatus = "تم إنشاء المشروع وتفعيله: ${it.name}"
                                            selected = StudioTab.Build
                                        }.onFailure { scaffoldStatus = it.message ?: "تعذر تفعيل المشروع" }
                                    } else scaffoldStatus = created.message
                                }
                            }, enabled = newProjectName.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.AddBox, null); Spacer(Modifier.width(8.dp)); Text("إنشاء المشروع وتفعيله")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("سجل المشروعات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { projectItems = projectCatalog.list() }) { Text("تحديث") }
                            }
                            Text("المشروعات المحفوظة محليًا: ${projectItems.size}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            projectItems.forEach { item ->
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, fontWeight = FontWeight.SemiBold)
                                        Text("${item.status} • ${File(item.path).name}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                    TextButton(onClick = {
                                        historyProject = item
                                        projectHistory = projectCatalog.history(File(item.path), 60)
                                    }) { Text("السجل") }
                                    TextButton(onClick = {
                                        projectPendingExport = File(item.path)
                                        projectExportLauncher.launch("${item.name}-MDStudio-backup.zip")
                                    }) { Text("نسخة") }
                                    TextButton(onClick = {
                                        val target = File(item.path)
                                        projectArchiveManager.activateProject(target).onSuccess {
                                            importedProject = it
                                            projectCatalog.register(it, item.status)
                                            projectCatalog.recordEvent(it, "تم فتح المشروع في مساحة العمل")
                                            projectItems = projectCatalog.list()
                                            workspaceFiles = workspaceManager.list(it)
                                            workspaceStatus = "تم فتح المشروع ${item.name}"
                                            selected = StudioTab.Workspace
                                        }.onFailure { status = it.message ?: "تعذر فتح المشروع" }
                                    }) { Text("فتح") }
                                }
                            }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("مدير التنفيذ الهندسي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("يحلل المهمة، يختار أدوات MD Studio المسجلة، يعرض الخطة، ثم ينفذها بعد موافقة واحدة.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            if (importedProject == null) {
                                Text("استورد أو أنشئ مشروع Android أولًا لتفعيل أدوات مساحة العمل.", color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedTextField(value = agentTaskTitle, onValueChange = { agentTaskTitle = it },
                                label = { Text("عنوان المهمة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = agentTaskRequest, onValueChange = { agentTaskRequest = it },
                                label = { Text("المطلوب تنفيذه بالتفصيل") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                            Button(onClick = {
                                agentBusy = true; agentPendingPlan = null; agentStatus = "جارٍ تحليل المهمة وتجهيز خطة التنفيذ..."
                                val selectedMode = aiMode
                                aiAdapter.configure(selectedMode, currentAiConsumption(), aiUsageLedger)
                                val selectedTitle = agentTaskTitle
                                val selectedRequest = agentTaskRequest
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        agentEngine.preparePlanFromModel(selectedTitle, selectedRequest)
                                    }
                                    aiUsageSnapshot = aiUsageLedger.snapshot()
                                    agentBusy = false
                                    if (result.ok) {
                                        agentPendingPlan = result.data
                                        agentStatus = result.message
                                        importedProject?.let { projectCatalog.recordEvent(it, "تم تجهيز خطة للمدير الهندسي: ${agentTaskTitle.take(100)}", "PLANNING") }
                                    } else agentStatus = result.message
                                }
                            }, enabled = !agentBusy && agentTaskTitle.isNotBlank() && agentTaskRequest.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()) {
                                if (agentBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.AutoAwesome, null)
                                Spacer(Modifier.width(8.dp)); Text("تحليل وتجهيز الخطة")
                            }
                            Text(agentStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            agentPendingPlan?.let { plan ->
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                Text(plan.optString("summary"), style = MaterialTheme.typography.bodyMedium)
                                val steps = plan.optJSONArray("steps")
                                if (steps != null) for (index in 0 until steps.length()) {
                                    val step = steps.optJSONObject(index) ?: continue
                                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Text("${index + 1}. ${step.optString("tool")} — ${step.optString("purpose")}",
                                            style = MaterialTheme.typography.bodySmall)
                                        Text(step.optJSONObject("arguments")?.toString().orEmpty(),
                                            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                }
                                Text("لن يبدأ أي تعديل قبل موافقتك. راجع الأدوات والخطوات قبل التنفيذ.",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val planId = plan.optString("plan_id")
                                        val selectedMode = aiMode
                                        val selectedConsumption = currentAiConsumption()
                                        val repairRounds = currentRepairRounds()
                                        aiAdapter.configure(selectedMode, selectedConsumption, aiUsageLedger)
                                        agentBusy = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { agentEngine.executeApproved(planId, true, repairRounds).first }
                                            aiUsageSnapshot = aiUsageLedger.snapshot()
                                            agentBusy = false
                                            agentStatus = result.message
                                            agentPendingPlan = null
                                            importedProject?.let { projectCatalog.recordEvent(it, "نتيجة تنفيذ المدير الهندسي: ${result.message}", if (result.ok) "DEVELOPMENT" else "ERROR") }
                                            projectItems = projectCatalog.list()
                                        }
                                    }, enabled = !agentBusy, modifier = Modifier.weight(1f)) { Text("موافقة وتنفيذ") }
                                    OutlinedButton(onClick = {
                                        val planId = plan.optString("plan_id")
                                        agentEngine.executeApproved(planId, false)
                                        agentPendingPlan = null
                                        agentStatus = "تم إلغاء الخطة دون تنفيذ"
                                    }, enabled = !agentBusy, modifier = Modifier.weight(1f)) { Text("إلغاء") }
                                }
                            }
                        }
                    }
                    FeatureCard("مصادر التنفيذ", "البناء السحابي عبر GitHub Actions مع تنزيل APK تلقائيًا")
                }
                StudioTab.Tests -> {
                    SectionTitle("مركز الاختبارات")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("تشخيص المشروع الحالي", fontWeight = FontWeight.Bold)
                            Text(importedProject?.absolutePath ?: "لا يوجد مشروع مستورد", color = Color.Gray)
                            Button(onClick = {
                                val project = importedProject
                                diagnostics = if (project == null) listOf("تعذر الفحص: لا يوجد مشروع مستورد") else buildList {
                                    add(if (File(project, "settings.gradle.kts").isFile || File(project, "settings.gradle").isFile) "PASS: ملف إعداد Gradle موجود" else "FAIL: ملف إعداد Gradle مفقود")
                                    add(if (File(project, "app/build.gradle.kts").isFile || File(project, "app/build.gradle").isFile) "PASS: وحدة app موجودة" else "WARN: لم يتم العثور على app/build.gradle")
                                    add(if (File(project, "app/src/main/AndroidManifest.xml").isFile) "PASS: AndroidManifest.xml موجود" else "FAIL: AndroidManifest.xml مفقود")
                                    add(if (File(project, "gradlew").isFile) "PASS: Gradle Wrapper موجود" else "WARN: لا يوجد Gradle Wrapper؛ يعتمد البناء على Gradle المثبت")
                                    val sources = File(project, "app/src/main").walkTopDown().count { it.isFile && (it.extension in setOf("kt", "java")) }
                                    add("INFO: ملفات Kotlin/Java: $sources")
                                    add(if (sources > 0) "PASS: توجد ملفات مصدر" else "FAIL: لا توجد ملفات Kotlin/Java")
                                    add("INFO: هذا فحص بنيوي فقط؛ لا يُعد اختبار بناء أو تشغيل")
                                }
                            }, enabled = importedProject != null, modifier = Modifier.fillMaxWidth()) { Text("تشغيل التشخيص البنيوي") }
                            diagnostics.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall, color = if (line.startsWith("FAIL")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    FeatureCard("اختبارات البناء", "نفّذ بناء Debug أو Release من مركز البناء؛ لا تظهر نتيجة نجاح إلا بعد الحصول على APK فعلي.")
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("تثبيت التطبيق وتشغيله", fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = runtimePackageName, onValueChange = { runtimePackageName = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("اسم حزمة التطبيق مثل com.example.app") })
                            Text(runtimeStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                val apk = latestApk
                                if (apk == null) runtimeStatus = "لا يوجد APK ناتج. نفّذ بناءً أو نزّل Artifact أولًا."
                                else {
                                    val result = runtimeDevice.installApk(apk)
                                    runtimeStatus = result.message
                                }
                            }, enabled = latestApk != null, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Android, null); Spacer(Modifier.width(8.dp)); Text("فتح شاشة تثبيت APK")
                            }
                            OutlinedButton(onClick = {
                                if (runtimePackageName.isBlank()) runtimeStatus = "أدخل اسم الحزمة أولًا"
                                else {
                                    val launched = runtimeDevice.launch(runtimePackageName)
                                    if (!launched.ok) runtimeStatus = launched.message
                                    else {
                                        val evidence = runtimeDevice.captureEvidence()
                                        runtimeStatus = if (evidence.ok) "${launched.message}\n${evidence.message}\n${evidence.data}" else evidence.message
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("تشغيل التطبيق وجمع بيانات التحقق")
                            }
                            Text("التثبيت يظل بموافقة Android. بيانات التحقق تشمل معلومات الحزمة المثبتة ولا تعني اجتياز اختبارات الوظائف تلقائيًا.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SectionTitle("خريطة محركات PocketBuilder التي تم رصدها")
                    Text("هذه خريطة لقدرات ظهرت أسماؤها في DEX، وليست ادعاءً بنقل كود PocketBuilder الأصلي. حالة كل محرك توضح ما أُعيد تنفيذه وما زال يحتاج موصلًا.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    EngineRegistry.all.forEach { engine ->
                        val stateLabel = when (engine.status) {
                            EngineDescriptor.Status.IMPLEMENTED -> "تنفيذ MD Studio موجود"
                            EngineDescriptor.Status.ADAPTER_READY -> "طبقة الربط موجودة؛ يحتاج توصيل/اختبار"
                            EngineDescriptor.Status.REQUIRES_PROVIDER -> "يحتاج مزود خدمة"
                            EngineDescriptor.Status.NOT_PORTABLE_FROM_APK -> "لا يمكن نقله من APK وحده"
                        }
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(engine.name, fontWeight = FontWeight.Bold)
                                Text(stateLabel, color = if (engine.status == EngineDescriptor.Status.IMPLEMENTED) Color(0xFF16804A) else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                Text("مرجع الأصل: ${engine.sourceCapability}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Text("في MD Studio: ${engine.implementation}", style = MaterialTheme.typography.bodySmall)
                                if (engine.requirements.isNotBlank()) Text("المتطلبات: ${engine.requirements}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
                StudioTab.Workspace -> {
                    SectionTitle("مساحة العمل")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("مستكشف ملفات المشروع", fontWeight = FontWeight.Bold)
                            Text(workspaceStatus, color = Color.Gray)
                            OutlinedButton(onClick = {
                                val project = importedProject
                                if (project == null) workspaceStatus = "استورد مشروع Android أولًا"
                                else {
                                    workspaceFiles = workspaceManager.list(project)
                                    workspaceStatus = "تم تحميل ${workspaceFiles.size} عنصرًا من ${project.name}"
                                }
                            }, enabled = importedProject != null, modifier = Modifier.fillMaxWidth()) { Text("تحديث قائمة الملفات") }
                            workspaceFiles.filter { !it.directory }.take(100).forEach { entry ->
                                TextButton(onClick = {
                                    val project = importedProject ?: return@TextButton
                                    workspaceManager.read(project, entry.relativePath).onSuccess {
                                        selectedSourcePath = entry.relativePath
                                        sourceContent = it
                                        workspaceStatus = "تم فتح ${entry.relativePath}"
                                    }.onFailure { workspaceStatus = it.message ?: "تعذر فتح الملف" }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("${entry.relativePath}  •  ${entry.bytes / 1024} KB", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (selectedSourcePath != null) {
                                Text("تحرير: $selectedSourcePath", fontWeight = FontWeight.Bold)
                                OutlinedTextField(value = sourceContent, onValueChange = { sourceContent = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp), minLines = 8,
                                    label = { Text("محتوى الملف") })
                                Button(onClick = {
                                    val project = importedProject
                                    val path = selectedSourcePath
                                    if (project != null && path != null) workspaceManager.write(project, path, sourceContent)
                                        .onSuccess { workspaceStatus = "تم حفظ التغييرات في $path" }
                                        .onFailure { workspaceStatus = it.message ?: "فشل حفظ الملف" }
                                }, modifier = Modifier.fillMaxWidth()) { Text("حفظ التعديلات") }
                            }
                        }
                    }
                    FeatureCard("محركات التنفيذ الذكي", "يتضمن المصدر طبقة وكيل بخطط JSON والتحقق من الأدوات، مع منشئ مشاريع ومساحة عمل وتشخيص. ربط محادثة المشروع بالتنفيذ التلقائي ما زال ضمن التطوير.")
                }
                StudioTab.Build -> {
                    SectionTitle("مركز البناء")
                    FeatureCard("بناء APK عبر السحابة", "يتم تجهيز نسخة من المشروع على فرع مستقل في GitHub، ثم تشغيل GitHub Actions لبناء APK واختبارات الوحدة وتنزيل ملف APK داخل MD Studio. لا يحتاج هاتفك إلى JDK أو Gradle أو Android SDK.")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("البناء السحابي عبر GitHub Actions", fontWeight = FontWeight.Bold)
                            Text("المستودع المحدد: ${selectedRepository?.fullName ?: "لم يتم الاختيار"}", color = Color.Gray)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedVariant == LocalBuildEngine.Variant.DEBUG, onClick = { selectedVariant = LocalBuildEngine.Variant.DEBUG })
                                Text("Debug")
                                Spacer(Modifier.width(16.dp))
                                RadioButton(selected = selectedVariant == LocalBuildEngine.Variant.RELEASE, onClick = { selectedVariant = LocalBuildEngine.Variant.RELEASE })
                                Text("Release غير موقّع")
                            }
                            Text(workflowStatus, color = MaterialTheme.colorScheme.primary)
                            Button(onClick = {
                                val repo = selectedRepository
                                val project = importedProject
                                if (repo == null) cloudPublishStatus = "اختر مستودع GitHub أولًا"
                                else if (project == null) cloudPublishStatus = "أنشئ أو استورد مشروع Gradle أولًا"
                                else {
                                    cloudBuildBusy = true
                                    cloudPublishStatus = "جارٍ رفع لقطة المشروع إلى فرع مستقل..."
                                    workflowStatus = "جارٍ تشغيل Workflow ومتابعة البناء تلقائيًا..."
                                    val provider = GitHubWorkflowBuildProvider(
                                        githubClient, repo.owner, repo.name, repo.defaultBranch,
                                        File(context.filesDir, "builds/cloud")
                                    )
                                    val request = BuildRequest(
                                        project,
                                        if (selectedVariant == LocalBuildEngine.Variant.DEBUG) BuildRequest.Variant.DEBUG else BuildRequest.Variant.RELEASE,
                                        runTests = true
                                    )
                                    projectCatalog.updateStatus(project, "BUILDING", "بدأ البناء المحلي")
                                    projectItems = projectCatalog.list()
                                    provider.build(request) { receipt ->
                                        cloudBuildBusy = false
                                        projectCatalog.updateStatus(project, if (receipt.success) "COMPLETED" else "ERROR", receipt.log.take(450))
                                        projectItems = projectCatalog.list()
                                        cloudPublishStatus = if (receipt.success) "تم رفع المشروع وتشغيل البناء بنجاح." else "تعذر إكمال البناء السحابي."
                                        workflowStatus = receipt.log
                                        if (receipt.success && !receipt.artifactPath.isNullOrBlank()) {
                                            latestApk = File(receipt.artifactPath)
                                            cloudDownloadStatus = "تم تنزيل APK تلقائيًا: ${latestApk?.name}"
                                        }
                                    }
                                }
                            }, enabled = selectedRepository != null && importedProject != null && !cloudBuildBusy, modifier = Modifier.fillMaxWidth()) {
                                if (cloudBuildBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.CloudUpload, null)
                                Spacer(Modifier.width(8.dp)); Text(if (cloudBuildBusy) "جارٍ البناء السحابي..." else "رفع المشروع وبناء APK وتنزيله تلقائيًا")
                            }
                            OutlinedButton(
                                onClick = {
                                    val project = importedProject
                                    if (project == null) {
                                        cloudPublishStatus = "أنشئ أو استورد مشروع Android أولًا"
                                    } else if (!githubClient.hasSavedToken()) {
                                        cloudPublishStatus = "اربط حساب GitHub من الإعدادات أولًا"
                                    } else {
                                        cloudBuildBusy = true
                                        cloudPublishStatus = "جارٍ إنشاء مستودع خاص تلقائيًا باسم مستمد من اسم المشروع..."
                                        githubClient.createRepositoryForProject(project) { result ->
                                            result.onSuccess { repo ->
                                                repositories = (repositories.filterNot { it.fullName == repo.fullName } + repo)
                                                repoStatus = "تم إنشاء المستودع الخاص تلقائيًا: ${repo.fullName}"
                                                startCloudBuildForRepository(repo, project)
                                            }.onFailure { error ->
                                                cloudBuildBusy = false
                                                cloudPublishStatus = error.message ?: "تعذر إنشاء مستودع GitHub"
                                                workflowStatus = "راجع صلاحيات رمز GitHub؛ إنشاء المستودع يحتاج Repository creation: write أو Administration: write."
                                            }
                                        }
                                    }
                                },
                                enabled = importedProject != null && githubClient.hasSavedToken() && !cloudBuildBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (cloudBuildBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(8.dp))
                                Text("إنشاء مستودع خاص تلقائيًا ثم رفع المشروع وبناء APK")
                            }
                            Text("ينشئ MD Studio مستودعًا خاصًا باسم المشروع، ثم يرفع الملفات، ويجهز Workflow، ويشغل البناء، ويتابع النتيجة، وينزّل APK تلقائيًا. لا يحتاج اختيار مستودع يدويًا.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Text(cloudPublishStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            OutlinedButton(onClick = {
                                val repo = selectedRepository
                                if (repo == null) workflowStatus = "اختر مستودعًا من الإعدادات أولًا"
                                else {
                                    workflowStatus = "جارٍ تشغيل Workflow للمستودع..."
                                    githubClient.dispatchWorkflow(repo.owner, repo.name, "mdstudio-build.yml", repo.defaultBranch,
                                        mapOf("build_variant" to selectedVariant.name.lowercase(), "run_tests" to "true")) { result ->
                                        workflowStatus = result.fold({ "تم إرسال طلب البناء. حدّث حالة التشغيل بعد قليل." }, { it.message ?: "فشل تشغيل البناء" })
                                    }
                                }
                            }, enabled = selectedRepository != null, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("بناء المستودع الحالي دون رفع ملفات جديدة")
                            }
                            OutlinedButton(onClick = {
                                val repo = selectedRepository
                                if (repo == null) workflowStatus = "اختر مستودعًا أولًا"
                                else {
                                    workflowStatus = "جارٍ تحديث تشغيلات البناء..."
                                    githubClient.latestWorkflowRuns(repo.owner, repo.name, "mdstudio-build.yml") { result ->
                                        result.onSuccess { runs ->
                                            workflowRuns = runs.sortedByDescending { it.id }.take(10)
                                            workflowStatus = if (workflowRuns.isEmpty()) "لا توجد تشغيلات بناء حتى الآن" else "تم تحديث سجل التشغيلات (${workflowRuns.size})"
                                        }.onFailure { error ->
                                            workflowStatus = error.message ?: "تعذر جلب سجل التشغيلات"
                                        }
                                    }
                                }
                            }, enabled = selectedRepository != null, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("تحديث حالة البناء")
                            }
                            workflowRuns.take(5).forEach { run ->
                                ListItem(
                                    headlineContent = { Text("${run.name} • #${run.id}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text("${run.status} / ${run.conclusion ?: "قيد التنفيذ"}") },
                                    leadingContent = { Icon(if (run.conclusion == "success") Icons.Default.CheckCircle else Icons.Default.BuildCircle, null) },
                                    trailingContent = { TextButton(onClick = {
                                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(run.htmlUrl))) }
                                    }) { Text("فتح") } }
                                )
                                if (run.conclusion == "success") {
                                    TextButton(onClick = {
                                        val repo = selectedRepository ?: return@TextButton
                                        githubClient.listArtifacts(repo.owner, repo.name, run.id) { result ->
                                            result.onSuccess { workflowArtifacts = it.filterNot { a -> a.expired }; workflowStatus = "تم العثور على ${workflowArtifacts.size} ملف بناء" }
                                                .onFailure { workflowStatus = it.message ?: "تعذر قراءة Artifacts" }
                                        }
                                    }) { Text("عرض ملفات البناء") }
                                }
                            }
                            workflowArtifacts.forEach { artifact ->
                                ListItem(
                                    headlineContent = { Text(artifact.name) },
                                    supportingContent = { Text("${artifact.sizeBytes / 1024} KB") },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            val repo = selectedRepository ?: return@TextButton
                                            cloudDownloadStatus = "جارٍ تنزيل ${artifact.name}..."
                                            githubClient.downloadArtifactApk(repo.owner, repo.name, artifact.id, File(context.filesDir, "builds/cloud")) { result ->
                                                result.onSuccess { latestApk = it; cloudDownloadStatus = "تم تنزيل APK: ${it.name}" }
                                                    .onFailure { cloudDownloadStatus = it.message ?: "فشل تنزيل APK" }
                                            }
                                        }) { Text("تنزيل APK") }
                                    }
                                )
                            }
                            Text(cloudDownloadStatus, color = MaterialTheme.colorScheme.primary)
                            OutlinedButton(onClick = {
                                val apk = latestApk
                                if (apk == null) cloudDownloadStatus = "نزّل APK أولًا"
                                else apkExportLauncher.launch(apk.name)
                            }, enabled = latestApk != null, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.SaveAlt, null); Text("حفظ APK على الهاتف")
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    FeatureCard("إدارة APK", "تنزيل Artifact من GitHub Actions وحفظ APK في ذاكرة الهاتف. حزمة Release تظل غير موقعة ما لم تضف إعدادات توقيعك الخاصة.")
                }
                StudioTab.Store -> {
                    SectionTitle("مكتبة المحركات والنماذج")
                    FeatureCard("Google Gemini", "مفاتيح متعددة مشفرة مع التبديل إلى مفتاح آخر عند تجاوز الحصة أو حدوث خطأ مؤقت")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("مكتبة اعتمادات Maven Central", fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = libraryQuery, onValueChange = { libraryQuery = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("اسم المكتبة أو التقنية") })
                            Button(onClick = {
                                if (libraryQuery.isBlank()) libraryStatus = "اكتب اسم مكتبة للبحث"
                                else scope.launch {
                                    libraryStatus = "جارٍ البحث في Maven Central..."
                                    val result = withContext(Dispatchers.IO) { libraryEngine.search(libraryQuery) }
                                    if (result.ok) {
                                        val items = result.data.optJSONArray("items")
                                        libraryEntries = (0 until (items?.length() ?: 0)).mapNotNull { index ->
                                            items?.optJSONObject(index)?.let { item ->
                                                LibraryEntry(item.optString("id"), item.optString("title"), item.optString("coordinate"),
                                                    item.optString("description"), item.optString("repository"))
                                            }
                                        }
                                        libraryStatus = result.message
                                    } else libraryStatus = result.message
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Search, null); Text("بحث في المكتبات") }
                            Text(libraryStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            libraryEntries.take(20).forEach { entry ->
                                ListItem(
                                    headlineContent = { Text(entry.title) },
                                    supportingContent = { Text(entry.coordinate + "\n" + entry.description) },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            val project = importedProject
                                            if (project == null) libraryStatus = "استورد أو أنشئ مشروع Android أولًا"
                                            else scope.launch {
                                                val added = withContext(Dispatchers.IO) { libraryEngine.addDependency(project, entry.coordinate) }
                                                libraryStatus = added.message
                                            }
                                        }, enabled = importedProject != null) { Text("إضافة") }
                                    }
                                )
                            }
                            Text("إضافة الاعتماد تحفظ نسخة احتياطية من ملف Gradle قبل تعديله. لا تُضاف أي مكتبة إلا بعد ضغطك على إضافة.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Llama / GGUF محلي", fontWeight = FontWeight.Bold)
                            Text("النموذج المحدد: $modelName", color = Color.Gray)
                            OutlinedButton(onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FolderOpen, null); Text("اختيار ملف نموذج GGUF")
                            }
                            OutlinedTextField(value = localAiPrompt, onValueChange = { localAiPrompt = it },
                                modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("اكتب طلبك للنموذج المحلي") })
                            Text(localAiStatus, color = MaterialTheme.colorScheme.primary)
                            Button(onClick = {
                                val uri = localModels.currentModelUri()
                                val tree = termuxTreeUri
                                if (uri == null) localAiStatus = "اختر ملف GGUF أولًا"
                                else if (tree == null) localAiStatus = "اختر مجلد مشاركة من مركز البناء أولًا"
                                else if (localAiPrompt.isBlank()) localAiStatus = "اكتب طلبًا للنموذج"
                                else if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                    localAiStatus = "امنح MD Studio إذن Termux RUN_COMMAND من إعدادات Android أولًا"
                                else {
                                    localAiStatus = "جارٍ نسخ النموذج إلى مجلد Termux المشترك..."
                                    localAiResponse = ""
                                    scope.launch {
                                        val exported = withContext(Dispatchers.IO) { termuxLlamaBridge.exportModel(uri, modelName, tree) }
                                        exported.onSuccess { sharedModel ->
                                            localAiStatus = "تم تجهيز النموذج. جارٍ تشغيل الاستدلال محليًا..."
                                            termuxLlamaBridge.runPrompt(sharedModel, localAiPrompt)
                                                .onSuccess { localAiStatus = "تم إرسال الطلب إلى llama.cpp داخل Termux. انتظر النتيجة." }
                                                .onFailure { localAiStatus = it.message ?: "تعذر تشغيل النموذج" }
                                        }.onFailure { localAiStatus = it.message ?: "تعذر نسخ النموذج" }
                                    }
                                }
                            }, enabled = modelName != "لم يتم اختيار نموذج", modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Psychology, null); Text("تشغيل Llama محليًا")
                            }
                            if (localAiResponse.isNotBlank()) {
                                Text("رد النموذج", fontWeight = FontWeight.Bold)
                                Text(localAiResponse, color = Color(0xFF344054))
                            }
                            Text("يتطلب تشغيل scripts/setup-llama-termux.sh داخل Termux مرة واحدة. هذا تكامل محلي عبر Termux CLI وليس مكتبة JNI مدمجة داخل APK.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
                StudioTab.Settings -> {
                    SectionTitle("إعدادات MD Studio")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Workspaces, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("MD Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("منتج تطوير Android من AMD Studio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                AssistChip(onClick = {}, label = { Text("Alpha 08") })
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("هوية المنتج: MD Studio  •  الاستوديو المطوّر: AMD Studio", fontWeight = FontWeight.SemiBold)
                            Text("AI Studio اسم أداة عمل فقط، وليس اسم المطوّر أو هوية شهادة توقيع التطبيقات.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("المظهر العام", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                listOf("system" to "حسب النظام", "light" to "فاتح", "dark" to "داكن").forEach { (value, label) ->
                                    FilterChip(selected = themeMode == value, onClick = { onThemeModeChange(value) }, label = { Text(label) })
                                }
                            }
                            Text("يُحفظ اختيار المظهر ويُطبق على واجهات البرنامج.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("إعدادات التشغيل الأساسية", fontWeight = FontWeight.Bold)
                            PreferenceSwitch("إظهار تفاصيل حالة المحركات", "عرض حالة المحرك ومتطلباته بدل الاكتفاء باسم التقنية.", detailedEngineStatus) {
                                detailedEngineStatus = it; corePrefs.edit().putBoolean("detailed_engine_status", it).apply()
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("اللغة الأساسية: العربية — اتجاه الواجهة RTL", fontWeight = FontWeight.Medium)
                            Text("النطاق: تطوير تطبيقات Android. مسار إنتاج APK المعتمد حاليًا هو GitHub Actions.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Text("إنشاء المشروع بالذكاء الاصطناعي يعرض الخطة والملفات ثم ينتظر موافقة واحدة قبل الكتابة.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("مركز المحركات والتقنيات", fontWeight = FontWeight.Bold)
                            Text("كل محرك يعرض حالته الفعلية؛ المحرك المتاح كموصل لا يُعرض على أنه خدمة إنتاج مكتملة.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            EngineRegistry.all.forEach { engine ->
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(1f)) {
                                        Text(engine.name, fontWeight = FontWeight.SemiBold)
                                        Text(engine.implementation, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        if (detailedEngineStatus && engine.requirements.isNotBlank()) Text(engine.requirements, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(engine.status.name, style = MaterialTheme.typography.labelSmall, color = when (engine.status) {
                                        EngineDescriptor.Status.IMPLEMENTED -> Color(0xFF16834A)
                                        EngineDescriptor.Status.ADAPTER_READY, EngineDescriptor.Status.REQUIRES_PROVIDER -> Color(0xFF9A6700)
                                        else -> Color(0xFFB42318)
                                    })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("إعدادات الاستوديو وهوية التطبيقات", fontWeight = FontWeight.Bold)
                            Text("اسم الاستوديو المطوّر: AMD Studio", fontWeight = FontWeight.SemiBold)
                            Text("اسم المنتج: MD Studio", color = Color.Gray)
                            Text("هوية توقيع APK مستقلة: تُجهز بشهادة يملكها المستخدم، ولا تُستبدل باسم AI Studio أو باسم الاستوديو النصي.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Text("يتم اشتقاق اسم الحزمة واسم APK من اسم التطبيق وفق قواعد Android وتفضيلات المشروع.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionTitle("الاتصالات والذكاء الاصطناعي")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("وضع تشغيل الذكاء الاصطناعي", fontWeight = FontWeight.Bold)
                            Text("الأوضاع الثلاثة تستخدم نفس محركات التطوير والبناء والاختبار؛ الاختلاف في عمق تدخل الذكاء الاصطناعي واستهلاكه فقط.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Spacer(Modifier.height(8.dp))
                            ExposedDropdownMenuBox(expanded = aiModeExpanded, onExpandedChange = { aiModeExpanded = !aiModeExpanded }) {
                                OutlinedTextField(value = aiMode.title, onValueChange = {}, readOnly = true,
                                    modifier = Modifier.menuAnchor().fillMaxWidth(), label = { Text("مستوى التدخل") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = aiModeExpanded) })
                                ExposedDropdownMenu(expanded = aiModeExpanded, onDismissRequest = { aiModeExpanded = false }) {
                                    AiOperatingMode.values().forEach { mode ->
                                        DropdownMenuItem(text = { Text("${mode.title} — ${mode.description}") }, onClick = {
                                            aiMode = mode
                                            stylePrefs.edit().putString("operating_mode", mode.key).apply()
                                            aiModeExpanded = false
                                        })
                                    }
                                }
                            }
                            when (aiMode) {
                                AiOperatingMode.NORMAL -> Text("الوضع العادي يستخدم ميزانية اقتصادية تلقائيًا، ويعتمد على محركات MD Studio أولًا دون حرمانه من أي قدرة أساسية.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                AiOperatingMode.ADVANCED -> {
                                    ExposedDropdownMenuBox(expanded = advancedBudgetExpanded, onExpandedChange = { advancedBudgetExpanded = !advancedBudgetExpanded }) {
                                        OutlinedTextField(value = advancedConsumption.title, onValueChange = {}, readOnly = true,
                                            modifier = Modifier.menuAnchor().fillMaxWidth(), label = { Text("استهلاك الوضع القوي") },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = advancedBudgetExpanded) })
                                        ExposedDropdownMenu(expanded = advancedBudgetExpanded, onDismissRequest = { advancedBudgetExpanded = false }) {
                                            AiConsumption.values().forEach { budget -> DropdownMenuItem(text = { Text(budget.title) }, onClick = {
                                                advancedConsumption = budget; stylePrefs.edit().putString("advanced_consumption", budget.key).apply(); advancedBudgetExpanded = false
                                            }) }
                                        }
                                    }
                                }
                                AiOperatingMode.PROFESSIONAL -> {
                                    ExposedDropdownMenuBox(expanded = professionalBudgetExpanded, onExpandedChange = { professionalBudgetExpanded = !professionalBudgetExpanded }) {
                                        OutlinedTextField(value = professionalConsumption.title, onValueChange = {}, readOnly = true,
                                            modifier = Modifier.menuAnchor().fillMaxWidth(), label = { Text("استهلاك الوضع الاحترافي") },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = professionalBudgetExpanded) })
                                        ExposedDropdownMenu(expanded = professionalBudgetExpanded, onDismissRequest = { professionalBudgetExpanded = false }) {
                                            AiConsumption.values().forEach { budget -> DropdownMenuItem(text = { Text(budget.title) }, onClick = {
                                                professionalConsumption = budget; stylePrefs.edit().putString("professional_consumption", budget.key).apply(); professionalBudgetExpanded = false
                                            }) }
                                        }
                                    }
                                }
                            }
                            if (aiMode != AiOperatingMode.NORMAL) Text("دورات الإصلاح التلقائي القصوى بعد فشل خطوة: ${currentRepairRounds()} (محدودة لمنع التكرار)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            Text("استهلاك الذكاء الاصطناعي", fontWeight = FontWeight.Bold)
                            Text("الطلبات: ${aiUsageSnapshot.optLong("requests")}  •  مدخلات: ${aiUsageSnapshot.optLong("input_tokens")} رمز  •  مخرجات: ${aiUsageSnapshot.optLong("output_tokens")} رمز", style = MaterialTheme.typography.bodySmall)
                            Text("تقدير تقريبي عند غياب بيانات الاستخدام من المزود: ${aiUsageSnapshot.optLong("estimated_requests")} طلب", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            OutlinedButton(onClick = { aiUsageLedger.reset(); aiUsageSnapshot = aiUsageLedger.snapshot() }, modifier = Modifier.fillMaxWidth()) { Text("تصفير عداد الاستهلاك المحلي") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("أسلوب ردود الذكاء الاصطناعي", fontWeight = FontWeight.Bold)
                            ExposedDropdownMenuBox(expanded = responseStyleExpanded, onExpandedChange = { responseStyleExpanded = !responseStyleExpanded }) {
                                OutlinedTextField(value = responseStyle, onValueChange = {}, readOnly = true,
                                    modifier = Modifier.menuAnchor().fillMaxWidth(), label = { Text("نمط الإجابة") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = responseStyleExpanded) })
                                ExposedDropdownMenu(expanded = responseStyleExpanded, onDismissRequest = { responseStyleExpanded = false }) {
                                    listOf("احترافي متوازن", "مختصر ومباشر", "تفصيلي تعليمي", "مطور Android", "مصري مبسط").forEach { style ->
                                        DropdownMenuItem(text = { Text(style) }, onClick = {
                                            responseStyle = style
                                            stylePrefs.edit().putString("response_style", style).apply()
                                            responseStyleExpanded = false
                                        })
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("مفاتيح Google AI", fontWeight = FontWeight.Bold)
                            Text("المفاتيح المحفوظة: ${apiKeys.size}", color = Color.Gray)
                            apiKeys.forEachIndexed { index, key ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("مفتاح ${index + 1}: ${key.take(5)}••••", Modifier.weight(1f))
                                    IconButton(onClick = { keyStore.removeKey(index); apiKeys = keyStore.readKeys() }) {
                                        Icon(Icons.Default.Delete, "حذف")
                                    }
                                }
                            }
                            OutlinedButton(onClick = { showKeyInput = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Add, null); Text("إضافة مفتاح Google آخر")
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("GitHub", fontWeight = FontWeight.Bold)
                            Text("الحالة: $githubStatus", color = Color.Gray)
                            OutlinedTextField(value = githubToken, onValueChange = { githubToken = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("GitHub Personal Access Token") })
                            Text("Fine-grained: All repositories + Repository creation: write (أو Administration: write) + Contents: write + Workflows: write + Actions: write + Metadata: read. البديل Classic: repo + workflow. لا تمنح صلاحية حذف المستودعات.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Button(onClick = {
                                githubClient.verify(githubToken) { result ->
                                    githubStatus = result
                                    githubAccounts = githubClient.accountNames()
                                    activeGithubAccount = githubClient.activeAccount()
                                    if (result.startsWith("متصل")) githubToken = ""
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("إضافة حساب / اختبار الاتصال وحفظه مشفرًا") }
                            Text("الحساب النشط: $activeGithubAccount", color = MaterialTheme.colorScheme.primary)
                            githubAccounts.forEach { login ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = activeGithubAccount == login, onClick = {
                                        if (githubClient.selectAccount(login)) {
                                            activeGithubAccount = login
                                            githubStatus = "تم التبديل إلى حساب $login"
                                            repositories = emptyList()
                                            selectedRepository = null
                                        }
                                    })
                                    Text(login)
                                }
                            }
                            OutlinedButton(onClick = {
                                githubClient.listRepositories { result ->
                                    result.onSuccess { list ->
                                        repositories = list
                                        repoStatus = "تم تحميل ${list.size} مستودعًا"
                                        if (selectedRepository == null) selectedRepository = list.firstOrNull()
                                    }.onFailure { repoStatus = it.message ?: "تعذر تحميل المستودعات" }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("تحميل مستودعات GitHub") }
                            Text(repoStatus, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            repositories.take(30).forEach { repo ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selectedRepository?.fullName == repo.fullName,
                                        onClick = { selectedRepository = repo })
                                    Column(Modifier.weight(1f)) {
                                        Text(repo.fullName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (repo.isPrivate) "خاص" else "عام", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                }
                            }
                            Text("للتشغيل التلقائي الكامل: استخدم Fine-grained token مع All repositories وصلاحيات Repository creation: write (أو Administration: write) وContents: write وWorkflows: write وActions: write وMetadata: read. أو Classic token بنطاقي repo وworkflow. لا يحتاج MD Studio صلاحية حذف المستودعات.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Text("يُخزن الرمز محليًا بشكل مشفر في النسخة التنفيذية الكاملة. لا تشارك الرمز داخل المحادثات أو المستودعات.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("الذكاء الاصطناعي المحلي", fontWeight = FontWeight.Bold)
                            Text("النموذج: $modelName", color = Color.Gray)
                            Text("استيراد ملفات GGUF متاح. تشغيل الاستدلال يتطلب دمج مكتبة llama.cpp الأصلية.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            OutlinedButton(onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FolderOpen, null); Text("اختيار ملف نموذج محلي")
                            }
                        }
                    }
                }
            }
        }
    }

    historyProject?.let { selectedHistoryProject ->
        AlertDialog(
            onDismissRequest = { historyProject = null },
            title = { Text("سجل المشروع: ${selectedHistoryProject.name}") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (projectHistory.isEmpty()) Text("لا توجد أحداث مسجلة لهذا المشروع بعد.")
                    projectHistory.forEach { event ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("${event.state} • ${android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(event.at))} ${android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(event.at))}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(event.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { historyProject = null }) { Text("إغلاق") } }
        )
    }

    if (showKeyInput) {
        AlertDialog(
            onDismissRequest = { showKeyInput = false; keyInput = "" },
            title = { Text("إضافة مفتاح Google AI") },
            text = { OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, label = { Text("API Key") }) },
            confirmButton = { TextButton(onClick = {
                if (keyInput.isNotBlank()) { keyStore.addKey(keyInput.trim()); apiKeys = keyStore.readKeys() }
                keyInput = ""; showKeyInput = false
            }) { Text("حفظ") } },
            dismissButton = { TextButton(onClick = { showKeyInput = false; keyInput = "" }) { Text("إلغاء") } }
        )
    }
}

@Composable private fun PreferenceSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable private fun StatusCard(status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp)); Text(status, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
@Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)) }
@Composable private fun FeatureCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp)) { Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
