package com.chistyl.tvremote.discovery

import java.net.InetAddress

data class TvDevice(
    val name: String,
    val serviceType: String,
    val host: InetAddress? = null,
    val port: Int? = null
) {
    val endpoint: String get() = listOfNotNull(host?.hostAddress, port?.toString()).joinToString(":")
}
