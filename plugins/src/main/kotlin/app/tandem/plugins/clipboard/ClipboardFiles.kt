// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A file being received for the clipboard. */
interface StagedClipFile {
    val stream: OutputStream

    /** A URI other apps can read once the file is on the clipboard. */
    val uri: String

    val mime: String

    /** Removes the partial file. */
    fun abort()
}

/** Where a clipboard file is written before it goes on the clipboard. */
interface ClipboardFileStore {
    fun create(name: String): StagedClipFile
}

/**
 * Stages clipboard files in the app cache and shares them through a FileProvider. Only the latest file
 * is kept: each new one replaces the folder content, because the clipboard holds one item.
 */
@Singleton
class CacheClipboardFileStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ClipboardFileStore {
        override fun create(name: String): StagedClipFile {
            val dir = File(context.cacheDir, DIR)
            dir.deleteRecursively()
            check(dir.mkdirs() || dir.isDirectory) { "cannot create $dir" }
            val file = File(dir, name)
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
            val stream = FileOutputStream(file)
            return object : StagedClipFile {
                override val stream: OutputStream = stream
                override val uri: String = uri
                override val mime: String = mime

                override fun abort() {
                    runCatching { stream.close() }
                    file.delete()
                }
            }
        }

        private companion object {
            const val DIR = "clipboard"
        }
    }
