//! Build script for `prover-ffi`.
//!
//! Transpiles the Circom witness-generator WASM (`circuits/policy_tx_4_2.wasm`)
//! into native code at build time via `rust-witness`. This is what replaces the
//! reference implementation's Wasmer runtime — the output is a native
//! `policy_tx_4_2_witness()` function with no WASM interpreter/JIT, so it works
//! on `aarch64-linux-android`.
//!
//! `transpile_wasm` scans the given directory for `*.wasm` files and emits a
//! `<stem>_witness` function for each (here: `policy_tx_4_2`).

fn main() {
    // NOTE: the wasm is named `policytx42.wasm` (no underscores) on purpose —
    // w2c2 sanitizes the module name by stripping non-alphanumerics, and that
    // sanitized name must match the `witness!()` macro argument in lib.rs.
    // Both witness wasms are named without underscores (w2c2 strips them) so the
    // sanitized module name matches the `witness!()` macro args in lib.rs:
    // policytx42 + selectiveDisclosure1.
    println!("cargo:rerun-if-changed=circuits/policytx42.wasm");
    println!("cargo:rerun-if-changed=circuits/selectiveDisclosure1.wasm");
    rust_witness::transpile::transpile_wasm("circuits".to_string());
}
