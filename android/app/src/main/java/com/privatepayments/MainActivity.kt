package com.privatepayments

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.privatepayments.net.DriveBackup
import com.privatepayments.net.HorizonClient
import com.privatepayments.net.IndexerClient
import com.privatepayments.net.InsecureTls
import com.privatepayments.net.RelayerClient
import com.privatepayments.net.SorobanRpc
import com.privatepayments.state.ChainStore
import com.privatepayments.state.CoinSelector
import com.privatepayments.state.NoteStore
import com.privatepayments.state.WalletBackup
import com.privatepayments.ui.ActivityScreen
import com.privatepayments.ui.AmountScreen
import com.privatepayments.ui.AuditScreen
import com.privatepayments.ui.BackupScreen
import com.privatepayments.ui.DisclosureResult
import com.privatepayments.ui.DisclosureScreen
import com.privatepayments.ui.HomeScreen
import com.privatepayments.ui.HomeTab
import com.privatepayments.ui.PeopleScreen
import com.privatepayments.ui.PendingTx
import com.privatepayments.ui.ProofScreen
import com.privatepayments.ui.ReceiveScreen
import com.privatepayments.ui.ConfirmScreen
import com.privatepayments.ui.OnboardingScreen
import com.privatepayments.ui.RecoveryScreen
import com.privatepayments.ui.RegisterScreen
import com.privatepayments.ui.SettingsScreen
import com.privatepayments.ui.SplashScreen
import com.privatepayments.ui.SuccessScreen
import com.privatepayments.ui.SyncState
import com.privatepayments.ui.SyncStatus
import com.privatepayments.ui.TxDetailScreen
import com.privatepayments.ui.ViewingKeyScreen
import com.privatepayments.ui.copyToClipboard
import com.privatepayments.ui.openUrl
import com.privatepayments.ui.rememberHaptics
import kotlinx.coroutines.CompletableDeferred
import uniffi.prover_ffi.SelectedNote
import uniffi.prover_ffi.accountBalanceStroops
import uniffi.prover_ffi.applyIdentity
import uniffi.prover_ffi.backupKeyFromMnemonic
import uniffi.prover_ffi.buildAspProofs
import uniffi.prover_ffi.buildDepositParams
import uniffi.prover_ffi.buildTransferParams
import uniffi.prover_ffi.buildWithdrawParams
import uniffi.prover_ffi.aspMembershipLeafDec
import uniffi.prover_ffi.buildUnsignedAspRegister
import uniffi.prover_ffi.decodeAspLeaf
import uniffi.prover_ffi.notePublicKey
import com.privatepayments.ui.WalletState
import com.privatepayments.ui.theme.LocalUmbraColors
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.UmbraTheme
import com.privatepayments.ui.theme.WalletMode
import com.privatepayments.ui.theme.paletteFor
import kotlin.math.hypot
import com.privatepayments.wallet.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.prover_ffi.FlowArtifacts
import uniffi.prover_ffi.accountLedgerKey
import uniffi.prover_ffi.assembleDeposit
import uniffi.prover_ffi.assembleTransfer
import uniffi.prover_ffi.assembleWithdraw
import uniffi.prover_ffi.buildSignedPayment
import uniffi.prover_ffi.buildUnsignedTransact
import uniffi.prover_ffi.decodeNullifierTopic
import uniffi.prover_ffi.currentPoolRoot
import uniffi.prover_ffi.deriveKeys
import uniffi.prover_ffi.issueDisclosureReceipt
import uniffi.prover_ffi.verifyDisclosureReceipt
import uniffi.prover_ffi.finalizeAndSign
import uniffi.prover_ffi.provePolicyTx42Json
import uniffi.prover_ffi.provePolicyTx42JsonRapidsnark
import uniffi.prover_ffi.rebuildInputPath
import uniffi.prover_ffi.scanNote
import uniffi.prover_ffi.warmUpProvers

private const val POOL_ID = "CAYDRYKMO23GEBDSUP5QUM3G4CMOS7YX3TICYAES2N2IAEI3GA22EBMS"
private const val ASP_MEMBERSHIP_ID = "CDGHHS4R45TKIUHYUZPNTYYND5R4KEO7J6VOOH4AYXJHRGEBYFXX27UK"
private const val RPC_URL = "https://soroban-testnet.stellar.org"

// Route withdraw/transfer through the relayer (its account is the on-chain
// source/sender/fee-payer) so this wallet's public account stays unlinkable.
// Requires the relayer service reachable at RelayerClient.BASE (the hosted
// EC2 relayer). When false, the wallet self-submits all ops from its own
// account (pays its own gas) — no relayer server needed. Deposit always
// self-submits.
private const val USE_RELAYER = true

// Task B6 verification flag: prove with the rapidsnark native backend
// (behind the prover-ffi `rapidsnark` cargo feature) instead of the cached
// arkworks `Prover`, to compare on-device timing/feasibility. Requires the
// .so to be built with `--features rapidsnark` and the zkey asset bundled —
// NOT the shipped default. Leave false for normal builds.
private const val USE_RAPIDSNARK = true
private const val RAPIDSNARK_ZKEY_ASSET = "policy_tx_4_2_final.zkey"

private enum class Screen { Splash, Onboarding, Home, Amount, Confirm, Proof, Success, Recovery, Disclosure, Backup, Settings, Receive, Register, PublicSend, PublicConfirm, PublicSending, TxDetail, ViewingKey, AuditScan }

/**
 * A shielded primitive the app can run on-device. The amount (and recipient,
 * where applicable) are chosen on the Amount screen; params are then built
 * in-app against the live pool tree. Public actions (deposit) read amber/globe;
 * private actions (send/withdraw) read purple/lock.
 */
