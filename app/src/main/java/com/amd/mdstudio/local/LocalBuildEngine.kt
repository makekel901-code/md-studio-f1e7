package com.amd.mdstudio.local

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Local Gradle runner. A toolchain ZIP must contain binaries built for Android's
 * runtime/ABI (or an explicitly supported Android shell environment). Desktop
 * Linux JDK/Gradle binaries are not assumed to run inside an ordinary APK.
 */
class LocalBuildEngine(private val context: Context) {
    private val root = File(context.filesDir, "mdstudio-toolchain")
    private val outputRoot = File(context.filesDir, "builds/local").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    enum class Variant(val task: String, val outputRelative: String) {
        DEBUG(":app:assembleDebug", "app/build/outputs/apk/debug/app-debug.apk"),
        RELEASE(":app:assembleRelease", "app/build/outputs/apk/release/app-release-unsigned.apk")
    }

    data class BuildOutput(val apk: File, val log: String)

    data class ToolchainStatus(
        val java: Boolean,
        val gradle: Boolean,
        val androidSdk: Boolean,
        val aapt2: Boolean,
        val javaRuntime: String,
        val gradleRuntime: String,
        val sdkPlatform: String?,
        val buildToolsVersion: String?,
        val rootPath: String
    ) {
        val ready: Boolean get() = java && gradle && androidSdk && aapt2
        fun summary(): String = buildString {
            append("JDK=$java; Gradle=$gradle; Android SDK=$androidSdk; AAPT2=$aapt2")
            append("\nJava: $javaRuntime\nGradle: $gradleRuntime")
            append("\nSDK: ${sdkPlatform ?: "غير موجود"}; Build Tools: ${buildToolsVersion ?: "غير موجود"}")
            append("\nالمسار: $rootPath")
        }
    }

    private fun javaFile(): File? = sequenceOf(File(root, "jdk/bin/java"), File(root, "bin/java"))
        .firstOrNull { it.isFile && it.canExecute() }

    private fun gradleFile(): File? = sequenceOf(File(root, "gradle/bin/gradle"), File(root, "bin/gradle"))
        .firstOrNull { it.isFile && it.canExecute() }

    private fun sdkPlatform(): File? = File(root, "android-sdk/platforms").listFiles()
        ?.filter { it.isDirectory && File(it, "android.jar").isFile }
        ?.maxByOrNull { it.name.removePrefix("android-").toIntOrNull() ?: 0 }

    private fun buildToolsDir(): File? = File(root, "android-sdk/build-tools").listFiles()
        ?.filter { it.isDirectory && File(it, "aapt2").isFile }
        ?.maxByOrNull { it.name }

