package com.example.repolauncher.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "repo_prefs")

@Serializable
data class LauncherSettings(
    val gridColumns: Int = 4,
    val gridRows: Int = 5,
    val dockIconCount: Int = 5,
    val iconSizePx: Int = 56,
    val showAppLabels: Boolean = true,
    val showSearchBar: Boolean = true
)

class RepoManager(private val context: Context) {

    companion object {
        private val REPOS_KEY = stringPreferencesKey("saved_repos")
        private val GRID_COLUMNS_KEY = intPreferencesKey("grid_columns")
        private val GRID_ROWS_KEY = intPreferencesKey("grid_rows")
        private val DOCK_ICON_COUNT_KEY = intPreferencesKey("dock_icon_count")
        private val ICON_SIZE_KEY = intPreferencesKey("icon_size")
        private val SHOW_LABELS_KEY = intPreferencesKey("show_labels")
        private val SHOW_SEARCH_KEY = intPreferencesKey("show_search")
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val reposFlow: Flow<List<RepoConfig>> = context.dataStore.data.map { prefs ->
        val raw = prefs[REPOS_KEY] ?: "[]"
        try { json.decodeFromString<List<RepoConfig>>(raw) } catch (e: Exception) { emptyList() }
    }

    val settingsFlow: Flow<LauncherSettings> = context.dataStore.data.map { prefs ->
        LauncherSettings(
            gridColumns = prefs[GRID_COLUMNS_KEY] ?: 4,
            gridRows = prefs[GRID_ROWS_KEY] ?: 5,
            dockIconCount = prefs[DOCK_ICON_COUNT_KEY] ?: 5,
            iconSizePx = prefs[ICON_SIZE_KEY] ?: 56,
            showAppLabels = (prefs[SHOW_LABELS_KEY] ?: 1) == 1,
            showSearchBar = (prefs[SHOW_SEARCH_KEY] ?: 1) == 1
        )
    }

    val launcherBackupPathFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[stringPreferencesKey("launcher_backup_path")]
    }

    suspend fun addRepo(repo: RepoConfig) {
        context.dataStore.edit { prefs ->
            val raw = prefs[REPOS_KEY] ?: "[]"
            val list = try { json.decodeFromString<List<RepoConfig>>(raw).toMutableList() } catch (e: Exception) { mutableListOf() }
            if (list.none { it.id == repo.id }) list.add(repo)
            prefs[REPOS_KEY] = json.encodeToString(list)
        }
    }

    suspend fun updateRepo(repo: RepoConfig) {
        context.dataStore.edit { prefs ->
            val raw = prefs[REPOS_KEY] ?: "[]"
            val list = try { json.decodeFromString<List<RepoConfig>>(raw).toMutableList() } catch (e: Exception) { mutableListOf() }
            val index = list.indexOfFirst { it.id == repo.id }
            if (index >= 0) list[index] = repo
            prefs[REPOS_KEY] = json.encodeToString(list)
        }
    }

    suspend fun removeRepo(repoId: String) {
        context.dataStore.edit { prefs ->
            val raw = prefs[REPOS_KEY] ?: "[]"
            val list = try { json.decodeFromString<List<RepoConfig>>(raw).toMutableList() } catch (e: Exception) { mutableListOf() }
            list.removeAll { it.id == repoId }
            prefs[REPOS_KEY] = json.encodeToString(list)
        }
    }

    suspend fun saveLauncherBackupPath(path: String) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("launcher_backup_path")] = path
        }
    }

    // Persist home screen layout (workspace + dock)
    val homeLayoutFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[stringPreferencesKey("home_layout")]
    }

    suspend fun saveHomeLayout(layoutJson: String) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("home_layout")] = layoutJson
        }
    }

    suspend fun clearHomeLayout() {
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("home_layout"))
        }
    }

    suspend fun saveSettings(settings: LauncherSettings) {
        context.dataStore.edit { prefs ->
            prefs[GRID_COLUMNS_KEY] = settings.gridColumns
            prefs[GRID_ROWS_KEY] = settings.gridRows
            prefs[DOCK_ICON_COUNT_KEY] = settings.dockIconCount
            prefs[ICON_SIZE_KEY] = settings.iconSizePx
            prefs[SHOW_LABELS_KEY] = if (settings.showAppLabels) 1 else 0
            prefs[SHOW_SEARCH_KEY] = if (settings.showSearchBar) 1 else 0
        }
    }
}
