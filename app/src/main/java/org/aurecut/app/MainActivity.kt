package org.aurecut.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.aurecut.app.audio.RecordingController
import org.aurecut.app.ui.AuReCutTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a rotation the same intent comes back; only a fresh start should open the file again.
        if (savedInstanceState == null) openFromIntent(intent)
        setContent {
            AuReCutTheme {
                AuReCutApp(vm, onMoveToBack = { moveTaskToBack(true) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    /** "Open with AuReCut" from a file manager or another app. */
    private fun openFromIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = intent.data ?: return
        // A recording in progress is never interrupted by a file that arrives from outside.
        if (RecordingController.isActive) return
        vm.openFile(uri)
    }
}
