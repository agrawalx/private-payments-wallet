//! Pure (no-network) Soroban `transact` transaction building + signing.
//!
//! The RPC round-trips (getLedgerEntries, simulateTransaction, sendTransaction,
//! getTransaction) live in Kotlin (`HttpURLConnection`, Android system TLS) —
//! this module only does the parts that need XDR + the wallet key:
//!
//!   1. `account_ledger_key(addr)`   -> base64 LedgerKey for getLedgerEntries
//!   2. `build_unsigned_transact(..)` -> base64 unsigned tx envelope (to simulate)
//!   3. `finalize_and_sign(..)`       -> base64 signed+assembled envelope (to send)
//!
//! Vendored verbatim (pure fns, no `reqwest`/`tokio`) from the reference
//! `stellar` crate: `build_invoke_contract_tx_envelope`, `assemble_soroban_transaction`,
//! the pool `ScVal` encoders, and the `field/bytes/i128` -> `ScVal` conversions.
//! This drops the `stellar` crate dependency that forced `rustls-platform-verifier`
//! (unusable under Android JNA) into the build.

use anyhow::{anyhow, bail, Result};
use ed25519_dalek::{Signer, SigningKey};
use sha2::{Digest, Sha256};
use stellar_xdr::curr::{
    self as xdr, AccountId, Asset, DecoratedSignature, Hash, Int256Parts, InvokeContractArgs,
    InvokeHostFunctionOp, LedgerEntryData, LedgerKey, LedgerKeyAccount, Limits, Memo, MuxedAccount,
    Operation, OperationBody, PaymentOp, Preconditions, PublicKey as XdrPublicKey, ReadXdr,
    ScAddress, ScMap, ScMapEntry, ScSymbol, ScVal, ScVec, SequenceNumber, Signature, SignatureHint,
    SorobanAuthorizationEntry, SorobanTransactionData, Transaction, TransactionEnvelope,
    TransactionExt, TransactionSignaturePayload,
    TransactionSignaturePayloadTaggedTransaction as Tagged, TransactionV1Envelope, UInt256Parts,
    Uint256, VecM, WriteXdr,
};
use types::{ExtData, Field};

const TESTNET_PASSPHRASE: &str = "Test SDF Network ; September 2015";
/// Classic base fee (stroops); resource fees are added after simulation.
const BASE_FEE: u32 = 100;

// ---- ScVal conversions (vendored from stellar::conversions) ----------------

/// Encodes a BN254 field element as Soroban `ScVal::U256`.
fn field_to_scval_u256(v: Field) -> ScVal {
    let be = v.to_be_bytes();
    ScVal::U256(UInt256Parts {
        hi_hi: u64::from_be_bytes(be[0..8].try_into().unwrap()),
        hi_lo: u64::from_be_bytes(be[8..16].try_into().unwrap()),
        lo_hi: u64::from_be_bytes(be[16..24].try_into().unwrap()),
        lo_lo: u64::from_be_bytes(be[24..32].try_into().unwrap()),
    })
}

/// Encodes 32 big-endian bytes as Soroban `ScVal::U256` (for the ASP leaf).
fn u256_scval_from_be(be: &[u8; 32]) -> ScVal {
    ScVal::U256(UInt256Parts {
        hi_hi: u64::from_be_bytes(be[0..8].try_into().unwrap()),
        hi_lo: u64::from_be_bytes(be[8..16].try_into().unwrap()),
        lo_hi: u64::from_be_bytes(be[16..24].try_into().unwrap()),
        lo_lo: u64::from_be_bytes(be[24..32].try_into().unwrap()),
    })
}

/// Encodes `i128` as Soroban `ScVal::I256` (two's-complement XDR layout).
fn i128_to_i256_scval(n: i128) -> ScVal {
    let hi = if n < 0 { -1i64 } else { 0i64 };
    let hi_lo = u64::from_be_bytes(hi.to_be_bytes());
    let bytes = n.to_be_bytes();
    ScVal::I256(Int256Parts {
        hi_hi: hi,
        hi_lo,
        lo_hi: u64::from_be_bytes(bytes[0..8].try_into().unwrap()),
        lo_lo: u64::from_be_bytes(bytes[8..16].try_into().unwrap()),
    })
}

