
//! `prover-ffi` — Milestone 0 vertical slice.
//!
//! One job: take a `policy_tx_4_2` circuit-input JSON, generate the witness
//! natively (rust-witness), prove with the reference `prover` crate
//! (ark-groth16, Wasmer-free), and return Soroban-ready proof bytes + public
//! inputs. No wallet, no flows, no state — those come in Phase 2.
//!
//! Pipeline:
//!   inputs_json --flatten--> HashMap<signal, Vec<BigInt>>
//!               --rust-witness--> Vec<BigInt> (wire-ordered witness)
//!               --LE bytes--> prover::Prover::prove_bytes_uncompressed
//!               --> 256-byte A||B||C + public inputs

use std::collections::HashMap;
use std::sync::OnceLock;

use anyhow::{anyhow, bail, Context, Result};
use num_bigint::{BigInt, Sign};
use prover::prover::Prover;

mod ext_data_hash;
#[cfg(feature = "rapidsnark")]
mod rapidsnark_backend;
mod submit;
mod wallet;

uniffi::setup_scaffolding!();

/// A freshly generated Stellar account (testnet).
#[derive(Clone, Debug, uniffi::Record)]
pub struct StellarAccount {
    /// 32-byte Ed25519 secret seed (store securely; never log/transmit).
    pub secret: Vec<u8>,
    /// Public `G...` address.
    pub address: String,
}

/// Generate a fresh Stellar Ed25519 account (the wallet's on-chain identity).
#[uniffi::export]
pub fn generate_stellar_account() -> StellarAccount {
    let (secret, address) = submit::generate_account();
    StellarAccount { secret: secret.to_vec(), address }
}

/// Generate a fresh BIP39 seed phrase (`words` = 12 or 24) — the wallet's
/// recovery backup. The phrase is the only secret the user must keep.
#[uniffi::export]
pub fn generate_mnemonic(words: u8) -> Result<String, ProverError> {
    wallet::generate_mnemonic(words).map_err(ProverError::from)
}

/// True if a phrase is a valid BIP39 mnemonic (wordlist + checksum) — for the
/// import screen to validate before deriving.
#[uniffi::export]
pub fn validate_mnemonic(phrase: String) -> bool {
    wallet::validate_mnemonic(&phrase)
}

/// Derive the Stellar account at SEP-5 path `m/44'/148'/<account>'` from a BIP39
/// mnemonic. Deterministic — same phrase always yields the same account.
#[uniffi::export]
pub fn mnemonic_to_account(phrase: String, account: u32) -> Result<StellarAccount, ProverError> {
    let (secret, address) = wallet::mnemonic_to_account(&phrase, account).map_err(ProverError::from)?;
    Ok(StellarAccount { secret: secret.to_vec(), address })
}

/// Derive the 32-byte AES-256 **backup encryption key** from the mnemonic
/// (Phase 7). Never stored; only someone with the recovery phrase can decrypt
/// the backup blob. Kotlin uses this with AES-256-GCM.
#[uniffi::export]
pub fn backup_key_from_mnemonic(phrase: String) -> Result<Vec<u8>, ProverError> {
    Ok(wallet::backup_key_from_mnemonic(&phrase).map_err(ProverError::from)?.to_vec())
}

/// Derive an account's **shielded keypair** (BN254 note key + X25519 encryption
/// key) from the seed at SEP-5 index `account` (Part B / real P2P). Each account
/// has its own shielded identity: it owns/decrypts its own notes, and its
/// `note_public`+`encryption_public` form its shareable shielded address.
#[uniffi::export]
pub fn account_shielded_keys(phrase: String, account: u32) -> Result<KeyBundle, ProverError> {
    let (secret, _addr) = wallet::mnemonic_to_account(&phrase, account).map_err(ProverError::from)?;
    let sig = wallet::shielded_key_signature(&secret);
    derive_keys(sig.to_vec())
}

/// Base64-XDR `LedgerKey::Account` for a `G...` address. Kotlin posts this in a
/// `getLedgerEntries` request to read the account's sequence number.
#[uniffi::export]
pub fn account_ledger_key(address: String) -> Result<String, ProverError> {
    submit::account_ledger_key(&address).map_err(ProverError::from)
}

/// Parse the account's native XLM balance (stroops) from the base64
/// `LedgerEntryData::Account` XDR returned by getLedgerEntries.
#[uniffi::export]
pub fn account_balance_stroops(account_entry_xdr: String) -> Result<i64, ProverError> {
    submit::balance_from_account_entry(&account_entry_xdr).map_err(ProverError::from)
}

/// Build the unsigned `transact` envelope (base64 XDR) for the RPC
/// `simulateTransaction` step. `account_entry_xdr` is the base64
/// `LedgerEntryData::Account` Kotlin got from `getLedgerEntries`.
#[allow(clippy::too_many_arguments)]
#[uniffi::export]
pub fn build_unsigned_transact(
    pool_id: String,
    source_address: String,
    account_entry_xdr: String,
    proof: Vec<u8>,
    public_inputs: Vec<u8>,
    ext_data_hash: Vec<u8>,
    ext_recipient: String,
    ext_amount: String,
    encrypted_output0: Vec<u8>,
    encrypted_output1: Vec<u8>,
) -> Result<String, ProverError> {
    let amount: i128 = ext_amount
        .parse()
        .map_err(|e| ProverError::Failed { msg: format!("bad ext_amount: {e}") })?;
    submit::build_unsigned_transact(
        &pool_id, &source_address, &account_entry_xdr, &proof, &public_inputs,
        &ext_data_hash, &ext_recipient, amount, &encrypted_output0, &encrypted_output1,
    )
    .map_err(ProverError::from)
}

/// Build an unsigned ASP-membership `insert_leaf` tx that enrolls this account's
/// note key — the self-serve "Register" step a wallet runs once before it can
/// spend (deposit/send/withdraw). The leaf is `asp_membership_leaf(note_pub, 0)`,
/// the same value `asp_membership_leaf_dec` reports and `build_asp_proofs`
/// rebuilds against. Simulate it, then sign with `finalize_and_sign`.
#[uniffi::export]
pub fn build_unsigned_asp_register(
    asp_contract_id: String,
    source_address: String,
    account_entry_xdr: String,
    note_public_key_hex: String,
) -> Result<String, ProverError> {
    let pkb = hex_decode(note_public_key_hex.trim_start_matches("0x")).map_err(ProverError::from)?;
    let pk32: [u8; 32] = pkb
        .try_into()
        .map_err(|_| ProverError::Failed { msg: "note pub != 32 bytes".into() })?;
    let leaf = prover::crypto::asp_membership_leaf(&types::NotePublicKey(pk32), &types::Field::ZERO)
        .map_err(ProverError::from)?;
    submit::build_unsigned_asp_register(
        &asp_contract_id, &source_address, &account_entry_xdr, &leaf.to_be_bytes(),
    )
    .map_err(ProverError::from)
}

/// Assemble the simulated tx (fee + soroban data + auth) and sign it with the
/// wallet key. Returns the signed envelope (base64 XDR) for `sendTransaction`.
/// `sim_response_json` is the raw `simulateTransaction` JSON-RPC response.
#[uniffi::export]
pub fn finalize_and_sign(
    unsigned_xdr: String,
    sim_response_json: String,
    source_secret: Vec<u8>,
) -> Result<String, ProverError> {
    if source_secret.len() != 32 {
        return Err(ProverError::Failed { msg: "secret must be 32 bytes".into() });
    }
    let mut secret = [0u8; 32];
    secret.copy_from_slice(&source_secret);
    submit::finalize_and_sign(&unsigned_xdr, &sim_response_json, &secret).map_err(ProverError::from)
}

/// Build + sign a **classic native-XLM payment** (Daylight / public mode) in one
/// call. Returns the signed base64 envelope — submit it via **Horizon**
/// `POST /transactions` (not Soroban RPC). `memo` empty = no memo.
/// `account_entry_xdr` is the base64 `LedgerEntryData::Account` from
/// getLedgerEntries; the tx uses `seq + 1`.
#[uniffi::export]
pub fn build_signed_payment(
    source_address: String,
    account_entry_xdr: String,
    dest_address: String,
    amount_stroops: i64,
    memo: String,
    source_secret: Vec<u8>,
) -> Result<String, ProverError> {
    if source_secret.len() != 32 {
        return Err(ProverError::Failed { msg: "secret must be 32 bytes".into() });
    }
    let mut secret = [0u8; 32];
    secret.copy_from_slice(&source_secret);
    submit::build_signed_payment(
        &source_address, &account_entry_xdr, &dest_address, amount_stroops, &memo, &secret,
    )
    .map_err(ProverError::from)
}

/// Error surfaced across the FFI boundary to Kotlin/Swift.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ProverError {
    #[error("{msg}")]
    Failed { msg: String },
}

impl From<anyhow::Error> for ProverError {
    fn from(e: anyhow::Error) -> Self {
        ProverError::Failed { msg: e.to_string() }
    }
}

// ---- UniFFI-exported surface (Phase 2) -------------------------------------
// Thin wrappers over the plain-Rust API below, with FFI-friendly signatures
// (owned String in, Record out, ProverError instead of anyhow).

/// Generate a `policy_tx_4_2` proof from a circuit-input JSON string.
/// Returns the Soroban-ready proof bytes + public inputs.
#[uniffi::export]
pub fn prove_policy_tx_4_2_json(inputs_json: String) -> Result<ProofBundle, ProverError> {
    Ok(prove_policy_tx_4_2(&inputs_json)?)
}

/// Same as [`prove_policy_tx_4_2_json`], but proves with rapidsnark's native
/// C++ prover instead of the cached arkworks `Prover` (Task B6). `zkey_path`
/// is a filesystem path to `policy_tx_4_2_final.zkey` (~35MB, not embedded —
/// Android callers copy the bundled asset to `filesDir` once, since assets
/// aren't directly file-pathable, and pass that path). Only compiled with
/// `--features rapidsnark`; the default build doesn't export this symbol.
#[cfg(feature = "rapidsnark")]
#[uniffi::export]
pub fn prove_policy_tx_4_2_json_rapidsnark(
    inputs_json: String,
    zkey_path: String,
) -> Result<ProofBundle, ProverError> {
    Ok(rapidsnark_backend::prove_policy_tx_4_2_rapidsnark(&inputs_json, &zkey_path)?)
}

/// Verify a proof bundle locally against the embedded verifying key.
#[uniffi::export]
pub fn verify_proof_bundle(bundle: ProofBundle) -> Result<bool, ProverError> {
    Ok(verify_locally(&bundle)?)
}

/// The four wallet keypairs derived from a Stellar Ed25519 signature over the
/// key-derivation message. All values are 32-byte little-endian.
#[derive(Clone, Debug, uniffi::Record)]
pub struct KeyBundle {
    /// BN254 note (spend authority) private key.
    pub note_private: Vec<u8>,
    /// BN254 note public key (identifies the owner of a note).
    pub note_public: Vec<u8>,
    /// X25519 encryption private key (decrypts incoming notes).
    pub encryption_private: Vec<u8>,
    /// X25519 encryption public key (others encrypt notes to this).
    pub encryption_public: Vec<u8>,
    /// BN254 nullifier key (`nk`, Zcash-style split) — derived from
    /// `note_private`; lets its holder compute nullifiers for owned notes
    /// without granting spend authority.
    pub nullifier_key: Vec<u8>,
}

/// Derive the note + encryption keypairs from a 64-byte Ed25519 signature.
///
/// The wallet signs the fixed key-derivation message with its Stellar account
/// key; that 64-byte signature is the only input. Deterministic — same
/// signature always yields the same keys.
#[uniffi::export]
pub fn derive_keys(signature: Vec<u8>) -> Result<KeyBundle, ProverError> {
    if signature.len() != 64 {
        return Err(ProverError::Failed {
            msg: format!("signature must be 64 bytes, got {}", signature.len()),
        });
    }
    let sig = types::KeyDerivationSignature(signature);
    let (note_kp, enc_kp, nullifier_key) = prover::encryption::derive_encryption_and_note_keypairs(sig)
        .map_err(ProverError::from)?;
    Ok(KeyBundle {
        note_private: note_kp.private.0.to_vec(),
        note_public: note_kp.public.0.to_vec(),
        encryption_private: enc_kp.private.0.to_vec(),
        encryption_public: enc_kp.public.0.to_vec(),
        nullifier_key,
    })
}

/// Output of a transaction flow (deposit/withdraw/transfer): the circuit-input
/// JSON (feeds straight into [`prove_policy_tx_4_2_json`]) plus the ext-data the
/// on-chain `transact` call needs.
#[derive(Clone, Debug, uniffi::Record)]
pub struct FlowArtifacts {
    /// Circuit inputs as JSON — pass directly to `prove_policy_tx_4_2_json`.
    pub circuit_inputs_json: String,
    /// ext_data.recipient (Stellar address / contract id).
    pub ext_recipient: String,
    /// ext_data.ext_amount as a decimal string (stroops; signed).
    pub ext_amount: String,
    /// Encrypted output note 0 (for recipient scanning).
    pub encrypted_output0: Vec<u8>,
    /// Encrypted output note 1.
    pub encrypted_output1: Vec<u8>,
    /// extDataHash bound into the proof (32-byte big-endian).
    pub ext_data_hash: Vec<u8>,
}

fn artifacts_to_ffi(a: prover::flows::TransactArtifacts) -> Result<FlowArtifacts> {
    let ext_data_hash = ext_data_hash::hash_ext_data_offchain(&a.ext_data)?.to_vec();
    let circuit_inputs_json = serde_json::to_string(&a.circuit_inputs)?;
    let ext_amount: i128 = a.ext_data.ext_amount.into();
    Ok(FlowArtifacts {
        circuit_inputs_json,
        ext_recipient: a.ext_data.recipient,
        ext_amount: ext_amount.to_string(),
        encrypted_output0: a.ext_data.encrypted_output0,
        encrypted_output1: a.ext_data.encrypted_output1,
        ext_data_hash,
    })
}

/// A note recovered by scanning an on-chain commitment event.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ScannedNote {
    /// Note amount (in the pool token's smallest unit), as a decimal string.
    pub amount: String,
}

