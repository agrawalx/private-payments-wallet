# Stella — Code Walkthrough

A guided tour of how this repo turns Nethermind's **stellar-privacy-pool** (Soroban smart
contracts + Circom zero-knowledge circuits) into **Stella**, a self-custodial Android wallet
that sends *private* payments on the Stellar test network, with all the cryptography running
**on the phone**.

This document is written to be read top-to-bottom by someone who is *not* a cryptographer.
It has these parts:

1. [The one-paragraph version](#1-the-one-paragraph-version)
2. [How a privacy pool works (plain English)](#2-how-a-privacy-pool-works-plain-english)
3. [What we built on top of the reference code](#3-what-we-built-on-top-of-the-reference-code)
4. [The pieces and the languages they're written in](#4-the-pieces-and-the-languages-theyre-written-in)
5. [How Rust and Kotlin talk (UniFFI)](#5-how-rust-and-kotlin-talk-uniffi)
6. [How a proof is made on the phone](#6-how-a-proof-is-made-on-the-phone)
7. [How the contract verifies a proof on-chain](#7-how-the-contract-verifies-a-proof-on-chain)
8. [The indexer, and why it exists](#8-the-indexer-and-why-it-exists)
9. [Full trace: what happens when you tap a button](#9-full-trace-what-happens-when-you-tap-a-button)
10. [Repo map and glossary](#10-repo-map-and-glossary)

---

## 1. The one-paragraph version

Your money lives in a shared on-chain "pool" as a list of **secret notes** (think of them as
sealed envelopes). The only thing the world sees for each note is a scrambled fingerprint called
a **commitment**. To spend, your phone builds a **zero-knowledge proof** that says, in effect:
*"I own some real notes in this pool, here are their one-time spend-receipts (nullifiers), and my
inputs equal my outputs — but I won't tell you which notes, how much, or who I am."* The Soroban
**pool contract** checks the proof, makes sure the notes weren't already spent, optionally moves
public tokens in (deposit) or out (withdraw), and records two brand-new notes. A small **indexer**
service keeps a durable log of the pool's events so your wallet can find the notes that belong to
it. Everything secret — keys, amounts, proving — happens on the device.

---

## 2. How a privacy pool works (plain English)

### Notes (sealed envelopes)

A **note** is just three numbers kept secret on your phone:

```
note = (amount, ownerPublicKey, blinding)
```

- `amount` — how much it's worth.
- `ownerPublicKey` — who can spend it.
- `blinding` — a big random number so two notes of the same amount don't look alike.

The pool never sees those three numbers. It only sees the note's **commitment**:

```
commitment = Poseidon2(amount, ownerPublicKey, blinding)
```

`Poseidon2` is a hash function (a one-way scrambler) chosen because it's cheap to prove things
about inside a zero-knowledge circuit. Given a commitment you cannot work backwards to the amount
or owner — but if you *know* the three secret numbers you can recompute the commitment and prove
it matches. (Reference: `prover/src/crypto.rs::compute_commitment`, domain tag `0x01`.)

All commitments are stored in an append-only **Merkle tree** on-chain. A Merkle tree lets you prove
"this leaf is in the tree" with a short path, and the whole tree is summarized by a single number,
the **root**.

### Spending without revealing

To spend a note you must reveal exactly one thing: its **nullifier**.

```
nullifier = Poseidon2(commitment, leafPosition, signature)   // signature needs your private key
```

The nullifier is deterministic: the *same* note always produces the *same* nullifier, but the
nullifier reveals nothing about which note it came from. The pool keeps a set of "seen" nullifiers.
If a nullifier shows up twice, that's a **double-spend** and the contract rejects it. This is how
privacy pools prevent cheating without knowing what was spent.

### The 2-in / 2-out shape

Every transaction in this pool has the **same shape**: up to **2 input notes** and exactly
**2 output notes**. This is fixed by the circuit (`policy_tx_2_2` = "2 in, 2 out"). It's why the
wallet can only spend two notes per payment (see `CoinSelector` later).

A single rule ties it all together — the **balance equation**, enforced *inside the proof*:

```
sum(inputs) + publicAmount === sum(outputs)
```

`publicAmount` is the only public-facing money knob, and its sign decides the operation:

| Operation    | `publicAmount` (a.k.a. `ext_amount`) | Public token movement              |
|--------------|--------------------------------------|------------------------------------|
| **Deposit**  | positive (`> 0`)                     | tokens move **into** the pool      |
| **Withdraw** | negative (`< 0`)                     | tokens move **out** to a `G…` addr |
| **Transfer** | zero (`== 0`)                        | **nothing public moves at all**    |

Because all three look like the same `transact` call, an outside observer can't tell a private
transfer apart from any other pool activity.

### The ASP: a policy gate

Pure anonymity is a problem for compliance, so privacy pools add an **Association Set Provider
(ASP)** — two more contracts that hold:

- an **allowlist** (an append-only Merkle tree of approved keys), and
- a **denylist** (a sparse Merkle tree of banned keys, empty by default).

The rule: **to *spend* a note, the note's owner key must be in the allowlist and not in the
denylist.** Receiving a note needs no enrollment — only spending does. The proof must be built
against the *live* allowlist/denylist roots, and the pool double-checks those roots by calling the
ASP contracts during `transact`. (We call the admin key that enrolls leaves `gate3`.)

### Finding your money (encrypted notes)

If only commitments are public, how do *you* learn a note was sent to you? When a note is created,
the sender also attaches an **encrypted blob** containing `(amount, blinding)`, locked to the
recipient's encryption key (X25519 + NaCl crypto_box). Your wallet trial-decrypts every commitment
event; if the blob opens, the note is yours and you now know its amount and blinding — enough to
spend it later. (Reference: `prover/src/encryption.rs::{encrypt_output_note, decrypt_output_note}`.)

---

## 3. What we built on top of the reference code

The reference repo (Nethermind's `stellar-private-payments`) ships the hard parts: the Soroban
**pool contract**, the **ASP contracts**, the **Groth16 verifier contract**, the **Circom
circuits**, and a Rust **prover** crate for off-chain proving. It assumes a desktop/server prover
and a full Stellar SDK.

Stella adds the parts that make it a real phone wallet:

- **On-device proving.** The reference prover used **Wasmer** to run Circom's WASM witness
  generator — which won't run on Android. We swapped in **rust-witness**, which transpiles the
  Circom WASM into native ARM code at build time (`prover-ffi/build.rs`). No WASM interpreter ships.
- **A clean Rust↔Kotlin boundary** via **UniFFI** (`prover-ffi/`): the Rust crate compiles to a
  `.so` and exposes ~35 functions to Kotlin. (The reference's "mopro" wrapper was dropped — we use
  UniFFI directly.)
- **Pure-Rust transaction build + sign** (`prover-ffi/src/submit.rs`) so we don't drag the full
  Stellar SDK (and its `reqwest`/`rustls` networking) into the Android library. The network calls
  themselves live in **Kotlin**, over Android's system TLS.
- **A real wallet**: BIP39/SEP-5 seed phrase, multiple accounts, per-account shielded keys, a
  durable on-device note database, in-app amount entry, copy/share, encrypted Google Drive backup,
  and a "proof of funds" disclosure feature.
- **In-app parameter building** so the wallet can deposit/withdraw/transfer **arbitrary amounts**
  with **per-seed keys** (the reference only demonstrated a fixed demo key), including rebuilding
  the ASP membership proof from live chain events.
- **A lightweight indexer** (`indexer/`) that gives the wallet a durable, paginated event feed.

---

## 4. The pieces and the languages they're written in

```
                                   YOUR PHONE
 ┌─────────────────────────────────────────────────────────────────────┐
 │  Android app  (Kotlin / Jetpack Compose)                             │
 │    • UI screens, wallet keys, note database, networking             │
 │                          │  UniFFI (JNA)                             │
 │                          ▼                                           │
 │  prover-ffi  (Rust, compiled to libprover_ffi.so)                   │
 │    • key derivation • param building • ZK proving • tx build/sign   │
 └─────────────────────────────────────────────────────────────────────┘
        │ HTTPS (Kotlin)                         │ HTTP (Kotlin)
        ▼                                        ▼
 ┌──────────────────┐                  ┌────────────────────────────┐
 │  Soroban RPC      │                 │  Indexer (Rust + Postgres) │
 │  (Stellar testnet)│  ◀─ polls ────  │   durable event feed       │
 └──────────────────┘                  └────────────────────────────┘
        │ runs
        ▼
 ┌─────────────────────────────────────────────────────────────────────┐
 │  ON-CHAIN (Soroban smart contracts, Rust/WASM)                       │
 │    • Pool contract  • Groth16 verifier  • ASP membership + non-memb. │
 └─────────────────────────────────────────────────────────────────────┘
```

| Layer | Language | Where | Job |
|-------|----------|-------|-----|
| UI + wallet glue | Kotlin (Compose) | `android/` | screens, keys, note DB, RPC/indexer calls |
| Crypto engine | Rust → `.so` | `prover-ffi/` | derive keys, build params, **prove**, build+sign tx |
| ZK circuits | Circom | reference repo `circuits/` | the math the proof satisfies |
| On-chain logic | Rust → WASM | reference repo `contracts/` | verify proof, move tokens, store notes |
| Event feed | Rust + Postgres | `indexer/` | durable, paginated history of pool events |

---

## 5. How Rust and Kotlin talk (UniFFI)

The crypto lives in Rust but the app is Kotlin. **UniFFI** bridges them automatically.

**On the Rust side** (`prover-ffi/src/lib.rs`):

- `uniffi::setup_scaffolding!();` turns on UniFFI's proc-macro mode (no `.udl` file).
- Functions you want to expose get `#[uniffi::export]`. Plain data structs get
  `#[derive(uniffi::Record)]` (e.g. `FlowArtifacts`, `KeyBundle`, `ProofBundle`, `SelectedNote`).
- All errors collapse into one type: `#[derive(uniffi::Error)] enum ProverError { Failed { msg } }`,
  with `From<anyhow::Error>` so any internal error becomes a single Kotlin exception.

**How the binaries are produced:**

1. `cargo ndk` cross-compiles the crate (`crate-type = ["lib","cdylib","staticlib"]`) for
   `aarch64-linux-android` → **`libprover_ffi.so`**.
2. `uniffi-bindgen generate --library libprover_ffi.so --language kotlin` reads metadata baked into
   the `.so` and emits **`prover_ffi.kt`** (package `uniffi.prover_ffi`). The tiny
   `src/bin/uniffi-bindgen.rs` is just `uniffi::uniffi_bindgen_main()`.
3. The app bundles the `.so` (under `jniLibs/`) and the generated `.kt`. At runtime, the generated
   code uses **JNA** (`Native.load("prover_ffi", …)`) to call into the `.so`.

**Naming:** Rust `snake_case` becomes Kotlin `camelCase`. So Rust `prove_policy_tx_2_2_json`
is called from Kotlin as `provePolicyTx22Json`; `build_unsigned_transact` → `buildUnsignedTransact`.

**What Kotlin actually calls** (imported in `MainActivity.kt` and `WalletManager.kt`):

- *Keys:* `generateMnemonic`, `validateMnemonic`, `mnemonicToAccount`, `accountShieldedKeys`,
  `backupKeyFromMnemonic`, `notePublicKey`.
- *Param building:* `buildDepositParams`, `buildWithdrawParams`, `buildTransferParams`,
  `buildAspProofs`, `applyIdentity`.
- *Assemble + prove:* `assembleDeposit` / `assembleWithdraw` / `assembleTransfer`,
  `provePolicyTx22Json`.
- *Transaction:* `accountLedgerKey`, `buildUnsignedTransact`, `finalizeAndSign`,
  `accountBalanceStroops`.
- *Scanning:* `scanNote`, `decodeNullifierTopic`, `decodeAspLeaf`, `currentPoolRoot`.
- *Disclosure:* `issueDisclosureReceipt`, `verifyDisclosureReceipt`.

> **Why is the *networking* in Kotlin, not Rust?** The Rust HTTP stack (`reqwest` + `rustls`) can't
> initialize under Android's JNA (it expects `rustls-platform-verifier` to be set up). So the Rust
> library is kept **pure** — it only *builds and signs* bytes — and every network round-trip
> (`getLedgerEntries`, `simulateTransaction`, `sendTransaction`, `getTransaction`) runs in Kotlin
> over Android's system TLS (`net/SorobanRpc.kt`).

---

## 6. How a proof is made on the phone

The phone never sends secrets to a server; it proves locally. The engine is
`prove_circuit` in `prover-ffi/src/lib.rs` (used by both the transaction circuit and the
disclosure circuit). Steps:

1. **Inputs as JSON.** The wallet first builds a big JSON of all the circuit inputs (secret +
   public): note amounts, keys, blindings, Merkle paths, ASP proofs, the public amount, the
   ext-data hash, etc. This JSON is the `circuitInputsJson` returned by `assembleDeposit/…`.
2. **Flatten to field elements.** `flatten_input` turns the JSON into the flat list of numbers the
   circuit expects (matching Circom signal names like `membershipProofs[0][0].leaf`). Negative
   numbers are wrapped into the BN254 field (`p − |n|`).
3. **Witness generation (the rust-witness trick).** The native `policytx22_witness` function —
   compiled from the Circom WASM at build time — computes the full witness (every wire value in
   the circuit). This is the step that used to need Wasmer.
4. **Groth16 prove (ark-groth16).** Using the embedded **proving key** (`policy_tx_2_2_proving_key.bin`,
   ~8 MB, baked in via `include_bytes!`) and **R1CS**, it produces a Groth16 proof.
5. **Two encodings out.** `provePolicyTx22Json` returns a `ProofBundle`:
   - `proof` — 256 bytes, uncompressed `A(64) ‖ B(128) ‖ C(64)`, the form the Soroban verifier wants.
   - `proofCompressed` — for a quick *local* self-check (`verify_proof_bundle`) before submitting.
   - `publicInputs` — the public signals as little-endian 32-byte chunks, in circuit order:
     `[root, publicAmount, extDataHash, nullifier0, nullifier1, commitment0, commitment1,
     membershipRoot×2, nonMembershipRoot×2]`.

On a phone this takes a few seconds — which is exactly why the UI has a narrated "proof moment"
screen with steps (assemble → prove → submit → done).

---

## 7. How the contract verifies a proof on-chain

The pool contract's entry point is `transact(proof, ext_data, sender)`
(reference `contracts/pool/src/pool.rs`). The real work is in `internal_transact`, which performs
these checks **in order** and only changes state if *all* pass:

1. **Known root?** `is_known_root(proof.root)` — the pool keeps the last **90** Merkle roots, so a
   proof built against a slightly-stale root still works. Unknown root → reject.
2. **Nullifiers unspent?** Each `input_nullifier` is looked up in the on-chain spent-set. Seen
   before → `AlreadySpentNullifier` (double-spend).
3. **Ext-data hash matches?** The contract re-hashes `ext_data` (Keccak256 of the sorted XDR,
   reduced mod the BN254 field) and requires it equals `proof.ext_data_hash`. This **binds the
   proof to this exact recipient and amount** — a relayer can't redirect your withdrawal.
4. **Public amount matches?** Recomputes `calculate_public_amount(ext_amount)` and requires it
   equals `proof.public_amount` (positive stays positive; negative becomes field-negative).
5. **ASP roots match the live ASP contracts?** This is the policy gate:

   ```rust
   let member_root     = Self::get_asp_membership_root(env)?;      // cross-contract get_root()
   let non_member_root = Self::get_asp_non_membership_root(env)?;
   if member_root != proof.asp_membership_root
       || non_member_root != proof.asp_non_membership_root {
       return Err(Error::InvalidProof);
   }
   ```

   Combined with the circuit (which proves each *input* note's owner key is a leaf in the allowlist
   and absent from the denylist), this enforces: **every note you spend belongs to an enrolled key.**
6. **Verify the Groth16 proof.** `verify_proof` packs the public inputs in the circuit's exact order
   and calls the **Groth16 verifier contract** (`CircomGroth16VerifierClient::verify`), which runs
   the BN254 pairing check using Soroban's native crypto host functions. The verification key is
   compiled into that contract.

**Then, the state changes** (this is the "on-chain state change" list):

- **Mark nullifiers spent** and emit a **`new_nullifier_event`** per input.
- **If `ext_amount < 0` (withdraw):** `token.transfer(pool → recipient, |ext_amount|)`.
  (For deposit, the *positive* transfer `sender → pool` happened up front in `transact` before
  `internal_transact`.)
- **Append the two new commitments** to the Merkle tree (`insert_two_leaves`), producing a new root
  that's pushed into the 90-root history.
- **Emit two `new_commitment_event`s**, each carrying the commitment, its leaf index, and the
  **encrypted note blob** — the breadcrumbs recipients use to find their money.

The contract therefore never learns amounts, owners, or which notes were spent — it trusts the math
of the proof plus the public-amount/token bookkeeping.

### The circuit, in one breath

`policy_tx_2_2.circom` proves, for the 2 inputs and 2 outputs: each input commitment is well-formed
and owned by you; each nullifier is the correct function of the note; each *non-zero* input is
included in the Merkle tree at its claimed position; each input owner is in the ASP allowlist and
not in the denylist; outputs are well-formed and range-checked; no two inputs share a nullifier;
and the **balance equation** `sum(inputs) + publicAmount === sum(outputs)` holds. The public
signals are exactly what the contract re-checks above.

---

## 8. The indexer, and why it exists

The indexer (`indexer/`, Rust + Postgres, ~440 lines) is a small backend with one job: **be the
durable, queryable log of pool events** between the chain and the wallet.

**The loop** (`src/main.rs`, `src/rpc.rs`, `src/db.rs`):

1. Poll Soroban RPC `getEvents` for the configured contract IDs (pool + ASP), wildcard topics
   (`"**"` = all event types), every ~5 seconds.
2. Resume from a saved **RPC cursor** (opaque token from the last page). On a cold start with no
   cursor, it begins ~24h back (`latest − 17,280` ledgers) — because RPC only retains recent events.
3. Insert new events into Postgres (`events` table). `event_id` is `UNIQUE`, so re-polling is a
   harmless no-op. Each row also gets a local monotonic `seq` (a `BIGSERIAL`).

**The feed** (`src/api.rs`): `GET /events?cursor=<seq>&limit=<n>` returns
`{ "cursor": <last seq>, "count": n, "events": [...] }`, where events are those with
`seq > cursor`, oldest first, capped at `limit` (default 300, max 1000). The wallet remembers the
returned `cursor` and asks again for just the delta.

**Why not read the chain directly?** Soroban RPC **forgets old events** (short retention). A wallet
that queried RPC directly couldn't rebuild older history. The indexer captures events into Postgres
**permanently**, gives a clean `seq`-based cursor, and lets the wallet rebuild its Merkle tree and
ASP tree from a complete record. On the device, the wallet reaches it via `adb reverse tcp:8080`
(see `net/IndexerClient.kt`, base URL `http://127.0.0.1:8080`).

**Which events the wallet cares about** (it filters by `topic[0]`, decoded from a base64-XDR symbol):

| Event | Emitted by | Wallet uses it to… |
|-------|------------|--------------------|
| `new_commitment_event` | pool | find incoming notes (trial-decrypt) **and** rebuild the commitment tree to prove inclusion when spending |
| `new_nullifier_event` | pool | detect which of its notes are already spent (reconcile its balance) |
| `LeafAdded` | ASP membership | rebuild the allowlist tree, in index order, to regenerate its membership proof |

> **Watch-out (a real bug we hit):** the wallet must **page through the entire feed**. An earlier
> version fetched only `limit=100`; once the chain had >100 events, the newest deposits fell off
> the first page and silently never appeared. `IndexerClient.fetchStatus()` now loops on the cursor
> until the feed is exhausted.

---

## 9. Full trace: what happens when you tap a button

All three buttons share the same machine; only the parameters differ. Below, "[Rust]" means an FFI
call into `libprover_ffi.so`, "[Kotlin]" means in-app, "[RPC]" means a network call to Soroban,
"[Indexer]" means the event feed, and "[chain]" means an on-chain state change.

### Common background: the sync loop (always running)

`MainActivity.kt` runs a 5-second loop (`LaunchedEffect(accountEpoch)`):

1. [Indexer] `IndexerClient.fetchStatus()` — page through `/events`, bucket into `commitments`,
   `nullifierTopics`, `leafAddedValues`.
2. [Rust] for each commitment: `scanNote(topic, value, encPriv, notePriv)` → if it's yours, returns
   `(amount, leafIndex, commitment, blinding, nullifier)`.
3. [Kotlin] `NoteStore.upsertNote(...)` saves it in SQLite (`stella_state_<account>.db`).
4. [Rust] `decodeNullifierTopic(...)` for each spent nullifier → [Kotlin] `NoteStore.addNullifiers`.
5. [Kotlin] `NoteStore.reconcile()` marks a note `spent` once its nullifier is seen on-chain;
   balance = `SUM(amount) WHERE NOT spent`.
6. [Rust] `accountBalanceStroops(getAccountEntryXdr(...))` for the public XLM balance.
7. [Kotlin] `WalletState.applyNotes(...)` refreshes the balance + activity list.

This loop is how money "appears" after any transaction confirms.

---

### Trace A — DEPOSIT (public XLM → shielded note)

You tap **Deposit**, enter an amount, tap confirm. (`Op.Deposit`, `isPublic = true`.)

**On the phone (build → prove → sign):**

1. [Rust] `buildDepositParams(fixture, amount, commitmentTopics, depth=10)` — sets the deposit
   amount, creates one output note to yourself with a **fresh random blinding**, and stamps the
   **live pool root** (rebuilt from `commitmentTopics`). The wallet also stashes this output
   blinding in `pendingDepositBlinding` so it can later label the note "Deposit".
2. [Rust] `buildAspProofs(myNotePub, aspLeaves, depth=10, nmFixture)` — rebuilds your ASP
   **membership** proof from the live `LeafAdded` leaves; non-membership is the constant empty-tree
   proof.
3. [Rust] `applyIdentity(params, camelCase=false, notePriv, encPub, membership, nonMembership)` —
   splices *your* per-seed spend identity and ASP proofs into the params (snake_case for deposit).
4. [Rust] `assembleDeposit(params)` → `FlowArtifacts` = `{ circuitInputsJson, extRecipient,
   extAmount (positive), encryptedOutput0/1, extDataHash }`. (The single output is padded to 2; the
   second is a zero-value note, both encrypted to you.)
5. [Rust] `provePolicyTx22Json(circuitInputsJson)` → `ProofBundle { proof, publicInputs }`
   *(the multi-second proof moment).*

**Submit (Kotlin networking + pure-Rust packing):**

6. [RPC] `SorobanRpc.getAccountEntryXdr(accountLedgerKey(addr))` — fetch your account entry (for the
   sequence number).
7. [Rust] `buildUnsignedTransact(POOL_ID, addr, entryXdr, proof, publicInputs, extDataHash,
   extRecipient, extAmount, encOut0, encOut1)` — build the unsigned `transact` envelope.
8. [RPC] `SorobanRpc.simulate(unsigned)` — Soroban simulation returns fees, resource footprint, auth.
9. [Rust] `finalizeAndSign(unsigned, sim, secret)` — fold in the simulated data and **Ed25519-sign**
   the transaction (pure Rust, no SDK).
10. [RPC] `SorobanRpc.send(signed)` → tx hash, then `pollTransaction(hash)` until `SUCCESS`.

**On-chain (`transact` → `internal_transact`):**

11. [chain] Because `ext_amount > 0`: `token.transfer(you → pool, amount)` — **public XLM enters the
    pool** (this part is visible on-chain).
12. [chain] Verify root, nullifiers (here the inputs are zero-value dummies), ext-data hash, public
    amount, ASP roots; verify the Groth16 proof.
13. [chain] Append two commitments (your real note + a zero note), push new root, emit two
    **`new_commitment_event`s**.

**Back on the phone:**

14. [Kotlin] `onDone`: `NoteStore.recordDepositBlinding(...)`, show the Success screen.
15. Within ~5s the sync loop scans the new commitment, matches the blinding → the note is saved and
    labeled **Deposit**, and your shielded balance goes up.

> A deposit reveals your address and the amount on-chain (that's the "public" amber framing in the
> UI). Everything you do *after* depositing is private.

---

### Trace B — WITHDRAW (shielded note → public XLM to any `G…` address)

You tap **Withdraw**, paste a recipient `G…` address, enter an amount. (`Op.Withdraw`,
`isPublic = false`, `ext_amount < 0`.)

1. [Kotlin] `CoinSelector.select(unspentNotes, amount)` — pick **≤2** notes that cover the amount
   with the least change. If even the two biggest notes can't cover it, the Amount screen blocks you
   and explains the **2-note limit** up front (`CoinSelector.maxSpendable`).
2. [Rust] `buildWithdrawParams(fixture, amount, recipientG, selectedNotes, commitmentTopics,
   depth=10)` — builds Merkle inclusion paths for the chosen notes; the flow auto-computes a
   **change note** back to you with a random blinding.
3. [Rust] `buildAspProofs(...)` + `applyIdentity(..., camelCase=true, ...)` (camelCase for withdraw).
4. [Rust] `assembleWithdraw(params)` → `FlowArtifacts` (here `extAmount` is **negative**,
   `extRecipient` is the `G…` address).
5. [Rust] `provePolicyTx22Json(...)` → proof.
6–10. Same submit path as deposit (get entry → build → simulate → sign → send → poll).
11. [chain] Verify everything; mark the spent notes' **nullifiers** (emit `new_nullifier_event`s);
    because `ext_amount < 0`: `token.transfer(pool → recipient, |amount|)` — **public XLM leaves the
    pool to the `G…` address**; append the change note + a zero note; emit `new_commitment_event`s.
12. [Kotlin] Sync loop: the spent notes' nullifiers are seen → those notes flip to **spent** and
    drop out of the balance; the change note is scanned back in. Activity shows **Transferred**.

The link between your shielded note and the public payout is hidden by the proof — the chain only
sees "the pool paid out X to this address," not which note funded it.

---

### Trace C — TRANSFER (private payment to another shielded address)

You tap **Send**, paste a recipient **`stella:` shielded address** (or leave it blank to re-shield
to yourself), enter an amount. (`Op.Transfer`, `isPublic = false`, `ext_amount == 0`.)

A shielded address is just `"stella:" + base64(recipientNotePub ‖ recipientEncPub)` — 64 bytes that
let you pay someone with no on-chain lookup. (`selfShieldedAddress` / `parseShieldedAddress`.)

1. [Kotlin] `parseShieldedAddress(recipient)` → `(recipientNotePub, recipientEncPub)` (or your own
   keys if blank).
2. [Kotlin] `CoinSelector.select(...)` — same ≤2-note rule.
3. [Rust] `buildTransferParams(fixture, amount, recipientNotePub, recipientEncPub, selectedNotes,
   commitmentTopics, depth=10)` — output0 = the payment note **encrypted to the recipient's keys**;
   output1 = your **change** note (`change = total − amount`), encrypted to you.
4. [Rust] `buildAspProofs(...)` + `applyIdentity(..., camelCase=true, ...)`.
5. [Rust] `assembleTransfer(params)` → `FlowArtifacts` with **`extAmount == 0`** (no public movement).
6. [Rust] `provePolicyTx22Json(...)` → proof.
7–11. Same submit path; then on-chain: verify, mark input nullifiers, **no token transfer**
    (`ext_amount == 0`), append the recipient note + your change note, emit two
    `new_commitment_event`s.
12. **On the recipient's phone**, their sync loop trial-decrypts the new commitment with *their*
    encryption key — the blob opens, so they learn the amount + blinding, save the note, and their
    shielded balance goes up. On your phone, the spent notes go **spent** and your change note
    appears.

Nothing about this payment — sender, recipient, or amount — is visible on-chain. It looks identical
to any other `transact`. *(This is why the activity label is just "Transferred": a privacy-pool
transfer carries no on-chain sender/recipient identity to show.)*

> **One prerequisite for spending:** the spending account's note key must be **ASP-enrolled** (the
> admin/gate3 calls `insert_leaf` on the ASP membership contract for that key's leaf, which you can
> compute with `asp_membership_leaf_dec`). Receiving needs no enrollment. Enrolling a new leaf
> changes the allowlist root, which is exactly why the wallet rebuilds its ASP proof from live
> `LeafAdded` events every sync.

---

## 10. Repo map and glossary

### Where things live

```
prover-ffi/                    Rust crypto engine → libprover_ffi.so (UniFFI)
  src/lib.rs                   all ~35 FFI functions (keys, params, prove, scan, disclosure)
  src/submit.rs                pure-Rust Soroban tx build + Ed25519 sign (no SDK)
  src/wallet.rs                BIP39 / SEP-5 derivation + shielded key derivation
  src/ext_data_hash.rs         off-chain Keccak256 ext-data hash (mirrors the contract)
  build.rs                     transpiles Circom WASM → native witness (rust-witness)
  circuits/                    embedded proving keys + r1cs + witness wasm
  fixtures/                    params templates (deposit/withdraw/transfer)

android/app/src/main/java/com/privatepayments/
  MainActivity.kt              screens, sync loop, the runProof pipeline
  wallet/WalletManager.kt      multi-account SLIP-0010 keys (EncryptedSharedPreferences)
  state/NoteStore.kt           SQLite notes + nullifiers + reconcile (balance source of truth)
  state/CoinSelector.kt        ≤2-note selection + maxSpendable (the 2-note cap)
  state/WalletBackup.kt        AES-256-GCM encrypt/decrypt of the note export
  net/SorobanRpc.kt            getLedgerEntries / simulate / send / getTransaction (system TLS)
  net/IndexerClient.kt         paginated /events feed, decodes event topics
  net/DriveBackup.kt           encrypted blob ↔ Google Drive appDataFolder (OAuth)
  ui/                          Compose screens + theme
uniffi/prover_ffi/prover_ffi.kt  GENERATED Kotlin bindings (JNA)

indexer/                       Rust + Postgres durable event feed
  src/main.rs                  config, startup, poll loop
  src/rpc.rs                   Soroban getEvents / getLatestLedger client
  src/db.rs                    Postgres schema, insert, cursor, paginated query
  src/api.rs                   GET /events, GET /health

(reference, vendored as a cargo git dep)
  contracts/pool/              the pool contract: transact / internal_transact
  contracts/circom-groth16-verifier/   on-chain BN254 Groth16 pairing check
  contracts/asp-membership/    allowlist (append-only Merkle tree, insert_leaf)
  contracts/asp-non-membership/ denylist (sparse Merkle tree, empty by default)
  circuits/                    policy_tx_2_2.circom, selectiveDisclosure_1.circom
  prover crate                 crypto.rs / merkle.rs / encryption.rs / flows.rs
```

### Glossary

- **Note** — a secret `(amount, ownerPublicKey, blinding)`; your unit of money in the pool.
- **Commitment** — `Poseidon2(amount, pubkey, blinding)`; the public fingerprint of a note.
- **Blinding** — a random number that makes equal-amount notes look different. Stored per note.
- **Nullifier** — a note's one-time spend-receipt; reveals nothing, but reused = double-spend.
- **Merkle tree / root** — the structure holding all commitments; the root is its one-number summary.
- **Groth16 / ZK proof** — a small proof that a statement is true without revealing the secrets.
- **Public amount (`ext_amount`)** — the only public money knob: `+` deposit, `−` withdraw, `0` transfer.
- **ASP** — Association Set Provider: the allowlist + denylist that gate *spending* (not receiving).
- **gate3 / admin** — the key authorized to `insert_leaf` (enroll a note key) on the ASP allowlist.
- **ext data / ext-data hash** — recipient + amount + encrypted notes, hashed and bound into the proof.
- **Indexer** — the durable, paginated event feed the wallet syncs from.
- **UniFFI** — the tool that exposes the Rust crate to Kotlin as `libprover_ffi.so` + `prover_ffi.kt`.
- **rust-witness** — transpiles the Circom witness WASM to native code so proving runs on Android.
- **Shielded address (`stella:`)** — `base64(notePub ‖ encPub)`; lets someone pay you privately.

---

*This walkthrough describes the code as it currently stands. The clearest end-to-end example in the
codebase is the test `submit_perseed_p2p_transfer_onchain` in `prover-ffi/src/lib.rs`, which runs a
real deposit → prove → build → simulate → sign → send → scan against testnet.*