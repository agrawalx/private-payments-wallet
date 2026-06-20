//! Minimal async Soroban JSON-RPC client (the four calls the relayer needs).
//! Mirrors the wallet's Kotlin `SorobanRpc`, but server-side over reqwest+rustls.

use anyhow::{anyhow, Result};
use serde_json::{json, Value};

pub struct Rpc {
    url: String,
    http: reqwest::Client,
}

impl Rpc {
    pub fn new(url: String) -> Self {
        Self { url, http: reqwest::Client::new() }
    }

    async fn call(&self, method: &str, params: Value) -> Result<Value> {
        let body = json!({ "jsonrpc": "2.0", "id": 1, "method": method, "params": params });
        let resp: Value = self.http.post(&self.url).json(&body).send().await?.json().await?;
        if let Some(e) = resp.get("error") {
            return Err(anyhow!("rpc {method} error: {e}"));
        }
        resp.get("result").cloned().ok_or_else(|| anyhow!("rpc {method}: no result"))
    }

    /// getLedgerEntries → the relayer account entry XDR (for its sequence number).
    pub async fn account_entry_xdr(&self, ledger_key_b64: &str) -> Result<String> {
        let r = self.call("getLedgerEntries", json!({ "keys": [ledger_key_b64] })).await?;
        r["entries"][0]["xdr"]
            .as_str()
            .map(str::to_string)
            .ok_or_else(|| anyhow!("relayer account entry not found (is the relayer funded?)"))
    }

    /// simulateTransaction → raw result JSON (fed back into finalize_and_sign).
    pub async fn simulate(&self, unsigned_xdr: &str) -> Result<String> {
        Ok(self.call("simulateTransaction", json!({ "transaction": unsigned_xdr })).await?.to_string())
    }

    /// sendTransaction → tx hash (errors carry the decoded XDR).
    pub async fn send(&self, signed_xdr: &str) -> Result<String> {
        let r = self.call("sendTransaction", json!({ "transaction": signed_xdr })).await?;
        if r["status"].as_str() == Some("ERROR") {
            return Err(anyhow!("sendTransaction ERROR: {r}"));
        }
        r["hash"].as_str().map(str::to_string).ok_or_else(|| anyhow!("sendTransaction: no hash"))
    }

    /// Poll getTransaction until SUCCESS / FAILED (or give up).
    pub async fn poll(&self, hash: &str) -> Result<()> {
        for _ in 0..40 {
            let r = self.call("getTransaction", json!({ "hash": hash })).await?;
            match r["status"].as_str() {
                Some("SUCCESS") => return Ok(()),
                Some("FAILED") => return Err(anyhow!("tx {hash} FAILED: {r}")),
                _ => tokio::time::sleep(std::time::Duration::from_secs(1)).await,
            }
        }
        Err(anyhow!("tx {hash} not confirmed in time"))
    }
}
