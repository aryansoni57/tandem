// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol.body

import app.tandem.protocol.NetworkPacket
import app.tandem.protocol.PacketTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Keys that have no text form, by the number the desktop expects in `specialKey`. The table is the
 * desktop's own (`SpecialKeysMap`), so the numbers must not change.
 */
enum class SpecialKey(val code: Int) {
    Backspace(1),
    Tab(2),
    LineFeed(3),
    Left(4),
    Up(5),
    Right(6),
    Down(7),
    PageUp(8),
    PageDown(9),
    Home(10),
    End(11),
    Enter(12),
    Delete(13),
    Escape(14),
    F1(21),
    F5(25),
    F11(31),
    F12(32),
}

/** Modifier keys held for one key press. */
data class KeyModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val superKey: Boolean = false,
)

/** Body of `kdeconnect.mousepad.request`, sent to drive the desktop's pointer and keyboard. */
sealed interface MousepadBody {
    /** Relative pointer move, in pixels. */
    data class Move(val dx: Float, val dy: Float) : MousepadBody

    /** Scroll by [dy]. Positive scrolls the page down. */
    data class Scroll(val dy: Float) : MousepadBody

    enum class Click { Single, Double, Middle, Right, Hold, Release }

    data class ClickEvent(val click: Click) : MousepadBody

    /** Typed text. A multi-character string is typed in order. */
    data class Text(val text: String, val modifiers: KeyModifiers = KeyModifiers()) : MousepadBody

    data class Special(val key: SpecialKey, val modifiers: KeyModifiers = KeyModifiers()) : MousepadBody

    fun toJson(): JsonObject =
        buildJsonObject {
            when (this@MousepadBody) {
                is Move -> {
                    put("dx", dx.toDouble())
                    put("dy", dy.toDouble())
                }
                is Scroll -> {
                    put("scroll", true)
                    put("dx", 0.0)
                    put("dy", dy.toDouble())
                }
                is ClickEvent ->
                    put(
                        when (click) {
                            Click.Single -> "singleclick"
                            Click.Double -> "doubleclick"
                            Click.Middle -> "middleclick"
                            Click.Right -> "rightclick"
                            Click.Hold -> "singlehold"
                            Click.Release -> "singlerelease"
                        },
                        true,
                    )
                is Text -> {
                    put("key", text)
                    putModifiers(modifiers)
                }
                is Special -> {
                    put("specialKey", key.code)
                    putModifiers(modifiers)
                }
            }
        }

    fun toPacket(id: Long = System.currentTimeMillis()): NetworkPacket =
        NetworkPacket(id, PacketTypes.MOUSEPAD_REQUEST, toJson())
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putModifiers(m: KeyModifiers) {
    if (m.ctrl) put("ctrl", true)
    if (m.alt) put("alt", true)
    if (m.shift) put("shift", true)
    if (m.superKey) put("super", true)
}