/// Encodes bytes as Soroban `ScVal::Bytes`.
fn bytes_to_scval(bytes: impl AsRef<[u8]>) -> Result<ScVal> {
    Ok(ScVal::Bytes(
        bytes.as_ref().to_vec().try_into().map_err(|_| anyhow!("bytes too long for ScVal::Bytes"))?,
    ))
}

// ---- Pool ScVal encoders (vendored from stellar::soroban_encode) -----------

fn map_entry(key: &str, val: ScVal) -> Result<ScMapEntry> {
    let sym: xdr::StringM<32> = key.try_into().map_err(|_| anyhow!("invalid map key"))?;
    Ok(ScMapEntry { key: ScVal::Symbol(ScSymbol(sym)), val })
}

/// Builds an `ScVal::Map` with keys sorted (Soroban requires sorted symbol keys).
fn sorted_map(mut entries: Vec<ScMapEntry>) -> Result<ScVal> {
    entries.sort_by(|a, b| match (&a.key, &b.key) {
        (ScVal::Symbol(ka), ScVal::Symbol(kb)) => ka.to_string().cmp(&kb.to_string()),
        _ => std::cmp::Ordering::Equal,
    });
    Ok(ScVal::Map(Some(ScMap(entries.try_into()?))))
}

/// Encodes an uncompressed Groth16 proof (256 bytes) as a `Groth16Proof` map.
fn groth16_proof_to_scval(proof_uncompressed: &[u8]) -> Result<ScVal> {
    if proof_uncompressed.len() != 256 {
        return Err(anyhow!("proof_uncompressed must be 256 bytes, got {}", proof_uncompressed.len()));
    }
    sorted_map(vec![
        map_entry("a", bytes_to_scval(&proof_uncompressed[0..64])?)?,
        map_entry("b", bytes_to_scval(&proof_uncompressed[64..192])?)?,
        map_entry("c", bytes_to_scval(&proof_uncompressed[192..256])?)?,
    ])
}

/// Public inputs of a pool proof, in field order.
struct PoolPublicInputs {
    root: Field,
    input_nullifiers: [Field; 4],
    output_commitment0: Field,
    output_commitment1: Field,
    public_amount: Field,
    ext_data_hash_be: [u8; 32],
    asp_membership_root: Field,
    asp_non_membership_root: Field,
}

/// Encodes the pool `Proof` struct (public inputs + embedded proof) for `transact`.
fn pool_proof_to_scval(proof_uncompressed: &[u8], p: &PoolPublicInputs) -> Result<ScVal> {
    let nullifiers = ScVec::try_from(
        p.input_nullifiers.iter().copied().map(field_to_scval_u256).collect::<Vec<_>>(),
    )?;
    sorted_map(vec![
        map_entry("asp_membership_root", field_to_scval_u256(p.asp_membership_root))?,
        map_entry("asp_non_membership_root", field_to_scval_u256(p.asp_non_membership_root))?,
        map_entry("ext_data_hash", bytes_to_scval(p.ext_data_hash_be)?)?,
        map_entry("input_nullifiers", ScVal::Vec(Some(nullifiers)))?,
        map_entry("output_commitment0", field_to_scval_u256(p.output_commitment0))?,
        map_entry("output_commitment1", field_to_scval_u256(p.output_commitment1))?,
        map_entry("proof", groth16_proof_to_scval(proof_uncompressed)?)?,
        map_entry("public_amount", field_to_scval_u256(p.public_amount))?,
        map_entry("root", field_to_scval_u256(p.root))?,
    ])
}

/// Encodes pool `ExtData` for `transact`.
fn pool_ext_data_to_scval(ext: &ExtData) -> Result<ScVal> {
    sorted_map(vec![
        map_entry("encrypted_output0", bytes_to_scval(&ext.encrypted_output0)?)?,
        map_entry("encrypted_output1", bytes_to_scval(&ext.encrypted_output1)?)?,
        map_entry("ext_amount", i128_to_i256_scval(ext.ext_amount.into()))?,
        map_entry("recipient", ScVal::Address(ext.recipient.parse::<ScAddress>()?))?,
    ])
}

