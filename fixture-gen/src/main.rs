//! Generates a known-good `policy_tx_4_2` circuit-input vector and writes it as
//! flat JSON (`prover-ffi/fixtures/policy_tx_4_2_1in1out.json`).
//!
//! Ported from the reference repo's `circuits/src/test/prove_policy.rs`
//! (`test_tx_1in_1out` + `run_case`) so the inputs are guaranteed consistent
//! with the circuit. The JSON keys are the exact circom signal paths
//! (`membershipProofs[0][0].leaf`, `inAmount`, ...), so `prover-ffi`'s
//! `flatten_input` round-trips them straight into the witness generator.
//!
//! `policy_tx_4_2` takes 4 input slots (vs. the old 2_2's 2), so the case
//! below is "1 real input, 3 dummies" instead of "1 real, 1 dummy" — same
//! 1-in/1-out shape, padded to the circuit's fixed width.

use anyhow::Result;
use circuits::test::utils::{
    circom_tester::{Inputs, InputValue, SignalKey},
    general::{poseidon2_hash2, scalar_to_bigint},
    keypair::derive_public_key,
    merkle_tree::{merkle_proof, merkle_root},
    sparse_merkle_tree::prepare_smt_proof_with_overrides,
    transaction::prepopulated_leaves,
    transaction_case::{build_base_inputs, prepare_transaction_witness, InputNote, OutputNote, TxCase},
};
use num_bigint::BigInt;
use zkhash::{ark_ff::Zero, fields::bn256::FpBN256 as Scalar};

const LEVELS: usize = 10;

/// Non-membership override set: for each input pubkey, a key that is provably
/// absent, paired with the leaf that *would* exist. Mirrors the reference.
fn non_membership_overrides(pubs: &[Scalar]) -> Vec<(BigInt, BigInt)> {
    pubs.iter()
        .enumerate()
        .map(|(i, pk)| {
            let idx = (i as u64 + 1) * 100_000 + (i as u64 + 1);
            let override_key = Scalar::from(idx);
            let leaf = poseidon2_hash2(*pk, Scalar::zero(), Some(Scalar::from(1u64)));
            (scalar_to_bigint(override_key), scalar_to_bigint(leaf))
        })
        .collect()
}

