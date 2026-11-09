package com.edgetts.engine

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * System DNS first; if it cannot resolve the host (filtered Wi-Fi/router DNS,
 * ad-blocking resolvers), fall back to DNS-over-HTTPS, as Chrome does. The DoH
 * servers are reached by literal IP so they do not depend on system DNS.
 *
 * IPv4 addresses are returned first: OkHttp 4 has no Happy Eyeballs, so on a
 * network with broken IPv6 routing it would stall on the AAAA address until
 * the connect timeout while browsers silently fall back to IPv4.
 */
object FallbackDns : Dns {

    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val resolvers: List<Dns> = listOf(
        doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
        doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
    )

    private fun doh(url: String, vararg ips: String): Dns =
        DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
            .build()

    override fun lookup(hostname: String): List<InetAddress> = ipv4First(resolve(hostname))

    private fun resolve(hostname: String): List<InetAddress> {
        val systemError = try {
            return Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            e
        }
        for (resolver in resolvers) {
            val result = runCatching { resolver.lookup(hostname) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        throw systemError
    }

    private fun ipv4First(addresses: List<InetAddress>): List<InetAddress> =
        addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
}
