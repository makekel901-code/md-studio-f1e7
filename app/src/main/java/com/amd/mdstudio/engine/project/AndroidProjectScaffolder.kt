package com.amd.mdstudio.engine.project

import com.amd.mdstudio.engine.core.EngineResult
import com.amd.mdstudio.engine.core.EngineTool
import org.json.JSONObject
import java.io.File

/** Creates a real minimal Kotlin/Compose Android project that can be built locally or on GitHub Actions. */
class AndroidProjectScaffolder(private val projectsRoot: File) {
    fun create(name: String, packageName: String): EngineResult = runCatching {
        val safeName = name.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(48)
        require(safeName.isNotBlank()) { "اسم التطبيق مطلوب" }
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){1,5}$").matches(packageName)) {
            "اسم الحزمة غير صالح؛ مثال: com.example.myapp"
        }
        projectsRoot.mkdirs()
        val slug = safeName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "android-app" }
        val root = File(projectsRoot, "$slug-${System.currentTimeMillis()}")
        check(root.mkdirs()) { "تعذر إنشاء مجلد المشروع" }
        fun put(path: String, content: String) {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            file.writeText(content.trimIndent() + "\n", Charsets.UTF_8)
        }
        val packagePath = packageName.replace('.', '/')
        put("settings.gradle.kts", """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "$safeName"
            include(":app")
        """)
        put("build.gradle.kts", """
            plugins {
                id("com.android.application") version "8.11.1" apply false
                id("org.jetbrains.kotlin.android") version "2.2.20" apply false
                id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
            }
        """)
        put("gradle.properties", """
            org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
            android.useAndroidX=true
            kotlin.code.style=official
            android.nonTransitiveRClass=true
        """)
        put("app/build.gradle.kts", """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
                id("org.jetbrains.kotlin.plugin.compose")
            }
            val releaseStorePath = System.getenv("MDSTUDIO_KEYSTORE_PATH")
            val releaseStorePassword = System.getenv("MDSTUDIO_KEYSTORE_PASSWORD")
            val releaseKeyAlias = System.getenv("MDSTUDIO_KEY_ALIAS")
            val releaseKeyPassword = System.getenv("MDSTUDIO_KEY_PASSWORD")
            android {
                namespace = "$packageName"
                compileSdk = 35
                defaultConfig {
                    applicationId = "$packageName"
                    minSdk = 23
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0.0"
                }
                signingConfigs {
                    create("release") {
                        if (!releaseStorePath.isNullOrBlank()) {
                            storeFile = file(releaseStorePath)
                            storePassword = releaseStorePassword
                            keyAlias = releaseKeyAlias
                            keyPassword = releaseKeyPassword
                        }
                    }
                }
                buildTypes {
                    getByName("release") {
                        isMinifyEnabled = false
                        if (!releaseStorePath.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
                    }
                }
                buildFeatures { compose = true }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.16.0")
                implementation("androidx.activity:activity-compose:1.10.1")
                implementation(platform("androidx.compose:compose-bom:2025.10.00"))
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.ui:ui-tooling-preview")
                implementation("androidx.compose.material3:material3")
            }
        """)
        put("app/src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="$safeName" android:theme="@android:style/Theme.Material.Light.NoActionBar" android:supportsRtl="true">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """)
        put("app/src/main/java/$packagePath/MainActivity.kt", """
            package $packageName

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.*
            import androidx.compose.material3.*
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MaterialTheme {
                            Surface(Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("$safeName", style = MaterialTheme.typography.headlineMedium)
                                    Spacer(Modifier.height(12.dp))
                                    Text("تم إنشاء المشروع بواسطة MD Studio")
                                }
                            }
                        }
                    }
                }
            }
        """)
        put("README.md", """
            # $safeName
            Generated by MD Studio.

            - Package: `$packageName`
            - Build: `gradle :app:assembleDebug`
            - Cloud build: MD Studio uploads this project to GitHub Actions and downloads the resulting APK.
        """)
        EngineResult(true, "تم إنشاء مشروع Android فعلي قابل للاستكمال", JSONObject()
            .put("project_path", root.absolutePath)
            .put("project_name", safeName)
            .put("package_name", packageName)
            .put("files_created", 7))
    }.getOrElse { EngineResult(false, it.message ?: "فشل إنشاء المشروع") }

    fun tool(): EngineTool = object : EngineTool {
        override val name = "project.create-android"
        override val description = "إنشاء مشروع Android Kotlin/Compose قابل للبناء"
        override fun execute(arguments: JSONObject) = create(
            arguments.optString("name"), arguments.optString("package_name")
        )
    }
}
