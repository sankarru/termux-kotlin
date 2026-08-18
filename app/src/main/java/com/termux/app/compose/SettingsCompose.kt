package com.termux.app.compose

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.termux.shared.activities.ReportActivity
import com.termux.shared.android.PackageUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.models.ReportInfo
import com.termux.app.models.UserAction
import com.termux.shared.android.AndroidUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.logger.Logger
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

enum class SettingsScreen {
    MAIN, TERMUX, DEBUGGING, TERMINAL_IO, TERMINAL_VIEW, UI_CUSTOMIZATION, ABOUT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxSettingsScreen(activity: Activity) {
    val context = LocalContext.current
    
    var currentScreen by remember { mutableStateOf(SettingsScreen.MAIN) }

    val hasTermuxApi = remember { TermuxAPIAppSharedPreferences.build(context, false) != null }
    val hasTermuxFloat = remember { TermuxFloatAppSharedPreferences.build(context, false) != null }
    val hasTermuxTasker = remember { TermuxTaskerAppSharedPreferences.build(context, false) != null }
    val hasTermuxWidget = remember { TermuxWidgetAppSharedPreferences.build(context, false) != null }
    
    val showDonate = remember {
        val digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context)
        digest != null && digest != TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST
    }

    BackHandler(enabled = currentScreen != SettingsScreen.MAIN) {
        when (currentScreen) {
            SettingsScreen.TERMUX -> currentScreen = SettingsScreen.MAIN
            SettingsScreen.ABOUT -> currentScreen = SettingsScreen.MAIN
            SettingsScreen.UI_CUSTOMIZATION -> currentScreen = SettingsScreen.TERMUX
            else -> currentScreen = SettingsScreen.TERMUX
        }
    }

