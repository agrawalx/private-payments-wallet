//! Minimal Stellar RPC (JSON-RPC 2.0) client: just `getEvents` +
//! `getLatestLedger`, which is all the indexer needs.

use anyhow::{anyhow, Result};
use serde::Deserialize;
use serde_json::json;

pub struct Rpc {
    http: reqwest::Client,
    url: String,
}

/// One contract event as returned by Stellar RPC `getEvents`.
#[derive(Debug, Clone, Deserialize)]
pub struct Event {
    pub id: String,
    #[serde(rename = "type")]
    pub event_type: String,
    pub ledger: u32,
    #[serde(rename = "ledgerClosedAt")]
    pub ledger_closed_at: String,
    #[serde(rename = "contractId")]
    pub contract_id: String,
    pub topic: Vec<String>,
    pub value: String,
}

#[derive(Debug, Deserialize)]
pub struct EventsPage {
    pub events: Vec<Event>,
    #[serde(rename = "latestLedger")]
    pub latest_ledger: u32,
    #[serde(rename = "oldestLedger")]
    pub oldest_ledger: u32,
    pub cursor: String,
}

#[derive(Deserialize)]
struct RpcEnvelope<T> {
    result: Option<T>,
    error: Option<RpcError>,
}

#[derive(Deserialize, Debug)]
struct RpcError {
    code: i64,
    message: String,
}

#[derive(Deserialize)]
struct LatestLedger {
    sequence: u32,
}

impl Rpc {
    pub fn new(url: impl Into<String>) -> Result<Self> {
        Ok(Self {
            http: reqwest::Client::builder().build()?,
            url: url.into(),
        })
    }

    async fn call<T: for<'de> Deserialize<'de>>(
        &self,
        method: &str,
        params: serde_json::Value,
    ) -> Result<T> {
        let body = json!({ "jsonrpc": "2.0", "id": 1, "method": method, "params": params });
        let env: RpcEnvelope<T> = self
            .http
            .post(&self.url)
            .json(&body)
            .send()
            .await?
            .json()
            .await?;
        if let Some(e) = env.error {
            return Err(anyhow!("RPC {method} error {}: {}", e.code, e.message));
        }
        env.result.ok_or_else(|| anyhow!("RPC {method}: empty result"))
    }

    pub async fn latest_ledger(&self) -> Result<u32> {
        let r: LatestLedger = self.call("getLatestLedger", json!({})).await?;
        Ok(r.sequence)
    }

    /// Fetch a page of contract events. Pass `cursor` to resume, else
    /// `start_ledger`. Topic filter `**` matches everything.
    pub async fn get_events(
        &self,
        contract_ids: &[String],
        start_ledger: u32,
        cursor: Option<&str>,
        limit: usize,
    ) -> Result<EventsPage> {
        let mut params = json!({
            "filters": [{
                "type": "contract",
                "contractIds": contract_ids,
                "topics": [["**"]],
            }],
            "pagination": { "limit": limit },
        });
        match cursor {
            Some(c) => params["pagination"]["cursor"] = json!(c),
            None => params["startLedger"] = json!(start_ledger),
        }
        self.call("getEvents", params).await
    }
}
