//! Builds DepositParams for a REAL on-chain deposit to our pool, against the
//! actual on-chain ASP/pool roots. Writes `prover-ffi/fixtures/deposit_onchain.json`.
//!
//! Preconditions (done on-chain first):
//!   - our membership leaf H(pubkey, 0, 1) inserted into the ASP membership tree
//!     (so asp_membership_root = WITH_LEAF root),
//!   - ASP non-membership tree empty (root 0), pool tree empty (root = zero-tree).
//!
//! The output note is encrypted to the APP's X25519 key (derived from the same
//! dummy 64-byte signature the app uses), so the wallet can scan + decrypt it.

use anyhow::Result;
use circuits::test::utils::{
    general::scalar_to_bigint,
    merkle_tree::{merkle_proof, merkle_root},
    sparse_merkle_tree::prepare_smt_proof_with_overrides,
};
use num_bigint::{BigInt, BigUint};
use prover::{
    crypto::{asp_membership_leaf, derive_public_key, zero_leaf},
    encryption::derive_encryption_and_note_keypairs,
    flows::{DepositParams, TransactOutput},
};
use types::{
    AspMembershipProof, AspNonMembershipProof, EncryptionPublicKey, ExtAmount, Field,
    KeyDerivationSignature, NoteAmount, NotePrivateKey, NotePublicKey,
};
use zkhash::{
    ark_ff::{BigInteger, PrimeField},
    fields::bn256::FpBN256 as Scalar,
};

const LEVELS: usize = 10;
const POOL: &str = "CCDFQ5D32OZVSK5BMNZMWZSY4U6VVJBHW4MEHEUCZOURZIP3C7UUJW4V";
const DEPOSIT_STROOPS: i128 = 1_000_000; // 0.1 XLM

fn field_from_scalar(s: Scalar) -> Field {
    let mut b = s.into_bigint().to_bytes_le();
    b.resize(32, 0);
    Field::try_from_le_bytes(b.try_into().unwrap()).unwrap()
}
fn scalar_from_field(f: &Field) -> Scalar {
    Scalar::from_le_bytes_mod_order(&f.to_le_bytes())
}
fn scalar_from_be(b: &[u8]) -> Scalar {
    Scalar::from(BigUint::from_bytes_be(b))
}
fn field_from_bigint(b: &BigInt) -> Field {
    let (_, mut bytes) = b.to_bytes_le();
    bytes.resize(32, 0);
    Field::try_from_le_bytes(bytes.try_into().unwrap()).unwrap()
}

fn main() -> Result<()> {
    // Deposit note key (its membership leaf is already inserted on-chain).
    let priv_field = field_from_scalar(Scalar::from(102u64));
    let priv_bytes: [u8; 32] = priv_field.to_le_bytes();
    let note_priv = NotePrivateKey(priv_bytes);
    let pubkey: [u8; 32] = derive_public_key(&priv_bytes)?.try_into().unwrap();
    let note_pub = NotePublicKey(pubkey);

    // App's X25519 encryption key (same dummy 64-byte signature the app uses)
    // so the wallet can decrypt the deposited output note.
    let sig = KeyDerivationSignature((0..64u8).collect());
    let (_note_kp, enc_kp) = derive_encryption_and_note_keypairs(sig)?;
    let enc_pub = EncryptionPublicKey(enc_kp.public.0);

    // Membership proof: path to our leaf at index 0 of the (otherwise empty) tree.
    let blinding = Field::try_from_le_bytes([0u8; 32])?;
    let leaf = asp_membership_leaf(&note_pub, &blinding)?;
    let zl = scalar_from_be(&zero_leaf());
    let mut leaves = vec![zl; 1usize << LEVELS];
    leaves[0] = scalar_from_field(&leaf);
    let mem_root = merkle_root(leaves.clone());
    let (siblings, path_idx, depth) = merkle_proof(&leaves, 0);
    assert_eq!(depth, LEVELS);
    let membership = AspMembershipProof {
        leaf,
        blinding,
        path_elements: siblings.iter().map(|s| field_from_scalar(*s)).collect(),
        path_indices: field_from_scalar(Scalar::from(path_idx)),
        root: field_from_scalar(mem_root),
    };

    // Non-membership: empty SMT (root 0) — our key is absent.
    let key_bigint = scalar_to_bigint(scalar_from_field(&Field::try_from_le_bytes(pubkey)?));
    let nm = prepare_smt_proof_with_overrides(&key_bigint, &[], LEVELS);
    let non_membership = AspNonMembershipProof {
        key: field_from_bigint(&key_bigint),
        old_key: if nm.is_old0 { Field::try_from_le_bytes([0u8; 32])? } else { field_from_bigint(&nm.not_found_key) },
        old_value: if nm.is_old0 { Field::try_from_le_bytes([0u8; 32])? } else { field_from_bigint(&nm.not_found_value) },
        is_old0: nm.is_old0,
        siblings: nm.siblings.iter().map(field_from_bigint).collect(),
        root: field_from_bigint(&nm.root),
    };

    // Pool root = empty zero-tree root (the pool is fresh; is_known_root passes).
    let pool_root = field_from_scalar(merkle_root(vec![zl; 1usize << LEVELS]));

    let params = DepositParams {
        priv_key: note_priv,
        encryption_pubkey: enc_pub,
        pool_root,
        pool_address: POOL.to_string(),
        amount: ExtAmount::from(DEPOSIT_STROOPS),
        outputs: vec![TransactOutput {
            amount: NoteAmount::from(DEPOSIT_STROOPS as u128),
            blinding: Field::try_from_le_bytes([5u8; 32])?,
            recipient_note_pubkey: None,        // self-output, encrypted to enc_pub
            recipient_encryption_pubkey: None,
        }],
        membership_proof: membership,
        non_membership_proof: non_membership,
        tree_depth: LEVELS as u32,
        smt_depth: LEVELS as u32,
    };

    let out = concat!(env!("CARGO_MANIFEST_DIR"), "/../prover-ffi/fixtures/deposit_onchain.json");
    std::fs::write(out, serde_json::to_string_pretty(&params)?)?;
    println!("mem_root      = {}", scalar_to_bigint(mem_root));
    println!("nonmem_root   = {}", nm.root);
    println!("pool_root     = {}", scalar_to_bigint(merkle_root(vec![zl; 1usize << LEVELS])));
    println!("wrote {out}");
    Ok(())
}
