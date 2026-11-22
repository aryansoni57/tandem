// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.tandem.core.device.DeviceManager
import app.tandem.core.service.ConnectionService
import app.tandem.plugins.share.OutgoingFile
import app.tandem.plugins.share.ShareSender
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app sends. Work runs on an app-level scope, so it outlives the sharing screen that started it.
 * Content URIs are opened before this returns, while the sharing app's read grant is still valid.
 */
@Singleton
class ShareActions
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sender: ShareSender,
        private val devices: DeviceManager,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The first paired device with a live link, or null. */
        fun connectedDeviceId(): String? =
            devices.deviceStates.value.firstOrNull { it.paired && it.reachable }?.deviceId

        /** Starts the batch and returns how many files could be opened. */
        fun sendFiles(
            deviceId: String,
            uris: List<Uri>,
        ): Int {
            val files = uris.mapNotNull(::openOutgoing)
            if (files.isEmpty()) return 0
            ConnectionService.start(context)
            scope.launch { sender.sendFiles(deviceId, files) }
            return files.size
        }

        /** Same path as [sendFiles], for files the app can read directly (debug send test). */
        fun sendLocalFiles(
            deviceId: String,
            files: List<java.io.File>,
        ): Int {
            val outgoing =
                files.map { file ->
                    OutgoingFile(
                        name = file.name,
                        size = file.length(),
                        mime = null,
                        stream = file.inputStream(),
                    )
                }
            ConnectionService.start(context)
            scope.launch { sender.sendFiles(deviceId, outgoing) }
            return outgoing.size
        }

        fun sendText(
            deviceId: String,
            text: String,
        ) {
            ConnectionService.start(context)
            scope.launch { sender.sendText(deviceId, text) }
        }

        fun sendLink(
            deviceId: String,
            url: String,
        ) {
            ConnectionService.start(context)
            scope.launch { sender.sendUrl(deviceId, url) }
        }

        /**
         * Sends to the first connected device. Adds https:// when the user typed no scheme.
         * Returns false when none is connected.
         */
        suspend fun sendLinkToConnected(text: String): Boolean {
            val deviceId = connectedDeviceId() ?: return false
            val url = if (text.contains("://")) text else "https://$text"
            return sender.sendUrl(deviceId, url)
        }

        private fun openOutgoing(uri: Uri): OutgoingFile? {
            val resolver = context.contentResolver
            val (name, size) = queryNameAndSize(uri) ?: return null
            // A URI the sharing app has revoked, or that no longer exists, must not crash the app.
            val stream = openStream(uri) ?: return null
            return OutgoingFile(
                name = name ?: DEFAULT_NAME,
                size = size,
                mime = resolver.getType(uri),
                stream = stream,
            )
        }

        /** The display name and size of [uri], or null when the provider refuses the query. */
        private fun queryNameAndSize(uri: Uri): Pair<String?, Long>? {
            var name: String? = null
            var size = UNKNOWN_SIZE
            try {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            name = cursor.getString(0)
                            if (!cursor.isNull(1)) size = cursor.getLong(1)
                        }
                    }
            } catch (_: SecurityException) {
                return null
            }
            return name to size
        }

        private fun openStream(uri: Uri): java.io.InputStream? =
            try {
                context.contentResolver.openInputStream(uri)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

        companion object {
            private const val UNKNOWN_SIZE = -1L
            private const val DEFAULT_NAME = "shared-file"
        }
    }
