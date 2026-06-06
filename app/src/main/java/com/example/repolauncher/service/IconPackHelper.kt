package com.example.repolauncher.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper for icon pack support.
 *
 * Pattern copied from Lawnchair:
 * - Load appfilter.xml ONCE on Dispatchers.IO → build componentMap
 * - getIcon() = componentMap[ComponentName] — instant map lookup
 * - XML never parsed on main thread
 */
class IconPackHelper(private val context: Context) {

    data class IconPackInfo(
        val packageName: String,
        val displayName: String
    )

    /** All known icon pack intent actions */
    private val iconPackIntents = listOf(
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme",
        "launcher.intent.action.ICON_PACK"
    )

    // ── Icon pack data (loaded async, off main thread) ──

    /** Parsed icon map: ComponentName → drawable name (from appfilter.xml) */
    private var _iconMap: Map<String, String>? = null // packPackage_drawableName → drawableName cache
    private var _componentMap: Map<ComponentName, String>? = null
    private var _loadedPackage: String? = null

    /**
     * Load icon pack data ONCE off the main thread (like Lawnchair's loadInternal).
     * Call this via LaunchedEffect + withContext(Dispatchers.IO).
     * After this, getIconFromPack() is just a map lookup.
     */
    suspend fun loadForPackage(packPackage: String) {
        if (packPackage.isEmpty()) {
            _componentMap = emptyMap()
            _loadedPackage = packPackage
            return
        }
        if (_loadedPackage == packPackage && _componentMap != null) return // already loaded

        withContext(Dispatchers.IO) {
            try {
                val packResources = context.packageManager.getResourcesForApplication(packPackage)
                val componentMap = mutableMapOf<ComponentName, String>()

                // Parse appfilter.xml ONCE — like Lawnchair's CustomIconPack.loadInternal()
                val parser = getXmlParser(packResources, packPackage)
                if (parser != null) {
                    try {
                        val compStart = "ComponentInfo{"
                        val compStartLength = compStart.length
                        val compEnd = "}"
                        val compEndLength = compEnd.length

                        while (parser.next() != XmlPullParser.END_DOCUMENT) {
                            if (parser.eventType != XmlPullParser.START_TAG) continue
                            if (parser.name != "item") continue

                            var component = parser.getAttributeValue(null, "component") ?: continue
                            val drawableName = parser.getAttributeValue(null, "drawable") ?: continue

                            // Strip ComponentInfo{...} wrappers (same as Lawnchair)
                            if (component.startsWith(compStart) && component.endsWith(compEnd)) {
                                component = component.substring(compStartLength, component.length - compEndLength)
                            }

                            val parsed = ComponentName.unflattenFromString(component)
                            if (parsed != null) {
                                componentMap[parsed] = drawableName
                            }
                        }
                    } catch (_: Exception) {}
                }

                _componentMap = componentMap
                _loadedPackage = packPackage
            } catch (_: Exception) {
                _componentMap = emptyMap()
                _loadedPackage = packPackage
            }
        }
    }

