//! Entry point for the `uniffi-bindgen` CLI used to generate foreign-language
//! bindings (Kotlin) from this crate's `#[uniffi::export]` surface.
//!
//! Usage:
//!   cargo run --bin uniffi-bindgen -- generate \
//!     --library target/<triple>/release/libprover_ffi.so \
//!     --language kotlin --out-dir <dir>
fn main() {
    uniffi::uniffi_bindgen_main()
}
