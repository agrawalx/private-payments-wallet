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
const POOL: &str = "CAYDRYKMO23GEBDSUP5QUM3G4CMOS7YX3TICYAES2N2IAEI3GA22EBMS";
const DEPOSIT_STROOPS: i128 = 1_000_000; // 0.1 XLM

// Live ASP membership tree leaves at CDGHHS4R45TKIUHYUZPNTYYND5R4KEO7J6VOOH4AYXJHRGEBYFXX27UK,
// on-chain leaf order (indices 0,1,2), snapshotted from the hosted indexer
// (`curl http://52.66.141.112/events?cursor=0&limit=300`, decoded via
// `decode_asp_leaf`) on 2026-07-03. Index 2 is our scalar-102 test leaf.
const ASP_LEAVES: [&str; 3] = [
    "9994517823975672122284466930373335370852567112864722324125744549747666024239",
    "2265510885976041928383517713406139983991156639540722924781593281193278186508",
    "17969525783030368157502924498519760117548348265060813172074119923679683982433",
];

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
fn scalar_from_dec(s: &str) -> Scalar {
    Scalar::from(s.parse::<BigUint>().unwrap())
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
    let (_note_kp, enc_kp, _nk) = derive_encryption_and_note_keypairs(sig)?;
    let enc_pub = EncryptionPublicKey(enc_kp.public.0);

    // Membership proof: path to our leaf, now at index 2 of the live tree
    // (indices 0,1 are other on-chain LeafAdded events; see ASP_LEAVES above).
    let blinding = Field::try_from_le_bytes([0u8; 32])?;
    let leaf = asp_membership_leaf(&note_pub, &blinding)?;
    assert_eq!(scalar_to_bigint(scalar_from_field(&leaf)).to_string(), ASP_LEAVES[2], "scalar-102 leaf != live index 2");
    let zl = scalar_from_be(&zero_leaf());
    let mut leaves = vec![zl; 1usize << LEVELS];
    leaves[0] = scalar_from_dec(ASP_LEAVES[0]);
    leaves[1] = scalar_from_dec(ASP_LEAVES[1]);
    leaves[2] = scalar_from_field(&leaf);
    let mem_root = merkle_root(leaves.clone());
    assert_eq!(
        scalar_to_bigint(mem_root).to_string(),
        "7650851037732131397668132823701172848267506844599464976150678500985807912790",
        "recomputed mem_root != live on-chain ASP root"
    );
    let (siblings, path_idx, depth) = merkle_proof(&leaves, 2);
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
