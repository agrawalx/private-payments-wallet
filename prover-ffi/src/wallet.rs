//! BIP39 seed-phrase wallet (Phase 4).
//!
//! Replaces the random Ed25519 auto-gen with a recoverable BIP39 mnemonic +
//! SEP-0005 key derivation, so the user has a backup phrase and can re-import.
//!
//! Path: mnemonic --BIP39 PBKDF2--> 64-byte seed --SLIP-0010 ed25519-->
//!       m/44'/148'/<account>' --> 32-byte Ed25519 secret seed --> Stellar `G...`.

use anyhow::{anyhow, Result};
use bip39::Mnemonic;
use ed25519_dalek::{Signer, SigningKey};
use hmac::{Hmac, Mac};
use sha2::Sha512;

type HmacSha512 = Hmac<Sha512>;

/// Generate a fresh BIP39 mnemonic. `words` must be 12 or 24.
pub fn generate_mnemonic(words: u8) -> Result<String> {
    let entropy_bytes = match words {
        12 => 16, // 128-bit
        24 => 32, // 256-bit
        _ => return Err(anyhow!("word count must be 12 or 24")),
    };
    let mut entropy = vec![0u8; entropy_bytes];
    rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut entropy);
    let m = Mnemonic::from_entropy(&entropy)?;
    Ok(m.words().collect::<Vec<_>>().join(" "))
}

/// Validate a mnemonic (BIP39 wordlist + checksum). Returns true if importable.
pub fn validate_mnemonic(phrase: &str) -> bool {
    Mnemonic::parse_normalized(phrase.trim()).is_ok()
}

/// Derive the Stellar account at SEP-5 path `m/44'/148'/<account>'` from a
/// mnemonic. Returns (32-byte Ed25519 secret seed, `G...` address).
pub fn mnemonic_to_account(phrase: &str, account: u32) -> Result<([u8; 32], String)> {
    let m = Mnemonic::parse_normalized(phrase.trim()).map_err(|e| anyhow!("invalid mnemonic: {e}"))?;
    // BIP39 seed (empty passphrase, per SEP-5).
    let seed = m.to_seed("");
    // SLIP-0010 ed25519: all path components are hardened.
    let key = derive_ed25519_slip10(&seed, &[44, 148, account])?;
    let sk = SigningKey::from_bytes(&key);
    let address = stellar_strkey::ed25519::PublicKey(sk.verifying_key().to_bytes())
        .to_string()
        .to_string();
    Ok((key, address))
}

/// Derive a 32-byte AES-256 backup key from the mnemonic (Phase 7). The key is
/// `HMAC-SHA512(BIP39-seed, "umbra/backup-key/v1")[..32]` — deterministic from
/// the recovery phrase, never stored, so the encrypted backup can only be opened
/// by someone who holds the phrase. Distinct from the signing key path.
pub fn backup_key_from_mnemonic(phrase: &str) -> Result<[u8; 32]> {
    let m = Mnemonic::parse_normalized(phrase.trim()).map_err(|e| anyhow!("invalid mnemonic: {e}"))?;
    let seed = m.to_seed("");
    let mut mac = HmacSha512::new_from_slice(&seed).map_err(|_| anyhow!("hmac key"))?;
    mac.update(b"umbra/backup-key/v1");
    let i = mac.finalize().into_bytes();
    let mut key = [0u8; 32];
    key.copy_from_slice(&i[0..32]);
    Ok(key)
}

/// Sign the fixed shielded-key-derivation message with an account's Ed25519
/// secret. The resulting 64-byte signature deterministically derives that
/// account's note + encryption keypair (`derive_keys`), so each account has its
/// own shielded identity. Must stay stable so a re-imported seed recovers the
/// same shielded keys.
const SHIELDED_KEY_MSG: &[u8] = b"Stella Privacy Pool Key Derivation v1";
pub fn shielded_key_signature(secret: &[u8; 32]) -> [u8; 64] {
    SigningKey::from_bytes(secret).sign(SHIELDED_KEY_MSG).to_bytes()
}

/// SLIP-0010 ed25519 master + hardened child derivation. Each path index is
/// hardened (`| 0x8000_0000`). Returns the 32-byte private key at the leaf.
fn derive_ed25519_slip10(seed: &[u8], path: &[u32]) -> Result<[u8; 32]> {
    let mut mac = HmacSha512::new_from_slice(b"ed25519 seed").map_err(|_| anyhow!("hmac key"))?;
    mac.update(seed);
    let i = mac.finalize().into_bytes();
    let mut key = [0u8; 32];
    let mut chain = [0u8; 32];
    key.copy_from_slice(&i[0..32]);
    chain.copy_from_slice(&i[32..64]);

    for &index in path {
        let hardened = index | 0x8000_0000;
        let mut mac = HmacSha512::new_from_slice(&chain).map_err(|_| anyhow!("hmac chain"))?;
        mac.update(&[0u8]); // SLIP-0010 ed25519: 0x00 || key
        mac.update(&key);
        mac.update(&hardened.to_be_bytes());
        let i = mac.finalize().into_bytes();
        key.copy_from_slice(&i[0..32]);
        chain.copy_from_slice(&i[32..64]);
    }
    Ok(key)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// SEP-0005 official test vector (mnemonic → account 0 address).
    /// https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0005.md
    #[test]
    fn sep5_test_vector() {
        let phrase = "illness spike retreat truth genius clock brain pass \
                      fit cave bargain toe";
        let (_secret, addr) = mnemonic_to_account(phrase, 0).expect("derive");
        // Canonical SEP-5 account-0 address for this mnemonic.
        assert_eq!(addr, "GDRXE2BQUC3AZNPVFSCEZ76NJ3WWL25FYFK6RGZGIEKWE4SOOHSUJUJ6");
        // Account 1 must be a distinct, valid address (different hardened index).
        let (_s1, addr1) = mnemonic_to_account(phrase, 1).expect("derive 1");
        assert_ne!(addr1, addr);
        assert!(addr1.starts_with('G'));
    }

    #[test]
    fn generate_and_reimport_roundtrip() {
        let phrase = generate_mnemonic(12).expect("generate");
        assert_eq!(phrase.split_whitespace().count(), 12);
        assert!(validate_mnemonic(&phrase));
        let (_s, a1) = mnemonic_to_account(&phrase, 0).unwrap();
        let (_s2, a2) = mnemonic_to_account(&phrase, 0).unwrap();
        assert_eq!(a1, a2, "derivation must be deterministic");
        assert!(a1.starts_with('G'));
    }

    #[test]
    fn rejects_bad_mnemonic() {
        assert!(!validate_mnemonic("not a real seed phrase at all nope nope nope"));
    }

    #[test]
    fn backup_key_is_deterministic_and_distinct() {
        let phrase = "illness spike retreat truth genius clock brain pass fit cave bargain toe";
        let k1 = backup_key_from_mnemonic(phrase).unwrap();
        let k2 = backup_key_from_mnemonic(phrase).unwrap();
        assert_eq!(k1, k2, "backup key must be deterministic from the phrase");
        // Must differ from the signing key (different derivation domain).
        let (sign_key, _) = mnemonic_to_account(phrase, 0).unwrap();
        assert_ne!(k1, sign_key, "backup key must not equal the signing key");
        // A different phrase yields a different key.
        let other = generate_mnemonic(12).unwrap();
        assert_ne!(k1, backup_key_from_mnemonic(&other).unwrap());
    }
}
