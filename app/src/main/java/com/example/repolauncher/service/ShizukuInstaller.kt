package com.example.repolauncher.service

import java.io.File

class ShizukuInstaller {

    companion object {
        suspend fun installApk(apkFile: File): Result<String> {
            return try {
                if (!apkFile.exists()) return Result.failure(Exception("APK file not found"))
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pm install -r ${apkFile.absolutePath}"))
                val exitCode = process.waitFor()
                val error = process.errorStream.bufferedReader().readText()
                val output = process.inputStream.bufferedReader().readText()
                if (exitCode == 0) Result.success("Installed successfully: $output")
                else Result.failure(Exception("Install failed: $error"))
            } catch (e: Exception) { Result.failure(e) }
        }
    }
}
