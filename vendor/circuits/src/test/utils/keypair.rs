use zkhash::{ark_ff::Zero, fields::bn256::FpBN256 as Scalar};

use super::general::{poseidon2_hash2, poseidon2_hash3};

/// Derive a public key from a private key using Poseidon2 hash
///
/// Computes `publicKey = Poseidon2(privateKey, 0)` with domain separation value
/// 3. Please note the 0 is used as padding as Poseidon2 hash does not support
/// T=1 inputs (over BN256).
///
/// # Arguments
///
/// * `private_key` - Private key scalar value
///
/// # Returns
///
/// Returns the derived public key as a scalar value.
pub fn derive_public_key(private_key: Scalar) -> Scalar {
    poseidon2_hash2(private_key, Scalar::zero(), Some(Scalar::from(3))) // We use 3 as domain separation for Keypair
}

/// Derive a nullifier key (`nk`) from a private key using Poseidon2 hash
/// (Zcash-style nk split).
///
/// Computes `nk = Poseidon2(privateKey, 0)` with domain separation value 5.
/// `nk` lets its holder compute nullifiers for owned notes (see `sign`) but
/// not spend — spend authority stays with `privateKey` via `derive_public_key`.
///
/// # Arguments
///
/// * `private_key` - Private key scalar value
///
/// # Returns
///
/// Returns the derived nullifier key as a scalar value.
pub fn derive_nullifier_key(private_key: Scalar) -> Scalar {
    poseidon2_hash2(private_key, Scalar::zero(), Some(Scalar::from(5))) // We use 5 as domain separation for NullifierKey
}

/// Generate a signature using Poseidon2 hash
///
/// Computes `signature = Poseidon2(privateKey, commitment, merklePath)` with
/// domain separation value 4.
///
/// # Arguments
///
/// * `private_key` - Private key scalar value
/// * `commitment` - Commitment scalar value
/// * `merkle_path` - Merkle path scalar value
///
/// # Returns
///
/// Returns the signature as a scalar value.
pub fn sign(private_key: Scalar, commitment: Scalar, merkle_path: Scalar) -> Scalar {
    poseidon2_hash3(private_key, commitment, merkle_path, Some(Scalar::from(4))) // We use 4 as domain separation for Signature
}
