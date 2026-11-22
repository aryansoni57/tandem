// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * Lenient body accessors. Peers are inconsistent (for example, the Android reference sends
 * targetProtocolVersion as a string), so numbers and numeric strings are both accepted.
 */

fun JsonObject.getInt(key: String): Int? = getLong(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

fun JsonObject.getLong(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.contentOrNull?.toLongOrNull()
}

/** Accepts `true`/`false` and the strings `"true"`/`"false"`. */
fun JsonObject.getBoolean(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.booleanOrNull ?: when (p.contentOrNull) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

fun JsonObject.getString(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull || !p.isString) return null
    return p.content
}

fun JsonObject.getStringSet(key: String): Set<String> {
    val arr = this[key] as? JsonArray ?: return emptySet()
    return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()
}