/// Try to scan a `NewCommitmentEvent`: decode its base64-XDR `value`, pull out
/// `encrypted_output`, and decrypt it with the wallet's X25519 key. Returns the
/// recovered note amount if the note is addressed to this wallet, else `None`.
#[uniffi::export]
pub fn scan_commitment(
    event_value_b64: String,
    encryption_private_key: Vec<u8>,
) -> Result<Option<ScannedNote>, ProverError> {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use stellar_xdr::curr::{Limits, ReadXdr, ScVal};

    if encryption_private_key.len() != 32 {
        return Err(ProverError::Failed { msg: "enc key must be 32 bytes".into() });
    }
    let raw = STANDARD
        .decode(event_value_b64.trim())
        .map_err(|e| ProverError::Failed { msg: format!("base64: {e}") })?;
    let val = ScVal::from_xdr(raw.as_slice(), Limits::none())
        .map_err(|e| ProverError::Failed { msg: format!("xdr: {e}") })?;

    // Event data is a map of the non-topic fields; find `encrypted_output`.
    let encrypted_output: Option<Vec<u8>> = match val {
        ScVal::Map(Some(m)) => m.0.iter().find_map(|e| match (&e.key, &e.val) {
            (ScVal::Symbol(s), ScVal::Bytes(b)) if s.to_string() == "encrypted_output" => {
                Some(b.0.to_vec())
            }
            _ => None,
        }),
        _ => None,
    };
    let Some(enc_out) = encrypted_output else { return Ok(None) };

    let mut pk = [0u8; 32];
    pk.copy_from_slice(&encryption_private_key);
    let recovered = prover::encryption::decrypt_output_note(&types::EncryptionPrivateKey(pk), &enc_out)
        .map_err(ProverError::from)?;
    Ok(recovered.map(|(amount, _blinding)| {
        let a: u128 = amount.into();
        ScannedNote { amount: a.to_string() }
    }))
}

/// Decode a base64-XDR `ScVal::U256` topic into a BN254 `Field` (big-endian).
fn u256_topic_to_field(topic_b64: &str) -> Result<types::Field, ProverError> {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use stellar_xdr::curr::{Limits, ReadXdr, ScVal};
    let raw = STANDARD
        .decode(topic_b64.trim())
        .map_err(|e| ProverError::Failed { msg: format!("base64: {e}") })?;
    match ScVal::from_xdr(raw.as_slice(), Limits::none())
        .map_err(|e| ProverError::Failed { msg: format!("xdr: {e}") })?
    {
        ScVal::U256(p) => {
            let mut be = [0u8; 32];
            be[0..8].copy_from_slice(&p.hi_hi.to_be_bytes());
            be[8..16].copy_from_slice(&p.hi_lo.to_be_bytes());
            be[16..24].copy_from_slice(&p.lo_hi.to_be_bytes());
            be[24..32].copy_from_slice(&p.lo_lo.to_be_bytes());
            types::Field::try_from_be_bytes(be).map_err(ProverError::from)
        }
        other => Err(ProverError::Failed { msg: format!("topic not U256: {other:?}") }),
    }
}

/// A note recovered by scanning a `NewCommitmentEvent`, enriched with the
/// state-layer fields needed for durable storage + nullifier reconciliation.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ScannedNoteFull {
    /// Note amount (pool token's smallest unit), decimal string.
    pub amount: String,
    /// Leaf index in the pool merkle tree (from the event's `index` field).
    pub leaf_index: u32,
    /// This note's nullifier (decimal) — present once spent on-chain, lets the
    /// state layer mark the note spent (fixes the balance over-count).
    pub nullifier: String,
    /// The note's commitment (decimal) — its stable identity in the pool tree.
    pub commitment: String,
    /// The note's blinding factor (`0x`-hex, big-endian) recovered on decrypt.
    /// Needed to rebuild spend params for this note (in-app withdraw/transfer).
    pub blinding: String,
}

/// Decode a `NewCommitmentEvent`, decrypt it for this wallet, and if it's ours
/// return the full note (amount + leaf index + computed nullifier + commitment).
///
/// - `commitment_topic_b64`: the event's topic[1] (base64 XDR `ScVal::U256`).
/// - `value_b64`: the event's data map (base64 XDR) carrying `index` + `encrypted_output`.
/// - `encryption_private_key`: 32-byte X25519 key (decrypts the note).
/// - `note_private_key`: 32-byte BN254 spend key of the note's owner — needed to
///   recompute the nullifier. For the current demo, notes are owned by the
///   `scalar 102` key (see HANDOFF "Demo note key"); pass that.
#[uniffi::export]
pub fn scan_note(
    commitment_topic_b64: String,
    value_b64: String,
    encryption_private_key: Vec<u8>,
    note_private_key: Vec<u8>,
) -> Result<Option<ScannedNoteFull>, ProverError> {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use num_bigint::BigUint;
    use stellar_xdr::curr::{Limits, ReadXdr, ScVal};

    if encryption_private_key.len() != 32 {
        return Err(ProverError::Failed { msg: "enc key must be 32 bytes".into() });
    }
    if note_private_key.len() != 32 {
        return Err(ProverError::Failed { msg: "note key must be 32 bytes".into() });
    }

    let decode = |b: &str| -> Result<ScVal, ProverError> {
        let raw = STANDARD
            .decode(b.trim())
            .map_err(|e| ProverError::Failed { msg: format!("base64: {e}") })?;
        ScVal::from_xdr(raw.as_slice(), Limits::none())
            .map_err(|e| ProverError::Failed { msg: format!("xdr: {e}") })
    };

    // Commitment from topic[1] (ScVal::U256, big-endian numeric).
    let commitment = u256_topic_to_field(&commitment_topic_b64)?;
    let commitment_be = commitment.to_be_bytes();
    let commitment_dec = BigUint::from_bytes_be(&commitment_be).to_str_radix(10);

    // Value map: pull `index` (leaf position) + `encrypted_output`.
    let (leaf_index, enc_out): (u32, Vec<u8>) = match decode(&value_b64)? {
        ScVal::Map(Some(m)) => {
            let mut idx = None;
            let mut out = None;
            for e in m.0.iter() {
                if let ScVal::Symbol(s) = &e.key {
                    match s.to_string().as_str() {
                        "index" => if let ScVal::U32(v) = &e.val { idx = Some(*v) },
                        "encrypted_output" => if let ScVal::Bytes(b) = &e.val { out = Some(b.0.to_vec()) },
                        _ => {}
                    }
                }
            }
            match (idx, out) {
                (Some(i), Some(o)) => (i, o),
                _ => return Ok(None),
            }
        }
        _ => return Ok(None),
    };

    // Try to decrypt for this wallet.
    let mut enc_pk = [0u8; 32];
    enc_pk.copy_from_slice(&encryption_private_key);
    let recovered = prover::encryption::decrypt_output_note(&types::EncryptionPrivateKey(enc_pk), &enc_out)
        .map_err(ProverError::from)?;
    let Some((amount, blinding)) = recovered else { return Ok(None) };
    let amount_u128: u128 = amount.into();
    // blinding (Field) -> "0x"-hex BE, matching the params JSON encoding.
    let blinding_hex = match serde_json::to_value(&blinding) {
        Ok(serde_json::Value::String(s)) => s,
        _ => return Err(ProverError::Failed { msg: "blinding serialize".into() }),
    };

    // Recompute the nullifier exactly as the circuit does:
    //   path_indices = leaf_index (LE in a field element)
    //   nk           = poseidon(note_priv, 0)                        [domain 5]
    //   signature    = poseidon(nk, commitment, path_indices)        [domain 4]
    //   nullifier    = poseidon(commitment, path_indices, signature) [domain 2]
    let commitment_le = commitment.to_le_bytes();
    let mut path_indices_le = [0u8; 32];
    path_indices_le[..8].copy_from_slice(&u64::from(leaf_index).to_le_bytes());
    let nullifier_key = prover::crypto::derive_nullifier_key(&note_private_key)
        .map_err(ProverError::from)?;
    let signature = prover::crypto::compute_signature(&nullifier_key, &commitment_le, &path_indices_le)
        .map_err(ProverError::from)?;
    let nullifier_le = prover::crypto::compute_nullifier(&commitment_le, &path_indices_le, &signature)
        .map_err(ProverError::from)?;
    let nullifier_dec = BigUint::from_bytes_le(&nullifier_le).to_str_radix(10);

    Ok(Some(ScannedNoteFull {
        amount: amount_u128.to_string(),
        leaf_index,
        nullifier: nullifier_dec,
        commitment: commitment_dec,
        blinding: blinding_hex,
    }))
}

/// Recompute a note's nullifier directly from its nullifier key (`nk`),
/// mirroring `scan_note`'s nullifier recompute but without requiring the
/// note's spend private key. Mirrors the circuit exactly:
///   path_indices = leaf_index (LE in a field element)
///   signature    = poseidon(nk, commitment, path_indices)        [domain 4]
///   nullifier    = poseidon(commitment, path_indices, signature) [domain 2]
///
/// `nullifier_key`: 32-byte LE `nk` (see `KeyBundle::nullifier_key`).
/// `commitment_dec`: the note's commitment as a decimal string (same encoding
/// `scan_note` derives from the on-chain topic).
/// Returns the nullifier as a decimal string (same format `decode_nullifier_topic` emits).
#[uniffi::export]
pub fn compute_note_nullifier(
    nullifier_key: Vec<u8>,
    commitment_dec: String,
    leaf_index: u32,
) -> Result<String, ProverError> {
    use num_bigint::BigUint;

    if nullifier_key.len() != 32 {
        return Err(ProverError::Failed { msg: "nullifier key must be 32 bytes".into() });
    }

    let commitment_be_vec = commitment_dec
        .parse::<BigUint>()
        .map_err(|e| ProverError::Failed { msg: format!("commitment decimal: {e}") })?
        .to_bytes_be();
    if commitment_be_vec.len() > 32 {
        return Err(ProverError::Failed { msg: "commitment out of range".into() });
    }
    let mut commitment_be = [0u8; 32];
    commitment_be[32 - commitment_be_vec.len()..].copy_from_slice(&commitment_be_vec);
    let commitment = types::Field::try_from_be_bytes(commitment_be).map_err(ProverError::from)?;
    let commitment_le = commitment.to_le_bytes();

    let mut path_indices_le = [0u8; 32];
    path_indices_le[..8].copy_from_slice(&u64::from(leaf_index).to_le_bytes());

    let signature = prover::crypto::compute_signature(&nullifier_key, &commitment_le, &path_indices_le)
        .map_err(ProverError::from)?;
    let nullifier_le = prover::crypto::compute_nullifier(&commitment_le, &path_indices_le, &signature)
        .map_err(ProverError::from)?;
    Ok(BigUint::from_bytes_le(&nullifier_le).to_str_radix(10))
}

/// Decode a `NewNullifierEvent` topic[1] (`ScVal::U256`) to a decimal string,
/// so the state layer can match it against scanned notes' nullifiers.
#[uniffi::export]
pub fn decode_nullifier_topic(topic_b64: String) -> Result<String, ProverError> {
    use num_bigint::BigUint;
    let f = u256_topic_to_field(&topic_b64)?;
    Ok(BigUint::from_bytes_be(&f.to_be_bytes()).to_str_radix(10))
}

/// Rebuild a withdraw/transfer params JSON to spend a **specific unspent note**
/// against the **live** pool tree, in-app. The bundled fixtures pin a stale
/// pool root + path (and an already-spent note); this swaps in:
///   - `poolRoot` = root of the tree rebuilt from all current commitments,
///   - `inputs[0].merklePathElements` / `merklePathIndices` = the chosen leaf's
///     inclusion proof, and the note's `amount` + `blinding`.
/// The ASP membership/non-membership proofs are **constant** for the demo note,
/// so they're left untouched. Output feeds straight into `assemble_{withdraw,transfer}`.
///
/// `commitment_topics_b64` are the `NewCommitmentEvent` topic[1] values in leaf
/// order (index 0..n-1); `leaf_index` selects the note to spend.
#[uniffi::export]
pub fn rebuild_input_path(
    params_json: String,
    commitment_topics_b64: Vec<String>,
    leaf_index: u32,
    amount: String,
    blinding: String,
) -> Result<String, ProverError> {
    use prover::merkle::MerklePrefixTree;

    let mut v: serde_json::Value = serde_json::from_str(&params_json)
        .map_err(|e| ProverError::Failed { msg: format!("params JSON: {e}") })?;
    let depth = v.get("treeDepth").and_then(|d| d.as_u64()).unwrap_or(10) as u32;

    let leaves: Vec<types::Field> = commitment_topics_b64
        .iter()
        .map(|t| u256_topic_to_field(t))
        .collect::<Result<_, _>>()?;
    if (leaf_index as usize) >= leaves.len() {
        return Err(ProverError::Failed {
            msg: format!("leaf_index {leaf_index} out of range ({} leaves)", leaves.len()),
        });
    }

    let tree = MerklePrefixTree::new(depth, &leaves)
        .map_err(ProverError::from)?
        .into_built();
    let proof = tree.proof(leaf_index).map_err(ProverError::from)?;

    v["poolRoot"] = serde_json::to_value(proof.root).unwrap();
    let input = v
        .get_mut("inputs")
        .and_then(|i| i.get_mut(0))
        .ok_or_else(|| ProverError::Failed { msg: "params missing inputs[0]".into() })?;
    input["merklePathElements"] = serde_json::to_value(&proof.path_elements).unwrap();
    input["merklePathIndices"] = serde_json::to_value(proof.path_indices).unwrap();
    input["amount"] = serde_json::Value::String(amount);
    input["blinding"] = serde_json::Value::String(blinding);

    serde_json::to_string(&v).map_err(|e| ProverError::Failed { msg: format!("serialize: {e}") })
}

/// Pool merkle root (`Field`) from live commitment topics (leaf order).
fn pool_root_field(commitment_topics_b64: &[String], tree_depth: u32) -> Result<types::Field, ProverError> {
    use prover::merkle::MerklePrefixTree;
    let leaves: Vec<types::Field> =
        commitment_topics_b64.iter().map(|t| u256_topic_to_field(t)).collect::<Result<_, _>>()?;
    let tree = MerklePrefixTree::new(tree_depth, &leaves).map_err(ProverError::from)?;
    tree.root().map_err(ProverError::from)
}

/// Build a **deposit** params JSON for an ARBITRARY amount, in-app. Uses the
/// bundled deposit fixture as a template (its ASP proofs are constant for the
/// note owner key) and overrides `amount`, the output note (amount + a fresh
/// random blinding so each deposit is a distinct, independently-spendable note),
/// and `pool_root` (rebuilt from the live tree). Feeds `assemble_deposit`.
#[uniffi::export]
pub fn build_deposit_params(
    fixture_json: String,
    amount: String,
    commitment_topics_b64: Vec<String>,
    tree_depth: u32,
) -> Result<String, ProverError> {
    let mut v: serde_json::Value = serde_json::from_str(&fixture_json)
        .map_err(|e| ProverError::Failed { msg: format!("deposit fixture JSON: {e}") })?;
    let root = pool_root_field(&commitment_topics_b64, tree_depth)?;
    let blinding = prover::encryption::generate_random_blinding().map_err(ProverError::from)?;

    v["pool_root"] = serde_json::to_value(root).unwrap();
    v["amount"] = serde_json::Value::String(amount.clone());
    let out0 = v
        .get_mut("outputs")
        .and_then(|o| o.get_mut(0))
        .ok_or_else(|| ProverError::Failed { msg: "deposit fixture missing outputs[0]".into() })?;
    out0["amount"] = serde_json::Value::String(amount);
    out0["blinding"] = serde_json::to_value(blinding).unwrap();
    serde_json::to_string(&v).map_err(|e| ProverError::Failed { msg: format!("serialize: {e}") })
}

