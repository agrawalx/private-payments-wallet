package com.privatepayments.net

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the privacy relayer (the `relayer/` Rust service). For withdraw and
 * transfer, the wallet hands the relayer the proof + ext_data and the relayer
 * submits `transact` under its OWN account — so this wallet's public `G…` account
 * never appears on-chain as the source / sender / fee-payer. The recipient and
 * amount are bound by `ext_data_hash` inside the proof, so the relayer cannot
 * redirect or skim funds.
 *
 * On the device, reach the host-run relayer via `adb reverse tcp:8090 tcp:8090`.
 */
object RelayerClient {
    private const val BASE = "http://127.0.0.1:8090"

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /** Submit a withdraw/transfer via the relayer; returns the tx hash, throws on failure. */
    fun relay(
        proof: ByteArray,
        publicInputs: ByteArray,
        extDataHash: ByteArray,
        extRecipient: String,
        extAmount: String,
        encryptedOutput0: ByteArray,
        encryptedOutput1: ByteArray,
    ): String {
        val body = JSONObject()
            .put("proof", b64(proof))
            .put("public_inputs", b64(publicInputs))
            .put("ext_data_hash", b64(extDataHash))
            .put("ext_recipient", extRecipient)
            .put("ext_amount", extAmount)
            .put("encrypted_output0", b64(encryptedOutput0))
            .put("encrypted_output1", b64(encryptedOutput1))
            .toString()

        val conn = (URL("$BASE/relay").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10000
            readTimeout = 90000 // relayer simulates + signs + sends + polls
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("relayer error $code: $text")
        return JSONObject(text).getString("hash")
    }
}
