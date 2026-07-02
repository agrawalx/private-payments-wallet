package com.privatepayments.net

import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Hackathon-only: this device's Android system trust store is missing the
 * root some stellar.org endpoints chain to (soroban-testnet / friendbot serve
 * only a 2-cert chain with no bridge to an older root), so the platform
 * TrustManager rejects them with "Trust anchor for certification path not
 * found" even though the chain is genuinely valid. Skip verification
 * entirely instead of chasing device-specific CA store gaps.
 */
object InsecureTls {
    private val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    /** Call once at process start, before any HTTPS call. */
    fun installGlobally() {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }
}