/// Rebuilt ASP proofs (JSON objects) for splicing into a params template.
#[derive(Clone, Debug, uniffi::Record)]
pub struct AspProofs {
    pub membership_json: String,
    pub non_membership_json: String,
}

/// The ASP membership leaf (decimal `U256`) for a note public key — what gate3
/// inserts on the ASP membership contract to enroll an account for spending.
#[uniffi::export]
pub fn asp_membership_leaf_dec(note_public_key_hex: String) -> Result<String, ProverError> {
    use num_bigint::BigUint;
    let pkb = hex_decode(note_public_key_hex.trim_start_matches("0x")).map_err(ProverError::from)?;
    let pk32: [u8; 32] = pkb.try_into().map_err(|_| ProverError::Failed { msg: "note pub != 32 bytes".into() })?;
    let leaf = prover::crypto::asp_membership_leaf(&types::NotePublicKey(pk32), &types::Field::ZERO)
        .map_err(ProverError::from)?;
    Ok(BigUint::from_bytes_be(&leaf.to_be_bytes()).to_str_radix(10))
}

/// Decode a `LeafAdded` event value (base64 XDR map) → its `leaf` (decimal),
/// so the wallet can rebuild the live ASP membership tree from the indexer.
#[uniffi::export]
pub fn decode_asp_leaf(value_b64: String) -> Result<String, ProverError> {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use num_bigint::BigUint;
    use stellar_xdr::curr::{Limits, ReadXdr, ScVal};
    let raw = STANDARD.decode(value_b64.trim())
        .map_err(|e| ProverError::Failed { msg: format!("base64: {e}") })?;
    let val = ScVal::from_xdr(raw.as_slice(), Limits::none())
        .map_err(|e| ProverError::Failed { msg: format!("xdr: {e}") })?;
    let leaf = match val {
        ScVal::Map(Some(m)) => m.0.iter().find_map(|e| match (&e.key, &e.val) {
            (ScVal::Symbol(s), ScVal::U256(p)) if s.to_string() == "leaf" => {
                let mut be = [0u8; 32];
                be[0..8].copy_from_slice(&p.hi_hi.to_be_bytes());
                be[8..16].copy_from_slice(&p.hi_lo.to_be_bytes());
                be[16..24].copy_from_slice(&p.lo_hi.to_be_bytes());
                be[24..32].copy_from_slice(&p.lo_lo.to_be_bytes());
                Some(BigUint::from_bytes_be(&be).to_str_radix(10))
            }
            _ => None,
        }),
        _ => None,
    };
    leaf.ok_or_else(|| ProverError::Failed { msg: "LeafAdded value has no `leaf` U256".into() })
}

/// Override the spend identity + ASP proofs in a params JSON (Part B / per-seed
/// keys). `camel_case` selects the field casing (deposit = false / snake_case;
/// withdraw + transfer = true / camelCase). `note_private_key_hex` and
/// `encryption_pubkey_hex` are `0x`-hex; the ASP JSONs come from `build_asp_proofs`.
#[allow(clippy::too_many_arguments)]
#[uniffi::export]
pub fn apply_identity(
    params_json: String,
    camel_case: bool,
    note_private_key_hex: String,
    encryption_pubkey_hex: String,
    membership_json: String,
    non_membership_json: String,
) -> Result<String, ProverError> {
    let mut v: serde_json::Value = serde_json::from_str(&params_json)
        .map_err(|e| ProverError::Failed { msg: format!("params JSON: {e}") })?;
    let parse = |s: &str| -> Result<serde_json::Value, ProverError> {
        serde_json::from_str(s).map_err(|e| ProverError::Failed { msg: format!("asp JSON: {e}") })
    };
    let (priv_k, enc_k, mem_k, nonmem_k) = if camel_case {
        ("privKey", "encryptionPubkey", "membershipProof", "nonMembershipProof")
    } else {
        ("priv_key", "encryption_pubkey", "membership_proof", "non_membership_proof")
    };
    v[priv_k] = serde_json::Value::String(note_private_key_hex);
    v[enc_k] = serde_json::Value::String(encryption_pubkey_hex);
    v[mem_k] = parse(&membership_json)?;
    v[nonmem_k] = parse(&non_membership_json)?;
    serde_json::to_string(&v).map_err(|e| ProverError::Failed { msg: format!("serialize: {e}") })
}

/// Rebuild the ASP **membership** + **non-membership** proofs for a note public
/// key against the **live** ASP membership tree (Part B / per-seed keys). The
/// membership tree is reconstructed from the `LeafAdded` leaves (decimal, leaf
/// order); `leaf_index` is this key's position. The non-membership tree is empty
/// in this deployment, so its proof is the constant empty proof with only `key`
/// set — lifted from `non_membership_fixture_json`. Splice the results into a
/// params template's `membership_proof`/`non_membership_proof`.
#[uniffi::export]
pub fn build_asp_proofs(
    note_public_key_hex: String,
    membership_leaves_dec: Vec<String>,
    membership_depth: u32,
    non_membership_fixture_json: String,
) -> Result<AspProofs, ProverError> {
    use num_bigint::BigUint;
    use prover::merkle::MerklePrefixTree;

    let dec_to_field = |d: &str| -> Result<types::Field, ProverError> {
        let bu = BigUint::parse_bytes(d.as_bytes(), 10)
            .ok_or_else(|| ProverError::Failed { msg: format!("bad decimal: {d}") })?;
        let mut be = [0u8; 32];
        let b = bu.to_bytes_be();
        if b.len() > 32 { return Err(ProverError::Failed { msg: "field > 32 bytes".into() }); }
        be[32 - b.len()..].copy_from_slice(&b);
        types::Field::try_from_be_bytes(be).map_err(ProverError::from)
    };

    let pkb = hex_decode(note_public_key_hex.trim_start_matches("0x")).map_err(ProverError::from)?;
    let pk32: [u8; 32] = pkb.try_into().map_err(|_| ProverError::Failed { msg: "note pub != 32 bytes".into() })?;
    let note_pub = types::NotePublicKey(pk32);
    let blinding0 = types::Field::ZERO;
    let leaf = prover::crypto::asp_membership_leaf(&note_pub, &blinding0).map_err(ProverError::from)?;

    let leaves: Vec<types::Field> =
        membership_leaves_dec.iter().map(|d| dec_to_field(d)).collect::<Result<_, _>>()?;
    // Find this key's leaf in the live membership set (= ASP-enrolled position).
    let leaf_index = leaves
        .iter()
        .position(|l| *l == leaf)
        .ok_or_else(|| ProverError::Failed {
            msg: "this account's note key is not ASP-enrolled yet (no membership leaf on-chain)".into(),
        })? as u32;
    let tree = MerklePrefixTree::new(membership_depth, &leaves).map_err(ProverError::from)?.into_built();
    let proof = tree.proof(leaf_index).map_err(ProverError::from)?;

    let membership = types::AspMembershipProof {
        leaf,
        blinding: blinding0,
        path_elements: proof.path_elements,
        path_indices: proof.path_indices,
        root: proof.root,
    };
    let membership_json = serde_json::to_string(&membership)
        .map_err(|e| ProverError::Failed { msg: format!("membership json: {e}") })?;

    // Non-membership: empty SMT → only `key` varies; rest is the constant proof.
    let mut nm: serde_json::Value = serde_json::from_str(&non_membership_fixture_json)
        .map_err(|e| ProverError::Failed { msg: format!("non-membership fixture: {e}") })?;
    let key = types::Field::from_le_bytes_mod_order(pk32);
    nm["key"] = serde_json::to_value(key).unwrap();
    let non_membership_json = serde_json::to_string(&nm)
        .map_err(|e| ProverError::Failed { msg: format!("non-membership json: {e}") })?;

    Ok(AspProofs { membership_json, non_membership_json })
}

/// The BN254 note **public** key (`0x`-hex) for a 32-byte note private key.
/// Used to address transfer outputs to self/recipients and to form a shielded
/// address.
#[uniffi::export]
pub fn note_public_key(note_private_key: Vec<u8>) -> Result<String, ProverError> {
    let pk = prover::crypto::derive_public_key(&note_private_key).map_err(ProverError::from)?;
    Ok(format!("0x{}", pk.iter().map(|b| format!("{b:02x}")).collect::<String>()))
}

/// An unspent note the wallet selected to spend (from the state layer).
#[derive(Clone, Debug, uniffi::Record)]
pub struct SelectedNote {
    pub leaf_index: u32,
    pub amount: String,
    pub blinding: String,
}

/// Build a **withdraw** params JSON for an ARBITRARY amount + recipient, in-app.
/// Spends the selected notes (1–4, the circuit's limit) against the live tree,
/// withdraws `amount` to `recipient_g` (any public `G...`), and lets the flow
/// auto-compute the change note. ASP proofs come from the fixture (constant for
/// the owner key). Feeds `assemble_withdraw`.
#[uniffi::export]
pub fn build_withdraw_params(
    fixture_json: String,
    amount: String,
    recipient_g: String,
    inputs: Vec<SelectedNote>,
    commitment_topics_b64: Vec<String>,
    tree_depth: u32,
) -> Result<String, ProverError> {
    use prover::merkle::MerklePrefixTree;
    if inputs.is_empty() || inputs.len() > 4 {
        return Err(ProverError::Failed { msg: format!("withdraw needs 1-4 input notes, got {}", inputs.len()) });
    }
    let mut v: serde_json::Value = serde_json::from_str(&fixture_json)
        .map_err(|e| ProverError::Failed { msg: format!("withdraw fixture JSON: {e}") })?;
    let leaves: Vec<types::Field> =
        commitment_topics_b64.iter().map(|t| u256_topic_to_field(t)).collect::<Result<_, _>>()?;
    let tree = MerklePrefixTree::new(tree_depth, &leaves).map_err(ProverError::from)?.into_built();

    let mut input_arr: Vec<serde_json::Value> = Vec::new();
    for n in &inputs {
        if (n.leaf_index as usize) >= leaves.len() {
            return Err(ProverError::Failed { msg: format!("leaf_index {} out of range", n.leaf_index) });
        }
        let proof = tree.proof(n.leaf_index).map_err(ProverError::from)?;
        input_arr.push(serde_json::json!({
            "amount": n.amount,
            "blinding": n.blinding,
            "merklePathElements": serde_json::to_value(&proof.path_elements).unwrap(),
            "merklePathIndices": serde_json::to_value(proof.path_indices).unwrap(),
        }));
    }

    v["poolRoot"] = serde_json::to_value(pool_root_field(&commitment_topics_b64, tree_depth)?).unwrap();
    v["inputs"] = serde_json::Value::Array(input_arr);
    v["withdrawAmount"] = serde_json::Value::String(amount);
    v["withdrawRecipient"] = serde_json::Value::String(recipient_g);
    v["outputs"] = serde_json::Value::Null; // flow auto-computes the change note
    serde_json::to_string(&v).map_err(|e| ProverError::Failed { msg: format!("serialize: {e}") })
}

/// Build a private **transfer** params JSON: spend the selected notes (1–4) and
/// create a note of `amount` for the recipient (`recipient_note_pub` +
/// `recipient_enc_pub`, `0x`-hex), change back to the sender. For a self-transfer
/// pass the sender's own pubkeys. Feeds `assemble_transfer`.
#[allow(clippy::too_many_arguments)]
#[uniffi::export]
pub fn build_transfer_params(
    fixture_json: String,
    amount: String,
    recipient_note_pub: String,
    recipient_enc_pub: String,
    inputs: Vec<SelectedNote>,
    commitment_topics_b64: Vec<String>,
    tree_depth: u32,
) -> Result<String, ProverError> {
    use prover::merkle::MerklePrefixTree;
    if inputs.is_empty() || inputs.len() > 4 {
        return Err(ProverError::Failed { msg: format!("transfer needs 1-4 input notes, got {}", inputs.len()) });
    }
    let send: u128 = amount.parse().map_err(|e| ProverError::Failed { msg: format!("bad amount: {e}") })?;
    let total: u128 = inputs.iter().map(|n| n.amount.parse::<u128>().unwrap_or(0)).sum();
    if send > total {
        return Err(ProverError::Failed { msg: format!("amount {send} exceeds selected notes {total}") });
    }
    let change = total - send;

    let mut v: serde_json::Value = serde_json::from_str(&fixture_json)
        .map_err(|e| ProverError::Failed { msg: format!("transfer fixture JSON: {e}") })?;
    let leaves: Vec<types::Field> =
        commitment_topics_b64.iter().map(|t| u256_topic_to_field(t)).collect::<Result<_, _>>()?;
    let tree = MerklePrefixTree::new(tree_depth, &leaves).map_err(ProverError::from)?.into_built();

    let mut input_arr: Vec<serde_json::Value> = Vec::new();
    for n in &inputs {
        if (n.leaf_index as usize) >= leaves.len() {
            return Err(ProverError::Failed { msg: format!("leaf_index {} out of range", n.leaf_index) });
        }
        let proof = tree.proof(n.leaf_index).map_err(ProverError::from)?;
        input_arr.push(serde_json::json!({
            "amount": n.amount,
            "blinding": n.blinding,
            "merklePathElements": serde_json::to_value(&proof.path_elements).unwrap(),
            "merklePathIndices": serde_json::to_value(proof.path_indices).unwrap(),
        }));
    }

    let b0 = prover::encryption::generate_random_blinding().map_err(ProverError::from)?;
    let b1 = prover::encryption::generate_random_blinding().map_err(ProverError::from)?;
    // output0 → recipient; output1 → change back to self (recipient keys null).
    let outputs = serde_json::json!([
        {
            "amount": send.to_string(),
            "blinding": serde_json::to_value(b0).unwrap(),
            "recipient_note_pubkey": recipient_note_pub,
            "recipient_encryption_pubkey": recipient_enc_pub,
        },
        {
            "amount": change.to_string(),
            "blinding": serde_json::to_value(b1).unwrap(),
            "recipient_note_pubkey": serde_json::Value::Null,
            "recipient_encryption_pubkey": serde_json::Value::Null,
        }
    ]);

    v["poolRoot"] = serde_json::to_value(pool_root_field(&commitment_topics_b64, tree_depth)?).unwrap();
    v["inputs"] = serde_json::Value::Array(input_arr);
    v["outputs"] = outputs;
    serde_json::to_string(&v).map_err(|e| ProverError::Failed { msg: format!("serialize: {e}") })
}

/// What the holder discloses (Phase 6), surfaced to the UI alongside the
/// canonical receipt JSON. The on-the-wire artifact is the receipt JSON itself
/// (`types::DisclosureReceipt`); these are convenience fields for display.
#[derive(Clone, Debug, uniffi::Record)]
pub struct IssuedDisclosure {
    /// Canonical `DisclosureReceipt` JSON (versioned; share this with the verifier).
    pub receipt_json: String,
    /// The disclosed note's commitment (decimal) — the public identity proven.
    pub note_commitment: String,
    /// Pool root the note is proven under (decimal).
    pub root: String,
    /// `ext_context_hash` bound into the proof (decimal) — ties it to the authority.
    pub ext_context_hash: String,
    /// The note amount (decimal) — known to the holder; informational (the
    /// circuit binds the *commitment*, not the amount).
    pub amount: String,
}

