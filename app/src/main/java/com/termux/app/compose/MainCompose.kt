package com.termux.app.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.TermuxActivity
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import kotlinx.coroutines.launch

class MainScreenStateHolder {
    val sessions = mutableStateListOf<TermuxSession>()
    var currentSession by mutableStateOf<TermuxSession?>(null)
}

@Composable
fun TermuxMainScreen(
    activity: TermuxActivity,
    savedTextInput: String?,
    stateHolder: MainScreenStateHolder,
    contextMenuStateHolder: ContextMenuStateHolder,
    terminalSelectionMenuStateHolder: TerminalSelectionMenuStateHolder,
    scriptBarStateHolder: ScriptBarStateHolder,
    quickCommandsStateHolder: QuickCommandsStateHolder,
    letterPanelStateHolder: LetterPanelStateHolder,
    taskManagerStateHolder: TaskManagerStateHolder,
    fileManagerStateHolder: FileManagerStateHolder
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // Bind drawer control lambdas to Java activity
    DisposableEffect(Unit) {
        activity.mDrawerOpenRunnable = Runnable {
            // Mutual exclusion: only one side panel at a time.
            scriptBarStateHolder.isOpen = false
            coroutineScope.launch { drawerState.open() }
        }
        activity.mDrawerCloseRunnable = Runnable {
            coroutineScope.launch { drawerState.close() }
        }
        activity.mDrawerIsOpenCheck = java.util.concurrent.Callable {
            drawerState.isOpen
        }
        onDispose {
            activity.mDrawerOpenRunnable = null
            activity.mDrawerCloseRunnable = null
            activity.mDrawerIsOpenCheck = null
        }
    }

    // Bind script bar control lambdas to Java activity
    DisposableEffect(Unit) {
        activity.mScriptBarOpenRunnable = Runnable {
            // Mutual exclusion: only one side panel at a time.
            coroutineScope.launch { drawerState.close() }
            scriptBarStateHolder.isOpen = true
        }
        activity.mScriptBarCloseRunnable = Runnable {
            scriptBarStateHolder.isOpen = false
        }
        activity.mScriptBarIsOpenCheck = java.util.concurrent.Callable {
            scriptBarStateHolder.isOpen
        }
        onDispose {
            activity.mScriptBarOpenRunnable = null
            activity.mScriptBarCloseRunnable = null
            activity.mScriptBarIsOpenCheck = null
        }
    }

    var gesturesEnabled by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        activity.mDrawerGesturesEnabledSetter = { enabled ->
            gesturesEnabled = enabled
        }
        onDispose {
            activity.mDrawerGesturesEnabledSetter = null
        }
    }

    // While the right-side script bar is open its scrim owns the touch stream; the drawer must not
    // also claim a drag over it (that closed the bar and opened the drawer at once — the "pushing
    // away" conflict). Drawer gestures stay enabled, but the drawer is barred from opening via drag
    // while the script bar is visible; it can still be opened from its drawer button/runnable.
    val drawerGesturesEnabled = gesturesEnabled && !scriptBarStateHolder.isOpen

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerGesturesEnabled,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                drawerTonalElevation = 0.dp,
                modifier = Modifier
                    .width(300.dp)
                    .imePadding()
                    // Keep the drawer clear of the status bar / display cutout in fullscreen.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            ) {
                TermuxDrawerContent(
                    activity = activity,
                    sessions = stateHolder.sessions,
                    currentSession = stateHolder.currentSession,
                    onSessionSelected = { session ->
                        activity.termuxTerminalSessionClient?.setCurrentSession(session.terminalSession)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onSessionRename = { session ->
                        activity.termuxTerminalSessionClient?.renameSession(session.terminalSession)
                    },
                    onSessionKill = { session ->
                        session.terminalSession?.finishIfRunning()
                    },
                    onNewSession = {
                        activity.termuxTerminalSessionClient?.addNewSession(false, null)
                    },
                    onToggleToolbar = {
                        activity.toggleTerminalToolbar()
                    },
                    onShowTerminalActions = {
                        activity.showTerminalActions()
                    },
                    onShowTaskManager = {
                        taskManagerStateHolder.isOpen = true
                    },
                    onShowLetterPanel = {
                        letterPanelStateHolder.configureOpen = true
                    }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = if (activity.properties.isUsingFullScreen()) {
                // Fullscreen: draw edge-to-edge on sides/bottom, but keep the top
                // status-bar + display-cutout (notch) inset so the Compose main screen
                // (drawer, toolbar, session list) is never rendered underneath it.
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
            } else {
                ScaffoldDefaults.contentWindowInsets
            }
        ) { paddingValues ->
            val marginHorizontal = activity.properties.terminalMarginHorizontal.dp
            val marginVertical = activity.properties.terminalMarginVertical.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = marginHorizontal, vertical = marginVertical)
                ) {
                    AndroidView(
                        factory = {
                            activity.terminalView.apply {
                                post { requestFocus() }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    TerminalSelectionMenuOverlay(activity = activity, stateHolder = terminalSelectionMenuStateHolder)
                }
                // Configurable letter row, pinned above the soft keyboard (the Scaffold's
                // imePadding keeps it clear of the IME). Tapping a chip sends raw input to the
                // current session (vim keystrokes, etc.).
                LetterPanelRow(activity = activity, stateHolder = letterPanelStateHolder)
            }
        }

        // Right-side script bar (opened by a left-swipe on the terminal) overlays everything.
        TermuxScriptBar(
            activity = activity,
            stateHolder = scriptBarStateHolder,
            fileManagerStateHolder = fileManagerStateHolder
        )
    }

    // Context Menu Overlay
    ContextMenuOverlay(activity = activity, stateHolder = contextMenuStateHolder)

    // Script editor panel (j-code-style) opened from the script bar.
    ScriptEditorPanel(activity = activity, stateHolder = scriptBarStateHolder)

    // Quick-commands floating panel (opened by double-tapping the terminal). Holds the keyboard
    // toggle that used to live in the left drawer.
    QuickCommandsOverlay(
        activity = activity,
        stateHolder = quickCommandsStateHolder,
        onShowKeyboard = {
            activity.termuxTerminalViewClient?.showSoftKeyboard()
        }
    )

    // Task-manager panel (j-code style, opened from the left drawer).
    TaskManagerOverlay(
        activity = activity,
        sessions = stateHolder.sessions,
        onCloseSession = { session -> session.terminalSession?.finishIfRunning() },
        stateHolder = taskManagerStateHolder
    )

    // Letter-panel configuration dialog (opened from the left drawer).
    LetterPanelConfigDialog(activity = activity, stateHolder = letterPanelStateHolder)
}

fun setMainContent(
    composeView: ComposeView,
    activity: TermuxActivity,
    savedTextInput: String?,
    stateHolder: MainScreenStateHolder,
    contextMenuStateHolder: ContextMenuStateHolder,
    terminalSelectionMenuStateHolder: TerminalSelectionMenuStateHolder,
    scriptBarStateHolder: ScriptBarStateHolder,
    quickCommandsStateHolder: QuickCommandsStateHolder,
    letterPanelStateHolder: LetterPanelStateHolder,
    taskManagerStateHolder: TaskManagerStateHolder,
    fileManagerStateHolder: FileManagerStateHolder
) {
    composeView.setContent {
        TermuxTheme {
            TermuxMainScreen(
                activity,
                savedTextInput,
                stateHolder,
                contextMenuStateHolder,
                terminalSelectionMenuStateHolder,
                scriptBarStateHolder,
                quickCommandsStateHolder,
                letterPanelStateHolder,
                taskManagerStateHolder,
                fileManagerStateHolder
            )
        }
    }
}