// ---- Tx envelope building (vendored from stellar::contract_state) ----------

fn muxed_account_from_g(account: &str) -> Result<MuxedAccount> {
    let pk = stellar_strkey::ed25519::PublicKey::from_string(account)?;
    Ok(MuxedAccount::Ed25519(Uint256(pk.0)))
}

fn contract_scaddress_from_str(contract_id: &str) -> Result<ScAddress> {
    use std::str::FromStr;
    let contract = stellar_strkey::Contract::from_str(contract_id)?;
    Ok(ScAddress::Contract(xdr::ContractId(Hash(contract.0))))
}

/// Builds an unsigned `InvokeContract` transaction envelope (no auth, V0 ext).
fn build_invoke_contract_tx_envelope(
    source_account: &str,
    seq_num: SequenceNumber,
    fee: u32,
    contract_id: &str,
    function: &str,
    args: Vec<ScVal>,
) -> Result<TransactionEnvelope> {
    let invoke_args = InvokeContractArgs {
        contract_address: contract_scaddress_from_str(contract_id)?,
        function_name: ScSymbol::try_from(function).map_err(|_| anyhow!("invalid function name"))?,
        args: VecM::try_from(args)?,
    };
    let invoke_op = InvokeHostFunctionOp {
        host_function: xdr::HostFunction::InvokeContract(invoke_args),
        auth: VecM::default(),
    };
    let op = Operation { source_account: None, body: OperationBody::InvokeHostFunction(invoke_op) };
    let tx = Transaction {
        source_account: muxed_account_from_g(source_account)?,
        fee,
        seq_num,
        cond: Preconditions::None,
        memo: Memo::None,
        operations: VecM::try_from(vec![op])?,
        ext: TransactionExt::V0,
    };
    Ok(TransactionEnvelope::Tx(TransactionV1Envelope { tx, signatures: VecM::default() }))
}

// ---- Public API ------------------------------------------------------------

/// Base64-XDR `LedgerKey::Account` for a `G...` address — the body of a
/// getLedgerEntries request Kotlin uses to read the account's sequence number.
pub fn account_ledger_key(address: &str) -> Result<String> {
    let pk = stellar_strkey::ed25519::PublicKey::from_string(address)?;
    let key = LedgerKey::Account(LedgerKeyAccount {
        account_id: AccountId(XdrPublicKey::PublicKeyTypeEd25519(Uint256(pk.0))),
    });
    Ok(key.to_xdr_base64(Limits::none())?)
}

/// Builds the public inputs struct from the 17 little-endian 32-byte chunks
/// (order: root, publicAmount, extDataHash, null0..3, comm0, comm1,
/// memRoot0..3, nonMemRoot0..3) + raw ext_data_hash bytes.
///
/// The 4 memRoot/nonMemRoot entries are the same ASP root duplicated once per
/// input slot (`policy_tx_4_2` has `N_INPUTS` membership/non-membership
/// roots, one per input) — they must all agree, since there's only one ASP
/// tree per proof; disagreement means a malformed proof.
fn public_inputs_from_bytes(pi: &[u8], ext_data_hash: &[u8]) -> Result<PoolPublicInputs> {
    let f = |i: usize| -> Result<Field> {
        let s: [u8; 32] = pi[i * 32..i * 32 + 32].try_into()?;
        Field::try_from_le_bytes(s)
    };
    let mem_roots = [f(9)?, f(10)?, f(11)?, f(12)?];
    if mem_roots[1..].iter().any(|r| *r != mem_roots[0]) {
        bail!("asp membership roots disagree across input slots: {mem_roots:?}");
    }
    let non_mem_roots = [f(13)?, f(14)?, f(15)?, f(16)?];
    if non_mem_roots[1..].iter().any(|r| *r != non_mem_roots[0]) {
        bail!("asp non-membership roots disagree across input slots: {non_mem_roots:?}");
    }
    Ok(PoolPublicInputs {
        root: f(0)?,
        public_amount: f(1)?,
        input_nullifiers: [f(3)?, f(4)?, f(5)?, f(6)?],
        output_commitment0: f(7)?,
        output_commitment1: f(8)?,
        asp_membership_root: mem_roots[0],
        asp_non_membership_root: non_mem_roots[0],
        ext_data_hash_be: ext_data_hash.try_into().map_err(|_| anyhow!("ext_data_hash != 32"))?,
    })
}