/// Four-part disclosure verification result (the verifier side). A "proof of
/// funds" receipt is trustworthy **only when all are true** — Groth16 proof
/// valid, context re-derives to the bound hash (anti-replay), the proven root is
/// a known pool root, and the disclosed opening recomputes to the proven
/// commitment (so the stated amount is real, not just asserted).
#[derive(Clone, Debug, uniffi::Record)]
pub struct DisclosureReport {
    pub proof_verified: bool,
    pub context_verified: bool,
    pub known_root_status: bool,
    /// The opening (amount+blinding+pubkey) hashes to the proven commitment.
    pub amount_verified: bool,
    pub fully_verified: bool,
}

/// The note opening the holder reveals so a verifier can confirm the amount:
/// `commitment == hash(amount, note_public_key, blinding)`. Revealing this
/// de-anonymizes exactly this one note (the intent of a funds disclosure).
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct NoteOpening {
    amount: String,
    blinding: String,
    note_public_key: String,
}

/// The shared "proof of funds" artifact: the canonical disclosure receipt plus
/// the note opening that makes the amount verifiable.
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct DisclosureBundle {
    receipt: types::DisclosureReceipt,
    opening: NoteOpening,
}

// VK bytes + hash for the embedded selectiveDisclosure_1 proving key.
fn sd_vk() -> Result<(Vec<u8>, String)> {
    let prover = sd_prover().map_err(|e| anyhow!("{e}"))?;
    let vk = prover.get_verifying_key().map_err(|e| anyhow!("vk: {e}"))?;
    let hash = disclosure::vk_hash_hex(&vk);
    Ok((vk, hash))
}

// Witness bytes (LE field elements) for a circuit-input JSON + witness fn.
fn witness_bytes_for(
    inputs_json: &str,
    witness_fn: fn(HashMap<String, Vec<BigInt>>) -> Vec<BigInt>,
) -> Result<Vec<u8>> {
    let value: serde_json::Value = serde_json::from_str(inputs_json)?;
    let obj = value.as_object().ok_or_else(|| anyhow!("inputs not an object"))?;
    let mut inputs: HashMap<String, Vec<BigInt>> = HashMap::new();
    for (k, v) in obj {
        flatten_input(k, v, &mut inputs)?;
    }
    Ok(bigints_to_le_bytes(&witness_fn(inputs)))
}

/// Issue a **canonical selective-disclosure receipt** for a note the wallet
/// owns (Phase 6), bound to an authority/purpose/nonce context — the way the
/// privacy pool intends disclosure to be used. The verifier supplies the
/// `context_nonce` (anti-replay challenge); the holder proves ownership of the
/// note's commitment under the live pool root, bound to that exact context.
///
/// Returns the canonical `DisclosureReceipt` JSON + display fields.
#[allow(clippy::too_many_arguments)]
#[uniffi::export]
pub fn issue_disclosure_receipt(
    commitment_topics_b64: Vec<String>,
    leaf_index: u32,
    amount: String,
    blinding: String,
    note_private_key: Vec<u8>,
    tree_depth: u32,
    network: String,
    pool_address: String,
    authority_label: String,
    purpose: String,
    context_nonce: String,
    issued_at: String,
) -> Result<IssuedDisclosure, ProverError> {
    use num_bigint::BigUint;
    use prover::flows::{selective_disclosure_1, SelectiveDisclosure1Params};
    use prover::merkle::MerklePrefixTree;
    use std::str::FromStr;
    use types::{
        DisclosureContext, DisclosurePublicInputs, DisclosureReceipt, DISCLOSURE_RECEIPT_VERSION,
    };

    if note_private_key.len() != 32 {
        return Err(ProverError::Failed { msg: "note key must be 32 bytes".into() });
    }
    let mut npk = [0u8; 32];
    npk.copy_from_slice(&note_private_key);

    // 1. Inclusion proof of the note's leaf against the live tree.
    let leaves: Vec<types::Field> = commitment_topics_b64
        .iter()
        .map(|t| u256_topic_to_field(t))
        .collect::<Result<_, _>>()?;
    if (leaf_index as usize) >= leaves.len() {
        return Err(ProverError::Failed {
            msg: format!("leaf_index {leaf_index} out of range ({} leaves)", leaves.len()),
        });
    }
    let note_commitment = leaves[leaf_index as usize];
    let tree = MerklePrefixTree::new(tree_depth, &leaves).map_err(ProverError::from)?.into_built();
    let mproof = tree.proof(leaf_index).map_err(ProverError::from)?;

    // 2. Build the canonical disclosure context + derive ext_context_hash.
    let nonce_field = types::Field::from_str(&parse_nonce(&context_nonce)?)
        .map_err(|e| ProverError::Failed { msg: format!("bad nonce: {e}") })?;
    let context = DisclosureContext {
        network,
        pool_address,
        authority_label: authority_label.clone(),
        // Identity payload = the authority label bytes (placeholder identity);
        // a real authority supplies a key/cert here.
        authority_identity_payload_hex: format!("0x{}", hex_lower(authority_label.as_bytes())),
        purpose,
        context_nonce: nonce_field,
    };
    let ext_context_hash =
        disclosure::derive_ext_context_hash(&context).map_err(ProverError::from)?;

    // 3. Assemble + prove the selectiveDisclosure_1 circuit.
    let amount_u128: u128 =
        amount.parse().map_err(|e| ProverError::Failed { msg: format!("bad amount: {e}") })?;
    let blinding_field = types::Field::from_str(&blinding)
        .map_err(|e| ProverError::Failed { msg: format!("bad blinding: {e}") })?;
    let params = SelectiveDisclosure1Params {
        root: mproof.root,
        note_commitment,
        note_amount: types::NoteAmount::from(amount_u128),
        note_private_key: types::NotePrivateKey(npk),
        note_blinding: blinding_field,
        merkle_path_indices: mproof.path_indices,
        merkle_path_elements: mproof.path_elements,
        ext_context_hash,
    };
    let artifacts = selective_disclosure_1(params).map_err(ProverError::from)?;
    let inputs_json = serde_json::to_string(&artifacts.circuit_inputs)
        .map_err(|e| ProverError::Failed { msg: format!("inputs json: {e}") })?;
    let witness = witness_bytes_for(&inputs_json, selectiveDisclosure1_witness)
        .map_err(ProverError::from)?;
    let sd_prover = sd_prover()?;
    let proved = disclosure::prove_receipt_proof_with_prover(sd_prover, &witness)
        .map_err(ProverError::from)?;

    // 4. Assemble the canonical receipt.
    let (_vk, vk_hash) = sd_vk().map_err(ProverError::from)?;
    let receipt = DisclosureReceipt {
        version: DISCLOSURE_RECEIPT_VERSION,
        circuit: disclosure::SELECTIVE_DISCLOSURE_1.receipt_metadata(&vk_hash),
        context,
        public_inputs: DisclosurePublicInputs {
            roots: vec![mproof.root],
            note_commitments: vec![note_commitment],
            ext_context_hash,
        },
        proof_compressed_hex: format!("0x{}", hex_lower(&proved.proof_compressed)),
        issued_at,
    };
    receipt.validate().map_err(ProverError::from)?;

    // The opening that makes the amount verifiable: the note's public key
    // (derived from the spend key) so the verifier can recompute the commitment.
    let note_pubkey = prover::crypto::derive_public_key(&npk).map_err(ProverError::from)?;
    let opening = NoteOpening {
        amount: amount_u128.to_string(),
        blinding,
        note_public_key: format!("0x{}", hex_lower(&note_pubkey)),
    };
    let bundle = DisclosureBundle { receipt, opening };
    let receipt_json = serde_json::to_string_pretty(&bundle)
        .map_err(|e| ProverError::Failed { msg: format!("receipt json: {e}") })?;

    let dec = |f: types::Field| BigUint::from_bytes_be(&f.to_be_bytes()).to_str_radix(10);
    Ok(IssuedDisclosure {
        receipt_json,
        note_commitment: dec(note_commitment),
        root: dec(mproof.root),
        ext_context_hash: dec(ext_context_hash),
        amount: amount_u128.to_string(),
    })
}

/// Verify a canonical disclosure receipt (the verifier side). Runs the full
/// three-part check: Groth16 proof, context re-derivation (anti-replay), and
/// known-root freshness against `known_roots` (decimal pool roots the verifier
/// independently trusts — e.g. reconstructed from on-chain commitments).
#[uniffi::export]
pub fn verify_disclosure_receipt(
    receipt_json: String,
    known_roots: Vec<String>,
) -> Result<DisclosureReport, ProverError> {
    use num_bigint::BigUint;
    use std::str::FromStr;

    let bundle: DisclosureBundle = serde_json::from_str(&receipt_json)
        .map_err(|e| ProverError::Failed { msg: format!("receipt JSON: {e}") })?;
    let receipt = &bundle.receipt;
    let (vk, vk_hash) = sd_vk().map_err(ProverError::from)?;

    let known: std::collections::HashSet<String> = known_roots.into_iter().collect();
    let context_verified =
        disclosure::verify_receipt_context(receipt).map_err(ProverError::from)?;
    let report = disclosure::verify_receipt_report_with(
        receipt,
        &vk_hash,
        |r, h| disclosure::verify_receipt_proof(r, &vk, h),
        context_verified,
        |root: types::Field| {
            let d = BigUint::from_bytes_be(&root.to_be_bytes()).to_str_radix(10);
            Ok(known.contains(&d))
        },
    )
    .map_err(ProverError::from)?;

    // Amount check: recompute commitment == hash(amount, pubkey, blinding) and
    // confirm it equals the proven note commitment.
    let amount_verified = (|| -> Result<bool> {
        let amount_u128: u128 = bundle.opening.amount.parse()?;
        let amount_le = types::Field::from(types::NoteAmount::from(amount_u128)).to_le_bytes();
        let blinding_le = types::Field::from_str(&bundle.opening.blinding)
            .map_err(|e| anyhow!("opening blinding: {e}"))?
            .to_le_bytes();
        let pubkey = bundle
            .opening
            .note_public_key
            .strip_prefix("0x")
            .ok_or_else(|| anyhow!("notePublicKey missing 0x"))?;
        let pubkey_bytes = hex_decode(pubkey)?;
        let computed = prover::crypto::compute_commitment(&amount_le, &pubkey_bytes, &blinding_le)?;
        let expected = receipt
            .public_inputs
            .note_commitments
            .first()
            .ok_or_else(|| anyhow!("receipt has no note commitment"))?
            .to_le_bytes();
        Ok(computed == expected)
    })()
    .map_err(ProverError::from)?;

    Ok(DisclosureReport {
        proof_verified: report.proof_verified,
        context_verified: report.context_verified,
        known_root_status: report.known_root_status,
        amount_verified,
        fully_verified: report.is_fully_verified() && amount_verified,
    })
}

/// Decode a lowercase hex string to bytes.
fn hex_decode(s: &str) -> Result<Vec<u8>> {
    if s.len() % 2 != 0 {
        bail!("odd hex length");
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|e| anyhow!("hex: {e}")))
        .collect()
}

/// Current pool merkle root (decimal) from the live commitment list — the
/// verifier reconstructs this from public on-chain commitments to seed the
/// known-root check.
#[uniffi::export]
pub fn current_pool_root(
    commitment_topics_b64: Vec<String>,
    tree_depth: u32,
) -> Result<String, ProverError> {
    use num_bigint::BigUint;
    use prover::merkle::MerklePrefixTree;
    let leaves: Vec<types::Field> =
        commitment_topics_b64.iter().map(|t| u256_topic_to_field(t)).collect::<Result<_, _>>()?;
    let tree = MerklePrefixTree::new(tree_depth, &leaves).map_err(ProverError::from)?;
    let root = tree.root().map_err(ProverError::from)?;
    Ok(BigUint::from_bytes_be(&root.to_be_bytes()).to_str_radix(10))
}

fn hex_lower(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Accept a nonce as decimal or 0x-hex and normalize to the `0x`-hex BE form
/// `Field::from_str` expects.
fn parse_nonce(nonce: &str) -> Result<String, ProverError> {
    use num_bigint::BigUint;
    let n = nonce.trim();
    let bu = if let Some(h) = n.strip_prefix("0x") {
        BigUint::parse_bytes(h.as_bytes(), 16)
    } else {
        BigUint::parse_bytes(n.as_bytes(), 10)
    }
    .ok_or_else(|| ProverError::Failed { msg: format!("invalid nonce: {nonce}") })?;
    let mut be = [0u8; 32];
    let bytes = bu.to_bytes_be();
    if bytes.len() > 32 {
        return Err(ProverError::Failed { msg: "nonce too large".into() });
    }
    be[32 - bytes.len()..].copy_from_slice(&bytes);
    Ok(format!("0x{}", hex_lower(&be)))
}

/// Assemble circuit inputs for a **deposit** from a JSON `DepositParams`.
#[uniffi::export]
pub fn assemble_deposit(params_json: String) -> Result<FlowArtifacts, ProverError> {
    let params = serde_json::from_str(&params_json)
        .map_err(|e| ProverError::Failed { msg: format!("invalid DepositParams JSON: {e}") })?;
    let artifacts = prover::flows::deposit(params, ext_data_hash::hash_ext_data_offchain)
        .map_err(ProverError::from)?;
    Ok(artifacts_to_ffi(artifacts)?)
}

/// Assemble circuit inputs for a **withdraw** from a JSON `WithdrawParams`.
#[uniffi::export]
pub fn assemble_withdraw(params_json: String) -> Result<FlowArtifacts, ProverError> {
    let params = serde_json::from_str(&params_json)
        .map_err(|e| ProverError::Failed { msg: format!("invalid WithdrawParams JSON: {e}") })?;
    let artifacts = prover::flows::withdraw(params, ext_data_hash::hash_ext_data_offchain)
        .map_err(ProverError::from)?;
    Ok(artifacts_to_ffi(artifacts)?)
}

/// Assemble circuit inputs for a **transfer** from a JSON `TransferParams`.
#[uniffi::export]
pub fn assemble_transfer(params_json: String) -> Result<FlowArtifacts, ProverError> {
    let params = serde_json::from_str(&params_json)
        .map_err(|e| ProverError::Failed { msg: format!("invalid TransferParams JSON: {e}") })?;
    let artifacts = prover::flows::transfer(params, ext_data_hash::hash_ext_data_offchain)
        .map_err(ProverError::from)?;
    Ok(artifacts_to_ffi(artifacts)?)
}

// Circuit artifacts, embedded in the library. The proving key (~16 MB) is
// ark-serialized; the .r1cs drives the constraint replay in `Prover`.
const PROVING_KEY: &[u8] = include_bytes!("../circuits/policy_tx_4_2_proving_key.bin");
const R1CS: &[u8] = include_bytes!("../circuits/policy_tx_4_2.r1cs");

// `selectiveDisclosure_1` circuit (Phase 6) — proves you own a note in the pool
// bound to a disclosure context, revealing only {root, commitment, context}.
const SD_PROVING_KEY: &[u8] = include_bytes!("../circuits/selectiveDisclosure_1_proving_key.bin");
const SD_R1CS: &[u8] = include_bytes!("../circuits/selectiveDisclosure_1.r1cs");

// Cached provers: deserializing the embedded proving key + R1CS is ~48% of
// total proof time, so we pay it once per process (lazily, on first use) and
// reuse the `Prover` for every subsequent proof/verify call.
static POLICY_PROVER: OnceLock<Result<Prover, String>> = OnceLock::new();
static SD_PROVER: OnceLock<Result<Prover, String>> = OnceLock::new();

fn policy_prover() -> Result<&'static Prover, ProverError> {
    POLICY_PROVER
        .get_or_init(|| Prover::new(PROVING_KEY, R1CS).map_err(|e| format!("policy prover init: {e}")))
        .as_ref()
        .map_err(|e| ProverError::Failed { msg: e.clone() })
}

