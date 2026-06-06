package com.example.repolauncher.service

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper to resolve app icons from installed icon packs.
 *
 * Android icon packs work via the component2image resource identifier mechanism:
 * An icon pack declares <meta-data android:name="launcher.theme.KEY" .../> in its manifest
 * pointing to a drawable resource. To get a specific app's icon, we look for
 * a drawable named "component_{package}_{activity}" or "icon_{package}" in the icon pack's resources.
 *
 * Arcticons and other popular packs follow this convention.
 */
class IconPackHelper(private val context: Context) {

    data class IconPackInfo(
        val packageName: String,
        val displayName: String
    )

    /** Get list of installed icon packs on device */
    fun getInstalledIconPacks(): List<IconPackInfo> {
        val pm = context.packageManager
        val intent = android.content.Intent("launcher.intent.action.ICON_PACK")
        val packs = mutableListOf<IconPackInfo>()

        try {
            val resolveInfos = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            for (info in resolveInfos) {
                try {
                    val ai = info.activityInfo
                    val label = ai.loadLabel(pm).toString()
                    packs.add(IconPackInfo(pm.getApplicationLabel(ai.applicationInfo).toString(), ai.packageName))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Also check common alternative intent actions
        if (packs.isEmpty()) {
            try {
                val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in allApps) {
                    try {
                        val ai = pm.getActivityInfo(
                            android.content.ComponentName(app.packageName, "${app.packageName}.MainActivity"),
                            PackageManager.GET_META_DATA
                        )
                        val meta = ai.metaData ?: continue
                        // Icon packs typically have meta-data with a drawable resource
                        if (meta.containsKey("launcher.theme.KEY") ||
                            meta.containsKey("launcher.theme.TAG") ||
                            meta.containsKey("launcher.theme.ICON")) {
                            packs.add(IconPackInfo(app.loadLabel(pm).toString(), app.packageName))
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        return packs.distinctBy { it.packageName }
    }

    /**
     * Try to get an icon from the icon pack for a given package.
     * Returns null if not found, in which case the system icon should be used.
     */
    fun getIconFromPack(packPackage: String, appPackage: String, activityName: String? = null): Drawable? {
        try {
            val pm = context.packageManager
            val packResources = pm.getResourcesForApplication(packPackage)
            val packPm = pm.getPackageInfo(packPackage, PackageManager.GET_META_DATA)

            // Strategy 1: Try "icon_{package}" (used by Arcticons and many packs)
            val iconResId = packResources.getIdentifier("icon_${appPackage.replace('.', '_')}", "drawable", packPackage)
            if (iconResId != 0) {
                try {
                    val drawable = packResources.getDrawable(iconResId, null)
                    if (drawable != null) return drawable
                } catch (_: Exception) {}
            }

            // Strategy 2: Try "component_{package}_{activity}" if we have the activity name
            if (activityName != null) {
                val compResId = packResources.getIdentifier(
                    "component_${appPackage.replace('.', '_')}_${activityName.replace('.', '_')}",
                    "drawable", packPackage
                )
                if (compResId != 0) {
                    try {
                        val drawable = packResources.getDrawable(compResId, null)
                        if (drawable != null) return drawable
                    } catch (_: Exception) {}
                }
            }

            // Strategy 3: Check meta-data for component mapping
            val meta = packPm.applicationInfo?.metaData
            if (meta != null) {
                // Some packs map app packages to drawable resource names in meta-data
                val mappedResource = meta.getString(appPackage)
                if (mappedResource != null) {
                    val mappedId = packResources.getIdentifier(mappedResource, "drawable", packPackage)
                    if (mappedId != 0) {
                        return packResources.getDrawable(mappedId, null)
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /** Convert any Drawable to Bitmap for consistent display */
    fun drawableToBitmap(drawable: Drawable, size: Int = 56): Bitmap {
        return when (drawable) {
            is BitmapDrawable -> {
                val bmp = drawable.bitmap
                Bitmap.createScaledBitmap(bmp, size, size, true)
            }
            is AdaptiveIconDrawable -> {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                bitmap
            }
            else -> {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                bitmap
            }
        }
    }
}
