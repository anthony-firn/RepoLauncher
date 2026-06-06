package com.example.repolauncher.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Handles importing Lawnchair .lawnchairbackup files.
 * These are ZIP files containing:
 *   info.pb          - Protobuf metadata
 *   launcher.db      - SQLite database with workspace/favorites/hotseat
 *   preferences.xml  - SharedPreferences
 *   preferences      - Database preferences
 *   preferences.preferences_pb - DataStore preferences
 *   wallpaper.png    - Wallpaper image
 *   screenshot.png   - Preview screenshot
 */
class LawnchairBackupImporter(private val context: Context) {

    data class ImportResult(
        val success: Boolean,
        val appCount: Int = 0,
        val apps: List<ImportApp> = emptyList(),
        val error: String? = null
    )

    data class ImportApp(
        val packageName: String,
        val displayName: String,
        val screen: Int = 0,
        val screenX: Int = 0,
        val screenY: Int = 0,
        val isInHotseat: Boolean = false,
        val hotseatIndex: Int = -1
    )

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?:
                return@withContext ImportResult(false, error = "Cannot open file")

            pfd.use { fd ->
                val zipIs = ZipInputStream(FileInputStream(fd.fileDescriptor))
                val apps = mutableListOf<ImportApp>()
                var dbBytes: ByteArray? = null

                zipIs.use { zip ->
                    var entry: ZipEntry?
                    while (true) {
                        entry = zip.nextEntry ?: break
                        when {
                            entry.name == "launcher.db" -> {
                                dbBytes = zip.readBytes()
                            }
                            entry.name == "info.pb" -> {
                                // Parse if needed for metadata
                            }
                            entry.name == "wallpaper.png" -> {
                                // Could optionally set wallpaper
                            }
                        }
                    }
                }

                if (dbBytes == null) {
                    return@withContext ImportResult(false, error = "No launcher.db found in backup")
                }

                // Write DB to temp location and parse
                val tempDb = File(context.cacheDir, "backup_launcher.db")
                tempDb.parentFile?.mkdirs()
                tempDb.writeBytes(dbBytes!!)

                val parsedApps = parseLauncherDb(tempDb)
                tempDb.delete()

                ImportResult(
                    success = true,
                    appCount = parsedApps.size,
                    apps = parsedApps
                )
            }
        } catch (e: Exception) {
            ImportResult(false, error = e.message ?: "Unknown error")
        }
    }

    private fun parseLauncherDb(dbFile: File): List<ImportApp> {
        val apps = mutableListOf<ImportApp>()
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.rawQuery("SELECT * FROM favorites ORDER BY container, screen, rank", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val itemType = cursor.getInt(cursor.getColumnIndexOrThrow("itemType"))
                    // itemType 0 = app, 1 = shortcut, 2 = widget
                    if (itemType != 0) continue

                    val intentVal = cursor.getString(cursor.getColumnIndexOrThrow("intent")) ?: continue
                    val packageName = extractPackageFromIntent(intentVal) ?: continue

                    val container = cursor.getInt(cursor.getColumnIndexOrThrow("container"))
                    val screen = cursor.getInt(cursor.getColumnIndexOrThrow("screen"))
                    val cellX = cursor.getInt(cursor.getColumnIndexOrThrow("cellX"))
                    val cellY = cursor.getInt(cursor.getColumnIndexOrThrow("cellY"))

                    // container = -101 means hotseat (favorites row)
                    val isHotseat = container == -101
                    val hotseatIndex = cursor.getInt(cursor.getColumnIndexOrThrow("rank"))

                    // Get display name from package manager
                    val displayName = try {
                        val pm = context.packageManager
                        val ai = pm.getApplicationInfo(packageName, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (e: Exception) {
                        packageName
                    }

                    apps.add(ImportApp(
                        packageName = packageName,
                        displayName = displayName,
                        screen = screen,
                        screenX = cellX,
                        screenY = cellY,
                        isInHotseat = isHotseat,
                        hotseatIndex = hotseatIndex
                    ))
                }
            }
            db.close()
        } catch (e: Exception) {
            // If DB parsing fails, return empty
        }
        return apps
    }

    private fun extractPackageFromIntent(intentUri: String): String? {
        return try {
            val intent = Intent.parseUri(intentUri, 0)
            intent.`package` ?: intent.component?.packageName
        } catch (e: Exception) {
            null
        }
    }

    private fun FileInputStream(fd: java.io.FileDescriptor): java.io.FileInputStream {
        return java.io.FileInputStream(fd)
    }
}
