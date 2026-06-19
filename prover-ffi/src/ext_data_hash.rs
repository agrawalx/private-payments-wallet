//! Vendored from `stellar/src/ext_data_hash.rs` (+ the `i128_to_i256_scval`
//! helper from `stellar/src/conversions.rs`).
//!
//! Copied rather than depended-on because the `stellar` crate pulls `reqwest`
//! (HTTP client + tokio), which we don't want in the mobile prover `.so`. This
//! is a small, stable, pure function. Keep it in sync with the on-chain
//! `hash_ext_data` in `contracts/pool/src/pool.rs`.

use anyhow::Result;
use core::ops::Rem;
use sha3::{Digest, Keccak256};
use stellar_xdr::curr::{
    Int256Parts, Limits, ScAddress, ScMap, ScMapEntry, ScSymbol, ScVal, WriteXdr,
};
use types::{ExtData, BN254_MODULUS_BE, U256};

/// Encode an `i128` as a Soroban `ScVal::I256`.
fn i128_to_i256_scval(n: i128) -> ScVal {
    let hi = if n < 0 { -1i64 } else { 0i64 };
    let hi_lo = u64::from_be_bytes(hi.to_be_bytes());
    let bytes = n.to_be_bytes();
    let lo_hi = u64::from_be_bytes(bytes[0..8].try_into().expect("i128 lo_hi slice"));
    let lo_lo = u64::from_be_bytes(bytes[8..16].try_into().expect("i128 lo_lo slice"));
    ScVal::I256(Int256Parts {
        hi_hi: hi,
        hi_lo,
        lo_hi,
        lo_lo,
    })
}

/// Compute `extDataHash` off-chain, matching the on-chain `hash_ext_data`:
/// XDR-serialize the Soroban `ExtData` map (keys sorted alphabetically),
/// Keccak256, then reduce modulo the BN254 scalar field. Returns 32-byte BE.
pub fn hash_ext_data_offchain(ext: &ExtData) -> Result<[u8; 32]> {
    let mut entries: Vec<(&str, ScVal)> = vec![
        (
            "encrypted_output0",
            ScVal::Bytes(ext.encrypted_output0.clone().try_into()?),
        ),
        (
            "encrypted_output1",
            ScVal::Bytes(ext.encrypted_output1.clone().try_into()?),
        ),
        ("ext_amount", i128_to_i256_scval(ext.ext_amount.into())),
        (
            "recipient",
            ScVal::Address(ext.recipient.parse::<ScAddress>()?),
        ),
    ];

    // Soroban structs serialize to XDR maps sorted alphabetically by key.
    entries.sort_by(|a, b| a.0.cmp(b.0));

    let mut map_entries: Vec<ScMapEntry> = Vec::with_capacity(entries.len());
    for (k, v) in entries {
        let sym: stellar_xdr::curr::StringM<32> = k.try_into()?;
        map_entries.push(ScMapEntry {
            key: ScVal::Symbol(ScSymbol(sym)),
            val: v,
        });
    }
    let sc_map = ScMap(map_entries.try_into()?);
    let sc_val = ScVal::Map(Some(sc_map));

    let payload = sc_val.to_xdr(Limits::none())?;

    let mut hasher = Keccak256::new();
    hasher.update(&payload);
    let digest = hasher.finalize();

    let mut digest_be = [0u8; 32];
    digest_be.copy_from_slice(digest.as_slice());
    let digest_u256 = U256::from_big_endian(&digest_be);
    let modulus = U256::from_big_endian(&BN254_MODULUS_BE);
    let reduced = Rem::rem(digest_u256, modulus);

    Ok(reduced.to_big_endian())
}
