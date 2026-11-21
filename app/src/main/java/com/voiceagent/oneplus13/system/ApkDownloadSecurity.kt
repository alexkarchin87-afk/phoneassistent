package com.voiceagent.oneplus13.system

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Reject addresses that must not be reached by model-supplied download links. */
internal fun isPublicDownloadAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val b = address.address.map { it.toInt() and 255 }
    return when (address) {
        is Inet4Address -> !(b[0] == 0 || b[0] >= 224 ||
            (b[0] == 100 && b[1] in 64..127) ||
            (b[0] == 192 && b[1] == 0 && b[2] == 0) ||
            (b[0] == 198 && b[1] in 18..19))
        is Inet6Address -> !(b[0] and 0xfe == 0xfc)
        else -> false
    }
}
