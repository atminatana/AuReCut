package org.aurecut.app

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aurecut.app.audio.AudioCaps
import org.aurecut.app.audio.AudioDecoder
import org.aurecut.app.audio.DecodeError
import org.aurecut.app.audio.DecodeException
import org.aurecut.app.audio.ExportError
import org.aurecut.app.audio.ExportException
import org.aurecut.app.audio.ExportFormat
import org.aurecut.app.audio.ExportRequest
import org.aurecut.app.audio.ExportResult
import org.aurecut.app.audio.Exporter
import org.aurecut.app.audio.OperationCancelled
import org.aurecut.app.audio.RangePlayer
import org.aurecut.app.audio.RecConfig
import org.aurecut.app.audio.RecFailure
import org.aurecut.app.audio.RecSource
import org.aurecut.app.audio.RecState
import org.aurecut.app.audio.RecordingController
import org.aurecut.app.audio.WorkDir
import org.aurecut.app.core.Normalizer
import org.aurecut.app.core.PcmClip
import org.aurecut.app.core.PeakPyramid
import org.aurecut.app.core.ViewWindow
import org.aurecut.app.ui.formatDb
import org.aurecut.app.ui.formatKhz
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

enum class Screen { START, RECORD, EDITOR }

/** A modal "please wait" state: [progress] is 0..1, or null when the length of the wait is unknown. */
data class Busy(@StringRes val label: Int, val progress: Float?)

sealed interface SaveStage {
    data object Options : SaveStage
    data class Saving(val progress: Float) : SaveStage
    data class Done(val uri: Uri, val format: ExportFormat, val notes: List<String>) : SaveStage
}

/** The clip being edited and everything derived from it once, so the UI never touches the file directly. */
class EditorDoc(val clip: PcmClip, val name: String, val peaks: PeakPyramid, val fromRecording: Boolean) {
    val total: Long = clip.frames
    val sampleRate: Int get() = clip.sampleRate
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()

    var screen by mutableStateOf(Screen.START)
        private set
    var busy by mutableStateOf<Busy?>(null)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set

    // ---- recording setup ----
    val caps = AudioCaps.recording(application)
    var recSource by mutableStateOf(RecSource.MIC)
        private set
    var recRate by mutableIntStateOf(AudioCaps.DEFAULT_RATE)
        private set
    var recChannels by mutableIntStateOf(1)
        private set

    // ---- editor ----
    var doc by mutableStateOf<EditorDoc?>(null)
        private set
    var selStart by mutableLongStateOf(0L)
        private set
    var selEnd by mutableLongStateOf(0L)
        private set
    var view by mutableStateOf(ViewWindow.full(1))
        private set
    var normalize by mutableStateOf(false)
        private set
    var targetDb by mutableDoubleStateOf(Normalizer.DEFAULT_TARGET_DB)
        private set
    var playing by mutableStateOf(false)
        private set
    var playFrame by mutableLongStateOf(-1L)
        private set
    /** True when leaving the editor would lose a recording or changes that were not saved. */
    var dirty by mutableStateOf(false)
        private set
    var showExitConfirm by mutableStateOf(false)
        private set

    // ---- saving ----
    val formats: List<ExportFormat> by lazy { AudioCaps.exportFormats() }
    var saveOpen by mutableStateOf(false)
        private set
    var saveStage by mutableStateOf<SaveStage>(SaveStage.Options)
        private set
    var saveFormat by mutableStateOf(ExportFormat.FLAC)
        private set
    var saveBitrate by mutableIntStateOf(0)
        private set
    var saveName by mutableStateOf("")

    private var player: RangePlayer? = null
    private var openJob: Job? = null
    private var saveJob: Job? = null

    init {
        fixRate()
        viewModelScope.launch {
            RecordingController.state.collect { s ->
                when (s) {
                    is RecState.Active -> if (screen == Screen.START) screen = Screen.RECORD
                    is RecState.Finished -> onRecordingFinished(s)
                    is RecState.Failed -> {
                        RecordingController.acknowledge()
                        showError(recordingMessage(s.reason))
                    }
                    RecState.Idle -> Unit
                }
            }
        }
    }

