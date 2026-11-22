// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

/** Device classes as they appear on the wire in the `deviceType` field. */
enum class DeviceType(val wireName: String) {
    PHONE("phone"),
    TABLET("tablet"),
    DESKTOP("desktop"),
    LAPTOP("laptop"),
    TV("tv"),
    ;

    companion object {
        fun fromWire(value: String?): DeviceType? = entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}
