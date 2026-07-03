//! Alternative proving backend for `policy_tx_4_2`: rapidsnark's native C++
//! prover instead of the cached arkworks `Prover` (Task B6). Behind the
//! `rapidsnark` cargo feature, default OFF — the arkworks path in `lib.rs`
//! stays the shipped default; this exists to compare feasibility/timings.
//!
//! Only the proving step (witness -> Groth16 proof) changes. Witness
//! generation is unchanged (same `policytx42_witness` native call via
//! rust-witness/w2c2), and public inputs are still extracted from the witness
//! bytes through the cached arkworks `Prover` (cheap — no proving involved),
//! so both backends agree byte-for-byte on public-input ordering without
//! trusting rapidsnark's own `public.json` output.
//!
//! The zkey is NOT embedded (`vendor/ceremony/final/policy_tx_4_2_final.zkey`
//! is ~35MB) — callers pass a filesystem path. On Android, Kotlin copies the
//! bundled asset to `filesDir` once and passes that path.

use std::collections::HashMap;

use anyhow::{anyhow, bail, Context, Result};
use num_bigint::{BigInt, BigUint};
use serde_json::Value;

use crate::{bigints_to_le_bytes, flatten_input, policy_prover, policytx42_witness, ProofBundle};

/// Generate a `policy_tx_4_2` proof with rapidsnark instead of arkworks.
/// `zkey_path` must point at `policy_tx_4_2_final.zkey` on disk.
pub fn prove_policy_tx_4_2_rapidsnark(inputs_json: &str, zkey_path: &str) -> Result<ProofBundle> {
    let value: Value = serde_json::from_str(inputs_json).context("inputs_json is not valid JSON")?;
    let obj = value
        .as_object()
        .ok_or_else(|| anyhow!("inputs_json must be a JSON object"))?;

    let mut inputs: HashMap<String, Vec<BigInt>> = HashMap::new();
    for (key, val) in obj {
        flatten_input(key, val, &mut inputs)?;
    }

    // Native witness generation — identical to the arkworks path.
    let witness: Vec<BigInt> = policytx42_witness(inputs);

    // Public inputs: extracted from witness bytes via the cached arkworks
    // `Prover` (no proving, just an offset slice) so both backends produce
    // byte-identical `public_inputs`. Cheaper than trusting rapidsnark's own
    // public-signals ordering, and lets Soroban submission code stay backend-
    // agnostic.
    let witness_bytes = bigints_to_le_bytes(&witness);
    let prover = policy_prover().map_err(|e| anyhow!("{e}"))?;
    let public_inputs = prover
        .extract_public_inputs(&witness_bytes)
        .map_err(|e| anyhow!("extract_public_inputs failed: {e}"))?;

    // rust-rapidsnark wants the witness as a snarkjs .wtns v2 buffer.
    let wtns_buffer = rust_rapidsnark::parse_bigints_to_witness(witness)
        .map_err(|e| anyhow!("wtns encode failed: {e}"))?;

    let result = rust_rapidsnark::groth16_prover_zkey_file_wrapper(zkey_path, wtns_buffer)
        .map_err(|e| anyhow!("rapidsnark prove failed: {e}"))?;

    let proof_json: Value =
        serde_json::from_str(&result.proof).context("rapidsnark proof is not valid JSON")?;
    let proof = snarkjs_proof_to_soroban_bytes(&proof_json)?;

    Ok(ProofBundle {
        proof,
        // rapidsnark's C API returns decimal-string proof/public JSON, not
        // arkworks' compressed point serialization — there's nothing correct
        // to put here, so leave it empty rather than fabricate a value no
        // caller can use. `verify_proof_bundle` (which needs it) is only
        // meaningful for the arkworks path; this backend is proved directly
        // against the on-chain 256-byte encoding (see the equivalence test).
        proof_compressed: Vec::new(),
        public_inputs,
    })
}

