package com.amd.mdstudio.github

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amd.mdstudio.ai.EncryptedSecret
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import org.json.JSONObject

/** GitHub REST client. Tokens are encrypted at rest and are never included in status messages. */
class GitHubClient(context: Context) {
    private val secret = EncryptedSecret(context, "mdstudio_github_token_key")
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.MINUTES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Repository(val fullName: String, val owner: String, val name: String, val defaultBranch: String, val isPrivate: Boolean)
    data class WorkflowRun(val id: Long, val name: String, val status: String, val conclusion: String?, val htmlUrl: String, val headBranch: String = "")
    data class Artifact(val id: Long, val name: String, val sizeBytes: Long, val expired: Boolean, val downloadUrl: String)

    private fun activeToken(): String? {
        val login = secret.get("github_active_login")
        return if (login.isNullOrBlank()) secret.get("github_token") else secret.get("github_token_$login")
    }
    fun saveToken(token: String) { secret.put("github_token", token.trim()) }
    fun hasSavedToken(): Boolean = !activeToken().isNullOrBlank()
    fun activeAccount(): String = secret.get("github_active_login") ?: "الحساب القديم"
    fun accountNames(): List<String> = runCatching {
        val raw = secret.get("github_accounts") ?: "[]"
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).map { arr.optString(it) }
    }.getOrDefault(emptyList())
    fun selectAccount(login: String): Boolean {
        if (secret.get("github_token_$login").isNullOrBlank()) return false
        secret.put("github_active_login", login)
        return true
    }
    private fun saveAccount(login: String, token: String) {
        secret.put("github_token_$login", token.trim())
        val names = (accountNames() + login).distinct()
        secret.put("github_accounts", org.json.JSONArray(names).toString())
        secret.put("github_active_login", login)
    }
    fun clearToken() { secret.put("github_token", ""); secret.put("github_active_login", "") }

    private fun request(path: String, token: String): Request = Request.Builder()
        .url("https://api.github.com$path")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .build()

    fun verify(token: String, callback: (String) -> Unit) {
        if (token.isBlank()) { callback("أدخل رمز GitHub أولًا"); return }
        executor.execute {
            val result = runCatching {
                http.newCall(request("/user", token)).execute().use { response ->
                    if (!response.isSuccessful) error("فشل التحقق: HTTP ${response.code}")
                    val login = JSONObject(response.body?.string().orEmpty()).optString("login", "GitHub")
                    saveAccount(login, token)
                    "متصل بنجاح: $login — تم حفظ الحساب مشفرًا"
                }
            }.getOrElse { it.message ?: "تعذر الاتصال بـ GitHub" }
            main.post { callback(result) }
        }
    }

    /** Creates a private repository automatically from the selected Android project's folder name. */
    fun createRepositoryForProject(projectDir: File, callback: (Result<Repository>) -> Unit) {
        val token = activeToken()
        if (token.isNullOrBlank()) {
            callback(Result.failure(IllegalStateException("لا يوجد رمز GitHub محفوظ")))
            return
        }
        executor.execute {
            val result = runCatching {
                check(projectDir.isDirectory) { "مجلد المشروع غير موجود" }
                val settingsFile = File(projectDir, "settings.gradle.kts").takeIf { it.isFile }
                    ?: File(projectDir, "settings.gradle").takeIf { it.isFile }
                val declaredProjectName = settingsFile?.readText(Charsets.UTF_8)?.let {
                    Regex("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']").find(it)?.groupValues?.get(1)
                }
                val baseName = (declaredProjectName ?: projectDir.name).lowercase()
                    .replace(Regex("[^a-z0-9._-]+"), "-")
                    .trim('-', '.', '_')
                    .take(72)
                    .ifBlank { "mdstudio-android-app" }

                fun create(name: String): Repository {
                    val body = JSONObject()
                        .put("name", name)
                        .put("description", "Android project created and built by MD Studio / AMD Studio")
                        .put("private", true)
                        .put("auto_init", true)
                    val req = Request.Builder()
                        .url("https://api.github.com/user/repos")
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    return http.newCall(req).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            error("تعذر إنشاء مستودع GitHub (HTTP ${response.code}): ${raw.take(450)}")
                        }
                        val json = JSONObject(raw)
                        val owner = json.getJSONObject("owner").getString("login")
                        Repository(
                            fullName = json.optString("full_name", "$owner/$name"),
                            owner = owner,
                            name = json.optString("name", name),
                            defaultBranch = json.optString("default_branch", "main"),
                            isPrivate = json.optBoolean("private", true)
                        )
                    }
                }

                // Try a clean project-derived name first; on a name collision, add a unique suffix.
                try {
                    create(baseName)
                } catch (firstError: Throwable) {
                    if (firstError.message?.contains("HTTP 422") != true) throw firstError
                    val uniqueName = "${baseName.take(58)}-${System.currentTimeMillis().toString().takeLast(8)}"
                    try {
                        create(uniqueName)
                    } catch (secondError: Throwable) {
                        throw IllegalStateException(
                            "فشل إنشاء المستودع بالاسم '$baseName' ثم '$uniqueName'. ${secondError.message ?: firstError.message}",
                            secondError
                        )
                    }
                }
            }
            main.post { callback(result) }
        }
    }

    fun listRepositories(callback: (Result<List<Repository>>) -> Unit) {
        val token = activeToken()
        if (token.isNullOrBlank()) { callback(Result.failure(IllegalStateException("لا يوجد رمز GitHub محفوظ"))); return }
        executor.execute {
            val result = runCatching {
                val repos = mutableListOf<Repository>()
                var page = 1
                while (page <= 5) {
                    val req = request("/user/repos?per_page=100&sort=updated&page=$page", token)
                    val raw = http.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) error("GitHub HTTP ${response.code}")
                        response.body?.string().orEmpty()
                    }
                    val array = org.json.JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val owner = item.getJSONObject("owner").optString("login")
                        val name = item.optString("name")
                        repos += Repository(item.optString("full_name"), owner, name,
                            item.optString("default_branch", "main"), item.optBoolean("private"))
                    }
                    if (array.length() < 100) break
                    page++
                }
                repos
            }
            main.post { callback(result) }
        }
    }

    /** Publishes a bounded project snapshot to a fresh branch without overwriting the default branch. */
    fun publishProject(owner: String, repo: String, projectDir: File, baseBranch: String,
                        callback: (Result<String>) -> Unit) {
        val token = activeToken()
        if (token.isNullOrBlank()) { callback(Result.failure(IllegalStateException("لا يوجد رمز GitHub محفوظ"))); return }
        executor.execute {
            val result = runCatching {
                check(projectDir.isDirectory) { "مجلد المشروع غير موجود" }
                check(File(projectDir, "app/build.gradle.kts").isFile || File(projectDir, "app/build.gradle").isFile) {
                    "البناء السحابي التلقائي يتطلب وحدة Android باسم app داخل المشروع"
                }
                val excludedDirs = setOf(".git", ".gradle", "build", ".idea", "legacy-reference")
                val files = projectDir.walkTopDown()
                    .onEnter { dir -> dir == projectDir || dir.name !in excludedDirs }
                    .filter { it.isFile }
                    .filter { file ->
                        val rel = file.relativeTo(projectDir).invariantSeparatorsPath
                        val lower = file.name.lowercase()
                        !rel.startsWith(".github/workflows/.cache/") &&
                            rel != ".github/workflows/mdstudio-build.yml" &&
                            lower !in setOf("local.properties", "secrets.properties", "google-services.json", ".npmrc") &&
                            !lower.startsWith(".env") && !lower.startsWith("service-account") &&
                            !lower.endsWith(".jks") && !lower.endsWith(".keystore") && !lower.endsWith(".p12") &&
                            !lower.endsWith(".pfx") && !lower.endsWith(".pem") && !lower.endsWith(".apk") &&
                            !lower.endsWith(".aab") && !lower.endsWith(".gguf") && !lower.endsWith(".env")
                    }.toList()
                check(files.isNotEmpty()) { "لا توجد ملفات مصدر قابلة للرفع" }
                check(files.size <= 600) { "المشروع يحتوي على أكثر من 600 ملف؛ قسّمه قبل الرفع" }
                val totalBytes = files.sumOf { it.length() }
                check(totalBytes <= 50L * 1024 * 1024) { "حجم ملفات المشروع القابلة للرفع تجاوز 50 MB" }

                fun apiJson(path: String): JSONObject {
                    val req = request(path, token)
                    return http.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) error("GitHub HTTP ${response.code} عند قراءة $path")
                        JSONObject(response.body?.string().orEmpty())
                    }
                }
                fun postJson(path: String, body: JSONObject): JSONObject {
                    val req = Request.Builder().url("https://api.github.com$path")
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    return http.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) error("GitHub HTTP ${response.code} عند رفع المشروع: ${response.body?.string()?.take(500)}")
                        JSONObject(response.body?.string().orEmpty())
                    }
                }

                fun patchJson(path: String, body: JSONObject): JSONObject {
                    val req = Request.Builder().url("https://api.github.com$path")
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .patch(body.toString().toRequestBody("application/json".toMediaType())).build()
                    return http.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) error("GitHub HTTP ${response.code} عند تحديث الفرع الافتراضي: ${response.body?.string()?.take(500)}")
                        JSONObject(response.body?.string().orEmpty())
                    }
                }

                val encodedBase = java.net.URLEncoder.encode(baseBranch, "UTF-8").replace("+", "%20")
                val baseRef = apiJson("/repos/$owner/$repo/git/ref/heads/$encodedBase")
                var parentSha = baseRef.getJSONObject("object").getString("sha")
                val entries = org.json.JSONArray()
                files.forEach { file ->
                    val rel = file.relativeTo(projectDir).invariantSeparatorsPath
                    val bytes = file.readBytes()
                    val blob = postJson("/repos/$owner/$repo/git/blobs", JSONObject()
                        .put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                        .put("encoding", "base64"))
                    entries.put(JSONObject()
                        .put("path", rel)
                        .put("mode", if (file.name == "gradlew" || file.extension == "sh") "100755" else "100644")
                        .put("type", "blob")
                        .put("sha", blob.getString("sha")))
                }
                val workflowPath = ".github/workflows/mdstudio-build.yml"
                val workflowText = """name: MD Studio Android APK Build

on:
  workflow_dispatch:
    inputs:
      build_variant:
        description: APK build variant
        required: true
        type: choice
        options: [debug, release]
        default: debug
      run_tests:
        description: Run unit tests before packaging
        required: true
        type: boolean
        default: true
  push:
    branches: [ main ]
permissions:
  contents: read
  actions: read
concurrency:
  group: mdstudio-apk-${'$'}{{ github.ref }}
  cancel-in-progress: false
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 50
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.13'
      - name: Install Android SDK packages
        run: sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
      - name: Validate Android project
        run: |
          test -f settings.gradle || test -f settings.gradle.kts
          test -f app/build.gradle || test -f app/build.gradle.kts
          test -f app/src/main/AndroidManifest.xml
      - name: Run unit tests
        if: github.event_name == 'push' || inputs.run_tests
        run: gradle --no-daemon --stacktrace testDebugUnitTest
      - name: Prepare optional release signing identity
        if: github.event_name == 'workflow_dispatch' && inputs.build_variant == 'release'
        env:
          KEYSTORE_BASE64: ${'$'}{{ secrets.MDSTUDIO_KEYSTORE_BASE64 }}
          KEYSTORE_PASSWORD: ${'$'}{{ secrets.MDSTUDIO_KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${'$'}{{ secrets.MDSTUDIO_KEY_ALIAS }}
          KEY_PASSWORD: ${'$'}{{ secrets.MDSTUDIO_KEY_PASSWORD }}
        run: |
          if [ -n "${'$'}KEYSTORE_BASE64" ]; then
            echo "${'$'}KEYSTORE_BASE64" | base64 --decode > "${'$'}RUNNER_TEMP/mdstudio-release.jks"
            echo "MDSTUDIO_KEYSTORE_PATH=${'$'}RUNNER_TEMP/mdstudio-release.jks" >> "${'$'}GITHUB_ENV"
            echo "MDSTUDIO_KEYSTORE_PASSWORD=${'$'}KEYSTORE_PASSWORD" >> "${'$'}GITHUB_ENV"
            echo "MDSTUDIO_KEY_ALIAS=${'$'}KEY_ALIAS" >> "${'$'}GITHUB_ENV"
            echo "MDSTUDIO_KEY_PASSWORD=${'$'}KEY_PASSWORD" >> "${'$'}GITHUB_ENV"
          fi
      - name: Build APK
        env:
          BUILD_VARIANT: ${'$'}{{ github.event.inputs.build_variant || 'debug' }}
        run: |
          if [ "${'$'}BUILD_VARIANT" = "release" ]; then
            gradle --no-daemon --stacktrace :app:assembleRelease
            if [ -s app/build/outputs/apk/release/app-release.apk ]; then
              APK_PATH=app/build/outputs/apk/release/app-release.apk
              ARTIFACT_NAME=MD-Studio-release-apk
            else
              APK_PATH=app/build/outputs/apk/release/app-release-unsigned.apk
              ARTIFACT_NAME=MD-Studio-release-unsigned-apk
            fi
          else
            gradle --no-daemon --stacktrace :app:assembleDebug
            APK_PATH=app/build/outputs/apk/debug/app-debug.apk
            ARTIFACT_NAME=MD-Studio-debug-apk
          fi
          test -s "${'$'}APK_PATH"
          echo "APK_PATH=${'$'}APK_PATH" >> "${'$'}GITHUB_ENV"
          echo "ARTIFACT_NAME=${'$'}ARTIFACT_NAME" >> "${'$'}GITHUB_ENV"
      - name: Verify APK
        run: |
          "${'$'}ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "${'$'}APK_PATH" | head -n 5
          if [[ "${'$'}APK_PATH" != *"unsigned.apk" ]]; then
            "${'$'}ANDROID_HOME/build-tools/35.0.0/apksigner" verify "${'$'}APK_PATH"
          fi
      - uses: actions/upload-artifact@v4
        with:
          name: ${'$'}{{ env.ARTIFACT_NAME }}
          path: ${'$'}{{ env.APK_PATH }}
          if-no-files-found: error
          retention-days: 14
      - name: Upload unit test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: MD-Studio-unit-test-reports
          path: '**/build/reports/tests/'
          if-no-files-found: ignore
          retention-days: 7
""".trimIndent()
                val workflowBlobForBranch = postJson("/repos/$owner/$repo/git/blobs", JSONObject()
                    .put("content", android.util.Base64.encodeToString(workflowText.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                    .put("encoding", "base64"))
                entries.put(JSONObject().put("path", workflowPath).put("mode", "100644").put("type", "blob").put("sha", workflowBlobForBranch.getString("sha")))
                // workflow_dispatch must exist on the default branch; install or upgrade it to the current MD Studio APK workflow when needed.
                val workflowExistsOnDefault = http.newCall(request(
                    "/repos/$owner/$repo/contents/$workflowPath?ref=$encodedBase", token
                )).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val content = JSONObject(response.body?.string().orEmpty()).optString("content")
                            val decoded = runCatching { String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
                            decoded.contains("MD Studio Android APK Build") && decoded.contains("workflow_dispatch") && decoded.contains("build_variant") && decoded.contains("ARTIFACT_NAME") && decoded.contains("build-tools;35.0.0")
                        }
                        404 -> false
                        else -> error("تعذر التحقق من Workflow على الفرع الافتراضي: HTTP ${response.code}")
                    }
                }
                if (!workflowExistsOnDefault) {
                    val baseCommit = apiJson("/repos/$owner/$repo/git/commits/$parentSha")
                    val baseTreeSha = baseCommit.getJSONObject("tree").getString("sha")
                    val workflowBlob = postJson("/repos/$owner/$repo/git/blobs", JSONObject()
                        .put("content", android.util.Base64.encodeToString(workflowText.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                        .put("encoding", "base64"))
                    val defaultTreeEntries = org.json.JSONArray().put(JSONObject()
                        .put("path", workflowPath).put("mode", "100644").put("type", "blob").put("sha", workflowBlob.getString("sha")))
                    val updatedTree = postJson("/repos/$owner/$repo/git/trees", JSONObject()
                        .put("base_tree", baseTreeSha).put("tree", defaultTreeEntries))
                    val workflowCommit = postJson("/repos/$owner/$repo/git/commits", JSONObject()
                        .put("message", "MD Studio: add Android build workflow")
                        .put("tree", updatedTree.getString("sha"))
                        .put("parents", org.json.JSONArray().put(parentSha)))
                    patchJson("/repos/$owner/$repo/git/refs/heads/$encodedBase", JSONObject()
                        .put("sha", workflowCommit.getString("sha")).put("force", false))
                    parentSha = workflowCommit.getString("sha")
                }
                // Only the build workflow may be added to the default branch; project source stays on the new branch.
                val tree = postJson("/repos/$owner/$repo/git/trees", JSONObject().put("tree", entries))
                val branch = "mdstudio/build-${System.currentTimeMillis()}"
                val commit = postJson("/repos/$owner/$repo/git/commits", JSONObject()
                    .put("message", "MD Studio: publish Android project snapshot")
                    .put("tree", tree.getString("sha"))
                    .put("parents", org.json.JSONArray().put(parentSha)))
                postJson("/repos/$owner/$repo/git/refs", JSONObject()
                    .put("ref", "refs/heads/$branch")
                    .put("sha", commit.getString("sha")))
                branch
            }
            main.post { callback(result) }
        }
    }

    fun dispatchWorkflow(owner: String, repo: String, workflowFile: String, branch: String = "main", inputs: Map<String, String> = emptyMap(), callback: (Result<Unit>) -> Unit) {
        val token = activeToken()
        if (token.isNullOrBlank()) { callback(Result.failure(IllegalStateException("لا يوجد رمز GitHub محفوظ"))); return }
        executor.execute {
            val result = runCatching {
                val inputJson = JSONObject()
                inputs.forEach { (key, value) -> inputJson.put(key, value) }
                val body = JSONObject().put("ref", branch).put("inputs", inputJson).toString()
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowFile/dispatches")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { response ->
                    if (response.code != 204) error("تعذر تشغيل Workflow: HTTP ${response.code}. تحقق من صلاحية Actions: write واسم الملف والفرع.")
                }
            }
            main.post { callback(result) }
        }
    }

    fun listArtifacts(owner: String, repo: String, runId: Long, callback: (Result<List<Artifact>>) -> Unit) {
        val token = activeToken()
        if (token.isNullOrBlank()) { callback(Result.failure(IllegalStateException("لا يوجد رمز GitHub محفوظ"))); return }
        executor.execute {
            val result = runCatching {
                val req = request("/repos/$owner/$repo/actions/runs/$runId/artifacts?per_page=100", token)
                val raw = http.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) error("تعذر قراءة ملفات البناء: HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                val arr = JSONObject(raw).optJSONArray("artifacts") ?: org.json.JSONArray()
                (0 until arr.length()).map { i ->
                    val item = arr.getJSONObject(i)
                    Artifact(item.optLong("id"), item.optString("name"), item.optLong("size_in_bytes"),
                        item.optBoolean("expired"), item.optString("archive_download_url"))
                }
            }
            main.post { callback(result) }
        }
    }

    fun downloadRepositoryZip(owner: String, repo: String, branch: String, destinationDir: File,
                             callback: (Result<File>) -> Unit) {
        val token = activeToken()
        if (token.isNullOrBlank()) { callback(Result.failure(IllegalStateException("لا يوجد رمز GitHub محفوظ"))); return }
        executor.execute {
            val result = runCatching {
                destinationDir.mkdirs()
                val encodedBranch = java.net.URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
                val req = request("/repos/$owner/$repo/zipball/$encodedBranch", token)
                val zipFile = File(destinationDir, "${owner}_${repo}_${System.currentTimeMillis()}.zip")
                var total = 0L
                http.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) error("تعذر تنزيل المستودع: HTTP ${response.code}")
                    val body = response.body ?: error("استجابة GitHub فارغة")
                    body.byteStream().use { input -> zipFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 1024L * 1024 * 1024) { "ملف المستودع تجاوز 1 GB" }
                            output.write(buffer, 0, count)
                        }
                    } }
                }
                check(zipFile.isFile && zipFile.length() > 0) { "ملف المستودع فارغ" }
                zipFile
            }
            main.post { callback(result) }
        }
    }

    /** Downloads an Actions artifact ZIP and extracts the first APK into app-private storage. */
    fun downloadArtifactApk(owner: String, repo: String, artifactId: Long, destinationDir: File,
                            callback: (Result<File>) -> Unit) {
        val token = activeToken()
        if (token.isNullOrBlank()) { callback(Result.failure(IllegalStateException("لا يوجد رمز GitHub محفوظ"))); return }
        executor.execute {
            val result = runCatching {
                destinationDir.mkdirs()
                val req = request("/repos/$owner/$repo/actions/artifacts/$artifactId/zip", token)
                val zipFile = File(destinationDir, "artifact-$artifactId.zip")
                http.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) error("فشل تنزيل Artifact: HTTP ${response.code}")
                    val body = response.body ?: error("استجابة GitHub فارغة")
                    body.byteStream().use { input -> zipFile.outputStream().buffered().use { output -> input.copyTo(output) } }
                }
                val apkFile = File(destinationDir, "github-artifact-$artifactId.apk")
                var found = false
                java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                            apkFile.outputStream().buffered().use { out -> zip.copyTo(out) }
                            found = true
                            break
                        }
                        zip.closeEntry()
                    }
                }
                zipFile.delete()
                check(found && apkFile.isFile && apkFile.length() > 0) { "تم تنزيل Artifact لكن لم نعثر على ملف APK بداخله" }
                apkFile
            }
            main.post { callback(result) }
        }
    }

    fun latestWorkflowRuns(owner: String, repo: String, workflowFile: String, callback: (Result<List<WorkflowRun>>) -> Unit) {
        val token = activeToken()
        if (token.isNullOrBlank()) { callback(Result.failure(IllegalStateException("لا يوجد رمز GitHub محفوظ"))); return }
        executor.execute {
            val result = runCatching {
                val req = request("/repos/$owner/$repo/actions/workflows/$workflowFile/runs?per_page=10", token)
                val raw = http.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) error("تعذر قراءة تشغيلات Workflow: HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                val arr = JSONObject(raw).optJSONArray("workflow_runs") ?: org.json.JSONArray()
                (0 until arr.length()).map { i ->
                    val item = arr.getJSONObject(i)
                    WorkflowRun(item.optLong("id"), item.optString("name", "Android Build"),
                        item.optString("status", "unknown"), item.optString("conclusion").takeIf { it != "null" && it.isNotBlank() },
                        item.optString("html_url"), item.optString("head_branch"))
                }
            }
            main.post { callback(result) }
        }
    }
}
