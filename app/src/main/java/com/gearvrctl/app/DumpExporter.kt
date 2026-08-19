package com.gearvrctl.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Writes the raw-packet capture to a shareable file and hands it to the Android share sheet. */
object DumpExporter {

    fun share(context: Context, text: String) {
        val dumpsDir = File(context.cacheDir, "dumps").apply { mkdirs() }
        val file = File(dumpsDir, "gearvr_dump_${System.currentTimeMillis()}.txt")
        file.writeText(text)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Gear VR packet dump"))
    }
}
