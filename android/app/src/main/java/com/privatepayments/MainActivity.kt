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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import com.privatepayments.net.IndexerClient
import com.privatepayments.net.RelayerClient
import com.privatepayments.net.SorobanRpc
import com.privatepayments.state.ChainStore
import com.privatepayments.state.CoinSelector
import com.privatepayments.state.NoteStore
import com.privatepayments.state.WalletBackup
import com.privatepayments.ui.AmountScreen
import com.privatepayments.ui.BackupScreen
import com.privatepayments.ui.DisclosureResult
import com.privatepayments.ui.DisclosureScreen
import com.privatepayments.ui.HomeScreen
import com.privatepayments.ui.ProofScreen
import com.privatepayments.ui.ReceiveScreen
import com.privatepayments.ui.ConfirmScreen
import com.privatepayments.ui.OnboardingScreen
import com.privatepayments.ui.RecoveryScreen
import com.privatepayments.ui.RegisterScreen
import com.privatepayments.ui.SettingsScreen
import com.privatepayments.ui.SplashScreen
import com.privatepayments.ui.copyToClipboard
import com.privatepayments.ui.openUrl
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
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.UmbraTheme
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
import uniffi.prover_ffi.buildUnsignedTransact
import uniffi.prover_ffi.decodeNullifierTopic
import uniffi.prover_ffi.currentPoolRoot
import uniffi.prover_ffi.deriveKeys
import uniffi.prover_ffi.issueDisclosureReceipt
import uniffi.prover_ffi.verifyDisclosureReceipt
import uniffi.prover_ffi.finalizeAndSign
import uniffi.prover_ffi.provePolicyTx22Json
import uniffi.prover_ffi.rebuildInputPath
import uniffi.prover_ffi.scanNote

private const val POOL_ID = "CCDFQ5D32OZVSK5BMNZMWZSY4U6VVJBHW4MEHEUCZOURZIP3C7UUJW4V"
private const val ASP_MEMBERSHIP_ID = "CC5XHHWNZDBLDBSYI54YBXN2RSJU52T4QTHMEYEFGIBG7CYAWLNWV5ZO"
private const val RPC_URL = "https://soroban-testnet.stellar.org"

// Route withdraw/transfer through the relayer (its account is the on-chain
// source/sender/fee-payer) so this wallet's public account stays unlinkable.
// Requires the relayer service reachable at RelayerClient.BASE (adb reverse
// tcp:8090). When false, the wallet self-submits all ops from its own account
// (pays its own gas) — no relayer server needed. Deposit always self-submits.
private const val USE_RELAYER = false

