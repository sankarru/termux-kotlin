package com.termux.app.compose

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.termux.app.TermuxActivity
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.extrakeys.ExtraKeysView
import com.termux.shared.termux.extrakeys.SpecialButton

class ComposeExtraKeysView(context: Context) : ExtraKeysView(context, null) {
    var stateChangeCounter by mutableStateOf(0)
        private set

    fun notifyStateChanged() {
        stateChangeCounter++
    }

    override fun readSpecialButton(specialButton: SpecialButton?, autoSetInActive: Boolean): Boolean? {
        val activeBefore = getSpecialButtonActive(specialButton)
        val result = super.readSpecialButton(specialButton, autoSetInActive)
        val activeAfter = getSpecialButtonActive(specialButton)
        if (activeBefore != activeAfter) {
            notifyStateChanged()
        }
        return result
    }

    override fun reload(extraKeysInfo: ExtraKeysInfo?, heightPx: Float) {
        super.reload(extraKeysInfo, heightPx)
        notifyStateChanged()
    }

    private fun getSpecialButtonActive(specialButton: SpecialButton?): Boolean {
        if (specialButton == null) return false
        val state = mSpecialButtons?.get(specialButton) ?: return false
        return state.isActive
    }

    fun toggleSpecialButtonActive(buttonKey: String) {
        val specBtn = try { SpecialButton.valueOf(buttonKey) } catch (e: Exception) { null } ?: return
        val state = mSpecialButtons?.get(specBtn) ?: return
        state.isActive = !state.isActive
        if (!state.isActive) {
            state.isLocked = false
        }
        notifyStateChanged()
    }

    fun toggleSpecialButtonLocked(buttonKey: String) {
        val specBtn = try { SpecialButton.valueOf(buttonKey) } catch (e: Exception) { null } ?: return
        val state = mSpecialButtons?.get(specBtn) ?: return
        state.isLocked = !state.isLocked
        state.isActive = state.isLocked
        notifyStateChanged()
    }

    fun isSpecialButtonActive(buttonKey: String): Boolean {
        val specBtn = try { SpecialButton.valueOf(buttonKey) } catch (e: Exception) { null } ?: return false
        val state = mSpecialButtons?.get(specBtn) ?: return false
        return state.isActive
    }

    fun isSpecialButtonLocked(buttonKey: String): Boolean {
        val specBtn = try { SpecialButton.valueOf(buttonKey) } catch (e: Exception) { null } ?: return false
        val state = mSpecialButtons?.get(specBtn) ?: return false
        return state.isLocked
    }
}

@Composable
fun TermuxToolbar(
    activity: TermuxActivity,
    savedTextInput: String?
) {
    // The toolbar is the send-to-terminal text input row only. The j-code-style script editor that
    // used to live here was removed; scripts are managed from the right-side script bar instead.
    LaunchedEffect(Unit) {
        activity.setToolbarPage(1)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        TextInputPage(activity, savedTextInput)
    }
}

@Composable
fun TextInputPage(
    activity: TermuxActivity,
    savedTextInput: String?
) {
    var text by remember { mutableStateOf(savedTextInput ?: "") }
    val focusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                activity.mToolbarTextInput = it
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text("Send to terminal...") },
            singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    val session = activity.currentSession
                    if (session != null) {
                        if (session.isRunning) {
                            val textToSend = if (text.isEmpty()) "\r" else text
                            session.write(textToSend)
                        } else {
                            activity.termuxTerminalSessionClient.removeFinishedSession(session)
                        }
                        text = ""
                        activity.mToolbarTextInput = ""
                    }
                }
            )
        )

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(
            onClick = {
                val session = activity.currentSession
                if (session != null) {
                    if (session.isRunning) {
                        val textToSend = if (text.isEmpty()) "\r" else text
                        session.write(textToSend)
                    } else {
                        activity.termuxTerminalSessionClient.removeFinishedSession(session)
                    }
                    text = ""
                    activity.mToolbarTextInput = ""
                }
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = "Send",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}