    private fun probe(file: File?, vararg args: String): String {
        if (file == null) return "غير موجود"
        return runCatching {
            val process = ProcessBuilder(listOf(file.absolutePath) + args)
                .redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> synchronized(output) { if (output.length < 1200) output.append(line).append('\n') } }
                }
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly(); reader.join(500)
                "لم يستجب خلال 8 ثوانٍ (قد لا يكون متوافقًا مع Android)"
            } else {
                reader.join(1000)
                val text = synchronized(output) { output.toString() }
                if (process.exitValue() == 0) text.lineSequence().firstOrNull().orEmpty().ifBlank { "يعمل" }
                else "فشل التشغيل: ${text.take(180)}"
            }
        }.getOrElse { "تعذر التشغيل: ${it.javaClass.simpleName}" }
    }

    fun inspect(): ToolchainStatus {
        val java = javaFile()
        val gradle = gradleFile()
        val platform = sdkPlatform()
        val tools = buildToolsDir()
        val javaProbe = probe(java, "-version")
        val gradleProbe = probe(gradle, "--version")
        val aaptProbe = probe(tools?.let { File(it, "aapt2") }, "version")
        return ToolchainStatus(
            java = java != null && !javaProbe.startsWith("فشل") && !javaProbe.startsWith("تعذر") && !javaProbe.startsWith("لم يستجب"),
            gradle = gradle != null && !gradleProbe.startsWith("فشل") && !gradleProbe.startsWith("تعذر") && !gradleProbe.startsWith("لم يستجب"),
            androidSdk = platform != null,
            aapt2 = tools != null && !aaptProbe.startsWith("فشل") && !aaptProbe.startsWith("تعذر") && !aaptProbe.startsWith("لم يستجب"),
            javaRuntime = javaProbe,
            gradleRuntime = gradleProbe,
            sdkPlatform = platform?.name,
            buildToolsVersion = tools?.name,
            rootPath = root.absolutePath
        )
    }

    /** ZIP layout: jdk/bin/java, gradle/bin/gradle, android-sdk/platforms/android-XX/android.jar, build-tools/XX/aapt2. */
    fun installToolchain(input: InputStream): Result<String> = runCatching {
        val staging = File(context.cacheDir, "toolchain-staging").apply { deleteRecursively(); mkdirs() }
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(staging, entry.name).canonicalFile
                check(out.path.startsWith(staging.canonicalPath + File.separator) || out == staging.canonicalFile) {
                    "أرشيف أدوات البناء يحتوي على مسار غير آمن"
                }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 3L * 1024 * 1024 * 1024) { "حزمة أدوات البناء أكبر من الحد المسموح (3 GB)" }
                            sink.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val candidate = if (File(staging, "mdstudio-toolchain").isDirectory) File(staging, "mdstudio-toolchain") else staging
        val java = sequenceOf(File(candidate, "jdk/bin/java"), File(candidate, "bin/java"))
            .firstOrNull { it.isFile } ?: error("لم يتم العثور على JDK/bin/java")
        val gradle = sequenceOf(File(candidate, "gradle/bin/gradle"), File(candidate, "bin/gradle"))
            .firstOrNull { it.isFile } ?: error("لم يتم العثور على Gradle")
        check(File(candidate, "android-sdk/platforms").listFiles()?.any { File(it, "android.jar").isFile } == true) {
            "لم يتم العثور على Android SDK platform يحتوي android.jar"
        }
        check(File(candidate, "android-sdk/build-tools").listFiles()?.any { File(it, "aapt2").isFile } == true) {
            "لم يتم العثور على Android Build Tools/AAPT2"
        }
        java.setExecutable(true, true)
        gradle.setExecutable(true, true)
        candidate.walkTopDown().filter { it.isFile && (it.name.endsWith(".so") || it.name in setOf("aapt", "aapt2", "aidl", "apksigner", "zipalign", "d8", "kotlinc")) }
            .forEach { it.setExecutable(true, true) }
        root.deleteRecursively()
        check(candidate.copyRecursively(root, overwrite = true))
        sequenceOf(File(root, "jdk/bin/java"), File(root, "bin/java"),
            File(root, "gradle/bin/gradle"), File(root, "bin/gradle"))
            .filter { it.isFile }.forEach { it.setExecutable(true, true) }
        root.walkTopDown().filter { it.isFile && it.name in setOf("aapt", "aapt2", "aidl", "apksigner", "zipalign", "d8", "kotlinc") }
            .forEach { it.setExecutable(true, true) }
        staging.deleteRecursively()
        val status = inspect()
        check(status.ready) {
            "تم فك الحزمة لكن الأدوات لم تجتز اختبار التشغيل. يجب أن تكون الأدوات مبنية لتعمل على Android/معمارية الهاتف، وليس Linux Desktop.\n${status.summary()}"
        }
        "تم تثبيت أدوات البناء واجتازت اختبار التشغيل.\n${status.summary()}"
    }

    fun build(projectDir: File, variant: Variant = Variant.DEBUG, callback: (Result<BuildOutput>) -> Unit) {
        executor.execute {
            val result = runCatching {
                check(projectDir.isDirectory && (File(projectDir, "settings.gradle.kts").isFile || File(projectDir, "settings.gradle").isFile)) {
                    "مجلد المشروع غير صالح: لم يتم العثور على settings.gradle أو settings.gradle.kts"
                }
                val status = inspect()
                check(status.ready) {
                    "أدوات البناء المحلية غير مكتملة أو غير متوافقة مع Android.\n${status.summary()}\n\nعلى الهاتف يلزم JDK/Gradle/AAPT2 متوافقة مع Android وABI الجهاز؛ ملفات Linux العادية لا تكفي."
                }
                val gradle = gradleFile()!!
                val javaHome = javaFile()!!.parentFile.parentFile
                val sdk = File(root, "android-sdk")
                val command = mutableListOf(gradle.absolutePath, "--no-daemon", "--stacktrace", variant.task)
                val process = ProcessBuilder(command)
                    .directory(projectDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["JAVA_HOME"] = javaHome.absolutePath
                        environment()["ANDROID_HOME"] = sdk.absolutePath
                        environment()["ANDROID_SDK_ROOT"] = sdk.absolutePath
                        environment()["PATH"] = "${javaHome.absolutePath}/bin:${sdk.absolutePath}/platform-tools:" + environment()["PATH"].orEmpty()
                        environment()["TMPDIR"] = context.cacheDir.absolutePath
                        environment()["GRADLE_USER_HOME"] = File(context.filesDir, "gradle-home").absolutePath
                    }
                    .start()
                val output = StringBuilder()
                val reader = Thread {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) {
                                if (output.length < 250_000) output.append(line).append('\n')
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
                val finished = process.waitFor(45, TimeUnit.MINUTES)
                if (!finished) {
                    process.destroyForcibly()
                    error("تجاوز البناء المحلي الحد الزمني (45 دقيقة).\n${synchronized(output) { output.toString().takeLast(9000) }}")
                }
                reader.join(3000)
                val log = synchronized(output) { output.toString() }
                check(process.exitValue() == 0) { "فشل البناء المحلي (exit=${process.exitValue()}):\n${log.takeLast(12000)}" }
                val built = File(projectDir, variant.outputRelative)
                check(built.isFile && built.length() > 0) { "انتهى Gradle دون العثور على APK الناتج في ${built.absolutePath}" }
                val saved = File(outputRoot, "${projectDir.name}-${variant.name.lowercase()}-${System.currentTimeMillis()}.apk")
                built.copyTo(saved, overwrite = true)
                BuildOutput(saved, "نجح البناء ${variant.name}.\nالحجم: ${saved.length()} bytes\n\n${log.takeLast(5000)}")
            }
            main.post { callback(result) }
        }
    }
}
