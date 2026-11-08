package org.aurecut.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aurecut.app.audio.RecState
import org.aurecut.app.audio.RecordingController
import org.aurecut.app.ui.EditorScreen
import org.aurecut.app.ui.RecordScreen
import org.aurecut.app.ui.StartScreen

/** Which screen is shown, the system Back behavior of each one, and the dialogs that can appear over any of them. */
@Composable
fun AuReCutApp(vm: AppViewModel, onMoveToBack: () -> Unit) {
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openFile(uri)
    }
    val recState by RecordingController.state.collectAsStateWithLifecycle()
    val recording = recState is RecState.Active

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (vm.screen) {
            Screen.START -> StartScreen(
                onOpenFile = { openDocument.launch(arrayOf("audio/*", "application/ogg")) },
                onRecord = vm::openRecordScreen,
            )
            Screen.RECORD -> RecordScreen(vm)
            Screen.EDITOR -> EditorScreen(vm)
        }
    }

    // On the start screen Back is left to the system. While recording, Back must not throw the recording away:
    // it only sends the app to the background and the foreground service keeps recording.
    BackHandler(enabled = vm.screen == Screen.RECORD) {
        if (recording) onMoveToBack() else vm.goStart()
    }
    BackHandler(enabled = vm.screen == Screen.EDITOR) {
        vm.onEditorBack()
    }

    vm.busy?.let { busy ->
        val progress = busy.progress
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(busy.label)) },
            text = {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = vm::cancelBusy) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }

    vm.errorText?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.btn_ok)) } },
        )
    }
}
