# Stella — private payments wallet on Stellar

A self-custodial Android wallet for **shielded payments** on the Stellar testnet, built on
Nethermind's privacy-pool Soroban contracts + Circom circuits, with **zero-knowledge proofs
generated on-device**. Deposit publicly into a pool, then send/withdraw privately — amounts and
links are hidden by a Groth16 proof the phone produces itself. A second, fully classic "Daylight"
mode sits right alongside it for plain public XLM.

<p align="center">
  <img src="docs/screenshots/home-shielded.png" width="230" alt="Shielded home — balance revealed" />
  <img src="docs/screenshots/home-public.png" width="230" alt="Public home with activity" />
## Highlights

- **On-device Groth16 proving.** No server ever sees your keys, amounts, or recipients — the
  phone builds and proves the transaction itself (`prover-ffi/`, Rust compiled to a native `.so`).
- **Two faces, one wallet.** A tap-or-swipe slider flips between **☀ Public** (plain Stellar XLM)
  and **🔒 Shielded** (the privacy pool), each with its own palette and a circular-reveal
  transition anchored to the thumb.
- **Full activity history**, mode-aware and grouped by day for public payments, with a tap-through
  to [stellar.expert](https://stellar.expert) on any transaction.
- **A local address book.** Save a name against a public `G…` address and/or a shielded `stella:`
  address, then send to it directly — no more re-copying addresses out of a block explorer.
- **MetaMask-style "your other addresses."** Moving XLM between your own accounts shows them as
  one-tap chips right on the Send screen (see below) instead of a manual copy-paste round trip.
- **Multi-account, BIP39/SEP-5.** One recovery phrase, unlimited accounts, each with its own
  shielded keys and note store — plus encrypted Google Drive backup of your note history.

<p align="center">
  <img src="docs/screenshots/send-quickpick.png" width="260" alt="Send screen showing quick-pick chips for your other accounts" />
</p>

```
Phone (Kotlin UI + Rust prover via UniFFI)
   │  HTTPS                         │ HTTP
   ▼                               ▼
Soroban RPC (public testnet)   Indexer (Rust + Postgres)  ← you run this
   │ runs                          (durable pool/ASP event feed for the wallet)
   ▼
On-chain: Pool · Groth16 verifier · ASP (membership + non-membership)
```

The wallet needs **two** backends: the public **Soroban RPC** (no setup) and an **indexer**
(you run it, or host one). The relayer is **off** by default — transactions self-submit and pay
their own gas, so no relayer server is required.

For a deep dive into the cryptography, contracts, and full request traces, see
**[walkthrough.md](walkthrough.md)**.

---

## Prerequisites

- **Rust** (rustup) + `cargo install cargo-ndk` + `rustup target add aarch64-linux-android`
- **Android SDK + NDK** (r27+). Set `ANDROID_HOME` and `ANDROID_NDK_HOME`.
- **Docker** (for the indexer's Postgres)
- **adb** (platform-tools) + a physical **arm64 Android** device (or an arm64 emulator) with USB debugging
- **JDK 17** — Gradle auto-provisions one via foojay if you don't have it

---

## 1. Run the indexer

The indexer polls the Soroban RPC for the pool + ASP contract events and serves them to the wallet.

> **You must seed it from the provided dump — do not start a fresh indexer from scratch.**
> Soroban testnet RPC only retains ~24h of events, so a fresh indexer can't fetch the pool's earlier
> history. The wallet rebuilds the *complete* commitment + ASP Merkle trees from that history, so a
> partial indexer produces wrong roots and **spends will fail**. `indexer/seed/stella_events.sql`
> contains the full event history; restore it first, then the indexer only fetches *new* events from
> there. Inserts are idempotent (`event_id` is `UNIQUE`, `ON CONFLICT DO NOTHING`), so any overlap is
> deduped — no duplicate events.
>
> **Note:** the contracts below were redeployed fresh (deployment ledger `3395077`), so the bundled
> `stella_events.sql` seed is stale (it's for the *previous* deployment) and step (b) can be skipped
> until you regenerate the seed — a brand-new indexer starting at the deployment ledger sees the
> complete history since nothing has fallen out of RPC's retention window yet.

```sh
# (a) Postgres — matches the indexer's default DATABASE_URL (localhost:5434, user/pw/db=indexer)
docker run -d --name pp-indexer-pg -p 5434:5432 \
  -e POSTGRES_USER=indexer -e POSTGRES_PASSWORD=indexer -e POSTGRES_DB=indexer \
  postgres:16
# next time, just: docker start pp-indexer-pg

# (b) seed the full event history into the DB (run once)
docker exec -i pp-indexer-pg psql -U indexer -d indexer < indexer/seed/stella_events.sql

# (c) build + run the indexer — it resumes from the seeded cursor and pulls only NEW events
cargo build --release -p indexer

INDEXER_CONTRACTS="CDVEICETZZERI7M3OSHQVT5YWXROK4EYC42KM52CUKCCXUXIUYBFJZQU,CDPU2F73UKCYBXK7LRE25JAM7G7MZQANKZRIAEORKRJZSSPDK4CAE5A6,CDH4LEBFJN5UHZ5GC5R2P5POXLWOJ4QTL7LS6RH4UKQ6JBVTC43I7ZRP" \
INDEXER_START_LEDGER=3395077 \
./target/release/indexer

# (d) sanity check (in another shell)
curl http://127.0.0.1:8080/health        # -> {"events":N,"status":"ok"} ; N >= 125
```

> **Keeping the dump current:** to refresh the seed after more activity, regenerate it from a
> caught-up indexer DB:
> ```sh
> docker exec pp-indexer-pg pg_dump -U indexer -d indexer --clean --if-exists > indexer/seed/stella_events.sql
> ```

Env vars (all have testnet defaults; see `indexer/src/main.rs`):
`INDEXER_CONTRACTS` (pool, ASP-membership, ASP-non-membership), `INDEXER_START_LEDGER`,
`INDEXER_RPC_URL` (default `https://soroban-testnet.stellar.org`),
`DATABASE_URL` (default `postgres://indexer:indexer@localhost:5434/indexer`),
`INDEXER_BIND` (default `0.0.0.0:8080`).

---

## 2. Build the app from source

The Rust prover compiles to an Android native lib (`libprover_ffi.so`); the Kotlin UniFFI bindings
are already committed (`android/app/src/main/java/uniffi/prover_ffi/prover_ffi.kt`).

```sh
# (a) build the prover → Android arm64 native lib, dropped into jniLibs
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<your-ndk-version>"   # e.g. 27.0.12077973
cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs build -p prover-ffi --release

# (b) build the APK
cd android
./gradlew assembleDebug          # debug, or: ./gradlew assembleRelease

# (c) install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK outputs: `android/app/build/outputs/apk/{debug,release}/app-*.apk`.

> **Regenerating UniFFI bindings** — only needed if you change the Rust FFI surface in
> `prover-ffi/src/lib.rs`:
> ```sh
> cargo run -p prover-ffi --bin uniffi-bindgen -- generate \
>   --library android/app/src/main/jniLibs/arm64-v8a/libprover_ffi.so \
>   --language kotlin --out-dir prover-ffi/bindings/kotlin
> cp prover-ffi/bindings/kotlin/uniffi/prover_ffi/prover_ffi.kt \
>   android/app/src/main/java/uniffi/prover_ffi/prover_ffi.kt
> ```

---

## 3. Point the phone at your local indexer

The app reads the indexer at `http://127.0.0.1:8080` (see `IndexerClient.kt`). For a device on USB,
forward that port:

```sh
adb reverse tcp:8080 tcp:8080
```

If you **host** the indexer instead (recommended for a shared build — one instance serves everyone),
change `IndexerClient.BASE` to your `https://…` URL and rebuild. The Soroban RPC is public, so with a
hosted indexer the app needs no local services at all.

---

## Using the app

1. **Create / import a wallet** (onboarding) — write down the 12-word phrase.
2. **Fund from testnet** — new accounts start empty; tap **Fund from testnet** on Home (friendbot).
3. **Register** — to *spend* (deposit/send/withdraw), an account does a one-time on-chain ASP
   enrollment; the app prompts via the **Register** screen. Receiving needs no enrollment.
4. **Deposit** (public) → **Send / Withdraw** (private). Each spend builds a ZK proof on-device
   (~7 s) — that's the "proof moment" screen.
5. **Switch faces any time** — tap or swipe the Public/Shielded slider on Home. Each mode keeps its
   own activity feed and balance card.
6. **Save contacts** on the People tab, then send straight to them from a chip — no address
   copy-pasting, on either rail.

## App structure

| Tab | What it shows |
|---|---|
| **Home** | The active mode's balance card, primary actions (Deposit/Send/Receive/Withdraw for Shielded; Send/Receive for Public), and a short activity preview. |
| **Activity** | The full history for the active mode — public payments grouped by day with a tap-through to stellar.expert; shielded notes in leaf order (deposits, receives, sends). |
| **People** | A local, on-device address book. Save a name against a public and/or shielded address; tap a contact to jump straight into Send with the recipient prefilled. |
| **Settings** | Account switcher, recovery phrase, and encrypted Google Drive backup. |

## Deployed testnet contracts

| Contract | ID |
|---|---|
| Pool (native XLM) | `CDVEICETZZERI7M3OSHQVT5YWXROK4EYC42KM52CUKCCXUXIUYBFJZQU` |
| Groth16 verifier | `CDT6PXNQQRIENHNI6FBQCD5AQN7FK6TEDSM7XWPLHDRRABCUOT4JB5GI` |
| ASP membership (permissionless) | `CDPU2F73UKCYBXK7LRE25JAM7G7MZQANKZRIAEORKRJZSSPDK4CAE5A6` |
| ASP non-membership | `CDH4LEBFJN5UHZ5GC5R2P5POXLWOJ4QTL7LS6RH4UKQ6JBVTC43I7ZRP` |

## Notes

- **Relayer is off** (`USE_RELAYER = false` in `MainActivity.kt`). All ops self-submit and pay their
  own gas — no relayer server needed. (Flip it on only if you host the `relayer/` service.)
- Testnet only. Native XLM. Contracts are unaudited — do not use with real assets.
