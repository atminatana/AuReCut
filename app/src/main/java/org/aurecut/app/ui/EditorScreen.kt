@file:OptIn(ExperimentalMaterial3Api::class)

package org.aurecut.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aurecut.app.AppViewModel
import org.aurecut.app.EditorDoc
import org.aurecut.app.R
import org.aurecut.app.core.TimeFormat

@Composable
fun EditorScreen(vm: AppViewModel) {
    val doc = vm.doc ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            TimeFormat.format(doc.total.toDouble() / doc.sampleRate, 1),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::onEditorBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::openSave) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.btn_save))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (maxWidth > maxHeight) {
                // landscape: the waveform takes all the width it can, the controls sit in a column on the right
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WaveArea(vm, doc, Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier
                            .width(340.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Controls(vm, doc)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WaveArea(vm, doc, Modifier.weight(1f).fillMaxWidth())
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Controls(vm, doc)
                    }
                }
            }
        }
    }

    if (vm.showExitConfirm) {
        AlertDialog(
            onDismissRequest = vm::dismissExitConfirm,
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_message)) },
            confirmButton = { TextButton(onClick = vm::leaveEditor) { Text(stringResource(R.string.exit_discard)) } },
            dismissButton = { TextButton(onClick = vm::dismissExitConfirm) { Text(stringResource(R.string.exit_stay)) } },
        )
    }

    if (vm.saveOpen) SaveSheet(vm)
}

/** The waveform, the zoom buttons and the overview strip. */
@Composable
private fun WaveArea(vm: AppViewModel, doc: EditorDoc, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WaveformView(
            doc = doc,
            view = vm.view,
            selStart = vm.selStart,
            selEnd = vm.selEnd,
            playFrame = vm.playFrame,
            onView = vm::changeView,
            onMoveStart = vm::moveStart,
            onMoveEnd = vm::moveEnd,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        OverviewStrip(
            doc = doc,
            view = vm.view,
            selStart = vm.selStart,
            selEnd = vm.selEnd,
            onView = vm::changeView,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            FilledTonalIconButton(onClick = { vm.zoomBy(0.5) }) {
                Icon(Icons.Filled.ZoomOut, contentDescription = stringResource(R.string.zoom_out))
            }
            FilledTonalIconButton(onClick = { vm.zoomBy(2.0) }) {
                Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.zoom_in))
            }
            FilledTonalIconButton(onClick = vm::zoomToSelection) {
                Icon(Icons.Filled.FitScreen, contentDescription = stringResource(R.string.zoom_selection))
            }
            FilledTonalIconButton(onClick = vm::zoomAll) {
                Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.zoom_all))
            }
        }
    }
}

@Composable
private fun Controls(vm: AppViewModel, doc: EditorDoc) {
    val rate = doc.sampleRate.toDouble()
    val start = vm.selStart / rate
    val end = vm.selEnd / rate
    val length = (vm.selEnd - vm.selStart) / rate

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeField(stringResource(R.string.label_start), start, vm::setStartSeconds, Modifier.weight(1f))
        TimeField(stringResource(R.string.label_end), end, vm::setEndSeconds, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TimeField(stringResource(R.string.label_length), length, vm::setLengthSeconds, Modifier.weight(1f))
        FilledTonalButton(onClick = vm::togglePlay, modifier = Modifier.weight(1f).height(56.dp)) {
            Icon(if (vm.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (vm.playing) R.string.btn_stop_play else R.string.btn_play))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.normalize, onCheckedChange = vm::changeNormalize)
        Text(stringResource(R.string.normalize), modifier = Modifier.weight(1f))
        DbField(
            label = stringResource(R.string.normalize_level),
            valueDb = vm.targetDb,
            enabled = vm.normalize,
            onCommit = vm::changeTargetDb,
            modifier = Modifier.weight(1.4f),
        )
    }
    Button(onClick = vm::openSave, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Icon(Icons.Filled.Save, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.btn_save), style = MaterialTheme.typography.titleMedium)
    }
}
