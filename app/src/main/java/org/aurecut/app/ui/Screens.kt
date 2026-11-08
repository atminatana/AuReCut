@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.aurecut.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aurecut.app.AppViewModel
import org.aurecut.app.R
import org.aurecut.app.audio.RecSource
import org.aurecut.app.audio.RecState
import org.aurecut.app.audio.RecordingController
import org.aurecut.app.core.TimeFormat

@Composable
fun StartScreen(onOpenFile: () -> Unit, onRecord: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onOpenFile,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .height(72.dp),
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.start_open_file), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = onRecord,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .height(72.dp),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.start_record), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@StringRes
private fun sourceLabel(s: RecSource): Int = when (s) {
    RecSource.MIC -> R.string.source_mic
    RecSource.UNPROCESSED -> R.string.source_unprocessed
    RecSource.VOICE_RECOGNITION -> R.string.source_voice_recognition
    RecSource.VOICE_COMMUNICATION -> R.string.source_voice_communication
}

@Composable
fun RecordScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val state by RecordingController.state.collectAsStateWithLifecycle()
    val active = state as? RecState.Active

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The notification permission is optional, only the microphone decides.
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startRecording()
        } else {
            vm.onMicDenied()
        }
    }

    fun requestAndStart() {
        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.startRecording() else permissions.launch(missing.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.record_title)) },
                navigationIcon = {
                    if (active == null) {
                        IconButton(onClick = vm::goStart) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active == null) {
                RecordOptions(vm)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { requestAndStart() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.record_start), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                RecordingInProgress(active)
            }
        }
    }
}

@Composable
private fun RecordOptions(vm: AppViewModel) {
    val caps = vm.caps

    Text(stringResource(R.string.record_source), style = MaterialTheme.typography.titleSmall)
    caps.sources.forEach { src ->
        val selected = vm.recSource == src
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = { vm.selectSource(src) }, role = Role.RadioButton)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(sourceLabel(src)))
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.record_channels), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = vm.recChannels == 1,
            onClick = { vm.selectChannels(1) },
            label = { Text(stringResource(R.string.channels_mono)) },
        )
        FilterChip(
            selected = vm.recChannels == 2,
            onClick = { vm.selectChannels(2) },
            enabled = caps.stereoAvailable,
            label = { Text(stringResource(R.string.channels_stereo)) },
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.record_rate), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        caps.ratesFor(vm.recChannels).forEach { rate ->
            FilterChip(
                selected = vm.recRate == rate,
                onClick = { vm.selectRate(rate) },
                label = { Text(stringResource(R.string.rate_khz, formatKhz(rate))) },
            )
        }
    }
}

@Composable
private fun RecordingInProgress(active: RecState.Active) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(TimeFormat.format(active.elapsedMs / 1000.0, 1), style = MaterialTheme.typography.displayMedium)
        LinearProgressIndicator(
            progress = { active.level.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { if (active.paused) RecordingController.resume() else RecordingController.pause() }) {
                Icon(if (active.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (active.paused) R.string.record_resume else R.string.record_pause))
            }
            Button(onClick = { RecordingController.stop() }) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.record_stop))
            }
        }
        Text(
            stringResource(R.string.record_active_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