fn sd_prover() -> Result<&'static Prover, ProverError> {
    SD_PROVER
        .get_or_init(|| Prover::new(SD_PROVING_KEY, SD_R1CS).map_err(|e| format!("sd prover init: {e}")))
        .as_ref()
        .map_err(|e| ProverError::Failed { msg: e.clone() })
}

/// Pre-deserializes both embedded provers (policy + selective disclosure) so
/// the first real proof skips the ~half of proof time spent loading keys.
/// Idempotent and thread-safe.
#[uniffi::export]
pub fn warm_up_provers() -> Result<(), ProverError> {
    policy_prover()?;
    sd_prover()?;
    Ok(())
}

/// BN254 scalar field modulus (decimal), for negative-input normalization.
const BN254_FIELD_MODULUS: &str =
    "21888242871839275222246405745257275088548364400416034343698204186575808495617";

/// Bytes per field element in the witness/public-input encoding.
const FIELD_SIZE: usize = 32;

// rust-witness generates `<stem>_witness(HashMap<String, Vec<BigInt>>) ->
// Vec<BigInt>` from each `circuits/*.wasm` (see build.rs). The macro argument
// must match w2c2's underscore-stripped module name (= the wasm file stem).
rust_witness::witness!(policytx42);
rust_witness::witness!(selectiveDisclosure1);

/// Soroban-ready proof + public signals produced by [`prove_policy_tx_4_2`].
#[derive(Clone, Debug, uniffi::Record)]
pub struct ProofBundle {
    /// Uncompressed Groth16 proof, Soroban encoding: A(64) || B(128) || C(64)
    /// = 256 bytes. This is what the on-chain verifier consumes.
    pub proof: Vec<u8>,
    /// Compressed Groth16 proof [A || B || C] — used for local `verify`.
    pub proof_compressed: Vec<u8>,
    /// Public inputs as field-element bytes (witness encoding), concatenated.
    pub public_inputs: Vec<u8>,
}

/// Generate a `policy_tx_4_2` proof from a circuit-input JSON object.
///
/// `inputs_json` is a JSON object mapping circom signal names to values
/// (numbers, decimal/hex strings, or nested arrays/objects matching the
/// circuit's bus layout). Flat keys like `"membershipProofs[0][0].leaf"` are
/// accepted as-is — they match the circom signal paths the witness generator
/// hashes.
pub fn prove_policy_tx_4_2(inputs_json: &str) -> Result<ProofBundle> {
    let prover = policy_prover().map_err(|e| anyhow!("{e}"))?;
    prove_circuit(inputs_json, policytx42_witness, prover)
}

/// Generic Groth16 prover: flatten the circuit-input JSON, generate the witness
/// with `witness_fn`, and prove with the given (cached) prover. Shared by the
/// transaction and selective-disclosure circuits.
fn prove_circuit(
    inputs_json: &str,
    witness_fn: fn(HashMap<String, Vec<BigInt>>) -> Vec<BigInt>,
    prover: &Prover,
) -> Result<ProofBundle> {
    let value: serde_json::Value =
        serde_json::from_str(inputs_json).context("inputs_json is not valid JSON")?;
    let obj = value
        .as_object()
        .ok_or_else(|| anyhow!("inputs_json must be a JSON object"))?;

    // Flatten nested signals into the HashMap<signal, Vec<BigInt>> the witness
    // generator consumes (same semantics as the reference `witness` crate).
    let mut inputs: HashMap<String, Vec<BigInt>> = HashMap::new();
    for (key, val) in obj {
        flatten_input(key, val, &mut inputs)?;
    }

    // Native witness generation (replaces Wasmer).
    let witness: Vec<BigInt> = witness_fn(inputs);

    // Encode witness as little-endian 32-byte field elements, in wire order.
    let witness_bytes = bigints_to_le_bytes(&witness);

    // Prove once (compressed), then derive the uncompressed Soroban form so
    // both come from the same proof.
    let proof_compressed = prover
        .prove_bytes(&witness_bytes)
        .map_err(|e| anyhow!("prove failed: {e}"))?;
    let proof = prover
        .proof_bytes_to_uncompressed(&proof_compressed)
        .map_err(|e| anyhow!("uncompress failed: {e}"))?;
    let public_inputs = prover
        .extract_public_inputs(&witness_bytes)
        .map_err(|e| anyhow!("extract_public_inputs failed: {e}"))?;

    Ok(ProofBundle {
        proof,
        proof_compressed,
        public_inputs,
    })
}

/// Local sanity verify (desktop). Mirrors the on-chain pairing check using the
/// proving key's embedded VK, so we can confirm wire ordering before submitting
/// to Soroban.
pub fn verify_locally(bundle: &ProofBundle) -> Result<bool> {
    let prover = policy_prover().map_err(|e| anyhow!("{e}"))?;
    verify_with(bundle, prover)
}

fn verify_with(bundle: &ProofBundle, prover: &Prover) -> Result<bool> {
    // `verify` deserializes a compressed proof.
    prover
        .verify(&bundle.proof_compressed, &bundle.public_inputs)
        .map_err(|e| anyhow!("verify failed: {e}"))
}

/// Convert field-element BigInts to little-endian 32-byte chunks (wire order
/// preserved). Matches the `prover` crate's expected witness encoding.
fn bigints_to_le_bytes(values: &[BigInt]) -> Vec<u8> {
    let mut out = Vec::with_capacity(values.len() * FIELD_SIZE);
    for v in values {
        let (_sign, mut bytes) = v.to_bytes_le();
        bytes.resize(FIELD_SIZE, 0);
        out.extend_from_slice(&bytes[..FIELD_SIZE]);
    }
    out
}

/// True if `value` is an array containing only scalars (no objects), possibly
/// nested. Such arrays flatten to a single base key.
fn is_pure_array(value: &serde_json::Value) -> bool {
    use serde_json::Value;
    let mut stack: Vec<&Value> = vec![value];
    while let Some(current) = stack.pop() {
        match current {
            Value::Number(_) | Value::String(_) | Value::Bool(_) | Value::Null => {}
            Value::Array(arr) => stack.extend(arr.iter()),
            Value::Object(_) => return false,
        }
    }
    true
}