/// Reads an account's sequence number from the base64 `LedgerEntryData::Account`
/// XDR returned by getLedgerEntries.
fn seq_from_account_entry(account_entry_xdr: &str) -> Result<i64> {
    match LedgerEntryData::from_xdr_base64(account_entry_xdr, Limits::none())? {
        LedgerEntryData::Account(e) => Ok(e.seq_num.0),
        other => Err(anyhow!("expected account ledger entry, got {other:?}")),
    }
}

/// Reads an account's native XLM balance (stroops) from the base64
/// `LedgerEntryData::Account` XDR returned by getLedgerEntries.
pub fn balance_from_account_entry(account_entry_xdr: &str) -> Result<i64> {
    match LedgerEntryData::from_xdr_base64(account_entry_xdr, Limits::none())? {
        LedgerEntryData::Account(e) => Ok(e.balance),
        other => Err(anyhow!("expected account ledger entry, got {other:?}")),
    }
}

/// Build the unsigned `transact` envelope (base64 XDR) for simulation.
///
/// `account_entry_xdr` is the base64 `LedgerEntryData::Account` from
/// getLedgerEntries; the new tx uses `seq_num + 1` (Stellar rejects equal seq).
#[allow(clippy::too_many_arguments)]
pub fn build_unsigned_transact(
    pool: &str,
    source_g: &str,
    account_entry_xdr: &str,
    proof_uncompressed: &[u8],
    public_inputs: &[u8],
    ext_data_hash: &[u8],
    ext_recipient: &str,
    ext_amount: i128,
    enc0: &[u8],
    enc1: &[u8],
) -> Result<String> {
    let public = public_inputs_from_bytes(public_inputs, ext_data_hash)?;
    let proof_scval = pool_proof_to_scval(proof_uncompressed, &public)?;
    let ext_data = ExtData {
        recipient: ext_recipient.to_string(),
        ext_amount: ext_amount.into(),
        encrypted_output0: enc0.to_vec(),
        encrypted_output1: enc1.to_vec(),
    };
    let ext_scval = pool_ext_data_to_scval(&ext_data)?;
    let sender_scval =
        ScVal::Address(source_g.parse().map_err(|e| anyhow!("invalid source account: {e}"))?);

    let next_seq = seq_from_account_entry(account_entry_xdr)?
        .checked_add(1)
        .ok_or_else(|| anyhow!("account sequence overflow"))?;

    let raw = build_invoke_contract_tx_envelope(
        source_g,
        SequenceNumber(next_seq),
        BASE_FEE,
        pool,
        "transact",
        vec![proof_scval, ext_scval, sender_scval],
    )?;
    Ok(raw.to_xdr_base64(Limits::none())?)
}

/// Build an unsigned `insert_leaf(leaf)` invoke against the ASP membership
/// contract — the wallet's self-serve "Register" enrollment. The contract must
/// be in permissionless mode (`set_admin_insert_only(false)`); then no auth
/// entry is needed and the tx source just pays the fee. Sign with
/// `finalize_and_sign` after simulating, exactly like `transact`.
pub fn build_unsigned_asp_register(
    asp: &str,
    source_g: &str,
    account_entry_xdr: &str,
    leaf_be: &[u8; 32],
) -> Result<String> {
    let next_seq = seq_from_account_entry(account_entry_xdr)?
        .checked_add(1)
        .ok_or_else(|| anyhow!("account sequence overflow"))?;
    let raw = build_invoke_contract_tx_envelope(
        source_g,
        SequenceNumber(next_seq),
        BASE_FEE,
        asp,
        "insert_leaf",
        vec![u256_scval_from_be(leaf_be)],
    )?;
    Ok(raw.to_xdr_base64(Limits::none())?)
}