/// Convert a snarkjs-format Groth16 proof (`pi_a`/`pi_b`/`pi_c`, decimal
/// strings) to the 256-byte Soroban encoding: A(64: x||y BE) || B(128: G2
/// with c1||c0 imaginary-first ordering) || C(64). Mirrors
/// `proof_to_uncompressed_bytes` in
/// vendor/app/crates/core/prover/src/prover.rs (not exported from that
/// crate, so replicated here at the decimal-string level — no arkworks curve
/// types needed since G1/G2 affine coordinates are already reduced field
/// elements).
fn snarkjs_proof_to_soroban_bytes(proof: &Value) -> Result<Vec<u8>> {
    let pi_a = proof["pi_a"].as_array().ok_or_else(|| anyhow!("proof.pi_a missing"))?;
    let pi_b = proof["pi_b"].as_array().ok_or_else(|| anyhow!("proof.pi_b missing"))?;
    let pi_c = proof["pi_c"].as_array().ok_or_else(|| anyhow!("proof.pi_c missing"))?;

    let mut out = Vec::with_capacity(256);
    out.extend_from_slice(&g1_uncompressed(pi_a)?);
    out.extend_from_slice(&g2_uncompressed(pi_b)?);
    out.extend_from_slice(&g1_uncompressed(pi_c)?);
    Ok(out)
}

/// Decimal string -> 32-byte big-endian field element.
fn dec_str_to_be32(v: &Value) -> Result<[u8; 32]> {
    let s = v.as_str().ok_or_else(|| anyhow!("expected decimal string, got {v}"))?;
    let n = BigUint::parse_bytes(s.as_bytes(), 10)
        .ok_or_else(|| anyhow!("invalid decimal field element: {s}"))?;
    let bytes = n.to_bytes_be();
    if bytes.len() > 32 {
        bail_too_large(s)?;
    }
    let mut out = [0u8; 32];
    out[32 - bytes.len()..].copy_from_slice(&bytes);
    Ok(out)
}

fn bail_too_large(s: &str) -> Result<()> {
    Err(anyhow!("field element exceeds 32 bytes: {s}"))
}

/// snarkjs G1 point `[x, y, z]` (affine, `z == "1"`) -> 64-byte BE `x || y`.
fn g1_uncompressed(coords: &[Value]) -> Result<[u8; 64]> {
    if coords.len() < 2 {
        bail!("G1 point needs >= 2 coordinates, got {}", coords.len());
    }
    let mut out = [0u8; 64];
    out[..32].copy_from_slice(&dec_str_to_be32(&coords[0])?);
    out[32..].copy_from_slice(&dec_str_to_be32(&coords[1])?);
    Ok(out)
}