    TermuxTheme {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { 
                        when (currentScreen) {
                            SettingsScreen.MAIN -> activity.finish()
                            SettingsScreen.TERMUX -> currentScreen = SettingsScreen.MAIN
                            SettingsScreen.ABOUT -> currentScreen = SettingsScreen.MAIN
                            SettingsScreen.UI_CUSTOMIZATION -> currentScreen = SettingsScreen.TERMUX
                            else -> currentScreen = SettingsScreen.TERMUX
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn() + slideInVertically { it / 2 } togetherWith fadeOut() + slideOutVertically { -it / 2 }
                        },
                        label = "title",
                        modifier = Modifier.weight(1f)
                    ) { screen ->
                        Text(
                            text = when (screen) {
                                SettingsScreen.MAIN -> "Settings"
                                SettingsScreen.TERMUX -> "Termux Settings"
                                SettingsScreen.DEBUGGING -> "Debugging"
                                SettingsScreen.TERMINAL_IO -> "Terminal IO"
                                SettingsScreen.TERMINAL_VIEW -> "Terminal View"
                                SettingsScreen.UI_CUSTOMIZATION -> "UI Customization"
                                SettingsScreen.ABOUT -> "About Termux"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        ) { paddingValues ->
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    val slideDirection = if (targetState == SettingsScreen.TERMUX) 1 else -1
                    slideInHorizontally(
                        initialOffsetX = { it * slideDirection },
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
                    ) + fadeIn() togetherWith slideOutHorizontally(
                        targetOffsetX = { -it * slideDirection },
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
                    ) + fadeOut()
                },
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
                label = "screen_transition"
            ) { screen ->
                when (screen) {
                    SettingsScreen.MAIN -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
                        ) {
                            item {
                                SettingsActionItem(
                                    title = "Termux",
                                    summary = "Appearance, behavior, terminal setup",
                                    icon = Icons.Rounded.Terminal,
                                    onClick = { currentScreen = SettingsScreen.TERMUX }
                                )
                            }
                            
                            if (hasTermuxApi || hasTermuxFloat || hasTermuxTasker || hasTermuxWidget) {
                                item {
                                    Text(
                                        text = "Plugins",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }

                                if (hasTermuxApi) {
                                    item {
                                        SettingsActionItem(
                                            title = "Termux:API",
                                            summary = "API access configuration",
                                            icon = Icons.Rounded.Extension,
                                            onClick = { /* TODO */ }
                                        )
                                    }
                                }
                                if (hasTermuxFloat) {
                                    item {
                                        SettingsActionItem(
                                            title = "Termux:Float",
                                            summary = "Floating window configuration",
                                            icon = Icons.Rounded.PictureInPicture,
                                            onClick = { /* TODO */ }
                                        )
                                    }
                                }
                                if (hasTermuxTasker) {
                                    item {
                                        SettingsActionItem(
                                            title = "Termux:Tasker",
                                            summary = "Tasker integration",
                                            icon = Icons.Rounded.Task,
                                            onClick = { /* TODO */ }
                                        )
                                    }
                                }
                                if (hasTermuxWidget) {
                                    item {
                                        SettingsActionItem(
                                            title = "Termux:Widget",
                                            summary = "Widget configuration",
                                            icon = Icons.Rounded.Widgets,
                                            onClick = { /* TODO */ }
                                        )
                                    }
                                }
                            }

                            item {
                                Text(
                                    text = "About",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                                SettingsActionItem(
                                    title = "About Termux",
                                    summary = "App info and device details",
                                    icon = Icons.Rounded.Info,
                                    onClick = { currentScreen = SettingsScreen.ABOUT }
                                )
                            }

                            if (showDonate) {
                                item {
                                    SettingsActionItem(
                                        title = "Donate",
                                        summary = "Support the development",
                                        icon = Icons.Rounded.Favorite,
                                        onClick = { ShareUtils.openUrl(context, TermuxConstants.TERMUX_DONATE_URL) }
                                    )
                                }
                            }
                        }
                    }
                    SettingsScreen.TERMUX -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
                        ) {
                            item {
                                SettingsActionItem(
                                    title = "Debugging",
                                    summary = "Logging, key logging, and notifications",
                                    icon = Icons.Rounded.BugReport,
                                    onClick = { currentScreen = SettingsScreen.DEBUGGING }
                                )
                            }
                            item {
                                SettingsActionItem(
                                    title = "Terminal IO",
                                    summary = "Soft keyboard behavior and input",
                                    icon = Icons.Rounded.Keyboard,
                                    onClick = { currentScreen = SettingsScreen.TERMINAL_IO }
                                )
                            }
                            item {
                                SettingsActionItem(
                                    title = "Terminal View",
                                    summary = "Colors, margins, scaling",
                                    icon = Icons.Rounded.Visibility,
                                    onClick = { currentScreen = SettingsScreen.TERMINAL_VIEW }
                                )
                            }
                            item {
                                SettingsActionItem(
                                    title = "UI Customization",
                                    summary = "Colors, fonts, styling",
                                    icon = Icons.Rounded.Palette,
                                    onClick = { currentScreen = SettingsScreen.UI_CUSTOMIZATION }
                                )
                            }
                        }
                    }
                    SettingsScreen.DEBUGGING -> {
                        DebuggingSettingsScreen(context)
                    }
                    SettingsScreen.TERMINAL_IO -> {
                        TerminalIOSettingsScreen(context)
                    }
                    SettingsScreen.TERMINAL_VIEW -> {
                        TerminalViewSettingsScreen(context)
                    }
                    SettingsScreen.UI_CUSTOMIZATION -> {
                        UICustomizationSettingsScreen(context)
                    }
                    SettingsScreen.ABOUT -> {
                        AboutSettingsScreen(context)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsActionItem(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

private fun showAboutReport(context: Context) {
    Thread {
        val title = "About"
        val aboutString = java.lang.StringBuilder()
        aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES))
        aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true))
        aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context))

        val userActionName = UserAction.ABOUT.actionName