private enum class Op(
    val paramsAsset: String,
    val title: String,
    val verb: String,
    val isPublic: Boolean,
    /** null = no recipient field (deposit). */
    val recipientLabel: String?,
    val recipientHint: String,
    /** True if a blank recipient is valid (transfer → re-shield to self). */
    val recipientOptional: Boolean = false,
) {
    Deposit("deposit_onchain.json", "Deposit to the pool", "Deposited", true, null, ""),
    Withdraw("withdraw_onchain.json", "Withdraw", "Withdrawn privately", false,
        "Recipient address (G…)", "G… public Stellar address"),
    Transfer("transfer_onchain.json", "Send privately", "Sent privately", false,
        "Recipient shielded address (blank = yourself)", "stella shielded address", recipientOptional = true),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InsecureTls.installGlobally()

        // Warm the embedded ZK provers off the critical path — first proof drops
        // from ~7s to ~3.6s when the 13MB of keys are already deserialized.
        Thread { runCatching { warmUpProvers() } }.start()

        // Per-op params templates (live-rebuilt in-app before each tx).
        val params: Map<Op, String> = Op.entries.associateWith { op ->
            assets.open(op.paramsAsset).bufferedReader().use { it.readText() }
        }
        // Constant empty non-membership proof template (from the deposit fixture).
        val nmFixture = org.json.JSONObject(params.getValue(Op.Deposit))
            .getJSONObject("non_membership_proof").toString()

        val walletManager = WalletManager(this)

        setContent {
            // Which face of the wallet is showing (Daylight/public vs Umbra/shielded).
            // Drives the whole palette; toggled by the slider on Home.
            var publicMode by rememberSaveable { mutableStateOf(false) }
            val walletMode = if (publicMode) WalletMode.Public else WalletMode.Shielded

            // --- Mode switch: circular reveal (Track A) -------------------------
            // HomeScreen/ActivityScreen/PeopleScreen are pure presentational
            // composables (no side effects of their own), so the transition
            // renders the OLD palette a second time — clipped to everywhere
            // *except* a circle growing from the tapped thumb — on top of the
            // NEW palette underneath (which is live from frame 1, no per-token
            // color tween). That gives a pixel-perfect wipe with zero muddy
            // in-between colors and no whole-tree recomposition storm.
            //
            // Deliberately declared HERE, above UmbraTheme(walletMode) — not
            // inside it — because triggering a switch changes `walletMode`,
            // which recomposes UmbraTheme's subtree; state remembered *inside*
            // that subtree (including the reveal's own CoroutineScope) could be
            // torn down mid-animation, permanently stranding the invisible
            // overlay (Modifier.clip only affects drawing, not hit-testing, so
            // a stuck overlay silently eats every future tap). Hoisting this
            // state above the thing it's animating means it can't be affected
            // by the switch it's driving.
            var revealFrom by remember { mutableStateOf<WalletMode?>(null) }
            var revealCenter by remember { mutableStateOf(Offset.Zero) }
            val revealRadius = remember { Animatable(0f) }
            var revealMax by remember { mutableStateOf(1f) }
            var rootOrigin by remember { mutableStateOf(Offset.Zero) }
            var rootSize by remember { mutableStateOf(IntSize.Zero) }
            val revealScope = rememberCoroutineScope()

            fun triggerModeChange(newMode: WalletMode, anchorWindow: Offset) {
                // Read the CURRENT mode from the `publicMode` state, never the
                // enclosing `walletMode` val: this fn is passed down as a
                // `::triggerModeChange` reference, which Compose memoizes — a
                // closure that captured `walletMode` keeps the value from the
                // composition that created it, so after one switch the compare
                // below silently no-ops every tap back to that mode (the
                // "slider dead until you change tabs" bug). `publicMode` is a
                // snapshot-state read, always current through any stale closure.
                val current = if (publicMode) WalletMode.Public else WalletMode.Shielded
                if (newMode == current) return
                val from = current
                val center = anchorWindow - rootOrigin
                val w = rootSize.width.toFloat()
                val h = rootSize.height.toFloat()
                revealCenter = center
                revealMax = maxOf(
                    hypot(center.x, center.y),
                    hypot(w - center.x, center.y),
                    hypot(center.x, h - center.y),
                    hypot(w - center.x, h - center.y),
                ).takeIf { it > 0f } ?: 2000f
                revealFrom = from
                publicMode = newMode == WalletMode.Public
                revealScope.launch {
                    // `finally` guarantees the overlay is torn down even if this
                    // animateTo is cancelled by a re-entrant switch — otherwise
                    // `revealFrom` stays non-null and the invisible overlay eats
                    // every future tap (Modifier.clip doesn't clip hit-testing).
                    try {
                        revealRadius.snapTo(0f)
                        revealRadius.animateTo(revealMax, tween(520, easing = FastOutSlowInEasing))
                    } finally {
                        revealFrom = null
                    }
                }
            }

            UmbraTheme(walletMode) {
                Surface(color = Umbra.Bg) {
                    val ctx = this@MainActivity
                    val wallet = remember { WalletState() }
                    var screen by remember { mutableStateOf(Screen.Splash) }
                    // Which bottom-nav destination is showing while screen == Home.
                    var homeTab by rememberSaveable { mutableStateOf(HomeTab.Home) }
                    var op by remember { mutableStateOf(Op.Deposit) }
                    var txHash by remember { mutableStateOf("") }
                    var pendingAmount by remember { mutableStateOf(0L) }
                    var pendingRecipient by remember { mutableStateOf("") }
                    // Set right before navigating to a Send screen from a tapped
                    // contact (Track D) — prefills AmountScreen's recipient field.
                    var prefillRecipient by remember { mutableStateOf("") }
                    // Local address book (Track D). Bumped on add/remove to re-read.
                    val contactStore = remember { com.privatepayments.state.ContactStore(ctx) }
                    var contactsEpoch by remember { mutableStateOf(0) }
                    val contacts = remember(contactsEpoch) { contactStore.list() }
                    // Success-screen verb override (e.g. a classic public send → "Sent");
                    // null falls back to the pool op's verb.
                    var sentVerb by remember { mutableStateOf<String?>(null) }
                    // On-device ZK proving time for the just-completed op (null for
                    // the classic public-send path, which has no proof step).
                    var lastProofMs by remember { mutableStateOf<Long?>(null) }
                    // Daylight (public) activity: classic XLM sends/receives from Horizon.
                    var publicActivity by remember { mutableStateOf<List<com.privatepayments.ui.Activity>>(emptyList()) }
                    // Blinding of the note created by an in-flight deposit; recorded
                    // on success so it can be labelled "Deposit" once scanned back.
                    var pendingDepositBlinding by remember { mutableStateOf<String?>(null) }
                    var walletAddr by remember { mutableStateOf<String?>(null) }
                    // Bumps on any account change (load / switch / add); re-derives
                    // the active account's shielded keys + per-account note store.
                    var accountEpoch by remember { mutableStateOf(0) }
                    var commitmentTopics by remember { mutableStateOf<List<String>>(emptyList()) }
                    var aspLeaves by remember { mutableStateOf<List<String>>(emptyList()) }

                    // Active account's PER-SEED shielded keys + per-account note store.
                    val shielded = remember(accountEpoch, walletAddr) {
                        if (walletAddr != null) runCatching { walletManager.shieldedKeys() }.getOrNull() else null
                    }
                    // Shareable "stella:" shielded address — the identity to show/copy
                    // at the top of Home while in Shielded mode.
                    val shieldedAddr = remember(shielded) {
                        shielded?.let { "stella:" + android.util.Base64.encodeToString(it.notePublic + it.encryptionPublic, android.util.Base64.NO_WRAP) }
                    }
                    val noteStore = remember(accountEpoch) { NoteStore(ctx, walletManager.activeIndex) }
                    // Durable, account-independent mirror of chain events (cursor + commitments + ASP leaves).
                    val chainStore = remember { ChainStore(ctx) }
                    val hx = { b: ByteArray -> "0x" + b.joinToString("") { "%02x".format(it) } }

                    // This account's ASP membership leaf, and whether it's enrolled
                    // ("registered"). Spending (deposit/send/withdraw) requires it;
                    // receiving does not. Recomputed when keys or the ASP tree change.
                    val myAspLeaf = remember(shielded) {
                        shielded?.let { runCatching { aspMembershipLeafDec(hx(it.notePublic)) }.getOrNull() }
                    }
                    val isRegistered = myAspLeaf != null && aspLeaves.contains(myAspLeaf)

                    // Phase 7 backup: Google Sign-In bridged into a suspend fn via
                    // a CompletableDeferred resolved in the activity-result callback.
                    val pendingSignIn = remember { mutableStateOf<CompletableDeferred<GoogleSignInAccount?>?>(null) }
                    val signInLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { res ->
                        val acct = runCatching {
                            GoogleSignIn.getSignedInAccountFromIntent(res.data).getResult(ApiException::class.java)
                        }.getOrNull()
                        pendingSignIn.value?.complete(acct); pendingSignIn.value = null
                    }
                    suspend fun ensureDriveAccount(): GoogleSignInAccount {
                        val existing = DriveBackup.lastAccount(ctx)
                        if (DriveBackup.hasScope(existing)) return existing!!
                        val d = CompletableDeferred<GoogleSignInAccount?>()
                        pendingSignIn.value = d
                        signInLauncher.launch(DriveBackup.signInClient(ctx).signInIntent)
                        return d.await() ?: throw Exception("Google sign-in cancelled or failed")
                    }
                    val backupKey: () -> ByteArray = {
                        val phrase = walletManager.recoveryPhrase() ?: error("no wallet phrase")
                        backupKeyFromMnemonic(phrase)
                    }
                    val doBackup: suspend () -> String = {
                        val acct = ensureDriveAccount()
                        withContext(Dispatchers.IO) {
                            val token = DriveBackup.accessToken(ctx, acct)
                            val blob = WalletBackup.encrypt(backupKey(), noteStore.exportJson().toByteArray())
                            DriveBackup.upload(token, blob)
                            val c = noteStore.counts()
                            "Backed up ${c.total} note(s) to your Google Drive (encrypted)."
                        }
                    }
                    val doRestore: suspend () -> String = {
                        val acct = ensureDriveAccount()
                        withContext(Dispatchers.IO) {
                            val token = DriveBackup.accessToken(ctx, acct)
                            val blob = DriveBackup.download(token) ?: throw Exception("No backup found in Drive yet")
                            val json = String(WalletBackup.decrypt(backupKey(), blob), Charsets.UTF_8)
                            val n = noteStore.importJson(json)
                            "Restored $n note(s) from your Google Drive backup."
                        }
                    }
                    var sync by remember { mutableStateOf(SyncStatus(SyncState.Syncing, "Connecting to indexer…")) }
                    // Bump to force an immediate re-poll (the banner's "Retry").
                    var syncEpoch by remember { mutableStateOf(0) }
                    // True once the poll loop has reached the indexer at least once —
                    // gates the empty-state skeletons (no data yet vs. genuinely empty).
                    var hasSyncedOnce by remember { mutableStateOf(false) }
                    // Last relayer /health check (every 6th poll) — surfaced as a
                    // warning on Confirm before a relayed (non-deposit) send.
                    var relayerOk by remember { mutableStateOf<Boolean?>(null) }
                    var selectedActivity by remember { mutableStateOf<com.privatepayments.ui.Activity?>(null) }

                    // Self-serve ASP enrollment ("Register"): submit an `insert_leaf`
                    // of this account's note leaf, signed by the account itself (the
                    // contract is permissionless), then pull deltas until the new leaf
                    // is mirrored locally so spending can proceed without a race.
                    val doRegister: suspend () -> Boolean = reg@{
                        val sk = shielded ?: error("wallet not ready")
                        val addr = walletManager.address
                        withContext(Dispatchers.IO) {
                            val entryXdr = SorobanRpc.getAccountEntryXdr(RPC_URL, accountLedgerKey(addr))
                            val unsigned = buildUnsignedAspRegister(ASP_MEMBERSHIP_ID, addr, entryXdr, hx(sk.notePublic))
                            val sim = SorobanRpc.simulate(RPC_URL, unsigned)
                            val signed = finalizeAndSign(unsigned, sim, walletManager.secret())
                            SorobanRpc.pollTransaction(RPC_URL, SorobanRpc.send(RPC_URL, signed))
                        }
                        val target = myAspLeaf
                        var ok = false
                        var tries = 0
                        while (tries < 10 && !ok) {
                            val d = withContext(Dispatchers.IO) { IndexerClient.fetchSince(chainStore.cursor()) }
                            if (d.reachable) {
                                d.commitments.forEach { chainStore.appendCommitment(it.seq, it.commitmentTopic, it.value, it.closedAt) }
                                d.leaves.forEach { lv ->
                                    runCatching { decodeAspLeaf(lv.value) }.getOrNull()?.let { chainStore.appendAspLeaf(lv.seq, it) }
                                }
                                chainStore.setCursor(d.newCursor)
                            }
                            ok = target != null && chainStore.aspLeaves().contains(target)
                            if (!ok) { delay(1500); tries++ }
                        }
                        commitmentTopics = chainStore.commitmentTopics()
                        aspLeaves = chainStore.aspLeaves()
                        ok
                    }

                    // Create/load the self-custodial wallet (Keystore-encrypted).
                    LaunchedEffect(Unit) {
                        if (withContext(Dispatchers.IO) { walletManager.hasWallet() }) {
                            withContext(Dispatchers.IO) { walletManager.load() }
                            walletAddr = walletManager.address
                            accountEpoch++
                            screen = Screen.Home
                        } else {
                            screen = Screen.Onboarding // first run
                        }
                    }

                    // Live sync (Phase 3 state layer): poll the indexer → scan
                    // commitments into durable notes → record on-chain nullifiers
                    // → reconcile (mark spent) → balance = unspent notes only.
                    LaunchedEffect(accountEpoch, syncEpoch) {
                        var iter = 0
                        while (true) {
                            iter++
                            // 0. Pull only the DELTA since our durable cursor, append it to
                            //    the global chain mirror (no more full re-fetch every poll).
                            val delta = withContext(Dispatchers.IO) { IndexerClient.fetchSince(chainStore.cursor()) }
                            if (delta.reachable) hasSyncedOnce = true
                            // Cheap liveness check on the relayer, every 6th poll only.
                            if (iter % 6 == 0) {
                                relayerOk = withContext(Dispatchers.IO) { RelayerClient.health() }
                            }
                            val sk = shielded
                            if (delta.reachable && sk != null) {
                                withContext(Dispatchers.Default) {
                                    delta.commitments.forEach { chainStore.appendCommitment(it.seq, it.commitmentTopic, it.value, it.closedAt, it.eventId) }
                                    delta.leaves.forEach { lv ->
                                        runCatching { decodeAspLeaf(lv.value) }.getOrNull()?.let { chainStore.appendAspLeaf(lv.seq, it) }
                                    }
                                    // Decode + record new on-chain nullifiers into the chain
                                    // mirror BEFORE scanning commitments below, so a commitment
                                    // from the same tx as one of our own spends (change) can be
                                    // recognized on first sight (indexer emits nullifiers before
                                    // commitments within a tx).
                                    val seen = delta.nullifiers.mapNotNull { nf ->
                                        runCatching { decodeNullifierTopic(nf.topic) }.getOrNull()?.let { dec ->
                                            chainStore.appendNullifier(nf.seq, dec, nf.eventId, nf.closedAt)
                                            dec to nf.closedAt
                                        }
                                    }
                                    chainStore.setCursor(delta.newCursor)
                                    // Full ordered lists (from local DB) feed proving.
                                    commitmentTopics = chainStore.commitmentTopics()
                                    aspLeaves = chainStore.aspLeaves()
                                    // 1. Scan only commitments THIS account hasn't seen yet,
                                    //    with its per-seed keys. (On account switch, scannedSeq=0
                                    //    → it rescans everything once.)
                                    // Spend-netting: a newly-scanned note is "change" if its
                                    // creating tx also spent one of the nullifiers of a note we
                                    // already own — i.e. we're both the sender and the receiver.
                                    val ownNullifiers = noteStore.notes().map { it.nullifier }.toSet()
                                    val ownSpendTxPrefixes = chainStore.eventIdPrefixesForNullifiers(ownNullifiers)
                                    val from = chainStore.scannedSeq(walletManager.activeIndex)
                                    chainStore.commitmentsSince(from).forEach { ev ->
                                        runCatching {
                                            scanNote(ev.topic, ev.value, sk.encryptionPrivate, sk.notePrivate)
                                        }.getOrNull()?.let { note ->
                                            val isChange = ev.eventId.isNotBlank() &&
                                                ev.eventId.substringBeforeLast('-') in ownSpendTxPrefixes
                                            noteStore.upsertNote(
                                                note.leafIndex.toLong(), note.commitment,
                                                note.amount.toLong(), note.nullifier, note.blinding,
                                                ev.closedAt, isChange,
                                            )
                                        }
                                    }
                                    chainStore.setScannedSeq(walletManager.activeIndex, chainStore.cursor())
                                    // 2. Reconcile: mark spent notes from the nullifiers just recorded.
                                    noteStore.addNullifiers(seen)
                                    noteStore.reconcile()
                                    val notesNow = noteStore.notes()
                                    wallet.applyNotes(noteStore.unspentBalanceStroops(), notesNow)
                                    // Clear shielded pending entries once the note store has
                                    // moved past the snapshot taken at creation, or after 60s.
                                    val nowMs = System.currentTimeMillis()
                                    val noteCount = notesNow.size
                                    val spentCount = notesNow.count { it.spent }
                                    wallet.pending.removeAll { p ->
                                        !p.isPublic && (
                                            nowMs - p.createdAtMs > 60_000L ||
                                                noteCount != p.noteCountAtCreation ||
                                                spentCount != p.spentCountAtCreation
                                            )
                                    }
                                }
                            }
                            // Public (Stellar account) XLM balance.
                            var publicError: String? = null
                            walletAddr?.let { addr ->
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        accountBalanceStroops(SorobanRpc.getAccountEntryXdr(RPC_URL, accountLedgerKey(addr)))
                                    }
                                }
                                wallet.applyPublic(result.getOrNull())
                                publicError = result.exceptionOrNull()?.message
                            }
                            val c = noteStore.counts()
                            sync = when {
                                !delta.reachable -> SyncStatus(
                                    SyncState.Unreachable,
                                    "Can't reach the network — balances may be stale",
                                    relayerOk,
                                )
                                publicError != null -> SyncStatus(SyncState.Unreachable, "Public balance error: $publicError", relayerOk)
                                else -> SyncStatus(
                                    SyncState.Ok,
                                    "Synced · ${chainStore.commitmentCount()} commitments · ${c.unspent} unspent / ${c.spent} spent note(s)",
                                    relayerOk,
                                )
                            }
                            delay(5000)
                        }
                    }

                    // Daylight activity: pull the account's classic XLM payment history
                    // from Horizon when in public mode (refreshes after a send changes txHash).
                    LaunchedEffect(publicMode, walletAddr, txHash, accountEpoch) {
                        val addr = walletAddr
                        if (publicMode && addr != null) {
                            val pays = withContext(Dispatchers.IO) {
                                runCatching { HorizonClient.recentPublicActivity(HorizonClient.TESTNET_URL, addr) }
                                    .getOrDefault(emptyList())
                            }
                            publicActivity = pays.map { p ->
                                com.privatepayments.ui.Activity(
                                    icon = if (p.sent) Icons.Filled.NorthEast else Icons.Filled.SouthWest,
                                    title = if (p.sent) "Sent" else "Received",
                                    private = false,
                                    amount = "${p.amount} XLM",
                                    positive = !p.sent,
                                    time = com.privatepayments.ui.dateOf(p.createdAt),
                                    subtitle = (if (p.sent) "To " else "From ") +
                                        // Real G-addresses get shortened; contract-driven
                                        // credits (e.g. a shielded withdraw) carry a plain label.
                                        (if (p.counterparty.startsWith("G") && p.counterparty.length == 56)
                                            com.privatepayments.ui.shortAddress(p.counterparty)
                                        else p.counterparty),
                                    url = p.txHash.takeIf { it.isNotEmpty() }
                                        ?.let { "https://stellar.expert/explorer/testnet/tx/$it" },
                                    kind = if (p.sent) com.privatepayments.ui.ActivityKind.PublicSent
                                    else com.privatepayments.ui.ActivityKind.PublicReceived,
                                    timestamp = p.createdAt,
                                    txHash = p.txHash.takeIf { it.isNotEmpty() },
                                )
                            }
                            // The refresh completed — any public pending entries are now
                            // either reflected above or gone; either way, stop showing them.
                            wallet.pending.removeAll { it.isPublic }
                        }
                    }

                    // Avatar = the active account as a letter (A = account 0, B = 1, …).
                    val accountLabel = remember(accountEpoch) { ('A' + walletManager.activeIndex.coerceIn(0, 25)).toString() }
                    // Your OTHER accounts' public addresses (label to address) — quick-pick
                    // chips on the public Send screen so moving XLM between your own
                    // accounts never needs a manual copy/paste (MetaMask-style).
                    val myOtherAddresses = remember(accountEpoch) {
                        walletManager.accounts()
                            .filter { it.index != walletManager.activeIndex }
                            .map { ('A' + it.index.coerceIn(0, 25)).toString() to it.address }
                    }
                    // Spending requires ASP enrollment — route unregistered accounts
                    // through Register first (receiving needs no enrollment).
                    val start: (Op) -> Unit = { chosen ->
                        if (walletAddr != null) {
                            // Idempotent — re-warms in case onCreate's warm-up hasn't
                            // finished yet by the time the user reaches Send.
                            Thread { runCatching { warmUpProvers() } }.start()
                            op = chosen; prefillRecipient = ""; screen = if (isRegistered) Screen.Amount else Screen.Register
                        }
                    }
                    val amountXlm = com.privatepayments.ui.xlm(pendingAmount)

                    // Tapping a contact's "Send public"/"Send shielded" chip (Track D):
                    // prefill the recipient and route into the same Send flows as the
                    // normal Home buttons.
                    val sendToContact: (String, Boolean) -> Unit = { recipientAddr, viaPublic ->
                        if (walletAddr != null) {
                            prefillRecipient = recipientAddr
                            if (viaPublic) screen = Screen.PublicSend
                            else { op = Op.Transfer; screen = if (isRegistered) Screen.Amount else Screen.Register }
                        }
                    }

                    // Private/public pending entries rendered as fake "Confirming…"
                    // Activity rows, prepended ahead of the real (reconciled) history.
                    fun pendingToActivity(p: PendingTx): com.privatepayments.ui.Activity {
                        val isDeposit = p.title == Op.Deposit.verb
                        return com.privatepayments.ui.Activity(
                            icon = if (isDeposit) Icons.Filled.SouthWest else Icons.Filled.NorthEast,
                            title = p.title,
                            private = !p.isPublic,
                            amount = com.privatepayments.ui.signedXlm(p.amountStroops, negative = !isDeposit),
                            positive = isDeposit,
                            time = "",
                            subtitle = "Confirming…",
                            pending = true,
                            kind = when {
                                p.isPublic -> com.privatepayments.ui.ActivityKind.PublicSent
                                isDeposit -> com.privatepayments.ui.ActivityKind.Deposit
                                else -> com.privatepayments.ui.ActivityKind.Transferred
                            },
                        )
                    }
                    val onActivityTap: (com.privatepayments.ui.Activity) -> Unit = {
                        selectedActivity = it; screen = Screen.TxDetail
                    }

                    // Renders the Home/Activity/People tab family for an EXPLICIT
                    // mode (not necessarily the live `walletMode`) — this lets the
                    // reveal transition render the old face a second time, frozen,
                    // while the live tree underneath is already the new mode.
                    @Composable
                    fun RenderHomeFamily(mode: WalletMode, onModeChangeRequest: (WalletMode, Offset) -> Unit) {
                        val topAddress = if (mode == WalletMode.Public) (walletAddr ?: "") else (shieldedAddr ?: walletAddr ?: "")
                        val shieldedActivity = wallet.pending.filter { !it.isPublic }.map(::pendingToActivity) + wallet.activity
                        val daylightActivity = wallet.pending.filter { it.isPublic }.map(::pendingToActivity) + publicActivity
                        val initialSyncing = !hasSyncedOnce && wallet.activity.isEmpty()
                        when (homeTab) {
                            HomeTab.Home -> HomeScreen(
                                address = topAddress,
                                accountLabel = accountLabel,
                                balanceText = wallet.balanceText(),
                                publicText = wallet.publicText(),
                                activity = shieldedActivity,
                                syncStatus = sync,
                                onSend = { start(Op.Transfer) },
                                onDeposit = { start(Op.Deposit) },
                                onWithdraw = { start(Op.Withdraw) },
                                onReceive = { screen = Screen.Receive },
                                onSettings = { screen = Screen.Settings },
                                onShareProof = { if (walletAddr != null) screen = Screen.Disclosure },
                                registered = isRegistered,
                                onRegister = { if (walletAddr != null) screen = Screen.Register },
                                onFund = { withContext(Dispatchers.IO) { walletManager.fundFromTestnet() } },
                                mode = mode,
                                onModeChange = onModeChangeRequest,
                                onPublicSend = { if (walletAddr != null) { prefillRecipient = ""; screen = Screen.PublicSend } },
                                publicActivity = daylightActivity,
                                onSelectTab = { homeTab = it },
                                onActivityTap = onActivityTap,
                                onRetrySync = { syncEpoch++ },
                                initialSyncing = initialSyncing,
                            )
                            HomeTab.Activity -> ActivityScreen(
                                address = topAddress,
                                accountLabel = accountLabel,
                                mode = mode,
                                onModeChange = onModeChangeRequest,
                                activity = shieldedActivity,
                                publicActivity = daylightActivity,
                                onSettings = { screen = Screen.Settings },
                                onSelectTab = { homeTab = it },
                                onActivityTap = onActivityTap,
                                syncStatus = sync,
                                onRetrySync = { syncEpoch++ },
                                initialSyncing = initialSyncing,
                            )
                            HomeTab.People -> PeopleScreen(
                                address = topAddress,
                                accountLabel = accountLabel,
                                mode = mode,
                                contacts = contacts,
                                onAddContact = { name, pub, shielded ->
                                    contactStore.add(name, pub, shielded)
                                    contactsEpoch++
                                },
                                onRemoveContact = { id -> contactStore.remove(id); contactsEpoch++ },
                                onUpdateContact = { c -> contactStore.update(c); contactsEpoch++ },
                                onSendToPublic = { addr -> sendToContact(addr, true) },
                                onSendToShielded = { addr -> sendToContact(addr, false) },
                                onSettings = { screen = Screen.Settings },
                                onSelectTab = { homeTab = it },
                                syncStatus = sync,
                                onRetrySync = { syncEpoch++ },
                            )
                        }
                    }

                    when (screen) {
                        Screen.Splash -> SplashScreen()
                        Screen.Onboarding -> OnboardingScreen(
                            onCreate = { withContext(Dispatchers.IO) { walletManager.createNew() } },
                            onImport = { p -> withContext(Dispatchers.IO) { walletManager.importPhrase(p) } },
                            onDone = { walletAddr = walletManager.address; accountEpoch++; screen = Screen.Home },
                        )
                        Screen.Home -> Box(
                            Modifier.fillMaxSize().onGloballyPositioned { c ->
                                rootOrigin = c.boundsInWindow().topLeft
                                rootSize = c.size
                            },
                        ) {
                            // Bottom layer: always the live, current mode.
                            RenderHomeFamily(walletMode, ::triggerModeChange)
                            // Top layer: only while the wipe is ACTIVELY animating —
                            // the OLD mode, frozen, clipped to everywhere except the
                            // growing circle so the live layer underneath shows
                            // through. Gating on `isRunning` (not just `revealFrom`)
                            // means a cancelled/stranded animation can never leave a
                            // full-screen interactive overlay eating taps.
                            val from = revealFrom
                            if (from != null && revealRadius.isRunning) {
                                CompositionLocalProvider(LocalUmbraColors provides paletteFor(from)) {
                                    Box(
                                        Modifier.fillMaxSize()
                                            .clip(RevealClipShape(revealCenter, revealRadius.value)),
                                    ) {
                                        RenderHomeFamily(from) { _, _ -> }
                                    }
                                }
                            }
                        }
                        Screen.PublicSend -> AmountScreen(
                            title = "Send XLM",
                            isPublic = true,
                            // Leave ~1.5 XLM for the base reserve + fee.
                            maxStroops = (wallet.publicStroops ?: 0L).minus(15_000_000L).coerceAtLeast(0L),
                            recipientLabel = "Recipient address (G…)",
                            recipientHint = "G… public Stellar address",
                            initialRecipient = prefillRecipient,
                            myAddresses = myOtherAddresses,
                            onConfirm = { amt, recip -> pendingAmount = amt; pendingRecipient = recip; screen = Screen.PublicConfirm },
                            onCancel = { screen = Screen.Home },
                        )
                        Screen.PublicConfirm -> ConfirmScreen(
                            title = "Send XLM",
                            isPublic = true,
                            amountXlm = amountXlm,
                            recipient = com.privatepayments.ui.shortAddress(pendingRecipient, 10, 6),
                            typeLabel = "Public payment · classic XLM",
                            onConfirm = { screen = Screen.PublicSending },
                            onCancel = { screen = Screen.PublicSend },
                        )
                        Screen.PublicSending -> PublicSendingScreen(
                            amountXlm = amountXlm,
                            send = {
                                val addr = walletManager.address
                                val entryXdr = SorobanRpc.getAccountEntryXdr(RPC_URL, accountLedgerKey(addr))
                                val signed = buildSignedPayment(addr, entryXdr, pendingRecipient, pendingAmount, "", walletManager.secret())
                                HorizonClient.submit(HorizonClient.TESTNET_URL, signed)
                            },
                            onDone = { hash ->
                                wallet.pending.add(
                                    PendingTx(
                                        id = System.nanoTime(), title = "Sent", amountStroops = pendingAmount,
                                        isPublic = true, createdAtMs = System.currentTimeMillis(),
                                    ),
                                )
                                sentVerb = "Sent"; txHash = hash; lastProofMs = null; screen = Screen.Success
                            },
                            onCancel = { screen = Screen.Home },
                        )
                        Screen.Amount -> AmountScreen(
                            title = op.title,
                            isPublic = op.isPublic,
                            // Spends are capped at the 2-note circuit limit.
                            maxStroops = if (op == Op.Deposit) null else CoinSelector.maxSpendable(noteStore.unspentNotes()),
                            recipientLabel = op.recipientLabel,
                            recipientHint = op.recipientHint,
                            recipientOptional = op.recipientOptional,
                            initialRecipient = prefillRecipient,
                            onConfirm = { amt, recip -> pendingAmount = amt; pendingRecipient = recip; screen = Screen.Confirm },
                            onCancel = { screen = Screen.Home },
                        )
                        Screen.Confirm -> ConfirmScreen(
                            title = op.title,
                            isPublic = op.isPublic,
                            amountXlm = amountXlm,
                            recipient = when {
                                op == Op.Deposit -> "Shielded pool"
                                op == Op.Transfer && pendingRecipient.isBlank() -> "Yourself (re-shield)"
                                else -> com.privatepayments.ui.shortAddress(pendingRecipient, 10, 6)
                            },
                            typeLabel = when (op) {
                                Op.Deposit -> "Public deposit"
                                Op.Withdraw -> "Private withdrawal"
                                Op.Transfer -> "Private send"
                            },
                            onConfirm = { screen = Screen.Proof },
                            onCancel = { screen = Screen.Amount },
                            warning = if (op != Op.Deposit && relayerOk == false)
                                "Relayer unreachable — this private send may fail" else null,
                        )
                        Screen.Proof -> ProofScreen(
                            title = op.title,
                            amount = amountXlm,
                            recipient = when {
                                op == Op.Deposit -> "shielded pool"
                                pendingRecipient.isBlank() -> "yourself"
                                else -> "${pendingRecipient.take(8)}…"
                            },
                            isPublic = op.isPublic,
                            runProof = { advance ->
                                val sk = shielded ?: throw IllegalStateException("wallet not ready")
                                val a = withContext(Dispatchers.Default) {
                                    // Build params in-app for the chosen amount.
                                    var paramsJson = when (op) {
                                        Op.Deposit -> buildDepositParams(
                                            params.getValue(op), pendingAmount.toString(), commitmentTopics, 10u,
                                        ).also { pj ->
                                            // Stash the deposit note's blinding to tag it on scan.
                                            pendingDepositBlinding = runCatching {
                                                org.json.JSONObject(pj).getJSONArray("outputs")
                                                    .getJSONObject(0).getString("blinding")
                                            }.getOrNull()
                                        }
                                        Op.Withdraw -> {
                                            val sel = CoinSelector.select(noteStore.unspentNotes(), pendingAmount)
                                                ?: throw IllegalStateException("Not enough in 2 notes for this amount")
                                            val notes = sel.inputs.map { SelectedNote(it.leafIndex.toUInt(), it.amount.toString(), it.blinding) }
                                            buildWithdrawParams(
                                                params.getValue(op), pendingAmount.toString(), pendingRecipient,
                                                notes, commitmentTopics, 10u,
                                            )
                                        }
                                        Op.Transfer -> {
                                            val sel = CoinSelector.select(noteStore.unspentNotes(), pendingAmount)
                                                ?: throw IllegalStateException("Not enough in 2 notes for this amount")
                                            val notes = sel.inputs.map { SelectedNote(it.leafIndex.toUInt(), it.amount.toString(), it.blinding) }
                                            // Blank recipient = re-shield to yourself.
                                            val (rNote, rEnc) = if (pendingRecipient.isBlank())
                                                Pair(hx(sk.notePublic), hx(sk.encryptionPublic))
                                            else parseShieldedAddress(pendingRecipient)
                                            buildTransferParams(
                                                params.getValue(op), pendingAmount.toString(), rNote, rEnc,
                                                notes, commitmentTopics, 10u,
                                            )
                                        }
                                    }
                                    // Splice in THIS account's per-seed identity + ASP proofs
                                    // (rebuilt against the live ASP membership tree).
                                    val asp = buildAspProofs(hx(sk.notePublic), aspLeaves, 10u, nmFixture)
                                    paramsJson = applyIdentity(
                                        paramsJson, op != Op.Deposit, hx(sk.notePrivate), hx(sk.encryptionPublic),
                                        asp.membershipJson, asp.nonMembershipJson,
                                    )
                                    when (op) {
                                        Op.Deposit -> assembleDeposit(paramsJson)
                                        Op.Withdraw -> assembleWithdraw(paramsJson)
                                        Op.Transfer -> assembleTransfer(paramsJson)
                                    }
                                }
                                val bundle = withContext(Dispatchers.Default) {
                                    if (USE_RAPIDSNARK) {
                                        val zkeyPath = ensureRapidsnarkZkey(applicationContext)
                                        val t0 = System.nanoTime()
                                        val b = provePolicyTx42JsonRapidsnark(a.circuitInputsJson, zkeyPath)
                                        android.util.Log.d("StellaProve", "rapidsnark prove: ${(System.nanoTime() - t0) / 1_000_000}ms")
                                        b
                                    } else {
                                        provePolicyTx42Json(a.circuitInputsJson)
                                    }
                                }
                                advance(2)
                                val hash = withContext(Dispatchers.IO) {
                                    // Withdraw/transfer go through the relayer so the
                                    // submitting account isn't this wallet's public one
                                    // (deposit is public anyway, so it self-submits). The
                                    // proof's ext_data_hash binds recipient+amount, so the
                                    // relayer can't redirect funds.
                                    if (USE_RELAYER && op != Op.Deposit) {
                                        RelayerClient.relay(
                                            bundle.proof, bundle.publicInputs, a.extDataHash,
                                            a.extRecipient, a.extAmount,
                                            a.encryptedOutput0, a.encryptedOutput1,
                                        )
                                    } else {
                                        val addr = walletManager.address
                                        val entryXdr = SorobanRpc.getAccountEntryXdr(RPC_URL, accountLedgerKey(addr))
                                        val unsigned = buildUnsignedTransact(
                                            POOL_ID, addr, entryXdr,
                                            bundle.proof, bundle.publicInputs, a.extDataHash,
                                            a.extRecipient, a.extAmount,
                                            a.encryptedOutput0, a.encryptedOutput1,
                                        )
                                        val sim = SorobanRpc.simulate(RPC_URL, unsigned)
                                        val signed = finalizeAndSign(unsigned, sim, walletManager.secret())
                                        SorobanRpc.pollTransaction(RPC_URL, SorobanRpc.send(RPC_URL, signed))
                                    }
                                }
                                advance(3)
                                hash
                            },
                            onDone = { hash, proofMs ->
                                if (op == Op.Deposit) pendingDepositBlinding?.let { noteStore.recordDepositBlinding(it) }
                                pendingDepositBlinding = null
                                val notesNow = noteStore.notes()
                                wallet.pending.add(
                                    PendingTx(
                                        id = System.nanoTime(), title = op.verb, amountStroops = pendingAmount,
                                        isPublic = false, createdAtMs = System.currentTimeMillis(),
                                        noteCountAtCreation = notesNow.size,
                                        spentCountAtCreation = notesNow.count { it.spent },
                                    ),
                                )
                                txHash = hash; lastProofMs = proofMs; screen = Screen.Success
                            },
                            onCancel = { screen = Screen.Home },
                        )
                        Screen.Success -> SuccessScreen(sentVerb ?: op.verb, amountXlm, txHash, lastProofMs) { sentVerb = null; screen = Screen.Home }
                        Screen.TxDetail -> selectedActivity?.let { TxDetailScreen(it) { screen = Screen.Home } }
                            ?: run { screen = Screen.Home }
                        Screen.Register -> RegisterScreen(
                            onRegister = doRegister,
                            onRegistered = { screen = Screen.Home },
                            onCancel = { screen = Screen.Home },
                        )
                        Screen.Receive -> ReceiveScreen(
                            mode = walletMode,
                            stellarAddress = walletManager.address,
                            shieldedAddress = shieldedAddr,
                            onClose = { screen = Screen.Home },
                        )
                        Screen.Settings -> SettingsScreen(
                            accounts = remember(accountEpoch) { walletManager.accounts() },
                            activeIndex = walletManager.activeIndex,
                            onSwitch = { i -> walletManager.setActive(i); walletAddr = walletManager.address; accountEpoch++ },
                            onAdd = {
                                withContext(Dispatchers.IO) { walletManager.addAccount() }
                                walletAddr = walletManager.address; accountEpoch++
                            },
                            onRecovery = { screen = Screen.Recovery },
                            onBackup = { screen = Screen.Backup },
                            onViewingKey = { if (shielded != null) screen = Screen.ViewingKey },
                            onAudit = { screen = Screen.AuditScan },
                            onClose = { screen = Screen.Home },
                        )
                        Screen.Recovery -> RecoveryScreen(
                            phrase = walletManager.recoveryPhrase(),
                            onImport = { phrase ->
                                val ok = walletManager.importPhrase(phrase)
                                if (ok) { walletAddr = walletManager.address; accountEpoch++ }
                                ok
                            },
                            onOpenBackup = { screen = Screen.Backup },
                            onClose = { screen = Screen.Home },
                        )
                        Screen.Backup -> BackupScreen(
                            encryptionOk = remember { WalletBackup.selfTest(backupKey()) },
                            onBackup = doBackup,
                            onRestore = doRestore,
                            onClose = { screen = Screen.Recovery },
                        )
                        Screen.Disclosure -> {
                            // Disclosure proves a SINGLE note — the user picks which one.
                            // Skip 0-value notes: a private send scans back empty
                            // output notes (amount 0) that carry nothing to disclose.
                            val discNotes = noteStore.unspentNotes()
                                .filter { it.amount > 0 }
                                .sortedByDescending { it.amount }
                                .map { com.privatepayments.ui.NoteOption(it.leafIndex, it.amount) }
                            DisclosureScreen(
                                notes = discNotes,
                                // Canonical privacy-pool disclosure: prove the SELECTED unspent
                                // note bound to {authority, purpose, fresh anti-replay nonce},
                                // then run the 3-part verify (proof ∧ context ∧ known-root).
                                runDisclosure = { req, leafIndex ->
                                    withContext(Dispatchers.Default) {
                                        val note = noteStore.unspentNotes().firstOrNull { it.leafIndex == leafIndex }
                                            ?: return@withContext null
                                        val sk = shielded ?: return@withContext null
                                        // Fresh nonce so each receipt is single-use (in a real
                                        // flow the verifier supplies this challenge).
                                        val nonce = java.util.UUID.randomUUID().mostSignificantBits.toULong().toString()
                                        val issuedAt = java.time.Instant.now().toString()
                                        val issued = issueDisclosureReceipt(
                                            commitmentTopics, note.leafIndex.toUInt(),
                                            note.amount.toString(), note.blinding, sk.notePrivate, 10u,
                                            "testnet", POOL_ID, req.authority, req.purpose, nonce, issuedAt,
                                        )
                                        // Verifier reconstructs the live pool root from public
                                        // commitments, then checks the receipt against it.
                                        val knownRoot = runCatching { currentPoolRoot(commitmentTopics, 10u) }.getOrNull()
                                        val report = verifyDisclosureReceipt(
                                            issued.receiptJson, listOfNotNull(knownRoot, issued.root),
                                        )
                                        DisclosureResult(
                                            amount = issued.amount,
                                            noteCommitment = issued.noteCommitment,
                                            root = issued.root,
                                            authority = req.authority,
                                            purpose = req.purpose,
                                            proofVerified = report.proofVerified,
                                            contextVerified = report.contextVerified,
                                            knownRoot = report.knownRootStatus,
                                            amountVerified = report.amountVerified,
                                            fullyVerified = report.fullyVerified,
                                            receiptJson = issued.receiptJson,
                                        )
                                    }
                                },
                                onClose = { screen = Screen.Home },
                            )
                        }
                        Screen.ViewingKey -> {
                            val sk = shielded
                            if (sk == null) {
                                screen = Screen.Settings
                            } else {
                                ViewingKeyScreen(
                                    viewKey = "stellaview2:" + android.util.Base64.encodeToString(
                                        sk.encryptionPrivate + sk.nullifierKey, android.util.Base64.NO_WRAP,
                                    ),
                                    onClose = { screen = Screen.Settings },
                                )
                            }
                        }
                        Screen.AuditScan -> AuditScreen(
                            commitments = remember { chainStore.commitmentsSince(0) },
                            nullifiers = remember { chainStore.allNullifiers() },
                            onClose = { screen = Screen.Settings },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Classic public XLM send — no ZK proof, just build+sign (Rust) then submit to
 * Horizon. Runs on entry; shows a spinner, then routes to Success or an inline
 * error with a retry.
 */
@Composable
private fun PublicSendingScreen(
    amountXlm: String,
    send: suspend () -> String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        val result = withContext(Dispatchers.IO) { runCatching { send() } }
        result.onSuccess { onDone(it) }.onFailure { error = it.message ?: "Payment failed" }
    }
    Column(
        Modifier.fillMaxSize().background(Umbra.Bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        if (error == null) {
            CircularProgressIndicator(color = Umbra.Primary, strokeWidth = 3.dp, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(28.dp))
            Text("Sending", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("$amountXlm XLM · public payment on Stellar", color = Umbra.TextMuted, fontSize = 14.sp)
        } else {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(Umbra.Error.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, tint = Umbra.Error, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(24.dp))
            Text("Couldn't send", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = Umbra.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Umbra.SurfaceElevated)
                    .clickable { attempt++ }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) { Text("Try again", color = Umbra.Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Surface)
                .clickable { onCancel() }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (error == null) "Cancel" else "Back", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
    }
}

/**
 * A full-rect shape with a circular hole cut out (even-odd fill: rect + oval
 * overlap cancels). Used to clip the "old mode" reveal layer so the growing
 * circle is transparent, letting the live "new mode" layer underneath show
 * through — the standard circular-reveal wipe, with no bitmap/snapshot APIs.
 */
private class RevealClipShape(private val center: Offset, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            if (radius > 0f) addOval(Rect(center - Offset(radius, radius), center + Offset(radius, radius)))
        }
        return Outline.Generic(path)
    }
}

private fun hexToBytes(h: String): ByteArray {
    val s = h.removePrefix("0x")
    return ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}

/** Shareable shielded address = base64(note_pub(32) ‖ enc_pub(32)), "stella:"-prefixed. */
private fun selfShieldedAddress(noteKey: ByteArray, encPub: ByteArray): String {
    val blob = hexToBytes(notePublicKey(noteKey)) + encPub
    return "stella:" + android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)
}

/** Parse a "stella:" shielded address into (note_pub_hex, enc_pub_hex). */
private fun parseShieldedAddress(addr: String): Pair<String, String> {
    val blob = android.util.Base64.decode(addr.trim().removePrefix("stella:"), android.util.Base64.NO_WRAP)
    require(blob.size == 64) { "invalid shielded address" }
    fun hex(b: ByteArray) = "0x" + b.joinToString("") { "%02x".format(it) }
    return Pair(hex(blob.copyOfRange(0, 32)), hex(blob.copyOfRange(32, 64)))
}

/**
 * Copy the bundled `policy_tx_4_2_final.zkey` asset (Task B6 rapidsnark
 * backend) to internal storage once and return the on-disk path — assets
 * aren't directly file-pathable, and rapidsnark's zkey loader needs a real
 * path. Re-copies when missing or when the APK is newer (app update may ship
 * a new zkey).
 *
 * NB: no `assets.openFd()` here — it throws on AAPT-compressed assets
 * ("cannot be opened as a file descriptor"); stream-copy works either way.
 * Copies via temp + rename so a killed copy can't leave a truncated zkey.
 */
private fun ensureRapidsnarkZkey(context: android.content.Context): String {
    val dest = java.io.File(context.filesDir, RAPIDSNARK_ZKEY_ASSET)
    val apkTime = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    if (!dest.exists() || dest.lastModified() < apkTime) {
        val tmp = java.io.File(context.filesDir, "$RAPIDSNARK_ZKEY_ASSET.tmp")
        context.assets.open(RAPIDSNARK_ZKEY_ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
    }
    return dest.absolutePath
}