/// snarkjs G2 point `[[x_c0, x_c1], [y_c0, y_c1], [1, 0]]` -> 128-byte Soroban
/// encoding: x.c1 || x.c0 || y.c1 || y.c0 (imaginary-first, matches
/// `g2_bytes_uncompressed` in the vendored prover).
fn g2_uncompressed(coords: &[Value]) -> Result<[u8; 128]> {
    if coords.len() < 2 {
        bail!("G2 point needs >= 2 coordinates, got {}", coords.len());
    }
    let x = coords[0].as_array().ok_or_else(|| anyhow!("G2 x is not an array"))?;
    let y = coords[1].as_array().ok_or_else(|| anyhow!("G2 y is not an array"))?;
    let x0 = dec_str_to_be32(&x[0])?; // real
    let x1 = dec_str_to_be32(&x[1])?; // imaginary
    let y0 = dec_str_to_be32(&y[0])?;
    let y1 = dec_str_to_be32(&y[1])?;

    let mut out = [0u8; 128];
    out[..32].copy_from_slice(&x1);
    out[32..64].copy_from_slice(&x0);
    out[64..96].copy_from_slice(&y1);
    out[96..].copy_from_slice(&y0);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    const FIXTURE: &str = include_str!("../fixtures/policy_tx_4_2_1in1out.json");

    fn zkey_path() -> String {
        // Repo-relative path to the ceremony output; desktop test only.
        concat!(env!("CARGO_MANIFEST_DIR"), "/../vendor/ceremony/final/policy_tx_4_2_final.zkey")
            .to_string()
    }

    /// Decode the 256-byte Soroban proof back into arkworks affine points and
    /// re-verify with the SAME cached `Prover`/VK used by the arkworks path —
    /// proves the re-encoding (this file's `snarkjs_proof_to_soroban_bytes`)
    /// is correct, not just that rapidsnark produced *a* valid proof.
    fn reencode_verifies(bundle: &ProofBundle) -> Result<bool> {
        use ark_bn254::{Bn254, Fq, Fq2, G1Affine, G2Affine};
        use ark_ff::PrimeField;
        use ark_groth16::Proof;
        use ark_serialize::CanonicalSerialize;

        fn fq_from_be32(b: &[u8]) -> Fq {
            Fq::from_be_bytes_mod_order(b)
        }

        let p = &bundle.proof;
        assert_eq!(p.len(), 256, "soroban proof must be 256 bytes");

        // A: x||y BE (64 bytes)
        let a = G1Affine::new_unchecked(fq_from_be32(&p[0..32]), fq_from_be32(&p[32..64]));
        // B: x.c1||x.c0||y.c1||y.c0 BE (128 bytes) -> undo Soroban ordering.
        let b_x1 = fq_from_be32(&p[64..96]);
        let b_x0 = fq_from_be32(&p[96..128]);
        let b_y1 = fq_from_be32(&p[128..160]);
        let b_y0 = fq_from_be32(&p[160..192]);
        let b = G2Affine::new_unchecked(Fq2::new(b_x0, b_x1), Fq2::new(b_y0, b_y1));
        // C: x||y BE (64 bytes)
        let c = G1Affine::new_unchecked(fq_from_be32(&p[192..224]), fq_from_be32(&p[224..256]));

        let proof = Proof::<Bn254> { a, b, c };
        let mut compressed = Vec::new();
        proof.serialize_compressed(&mut compressed)?;

        let prover = policy_prover().map_err(|e| anyhow!("{e}"))?;
        prover
            .verify(&compressed, &bundle.public_inputs)
            .map_err(|e| anyhow!("verify failed: {e}"))
    }

    /// Full chain: prove with rapidsnark, re-encode, and verify the re-encoded
    /// proof through the existing arkworks verifier (same VK). Also times
    /// arkworks-vs-rapidsnark on the same fixture+witness for comparison.
    /// Requires the zkey at `vendor/ceremony/final/policy_tx_4_2_final.zkey`
    /// (desktop only; not run on Android).
    #[test]
    fn rapidsnark_proof_reencodes_and_verifies() {
        let zkey = zkey_path();
        assert!(std::path::Path::new(&zkey).exists(), "zkey not found at {zkey}");

        let t0 = Instant::now();
        let ark_bundle = crate::prove_policy_tx_4_2(FIXTURE).expect("arkworks prove failed");
        let arkworks_elapsed = t0.elapsed();

        let t1 = Instant::now();
        let rs_bundle =
            prove_policy_tx_4_2_rapidsnark(FIXTURE, &zkey).expect("rapidsnark prove failed");
        let rapidsnark_elapsed = t1.elapsed();

        println!("arkworks:   {arkworks_elapsed:?}");
        println!("rapidsnark: {rapidsnark_elapsed:?}");

        assert_eq!(
            rs_bundle.public_inputs, ark_bundle.public_inputs,
            "public inputs must match between backends"
        );

        let ok = reencode_verifies(&rs_bundle).expect("verify errored");
        assert!(ok, "re-encoded rapidsnark proof failed arkworks verification");

        // Sanity: the arkworks-path proof still verifies against itself too.
        assert!(crate::verify_locally(&ark_bundle).expect("arkworks verify errored"));
    }
}