/// Build + sign a **classic native-XLM payment** (Daylight / public mode).
///
/// Classic payments need no Soroban simulation, data, or auth — so this builds
/// the `Payment` op, signs with the wallet key, and returns the signed base64
/// envelope in one shot. Submit it via **Horizon** `POST /transactions`
/// (Soroban RPC's `sendTransaction` rejects non-Soroban txs), not the RPC path
/// used by `transact`. `memo_text` empty = no memo. `account_entry_xdr` is the
/// base64 `LedgerEntryData::Account` from getLedgerEntries (for the seq number).
pub fn build_signed_payment(
    source_g: &str,
    account_entry_xdr: &str,
    dest_g: &str,
    amount_stroops: i64,
    memo_text: &str,
    secret: &[u8; 32],
) -> Result<String> {
    if amount_stroops <= 0 {
        return Err(anyhow!("payment amount must be positive"));
    }
    let next_seq = seq_from_account_entry(account_entry_xdr)?
        .checked_add(1)
        .ok_or_else(|| anyhow!("account sequence overflow"))?;
    let memo = if memo_text.is_empty() {
        Memo::None
    } else {
        Memo::Text(memo_text.as_bytes().to_vec().try_into().map_err(|_| anyhow!("memo > 28 bytes"))?)
    };
    let op = Operation {
        source_account: None,
        body: OperationBody::Payment(PaymentOp {
            destination: muxed_account_from_g(dest_g)?,
            asset: Asset::Native,
            amount: amount_stroops,
        }),
    };
    let tx = Transaction {
        source_account: muxed_account_from_g(source_g)?,
        fee: BASE_FEE,
        seq_num: SequenceNumber(next_seq),
        cond: Preconditions::None,
        memo,
        operations: VecM::try_from(vec![op])?,
        ext: TransactionExt::V0,
    };
    let env = TransactionEnvelope::Tx(TransactionV1Envelope { tx, signatures: VecM::default() });
    let signed = sign_envelope(env, secret)?;
    Ok(signed.to_xdr_base64(Limits::none())?)
}

/// Merges simulation resource fee + soroban data + auth into the unsigned tx,
/// then signs it with the wallet's Ed25519 key. Returns the signed envelope as
/// base64 XDR (ready for sendTransaction).
///
/// `sim_response_json` is the raw JSON-RPC response from `simulateTransaction`
/// (we read `.result`: `transactionData`, `minResourceFee`, `results[].auth`).
pub fn finalize_and_sign(
    unsigned_xdr: &str,
    sim_response_json: &str,
    secret: &[u8; 32],
) -> Result<String> {
    let raw = TransactionEnvelope::from_xdr_base64(unsigned_xdr, Limits::none())?;
    let assembled = assemble_from_sim(&raw, sim_response_json)?;
    let signed = sign_envelope(assembled, secret)?;
    Ok(signed.to_xdr_base64(Limits::none())?)
}

