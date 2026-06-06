package com.example.repolauncher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.repolauncher.data.*
import com.example.repolauncher.service.IconPackHelper
import com.example.repolauncher.service.LawnchairBackupImporter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Lawnchair-style: 1:1 finger tracking, momentum on quick flick */
private const val DRAG_MULT = 1f
/** Position threshold: 20% of screen height */
private const val POS_THRESHOLD = 0.20f
/** Velocity threshold: 300 px/s — a quick flick commits the transition */
private const val VEL_THRESHOLD = 300f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RepoLauncherTheme { LauncherScreen() } }
    }
}

@Composable
fun RepoLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8AB4F8), secondary = Color(0xFF9AA0A6),
            surface = Color(0xFF1E1E1E), background = Color(0xFF121212),
            onSurface = Color.White, onBackground = Color.White,
            surfaceVariant = Color(0xFF2C2C2C), onSurfaceVariant = Color(0xFFE8EAED),
            surfaceContainerLow = Color(0xFF1A1A1A), surfaceContainer = Color(0xFF242424)
        ),
        content = content
    )
}

@Composable
fun LauncherScreen(viewModel: LauncherViewModel = viewModel()) {
    val repos by viewModel.repos.collectAsStateWithLifecycle()
    val settings = viewModel.settings

    var rawOffset by remember { mutableFloatStateOf(0f) }
    var screenHeight by remember { mutableFloatStateOf(1f) }
    var drawerOpen by remember { mutableStateOf(false) }
    val statusBarHeight = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toFloat() }
    /** Max drawer offset = screen minus status bar, so content sits below clock/battery */
    val maxOffset = screenHeight - statusBarHeight.coerceAtLeast(48f)

    val displayOffset by animateFloatAsState(
        targetValue = rawOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "drawerOffset"
    )

    fun commit(totalDy: Float, velocityY: Float) {
        val goingUp = velocityY < -VEL_THRESHOLD
        val goingDown = velocityY > VEL_THRESHOLD
        if (goingUp) { rawOffset = maxOffset; drawerOpen = true }
        else if (goingDown) { rawOffset = 0f; drawerOpen = false }
        else if (totalDy < 0 && abs(totalDy) > 20f) { rawOffset = maxOffset; drawerOpen = true }
        else if (totalDy > 0 && abs(totalDy) > 20f) { rawOffset = 0f; drawerOpen = false }
        else if (rawOffset > screenHeight * POS_THRESHOLD) { rawOffset = maxOffset; drawerOpen = true }
        else { rawOffset = 0f; drawerOpen = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { screenHeight = it.height.coerceAtLeast(1).toFloat() }
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!drawerOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            var cumulativeDy = 0f
                            var lastDy = 0f; var lastTime = 0L
                            detectVerticalDragGestures(
                                onDragStart = { cumulativeDy = 0f; lastDy = 0f },
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    cumulativeDy += dy
                                    lastDy = dy; lastTime = SystemClock.uptimeMillis()
                                    rawOffset = (rawOffset - dy * DRAG_MULT).coerceIn(0f, maxOffset)
                                },
                                onDragEnd = {
                                    val elapsed = SystemClock.uptimeMillis() - lastTime
                                    val vel = if (elapsed > 0 && elapsed < 300) lastDy / (elapsed / 1000f) else 0f
                                    commit(cumulativeDy, vel)
                                },
                                onDragCancel = { rawOffset = 0f; drawerOpen = false }
                            )
                        }
                ) {
                    Column(Modifier.fillMaxSize()) {
                        WorkspacePages(viewModel, Modifier.weight(1f))
                        HotseatBar(viewModel, Modifier.fillMaxWidth(), settings)
                        RepoStrip(repos, viewModel, Modifier.fillMaxWidth())
                    }
                }
            } else {
                // Hide home screen scrub when drawer is open (still advance offset for smooth return)
                if (displayOffset < maxOffset * 0.95f) {
                    Column(Modifier.fillMaxSize()) {
                        WorkspacePages(viewModel, Modifier.weight(1f))
                        HotseatBar(viewModel, Modifier.fillMaxWidth(), settings)
                        RepoStrip(repos, viewModel, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        val drawerFraction = (displayOffset / maxOffset).coerceIn(0f, 1f)

        if (displayOffset > 1f || drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = drawerFraction }
                    .offset { IntOffset(0, (maxOffset - displayOffset).roundToInt()) }
                    .pointerInput(Unit) {
                        var cumulativeDy = 0f
                        var lastDy = 0f; var lastTime = 0L
                        detectVerticalDragGestures(
                            onDragStart = { cumulativeDy = 0f; lastDy = 0f },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                cumulativeDy += dy
                                lastDy = dy; lastTime = SystemClock.uptimeMillis()
                                rawOffset = (rawOffset - dy * DRAG_MULT).coerceIn(0f, maxOffset)
                            },
                            onDragEnd = {
                                val elapsed = SystemClock.uptimeMillis() - lastTime
                                val vel = if (elapsed > 0 && elapsed < 300) lastDy / (elapsed / 1000f) else 0f
                                commit(cumulativeDy, vel)
                            },
                            onDragCancel = { rawOffset = 0f; drawerOpen = false }
                        )
                    }
            ) {
                AppDrawerContent(viewModel, settings) { rawOffset = 0f; drawerOpen = false }
            }
        }
    }

    if (viewModel.showAddRepoDialog) AddRepoDialog(viewModel)
    if (viewModel.showRepoDetail && viewModel.selectedRepo != null) RepoDetailSheet(viewModel)
    if (viewModel.showBackupImportDialog) ImportBackupDialog(viewModel)
    if (viewModel.showAppInfoDialog) AppInfoDialog(viewModel)
    if (viewModel.showCustomizeDialog) CustomizeScreen(viewModel)
    if (viewModel.showSettingsDialog) SettingsScreen(viewModel)
}