/// Flatten a scalar-only (possibly nested) array into `inputs[key]` in
/// row-major order. Ported from the reference `witness` crate.
fn flatten_pure_array(
    key: &str,
    value: &serde_json::Value,
    inputs: &mut HashMap<String, Vec<BigInt>>,
) -> Result<()> {
    use serde_json::Value;

    enum WorkItem<'a> {
        Value(&'a Value),
        ArrayIter { arr: &'a [Value], idx: usize },
    }

    let mut stack: Vec<WorkItem<'_>> = vec![WorkItem::Value(value)];

    while let Some(item) = stack.pop() {
        match item {
            WorkItem::Value(v) => match v {
                Value::Number(n) => {
                    let bi = if let Some(i) = n.as_u64() {
                        BigInt::from(i)
                    } else if let Some(i) = n.as_i64() {
                        BigInt::from(i)
                    } else {
                        bail!("invalid number for {key}");
                    };
                    inputs.entry(key.to_string()).or_default().push(to_field_element(bi)?);
                }
                Value::String(s) => {
                    let bi = if let Some(hex) = s.strip_prefix("0x") {
                        BigInt::parse_bytes(hex.as_bytes(), 16)
                    } else {
                        BigInt::parse_bytes(s.as_bytes(), 10)
                    };
                    let bi = bi.with_context(|| format!("invalid bigint for {key}: {s}"))?;
                    inputs.entry(key.to_string()).or_default().push(to_field_element(bi)?);
                }
                Value::Array(arr) => {
                    if !arr.is_empty() {
                        stack.push(WorkItem::ArrayIter { arr, idx: 0 });
                    }
                }
                Value::Bool(b) => {
                    inputs.entry(key.to_string()).or_default().push(BigInt::from(u8::from(*b)));
                }
                Value::Null => {
                    inputs.entry(key.to_string()).or_default().push(BigInt::from(0));
                }
                Value::Object(_) => bail!("unexpected object in pure array: {key}"),
            },
            WorkItem::ArrayIter { arr, idx } => {
                let next_idx = idx.saturating_add(1);
                if next_idx < arr.len() {
                    stack.push(WorkItem::ArrayIter { arr, idx: next_idx });
                }
                stack.push(WorkItem::Value(&arr[idx]));
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    // Known-good 1-in(of 4)/1-out policy_tx_4_2 input vector (see fixture-gen).
    const FIXTURE: &str = include_str!("../fixtures/policy_tx_4_2_1in1out.json");
    // Valid DepositParams (see fixture-gen/src/bin/deposit_params.rs).
    const DEPOSIT_PARAMS: &str = include_str!("../fixtures/deposit_params.json");

    /// The deposit flow end-to-end: params -> assemble_deposit -> circuit inputs
    /// -> prove -> verify. Validates the whole flow→prove chain.
    #[test]
    fn deposit_flow_proves_and_verifies() {
        let artifacts =
            assemble_deposit(DEPOSIT_PARAMS.to_string()).expect("assemble_deposit failed");
        assert_eq!(artifacts.ext_amount, "120", "unexpected ext_amount");
        let bundle =
            prove_policy_tx_4_2(&artifacts.circuit_inputs_json).expect("prove failed");
        assert!(
            verify_locally(&bundle).expect("verify errored"),
            "deposit proof failed to verify",
        );
    }

    /// Validates the Phase 3 nullifier formula against REAL on-chain state:
    /// the scalar-102 note (commitment 18263061…) appears at leaf indices
    /// 0,4,8,10,12; their nullifiers must all be in the on-chain spent set
    /// (the demo spent them). Confirms `scan_note`'s nullifier derivation.
    #[test]
    fn nullifier_formula_matches_onchain() {
        use num_bigint::BigUint;
        let comm_dec = "18263061230805500442272313235592384331733893249443814861628842931597516851047";
        let comm = types::Field::try_from_be_bytes(
            BigUint::parse_bytes(comm_dec.as_bytes(), 10).unwrap().to_bytes_be().try_into().unwrap(),
        )
        .unwrap();
        let comm_le = comm.to_le_bytes();
        // scalar-102 note private key = Field(102) LE bytes.
        let note_priv = types::Field::try_from_be_bytes({
            let mut be = [0u8; 32];
            be[31] = 102;
            be
        })
        .unwrap()
        .to_le_bytes();

        // On-chain nullifier set (decimal) from our indexer.
        let onchain: std::collections::HashSet<&str> = [
            "2686318526954992817838553843541126011865616715698478693153807245910213019785",
            "18971212323731160423153073119731953152953660639843222567183439033107711203533",
            "21149557482349299553378960986034341961191168638442917675901218280087974105038",
            "10030223767479328340803399531006188902300167419724732853185832294350014760285",
            "18881672358135471807796556007842940728593503656638386940270959546159950678805",
            "12904739193189052067521845475215862103714871546619324374345325583340626742324",
            "3949448520295245707265478059284097986554001338310641280138981668159738162055",
            "6672264070197750575043656204966605053724355210711455041726134244571584756491",
            "18308053204054474513543138179767771448846718000019244166543624575029856326334",
            "7751152949286079740496516072102665754619044776288320548857106014408443431764",
            "8540833198319213980277255805343638342870815723212655127002493861603150265691",
            "18354679797162433168246215193141607701482373198871585112363329828082341006477",
            "15103458741249700652513851136840696496725194836953710083090949258219566195876",
            "14704724076053339214740030781684005350307850833768957453958180118930655670573",
        ]
        .into_iter()
        .collect();

        // Indices 0 and 4 were definitely spent by the CLI withdraw/transfer demos.
        for idx in [0u32, 4] {
            let mut pi = [0u8; 32];
            pi[..8].copy_from_slice(&u64::from(idx).to_le_bytes());
            let sig = prover::crypto::compute_signature(&note_priv, &comm_le, &pi).unwrap();
            let nf = prover::crypto::compute_nullifier(&comm_le, &pi, &sig).unwrap();
            let dec = BigUint::from_bytes_le(&nf).to_str_radix(10);
            assert!(onchain.contains(dec.as_str()), "index {idx} nullifier {dec} not on-chain — formula wrong");
            eprintln!("index {idx} nullifier {dec} ✓ on-chain (spent)");
        }
    }

    // Fixed test identity for the per-seed P2P de-risk.
    const TEST_PHRASE: &str = "illness spike retreat truth genius clock brain pass fit cave bargain toe";

    /// Prints the per-seed account's note pubkey + ASP leaf to enroll (gate3),
    /// plus scalar-102's leaf (tree index 0). `-- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn perseed_info() {
        let kb = account_shielded_keys(TEST_PHRASE.into(), 0).unwrap();
        let note_pub_hex = format!("0x{}", kb.note_public.iter().map(|b| format!("{b:02x}")).collect::<String>());
        let leaf = asp_membership_leaf_dec(note_pub_hex.clone()).unwrap();
        let s102: Vec<u8> = { let mut v = vec![0u8; 32]; v[0] = 102; v };
        let s102_leaf = asp_membership_leaf_dec(note_public_key(s102).unwrap()).unwrap();
        eprintln!("PERSEED_NOTE_PUB={note_pub_hex}");
        eprintln!("PERSEED_ASP_LEAF={leaf}");
        eprintln!("SCALAR102_ASP_LEAF={s102_leaf}");
        // Account 1's shielded address (P2P recipient) + ASP leaf to enroll.
        let kb1 = account_shielded_keys(TEST_PHRASE.into(), 1).unwrap();
        let blob = [kb1.note_public.clone(), kb1.encryption_public.clone()].concat();
        use base64::{engine::general_purpose::STANDARD, Engine};
        eprintln!("ACCT1_SHIELDED_ADDR=stella:{}", STANDARD.encode(&blob));
        let a1pub = format!("0x{}", kb1.note_public.iter().map(|b| format!("{b:02x}")).collect::<String>());
        eprintln!("ACCT1_ASP_LEAF={}", asp_membership_leaf_dec(a1pub).unwrap());
    }

    /// Part B de-risk: rebuilding scalar-102's ASP membership proof in-app from
    /// the 1-leaf membership tree must reproduce the known on-chain membership
    /// root. Proves `build_asp_proofs` is correct → per-seed-key enrollment
    /// (insert leaf → rebuild adapts) will work. Pure (no network).
    #[test]
    fn asp_membership_rebuild_matches_onchain_root() {
        use num_bigint::BigUint;
        // scalar-102 note pubkey + its ASP leaf (from the deposit fixture).
        let note_priv: Vec<u8> = { let mut v = vec![0u8; 32]; v[0] = 102; v };
        let note_pub_hex = note_public_key(note_priv).unwrap();
        let leaf = prover::crypto::asp_membership_leaf(
            &types::NotePublicKey(
                hex_decode(note_pub_hex.trim_start_matches("0x")).unwrap().try_into().unwrap(),
            ),
            &types::Field::ZERO,
        ).unwrap();
        let leaf_dec = BigUint::from_bytes_be(&leaf.to_be_bytes()).to_str_radix(10);

        // The deposit fixture carries the known-good membership proof against the
        // LIVE ASP tree (3 leaves, scalar-102 at index 2 — see fixture-gen's
        // ASP_LEAVES snapshot) — use its non_membership object as the template
        // and rebuild with the same leaf set.
        let fixture: serde_json::Value = serde_json::from_str(DEPOSIT_ONCHAIN).unwrap();
        let expected_root = fixture["membership_proof"]["root"].as_str().unwrap();
        let nm = fixture["non_membership_proof"].to_string();

        let leaves = vec![
            "9994517823975672122284466930373335370852567112864722324125744549747666024239".into(),
            "2265510885976041928383517713406139983991156639540722924781593281193278186508".into(),
            leaf_dec,
        ];
        let proofs = build_asp_proofs(note_pub_hex, leaves, 10, nm).unwrap();
        let rebuilt: serde_json::Value = serde_json::from_str(&proofs.membership_json).unwrap();
        assert_eq!(rebuilt["root"].as_str().unwrap(), expected_root, "rebuilt ASP root must match on-chain");
        eprintln!("✅ in-app ASP membership rebuild matches on-chain root {expected_root}");
    }

    /// Phase 6: canonical selective disclosure end-to-end. Issues a receipt for
    /// the scalar-102 0.1 note (its real on-chain commitment) bound to an
    /// authority/purpose/nonce context, then runs the full three-part verify
    /// (proof + context + known-root). Confirms wrong-nonce (replay) and
    /// unknown-root both fail. Self-contained (encodes the commitment as a U256
    /// topic; single-leaf tree).
    #[test]
    fn disclosure_receipt_proves_and_verifies() {
        use num_bigint::BigUint;
        use stellar_xdr::curr::{Limits, ScVal, UInt256Parts, WriteXdr};

        const NOTE_COMM: &str =
            "18263061230805500442272313235592384331733893249443814861628842931597516851047";
        let comm = types::Field::try_from_be_bytes(
            BigUint::parse_bytes(NOTE_COMM.as_bytes(), 10).unwrap().to_bytes_be().try_into().unwrap(),
        )
        .unwrap();
        let be = comm.to_be_bytes();
        let topic = ScVal::U256(UInt256Parts {
            hi_hi: u64::from_be_bytes(be[0..8].try_into().unwrap()),
            hi_lo: u64::from_be_bytes(be[8..16].try_into().unwrap()),
            lo_hi: u64::from_be_bytes(be[16..24].try_into().unwrap()),
            lo_lo: u64::from_be_bytes(be[24..32].try_into().unwrap()),
        })
        .to_xdr_base64(Limits::none())
        .unwrap();

        let blinding = format!("0x{}", "05".repeat(32));
        let note_priv: Vec<u8> = { let mut v = vec![0u8; 32]; v[0] = 102; v };

        let issued = issue_disclosure_receipt(
            vec![topic], 0, "1000000".into(), blinding, note_priv, 10,
            "testnet".into(), "CCDFQ5D3".into(), "Acme Auditors".into(),
            "kyc-review".into(), "424242".into(), "2026-06-19T00:00:00Z".into(),
        )
        .expect("issue_disclosure_receipt");
        assert_eq!(issued.amount, "1000000");
        assert_eq!(issued.note_commitment, NOTE_COMM);

        // Full verify against the live root — all four checks pass.
        let report = verify_disclosure_receipt(issued.receipt_json.clone(), vec![issued.root.clone()])
            .expect("verify");
        assert!(report.fully_verified, "receipt should fully verify: {report:?}");
        assert!(report.proof_verified && report.context_verified && report.known_root_status);
        assert!(report.amount_verified, "opening should recompute to the commitment");

        // Tamper the disclosed amount → amount check fails (opening ≠ commitment).
        let wrong_amount = issued.receipt_json.replace("\"1000000\"", "\"9999999\"");
        let bad_amt = verify_disclosure_receipt(wrong_amount, vec![issued.root.clone()]).expect("verify");
        assert!(!bad_amt.amount_verified && !bad_amt.fully_verified, "lying about the amount must be caught");

        // Unknown root → known_root_status fails (not fully verified).
        let bad_root = verify_disclosure_receipt(issued.receipt_json.clone(), vec!["999".into()])
            .expect("verify");
        assert!(!bad_root.fully_verified && !bad_root.known_root_status);

        // Tamper a context field (purpose) → re-derived hash ≠ bound hash.
        assert!(issued.receipt_json.contains("kyc-review"), "purpose should be in receipt");
        let tampered = issued.receipt_json.replace("kyc-review", "fraud-review");
        let bad_ctx = verify_disclosure_receipt(tampered, vec![issued.root.clone()]).expect("verify");
        assert!(!bad_ctx.context_verified, "context tamper must be detected");
        assert!(!bad_ctx.fully_verified);
        eprintln!("✅ canonical disclosure: 3-part verify OK; unknown-root + context-tamper rejected");
    }

    /// Gate 2: the rust-witness ↔ r1cs wire-ordering correctness check.
    /// A wrong ordering yields a proof that fails verification.
    #[test]
    fn proof_from_fixture_verifies() {
        let bundle = prove_policy_tx_4_2(FIXTURE).expect("proof generation failed");
        assert_eq!(bundle.proof.len(), 256, "unexpected proof byte length");
        assert_eq!(
            bundle.public_inputs.len() % 32,
            0,
            "public inputs not a multiple of 32 bytes"
        );
        let ok = verify_locally(&bundle).expect("verification errored");
        assert!(ok, "proof failed to verify — wire ordering mismatch?");
    }

    const DEPOSIT_ONCHAIN: &str = include_str!("../fixtures/deposit_onchain.json");

    // Shared: assemble -> prove -> write pool `transact` args JSON to `out`.
    fn emit_transact(circuit_inputs_json: &str, art: &FlowArtifacts, out: &str) {
        use num_bigint::BigUint;
        let bundle = prove_policy_tx_4_2(circuit_inputs_json).expect("prove");
        assert!(verify_locally(&bundle).expect("verify"), "failed local verify");
        let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
        let pi: Vec<String> = bundle
            .public_inputs
            .chunks_exact(32)
            .map(|le| BigUint::from_bytes_be(&le.iter().rev().copied().collect::<Vec<u8>>()).to_str_radix(10))
            .collect();
        let json = serde_json::json!({
            "proof": {
                "asp_membership_root": pi[9], "asp_non_membership_root": pi[13],
                "ext_data_hash": hex(&art.ext_data_hash),
                "input_nullifiers": [pi[3], pi[4], pi[5], pi[6]],
                "output_commitment0": pi[7], "output_commitment1": pi[8],
                "proof": { "a": hex(&bundle.proof[0..64]), "b": hex(&bundle.proof[64..192]), "c": hex(&bundle.proof[192..256]) },
                "public_amount": pi[1], "root": pi[0],
            },
            "ext_data": {
                "recipient": art.ext_recipient, "ext_amount": art.ext_amount,
                "encrypted_output0": hex(&art.encrypted_output0),
                "encrypted_output1": hex(&art.encrypted_output1),
            },
        });
        std::fs::write(out, serde_json::to_string_pretty(&json).unwrap()).expect("write");
        eprintln!("wrote {out}");
    }

    const POOL_ID: &str = "CAYDRYKMO23GEBDSUP5QUM3G4CMOS7YX3TICYAES2N2IAEI3GA22EBMS";
    const RPC_URL: &str = "https://soroban-testnet.stellar.org";

    // ---- Desktop-only RPC helpers (reqwest dev-dep) ------------------------
    // These mirror what Kotlin does on-device: the Rust side is pure (build /
    // sign), the network round-trips live outside it. This test exercises the
    // exact same `account_ledger_key` / `build_unsigned_transact` /
    // `finalize_and_sign` split the app uses.

    fn rpc_call(method: &str, params: serde_json::Value) -> serde_json::Value {
        reqwest::blocking::Client::new()
            .post(RPC_URL)
            .json(&serde_json::json!({"jsonrpc":"2.0","id":1,"method":method,"params":params}))
            .send()
            .unwrap()
            .json()
            .unwrap()
    }

    /// Full in-app submission path with the network in the test (not in the
    /// prover lib): generate wallet -> friendbot fund -> assemble_deposit ->
    /// prove -> account seq -> build unsigned -> simulate -> sign -> send -> poll.
    #[test]
    #[ignore]
    fn submit_deposit_onchain() {
        let acct = generate_stellar_account();
        eprintln!("wallet {}", acct.address);
        let funded = reqwest::blocking::Client::new()
            .get(format!("https://friendbot.stellar.org?addr={}", acct.address))
            .send()
            .map(|r| r.status().is_success())
            .unwrap_or(false);
        assert!(funded, "friendbot fund failed");
        std::thread::sleep(std::time::Duration::from_secs(4));

        let a = assemble_deposit(DEPOSIT_ONCHAIN.to_string()).unwrap();
        let bundle = prove_policy_tx_4_2(&a.circuit_inputs_json).unwrap();

        // 1. account sequence via getLedgerEntries.
        let key = account_ledger_key(acct.address.clone()).unwrap();
        let entries = rpc_call("getLedgerEntries", serde_json::json!({"keys":[key]}));
        let entry_xdr = entries["result"]["entries"][0]["xdr"].as_str().expect("account entry xdr");

        // 2. build unsigned tx.
        let unsigned = build_unsigned_transact(
            POOL_ID.into(), acct.address.clone(), entry_xdr.into(),
            bundle.proof, bundle.public_inputs, a.ext_data_hash,
            a.ext_recipient, a.ext_amount, a.encrypted_output0, a.encrypted_output1,
        )
        .expect("build_unsigned_transact failed");

        // 3. simulate.
        let sim = rpc_call("simulateTransaction", serde_json::json!({"transaction":unsigned}));

        // 4. assemble + sign.
        let signed = finalize_and_sign(unsigned, sim.to_string(), acct.secret)
            .expect("finalize_and_sign failed");

        // 5. send + poll.
        let send = rpc_call("sendTransaction", serde_json::json!({"transaction":signed}));
        assert_ne!(send["result"]["status"].as_str(), Some("ERROR"), "sendTransaction ERROR: {send}");
        let hash = send["result"]["hash"].as_str().expect("tx hash").to_string();
        let mut status = String::new();
        for _ in 0..40 {
            std::thread::sleep(std::time::Duration::from_secs(1));
            let g = rpc_call("getTransaction", serde_json::json!({"hash":hash}));
            status = g["result"]["status"].as_str().unwrap_or("").to_string();
            if status == "SUCCESS" || status == "FAILED" {
                break;
            }
        }
        assert_eq!(status, "SUCCESS", "tx {hash} not successful: {status}");
        eprintln!("✅ DEPOSIT TX HASH = {hash}");
        assert_eq!(hash.len(), 64, "expected a tx hash");
    }

    const WITHDRAW_ONCHAIN: &str = include_str!("../fixtures/withdraw_onchain.json");

    /// Full IN-APP withdraw param building → land on-chain. Mirrors what the
    /// app does on-device: fetch live commitments from the indexer, find an
    /// UNSPENT scalar-102 0.1 note, `rebuild_input_path` against the live tree,
    /// assemble → prove → submit. Proves withdraw now lands (fresh nullifier).
    /// Requires the local indexer at :8080. `-- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn submit_inapp_withdraw_onchain() {
        use num_bigint::BigUint;
        // The scalar-102 0.1 XLM deposit note (commitment + blinding [5;32]).
        const NOTE_COMM: &str =
            "18263061230805500442272313235592384331733893249443814861628842931597516851047";
        let blinding = format!("0x{}", "05".repeat(32));
        let note_priv: Vec<u8> = { let mut v = vec![0u8; 32]; v[0] = 102; v };

        // 1. Pull live commitments (topic[1], leaf order) + spent nullifier set.
        let events: serde_json::Value = reqwest::blocking::Client::new()
            .get("http://127.0.0.1:8080/events?cursor=0&limit=300")
            .send().expect("indexer unreachable — is :8080 up?")
            .json().expect("events json");
        let evs = events["events"].as_array().expect("events array");
        let sym = |b64: &str| -> String {
            use base64::{engine::general_purpose::STANDARD, Engine};
            let b = STANDARD.decode(b64).unwrap_or_default();
            if b.len() >= 8 {
                let n = u32::from_be_bytes([b[4], b[5], b[6], b[7]]) as usize;
                String::from_utf8_lossy(&b[8..(8 + n).min(b.len())]).into_owned()
            } else { String::new() }
        };
        let mut commitment_topics: Vec<String> = Vec::new();
        let mut commitment_decs: Vec<String> = Vec::new();
        let mut spent: std::collections::HashSet<String> = std::collections::HashSet::new();
        for e in evs {
            let topics = e["topic"].as_array().cloned().unwrap_or_default();
            if topics.is_empty() { continue; }
            let name = sym(topics[0].as_str().unwrap_or(""));
            let t1 = topics.get(1).and_then(|t| t.as_str()).unwrap_or("").to_string();
            match name.as_str() {
                "new_commitment_event" => {
                    commitment_decs.push(decode_nullifier_topic(t1.clone()).unwrap());
                    commitment_topics.push(t1);
                }
                "new_nullifier_event" => { spent.insert(decode_nullifier_topic(t1).unwrap()); }
                _ => {}
            }
        }

        // 2. Find an UNSPENT scalar-102 0.1 note: commitment matches + its
        //    nullifier (at that leaf index) is not yet on-chain.
        let mut chosen: Option<u32> = None;
        for (i, c) in commitment_decs.iter().enumerate() {
            if c != NOTE_COMM { continue; }
            let comm = types::Field::try_from_be_bytes(
                BigUint::parse_bytes(NOTE_COMM.as_bytes(), 10).unwrap().to_bytes_be().try_into().unwrap(),
            ).unwrap();
            let comm_le = comm.to_le_bytes();
            let mut pi = [0u8; 32];
            pi[..8].copy_from_slice(&(i as u64).to_le_bytes());
            let sig = prover::crypto::compute_signature(&note_priv, &comm_le, &pi).unwrap();
            let nf = prover::crypto::compute_nullifier(&comm_le, &pi, &sig).unwrap();
            let nf_dec = BigUint::from_bytes_le(&nf).to_str_radix(10);
            if !spent.contains(&nf_dec) { chosen = Some(i as u32); break; }
        }
        let leaf_index = chosen.expect("no unspent scalar-102 0.1 note found — deposit one first");
        eprintln!("spending unspent note at leaf #{leaf_index}");

        // 3. Rebuild params against the live tree, then assemble + prove.
        let params = rebuild_input_path(
            WITHDRAW_ONCHAIN.to_string(), commitment_topics, leaf_index, "1000000".into(), blinding,
        ).expect("rebuild_input_path");
        let a = assemble_withdraw(params).expect("assemble_withdraw");
        let bundle = prove_policy_tx_4_2(&a.circuit_inputs_json).expect("prove");

        // 4. Fund a fresh fee payer + submit via the same split the app uses.
        let acct = generate_stellar_account();
        let funded = reqwest::blocking::Client::new()
            .get(format!("https://friendbot.stellar.org?addr={}", acct.address))
            .send().map(|r| r.status().is_success()).unwrap_or(false);
        assert!(funded, "friendbot fund failed");
        std::thread::sleep(std::time::Duration::from_secs(4));

        let key = account_ledger_key(acct.address.clone()).unwrap();
        let entries = rpc_call("getLedgerEntries", serde_json::json!({"keys":[key]}));
        let entry_xdr = entries["result"]["entries"][0]["xdr"].as_str().expect("account entry");
        let unsigned = build_unsigned_transact(
            POOL_ID.into(), acct.address.clone(), entry_xdr.into(),
            bundle.proof, bundle.public_inputs, a.ext_data_hash,
            a.ext_recipient, a.ext_amount, a.encrypted_output0, a.encrypted_output1,
        ).expect("build_unsigned");
        let sim = rpc_call("simulateTransaction", serde_json::json!({"transaction":unsigned}));
        assert!(sim["result"]["error"].is_null(), "simulate error: {}", sim["result"]["error"]);
        let signed = finalize_and_sign(unsigned, sim.to_string(), acct.secret).expect("sign");
        let send = rpc_call("sendTransaction", serde_json::json!({"transaction":signed}));
        let hash = send["result"]["hash"].as_str().expect("hash").to_string();
        let mut status = String::new();
        for _ in 0..40 {
            std::thread::sleep(std::time::Duration::from_secs(1));
            let g = rpc_call("getTransaction", serde_json::json!({"hash":hash}));
            status = g["result"]["status"].as_str().unwrap_or("").to_string();
            if status == "SUCCESS" || status == "FAILED" { break; }
        }
        assert_eq!(status, "SUCCESS", "withdraw {hash} not successful: {status}");
        eprintln!("✅ IN-APP WITHDRAW TX HASH = {hash} (leaf #{leaf_index})");
    }

    /// PART B core: a PER-SEED key (enrolled in the ASP set via gate3 at index 1)
    /// deposits on testnet using in-app rebuilt ASP proofs + `apply_identity`.
    /// Confirms (1) the rebuilt membership root matches the new on-chain root and
    /// (2) a per-seed key can transact. Requires the indexer + prior enrollment.
    #[test]
    #[ignore]
    fn submit_perseed_deposit_onchain() {
        use num_bigint::BigUint;
        const SCALAR102_LEAF: &str =
            "17969525783030368157502924498519760117548348265060813172074119923679683982433";
        const NEW_ASP_ROOT_DEC: &str =
            "7650851037732131397668132823701172848267506844599464976150678500985807912790";

        let kb = account_shielded_keys(TEST_PHRASE.into(), 0).unwrap();
        let hex = |b: &[u8]| format!("0x{}", b.iter().map(|x| format!("{x:02x}")).collect::<String>());
        let note_pub_hex = hex(&kb.note_public);
        let note_priv_hex = hex(&kb.note_private);
        let enc_pub_hex = hex(&kb.encryption_public);
        let perseed_leaf = asp_membership_leaf_dec(note_pub_hex.clone()).unwrap();

        let fixture: serde_json::Value = serde_json::from_str(DEPOSIT_ONCHAIN).unwrap();
        let nm = fixture["non_membership_proof"].to_string();
        let asp = build_asp_proofs(
            note_pub_hex, vec![SCALAR102_LEAF.into(), perseed_leaf], 10, nm,
        ).expect("build_asp_proofs");

        // (1) rebuilt membership root must equal the new on-chain ASP root.
        let mem: serde_json::Value = serde_json::from_str(&asp.membership_json).unwrap();
        let root_be = hex_decode(mem["root"].as_str().unwrap().trim_start_matches("0x")).unwrap();
        let root_dec = BigUint::from_bytes_be(&root_be).to_str_radix(10);
        assert_eq!(root_dec, NEW_ASP_ROOT_DEC, "rebuilt ASP root != on-chain");
        eprintln!("✅ rebuilt ASP root matches new on-chain root");

        // (2) build a per-seed deposit + submit.
        let sym = |b64: &str| -> String {
            use base64::{engine::general_purpose::STANDARD, Engine};
            let b = STANDARD.decode(b64).unwrap_or_default();
            if b.len() >= 8 {
                let n = u32::from_be_bytes([b[4], b[5], b[6], b[7]]) as usize;
                String::from_utf8_lossy(&b[8..(8 + n).min(b.len())]).into_owned()
            } else { String::new() }
        };
        let events: serde_json::Value = reqwest::blocking::Client::new()
            .get("http://127.0.0.1:8080/events?cursor=0&limit=300").send().unwrap().json().unwrap();
        let mut topics: Vec<String> = Vec::new();
        for e in events["events"].as_array().unwrap() {
            let t = e["topic"].as_array().cloned().unwrap_or_default();
            if !t.is_empty() && sym(t[0].as_str().unwrap_or("")) == "new_commitment_event" {
                topics.push(t.get(1).and_then(|x| x.as_str()).unwrap_or("").to_string());
            }
        }
        let mut params = build_deposit_params(DEPOSIT_ONCHAIN.to_string(), "1000000".into(), topics, 10).unwrap();
        params = apply_identity(params, false, note_priv_hex, enc_pub_hex, asp.membership_json, asp.non_membership_json).unwrap();
        let a = assemble_deposit(params).expect("assemble");
        let bundle = prove_policy_tx_4_2(&a.circuit_inputs_json).expect("prove");

        let acct = generate_stellar_account();
        assert!(reqwest::blocking::Client::new()
            .get(format!("https://friendbot.stellar.org?addr={}", acct.address))
            .send().map(|r| r.status().is_success()).unwrap_or(false), "friendbot");
        std::thread::sleep(std::time::Duration::from_secs(4));
        let key = account_ledger_key(acct.address.clone()).unwrap();
        let entry = rpc_call("getLedgerEntries", serde_json::json!({"keys":[key]}));
        let entry_xdr = entry["result"]["entries"][0]["xdr"].as_str().unwrap();
        let unsigned = build_unsigned_transact(
            POOL_ID.into(), acct.address.clone(), entry_xdr.into(),
            bundle.proof, bundle.public_inputs, a.ext_data_hash,
            a.ext_recipient, a.ext_amount, a.encrypted_output0, a.encrypted_output1,
        ).unwrap();
        let sim = rpc_call("simulateTransaction", serde_json::json!({"transaction":unsigned}));
        assert!(sim["result"]["error"].is_null(), "simulate: {}", sim["result"]["error"]);
        let signed = finalize_and_sign(unsigned, sim.to_string(), acct.secret).unwrap();
        let send = rpc_call("sendTransaction", serde_json::json!({"transaction":signed}));
        let h = send["result"]["hash"].as_str().expect("hash").to_string();
        let mut status = String::new();
        for _ in 0..40 {
            std::thread::sleep(std::time::Duration::from_secs(1));
            let g = rpc_call("getTransaction", serde_json::json!({"hash":h}));
            status = g["result"]["status"].as_str().unwrap_or("").to_string();
            if status == "SUCCESS" || status == "FAILED" { break; }
        }
        assert_eq!(status, "SUCCESS", "per-seed deposit {h}: {status}");
        eprintln!("✅ PER-SEED DEPOSIT TX = {h}");
    }

    /// PART B headline: a real PRIVATE P2P transfer between two per-seed
    /// accounts of one seed. Account-0 (ASP-enrolled) spends its note and creates
    /// an output for **account-1's shielded address**; then account-1's
    /// encryption key decrypts that output (proves receipt). No public token
    /// movement. Requires the indexer + account-0 enrolled + an unspent acct-0 note.
    #[test]
    #[ignore]
    fn submit_perseed_p2p_transfer_onchain() {
        const SCALAR102_LEAF: &str =
            "17969525783030368157502924498519760117548348265060813172074119923679683982433";
        let hx = |b: &[u8]| format!("0x{}", b.iter().map(|x| format!("{x:02x}")).collect::<String>());
        let a0 = account_shielded_keys(TEST_PHRASE.into(), 0).unwrap();
        let a1 = account_shielded_keys(TEST_PHRASE.into(), 1).unwrap();

        // Pull events.
        let sym = |b64: &str| -> String {
            use base64::{engine::general_purpose::STANDARD, Engine};
            let b = STANDARD.decode(b64).unwrap_or_default();
            if b.len() >= 8 {
                let n = u32::from_be_bytes([b[4], b[5], b[6], b[7]]) as usize;
                String::from_utf8_lossy(&b[8..(8 + n).min(b.len())]).into_owned()
            } else { String::new() }
        };
        let events: serde_json::Value = reqwest::blocking::Client::new()
            .get("http://127.0.0.1:8080/events?cursor=0&limit=400").send().unwrap().json().unwrap();
        let mut topics: Vec<String> = Vec::new();
        let mut values: Vec<String> = Vec::new();
        let mut spent: std::collections::HashSet<String> = std::collections::HashSet::new();
        let mut asp_leaves: Vec<String> = Vec::new();
        for e in events["events"].as_array().unwrap() {
            let t = e["topic"].as_array().cloned().unwrap_or_default();
            if t.is_empty() { continue; }
            match sym(t[0].as_str().unwrap_or("")).as_str() {
                "new_commitment_event" => {
                    topics.push(t.get(1).and_then(|x| x.as_str()).unwrap_or("").into());
                    values.push(e["value"].as_str().unwrap_or("").into());
                }
                "new_nullifier_event" => { spent.insert(decode_nullifier_topic(t[1].as_str().unwrap().into()).unwrap()); }
                "LeafAdded" => { asp_leaves.push(decode_asp_leaf(e["value"].as_str().unwrap().into()).unwrap()); }
                _ => {}
            }
        }
        let _ = SCALAR102_LEAF; // (asp_leaves already includes index 0 from the indexer)

        // Find an UNSPENT account-0 note (scan with account-0's keys).
        let mut chosen: Option<SelectedNote> = None;
        for (i, (tp, vl)) in topics.iter().zip(values.iter()).enumerate() {
            if let Ok(Some(n)) = scan_note(tp.clone(), vl.clone(), a0.encryption_private.clone(), a0.note_private.clone()) {
                if n.amount.parse::<u128>().unwrap_or(0) > 0 && !spent.contains(&n.nullifier) {
                    chosen = Some(SelectedNote { leaf_index: i as u32, amount: n.amount, blinding: n.blinding });
                    break;
                }
            }
        }
        let note = chosen.expect("no unspent account-0 note — run submit_perseed_deposit_onchain first");
        let amount = note.amount.clone();
        eprintln!("account-0 spending note leaf #{} amount {}", note.leaf_index, amount);

        // Build transfer → account-1's shielded address, apply account-0 identity + ASP.
        let nm = serde_json::from_str::<serde_json::Value>(DEPOSIT_ONCHAIN).unwrap()["non_membership_proof"].to_string();
        let mut params = build_transfer_params(
            TRANSFER_ONCHAIN.to_string(), amount.clone(), hx(&a1.note_public), hx(&a1.encryption_public),
            vec![note], topics.clone(), 10,
        ).unwrap();
        let asp = build_asp_proofs(hx(&a0.note_public), asp_leaves, 10, nm).unwrap();
        params = apply_identity(params, true, hx(&a0.note_private), hx(&a0.encryption_public), asp.membership_json, asp.non_membership_json).unwrap();
        let a = assemble_transfer(params).expect("assemble_transfer");
        let bundle = prove_policy_tx_4_2(&a.circuit_inputs_json).expect("prove");

        // Submit.
        let acct = generate_stellar_account();
        assert!(reqwest::blocking::Client::new()
            .get(format!("https://friendbot.stellar.org?addr={}", acct.address))
            .send().map(|r| r.status().is_success()).unwrap_or(false), "friendbot");
        std::thread::sleep(std::time::Duration::from_secs(4));
        let key = account_ledger_key(acct.address.clone()).unwrap();
        let entry = rpc_call("getLedgerEntries", serde_json::json!({"keys":[key]}));
        let entry_xdr = entry["result"]["entries"][0]["xdr"].as_str().unwrap();
        let unsigned = build_unsigned_transact(
            POOL_ID.into(), acct.address.clone(), entry_xdr.into(),
            bundle.proof, bundle.public_inputs, a.ext_data_hash,
            a.ext_recipient, a.ext_amount, a.encrypted_output0.clone(), a.encrypted_output1,
        ).unwrap();
        let sim = rpc_call("simulateTransaction", serde_json::json!({"transaction":unsigned}));
        assert!(sim["result"]["error"].is_null(), "simulate: {}", sim["result"]["error"]);
        let signed = finalize_and_sign(unsigned, sim.to_string(), acct.secret).unwrap();
        let send = rpc_call("sendTransaction", serde_json::json!({"transaction":signed}));
        let h = send["result"]["hash"].as_str().expect("hash").to_string();
        let mut status = String::new();
        for _ in 0..40 {
            std::thread::sleep(std::time::Duration::from_secs(1));
            let g = rpc_call("getTransaction", serde_json::json!({"hash":h}));
            status = g["result"]["status"].as_str().unwrap_or("").to_string();
            if status == "SUCCESS" || status == "FAILED" { break; }
        }
        assert_eq!(status, "SUCCESS", "P2P transfer {h}: {status}");

        // RECEIPT proof: account-1's enc key decrypts the output note (output0).
        let recovered = prover::encryption::decrypt_output_note(
            &types::EncryptionPrivateKey({ let mut k=[0u8;32]; k.copy_from_slice(&a1.encryption_private); k }),
            &a.encrypted_output0,
        ).unwrap();
        let (recv_amount, _) = recovered.expect("account-1 must decrypt the received note");
        let recv: u128 = recv_amount.into();
        assert_eq!(recv.to_string(), amount, "account-1 received the sent amount");
        eprintln!("✅ PRIVATE P2P TRANSFER TX = {h} — account-1 received {amount} (no public movement)");
    }

    /// Arbitrary-amount deposit via `build_deposit_params` (the in-app path):
    /// fetch live commitments, build a 0.37 XLM deposit, assemble→prove→submit.
    /// Confirms non-0.1 amounts land. Requires the indexer at :8080.
    #[test]
    #[ignore]
    fn submit_arbitrary_deposit_onchain() {
        let sym = |b64: &str| -> String {
            use base64::{engine::general_purpose::STANDARD, Engine};
            let b = STANDARD.decode(b64).unwrap_or_default();
            if b.len() >= 8 {
                let n = u32::from_be_bytes([b[4], b[5], b[6], b[7]]) as usize;
                String::from_utf8_lossy(&b[8..(8 + n).min(b.len())]).into_owned()
            } else { String::new() }
        };
        let events: serde_json::Value = reqwest::blocking::Client::new()
            .get("http://127.0.0.1:8080/events?cursor=0&limit=300")
            .send().expect("indexer unreachable").json().expect("json");
        let mut topics: Vec<String> = Vec::new();
        for e in events["events"].as_array().unwrap() {
            let t = e["topic"].as_array().cloned().unwrap_or_default();
            if !t.is_empty() && sym(t[0].as_str().unwrap_or("")) == "new_commitment_event" {
                topics.push(t.get(1).and_then(|x| x.as_str()).unwrap_or("").to_string());
            }
        }

        let params = build_deposit_params(DEPOSIT_ONCHAIN.to_string(), "3700000".into(), topics, 10)
            .expect("build_deposit_params");
        let a = assemble_deposit(params).expect("assemble_deposit");
        let bundle = prove_policy_tx_4_2(&a.circuit_inputs_json).expect("prove");

        let acct = generate_stellar_account();
        assert!(
            reqwest::blocking::Client::new()
                .get(format!("https://friendbot.stellar.org?addr={}", acct.address))
                .send().map(|r| r.status().is_success()).unwrap_or(false),
            "friendbot",
        );
        std::thread::sleep(std::time::Duration::from_secs(4));
        let key = account_ledger_key(acct.address.clone()).unwrap();
        let entries = rpc_call("getLedgerEntries", serde_json::json!({"keys":[key]}));
        let entry_xdr = entries["result"]["entries"][0]["xdr"].as_str().expect("entry");
        let unsigned = build_unsigned_transact(
            POOL_ID.into(), acct.address.clone(), entry_xdr.into(),
            bundle.proof, bundle.public_inputs, a.ext_data_hash,
            a.ext_recipient, a.ext_amount, a.encrypted_output0, a.encrypted_output1,
        ).expect("build_unsigned");
        let sim = rpc_call("simulateTransaction", serde_json::json!({"transaction":unsigned}));
        assert!(sim["result"]["error"].is_null(), "simulate: {}", sim["result"]["error"]);
        let signed = finalize_and_sign(unsigned, sim.to_string(), acct.secret).expect("sign");
        let send = rpc_call("sendTransaction", serde_json::json!({"transaction":signed}));
        let hash = send["result"]["hash"].as_str().expect("hash").to_string();
        let mut status = String::new();
        for _ in 0..40 {
            std::thread::sleep(std::time::Duration::from_secs(1));
            let g = rpc_call("getTransaction", serde_json::json!({"hash":hash}));
            status = g["result"]["status"].as_str().unwrap_or("").to_string();
            if status == "SUCCESS" || status == "FAILED" { break; }
        }
        assert_eq!(status, "SUCCESS", "deposit {hash}: {status}");
        eprintln!("✅ ARBITRARY DEPOSIT (0.37 XLM) TX = {hash}");
    }

    #[test]
    #[ignore]
    fn emit_onchain_withdraw() {
        let a = assemble_withdraw(WITHDRAW_ONCHAIN.to_string()).expect("assemble_withdraw");
        emit_transact(&a.circuit_inputs_json, &a, "/tmp/withdraw_args.json");
    }

    /// Gate B4b: spend the fixture's note straight (no indexer rebuild needed —
    /// `WITHDRAW_ONCHAIN` already bakes the pool_root/path for the single note
    /// our own `submit_deposit_onchain` run put in the new pool). Mirrors
    /// `submit_deposit_onchain`'s build/sign/submit split.
    #[test]
    #[ignore]
    fn submit_withdraw_onchain() {
        let a = assemble_withdraw(WITHDRAW_ONCHAIN.to_string()).expect("assemble_withdraw");
        let bundle = prove_policy_tx_4_2(&a.circuit_inputs_json).unwrap();

        let acct = generate_stellar_account();
        eprintln!("wallet {}", acct.address);
        let funded = reqwest::blocking::Client::new()
            .get(format!("https://friendbot.stellar.org?addr={}", acct.address))
            .send()
            .map(|r| r.status().is_success())
            .unwrap_or(false);
        assert!(funded, "friendbot fund failed");
        std::thread::sleep(std::time::Duration::from_secs(4));

        let key = account_ledger_key(acct.address.clone()).unwrap();
        let entries = rpc_call("getLedgerEntries", serde_json::json!({"keys":[key]}));
        let entry_xdr = entries["result"]["entries"][0]["xdr"].as_str().expect("account entry xdr");

        let unsigned = build_unsigned_transact(
            POOL_ID.into(), acct.address.clone(), entry_xdr.into(),
            bundle.proof, bundle.public_inputs, a.ext_data_hash,
            a.ext_recipient, a.ext_amount, a.encrypted_output0, a.encrypted_output1,
        )
        .expect("build_unsigned_transact failed");

        let sim = rpc_call("simulateTransaction", serde_json::json!({"transaction":unsigned}));
        assert!(sim["result"]["error"].is_null(), "simulate error: {}", sim["result"]["error"]);

        let signed = finalize_and_sign(unsigned, sim.to_string(), acct.secret)
            .expect("finalize_and_sign failed");

        let send = rpc_call("sendTransaction", serde_json::json!({"transaction":signed}));
        assert_ne!(send["result"]["status"].as_str(), Some("ERROR"), "sendTransaction ERROR: {send}");
        let hash = send["result"]["hash"].as_str().expect("tx hash").to_string();
        let mut status = String::new();
        for _ in 0..40 {
            std::thread::sleep(std::time::Duration::from_secs(1));
            let g = rpc_call("getTransaction", serde_json::json!({"hash":hash}));
            status = g["result"]["status"].as_str().unwrap_or("").to_string();
            if status == "SUCCESS" || status == "FAILED" {
                break;
            }
        }
        assert_eq!(status, "SUCCESS", "tx {hash} not successful: {status}");
        eprintln!("✅ WITHDRAW TX HASH = {hash}");
    }

    const TRANSFER_ONCHAIN: &str = include_str!("../fixtures/transfer_onchain.json");

    #[test]
    #[ignore]
    fn emit_onchain_transfer() {
        let a = assemble_transfer(TRANSFER_ONCHAIN.to_string()).expect("assemble_transfer");
        emit_transact(&a.circuit_inputs_json, &a, "/tmp/transfer_args.json");
    }

    /// Scans the REAL on-chain deposit's commitment events (saved to /tmp) with
    /// the app's derived X25519 key and confirms it recovers the 0.1 XLM note.
    #[test]
    #[ignore]
    fn scan_real_deposit() {
        let enc_priv = derive_keys((0..64u8).collect()).unwrap().encryption_private;
        let v5 = std::fs::read_to_string("/tmp/commit_value_5.txt").unwrap();
        let v6 = std::fs::read_to_string("/tmp/commit_value_6.txt").unwrap();
        let n5 = scan_commitment(v5, enc_priv.clone()).unwrap();
        let n6 = scan_commitment(v6, enc_priv).unwrap();
        eprintln!("note5 = {n5:?}, note6 = {n6:?}");
        let total: u128 = [n5, n6].iter().flatten().map(|n| n.amount.parse::<u128>().unwrap()).sum();
        assert_eq!(total, 1_000_000, "should recover the 0.1 XLM deposit");
    }

    /// Emit CLI-ready `transact` args for a real on-chain deposit to our pool:
    /// assemble_deposit -> prove -> extract the pool `Proof` struct + ext_data.
    /// `cargo test -p prover-ffi --lib emit_onchain_deposit -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn emit_onchain_deposit() {
        use num_bigint::BigUint;

        let a = assemble_deposit(DEPOSIT_ONCHAIN.to_string()).expect("assemble_deposit");
        let bundle = prove_policy_tx_4_2(&a.circuit_inputs_json).expect("prove");
        assert!(verify_locally(&bundle).expect("verify"), "proof failed local verify");

        let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
        // public_inputs: LE 32-byte chunks -> BE decimal (U256).
        let pi: Vec<String> = bundle
            .public_inputs
            .chunks_exact(32)
            .map(|le| {
                let be: Vec<u8> = le.iter().rev().copied().collect();
                BigUint::from_bytes_be(&be).to_str_radix(10)
            })
            .collect();
        // Order: [root, publicAmount, extDataHash, null0..3, comm0, comm1,
        //         memRoot0..3, nonMemRoot0..3]
        let proof = serde_json::json!({
            "asp_membership_root": pi[9],
            "asp_non_membership_root": pi[13],
            "ext_data_hash": hex(&a.ext_data_hash),
            "input_nullifiers": [pi[3], pi[4], pi[5], pi[6]],
            "output_commitment0": pi[7],
            "output_commitment1": pi[8],
            "proof": { "a": hex(&bundle.proof[0..64]), "b": hex(&bundle.proof[64..192]), "c": hex(&bundle.proof[192..256]) },
            "public_amount": pi[1],
            "root": pi[0],
        });
        let ext_data = serde_json::json!({
            "recipient": a.ext_recipient,
            "ext_amount": a.ext_amount,
            "encrypted_output0": hex(&a.encrypted_output0),
            "encrypted_output1": hex(&a.encrypted_output1),
        });
        let out = serde_json::json!({ "proof": proof, "ext_data": ext_data });
        std::fs::write("/tmp/transact_args.json", serde_json::to_string_pretty(&out).unwrap())
            .expect("write");
        eprintln!("wrote /tmp/transact_args.json");
    }

    /// Gate 3 helper (run explicitly): prove from the fixture and write
    /// CLI-ready args for the on-chain verifier to `/tmp/gate3_args.json`:
    ///   { "a","b","c": hex (Soroban uncompressed proof parts),
    ///     "public_inputs": [hex32 big-endian, ...] }
    /// Public inputs are reversed LE->BE to match `Bn254Fr::from_bytes`.
    ///
    /// `cargo test -p prover-ffi --lib emit_gate3_args -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn emit_gate3_args() {
        let bundle = prove_policy_tx_4_2(FIXTURE).expect("proof generation failed");
        assert!(verify_locally(&bundle).unwrap(), "local verify failed");

        let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
        let a = hex(&bundle.proof[0..64]);
        let b = hex(&bundle.proof[64..192]);
        let c = hex(&bundle.proof[192..256]);

        let public_inputs: Vec<String> = bundle
            .public_inputs
            .chunks_exact(32)
            .map(|le| {
                let mut be = le.to_vec();
                be.reverse(); // LE (witness) -> BE (Bn254Fr::from_bytes)
                hex(&be)
            })
            .collect();

        let json = serde_json::json!({
            "a": a, "b": b, "c": c, "public_inputs": public_inputs,
        });
        // Override with GATE3_OUT on Android (no /tmp there; use /data/local/tmp).
        let out = std::env::var("GATE3_OUT").unwrap_or_else(|_| "/tmp/gate3_args.json".to_string());
        std::fs::write(&out, serde_json::to_string_pretty(&json).unwrap())
            .expect("write gate3 args");
        eprintln!("wrote {out} ({} public inputs)", public_inputs.len());
    }
}

