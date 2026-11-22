// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The system clipboard. Reading works only while the app has focus (Android 10 and later). */
interface SystemClipboard {
    /**
     * The primary clip's text, or null when there is none, it is empty, or it is sensitive and
     * [skipSensitive] is set.
     */
    suspend fun readText(skipSensitive: Boolean): String?

    suspend fun writeText(text: String)

    /** Puts a file on the clipboard. [uri] must be readable by other apps (a FileProvider URI). */
    suspend fun writeFile(
        uri: String,
        mime: String,
    )
}

@Singleton
class AndroidSystemClipboard
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SystemClipboard {
        private val manager: ClipboardManager
            get() = context.getSystemService(ClipboardManager::class.java)

        override suspend fun readText(skipSensitive: Boolean): String? =
            withContext(Dispatchers.Main) {
                val clip = manager.primaryClip ?: return@withContext null
                if (clip.itemCount == 0) return@withContext null
                if (skipSensitive && isSensitive(clip)) return@withContext null
                clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
            }

        override suspend fun writeText(text: String) {
            withContext(Dispatchers.Main) {
                manager.setPrimaryClip(ClipData.newPlainText(LABEL, text))
            }
        }

        override suspend fun writeFile(
            uri: String,
            mime: String,
        ) {
            withContext(Dispatchers.Main) {
                val clip = ClipData(LABEL, arrayOf(mime), ClipData.Item(android.net.Uri.parse(uri)))
                manager.setPrimaryClip(clip)
            }
        }

        private fun isSensitive(clip: ClipData): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true

        private companion object {
            const val LABEL = "Tandem"
        }
    }
