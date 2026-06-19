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
        runCatching { friendbotFund(address) }
        return true
    }

    /** Derive + activate a new account at the next index; fund it. Returns its index. */
    fun addAccount(): Int {
        val newIndex = accountCount
        accountCount += 1
        prefs.edit().putInt("accountCount", accountCount).apply()
        setActive(newIndex)
        runCatching { friendbotFund(address) }
        return newIndex
    }

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

    /** Friendbot-funds a testnet account over Android system TLS. Best-effort. */
    private fun friendbotFund(address: String): Boolean = try {
        val conn = URL("https://friendbot.stellar.org?addr=$address").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    } catch (e: Exception) {
        android.util.Log.w("StellaWallet", "friendbot fund failed: ${e.message}")
        false
    }
}
