// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.transport

import java.net.InetAddress

/**
 * Plan section 2.2 step 5: accept a discovery or connection peer only from a private
 * network address, or from a custom IP the user configured.
 */
object AddressFilter {
    fun isPrivate(address: InetAddress): Boolean =
        address.isSiteLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            isUniqueLocalIpv6(address)

    fun isAllowed(
        address: InetAddress,
        customIps: Set<InetAddress>,
    ): Boolean = isPrivate(address) || address in customIps

    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }
}
