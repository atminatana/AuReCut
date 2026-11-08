package org.aurecut.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import org.aurecut.app.core.TimeFormat

/**
 * A time input that shows "1:23.456" and applies what the user typed when they press Done or leave the field.
 * Text that is not a time is thrown away and the previous value comes back.
 */
@Composable
fun TimeField(label: String, seconds: Double, onCommit: (Double) -> Unit, modifier: Modifier = Modifier) {
    val shown = TimeFormat.format(seconds)
    var text by remember(shown) { mutableStateOf(shown) }
    fun commit() {
        if (text == shown) return
        val parsed = TimeFormat.parse(text)
        if (parsed != null) onCommit(parsed)
        text = shown // the real value (possibly clamped by the editor) returns with the next recomposition
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
    )
}

/** Same idea for a level in dB; accepts "-1", "-1,5" and the typographic minus. */
@Composable
fun DbField(label: String, valueDb: Double, enabled: Boolean, onCommit: (Double) -> Unit, modifier: Modifier = Modifier) {
    val shown = formatDb(valueDb)
    var text by remember(shown) { mutableStateOf(shown) }
    fun commit() {
        if (text == shown) return
        val parsed = parseDb(text)
        if (parsed != null) onCommit(parsed)
        text = shown
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        enabled = enabled,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
    )
}
