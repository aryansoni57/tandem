// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

/** Display and identity names: drop punctuation that peers reject or render badly, cap at 32 chars. */
object DeviceNameFilter {
    const val MAX_LENGTH = 32
    private const val STRIPPED = "\"',;:.!?()[]<>"

    fun filter(raw: String): String {
        val kept = raw.filterNot { it in STRIPPED }
        return kept.take(MAX_LENGTH)
    }
}

/** Device ids are 32 to 38 characters from `[a-zA-Z0-9_-]`. Our own id is 32 hex characters. */
object DeviceIdValidator {
    private val pattern = Regex("^[a-zA-Z0-9_-]{32,38}$")

    fun isValid(deviceId: String?): Boolean = deviceId != null && pattern.matches(deviceId)
}
