//! Builds TransferParams to spend our note at pool index 4 and create a new
//! private note (self-transfer for the demo). Writes
//! `prover-ffi/fixtures/transfer_onchain.json`.

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
    flows::{TransactInputNote, TransactOutput, TransferParams},
};
use types::{
    AspMembershipProof, AspNonMembershipProof, EncryptionPublicKey, Field, KeyDerivationSignature,
    NoteAmount, NotePrivateKey, NotePublicKey,
};
use zkhash::{
    ark_ff::{BigInteger, PrimeField},
    fields::bn256::FpBN256 as Scalar,
};

const LEVELS: usize = 10;
const AMOUNT: u128 = 1_000_000;
const POOL: &str = "CCDFQ5D32OZVSK5BMNZMWZSY4U6VVJBHW4MEHEUCZOURZIP3C7UUJW4V";
const INPUT_INDEX: usize = 4;
const COMMITMENTS: [&str; 6] = [
    "18263061230805500442272313235592384331733893249443814861628842931597516851047",
    "12401823200749646421033992859594403456934209749448747537236696806596108078728",
    "6796513271408424129577024885242728643421429114817081996334829941571343536364",
    "5896081705399637978688225992365103423149087432975718381179144965475173518498",
    "18263061230805500442272313235592384331733893249443814861628842931597516851047",
    "24219891374038460565863015543608887805267297385220640534647810395212564315",
];
const POOL_ROOT: &str = "14869693338443042301083661026342888870895851933169147054491405273805110932733";

fn ff(s: Scalar) -> Field {
    let mut b = s.into_bigint().to_bytes_le();
    b.resize(32, 0);
    Field::try_from_le_bytes(b.try_into().unwrap()).unwrap()
}
fn sf(f: &Field) -> Scalar {
    Scalar::from_le_bytes_mod_order(&f.to_le_bytes())
}
fn sbe(b: &[u8]) -> Scalar {
    Scalar::from(BigUint::from_bytes_be(b))
}
fn fbi(b: &BigInt) -> Field {
    let (_, mut x) = b.to_bytes_le();
    x.resize(32, 0);
    Field::try_from_le_bytes(x.try_into().unwrap()).unwrap()
}
fn sdec(s: &str) -> Scalar {
    Scalar::from(s.parse::<BigUint>().unwrap())
}

fn main() -> Result<()> {
    let priv_field = ff(Scalar::from(102u64));
    let priv_bytes: [u8; 32] = priv_field.to_le_bytes();
    let note_priv = NotePrivateKey(priv_bytes);
    let pubkey: [u8; 32] = derive_public_key(&priv_bytes)?.try_into().unwrap();
    let note_pub = NotePublicKey(pubkey);

    let sig = KeyDerivationSignature((0..64u8).collect());
    let (_n, enc_kp, _nk) = derive_encryption_and_note_keypairs(sig)?;
    let enc_pub = EncryptionPublicKey(enc_kp.public.0);

    // Pool tree from all 6 commitments; path to our note at index 4.
    let zl = sbe(&zero_leaf());
    let mut leaves = vec![zl; 1usize << LEVELS];
    for (i, c) in COMMITMENTS.iter().enumerate() {
        leaves[i] = sdec(c);
    }
    let pool_root = merkle_root(leaves.clone());
    assert_eq!(scalar_to_bigint(pool_root).to_string(), POOL_ROOT, "pool root mismatch");
    let (siblings, path_idx, _d) = merkle_proof(&leaves, INPUT_INDEX);

    let input = TransactInputNote {
        amount: NoteAmount::from(AMOUNT),
        blinding: Field::try_from_le_bytes([5u8; 32])?, // deposit output0 blinding
        merkle_path_elements: siblings.iter().map(|s| ff(*s)).collect(),
        merkle_path_indices: ff(Scalar::from(path_idx)),
    };

    // Output: new private note of the same amount, addressed to ourselves
    // (so the wallet can re-scan it). Second output is empty change.
    let outputs = vec![
        TransactOutput {
            amount: NoteAmount::from(AMOUNT),
            blinding: Field::try_from_le_bytes([9u8; 32])?,
            recipient_note_pubkey: Some(note_pub.clone()),
            recipient_encryption_pubkey: Some(enc_pub.clone()),
        },
        TransactOutput {
            amount: NoteAmount::ZERO,
            blinding: Field::try_from_le_bytes([8u8; 32])?,
            recipient_note_pubkey: None,
            recipient_encryption_pubkey: None,
        },
    ];

    // ASP membership/non-membership (same key).
    let blinding0 = Field::try_from_le_bytes([0u8; 32])?;
    let leaf = asp_membership_leaf(&note_pub, &blinding0)?;
    let mut ml = vec![zl; 1usize << LEVELS];
    ml[0] = sf(&leaf);
    let mem_root = merkle_root(ml.clone());
    let (msib, mpi, _) = merkle_proof(&ml, 0);
    let membership = AspMembershipProof {
        leaf,
        blinding: blinding0,
        path_elements: msib.iter().map(|s| ff(*s)).collect(),
        path_indices: ff(Scalar::from(mpi)),
        root: ff(mem_root),
    };
    let key_bigint = scalar_to_bigint(sf(&Field::try_from_le_bytes(pubkey)?));
    let nm = prepare_smt_proof_with_overrides(&key_bigint, &[], LEVELS);
    let non_membership = AspNonMembershipProof {
        key: fbi(&key_bigint),
        old_key: Field::try_from_le_bytes([0u8; 32])?,
        old_value: Field::try_from_le_bytes([0u8; 32])?,
        is_old0: nm.is_old0,
        siblings: nm.siblings.iter().map(fbi).collect(),
        root: fbi(&nm.root),
    };

    let params = TransferParams {
        priv_key: note_priv,
        encryption_pubkey: enc_pub,
        pool_root: ff(pool_root),
        pool_address: POOL.to_string(),
        inputs: vec![input],
        outputs,
        membership_proof: membership,
        non_membership_proof: non_membership,
        tree_depth: LEVELS as u32,
        smt_depth: LEVELS as u32,
    };

    let out = concat!(env!("CARGO_MANIFEST_DIR"), "/../prover-ffi/fixtures/transfer_onchain.json");
    std::fs::write(out, serde_json::to_string_pretty(&params)?)?;
    println!("pool_root ok = {}", scalar_to_bigint(pool_root));
    println!("wrote {out}");
    Ok(())
}
