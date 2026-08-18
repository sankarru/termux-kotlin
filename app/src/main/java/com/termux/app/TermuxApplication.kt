package com.termux.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.termux.BuildConfig
import com.termux.shared.errors.Error
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxBootstrap
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.crash.TermuxCrashUtils
import com.termux.shared.termux.file.TermuxFileUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.shared.termux.shell.am.TermuxAmSocketServer
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.termux.theme.TermuxThemeUtils

class TermuxApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val context = applicationContext

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this)

        // Set log config for the app
        setLogConfig(context)

        Logger.logDebug("Starting Application")

        // Set TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER and TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT
        TermuxBootstrap.setTermuxPackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT)

        // Init app wide SharedProperties loaded from termux.properties
        val properties = TermuxAppSharedProperties.init(context)

        // Apply fullscreen to every activity (settings, help, terminal, ...) so the `fullscreen`
        // termux.properties option takes effect throughout the whole app, not just the terminal.
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyFullScreen(activity)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                // Re-apply so bars hidden by enableEdgeToEdge()/system UI don't reappear
                applyFullScreen(activity)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Init app wide shell manager
        val shellManager = TermuxShellManager.init(context)

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.nightMode)

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        val error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true)
        val isTermuxFilesDirectoryAccessible = error == null
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible")

            val appDirError = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true)
            if (appDirError != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n$appDirError")
                return
            }

            // Setup termux-am-socket server
            TermuxAmSocketServer.setupTermuxAmSocketServer(context)
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n$error")
        }

        // Init TermuxShellEnvironment constants and caches after everything has been setup including termux-am-socket server
        TermuxShellEnvironment.init(this)

        if (isTermuxFilesDirectoryAccessible) {
            TermuxShellEnvironment.writeEnvironmentToFile(this)
        }
    }

    companion object {
        private const val LOG_TAG = "TermuxApplication"

        @JvmStatic
        fun setLogConfig(context: Context) {
            Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME)

            // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
            val preferences = TermuxAppSharedPreferences.build(context) ?: return
            Logger.setLogLevel(null, preferences.getLogLevel())
        }

        /**
         * Hide the status bar and navigation bar on the given activity if the `fullscreen`
         * termux.properties option is enabled.
         */
        @JvmStatic
        fun applyFullScreen(activity: Activity) {
            try {
                val properties = TermuxAppSharedProperties.getProperties()
                if (properties == null || !properties.isUsingFullScreen()) return
                val window = activity.window
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                // Opt into the display cutout area (e.g. camera hole) so the window is not
                // letterboxed below it.
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    window.attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                // Draw content edge-to-edge so it extends behind the (hidden) system bars.
                WindowCompat.setDecorFitsSystemWindows(window, false)
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (e: Exception) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply fullscreen", e)
            }
        }
    }
}
