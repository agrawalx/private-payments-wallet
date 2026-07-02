package com.privatepayments.wallet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import uniffi.prover_ffi.KeyBundle
import uniffi.prover_ffi.accountShieldedKeys
import uniffi.prover_ffi.generateMnemonic
import uniffi.prover_ffi.mnemonicToAccount
import uniffi.prover_ffi.validateMnemonic
import java.net.HttpURLConnection
import java.net.URL

/** One derived account (SLIP-0010 m/44'/148'/index'). */
data class AccountInfo(val index: Int, val address: String)

/**
 * Self-custodial wallet (BIP39 + SEP-0005). One recovery phrase, **multiple
 * switchable accounts** at `m/44'/148'/index'` (like Base/MetaMask). The phrase
 * is stored encrypted at rest (Android Keystore). The active account's Stellar
 * Ed25519 key signs transactions + pays fees; its **per-seed shielded keys**
 * (note + encryption keypair, derived from the account's signing key) own and
 * decrypt that account's private notes.
 */
class WalletManager(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "umbra_wallet",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    lateinit var address: String
        private set
    private lateinit var secret: ByteArray
    private var mnemonic: String? = null
    var activeIndex: Int = 0
        private set
    private var accountCount: Int = 1

    fun secret(): ByteArray = secret
    fun recoveryPhrase(): String? = mnemonic

    /** All derived accounts (index + address). */
    fun accounts(): List<AccountInfo> {
        val phrase = mnemonic ?: return emptyList()
        return (0 until accountCount).map { i ->
            AccountInfo(i, mnemonicToAccount(phrase, i.toUInt()).address)
        }
    }

    /** The active account's per-seed shielded (note + encryption) keypair. */
    fun shieldedKeys(): KeyBundle = accountShieldedKeys(mnemonic!!, activeIndex.toUInt())

    /** True if a wallet is already stored (decides onboarding vs. straight to Home). */
    fun hasWallet(): Boolean = prefs.getString("mnemonic", null) != null

    /** Load the stored wallet. Call only when [hasWallet] is true. Off the main thread. */
    fun load() {
        val savedPhrase = prefs.getString("mnemonic", null) ?: return
        mnemonic = savedPhrase
        accountCount = prefs.getInt("accountCount", 1).coerceAtLeast(1)
        activeIndex = prefs.getInt("activeIndex", 0).coerceIn(0, accountCount - 1)
        derive(activeIndex)
    }

    /** Create a brand-new wallet (generate phrase, store encrypted). Returns the phrase.
     *  Funding is manual — use [fundFromTestnet] (the Home "Fund from testnet" button). */
    fun createNew(): String {
        val phrase = generateMnemonic(12u)
        mnemonic = phrase
        accountCount = 1
        activeIndex = 0
        prefs.edit().putString("mnemonic", phrase).putInt("accountCount", 1).putInt("activeIndex", 0).apply()
        derive(0)
        return phrase
    }

    /** Returns true if a wallet already existed; loads or creates one. Off the main thread. */
    fun loadOrCreate(): Boolean {
        val savedPhrase = prefs.getString("mnemonic", null)
        if (savedPhrase != null) {
            mnemonic = savedPhrase
            accountCount = prefs.getInt("accountCount", 1).coerceAtLeast(1)
            activeIndex = prefs.getInt("activeIndex", 0).coerceIn(0, accountCount - 1)
            derive(activeIndex)
            return true
        }
        val phrase = generateMnemonic(12u)
        mnemonic = phrase
        accountCount = 1
        activeIndex = 0
        prefs.edit().putString("mnemonic", phrase).putInt("accountCount", 1).putInt("activeIndex", 0).apply()
        derive(0)
        val funded = runCatching { friendbotFund(address) }.getOrDefault(false)
        android.util.Log.i("StellaWallet", "created $address funded=$funded")
        return false
    }

    /** Import an existing recovery phrase, replacing the current wallet. */
    fun importPhrase(phrase: String): Boolean {
        val cleaned = phrase.trim().lowercase().replace(Regex("\\s+"), " ")
        if (!validateMnemonic(cleaned)) return false
        mnemonic = cleaned
        accountCount = 1
        activeIndex = 0
        prefs.edit().putString("mnemonic", cleaned).putInt("accountCount", 1).putInt("activeIndex", 0).apply()
        derive(0)
        return true
    }

    /** Derive + activate a new account at the next index. Returns its index.
     *  Not funded automatically — use [fundFromTestnet]. */
    fun addAccount(): Int {
        val newIndex = accountCount
        accountCount += 1
        prefs.edit().putInt("accountCount", accountCount).apply()
        setActive(newIndex)
        return newIndex
    }

    /** Friendbot-fund the active account (testnet). Returns (success, message). */
    fun fundFromTestnet(): Pair<Boolean, String> = friendbotFund(address)

    /** Switch the active account (re-derives address/secret). */
    fun setActive(index: Int) {
        activeIndex = index.coerceIn(0, accountCount - 1)
        prefs.edit().putInt("activeIndex", activeIndex).apply()
        derive(activeIndex)
    }

    private fun derive(index: Int) {
        val acct = mnemonicToAccount(mnemonic!!, index.toUInt())
        address = acct.address
        secret = acct.secret
    }

    /** Friendbot-funds a testnet account over Android system TLS. Returns (success, reason). */
    private fun friendbotFund(address: String): Pair<Boolean, String> = try {
        val conn = URL("https://friendbot.stellar.org?addr=$address").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        val code = conn.responseCode
        val ok = code in 200..299
        val body = (if (ok) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        val reason = when {
            ok -> "Requested 10,000 test XLM — balance updates shortly"
            code == 429 -> "Rate-limited by friendbot — try again in a bit"
            body.contains("op_already_exists", ignoreCase = true) -> "Account already funded"
            else -> "Friendbot error ($code): ${body.take(200)}"
        }
        android.util.Log.i("StellaWallet", "friendbot fund: code=$code ok=$ok body=${body.take(200)}")
        Pair(ok, reason)
    } catch (e: Exception) {
        android.util.Log.w("StellaWallet", "friendbot fund failed: ${e}")
        Pair(false, "Fund failed: ${e.message ?: e.javaClass.simpleName}")
    }
}
