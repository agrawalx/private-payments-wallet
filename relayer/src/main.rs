//! Privacy relayer for withdraw / transfer.
//!
//! ## Why this exists
//! Pool `transact(proof, ext_data, sender)` runs `sender.require_auth()` and, for
//! a deposit (`ext_amount > 0`), pulls tokens from `sender`. For **withdraw**
//! (`< 0`) and **transfer** (`== 0`), `sender` moves no tokens — it only submits
//! and pays the network fee, and nothing in the proof binds it to the spent
//! notes. If the wallet submits these itself, its public `G…` account becomes the
//! on-chain source/sender/fee-payer and re-links the otherwise-unlinkable op to a
//! public identity.
//!
//! This relayer makes **its own account** the source + `sender` + fee-payer
//! (Soroban `SourceAccount` credentials satisfy `require_auth`). The user just
//! hands over the proof + ext_data; the recipient and amount are bound by
//! `ext_data_hash` inside the proof, so the relayer cannot redirect or skim
//! funds. The wallet's public account never appears on-chain.
//!
//! ## Why not just fee-bump (OpenZeppelin Channels)?
//! A Stellar fee-bump changes only the *fee-payer*; the inner tx `sourceAccount`
//! (and the `sender` arg) stay the user. That hides who paid gas, not who acted.
//! For source/sender unlinkability the relayer must *be* the source — which is
//! what this does. (Channels could still fee-bump the relayer's own txs later.)
//!
//! Testnet only. No abuse controls / relayer-fee accounting yet (see plan.md).

mod rpc;

use anyhow::{anyhow, Result};
use axum::{extract::State, http::StatusCode, routing::{get, post}, Json, Router};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;

const DEFAULT_POOL_ID: &str = "CDVEICETZZERI7M3OSHQVT5YWXROK4EYC42KM52CUKCCXUXIUYBFJZQU";
const DEFAULT_RPC_URL: &str = "https://soroban-testnet.stellar.org";

struct Cfg {
    rpc_url: String,
    pool_id: String,
    relayer_addr: String,
    relayer_secret: Vec<u8>, // raw 32-byte Ed25519 seed
    bind: String,
}

#[derive(Clone)]
struct AppState {
    cfg: Arc<Cfg>,
    rpc: Arc<rpc::Rpc>,
}

/// The proof bundle + ext_data the wallet produced for a withdraw/transfer.
/// All byte fields are base64 (matching the wallet's Android `Base64.NO_WRAP`).
#[derive(Deserialize)]
struct RelayReq {
    proof: String,
    public_inputs: String,
    ext_data_hash: String,
    ext_recipient: String,
    ext_amount: String, // i128 as a decimal string (0 = transfer, negative = withdraw)
    encrypted_output0: String,
    encrypted_output1: String,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "relayer=info".into()),
        )
        .init();

    let cfg = Arc::new(load_cfg().await?);
    let rpc = Arc::new(rpc::Rpc::new(cfg.rpc_url.clone()));
    let bind = cfg.bind.clone();
    let state = AppState { cfg, rpc };

    let app = Router::new()
        .route("/health", get(health))
        .route("/relay", post(relay))
        .with_state(state.clone());

    let listener = tokio::net::TcpListener::bind(&bind).await?;
    tracing::info!(addr = %bind, relayer = %state.cfg.relayer_addr, pool = %state.cfg.pool_id,
        "relayer serving POST /relay (withdraw/transfer) + GET /health");
    axum::serve(listener, app).await?;
    Ok(())
}

async fn health(State(s): State<AppState>) -> Json<Value> {
    Json(json!({ "status": "ok", "relayer": s.cfg.relayer_addr }))
}

