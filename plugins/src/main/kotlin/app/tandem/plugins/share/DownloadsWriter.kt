// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.share

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import app.tandem.protocol.share.FileNames
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes received files to `Download/Tandem`. Android 10 and later use MediaStore, so no storage
 * permission is needed. Android 8 and 9 write the legacy folder, which needs WRITE_EXTERNAL_STORAGE
 * (declared with maxSdkVersion 28 in the app manifest).
 *
 * A MediaStore file stays pending (hidden from other apps) until [IncomingFile.commit].
 */
@Singleton
class DownloadsWriter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : IncomingStorage {
        override fun create(name: String): IncomingFile =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStore(name) else legacy(name)

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun mediaStore(requested: String): IncomingFile {
            val resolver = context.contentResolver
            val name = FileNames.uniqueName(requested) { candidate -> mediaStoreExists(candidate) }
            val mime = mimeFor(name)
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore refused a new file")
            val stream =
                resolver.openOutputStream(uri, "w") ?: run {
                    resolver.delete(uri, null, null)
                    throw IOException("cannot open $uri for writing")
                }
            return MediaStoreFile(name, mime, stream, uri)
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun mediaStoreExists(name: String): Boolean =
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(name, RELATIVE_PATH),
                null,
            )?.use { it.count > 0 } ?: false

        private fun legacy(requested: String): IncomingFile {
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Tandem")
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("cannot create $folder")
            val name = FileNames.uniqueName(requested) { File(folder, it).exists() }
            val file = File(folder, name)
            return LegacyFile(name, mimeFor(name), FileOutputStream(file), file)
        }

        private fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        private inner class MediaStoreFile(
            override val name: String,
            override val mime: String,
            override val stream: OutputStream,
            private val target: Uri,
        ) : IncomingFile {
            private var open = true

            override val uri: String get() = target.toString()

            override fun commit(lastModifiedMs: Long?) {
                if (!open) return
                open = false
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                        lastModifiedMs?.let { put(MediaStore.MediaColumns.DATE_MODIFIED, it / MS_PER_SECOND) }
                    }
                context.contentResolver.update(target, values, null, null)
            }

            override fun abort() {
                if (!open) return
                open = false
                runCatching { stream.close() }
                context.contentResolver.delete(target, null, null)
            }
        }

        private inner class LegacyFile(
            override val name: String,
            override val mime: String,
            override val stream: OutputStream,
            private val file: File,
        ) : IncomingFile {
            private var open = true

            override val uri: String get() = Uri.fromFile(file).toString()

            override fun commit(lastModifiedMs: Long?) {
                if (!open) return
                open = false
                lastModifiedMs?.let { file.setLastModified(it) }
            }

            override fun abort() {
                if (!open) return
                open = false
                runCatching { stream.close() }
                file.delete()
            }
        }

        companion object {
            const val RELATIVE_PATH = "Download/Tandem/"
            private const val MS_PER_SECOND = 1000L
        }
    }