fn main() -> Result<()> {
    // 1-in / 1-out case (one real input @ leaf 7, three dummies @ leaves 0/1/2 —
    // policy_tx_4_2 has 4 fixed input slots).
    let case = TxCase::new(
        vec![
            InputNote {
                leaf_index: 0,
                priv_key: Scalar::from(101u64),
                blinding: Scalar::from(201u64),
                amount: Scalar::from(0u64),
            },
            InputNote {
                leaf_index: 1,
                priv_key: Scalar::from(103u64),
                blinding: Scalar::from(221u64),
                amount: Scalar::from(0u64),
            },
            InputNote {
                leaf_index: 2,
                priv_key: Scalar::from(104u64),
                blinding: Scalar::from(231u64),
                amount: Scalar::from(0u64),
            },
            InputNote {
                leaf_index: 7,
                priv_key: Scalar::from(102u64),
                blinding: Scalar::from(211u64),
                amount: Scalar::from(13u64),
            },
        ],
        vec![
            OutputNote {
                pub_key: Scalar::from(501u64),
                blinding: Scalar::from(601u64),
                amount: Scalar::from(13u64),
            },
            OutputNote {
                pub_key: Scalar::from(502u64),
                blinding: Scalar::from(602u64),
                amount: Scalar::from(0u64),
            },
        ],
    );

    // Pool Merkle tree with the input commitments inserted.
    let leaves = prepopulated_leaves(
        LEVELS,
        0xDEAD_BEEFu64,
        &case.inputs.iter().map(|n| n.leaf_index).collect::<Vec<_>>(),
        24,
    );

    let witness = prepare_transaction_witness(&case, leaves, LEVELS)?;
    let mut inputs = build_base_inputs(&case, &witness, Scalar::from(0u64));
    let pubs = &witness.public_keys;
    let n_inputs = case.inputs.len();

    // === Membership proofs (1 ASP tree, blinding = 0) ===
    let base_mem_leaves = prepopulated_leaves(LEVELS, 0xFEED_FACEu64 ^ 0x1234_5678u64, &[], 24);
    let mut frozen = base_mem_leaves.clone();
    for (k, &pk) in pubs.iter().enumerate() {
        let leaf = poseidon2_hash2(pk, Scalar::zero(), Some(Scalar::from(1u64)));
        frozen[case.inputs[k].leaf_index] = leaf;
    }
    let mem_root = merkle_root(frozen.clone());

    let mut membership_roots: Vec<BigInt> = Vec::with_capacity(n_inputs);
    for i in 0..n_inputs {
        let leaf = poseidon2_hash2(pubs[i], Scalar::zero(), Some(Scalar::from(1u64)));
        let (siblings, path_idx_u64, depth) = merkle_proof(&frozen, case.inputs[i].leaf_index);
        assert_eq!(depth, LEVELS, "unexpected membership depth");

        let key = |field: &str| SignalKey::new("membershipProofs").idx(i).idx(0).field(field);
        inputs.set_key(&key("leaf"), scalar_to_bigint(leaf));
        inputs.set_key(&key("blinding"), scalar_to_bigint(Scalar::zero()));
        inputs.set_key(&key("pathIndices"), scalar_to_bigint(Scalar::from(path_idx_u64)));
        inputs.set_key(
            &key("pathElements"),
            siblings.into_iter().map(scalar_to_bigint).collect::<Vec<_>>(),
        );
        membership_roots.push(scalar_to_bigint(mem_root));
    }
    inputs.set("membershipRoots", membership_roots);

    // === Non-membership proofs (1 SMT, default overrides) ===
    let overrides = non_membership_overrides(pubs);
    let mut non_membership_roots: Vec<BigInt> = Vec::with_capacity(n_inputs);
    for i in 0..n_inputs {
        let key_non_incl = scalar_to_bigint(derive_public_key(case.inputs[i].priv_key));
        let proof = prepare_smt_proof_with_overrides(&key_non_incl, &overrides, LEVELS);

        let key = |field: &str| SignalKey::new("nonMembershipProofs").idx(i).idx(0).field(field);
        inputs.set_key(&key("key"), scalar_to_bigint(pubs[i]));
        if proof.is_old0 {
            inputs.set_key(&key("oldKey"), BigInt::from(0u32));
            inputs.set_key(&key("oldValue"), BigInt::from(0u32));
            inputs.set_key(&key("isOld0"), BigInt::from(1u32));
        } else {
            inputs.set_key(&key("oldKey"), proof.not_found_key.clone());
            inputs.set_key(&key("oldValue"), proof.not_found_value.clone());
            inputs.set_key(&key("isOld0"), BigInt::from(0u32));
        }
        inputs.set_key(&key("siblings"), proof.siblings.clone());
        non_membership_roots.push(proof.root.clone());
    }
    inputs.set("nonMembershipRoots", non_membership_roots);

    // Serialize to flat JSON: { "<signal>": "<dec>" | ["<dec>", ...] }
    let mut map = serde_json::Map::new();
    for (signal, value) in inputs.iter() {
        let json = match value {
            InputValue::Single(b) => serde_json::Value::String(b.to_string()),
            InputValue::Array(arr) => serde_json::Value::Array(
                arr.iter().map(|b| serde_json::Value::String(b.to_string())).collect(),
            ),
        };
        map.insert(signal.clone(), json);
    }
    let out = serde_json::to_string_pretty(&serde_json::Value::Object(map))?;

    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../prover-ffi/fixtures/policy_tx_4_2_1in1out.json");
    std::fs::create_dir_all(concat!(env!("CARGO_MANIFEST_DIR"), "/../prover-ffi/fixtures"))?;
    std::fs::write(path, out)?;
    println!("wrote fixture to {path}");
    Ok(())
}