private enum class Screen { Splash, Onboarding, Home, Amount, Confirm, Proof, Success, Recovery, Disclosure, Backup, Settings, Receive, Register }

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

        // Per-op params templates (live-rebuilt in-app before each tx).
        val params: Map<Op, String> = Op.entries.associateWith { op ->
            assets.open(op.paramsAsset).bufferedReader().use { it.readText() }
        }
        // Constant empty non-membership proof template (from the deposit fixture).
        val nmFixture = org.json.JSONObject(params.getValue(Op.Deposit))
            .getJSONObject("non_membership_proof").toString()

        val walletManager = WalletManager(this)

        setContent {
            UmbraTheme {
                Surface(color = Umbra.Bg) {
                    val ctx = this@MainActivity
                    val wallet = remember { WalletState() }
                    var screen by remember { mutableStateOf(Screen.Splash) }
                    var op by remember { mutableStateOf(Op.Deposit) }
                    var txHash by remember { mutableStateOf("") }
                    var pendingAmount by remember { mutableStateOf(0L) }
                    var pendingRecipient by remember { mutableStateOf("") }
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
                    var sync by remember { mutableStateOf("Connecting to indexer…") }

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
                                d.commitments.forEach { chainStore.appendCommitment(it.seq, it.commitmentTopic, it.value) }
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
                    LaunchedEffect(accountEpoch) {
                        while (true) {
                            // 0. Pull only the DELTA since our durable cursor, append it to
                            //    the global chain mirror (no more full re-fetch every poll).
                            val delta = withContext(Dispatchers.IO) { IndexerClient.fetchSince(chainStore.cursor()) }
                            val sk = shielded
                            if (delta.reachable && sk != null) {
                                withContext(Dispatchers.Default) {
                                    delta.commitments.forEach { chainStore.appendCommitment(it.seq, it.commitmentTopic, it.value) }
                                    delta.leaves.forEach { lv ->
                                        runCatching { decodeAspLeaf(lv.value) }.getOrNull()?.let { chainStore.appendAspLeaf(lv.seq, it) }
                                    }
                                    chainStore.setCursor(delta.newCursor)
                                    // Full ordered lists (from local DB) feed proving.
                                    commitmentTopics = chainStore.commitmentTopics()
                                    aspLeaves = chainStore.aspLeaves()
                                    // 1. Scan only commitments THIS account hasn't seen yet,
                                    //    with its per-seed keys. (On account switch, scannedSeq=0
                                    //    → it rescans everything once.)
                                    val from = chainStore.scannedSeq(walletManager.activeIndex)
                                    chainStore.commitmentsSince(from).forEach { ev ->
                                        runCatching {
                                            scanNote(ev.topic, ev.value, sk.encryptionPrivate, sk.notePrivate)
                                        }.getOrNull()?.let { note ->
                                            noteStore.upsertNote(
                                                note.leafIndex.toLong(), note.commitment,
                                                note.amount.toLong(), note.nullifier, note.blinding,
                                            )
                                        }
                                    }
                                    chainStore.setScannedSeq(walletManager.activeIndex, chainStore.cursor())
                                    // 2. Record new on-chain nullifiers, then reconcile.
                                    val seen = delta.nullifierTopics.mapNotNull {
                                        runCatching { decodeNullifierTopic(it) }.getOrNull()
                                    }
                                    noteStore.addNullifiers(seen)
                                    noteStore.reconcile()
                                    wallet.applyNotes(noteStore.unspentBalanceStroops(), noteStore.notes())
                                }
                            }
                            // Public (Stellar account) XLM balance.
                            walletAddr?.let { addr ->
                                val pub = withContext(Dispatchers.IO) {
                                    runCatching {
                                        accountBalanceStroops(SorobanRpc.getAccountEntryXdr(RPC_URL, accountLedgerKey(addr)))
                                    }.getOrNull()
                                }
                                wallet.applyPublic(pub)
                            }
                            val c = noteStore.counts()
                            sync = if (!delta.reachable) "Indexer unreachable"
                            else "Synced · ${chainStore.commitmentCount()} commitments · ${c.unspent} unspent / ${c.spent} spent note(s)"
                            delay(5000)
                        }
                    }

                    // Avatar = the active account as a letter (A = account 0, B = 1, …).
                    val accountLabel = remember(accountEpoch) { ('A' + walletManager.activeIndex.coerceIn(0, 25)).toString() }
                    // Spending requires ASP enrollment — route unregistered accounts
                    // through Register first (receiving needs no enrollment).
                    val start: (Op) -> Unit = { chosen ->
                        if (walletAddr != null) { op = chosen; screen = if (isRegistered) Screen.Amount else Screen.Register }
                    }
                    val amountXlm = "%.4f".format(pendingAmount / 1e7)

                    when (screen) {
                        Screen.Splash -> SplashScreen()
                        Screen.Onboarding -> OnboardingScreen(
                            onCreate = { withContext(Dispatchers.IO) { walletManager.createNew() } },
                            onImport = { p -> withContext(Dispatchers.IO) { walletManager.importPhrase(p) } },
                            onDone = { walletAddr = walletManager.address; accountEpoch++; screen = Screen.Home },
                        )
                        Screen.Home -> HomeScreen(
                            address = walletAddr ?: "",
                            accountLabel = accountLabel,
                            balanceText = wallet.balanceText(),
                            publicText = wallet.publicText(),
                            activity = wallet.activity,
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
                        )
                        Screen.Amount -> AmountScreen(
                            title = op.title,
                            isPublic = op.isPublic,
                            // Spends are capped at the 2-note circuit limit.
                            maxStroops = if (op == Op.Deposit) null else CoinSelector.maxSpendable(noteStore.unspentNotes()),
                            recipientLabel = op.recipientLabel,
                            recipientHint = op.recipientHint,
                            recipientOptional = op.recipientOptional,
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
                                pendingRecipient.length > 16 -> "${pendingRecipient.take(10)}…${pendingRecipient.takeLast(6)}"
                                else -> pendingRecipient
                            },
                            typeLabel = when (op) {
                                Op.Deposit -> "Public deposit"
                                Op.Withdraw -> "Private withdrawal"
                                Op.Transfer -> "Private send"
                            },
                            onConfirm = { screen = Screen.Proof },
                            onCancel = { screen = Screen.Amount },
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
                                val bundle = withContext(Dispatchers.Default) { provePolicyTx22Json(a.circuitInputsJson) }
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
                            onDone = { hash ->
                                if (op == Op.Deposit) pendingDepositBlinding?.let { noteStore.recordDepositBlinding(it) }
                                pendingDepositBlinding = null
                                txHash = hash; screen = Screen.Success
                            },
                            onCancel = { screen = Screen.Home },
                        )
                        Screen.Success -> SuccessScreen(op.verb, amountXlm, txHash) { screen = Screen.Home }
                        Screen.Register -> RegisterScreen(
                            onRegister = doRegister,
                            onRegistered = { screen = Screen.Home },
                            onCancel = { screen = Screen.Home },
                        )
                        Screen.Receive -> ReceiveScreen(
                            stellarAddress = walletManager.address,
                            shieldedAddress = shielded?.let { "stella:" + android.util.Base64.encodeToString(it.notePublic + it.encryptionPublic, android.util.Base64.NO_WRAP) },
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
                            // Disclosure proves a SINGLE note — show that note's amount.
                            val largest = noteStore.unspentNotes().maxOfOrNull { it.amount } ?: 0L
                            DisclosureScreen(
                                spendableLabel = "%.4f XLM".format(largest / 1e7),
                                // Canonical privacy-pool disclosure: prove the largest unspent
                                // note bound to {authority, purpose, fresh anti-replay nonce},
                                // then run the 3-part verify (proof ∧ context ∧ known-root).
                                runDisclosure = { req ->
                                    withContext(Dispatchers.Default) {
                                        val note = noteStore.unspentNotes().maxByOrNull { it.amount }
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
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessScreen(verb: String, amount: String, txHash: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val explorer = "https://stellar.expert/explorer/testnet/tx/$txHash"
    Column(
        Modifier.fillMaxSize().background(Umbra.Bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(Umbra.Success),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Check, null, tint = Umbra.Bg, modifier = Modifier.size(48.dp)) }
        Spacer(Modifier.height(28.dp))
        Text(verb, color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("$amount XLM", color = Umbra.TextSecondary, fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        Text("Confirmed on Stellar testnet · tap to copy", color = Umbra.TextFaint, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "${txHash.take(12)}…${txHash.takeLast(8)}",
            color = Umbra.PrimaryLight, fontFamily = Umbra.Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { copyToClipboard(ctx, "Transaction id", txHash) },
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Umbra.SurfaceElevated)
                .clickable { openUrl(ctx, explorer) }.padding(horizontal = 16.dp, vertical = 10.dp),
        ) { Text("View on stellar.expert ↗", color = Umbra.PrimaryLight, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Primary)
                .clickable { onClose() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Done", color = Umbra.Bg, fontWeight = FontWeight.SemiBold) }
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
