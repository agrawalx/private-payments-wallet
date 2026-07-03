# The Full Viewing Key: a Zcash-Style Nullifier-Key Split

*How one circuit change turned our auditor disclosure from a misleading inflow
log into a true, spend-aware audit — without giving the auditor any power to
move funds.*

---

## 1. The problem that motivated it

Our first viewing key (`stellaview:`) was simply the wallet's X25519
**encryption private key**. Sharing it let an auditor decrypt every note
addressed to the wallet — amounts and dates of everything it ever *received*.

Testing immediately surfaced the flaw:

> Deposit 20 XLM → send 15 → receive 5 back as change.
> The audit reports **25 XLM received**. The wallet only ever held 20.

Two structural blind spots caused this, and both trace to the same root:

1. **Change is indistinguishable from payment.** When a wallet spends a note,
   the transaction pays the recipient *and* returns change to the sender —
   encrypted to the sender's own key, exactly like any incoming payment. A
   decrypt-only key cannot tell "someone paid me 5" from "my own 5 came back."
2. **Spends are invisible.** Whether a note was later consumed is recorded
   on-chain only as a *nullifier* — and computing a note's nullifier required
   the **spend key**. An auditor could see money arrive but never see it move,
   so totals could only ever go up.

Neither is a contract or app bug. The pool contract sees only opaque
commitments and nullifiers; the conflation lives in the cryptographic key
structure itself: **one secret scalar did two unrelated jobs** — proving
ownership *and* deriving nullifiers.

## 2. What Zcash does, and what we borrowed

Zcash Sapling solved exactly this by splitting authority. Its *full viewing
key* contains a **nullifier-deriving key (`nk`)** that can compute the
nullifiers of the holder's notes — revealing when they are spent — while the
spend-authorizing key stays private. Auditors get read access to the full
lifecycle of funds; they can never move them.

We ported the *concept* (not the literal construction) into our
Poseidon2-based note scheme.

### Before

```
note_public = Poseidon2(note_private, 0,               dom=3)   // ownership / address
signature   = Poseidon2(note_private, commitment, path, dom=4)  // ← spend key here
nullifier   = Poseidon2(commitment, path, signature,    dom=2)
```

The spend key (`note_private`) feeds **both** the ownership path and the
nullifier path. Whoever can compute nullifiers can, by construction, also
spend.

### After (the nk split)

```
nk          = Poseidon2(note_private, 0,      dom=5)            // NEW: derived nullifier key
note_public = Poseidon2(note_private, 0,      dom=3)            // unchanged
signature   = Poseidon2(nk, commitment, path,  dom=4)           // ← nk instead of spend key
nullifier   = Poseidon2(commitment, path, signature, dom=2)     // unchanged shape
```

One new domain-separated hash (`dom=5`, previously unused) and one rewired
input. Crucially, `nk` is **derived inside the circuit** from the same
`note_private` that proves ownership — a prover cannot substitute an unrelated
`nk` to forge nullifiers for notes they don't own.

### Why the nk holder still cannot spend

Spending requires reconstructing the note's commitment through
`note_public = Poseidon2(note_private, …)` and proving Merkle inclusion — the
ownership path, which still demands the raw spend key. `nk` is a one-way
Poseidon2 image of that key: it feeds *only* the nullifier path. Preimage
resistance means the auditor can never recover `note_private` from it, and no
circuit constraint accepts `nk` as a substitute for ownership.

## 3. What it enabled

### Viewing key v2

```
stellaview2: base64( encryption_private(32 bytes) ‖ nk(32 bytes) )
```

With both halves, an auditor working purely from public chain data can now:

| Capability | v1 (`stellaview:`) | v2 (`stellaview2:`) |
|---|---|---|
| Decrypt received amounts + dates | ✅ | ✅ |
| See **when a note was spent** | ❌ | ✅ (compute its nullifier, match against on-chain nullifier events) |
| Distinguish **change from real payments** | ❌ | ✅ (a "received" note created in the same tx as one of this key's own spends = change) |
| Report **net unspent balance** | ❌ (inflow only, monotonic) | ✅ |
| Spend or redirect funds | ❌ | ❌ (unchanged — by construction) |

The 20 → 15 → 5 scenario now audits correctly: *received 20, of which change
5, net unspent 5* — instead of "25 received."

### Change detection in the wallet itself

The same mechanics fixed our own Activity feed: change notes used to show as
**"Received"** (the wallet decrypts its own change like any incoming note).
Now a note created in a transaction that also consumed one of the wallet's own
nullifiers is labeled **"Change (to self)"**, with its own filter — the
transaction correlation uses the indexer's toid-based `event_id`, where all
events of one transaction share a prefix.

### One subtlety we hit in practice

The change flag answers *"was this inflow a payment?"* — it must **not** be
subtracted from balance. A self-send converts the entire balance into
change-flagged notes; excluding them from "net unspent" briefly showed a
wallet holding 1 XLM as holding 0. Net unspent = **all** unspent notes the key
can see, change or not; the flag only splits the inflow attribution line.

## 4. Honest boundaries

A v2 key is a serious disclosure. It reveals, **permanently and
irrevocably**, for one account:

- every incoming note — amount and date, past and future;
- every spend event of those notes — the fact and the time.

It still does **not** reveal:

- where withdrawn or transferred funds went (outbound notes are encrypted to
  the *recipient's* key);
- anything about other accounts;
- any ability to move funds.

There is no scoping ("only this quarter") and no revocation — the same
trade-off Zcash full viewing keys make. The app's export screen states this
before showing the key.

## 5. Where it lives

| Layer | Change |
|---|---|
| Circuit (`vendor/circuits/src/keypair.circom`) | New `NullifierKey()` template (Poseidon2, domain 5) |
| Circuit (`transaction.circom`, `policyTransaction.circom`) | Per-input `nk` derivation; `Signature` takes `nk` |
| Prover crate (`vendor/app/crates/core/prover`) | `derive_nullifier_key`; all `compute_signature` call sites pass `nk` |
| FFI (`prover-ffi`) | `KeyBundle.nullifier_key`; `compute_note_nullifier(nk, commitment, leaf_index)` for the auditor path |
| App | `stellaview2:` export, dual-format audit screen with spend-netting, "Change (to self)" activity kind |

Because nullifier derivation is consensus-critical, the change shipped inside
a full redeploy: new constraint system (bundled with the 4-in/2-out arity
upgrade, one trusted-setup ceremony for both), new verifier contract, fresh
pool. Both proof directions were gated on-chain before the app switched over
(deposit `10a9a947…`, spend `27b3d2bd…`).

## 6. What it cost

- ~515 constraints per input (one extra Poseidon2 permutation each) — about
  +2.7% on the 2-in circuit, absorbed invisibly into the 4-in upgrade.
- One extra 32-byte field in the key bundle and viewing key.
- No new trust assumptions: `nk`'s security rests on the same Poseidon2
  preimage resistance the scheme already depends on everywhere else.