    // ================= navigation =================

    fun openRecordScreen() {
        if (recSource !in caps.sources) recSource = RecSource.MIC
        if (recChannels == 2 && !caps.stereoAvailable) recChannels = 1
        fixRate()
        screen = Screen.RECORD
    }

    fun goStart() {
        screen = Screen.START
    }

    /** System back in the editor. */
    fun onEditorBack() {
        when {
            saveOpen && saveStage is SaveStage.Saving -> cancelSave()
            saveOpen -> closeSave()
            dirty -> showExitConfirm = true
            else -> leaveEditor()
        }
    }

    fun dismissExitConfirm() {
        showExitConfirm = false
    }

    fun leaveEditor() {
        stopPlay()
        saveJob?.cancel()
        doc = null
        showExitConfirm = false
        saveOpen = false
        clearWorkFiles(emptyList())
        screen = Screen.START
    }

    // ================= recording =================

    fun selectSource(s: RecSource) {
        recSource = s
    }

    fun selectChannels(channels: Int) {
        recChannels = channels
        fixRate()
    }

    fun selectRate(rate: Int) {
        recRate = rate
    }

    private fun fixRate() {
        val rates = caps.ratesFor(recChannels)
        if (recRate !in rates) recRate = if (AudioCaps.DEFAULT_RATE in rates) AudioCaps.DEFAULT_RATE else rates.last()
    }

    fun startRecording() {
        RecordingController.start(app, RecConfig(recSource, recRate, recChannels))
    }

    fun onMicDenied() = showError(R.string.err_mic_denied)

    private fun onRecordingFinished(f: RecState.Finished) {
        RecordingController.acknowledge()
        viewModelScope.launch {
            busy = Busy(R.string.open_progress, 0f)
            try {
                val ctx = currentCoroutineContext()
                val peaks = withContext(Dispatchers.Default) {
                    PeakPyramid.build(f.clip, { busy = Busy(R.string.open_progress, it) }, { !ctx.isActive })
                }
                enterEditor(f.clip, recordingName(), fromRecording = true, peaks)
                f.interrupted?.let { showError(recordingMessage(it)) }
            } finally {
                busy = null
            }
        }
    }

