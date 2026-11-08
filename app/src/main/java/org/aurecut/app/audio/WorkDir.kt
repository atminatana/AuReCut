package org.aurecut.app.audio

import android.content.Context
import java.io.File

/** Temporary PCM and export files live in the app cache and are never visible to other apps. */
object WorkDir {
    private fun dir(context: Context): File = File(context.cacheDir, "work").apply { mkdirs() }

    fun newFile(context: Context, prefix: String, extension: String): File =
        File(dir(context), "$prefix-${System.nanoTime()}.$extension")

    /** Deletes everything except the files in [keep]. */
    fun clear(context: Context, keep: Collection<File> = emptyList()) {
        val keepPaths = keep.map { it.absolutePath }.toSet()
        dir(context).listFiles()?.forEach { f ->
            if (f.absolutePath !in keepPaths) f.delete()
        }
    }
}
