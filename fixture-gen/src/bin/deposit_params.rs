//! Builds a valid `DepositParams` JSON for the deposit → prove flow and writes
//! it to `prover-ffi/fixtures/deposit_params.json`.
//!
//! Consistency strategy: pubkey + ASP membership leaf are computed with the
//! **prover's own crypto** (the exact functions `transact()` uses), so the
//! ungated circuit constraint `membership.leaf === H(pubkey, blinding, 1)`
//! holds. The merkle path + SMT non-inclusion proof use `circuits::test::utils`
//! (same Poseidon2 the circuit uses). ASP/Merkle data is synthetic test data —
//! a real wallet sources it from the pool + ASP trees (state/indexer, deferred).

use anyhow::Result;
use circuits::test::utils::{
    general::scalar_to_bigint,
    merkle_tree::{merkle_proof, merkle_root},
    sparse_merkle_tree::prepare_smt_proof_with_overrides,
    transaction::prepopulated_leaves,
};
use num_bigint::BigInt;
use prover::{
    crypto::{asp_membership_leaf, derive_public_key},
    flows::{DepositParams, TransactOutput},
};
use types::{
    AspMembershipProof, AspNonMembershipProof, EncryptionPublicKey, ExtAmount, Field, NoteAmount,
    NotePrivateKey, NotePublicKey,
};
use zkhash::{
    ark_ff::{BigInteger, PrimeField},
    fields::bn256::FpBN256 as Scalar,
};

const LEVELS: usize = 10;
// Deployed testnet pool contract — a valid Stellar address for ext-data hashing.
const POOL: &str = "CDQRXOD6VHFR5W34HMDLQNROGXA64DPI6BCU6M5JVA2GARDVHAMS2PZF";

fn field_from_scalar(s: Scalar) -> Field {
    let mut b = s.into_bigint().to_bytes_le();
    b.resize(32, 0);
    Field::try_from_le_bytes(b.try_into().unwrap()).unwrap()
}
fn scalar_from_field(f: &Field) -> Scalar {
    Scalar::from_le_bytes_mod_order(&f.to_le_bytes())
}
fn field_from_bigint(b: &BigInt) -> Field {
    let (_, mut bytes) = b.to_bytes_le();
    bytes.resize(32, 0);
    Field::try_from_le_bytes(bytes.try_into().unwrap()).unwrap()
}

fn main() -> Result<()> {
    // 1. Spend key + its pubkey (prover crypto — what transact() uses).
    let priv_field = field_from_scalar(Scalar::from(102u64));
    let priv_bytes: [u8; 32] = priv_field.to_le_bytes();
    let note_priv = NotePrivateKey(priv_bytes);
    let pubkey_bytes: [u8; 32] = derive_public_key(&priv_bytes)?
        .try_into()
        .map_err(|_| anyhow::anyhow!("pubkey not 32 bytes"))?;
    let note_pub = NotePublicKey(pubkey_bytes);

    // 2. ASP membership: leaf = H(pubkey, blinding=0, dom=1); valid merkle path.
    let blinding = Field::try_from_le_bytes([0u8; 32])?;
    let leaf_field = asp_membership_leaf(&note_pub, &blinding)?;
    let leaf_scalar = scalar_from_field(&leaf_field);
    let idx = 7usize;
    let mut leaves = prepopulated_leaves(LEVELS, 0xDEAD_BEEFu64, &[idx], 24);
    leaves[idx] = leaf_scalar;
    let root_scalar = merkle_root(leaves.clone());
    let (siblings, path_idx_u64, depth) = merkle_proof(&leaves, idx);
    assert_eq!(depth, LEVELS);
    let membership = AspMembershipProof {
        leaf: leaf_field,
        blinding,
        path_elements: siblings.iter().map(|s| field_from_scalar(*s)).collect(),
        path_indices: field_from_scalar(Scalar::from(path_idx_u64)),
        root: field_from_scalar(root_scalar),
    };

    // 3. ASP non-membership: SMT proof that key=pubkey is absent.
    let pubkey_scalar = scalar_from_field(&Field::try_from_le_bytes(pubkey_bytes)?);
    let key_bigint = scalar_to_bigint(pubkey_scalar);
    let overrides: Vec<(BigInt, BigInt)> =
        vec![(scalar_to_bigint(Scalar::from(100_001u64)), scalar_to_bigint(leaf_scalar))];
    let nm = prepare_smt_proof_with_overrides(&key_bigint, &overrides, LEVELS);
    let non_membership = AspNonMembershipProof {
        key: field_from_bigint(&key_bigint),
        old_key: if nm.is_old0 { Field::try_from_le_bytes([0u8; 32])? } else { field_from_bigint(&nm.not_found_key) },
        old_value: if nm.is_old0 { Field::try_from_le_bytes([0u8; 32])? } else { field_from_bigint(&nm.not_found_value) },
        is_old0: nm.is_old0,
        siblings: nm.siblings.iter().map(field_from_bigint).collect(),
        root: field_from_bigint(&nm.root),
    };

    // 4. Assemble DepositParams: deposit 120, single self-output of 120.
    let params = DepositParams {
        priv_key: note_priv,
        encryption_pubkey: EncryptionPublicKey([7u8; 32]),
        pool_root: Field::try_from_le_bytes([0u8; 32])?, // gated off for all-dummy inputs
        pool_address: POOL.to_string(),
        amount: ExtAmount::from(120i128),
        outputs: vec![TransactOutput {
            amount: NoteAmount::from(120u128),
            blinding: Field::try_from_le_bytes([5u8; 32])?,
            recipient_note_pubkey: None,
            recipient_encryption_pubkey: None,
        }],
        membership_proof: membership,
        non_membership_proof: non_membership,
        tree_depth: LEVELS as u32,
        smt_depth: LEVELS as u32,
    };

    let json = serde_json::to_string_pretty(&params)?;
    let out = concat!(env!("CARGO_MANIFEST_DIR"), "/../prover-ffi/fixtures/deposit_params.json");
    std::fs::write(out, json)?;
    println!("wrote {out}");
    Ok(())
}
