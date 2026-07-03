//! Private-payments indexer.
//!
//! Polls Stellar RPC `getEvents` for the pool + ASP contracts, stores raw
//! events in Postgres (resuming via a saved RPC cursor), and serves them over
//! HTTP with cursor pagination. Config via env vars (sensible testnet defaults).

mod api;
mod db;
mod rpc;

use std::time::Duration;

use anyhow::Result;
use tracing::{info, warn};

use crate::{db::Db, rpc::Rpc};

struct Config {
    rpc_url: String,
    database_url: String,
    contracts: Vec<String>,
    start_ledger: u32,
    poll_interval: Duration,
    bind: String,
    page_size: usize,
}

impl Config {
    fn from_env() -> Self {
        let env = |k: &str, d: &str| std::env::var(k).unwrap_or_else(|_| d.to_string());
        // Our deployed testnet contracts (fresh pool + ASP membership +
        // ASP non-membership) — override with INDEXER_CONTRACTS if redeployed.
        let contracts = env(
            "INDEXER_CONTRACTS",
            "CDFXXZCDNFVQXMM6DUZWXHABGHCAWWXXA7T3IZYE7DY5ZLHKTR52VACV,\
             CALBHI3CBBMEQ4CC57NA4FFEMM26TUHZPFXPJJ3DOJ4GKTK7BZA352BH,\
             CB7MG5HSATWQ4S4C4TGWECCAIR66VM6M5KQEYOUCWUN6ZO5N3LBK4K7V",
        )
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
        Config {
            rpc_url: env("INDEXER_RPC_URL", "https://soroban-testnet.stellar.org"),
            database_url: env(
                "DATABASE_URL",
                "postgres://indexer:indexer@localhost:5434/indexer",
            ),
            contracts,
            start_ledger: env("INDEXER_START_LEDGER", "3408231").parse().unwrap_or(0),
            poll_interval: Duration::from_secs(env("INDEXER_POLL_SECS", "5").parse().unwrap_or(5)),
            bind: env("INDEXER_BIND", "0.0.0.0:8080"),
            page_size: env("INDEXER_PAGE_SIZE", "200").parse().unwrap_or(200),
        }
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "indexer=info".into()),
        )
        .init();

    let cfg = Config::from_env();
    info!(contracts = cfg.contracts.len(), rpc = %cfg.rpc_url, "starting indexer");

    let db = Db::connect(&cfg.database_url).await?;
    let rpc = Rpc::new(&cfg.rpc_url)?;

    // Background poller.
    let poll_db = db.clone();
    tokio::spawn(async move {
        if let Err(e) = poll_loop(
            rpc,
            poll_db,
            cfg.contracts,
            cfg.start_ledger,
            cfg.page_size,
            cfg.poll_interval,
        )
        .await
        {
            warn!("poller exited: {e:#}");
        }
    });

    // HTTP server.
    let listener = tokio::net::TcpListener::bind(&cfg.bind).await?;
    info!(addr = %cfg.bind, "serving GET /events and /health");
    axum::serve(listener, api::router(db)).await?;
    Ok(())
}

async fn poll_loop(
    rpc: Rpc,
    db: Db,
    contracts: Vec<String>,
    configured_start: u32,
    page_size: usize,
    interval: Duration,
) -> Result<()> {
    // Resume from a saved cursor, else pick a start ledger inside the RPC
    // retention window (events older than that are gone from RPC — that's the
    // gap a durable indexer exists to close, going forward).
    let mut cursor = db.get_cursor().await?;
    if cursor.is_none() {
        let latest = rpc.latest_ledger().await?;
        let start = configured_start.max(latest.saturating_sub(17_280)); // ~24h back
        info!(latest, start, "no saved cursor — starting from recent ledger");
        cursor = poll_once(&rpc, &db, &contracts, start, None, page_size).await?;
    }

    loop {
        tokio::time::sleep(interval).await;
        match poll_once(&rpc, &db, &contracts, configured_start, cursor.as_deref(), page_size).await
        {
            Ok(Some(c)) => cursor = Some(c),
            Ok(None) => {}
            Err(e) => warn!("poll error (will retry): {e:#}"),
        }
    }
}

/// One poll: fetch a page, store it, persist + return the new RPC cursor.
async fn poll_once(
    rpc: &Rpc,
    db: &Db,
    contracts: &[String],
    start_ledger: u32,
    cursor: Option<&str>,
    page_size: usize,
) -> Result<Option<String>> {
    let page = rpc.get_events(contracts, start_ledger, cursor, page_size).await?;
    let new_cursor = page.cursor.clone();
    let inserted = db.insert_events(&page.events).await?;
    db.set_cursor(&new_cursor).await?;
    if inserted > 0 {
        info!(
            inserted,
            fetched = page.events.len(),
            latest = page.latest_ledger,
            "indexed new events"
        );
    }
    Ok(Some(new_cursor))
}
