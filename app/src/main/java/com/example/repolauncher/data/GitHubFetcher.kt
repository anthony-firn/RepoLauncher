package com.example.repolauncher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class GitHubFetcher {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchReleases(repoFullName: String, includePrereleases: Boolean = false): Result<List<GitHubRelease>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/repos/$repoFullName/releases?per_page=20"
                val request = Request.Builder().url(url)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "RepoLauncher/1.0").build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext Result.failure(Exception("GitHub API error: ${response.code}"))
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
                val releases = json.decodeFromString<List<GitHubReleaseResponse>>(body)
                val filtered = releases.filter { release ->
                    if (includePrereleases) true else !release.prerelease
                }.map {
                    GitHubRelease(it.tag_name, it.name, it.prerelease, it.assets, it.body)
                }
                Result.success(filtered)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    suspend fun downloadApk(downloadUrl: String, destFile: File, onProgress: ((Float) -> Unit)? = null): Result<File> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(downloadUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "RepoLauncher/1.0").build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext Result.failure(Exception("Download failed: ${response.code}"))
                val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
                val contentLength = body.contentLength()
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(8192)
                    var total: Long = 0
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        total += read
                        if (contentLength > 0) onProgress?.invoke(total.toFloat() / contentLength)
                    }
                }
                Result.success(destFile)
            } catch (e: Exception) { Result.failure(e) }
        }
    }
}