    private fun recordingName(): String =
        app.getString(R.string.recording_name) + " " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())

    // ================= opening files =================

    fun openFile(uri: Uri) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            busy = Busy(R.string.open_progress, 0f)
            val out = WorkDir.newFile(app, "open", "pcm")
            try {
                val ctx = currentCoroutineContext()
                val name = withContext(Dispatchers.IO) { AudioDecoder.displayName(app, uri) }
                val clip = withContext(Dispatchers.IO) {
                    AudioDecoder.decode(app, uri, out, { busy = Busy(R.string.open_progress, it * 0.85f) }, { !ctx.isActive })
                }
                val peaks = withContext(Dispatchers.Default) {
                    PeakPyramid.build(clip, { busy = Busy(R.string.open_progress, 0.85f + it * 0.15f) }, { !ctx.isActive })
                }
                ensureActive()
                enterEditor(clip, name, fromRecording = false, peaks)
            } catch (e: OperationCancelled) {
                out.delete()
            } catch (e: CancellationException) {
                out.delete()
                throw e
            } catch (e: DecodeException) {
                out.delete()
                showError(decodeMessage(e.error))
            } catch (e: Exception) {
                out.delete()
                showError(R.string.err_open_io)
            } finally {
                busy = null
            }
        }
    }

    fun cancelBusy() {
        openJob?.cancel()
    }

    private fun enterEditor(clip: PcmClip, name: String, fromRecording: Boolean, peaks: PeakPyramid) {
        stopPlay()
        player = null
        doc = EditorDoc(clip, name, peaks, fromRecording)
        selStart = 0L
        selEnd = clip.frames
        view = ViewWindow.full(clip.frames)
        normalize = false
        targetDb = Normalizer.DEFAULT_TARGET_DB
        dirty = fromRecording
        saveOpen = false
        saveStage = SaveStage.Options
        clearWorkFiles(listOf(clip))
        screen = Screen.EDITOR
    }

    /** Deletes cached audio we no longer need, but never while a recording is writing its file. */
    private fun clearWorkFiles(keep: List<PcmClip>) {
        if (!RecordingController.isActive) WorkDir.clear(app, keep.map { it.file })
    }

    // ================= selection and view =================

    /** Markers stay at least 1 ms apart (or the whole clip, if it is shorter than that). */
    private fun minGap(d: EditorDoc): Long = minOf(d.total, maxOf(1L, d.sampleRate / 1000L))

    fun moveStart(frame: Long) {
        val d = doc ?: return
        val v = frame.coerceIn(0L, selEnd - minGap(d))
        if (v != selStart) {
            stopPlay()
            selStart = v
            dirty = true
        }
    }

    fun moveEnd(frame: Long) {
        val d = doc ?: return
        val v = frame.coerceIn(selStart + minGap(d), d.total)
        if (v != selEnd) {
            stopPlay()
            selEnd = v
            dirty = true
        }
    }

    fun setStartSeconds(sec: Double) {
        val d = doc ?: return
        moveStart((sec * d.sampleRate).roundToLong())
    }

    fun setEndSeconds(sec: Double) {
        val d = doc ?: return
        moveEnd((sec * d.sampleRate).roundToLong())
    }

    fun setLengthSeconds(sec: Double) {
        val d = doc ?: return
        moveEnd(selStart + (sec * d.sampleRate).roundToLong())
    }

    fun changeView(w: ViewWindow) {
        val d = doc ?: return
        view = w.coerced(d.total)
    }

    fun zoomBy(factor: Double) {
        val d = doc ?: return
        view = view.zoomed(factor, view.start + view.span / 2, d.total)
    }

    fun zoomToSelection() {
        val d = doc ?: return
        view = ViewWindow.around(selStart, selEnd, d.total)
    }

    fun zoomAll() {
        val d = doc ?: return
        view = ViewWindow.full(d.total)
    }

    // ================= normalization and playback =================

    fun changeNormalize(on: Boolean) {
        normalize = on
        dirty = true
    }

    fun changeTargetDb(db: Double) {
        targetDb = db.coerceIn(Normalizer.MIN_TARGET_DB, Normalizer.MAX_TARGET_DB)
        dirty = true
    }

    fun togglePlay() {
        if (playing) stopPlay() else startPlay()
    }

    private fun startPlay() {
        val d = doc ?: return
        val p = player ?: RangePlayer(d.clip).also { player = it }
        playing = true
        playFrame = selStart
        p.play(
            startFrame = selStart,
            endFrame = selEnd,
            normalizeToDb = if (normalize) targetDb else null,
            onPosition = { playFrame = it },
            onEnded = {
                playing = false
                playFrame = -1L
            },
        )
    }

    fun stopPlay() {
        player?.stop()
        playing = false
        playFrame = -1L
    }

    // ================= saving =================

    fun openSave() {
        val d = doc ?: return
        stopPlay()
        if (saveFormat !in formats) saveFormat = formats.first()
        if (saveFormat.hasBitrate && saveBitrate !in saveFormat.bitrates) saveBitrate = saveFormat.defaultBitrate
        saveName = suggestedName(d)
        saveStage = SaveStage.Options
        saveOpen = true
    }

    fun selectFormat(f: ExportFormat) {
        saveFormat = f
        if (f.hasBitrate && saveBitrate !in f.bitrates) saveBitrate = f.defaultBitrate
    }

    fun selectBitrate(kbps: Int) {
        saveBitrate = kbps
    }

    fun closeSave() {
        if (saveStage !is SaveStage.Saving) saveOpen = false
    }

    /** File name for the system "save as" dialog. */
    fun fileNameForDialog(): String = saveName.replace(INVALID_NAME_CHARS, "_").trim().ifEmpty { "AuReCut" } + "." + saveFormat.extension

    /** What the Opus notice should say, or null when the sample rate stays as it is. */
    fun resampleNotice(): Pair<String, String>? {
        val d = doc ?: return null
        val out = saveFormat.outputRate(d.sampleRate)
        return if (out != d.sampleRate) formatKhz(d.sampleRate) to formatKhz(out) else null
    }

    fun save(uri: Uri) {
        val d = doc ?: return
        val request = ExportRequest(d.clip, selStart, selEnd, saveFormat, saveBitrate, normalize, targetDb)
        saveStage = SaveStage.Saving(0f)
        saveJob = viewModelScope.launch {
            val ctx = currentCoroutineContext()
            try {
                val result = withContext(Dispatchers.IO) {
                    Exporter.export(app, request, uri, { saveStage = SaveStage.Saving(it) }, { !ctx.isActive })
                }
                saveStage = SaveStage.Done(uri, request.format, resultNotes(result))
                dirty = false
            } catch (e: OperationCancelled) {
                saveStage = SaveStage.Options
            } catch (e: CancellationException) {
                saveStage = SaveStage.Options
                throw e
            } catch (e: ExportException) {
                saveStage = SaveStage.Options
                showError(exportMessage(e.error))
            } catch (e: Exception) {
                saveStage = SaveStage.Options
                showError(R.string.err_save_failed)
            }
        }
    }

    fun cancelSave() {
        saveJob?.cancel()
    }

    private fun resultNotes(r: ExportResult): List<String> = buildList {
        r.gainDb?.let { add(app.getString(R.string.save_done_gain, formatDb(it, signed = true))) }
        if (r.silentNormalizeSkipped) add(app.getString(R.string.save_done_silent))
        r.resampledFrom?.let { add(app.getString(R.string.save_done_resampled, formatKhz(it), formatKhz(r.sampleRate))) }
    }

    private fun suggestedName(d: EditorDoc): String {
        if (d.fromRecording) return d.name
        return d.name.substringBeforeLast('.', d.name) + "_cut"
    }

    // ================= messages =================

    fun showError(@StringRes id: Int) {
        errorText = app.getString(id)
    }

    fun dismissError() {
        errorText = null
    }

    private fun recordingMessage(r: RecFailure): Int = when (r) {
        RecFailure.PERMISSION -> R.string.err_mic_denied
        RecFailure.UNSUPPORTED -> R.string.err_rec_unsupported
        RecFailure.START -> R.string.err_rec_start
        RecFailure.READ -> R.string.err_rec_read
        RecFailure.DISK -> R.string.err_rec_disk
        RecFailure.EMPTY -> R.string.err_rec_empty
    }

    private fun decodeMessage(e: DecodeError): Int = when (e) {
        DecodeError.NO_AUDIO -> R.string.err_open_no_audio
        DecodeError.UNSUPPORTED -> R.string.err_open_unsupported
        DecodeError.EMPTY -> R.string.err_open_empty
        DecodeError.IO -> R.string.err_open_io
    }

    private fun exportMessage(e: ExportError): Int = when (e) {
        ExportError.EMPTY_RANGE -> R.string.err_save_empty
        ExportError.WAV_TOO_LARGE -> R.string.err_save_wav_large
        ExportError.ENCODER_UNAVAILABLE -> R.string.err_save_encoder
        ExportError.ENCODER_FAILED -> R.string.err_save_encoder
        ExportError.WRITE_FAILED -> R.string.err_save_failed
    }

    override fun onCleared() {
        player?.stop()
        saveJob?.cancel()
        openJob?.cancel()
        clearWorkFiles(emptyList())
        super.onCleared()
    }

    private companion object {
        val INVALID_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")
    }
}
