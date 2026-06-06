package com.example.repolauncher

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.repolauncher.data.*
import com.example.repolauncher.service.LawnchairBackupImporter
import com.example.repolauncher.service.ShizukuInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application
    val repoManager = RepoManager(context)
    private val gitHubFetcher = GitHubFetcher()
    private val backupImporter = LawnchairBackupImporter(context)

    // All installed apps
    val installedApps = mutableStateListOf<AppInfo>()

    // Lawnchair backup imported apps (workspace)
    val workspaceApps = mutableStateListOf<LawnchairBackupImporter.ImportApp>()

    // Hotseat apps (from backup or auto-detected)
    val hotseatApps = mutableStateListOf<LawnchairBackupImporter.ImportApp>()

    // Workspace pages tracking
    var currentPage by mutableIntStateOf(0)

    // Saved repos
    private val _repos = MutableStateFlow<List<RepoConfig>>(emptyList())
    val repos: StateFlow<List<RepoConfig>> = _repos.asStateFlow()

    private val _repoFetchStates = MutableStateFlow<Map<String, FetchState>>(emptyMap())
    val repoFetchStates: StateFlow<Map<String, FetchState>> = _repoFetchStates.asStateFlow()

    private val _repoReleases = MutableStateFlow<Map<String, List<GitHubRelease>>>(emptyMap())
    val repoReleases: StateFlow<Map<String, List<GitHubRelease>>> = _repoReleases.asStateFlow()

    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val installStates: StateFlow<Map<String, InstallState>> = _installStates.asStateFlow()

    var selectedRepo by mutableStateOf(null as RepoConfig?)
        private set
    var showRepoDetail by mutableStateOf(false)
        private set
    var showAddRepoDialog by mutableStateOf(false)
    var showAppDrawer by mutableStateOf(false)
    var showBackupImportDialog by mutableStateOf(false)
    var showAppInfoDialog by mutableStateOf(false)
    var appInfoPackage by mutableStateOf(null as String?)
        private set
    var showSettingsDialog by mutableStateOf(false)
    var showCustomizeDialog by mutableStateOf(false)
    var settings by mutableStateOf(LauncherSettings())

    init {
        viewModelScope.launch {
            repoManager.settingsFlow.collect { settings = it }
        }
    }

    fun updateSettings(newSettings: LauncherSettings) {
        settings = newSettings
        viewModelScope.launch { repoManager.saveSettings(newSettings) }
    }

    fun launchCustomize() {
        showCustomizeDialog = true
    }

    fun launchSettings() {
        showSettingsDialog = true
    }

    fun showAppInfo(packageName: String) {
        appInfoPackage = packageName
        showAppInfoDialog = true
    }

    fun dismissAppInfo() {
        showAppInfoDialog = false
        appInfoPackage = null
    }

    // Search
    var searchQuery by mutableStateOf("")
    val filteredApps: List<AppInfo>
        get() {
            if (searchQuery.isBlank()) return installedApps
            return installedApps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
        }

    init {
        loadInstalledApps()
        observeRepos()
        loadHomeLayout()
    }

    private fun loadInstalledApps() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val activities = pm.queryIntentActivities(intent, 0)
        val apps = mutableListOf<AppInfo>()
        for (resolveInfo in activities) {
            try {
                val ai = resolveInfo.activityInfo
                apps.add(AppInfo(ai.packageName, ai.loadLabel(pm).toString(), ai.loadIcon(pm)))
            } catch (_: Exception) {}
        }
        apps.sortBy { it.appName }
        installedApps.clear()
        installedApps.addAll(apps)
    }

    private fun observeRepos() {
        viewModelScope.launch { repoManager.reposFlow.collect { _repos.value = it } }
    }

    // ── Backup import ──

    fun importLawnchairBackup(uri: Uri) {
        viewModelScope.launch {
            val result = backupImporter.importFromUri(uri)
            if (result.success) {
                workspaceApps.clear()
                hotseatApps.clear()
                result.apps.forEach { app ->
                    if (app.isInHotseat) hotseatApps.add(app)
                    else workspaceApps.add(app)
                }
                // Remember this backup path
                repoManager.saveLauncherBackupPath(uri.toString())
            }
        }
    }

    // ── Home screen persistence ──

    private val layoutJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @kotlinx.serialization.Serializable
    data class PersistedApp(val packageName: String, val displayName: String, val isHotseat: Boolean, val hotseatIndex: Int = -1)

    private fun persistHomeLayout() {
        viewModelScope.launch {
            val allApps = workspaceApps.map {
                PersistedApp(it.packageName, it.displayName, false)
            } + hotseatApps.mapIndexed { i, it ->
                PersistedApp(it.packageName, it.displayName, true, i)
            }
            repoManager.saveHomeLayout(layoutJson.encodeToString(allApps))
        }
    }

    fun loadHomeLayout() {
        viewModelScope.launch {
            val raw = repoManager.homeLayoutFlow.first() ?: return@launch
            try {
                val apps = layoutJson.decodeFromString<List<PersistedApp>>(raw)
                workspaceApps.clear()
                hotseatApps.clear()
                apps.forEach { app ->
                    val realApp = installedApps.find { it.packageName == app.packageName }
                    val name = realApp?.appName ?: app.displayName
                    val imp = LawnchairBackupImporter.ImportApp(app.packageName, name, isInHotseat = app.isHotseat, hotseatIndex = app.hotseatIndex)
                    if (app.isHotseat) hotseatApps.add(imp) else workspaceApps.add(imp)
                }
            } catch (_: Exception) {}
        }
    }

    // ── Move / Add / Remove apps from home screen ──

    fun removeFromWorkspace(packageName: String) {
        workspaceApps.removeAll { it.packageName == packageName }
        hotseatApps.removeAll { it.packageName == packageName }
        persistHomeLayout()
    }

    fun addAppToWorkspace(packageName: String) {
        if (workspaceApps.any { it.packageName == packageName }) return
        val app = installedApps.find { it.packageName == packageName } ?: return
        workspaceApps.add(LawnchairBackupImporter.ImportApp(packageName, app.appName, screen = 0))
        persistHomeLayout()
    }

    fun addAppToDock(packageName: String) {
        if (hotseatApps.any { it.packageName == packageName }) return
        if (hotseatApps.size >= 5) return // max 5 dock items
        val app = installedApps.find { it.packageName == packageName } ?: return
        hotseatApps.add(LawnchairBackupImporter.ImportApp(packageName, app.appName, isInHotseat = true, hotseatIndex = hotseatApps.size))
        persistHomeLayout()
    }

    fun moveAppToDock(packageName: String) {
        workspaceApps.removeAll { it.packageName == packageName }
        addAppToDock(packageName)
    }

    fun moveAppToWorkspace(packageName: String) {
        hotseatApps.removeAll { it.packageName == packageName }
        addAppToWorkspace(packageName)
    }

    // ── Repo management ──

    fun addRepo(repoFullName: String, displayName: String) {
        viewModelScope.launch {
            val id = repoFullName.lowercase().trim()
            repoManager.addRepo(RepoConfig(id, repoFullName.trim(), displayName.ifBlank { repoFullName.substringAfter("/") }))
        }
    }

    fun removeRepo(repoId: String) {
        viewModelScope.launch { repoManager.removeRepo(repoId) }
    }

    fun fetchReleases(repoId: String) {
        val repo = _repos.value.find { it.id == repoId } ?: return
        viewModelScope.launch {
            _repoFetchStates.value = _repoFetchStates.value + (repoId to FetchState.Loading)
            gitHubFetcher.fetchReleases(repo.repoFullName, repo.includePrereleases)
                .onSuccess { releases ->
                    _repoReleases.value = _repoReleases.value + (repoId to releases)
                    _repoFetchStates.value = _repoFetchStates.value + (repoId to FetchState.Success(releases))
                }.onFailure { e ->
                    _repoFetchStates.value = _repoFetchStates.value + (repoId to FetchState.Error(e.message ?: "Failed"))
                }
        }
    }

    fun downloadAndInstall(repoId: String, assetUrl: String, assetName: String) {
        val repo = _repos.value.find { it.id == repoId } ?: return
        viewModelScope.launch {
            _installStates.value = _installStates.value + (repoId to InstallState.Downloading(0f))
            val destFile = File(context.cacheDir, "downloads/$assetName")
            destFile.parentFile?.mkdirs()
            gitHubFetcher.downloadApk(assetUrl, destFile) { progress ->
                _installStates.value = _installStates.value + (repoId to InstallState.Downloading(progress))
            }.onSuccess { apk ->
                _installStates.value = _installStates.value + (repoId to InstallState.Installing(repo.displayName))
                ShizukuInstaller.installApk(apk).onSuccess {
                    _installStates.value = _installStates.value + (repoId to InstallState.Success(repo.displayName))
                    repoManager.updateRepo(repo.copy(installedVersion = assetName))
                }.onFailure { e ->
                    _installStates.value = _installStates.value + (repoId to InstallState.Error(repo.displayName, e.message ?: "Failed"))
                }
            }.onFailure { e ->
                _installStates.value = _installStates.value + (repoId to InstallState.Error(repo.displayName, e.message ?: "Download failed"))
            }
        }
    }

    fun launchApp(packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun openRepoDetail(repo: RepoConfig) {
        selectedRepo = repo
        showRepoDetail = true
        fetchReleases(repo.id)
    }

    fun closeRepoDetail() {
        showRepoDetail = false
        selectedRepo = null
    }

    fun clearInstallState(repoId: String) {
        val newMap = _installStates.value.toMutableMap()
        newMap.remove(repoId)
        _installStates.value = newMap
    }
}
