package com.example.repolauncher.data

import android.graphics.drawable.Drawable
import kotlinx.serialization.Serializable

@Serializable
data class RepoConfig(
    val id: String = "",
    val repoFullName: String = "",
    val displayName: String = "",
    val apkAssetPattern: String = ".*\\.apk$",
    val preferStable: Boolean = true,
    val includePrereleases: Boolean = false,
    val installedVersion: String = "",
    val note: String = ""
)

@Serializable
data class GitHubRelease(
    val tag_name: String = "",
    val name: String = "",
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
    val body: String = ""
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
    val content_type: String = ""
)

@Serializable
data class GitHubReleaseResponse(
    val tag_name: String = "",
    val name: String = "",
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
    val body: String = ""
)

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?
)

// Lawnchair backup model
data class BackupInfo(
    val version: Int = 0,
    val lawnchairVersion: Long = 0,
    val createdAt: Long = 0,
    val hasWallpaper: Boolean = false,
    val hasScreenshot: Boolean = false
)

sealed class InstallState {
    data object Idle : InstallState()
    data class Downloading(val progress: Float) : InstallState()
    data class Installing(val appName: String) : InstallState()
    data class Success(val appName: String) : InstallState()
    data class Error(val appName: String, val message: String) : InstallState()
}

sealed class FetchState {
    data object Idle : FetchState()
    data object Loading : FetchState()
    data class Success(val releases: List<GitHubRelease>) : FetchState()
    data class Error(val message: String) : FetchState()
}