/// Normalize a signed integer into a BN254 field element (`p - |n|` for
/// negatives). Ported from the reference `witness` crate.
fn to_field_element(bi: BigInt) -> Result<BigInt> {
    let modulus = BigInt::parse_bytes(BN254_FIELD_MODULUS.as_bytes(), 10)
        .ok_or_else(|| anyhow!("invalid field modulus"))?;
    if bi.sign() == Sign::Minus {
        let abs_value = -&bi;
        if abs_value >= modulus {
            bail!("negative value {bi} exceeds field modulus");
        }
        Ok(modulus - abs_value)
    } else {
        if bi >= modulus {
            bail!("value {bi} exceeds field modulus");
        }
        Ok(bi)
    }
}

/// Flatten a nested JSON signal into `HashMap<signal, Vec<BigInt>>`.
/// Ported from the reference `witness` crate so the wire mapping is identical.
fn flatten_input(
    key: &str,
    value: &serde_json::Value,
    inputs: &mut HashMap<String, Vec<BigInt>>,
) -> Result<()> {
    use serde_json::Value;

    let mut stack: Vec<(String, &Value)> = vec![(key.to_string(), value)];

    while let Some((current_key, current_value)) = stack.pop() {
        match current_value {
            Value::Number(n) => {
                let bi = if let Some(i) = n.as_u64() {
                    BigInt::from(i)
                } else if let Some(i) = n.as_i64() {
                    BigInt::from(i)
                } else {
                    bail!("invalid number for {current_key}");
                };
                inputs
                    .entry(current_key)
                    .or_default()
                    .push(to_field_element(bi)?);
            }
            Value::String(s) => {
                let bi = if let Some(hex) = s.strip_prefix("0x") {
                    BigInt::parse_bytes(hex.as_bytes(), 16)
                } else {
                    BigInt::parse_bytes(s.as_bytes(), 10)
                };
                let bi = bi.with_context(|| format!("invalid bigint for {current_key}: {s}"))?;
                inputs
                    .entry(current_key)
                    .or_default()
                    .push(to_field_element(bi)?);
            }
            Value::Array(arr) => {
                if is_pure_array(current_value) {
                    // Arrays of scalars (possibly nested) flatten to the base
                    // key in row-major order: `inAmount` -> [v0, v1].
                    flatten_pure_array(&current_key, current_value, inputs)?;
                } else {
                    // Arrays containing objects get indexed:
                    // `membershipProofs[0][0].leaf`. Push reversed so popping
                    // restores original order.
                    for (idx, item) in arr.iter().enumerate().rev() {
                        stack.push((format!("{current_key}[{idx}]"), item));
                    }
                }
            }
            Value::Object(obj) => {
                for (field, val) in obj {
                    stack.push((format!("{current_key}.{field}"), val));
                }
            }
            Value::Bool(b) => {
                inputs
                    .entry(current_key)
                    .or_default()
                    .push(BigInt::from(u8::from(*b)));
            }
            Value::Null => {
                inputs.entry(current_key).or_default().push(BigInt::from(0));
            }
        }
    }
    Ok(())
}
