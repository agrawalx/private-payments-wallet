package com.privatepayments.net

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 7 backup transport: the encrypted blob is stored in the user's **own
 * Google Drive `appDataFolder`** (a hidden, per-app folder). We use Google
 * Sign-In only to obtain an OAuth access token for the `drive.appdata` scope,
 * then call the Drive REST API directly over HTTP — no heavy Drive client lib.
 *
 * SETUP REQUIRED (the live round-trip needs this; the app builds/runs without):
 *  1. Google Cloud project → enable the Drive API.
 *  2. OAuth consent screen (add the `drive.appdata` scope; add yourself as a test user).
 *  3. Android OAuth client ID: package `com.privatepayments`, signing-cert SHA-1
 *     `D9:EA:39:9A:E3:FD:F4:73:2D:C6:89:84:5F:E0:10:C5:BA:FE:D2:6C` (debug keystore).
 *  4. A Google account signed in on the device.
 * Without these, sign-in / `getToken` fail and the UI surfaces the error.
 */
object DriveBackup {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val FILENAME = "umbra-wallet-backup.enc"
    private const val BOUNDARY = "umbraBackupBoundary7e3f"

    fun signInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun lastAccount(context: Context): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun hasScope(account: GoogleSignInAccount?): Boolean =
        account != null && GoogleSignIn.hasPermissions(account, Scope(SCOPE))

    /** Blocking — call off the main thread. May throw UserRecoverableAuthException. */
    fun accessToken(context: Context, account: GoogleSignInAccount): String {
        val acc = account.account ?: error("Google account has no underlying Account")
        return GoogleAuthUtil.getToken(context, acc, "oauth2:$SCOPE")
    }

    /** Upload the encrypted blob (create or overwrite the single backup file). */
    fun upload(token: String, blob: ByteArray) {
        val existingId = findFileId(token)
        if (existingId == null) createMultipart(token, blob) else updateMedia(token, existingId, blob)
    }

    /** Download the encrypted backup blob, or null if none exists yet. */
    fun download(token: String): ByteArray? {
        val id = findFileId(token) ?: return null
        val conn = open("https://www.googleapis.com/drive/v3/files/$id?alt=media", token, "GET")
        return readBytesOrThrow(conn)
    }

    // ---- Drive REST helpers ------------------------------------------------

    private fun findFileId(token: String): String? {
        val q = "name='$FILENAME'".let { java.net.URLEncoder.encode(it, "UTF-8") }
        val conn = open(
            "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id,name)",
            token, "GET",
        )
        val body = readTextOrThrow(conn)
        val files = JSONObject(body).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun createMultipart(token: String, blob: ByteArray) {
        val meta = JSONObject().put("name", FILENAME).put("parents", org.json.JSONArray().put("appDataFolder"))
        val conn = open(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", token, "POST",
        )
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$BOUNDARY")
        conn.doOutput = true
        conn.outputStream.use { out ->
            out.write(("--$BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").toByteArray())
            out.write(meta.toString().toByteArray())
            out.write(("\r\n--$BOUNDARY\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray())
            out.write(blob)
            out.write(("\r\n--$BOUNDARY--").toByteArray())
        }
        readTextOrThrow(conn)
    }

    private fun updateMedia(token: String, id: String, blob: ByteArray) {
        val conn = open(
            "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media", token, "PATCH",
        )
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.doOutput = true
        conn.outputStream.use { it.write(blob) }
        readTextOrThrow(conn)
    }

    private fun open(url: String, token: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        // HttpURLConnection has no native PATCH; tunnel via header override.
        if (method == "PATCH") {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else {
            conn.requestMethod = method
        }
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun readTextOrThrow(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("Drive HTTP $code: ${text.take(300)}")
        return text
    }

    private fun readBytesOrThrow(conn: HttpURLConnection): ByteArray {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("Drive HTTP $code: ${err.take(300)}")
        }
        return conn.inputStream.use { it.readBytes() }
    }
}
