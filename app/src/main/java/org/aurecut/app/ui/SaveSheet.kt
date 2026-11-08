@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.aurecut.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aurecut.app.AppViewModel
import org.aurecut.app.R
import org.aurecut.app.SaveStage
import org.aurecut.app.audio.ExportFormat

/**
 * The save sheet has three states: options (format, bitrate, name), saving (progress) and done (open / share).
 * While a file is being written the sheet cannot be dismissed by swiping or tapping outside; only Cancel stops it.
 */
@Composable
fun SaveSheet(vm: AppViewModel) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { vm.saveStage !is SaveStage.Saving },
    )
    val createDocument = rememberLauncherForActivityResult(CreateAudioDocument()) { uri ->
        if (uri != null) vm.save(uri)
    }

    ModalBottomSheet(onDismissRequest = vm::closeSave, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.save_title), style = MaterialTheme.typography.titleLarge)
            when (val stage = vm.saveStage) {
                is SaveStage.Options -> Options(vm) { createDocument.launch(vm.fileNameForDialog() to vm.saveFormat.mime) }
                is SaveStage.Saving -> Saving(stage.progress, onCancel = vm::cancelSave)
                is SaveStage.Done -> Done(vm, stage)
            }
        }
    }
}

@Composable
private fun Options(vm: AppViewModel, onChooseFolder: () -> Unit) {
    Text(stringResource(R.string.save_format), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.formats.forEach { f ->
            FilterChip(
                selected = vm.saveFormat == f,
                onClick = { vm.selectFormat(f) },
                label = { Text(f.label) },
            )
        }
    }

    if (vm.saveFormat.hasBitrate) {
        Text(stringResource(R.string.save_bitrate), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.saveFormat.bitrates.forEach { kbps ->
                FilterChip(
                    selected = vm.saveBitrate == kbps,
                    onClick = { vm.selectBitrate(kbps) },
                    label = { Text(stringResource(R.string.bitrate_kbps, kbps)) },
                )
            }
        }
    }

    vm.resampleNotice()?.let { (from, to) ->
        Text(
            stringResource(R.string.opus_resample_notice, from, to),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }

    OutlinedTextField(
        value = vm.saveName,
        onValueChange = { vm.saveName = it },
        label = { Text(stringResource(R.string.save_filename)) },
        suffix = { Text("." + vm.saveFormat.extension) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onChooseFolder, modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)) {
        Text(stringResource(R.string.save_choose_folder), style = MaterialTheme.typography.titleMedium)
    }
    Text(
        stringResource(R.string.save_hint_folder),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Saving(progress: Float, onCancel: () -> Unit) {
    Text(stringResource(R.string.save_progress))
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
    )
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.btn_cancel))
    }
}

@Composable
private fun Done(vm: AppViewModel, done: SaveStage.Done) {
    val context = LocalContext.current
    Text(stringResource(R.string.save_done), style = MaterialTheme.typography.titleMedium)
    done.notes.forEach { note ->
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { startExternal(context, vm, openIntent(done.uri, done.format)) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.btn_open)) }
        OutlinedButton(
            onClick = { startExternal(context, vm, shareIntent(done.uri, done.format)) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.btn_share)) }
    }
    TextButton(onClick = vm::closeSave, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.btn_close))
    }
}

private fun openIntent(uri: Uri, format: ExportFormat): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, format.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

private fun shareIntent(uri: Uri, format: ExportFormat): Intent =
    Intent(Intent.ACTION_SEND)
        .setType(format.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // setClipData returns void, so it cannot sit in the middle of the chain
        .apply { clipData = ClipData.newRawUri(null, uri) }

private fun startExternal(context: Context, vm: AppViewModel, intent: Intent) {
    val chooser = Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        vm.showError(R.string.err_no_app)
    }
}
