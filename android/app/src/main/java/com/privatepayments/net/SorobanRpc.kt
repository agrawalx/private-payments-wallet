package com.privatepayments.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Soroban JSON-RPC client over `HttpURLConnection` (Android **system
 * TLS**). This exists because the Rust `reqwest`/`rustls-platform-verifier`
 * stack cannot initialize under Android JNA ("Expect rustls-platform-verifier
 * to be initialized"). So the network round-trips live here in Kotlin; the
 * prover lib stays pure (build unsigned tx + assemble + sign over the FFI).
 *
 * Flow for an in-app `transact` submission:
 *   1. getLedgerEntries(accountLedgerKey)  -> account entry XDR (for seq num)
 *   2. [Rust] buildUnsignedTransact(...)    -> unsigned tx XDR
 *   3. simulateTransaction(unsigned)        -> sim response JSON
 *   4. [Rust] finalizeAndSign(...)          -> signed tx XDR
 *   5. sendTransaction(signed)              -> tx hash
 *   6. poll getTransaction(hash)            -> SUCCESS / FAILED
 */
object SorobanRpc {
    const val TESTNET_URL = "https://soroban-testnet.stellar.org"

    class RpcException(message: String) : Exception(message)

    /** Raw JSON-RPC call. Returns the `result` object; throws on JSON-RPC error. */
    private fun call(url: String, method: String, params: JSONObject): JSONObject {
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", 1)
            .put("method", method).put("params", params)
            .toString()

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        json.optJSONObject("error")?.let { throw RpcException("$method: $it") }
        return json.optJSONObject("result") ?: throw RpcException("$method: no result in $text")
    }

    /** Reads the account-entry XDR for the seq number. `ledgerKey` is base64 XDR. */
    fun getAccountEntryXdr(url: String, ledgerKey: String): String {
        val params = JSONObject().put("keys", JSONArray().put(ledgerKey))
        val result = call(url, "getLedgerEntries", params)
        val entries = result.optJSONArray("entries")
            ?: throw RpcException("getLedgerEntries: no entries (account unfunded?)")
        if (entries.length() == 0) throw RpcException("account not found on-chain (unfunded?)")
        return entries.getJSONObject(0).getString("xdr")
    }

    /** Simulates a transaction; returns the raw `result` JSON for finalizeAndSign. */
    fun simulate(url: String, unsignedTxXdr: String): String {
        val params = JSONObject().put("transaction", unsignedTxXdr)
        val result = call(url, "simulateTransaction", params)
        result.optString("error").takeIf { it.isNotEmpty() }
            ?.let { throw RpcException("simulateTransaction failed: $it") }
        return result.toString()
    }

    /** Submits a signed transaction. Returns the tx hash. */
    fun send(url: String, signedTxXdr: String): String {
        val params = JSONObject().put("transaction", signedTxXdr)
        val result = call(url, "sendTransaction", params)
        val status = result.optString("status")
        if (status == "ERROR") {
            throw RpcException("sendTransaction ERROR: ${result.optString("errorResultXdr")}")
        }
        return result.optString("hash").ifEmpty { throw RpcException("sendTransaction: no hash") }
    }

    /** Polls getTransaction until SUCCESS/FAILED or timeout. Returns final status. */
    fun pollTransaction(url: String, hash: String, attempts: Int = 40, delayMs: Long = 1000): String {
        val params = JSONObject().put("hash", hash)
        repeat(attempts) {
            val result = call(url, "getTransaction", params)
            when (val status = result.optString("status")) {
                "SUCCESS" -> return status
                "FAILED" -> throw RpcException("transaction FAILED: ${result.optString("resultXdr")}")
                else -> {}
            }
            Thread.sleep(delayMs)
        }
        throw RpcException("transaction $hash not confirmed before timeout")
    }
}