/// Applies a `simulateTransaction` result to the unsigned envelope: bumps the
/// fee by the resource fee, sets the Soroban transaction data, and embeds the
/// simulated auth entries. Mirrors `assembleTransaction` from the JS SDK.
fn assemble_from_sim(
    raw: &TransactionEnvelope,
    sim_response_json: &str,
) -> Result<TransactionEnvelope> {
    let resp: serde_json::Value = serde_json::from_str(sim_response_json)?;
    // Accept either a full JSON-RPC envelope ({result:{...}}) or the result obj.
    let result = resp.get("result").unwrap_or(&resp);

    if let Some(err) = result.get("error").and_then(|e| e.as_str()) {
        return Err(anyhow!("transaction simulation failed: {err}"));
    }

    let min_resource_fee: u64 = result
        .get("minResourceFee")
        .and_then(|v| v.as_str())
        .map(|s| s.parse::<u64>())
        .transpose()
        .map_err(|_| anyhow!("invalid minResourceFee"))?
        .unwrap_or(0);

    let tx_data_b64 = result
        .get("transactionData")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow!("simulateTransaction missing transactionData"))?;
    let soroban_data = SorobanTransactionData::from_xdr_base64(tx_data_b64, Limits::none())
        .map_err(|e| anyhow!("invalid transactionData xdr: {e}"))?;

    // Auth entries: from `result.results[0].auth` (or the legacy `result.result`).
    let first = result
        .get("results")
        .and_then(|r| r.as_array())
        .and_then(|a| a.first())
        .or_else(|| result.get("result"));
    let auth_entries: Vec<SorobanAuthorizationEntry> = first
        .and_then(|r| r.get("auth"))
        .and_then(|a| a.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str())
                .map(|b64| {
                    SorobanAuthorizationEntry::from_xdr_base64(b64, Limits::none())
                        .map_err(|e| anyhow!("invalid auth entry xdr: {e}"))
                })
                .collect::<Result<Vec<_>>>()
        })
        .transpose()?
        .unwrap_or_default();

    let TransactionEnvelope::Tx(v1) = raw else {
        return Err(anyhow!("expected TransactionEnvelope::Tx"));
    };
    let mut tx = v1.tx.clone();
    if tx.operations.len() != 1 {
        return Err(anyhow!("expected exactly one operation, got {}", tx.operations.len()));
    }

    let resource_fee: u32 =
        min_resource_fee.try_into().map_err(|_| anyhow!("minResourceFee does not fit into u32"))?;
    let mut classic_fee = u64::from(tx.fee);
    if let TransactionExt::V1(existing) = &tx.ext {
        let existing_resource = u64::try_from(existing.resource_fee).unwrap_or(0);
        classic_fee = classic_fee.saturating_sub(existing_resource);
    }
    tx.fee = classic_fee
        .saturating_add(u64::from(resource_fee))
        .try_into()
        .map_err(|_| anyhow!("total fee does not fit into u32"))?;
    tx.ext = TransactionExt::V1(soroban_data);

    let op = tx.operations[0].clone();
    let OperationBody::InvokeHostFunction(mut invoke) = op.body else {
        return Err(anyhow!("expected invokeHostFunction operation"));
    };
    if !invoke.auth.is_empty() {
        return Err(anyhow!("invoke op already has auth entries before assembly"));
    }
    invoke.auth = VecM::try_from(auth_entries)?;
    tx.operations = VecM::try_from(vec![Operation {
        source_account: op.source_account,
        body: OperationBody::InvokeHostFunction(invoke),
    }])?;

    Ok(TransactionEnvelope::Tx(TransactionV1Envelope { tx, signatures: v1.signatures.clone() }))
}

/// Sign an assembled tx envelope with the wallet's Ed25519 key.
fn sign_envelope(env: TransactionEnvelope, secret: &[u8; 32]) -> Result<TransactionEnvelope> {
    let TransactionEnvelope::Tx(e) = env else {
        return Err(anyhow!("expected v1 transaction envelope"));
    };
    let tx = e.tx;
    let network_id: [u8; 32] = Sha256::digest(TESTNET_PASSPHRASE.as_bytes()).into();
    let payload = TransactionSignaturePayload {
        network_id: Hash(network_id),
        tagged_transaction: Tagged::Tx(tx.clone()),
    };
    let tx_hash: [u8; 32] = Sha256::digest(payload.to_xdr(Limits::none())?).into();

    let sk = SigningKey::from_bytes(secret);
    let sig = sk.sign(&tx_hash);
    let pubkey = sk.verifying_key().to_bytes();
    let decorated = DecoratedSignature {
        hint: SignatureHint([pubkey[28], pubkey[29], pubkey[30], pubkey[31]]),
        signature: Signature(sig.to_bytes().to_vec().try_into().map_err(|_| anyhow!("sig len"))?),
    };
    Ok(TransactionEnvelope::Tx(TransactionV1Envelope {
        tx,
        signatures: vec![decorated].try_into().map_err(|_| anyhow!("sigs"))?,
    }))
}

/// Generate a fresh Stellar Ed25519 account. Returns (32-byte secret seed, G-address).
pub fn generate_account() -> ([u8; 32], String) {
    use rand::RngCore;
    let mut seed = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut seed);
    let sk = SigningKey::from_bytes(&seed);
    let address =
        stellar_strkey::ed25519::PublicKey(sk.verifying_key().to_bytes()).to_string().to_string();
    (seed, address)
}
