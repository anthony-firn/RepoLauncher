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

/**
 * Helper to detect installed icon packs and resolve app icons from them.
 *
 * Works just like Lawnchair:
 * 1. Detects icon packs via known intent actions and meta-data checks.
 * 2. Resolves icons by parsing the pack's "appfilter.xml" (the standard way)
 *    and falls back to "icon_{package}" / "component_{package}_{activity}" naming.
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

    /** Get list of installed icon packs on device */
    fun getInstalledIconPacks(): List<IconPackInfo> {
        val pm = context.packageManager
        val packs = mutableMapOf<String, String>() // packageName -> displayName

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

        // Also scan all installed apps for icon pack meta-data
        try {
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in allApps) {
                try {
                    val ai = app
                    val meta = ai.metaData ?: continue
                    if (meta.containsKey("launcher.theme.KEY") ||
                        meta.containsKey("org.adw.launcher.THEME_ICONS") ||
                        meta.containsKey("launcher.theme.ICON") ||
                        meta.containsKey("launcher.iconpack")
                    ) {
                        val label = ai.loadLabel(pm)?.toString() ?: ai.packageName
                        if (ai.packageName !in packs) packs[ai.packageName] = label
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        return packs.map { (pkg, label) -> IconPackInfo(pkg, label) }
    }

    /**
     * Try to get an icon from the icon pack for a given package.
     * Returns null if not found, in which case the system icon should be used.
     */
    fun getIconFromPack(packPackage: String, appPackage: String, activityName: String? = null): Drawable? {
        try {
            val pm = context.packageManager
            val packResources = pm.getResourcesForApplication(packPackage)

            // Strategy 1: Parse appfilter.xml (standard Lawnchair / Nova / ADW method)
            val parsed = parseAppfilterXml(packResources, packPackage, appPackage, activityName)
            if (parsed != null) return parsed

            // Strategy 2: Try "icon_{package}" (used by Arcticons and many packs)
            val iconResId = packResources.getIdentifier("icon_${appPackage.replace('.', '_')}", "drawable", packPackage)
            if (iconResId != 0) {
                try {
                    val drawable = packResources.getDrawable(iconResId, null)
                    if (drawable != null) return drawable
                } catch (_: Exception) {}
            }

            // Strategy 3: Try "component_{package}_{activity}" if we have the activity name
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

            // Strategy 4: Check meta-data for component mapping
            try {
                val packPm = pm.getPackageInfo(packPackage, PackageManager.GET_META_DATA)
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

    /**
     * Parse the icon pack's "appfilter.xml" to find a matching drawable.
     * This is the standard approach used by Lawnchair, Nova, ADW, etc.
     *
     * appfilter.xml format:
     *   <item component="ComponentInfo{package/activity}" drawable="drawable_name"/>
     */
    private fun parseAppfilterXml(
        packResources: Resources,
        packPackage: String,
        appPackage: String,
        activityName: String?
    ): Drawable? {
        val parser = getXmlParser(packResources, packPackage) ?: return null
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                if (parser.name != "item") continue

                val component = parser.getAttributeValue(null, "component") ?: continue
                val drawableName = parser.getAttributeValue(null, "drawable") ?: continue

                // ComponentInfo{package/activity}
                val compStart = "ComponentInfo{"
                val compEnd = "}"
                var compStr = component
                if (component.startsWith(compStart) && component.endsWith(compEnd)) {
                    compStr = component.substring(compStart.length, component.length - compEnd.length)
                }

                // Check if this component matches our app
                val slashIdx = compStr.indexOf('/')
                val compPackage = if (slashIdx > 0) compStr.substring(0, slashIdx) else ""

                if (compPackage == appPackage) {
                    @Suppress("DEPRECATION")
                    val id = packResources.getIdentifier(drawableName, "drawable", packPackage)
                    if (id != 0) {
                        try {
                            return packResources.getDrawable(id, null)
                        } catch (_: Exception) {}
                    }
                }

                // Also try by fully qualified component name
                if (activityName != null) {
                    val parsed = ComponentName.unflattenFromString(compStr)
                    if (parsed != null && parsed.packageName == appPackage) {
                        @Suppress("DEPRECATION")
                        val id = packResources.getIdentifier(drawableName, "drawable", packPackage)
                        if (id != 0) {
                            try {
                                return packResources.getDrawable(id, null)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: XmlPullParserException) {}
        catch (_: IOException) {}
        catch (_: Exception) {}

        return null
    }

    /** Get an XmlPullParser for appfilter.xml from the icon pack */
    private fun getXmlParser(packResources: Resources, packPackage: String): XmlPullParser? {
        // Try resource xml/appfilter.xml
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

    companion object {
        private const val TAG = "IconPackHelper"
    }
}