// ── Workspace with Lawnchair-style long-press context menu ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspacePages(viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    var showContextMenu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp)
                .combinedClickable(
                    onClick = { },
                    onLongClick = { showContextMenu = true }
                )
        ) {
            val hasApps = viewModel.workspaceApps.isNotEmpty()
            if (!hasApps) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Home, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("Repo Launcher", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    Text("Swipe up anywhere to open app drawer", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        AssistChip(onClick = { }, label = { Text("Apps") }, leadingIcon = { Icon(Icons.Default.Apps, null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { viewModel.showAddRepoDialog = true }, label = { Text("Add Repo") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { viewModel.showBackupImportDialog = true }, label = { Text("Import Backup") }, leadingIcon = { Icon(Icons.Default.Restore, null, Modifier.size(18.dp)) })
                    }
                }
            } else {
                val apps = viewModel.workspaceApps
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val rows = apps.chunked(4)
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { app -> WorkspaceAppIcon(app, viewModel, Modifier.weight(1f)) }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }

        // Lawnchair-style context menu overlay
        if (showContextMenu) {
            Box(Modifier.fillMaxSize()) {
                // Scrim
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(onClick = { showContextMenu = false })
                )
                // Menu card
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "Home screen",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                        // Wallpaper & style
                        Surface(
                            onClick = {
                                showContextMenu = false
                                viewModel.launchCustomize()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Wallpaper, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text("Wallpaper & style", style = MaterialTheme.typography.bodyLarge)
                                    Text("Customize your home screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        // Widgets
                        Surface(
                            onClick = {
                                showContextMenu = false
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                                        action = "android.appwidget.action.APPWIDGET_PICK"
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    })
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.GridView, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text("Widgets", style = MaterialTheme.typography.bodyLarge)
                                    Text("Add widgets to your home screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        // Add repository
                        Surface(
                            onClick = {
                                showContextMenu = false
                                viewModel.showAddRepoDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text("Add repository", style = MaterialTheme.typography.bodyLarge)
                                    Text("Add GitHub repo for APK installs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        // Settings
                        Surface(
                            onClick = {
                                showContextMenu = false
                                viewModel.launchSettings()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text("Settings", style = MaterialTheme.typography.bodyLarge)
                                    Text("Launcher settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Customize Screen (Wallpaper & style) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeScreen(viewModel: LauncherViewModel) {
    val ctx = LocalContext.current
    var localSettings by remember(viewModel.settings) { mutableStateOf(viewModel.settings) }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = { viewModel.showCustomizeDialog = false },
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(scrollState)
                    .padding(24.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wallpaper, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Wallpaper & style", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(24.dp))

                // Wallpaper picker
                Text("Wallpaper", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_SET_WALLPAPER).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                                    setClassName("com.android.wallpaper.picker", "com.android.wallpaper.picker.WallpaperPickerActivity")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Choose wallpaper", style = MaterialTheme.typography.bodyLarge)
                            Text("From gallery or wallpapers app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))

                // Grid settings
                Text("Grid", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SettingsSliderRow(
                    title = "Columns",
                    subtitle = "Apps per row",
                    value = localSettings.gridColumns.toFloat(),
                    range = 3f..6f,
                    steps = 2,
                    onValueChange = { localSettings = localSettings.copy(gridColumns = it.roundToInt()) }
                )
                SettingsSliderRow(
                    title = "Rows",
                    subtitle = "Apps per column",
                    value = localSettings.gridRows.toFloat(),
                    range = 4f..7f,
                    steps = 2,
                    onValueChange = { localSettings = localSettings.copy(gridRows = it.roundToInt()) }
                )
                Spacer(Modifier.height(24.dp))

                // Dock settings
                Text("Dock", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SettingsSliderRow(
                    title = "Dock icon count",
                    subtitle = "Maximum icons in dock",
                    value = localSettings.dockIconCount.toFloat(),
                    range = 3f..7f,
                    steps = 3,
                    onValueChange = { localSettings = localSettings.copy(dockIconCount = it.roundToInt()) }
                )
                Spacer(Modifier.height(24.dp))

                // Icon size
                Text("Icons", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SettingsSliderRow(
                    title = "Icon size",
                    subtitle = "${localSettings.iconSizePx}px",
                    value = localSettings.iconSizePx.toFloat(),
                    range = 40f..80f,
                    steps = 7,
                    onValueChange = { localSettings = localSettings.copy(iconSizePx = it.roundToInt()) }
                )
                Spacer(Modifier.height(8.dp))

                // Icon pack
                Text("Icon Pack", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val iconPacks = remember { IconPackHelper(ctx).getInstalledIconPacks() }
                SettingsItem(
                    title = localSettings.iconPackPackage.ifEmpty { "None (system icons)" },
                    subtitle = "Apply custom icons from a pack",
                    icon = Icons.Default.Palette,
                    onClick = { }
                )
                if (iconPacks.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        iconPacks.forEach { pack ->
                            Surface(
                                onClick = {
                                    localSettings = localSettings.copy(
                                        iconPackPackage = if (localSettings.iconPackPackage == pack.packageName) "" else pack.packageName
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = if (localSettings.iconPackPackage == pack.packageName)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = if (localSettings.iconPackPackage == pack.packageName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(pack.displayName, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                } else {
                    Text("No icon packs installed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp))
                    Text("Install Arcticons or another icon pack from Play Store", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                Spacer(Modifier.height(8.dp))

                // Toggle switches
                SettingsToggleRow(
                    title = "Show app labels",
                    subtitle = "Display app names under icons",
                    checked = localSettings.showAppLabels,
                    onCheckedChange = { localSettings = localSettings.copy(showAppLabels = it) }
                )
                SettingsToggleRow(
                    title = "Show search bar",
                    subtitle = "Display search field in app drawer",
                    checked = localSettings.showSearchBar,
                    onCheckedChange = { localSettings = localSettings.copy(showSearchBar = it) }
                )

                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.showCustomizeDialog = false }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        viewModel.updateSettings(localSettings)
                        viewModel.showCustomizeDialog = false
                    }) { Text("Apply") }
                }
            }
        }
    }
}

// ── Full Settings Screen (Like Lawnchair) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: LauncherViewModel) {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = { viewModel.showSettingsDialog = false },
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(scrollState)
                    .padding(24.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(24.dp))

                // Home screen
                SettingsSectionHeader("Home screen")
                SettingsItem(
                    title = "Home screen layout",
                    subtitle = "Grid size, icon arrangement",
                    icon = Icons.Default.GridView,
                    onClick = { viewModel.showSettingsDialog = false; viewModel.launchCustomize() }
                )
                SettingsItem(
                    title = "Wallpaper",
                    subtitle = "Set home screen wallpaper",
                    icon = Icons.Default.Wallpaper,
                    onClick = {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_SET_WALLPAPER).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
                SettingsItem(
                    title = "Widgets",
                    subtitle = "Add widgets to home screen",
                    icon = Icons.Default.GridView,
                    onClick = {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                                action = "android.appwidget.action.APPWIDGET_PICK"
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Dock
                SettingsSectionHeader("Dock")
                SettingsItem(
                    title = "Dock settings",
                    subtitle = "Configure dock appearance and behavior",
                    icon = Icons.Default.Star,
                    onClick = { viewModel.showSettingsDialog = false; viewModel.launchCustomize() }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Icon pack
                val iconPacks = remember { IconPackHelper(ctx).getInstalledIconPacks() }
                val currentIconPack = remember { iconPacks.find { it.packageName == viewModel.settings.iconPackPackage } }
                SettingsSectionHeader("Icon pack")
                SettingsItem(
                    title = currentIconPack?.displayName ?: "None (system icons)",
                    subtitle = "Apply custom icons from an installed pack",
                    icon = Icons.Default.Palette,
                    onClick = {}
                )
                if (iconPacks.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        iconPacks.forEach { pack ->
                            Surface(
                                onClick = {
                                    viewModel.updateSettings(
                                        viewModel.settings.copy(
                                            iconPackPackage = if (viewModel.settings.iconPackPackage == pack.packageName) "" else pack.packageName
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = if (viewModel.settings.iconPackPackage == pack.packageName)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = if (viewModel.settings.iconPackPackage == pack.packageName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(pack.displayName, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                } else {
                    Text("No icon packs installed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp))
                    Text("Install Arcticons or another icon pack from Play Store", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Backup & Restore
                SettingsSectionHeader("Backup & Restore")
                SettingsItem(
                    title = "Import Lawnchair backup",
                    subtitle = "Restore home screen layout from .lawnchairbackup",
                    icon = Icons.Default.Restore,
                    onClick = { viewModel.showSettingsDialog = false; viewModel.showBackupImportDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // App drawer
                SettingsSectionHeader("App drawer")
                SettingsItem(
                    title = "Search bar",
                    subtitle = "Show or hide the search bar in app drawer",
                    icon = Icons.Default.Search,
                    onClick = {}
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Gestures
                SettingsSectionHeader("Gestures")
                SettingsItem(
                    title = "Swipe up to open drawer",
                    subtitle = "Enabled — swipe anywhere on home screen",
                    icon = Icons.Default.Swipe,
                    onClick = {}
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // About
                SettingsSectionHeader("About")
                SettingsItem(
                    title = "Repo Launcher",
                    subtitle = "v1.0 — Open source launcher",
                    icon = Icons.Default.Info,
                    onClick = {}
                )
                SettingsItem(
                    title = "Source code",
                    subtitle = "GitHub — Built with Compose",
                    icon = Icons.Default.Code,
                    onClick = {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/example/repolauncher")).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )

                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.showSettingsDialog = false }) { Text("Close") }
                }
            }
        }
    }
}

// ── Reusable settings components ──

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent
    ) {
        Row(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun SettingsSliderRow(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent
    ) {
        Row(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

// ── Workspace App Icon ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceAppIcon(app: LawnchairBackupImporter.ImportApp, viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    var showMenu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val icon = remember(app.packageName, viewModel.settings.iconPackPackage) {
        val pack = viewModel.settings.iconPackPackage
        if (pack.isNotBlank()) {
            val packIcon = IconPackHelper(ctx).getIconFromPack(pack, app.packageName)
            if (packIcon != null) packIcon else try { pm.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
        } else {
            try { pm.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.combinedClickable(onClick = { viewModel.launchApp(app.packageName) }, onLongClick = { showMenu = true })) {
        val bitmap = icon?.toBitmap(64, 64)?.asImageBitmap()
        Box {
            if (bitmap != null) Image(bitmap = bitmap, contentDescription = app.displayName, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            else Icon(Icons.Default.Android, null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("Move to dock") }, onClick = { showMenu = false; viewModel.moveAppToDock(app.packageName) }, leadingIcon = { Icon(Icons.Default.Star, null) })
                DropdownMenuItem(text = { Text("Remove from home") }, onClick = { showMenu = false; viewModel.removeFromWorkspace(app.packageName) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                DropdownMenuItem(text = { Text("App info") }, onClick = { showMenu = false; viewModel.showAppInfo(app.packageName) }, leadingIcon = { Icon(Icons.Default.Info, null) })
            }
        }
        if (viewModel.settings.showAppLabels) {
            Text(app.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 4.dp).width(64.dp))
        }
    }
}

// ── Hotseat ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HotseatBar(viewModel: LauncherViewModel, modifier: Modifier = Modifier, settings: LauncherSettings) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            viewModel.hotseatApps.take(settings.dockIconCount).forEach { app ->
                var showMenu by remember { mutableStateOf(false) }
                val fullApp = viewModel.installedApps.find { it.packageName == app.packageName }
                val icon = fullApp?.icon
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.combinedClickable(onClick = { viewModel.launchApp(app.packageName) }, onLongClick = { showMenu = true })) {
                    val bitmap = try { icon?.let { 
                        if (icon is android.graphics.drawable.Drawable) icon.toBitmap(52, 52)?.asImageBitmap() 
                        else null 
                    } } catch (_: Exception) { null }
                    Box {
                        if (bitmap != null) Image(bitmap = bitmap, contentDescription = app.displayName, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                        else Icon(Icons.Default.Android, null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Move to home screen") }, onClick = { showMenu = false; viewModel.moveAppToWorkspace(app.packageName) }, leadingIcon = { Icon(Icons.Default.Home, null) })
                            DropdownMenuItem(text = { Text("Remove from dock") }, onClick = { showMenu = false; viewModel.removeFromWorkspace(app.packageName) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                            DropdownMenuItem(text = { Text("App info") }, onClick = { showMenu = false; viewModel.showAppInfo(app.packageName) }, leadingIcon = { Icon(Icons.Default.Info, null) })
                        }
                    }
                }
            }
            val e = settings.dockIconCount - viewModel.hotseatApps.size; if (e > 0) repeat(e) { Spacer(Modifier.size(48.dp)) }
        }
    }
}

// ── Repo Strip ──

@Composable
fun RepoStrip(repos: List<RepoConfig>, viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val s by viewModel.installStates.collectAsStateWithLifecycle()
            repos.take(3).forEach { repo ->
                Surface(modifier = Modifier.padding(horizontal = 2.dp).clickable { viewModel.openRepoDetail(repo) }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp))
                        Text(repo.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
                        if (s[repo.id] is InstallState.Success) Icon(Icons.Default.CheckCircle, null, Modifier.size(12.dp), tint = Color(0xFF81C784))
                    }
                }
            }
            if (repos.isEmpty()) Text("No repos", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.weight(1f))
        }
    }
}

// ── App Drawer Content ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerContent(viewModel: LauncherViewModel, settings: LauncherSettings, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = 0.97f)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (settings.showSearchBar) {
                OutlinedTextField(value = viewModel.searchQuery, onValueChange = { viewModel.searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Search apps…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (viewModel.searchQuery.isNotEmpty()) IconButton(onClick = { viewModel.searchQuery = "" }) { Icon(Icons.Default.Close, null) } },
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant))
            }
            val apps = viewModel.filteredApps
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                val rows = apps.chunked(settings.gridColumns)
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { app -> AppDrawerItem(app, viewModel, onClose, Modifier.weight(1f), settings) }
                        repeat(settings.gridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerItem(app: AppInfo, viewModel: LauncherViewModel, onClose: () -> Unit, modifier: Modifier = Modifier, settings: LauncherSettings) {
    var showMenu by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.combinedClickable(
        onClick = { viewModel.launchApp(app.packageName); onClose() },
        onLongClick = { showMenu = true }
    )) {
        val bitmap = app.icon?.toBitmap(56, 56)?.asImageBitmap()
        Box {
            if (bitmap != null) Image(bitmap = bitmap, contentDescription = app.appName, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            else Icon(Icons.Default.Android, null, Modifier.size(52.dp))
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("Add to home screen") }, onClick = { showMenu = false; viewModel.addAppToWorkspace(app.packageName) }, leadingIcon = { Icon(Icons.Default.AddToHomeScreen, null) })
                DropdownMenuItem(text = { Text("Add to dock") }, onClick = { showMenu = false; viewModel.addAppToDock(app.packageName) }, leadingIcon = { Icon(Icons.Default.Star, null) })
                DropdownMenuItem(text = { Text("App info") }, onClick = { showMenu = false; viewModel.showAppInfo(app.packageName) }, leadingIcon = { Icon(Icons.Default.Info, null) })
            }
        }
        if (settings.showAppLabels) {
            Text(app.appName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.width(64.dp).padding(top = 2.dp))
        }
    }
}

// ── Dialogs ──

@Composable
fun AddRepoDialog(viewModel: LauncherViewModel) {
    var r by remember { mutableStateOf("") }; var d by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { viewModel.showAddRepoDialog = false }, title = { Text("Add Repository") }, text = {
        Column { OutlinedTextField(r, { r = it }, label = { Text("GitHub repo (user/repo)") }, placeholder = { Text("e.g. ImranR98/Obtainium") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); OutlinedTextField(d, { d = it }, label = { Text("Display name (optional)") }, placeholder = { Text("Defaults to repo name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
    }, confirmButton = { Button(onClick = { viewModel.addRepo(r, d); viewModel.showAddRepoDialog = false }, enabled = r.isNotBlank() && r.contains("/")) { Text("Add") } }, dismissButton = { TextButton(onClick = { viewModel.showAddRepoDialog = false }) { Text("Cancel") } })
}

@Composable
fun RepoDetailSheet(viewModel: LauncherViewModel) {
    val repo = viewModel.selectedRepo ?: return
    val fetchStates by viewModel.repoFetchStates.collectAsStateWithLifecycle()
    val releases by viewModel.repoReleases.collectAsStateWithLifecycle()
    val iStates by viewModel.installStates.collectAsStateWithLifecycle()
    val fetchState = fetchStates[repo.id] ?: FetchState.Idle; val rel = releases[repo.id] ?: emptyList(); val iState = iStates[repo.id] ?: InstallState.Idle

    AlertDialog(onDismissRequest = { viewModel.closeRepoDetail() }, title = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { Column(Modifier.weight(1f)) { Text(repo.displayName.ifBlank { repo.repoFullName }); Text(repo.repoFullName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick = { viewModel.fetchReleases(repo.id) }) { Icon(Icons.Default.Refresh, "Refresh") } }
    }, text = {
        Column {
            when (iState) { is InstallState.Success -> { Surface(color = Color(0xFF1B5E20).copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp)) { Text("${iState.appName} installed!", Modifier.padding(12.dp), color = Color(0xFF81C784)) }; Spacer(Modifier.height(8.dp)) }; is InstallState.Error -> { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) { Column(Modifier.padding(12.dp)) { Text("Install failed", fontWeight = FontWeight.Bold); Text(iState.message, style = MaterialTheme.typography.bodySmall) } }; Spacer(Modifier.height(8.dp)); TextButton(onClick = { viewModel.clearInstallState(repo.id) }) { Text("Dismiss") } }; else -> {} }
            when (fetchState) { is FetchState.Loading -> { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }; is FetchState.Error -> { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) { Column(Modifier.padding(12.dp)) { Text("Failed to load releases"); Text(fetchState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } } }; is FetchState.Success -> { rel.forEach { ReleaseCard(it, repo.id, iState, viewModel, Modifier.padding(bottom = 8.dp)) } }; is FetchState.Idle -> { LaunchedEffect(repo.id) { viewModel.fetchReleases(repo.id) } } }
        }
    }, confirmButton = { TextButton(onClick = { viewModel.closeRepoDetail() }) { Text("Close") } })
}

@Composable
fun ReleaseCard(release: GitHubRelease, repoId: String, installState: InstallState, viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(release.name.ifBlank { release.tag_name }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium); if (release.prerelease) Text("Pre-release", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                if (release.tag_name.isNotBlank()) Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) { Text(release.tag_name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary) }
            }
            release.assets.filter { it.name.endsWith(".apk") }.forEach { asset ->
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Column { Text(asset.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis); if (asset.size > 0) Text(formatSize(asset.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
                    }
                    when (installState) { is InstallState.Downloading -> { CircularProgressIndicator(progress = { installState.progress }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }; is InstallState.Installing -> { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }; else -> { FilledTonalButton(onClick = { viewModel.downloadAndInstall(repoId, asset.browser_download_url, asset.name) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Icon(Icons.Default.InstallMobile, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Install", style = MaterialTheme.typography.labelSmall) } } }
                }
            }
        }
    }
}

@Composable
fun ImportBackupDialog(viewModel: LauncherViewModel) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = { viewModel.showBackupImportDialog = false }, title = { Text("Import Lawnchair Backup") },
        text = { Text("Import a .lawnchairbackup file to restore your home screen layout.") },
        confirmButton = { Button(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, "Select Lawnchair backup")); viewModel.showBackupImportDialog = false }) { Text("Select File") } },
        dismissButton = { TextButton(onClick = { viewModel.showBackupImportDialog = false }) { Text("Cancel") } })
}

@Composable
fun AppInfoDialog(viewModel: LauncherViewModel) {
    val pkg = viewModel.appInfoPackage ?: return
    val ctx = LocalContext.current; val pm = ctx.packageManager
    var n by remember { mutableStateOf("") }
    LaunchedEffect(pkg) { try { val ai = pm.getApplicationInfo(pkg, 0); n = pm.getApplicationLabel(ai).toString() } catch (_: Exception) { n = pkg } }
    AlertDialog(onDismissRequest = { viewModel.dismissAppInfo() }, title = { Text(n) }, text = {
        Column { Text("Package: $pkg", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(12.dp)); Button(onClick = { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg"))) } catch (_: Exception) {}; viewModel.dismissAppInfo() }) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("App settings") } }
    }, confirmButton = { TextButton(onClick = { viewModel.dismissAppInfo() }) { Text("Close") } })
}

private fun formatSize(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "${bytes / 1024} KB"; else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB" }
