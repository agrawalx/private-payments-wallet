//! Builds WithdrawParams to spend our on-chain deposit note (0.1 XLM at pool
//! index 0) and withdraw it to a public address. Writes
//! `prover-ffi/fixtures/withdraw_onchain.json`.
//!
//! Reconstructs the pool merkle tree from the two commitments our deposit
//! emitted, builds the input note's inclusion path, and proves against the
//! real on-chain pool/ASP roots.

use anyhow::Result;
use circuits::test::utils::{
    general::scalar_to_bigint,
    merkle_tree::{merkle_proof, merkle_root},
    sparse_merkle_tree::prepare_smt_proof_with_overrides,
};
use num_bigint::{BigInt, BigUint};
use prover::{
    crypto::{asp_membership_leaf, compute_commitment, derive_public_key, zero_leaf},
    encryption::derive_encryption_and_note_keypairs,
    flows::{TransactInputNote, WithdrawParams},
};
use types::{
    AspMembershipProof, AspNonMembershipProof, EncryptionPublicKey, ExtAmount, Field, NoteAmount,
    KeyDerivationSignature, NotePrivateKey, NotePublicKey,
};
use zkhash::{
    ark_ff::{BigInteger, PrimeField},
    fields::bn256::FpBN256 as Scalar,
};

const LEVELS: usize = 10;
const AMOUNT: i128 = 1_000_000; // the 0.1 XLM note we deposited
const RECIPIENT: &str = "GDFQA474KPZWWE4YAX6EAJVZHYO3SJR5DCQXV3U6GFDVMNNFZZ7UD2SD"; // gate3
// Commitments our deposit (tx 10a9a9475a0c9e72b7be655f873220563905f0441b0c2a2ed53e7f42c47f659e,
// into the new pool CAYDRYKMO23GEBDSUP5QUM3G4CMOS7YX3TICYAES2N2IAEI3GA22EBMS) emitted
// (NewCommitmentEvent index 0 and 1), decoded via `decode_nullifier_topic` on 2026-07-03.
const COMM0: &str = "18263061230805500442272313235592384331733893249443814861628842931597516851047";
const COMM1: &str = "21158080344800240816300532316086569362445313206198807210561088406377156083599";
const POOL_ROOT: &str = "4051697079123744283342357277046060981093496486031259126841379961128596812563";

// Live ASP membership tree leaves (see onchain_deposit_params.rs for the fetch
// details/date); our scalar-102 leaf is at index 2.
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
    let priv_field = field_from_scalar(Scalar::from(102u64));
    let priv_bytes: [u8; 32] = priv_field.to_le_bytes();
    let note_priv = NotePrivateKey(priv_bytes);
    let pubkey: [u8; 32] = derive_public_key(&priv_bytes)?.try_into().unwrap();
    let note_pub = NotePublicKey(pubkey);

    let sig = KeyDerivationSignature((0..64u8).collect());
    let (_n, enc_kp, _nk) = derive_encryption_and_note_keypairs(sig)?;
    let enc_pub = EncryptionPublicKey(enc_kp.public.0);

    // Input note: amount 0.1 XLM, blinding [5;32] (the deposit output0 blinding).
    let note_blinding = Field::try_from_le_bytes([5u8; 32])?;
    // Sanity: our recomputed commitment must equal the on-chain comm0.
    let amount_field = Field::from(NoteAmount::from(AMOUNT as u128));
    let comm = compute_commitment(&amount_field.to_le_bytes(), &pubkey, &note_blinding.to_le_bytes())?;
    let comm_scalar = scalar_from_be(&comm); // compute_commitment returns BE? compare both ways below
    let comm0 = scalar_from_dec(COMM0);
    assert!(
        comm_scalar == comm0 || scalar_from_field(&Field::try_from_le_bytes(comm.clone().try_into().unwrap())?) == comm0,
        "recomputed commitment != on-chain comm0"
    );

    // Pool tree = [comm0, comm1, ZERO_LEAF...]; build path to our note at index 0.
    let zl = scalar_from_be(&zero_leaf());
    let mut leaves = vec![zl; 1usize << LEVELS];
    leaves[0] = comm0;
    leaves[1] = scalar_from_dec(COMM1);
    let pool_root = merkle_root(leaves.clone());
    assert_eq!(scalar_to_bigint(pool_root).to_string(), POOL_ROOT, "pool root mismatch");
    let (siblings, path_idx, _d) = merkle_proof(&leaves, 0);

    let input = TransactInputNote {
        amount: NoteAmount::from(AMOUNT as u128),
        blinding: note_blinding,
        merkle_path_elements: siblings.iter().map(|s| field_from_scalar(*s)).collect(),
        merkle_path_indices: field_from_scalar(Scalar::from(path_idx)),
    };

    // ASP membership: our leaf, now at index 2 of the live tree (indices 0,1
    // are other on-chain LeafAdded events; see ASP_LEAVES above) + empty-SMT
    // non-membership.
    let blinding0 = Field::try_from_le_bytes([0u8; 32])?;
    let leaf = asp_membership_leaf(&note_pub, &blinding0)?;
    let mut mem_leaves = vec![zl; 1usize << LEVELS];
    mem_leaves[0] = scalar_from_dec(ASP_LEAVES[0]);
    mem_leaves[1] = scalar_from_dec(ASP_LEAVES[1]);
    mem_leaves[2] = scalar_from_field(&leaf);
    let mem_root = merkle_root(mem_leaves.clone());
    assert_eq!(
        scalar_to_bigint(mem_root).to_string(),
        "7650851037732131397668132823701172848267506844599464976150678500985807912790",
        "recomputed mem_root != live on-chain ASP root"
    );
    let (msib, mpi, _) = merkle_proof(&mem_leaves, 2);
    let membership = AspMembershipProof {
        leaf,
        blinding: blinding0,
        path_elements: msib.iter().map(|s| field_from_scalar(*s)).collect(),
        path_indices: field_from_scalar(Scalar::from(mpi)),
        root: field_from_scalar(mem_root),
    };
    let key_bigint = scalar_to_bigint(scalar_from_field(&Field::try_from_le_bytes(pubkey)?));
    let nm = prepare_smt_proof_with_overrides(&key_bigint, &[], LEVELS);
    let non_membership = AspNonMembershipProof {
        key: field_from_bigint(&key_bigint),
        old_key: Field::try_from_le_bytes([0u8; 32])?,
        old_value: Field::try_from_le_bytes([0u8; 32])?,
        is_old0: nm.is_old0,
        siblings: nm.siblings.iter().map(field_from_bigint).collect(),
        root: field_from_bigint(&nm.root),
    };

    let params = WithdrawParams {
        priv_key: note_priv,
        encryption_pubkey: enc_pub,
        pool_root: field_from_scalar(pool_root),
        withdraw_recipient: RECIPIENT.to_string(),
        withdraw_amount: ExtAmount::from(AMOUNT),
        inputs: vec![input],
        outputs: None, // change = 0 -> two dummy outputs
        membership_proof: membership,
        non_membership_proof: non_membership,
        tree_depth: LEVELS as u32,
        smt_depth: LEVELS as u32,
    };

    let out = concat!(env!("CARGO_MANIFEST_DIR"), "/../prover-ffi/fixtures/withdraw_onchain.json");
    std::fs::write(out, serde_json::to_string_pretty(&params)?)?;
    println!("pool_root ok = {}", scalar_to_bigint(pool_root));
    println!("wrote {out}");
    Ok(())
}
