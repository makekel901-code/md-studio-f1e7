package com.amd.mdstudio.engine.libraries

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Live Maven Central catalog adapter using the public Search API. */
class MavenCentralCatalogProvider : LibraryCatalogProvider {
    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun search(query: String): Result<List<LibraryEntry>> = runCatching {
        require(query.isNotBlank()) { "اكتب اسم المكتبة" }
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://search.maven.org/solrsearch/select?q=$q&rows=30&wt=json&sort=timestamp%20desc"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Maven Central HTTP ${response.code}" }
            val root = JSONObject(response.body?.string().orEmpty())
            val docs = root.optJSONObject("response")?.optJSONArray("docs")
                ?: org.json.JSONArray()
            (0 until docs.length()).mapNotNull { index ->
                val item = docs.optJSONObject(index) ?: return@mapNotNull null
                val group = item.optString("g")
                val artifact = item.optString("a")
                val version = item.optString("latestVersion")
                if (group.isBlank() || artifact.isBlank() || version.isBlank()) null else {
                    val coordinate = "$group:$artifact:$version"
                    LibraryEntry(
                        id = coordinate,
                        title = "$group:$artifact",
                        coordinate = coordinate,
                        description = "Maven Central • ${item.optInt("versionCount")} versions",
                        repository = "mavenCentral()"
                    )
                }
            }
        }
    }

    override fun details(id: String): Result<LibraryEntry> = runCatching {
        val parts = id.split(':')
        require(parts.size == 3) { "معرّف Maven غير صالح" }
        val (group, artifact, version) = parts
        LibraryEntry(id, "$group:$artifact", id, "اعتماد Maven Central: $id", "mavenCentral()")
    }
}
