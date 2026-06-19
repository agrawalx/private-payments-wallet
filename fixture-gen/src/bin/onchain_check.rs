//! Decisive experiment for the on-chain deposit: compute our ASP membership
//! leaf and check that our merkle math reproduces the contract's empty-tree
//! root (so a membership path we build will match the on-chain root).

use circuits::test::utils::{general::scalar_to_bigint, merkle_tree::merkle_root};
use num_bigint::BigUint;
use prover::crypto::{asp_membership_leaf, derive_public_key, zero_leaf};
use types::{Field, NotePublicKey};
use zkhash::{
    ark_ff::{BigInteger, PrimeField},
    fields::bn256::FpBN256 as Scalar,
};

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

fn main() {
    let priv_field = field_from_scalar(Scalar::from(102u64));
    let priv_bytes: [u8; 32] = priv_field.to_le_bytes();
    let pubkey: [u8; 32] = derive_public_key(&priv_bytes).unwrap().try_into().unwrap();
    let note_pub = NotePublicKey(pubkey);
    let blinding = Field::try_from_le_bytes([0u8; 32]).unwrap();
    let leaf = asp_membership_leaf(&note_pub, &blinding).unwrap();
    let leaf_scalar = scalar_from_field(&leaf);

    println!("LEAF_DECIMAL  = {}", scalar_to_bigint(leaf_scalar));

    let n = 1usize << 10;
    let zl = scalar_from_be(&zero_leaf());
    let empty_root = merkle_root(vec![zl; n]);
    println!("EMPTY_ROOT    = {}", scalar_to_bigint(empty_root));

    let mut leaves = vec![zl; n];
    leaves[0] = leaf_scalar;
    let with_root = merkle_root(leaves);
    println!("WITH_LEAF_ROOT= {}", scalar_to_bigint(with_root));
}
