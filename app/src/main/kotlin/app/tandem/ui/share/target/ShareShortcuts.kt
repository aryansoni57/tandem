// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.share.target

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.tandem.MainActivity
import app.tandem.R
import app.tandem.core.device.DeviceManager
import app.tandem.core.device.DeviceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes one direct-share shortcut per paired device, so the system share sheet lists each
 * computer as a target. The shortcut id is the device id; [ShareTargetActivity] reads it back from
 * `Intent.EXTRA_SHORTCUT_ID`. Direct share needs Android 10 or later: older versions keep the normal
 * "Send with Tandem" entry. See `res/xml/shortcuts.xml`.
 */
@Singleton
class ShareShortcuts
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val deviceManager: DeviceManager,
    ) {
        fun start(scope: CoroutineScope) {
            scope.launch {
                deviceManager.deviceStates
                    .map { list -> list.filter { it.paired }.map { it.deviceId to it.name } }
                    .distinctUntilChanged()
                    .collect { publish(it) }
            }
        }

        private fun publish(devices: List<Pair<String, String>>) {
            val limit = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(MAX)
            val shortcuts =
                devices.take(limit).map { (id, name) ->
                    ShortcutInfoCompat.Builder(context, id)
                        .setShortLabel(name)
                        .setLongLabel(name)
                        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                        .setCategories(setOf(CATEGORY))
                        .setLongLived(true)
                        .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                        .build()
                }
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
        }

        companion object {
            const val CATEGORY = "app.tandem.category.SHARE_DEVICE"
            private const val MAX = 4

            /** The paired device a direct-share shortcut points at, or null for an ordinary share. */
            fun target(
                shortcutId: String?,
                devices: List<DeviceState>,
            ): DeviceState? = shortcutId?.let { id -> devices.firstOrNull { it.paired && it.deviceId == id } }
        }
    }