    /**
     * Get icon from the pre-loaded map. Instant lookup — no XML parsing.
     * Returns null if not found (use system icon).
     */
    fun getIconFromPack(packPackage: String, appPackage: String, activityName: String? = null): Drawable? {
        if (packPackage.isEmpty() || _componentMap == null || _loadedPackage != packPackage) return null

        // Build ComponentName variants to match
        val cn = if (activityName != null) {
            ComponentName(appPackage, activityName)
        } else {
            ComponentName(appPackage, ".")
        }

        // Direct match by ComponentName
        val drawableName = _componentMap!![cn] ?: _componentMap!!.entries.find { entry ->
            entry.key.packageName == appPackage && (activityName == null || entry.key.className == activityName)
        }?.value

        if (drawableName != null) {
            try {
                val packResources = context.packageManager.getResourcesForApplication(packPackage)
                val id = packResources.getIdentifier(drawableName, "drawable", packPackage)
                if (id != 0) {
                    return packResources.getDrawable(id, null)
                }
            } catch (_: Exception) {}
        }

        // Fallback: try named strategies (icon_{package}, component_{package}_{activity})
        try {
            val packResources = context.packageManager.getResourcesForApplication(packPackage)

            // Try "icon_{package}" (Arcticons style)
            val iconResId = packResources.getIdentifier(
                "icon_${appPackage.replace('.', '_')}", "drawable", packPackage
            )
            if (iconResId != 0) {
                try {
                    val drawable = packResources.getDrawable(iconResId, null)
                    if (drawable != null) return drawable
                } catch (_: Exception) {}
            }

            // Try "component_{package}_{activity}"
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

            // Check meta-data for component mapping
            try {
                val packPm = context.packageManager.getPackageInfo(packPackage, PackageManager.GET_META_DATA)
                val meta = packPm.applicationInfo?.metaData
                if (meta != null) {
                    val mappedResource = meta.getString(appPackage)
                    if (mappedResource != null) {
                        val mappedId = packResources.getIdentifier(mappedResource, "drawable", packPackage)
                        if (mappedId != 0) {
                            return packResources.getDrawable(mappedId, null)
                        }
                    }
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        return null
    }

    /** Parse all icons into a Map<String, Drawable?> — one-time batch for Compose */
    suspend fun resolveAllIcons(packPackage: String, appList: List<Pair<String, String?>>): Map<String, Drawable?> {
        if (packPackage.isEmpty()) return emptyMap()
        loadForPackage(packPackage)
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, Drawable?>()
            for ((pkg, activity) in appList) {
                result["$pkg|$activity"] = getIconFromPack(packPackage, pkg, activity)
            }
            result
        }
    }

    // ── Install detection ──

    /** Get list of installed icon packs on device */
    fun getInstalledIconPacks(): List<IconPackInfo> {
        val pm = context.packageManager
        val packs = mutableMapOf<String, String>()

        try {
            for (intentAction in iconPackIntents) {
                val intent = Intent(intentAction)
                val resolveInfos = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
                for (info in resolveInfos) {
                    try {
                        val pkg = info.activityInfo.packageName
                        val label = info.activityInfo.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
                        if (pkg !in packs) packs[pkg] = label
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // Scan for meta-data
        try {
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in allApps) {
                try {
                    val meta = app.metaData ?: continue
                    if (meta.containsKey("launcher.theme.KEY") ||
                        meta.containsKey("org.adw.launcher.THEME_ICONS") ||
                        meta.containsKey("launcher.theme.ICON") ||
                        meta.containsKey("launcher.iconpack")
                    ) {
                        val label = app.loadLabel(pm)?.toString() ?: app.packageName
                        if (app.packageName !in packs) packs[app.packageName] = label
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        return packs.map { (pkg, label) -> IconPackInfo(pkg, label) }
    }

    // ── XML parser (same as Lawnchair's getXml) ──

    private fun getXmlParser(packResources: Resources, packPackage: String): XmlPullParser? {
        // Try xml/appfilter.xml
        try {
            val resId = packResources.getIdentifier("appfilter", "xml", packPackage)
            if (resId != 0) {
                return context.packageManager.getXml(packPackage, resId, null)
            }
        } catch (_: Exception) {}

        // Try assets/appfilter.xml
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(packResources.assets.open("appfilter.xml"), Xml.Encoding.UTF_8.toString())
            return parser
        } catch (_: Exception) {}

        return null
    }

    // ── Utility ──

    /** Convert any Drawable to Bitmap for consistent display */
    fun drawableToBitmap(drawable: Drawable, size: Int = 56): Bitmap {
        return when (drawable) {
            is BitmapDrawable -> {
                Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
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

    companion object {
        private const val TAG = "IconPackHelper"
    }
}