        val reportInfo = ReportInfo(
            userActionName,
            TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title
        )
        reportInfo.reportString = aboutString.toString()
        reportInfo.setReportSaveFileLabelAndPath(
            userActionName,
            Environment.getExternalStorageDirectory().toString() + "/" +
                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true)
        )

        ReportActivity.startReportActivity(context, reportInfo)
    }.start()
}

@Composable
fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                content = content
            )
        }
    }
}

@Composable
fun SettingSwitchTile(
    title: String,
    summaryOn: String,
    summaryOff: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text = if (checked) summaryOn else summaryOff,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onCheckedChange == null) it else it.clickable { onCheckedChange(!checked) } }
            .padding(vertical = 4.dp)
    )
}

@Composable
fun SettingListTile(
    title: String,
    selectedValueLabel: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text = selectedValueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    )
}

@Composable
fun DebuggingSettingsScreen(context: Context) {
    val preferences = remember { TermuxAppSharedPreferences.build(context, true) } ?: return
    
    var logLevel by remember { mutableStateOf(preferences.getLogLevel()) }
    var keyLoggingEnabled by remember { mutableStateOf(preferences.isTerminalViewKeyLoggingEnabled()) }
    var pluginErrorsEnabled by remember { mutableStateOf(preferences.arePluginErrorNotificationsEnabled(false)) }
    var crashReportsEnabled by remember { mutableStateOf(preferences.areCrashReportNotificationsEnabled(false)) }
    
    var showLogLevelDialog by remember { mutableStateOf(false) }
    
    val logLevels = remember { Logger.getLogLevelsArray() }
    val logLevelLabels = remember { Logger.getLogLevelLabelsArray(context, logLevels, true)!! }
    val currentLogLevelLabel = remember(logLevel) {
        val index = logLevels.indexOf(logLevel.toString())
        if (index >= 0 && index < logLevelLabels.size) logLevelLabels[index].toString() else "Unknown"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            SettingsGroup(title = stringResource(com.termux.R.string.termux_logging_header)) {
                SettingListTile(
                    title = stringResource(com.termux.R.string.termux_log_level_title),
                    selectedValueLabel = currentLogLevelLabel,
                    onClick = { showLogLevelDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingSwitchTile(
                    title = stringResource(com.termux.R.string.termux_terminal_view_key_logging_enabled_title),
                    summaryOn = stringResource(com.termux.R.string.termux_terminal_view_key_logging_enabled_on),
                    summaryOff = stringResource(com.termux.R.string.termux_terminal_view_key_logging_enabled_off),
                    checked = keyLoggingEnabled,
                    onCheckedChange = { newValue ->
                        preferences.setTerminalViewKeyLoggingEnabled(newValue)
                        keyLoggingEnabled = newValue
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingSwitchTile(
                    title = stringResource(com.termux.R.string.termux_plugin_error_notifications_enabled_title),
                    summaryOn = stringResource(com.termux.R.string.termux_plugin_error_notifications_enabled_on),
                    summaryOff = stringResource(com.termux.R.string.termux_plugin_error_notifications_enabled_off),
                    checked = pluginErrorsEnabled,
                    onCheckedChange = { newValue ->
                        preferences.setPluginErrorNotificationsEnabled(newValue)
                        pluginErrorsEnabled = newValue
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingSwitchTile(
                    title = stringResource(com.termux.R.string.termux_crash_report_notifications_enabled_title),
                    summaryOn = stringResource(com.termux.R.string.termux_crash_report_notifications_enabled_on),
                    summaryOff = stringResource(com.termux.R.string.termux_crash_report_notifications_enabled_off),
                    checked = crashReportsEnabled,
                    onCheckedChange = { newValue ->
                        preferences.setCrashReportNotificationsEnabled(newValue)
                        crashReportsEnabled = newValue
                    }
                )
            }
        }
    }

    if (showLogLevelDialog) {
        AlertDialog(
            onDismissRequest = { showLogLevelDialog = false },
            title = {
                Text(
                    text = stringResource(com.termux.R.string.termux_log_level_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    logLevels.forEachIndexed { index, valueStr ->
                        val label = logLevelLabels[index].toString()
                        val isSelected = valueStr.toString() == logLevel.toString()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newLevel = Integer.parseInt(valueStr.toString())
                                    preferences.setLogLevel(context, newLevel)
                                    logLevel = newLevel
                                    showLogLevelDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    val newLevel = Integer.parseInt(valueStr.toString())
                                    preferences.setLogLevel(context, newLevel)
                                    logLevel = newLevel
                                    showLogLevelDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogLevelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TerminalIOSettingsScreen(context: Context) {
    val preferences = remember { TermuxAppSharedPreferences.build(context, true) } ?: return
    
    var softKeyboardEnabled by remember { mutableStateOf(preferences.isSoftKeyboardEnabled()) }
    var softKeyboardOnlyNoHardware by remember { mutableStateOf(preferences.isSoftKeyboardEnabledOnlyIfNoHardware()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            SettingsGroup(title = stringResource(com.termux.R.string.termux_keyboard_header)) {
                SettingSwitchTile(
                    title = stringResource(com.termux.R.string.termux_soft_keyboard_enabled_title),
                    summaryOn = stringResource(com.termux.R.string.termux_soft_keyboard_enabled_on),
                    summaryOff = stringResource(com.termux.R.string.termux_soft_keyboard_enabled_off),
                    checked = softKeyboardEnabled,
                    onCheckedChange = { newValue ->
                        preferences.setSoftKeyboardEnabled(newValue)
                        softKeyboardEnabled = newValue
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingSwitchTile(
                    title = stringResource(com.termux.R.string.termux_soft_keyboard_enabled_only_if_no_hardware_title),
                    summaryOn = stringResource(com.termux.R.string.termux_soft_keyboard_enabled_only_if_no_hardware_on),
                    summaryOff = stringResource(com.termux.R.string.termux_soft_keyboard_enabled_only_if_no_hardware_off),
                    checked = softKeyboardOnlyNoHardware,
                    onCheckedChange = { newValue ->
                        preferences.setSoftKeyboardEnabledOnlyIfNoHardware(newValue)
                        softKeyboardOnlyNoHardware = newValue
                    }
                )
            }
        }
    }
}

@Composable
fun TerminalViewSettingsScreen(context: Context) {
    val preferences = remember { TermuxAppSharedPreferences.build(context, true) } ?: return

    var marginAdjustment by remember { mutableStateOf(preferences.isTerminalMarginAdjustmentEnabled()) }
    var wallpaperApplied by remember { mutableStateOf(false) }

    val wallpaperPath = TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/background-image"
    val wallpaperDisplayPath = "~/.termux/background-image"

    val wallpaperPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = context.contentResolver.openInputStream(uri)
        if (result == null) {
            Toast.makeText(context, "Unable to read the selected image", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        try {
            result.use { input ->
                val outFile = java.io.File(wallpaperPath)
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            val properties = TermuxAppSharedProperties.getProperties()
            if (properties == null) {
                Toast.makeText(context, "Properties not initialised", Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            if (properties.updatePropertyValueInPrimaryFile(
                    TermuxPropertyConstants.KEY_TERMINAL_BACKGROUND_IMAGE, wallpaperDisplayPath
                )
            ) {
                wallpaperApplied = true
                Toast.makeText(context, "Wallpaper set", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to save wallpaper setting", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage("Settings", "Failed to set wallpaper", e)
            Toast.makeText(context, "Failed to set wallpaper: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            SettingsGroup(title = stringResource(com.termux.R.string.termux_terminal_view_view_header)) {
                SettingSwitchTile(
                    title = stringResource(com.termux.R.string.termux_terminal_view_terminal_margin_adjustment_title),
                    summaryOn = stringResource(com.termux.R.string.termux_terminal_view_terminal_margin_adjustment_on),
                    summaryOff = stringResource(com.termux.R.string.termux_terminal_view_terminal_margin_adjustment_off),
                    checked = marginAdjustment,
                    onCheckedChange = { newValue ->
                        preferences.setTerminalMarginAdjustment(newValue)
                        marginAdjustment = newValue
                    }
                )
            }
        }

        item {
            SettingsGroup(title = "Wallpaper") {
                SettingListTile(
                    title = "Set Wallpaper",
                    selectedValueLabel = if (wallpaperApplied) "Wallpaper set to " + wallpaperDisplayPath else
                        "Choose an image to show behind the terminal",
                    onClick = { wallpaperPickerLauncher.launch(arrayOf("image/*")) }
                )
                SettingListTile(
                    title = "Clear Wallpaper",
                    selectedValueLabel = "Remove the terminal background image",
                    onClick = {
                        val properties = TermuxAppSharedProperties.getProperties()
                        if (properties != null && properties.updatePropertyValueInPrimaryFile(
                                TermuxPropertyConstants.KEY_TERMINAL_BACKGROUND_IMAGE, null
                            )
                        ) {
                            wallpaperApplied = false
                            Toast.makeText(context, "Wallpaper cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AboutSettingsScreen(context: Context) {
    val appInfo = remember {
        try {
            TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES).orEmpty()
                .replace("### ", "")
                .replace("## ", "")
                .replace("* ", "• ")
        } catch (e: Exception) {
            "Unable to load app info"
        }
    }
    
    val deviceInfo = remember {
        try {
            AndroidUtils.getDeviceInfoMarkdownString(context, true)
                .replace("### ", "")
                .replace("## ", "")
                .replace("* ", "• ")
        } catch (e: Exception) {
            "Unable to load device info"
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Termux",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                val packageVersion = remember {
                    try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    } catch (e: Exception) {
                        "v0.118.0"
                    }
                }
                Text(
                    text = "Version $packageVersion",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingsGroup(title = "App Diagnostics") {
                Text(
                    text = appInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        item {
            SettingsGroup(title = "Device Info") {
                Text(
                    text = deviceInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        item {
            SettingsGroup(title = "Community & Links") {
                SettingListTile(
                    title = "GitHub Repository",
                    selectedValueLabel = "Source code and issue tracker",
                    onClick = { ShareUtils.openUrl(context, "https://github.com/termux/termux-app") }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingListTile(
                    title = "Termux Wiki",
                    selectedValueLabel = "Guides, tips, and documentation",
                    onClick = { ShareUtils.openUrl(context, "https://wiki.termux.com") }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingListTile(
                    title = "Reddit Community",
                    selectedValueLabel = "Discussion and support on r/termux",
                    onClick = { ShareUtils.openUrl(context, "https://www.reddit.com/r/termux") }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingListTile(
                    title = "Email Support",
                    selectedValueLabel = "support@termux.dev",
                    onClick = { ShareUtils.openUrl(context, "mailto:support@termux.dev") }
                )
            }
        }
    }
}

@Composable
fun UICustomizationSettingsScreen(context: Context) {
    val prefs = remember { TermuxAppSharedPreferences.build(context, true)?.sharedPreferences } ?: return

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            SettingsGroup(title = "Style") {
                SettingSwitchTile(
                    title = "Use Termux (j-code) Colors",
                    summaryOn = "All UI parts use colors from your Termux theme (.termux/colors.properties), matching the terminal.",
                    summaryOff = "All UI parts use colors from your Termux theme (.termux/colors.properties), matching the terminal.",
                    checked = true,
                    onCheckedChange = null
                )
                SettingListTile(
                    title = "UI Font",
                    selectedValueLabel = "Terminal monospace (matches the terminal)",
                    onClick = {}
                )
            }
        }
    }
}