async fn relay(State(s): State<AppState>, Json(req): Json<RelayReq>) -> Result<Json<Value>, (StatusCode, String)> {
    match relay_inner(&s, &req).await {
        Ok(hash) => {
            tracing::info!(%hash, "relayed transact");
            Ok(Json(json!({ "hash": hash, "relayer": s.cfg.relayer_addr })))
        }
        Err(e) => {
            tracing::warn!(error = %e, "relay failed");
            Err((StatusCode::BAD_GATEWAY, e.to_string()))
        }
    }
}

async fn relay_inner(s: &AppState, req: &RelayReq) -> Result<String> {
    let dec = |b64: &str| B64.decode(b64).map_err(|e| anyhow!("base64: {e}"));
    let proof = dec(&req.proof)?;
    let public_inputs = dec(&req.public_inputs)?;
    let ext_data_hash = dec(&req.ext_data_hash)?;
    let enc0 = dec(&req.encrypted_output0)?;
    let enc1 = dec(&req.encrypted_output1)?;

    // Reuse prover-ffi's pure tx build/sign — relayer account is source + sender.
    let key = prover_ffi::account_ledger_key(s.cfg.relayer_addr.clone())
        .map_err(|e| anyhow!("ledger key: {e:?}"))?;
    let entry = s.rpc.account_entry_xdr(&key).await?;
    let unsigned = prover_ffi::build_unsigned_transact(
        s.cfg.pool_id.clone(),
        s.cfg.relayer_addr.clone(),
        entry,
        proof,
        public_inputs,
        ext_data_hash,
        req.ext_recipient.clone(),
        req.ext_amount.clone(),
        enc0,
        enc1,
    )
    .map_err(|e| anyhow!("build_unsigned_transact: {e:?}"))?;
    let sim = s.rpc.simulate(&unsigned).await?;
    let signed = prover_ffi::finalize_and_sign(unsigned, sim, s.cfg.relayer_secret.clone())
        .map_err(|e| anyhow!("finalize_and_sign: {e:?}"))?;
    let hash = s.rpc.send(&signed).await?;
    s.rpc.poll(&hash).await?;
    Ok(hash)
}

async fn load_cfg() -> Result<Cfg> {
    let env = |k: &str, d: &str| std::env::var(k).unwrap_or_else(|_| d.to_string());
    let rpc_url = env("RELAYER_RPC_URL", DEFAULT_RPC_URL);
    let pool_id = env("RELAYER_POOL_ID", DEFAULT_POOL_ID);
    let bind = env("RELAYER_BIND", "0.0.0.0:8090");

    let (relayer_addr, relayer_secret) =
        match (std::env::var("RELAYER_ADDRESS"), std::env::var("RELAYER_SECRET_HEX")) {
            (Ok(a), Ok(h)) => (a, hex::decode(h.trim()).map_err(|e| anyhow!("RELAYER_SECRET_HEX: {e}"))?),
            _ => {
                // No persisted key — mint one and friendbot-fund it. The operator
                // should copy these into env vars to keep a stable relayer account.
                let acct = prover_ffi::generate_stellar_account();
                tracing::warn!(
                    address = %acct.address,
                    secret_hex = %hex::encode(&acct.secret),
                    "no RELAYER_ADDRESS/RELAYER_SECRET_HEX set — generated a fresh relayer account; \
                     set these env vars to persist it across restarts"
                );
                friendbot(&acct.address).await;
                (acct.address, acct.secret)
            }
        };
    if relayer_secret.len() != 32 {
        return Err(anyhow!("relayer secret must be 32 bytes ({} given)", relayer_secret.len()));
    }
    Ok(Cfg { rpc_url, pool_id, relayer_addr, relayer_secret, bind })
}

async fn friendbot(addr: &str) {
    let url = format!("https://friendbot.stellar.org/?addr={addr}");
    match reqwest::get(&url).await {
        Ok(_) => {
            tracing::info!(%addr, "friendbot funded relayer account");
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
        }
        Err(e) => tracing::warn!(error = %e, "friendbot funding failed (fund the relayer manually)"),
    }
}
