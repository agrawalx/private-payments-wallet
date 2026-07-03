package com.privatepayments.net

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Minimal Horizon client for **classic** Stellar payments (Daylight / public
 * mode). Soroban RPC's `sendTransaction` rejects non-Soroban txs, so a classic
 * native-XLM `Payment` must be submitted here, at Horizon's `POST /transactions`
 * (`tx=<base64 XDR>`, form-encoded). Over Android system TLS (see [InsecureTls]).
 *
 * Reads (balance / sequence) still go through Soroban RPC `getLedgerEntries`
 * (see [SorobanRpc]) — this client only submits.
 */
object HorizonClient {
    const val TESTNET_URL = "https://horizon-testnet.stellar.org"

    class HorizonException(message: String) : Exception(message)

    /**
     * Submit a signed classic tx envelope (base64 XDR). Returns the tx hash on
     * success; throws [HorizonException] with a decoded reason on failure.
     */
    fun submit(url: String, signedTxXdr: String): String {
        val conn = URL("$url/transactions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 40000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = "tx=" + URLEncoder.encode(signedTxXdr, "UTF-8")
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: throw HorizonException("Horizon HTTP $code: ${text.take(300)}")

        if (code in 200..299) {
            val hash = json.optString("hash")
            if (hash.isNotEmpty() && json.optBoolean("successful", true)) return hash
            throw HorizonException("payment failed: ${resultCodes(json) ?: text.take(300)}")
        }
        // 4xx/5xx: Horizon returns a Problem+JSON body with extras.result_codes.
        throw HorizonException(resultCodes(json) ?: json.optString("title", "Horizon HTTP $code"))
    }

    /** One classic (native-XLM) payment in/out of the account, for public activity. */
    data class PublicPayment(
        val sent: Boolean,
        /** Signed XLM string, e.g. "+25.0000" / "−10.0000". */
        val amount: String,
        /** The other party's G-address (short-form handled by the UI). */
        val counterparty: String,
        val createdAt: String,
        val txHash: String,
    )

    /**
     * Recent classic native-XLM payments for [account] (Daylight activity feed),
     * newest first. Reads Horizon `/accounts/{id}/payments`; includes the
     * `create_account` op (the initial friendbot funding) as a received payment.
     */
    fun recentPayments(url: String, account: String, limit: Int = 15): List<PublicPayment> {
        val conn = URL("$url/accounts/$account/payments?order=desc&limit=$limit")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) return emptyList()

        val records = runCatching {
            JSONObject(text).optJSONObject("_embedded")?.optJSONArray("records")
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<PublicPayment>()
        for (i in 0 until records.length()) {
            val r = records.optJSONObject(i) ?: continue
            when (r.optString("type")) {
                "payment" -> {
                    if (r.optString("asset_type") != "native") continue
                    val from = r.optString("from")
                    val to = r.optString("to")
                    val sent = from == account
                    val amt = fmtAmount(r.optString("amount"), sent)
                    out.add(PublicPayment(sent, amt, if (sent) to else from, r.optString("created_at"), r.optString("transaction_hash")))
                }
                "create_account" -> {
                    // The account being created received `starting_balance`.
                    val created = r.optString("account")
                    val sent = created != account // if we funded someone else
                    val amt = fmtAmount(r.optString("starting_balance"), sent)
                    val other = if (sent) created else r.optString("funder")
                    out.add(PublicPayment(sent, amt, other, r.optString("created_at"), r.optString("transaction_hash")))
                }
            }
        }
        return out
    }

    /**
     * Full native-XLM activity for [account], newest first — the Daylight feed.
     *
     * Unlike [recentPayments] (which only sees **classic** payment ops), this reads
     * Horizon `/effects` and so also captures native balance changes driven by
     * **Soroban contract invocations** — e.g. a shielded **withdraw** paying out to
     * this account (a SAC `transfer`, invisible to `/payments`), or a deposit moving
     * XLM into the pool. Effects carry no tx hash / counterparty, so we enrich each
     * from a matching classic payment when there is one (deposits/withdraws stay
     * hash-less and are labelled against the pool).
     */
    fun recentPublicActivity(url: String, account: String, limit: Int = 20): List<PublicPayment> {
        val classic = runCatching { recentPayments(url, account, limit) }.getOrDefault(emptyList())
        // Key a classic payment by (direction, amount, timestamp) so an effect can
        // borrow its tx hash + counterparty.
        val byKey = classic.associateBy { Triple(it.sent, it.amount, it.createdAt) }

        val conn = URL("$url/accounts/$account/effects?order=desc&limit=$limit")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) return classic   // effects unavailable → fall back to classic

        val records = runCatching {
            JSONObject(text).optJSONObject("_embedded")?.optJSONArray("records")
        }.getOrNull() ?: return classic

        val out = ArrayList<PublicPayment>()
        for (i in 0 until records.length()) {
            val r = records.optJSONObject(i) ?: continue
            val (sent, raw) = when (r.optString("type")) {
                "account_credited" -> {
                    if (r.optString("asset_type") != "native") continue
                    false to r.optString("amount")
                }
                "account_debited" -> {
                    if (r.optString("asset_type") != "native") continue
                    true to r.optString("amount")
                }
                // Initial friendbot funding: the new account is credited its balance.
                "account_created" -> false to r.optString("starting_balance")
                else -> continue
            }
            val amt = fmtAmount(raw, sent)
            val createdAt = r.optString("created_at")
            val match = byKey[Triple(sent, amt, createdAt)]
            out.add(
                PublicPayment(
                    sent = sent,
                    amount = amt,
                    counterparty = match?.counterparty ?: "shielded pool",
                    createdAt = createdAt,
                    txHash = match?.txHash ?: "",
                ),
            )
        }
        return out
    }

    /** "12.5" → "+12.5000" / "−12.5000". */
    private fun fmtAmount(raw: String, sent: Boolean): String {
        val v = raw.toDoubleOrNull() ?: 0.0
        return "%s%.4f".format(if (sent) "−" else "+", v)
    }

    /** Pull the human-useful `extras.result_codes` (tx + operations) if present. */
    private fun resultCodes(json: JSONObject): String? {
        val extras = json.optJSONObject("extras") ?: return null
        val rc = extras.optJSONObject("result_codes") ?: return null
        val tx = rc.optString("transaction")
        val ops = rc.optJSONArray("operations")?.let { arr ->
            (0 until arr.length()).joinToString(",") { arr.optString(it) }
        }
        return buildString {
            if (tx.isNotEmpty()) append(tx)
            if (!ops.isNullOrEmpty()) append(if (isEmpty()) ops else " ($ops)")
        }.ifEmpty { null }
    }
}
