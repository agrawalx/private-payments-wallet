//! Postgres storage for raw contract events + the RPC resume cursor.

use anyhow::Result;
use serde::Serialize;
use sqlx::{postgres::PgPoolOptions, PgPool, Row};

use crate::rpc::Event;

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS events (
    seq              BIGSERIAL PRIMARY KEY,
    event_id         TEXT UNIQUE NOT NULL,
    contract_id      TEXT NOT NULL,
    ledger           BIGINT NOT NULL,
    type             TEXT NOT NULL,
    ledger_closed_at TEXT NOT NULL,
    topic            TEXT NOT NULL,   -- JSON array
    value            TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_contract ON events(contract_id);
CREATE TABLE IF NOT EXISTS sync_state (
    id          INT PRIMARY KEY CHECK (id = 1),
    rpc_cursor  TEXT
);
INSERT INTO sync_state (id, rpc_cursor) VALUES (1, NULL) ON CONFLICT DO NOTHING;
"#;

#[derive(Clone)]
pub struct Db {
    pool: PgPool,
}

/// An event as served by the API — raw fields plus our monotonic `seq` (the
/// client's pagination cursor).
#[derive(Debug, Serialize)]
pub struct StoredEvent {
    pub seq: i64,
    pub event_id: String,
    pub contract_id: String,
    pub ledger: i64,
    #[serde(rename = "type")]
    pub event_type: String,
    pub ledger_closed_at: String,
    pub topic: Vec<String>,
    pub value: String,
}

impl Db {
    pub async fn connect(url: &str) -> Result<Self> {
        let pool = PgPoolOptions::new().max_connections(5).connect(url).await?;
        sqlx::raw_sql(SCHEMA).execute(&pool).await?;
        Ok(Self { pool })
    }

    /// Insert a batch of events (idempotent by `event_id`). Returns inserted count.
    pub async fn insert_events(&self, events: &[Event]) -> Result<u64> {
        let mut inserted = 0;
        let mut tx = self.pool.begin().await?;
        for e in events {
            let r = sqlx::query(
                "INSERT INTO events
                 (event_id, contract_id, ledger, type, ledger_closed_at, topic, value)
                 VALUES ($1, $2, $3, $4, $5, $6, $7)
                 ON CONFLICT (event_id) DO NOTHING",
            )
            .bind(&e.id)
            .bind(&e.contract_id)
            .bind(e.ledger as i64)
            .bind(&e.event_type)
            .bind(&e.ledger_closed_at)
            .bind(serde_json::to_string(&e.topic)?)
            .bind(&e.value)
            .execute(&mut *tx)
            .await?;
            inserted += r.rows_affected();
        }
        tx.commit().await?;
        Ok(inserted)
    }

    pub async fn get_cursor(&self) -> Result<Option<String>> {
        let row = sqlx::query("SELECT rpc_cursor FROM sync_state WHERE id = 1")
            .fetch_one(&self.pool)
            .await?;
        Ok(row.try_get::<Option<String>, _>("rpc_cursor")?)
    }

    pub async fn set_cursor(&self, cursor: &str) -> Result<()> {
        sqlx::query("UPDATE sync_state SET rpc_cursor = $1 WHERE id = 1")
            .bind(cursor)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    /// Events with `seq > after_seq`, ordered, capped at `limit`.
    pub async fn query_events(&self, after_seq: i64, limit: i64) -> Result<Vec<StoredEvent>> {
        let rows = sqlx::query(
            "SELECT seq, event_id, contract_id, ledger, type, ledger_closed_at, topic, value
             FROM events WHERE seq > $1 ORDER BY seq ASC LIMIT $2",
        )
        .bind(after_seq)
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;

        let mut out = Vec::with_capacity(rows.len());
        for r in rows {
            let topic_json: String = r.try_get("topic")?;
            out.push(StoredEvent {
                seq: r.try_get("seq")?,
                event_id: r.try_get("event_id")?,
                contract_id: r.try_get("contract_id")?,
                ledger: r.try_get("ledger")?,
                event_type: r.try_get("type")?,
                ledger_closed_at: r.try_get("ledger_closed_at")?,
                topic: serde_json::from_str(&topic_json).unwrap_or_default(),
                value: r.try_get("value")?,
            });
        }
        Ok(out)
    }

    pub async fn count(&self) -> Result<i64> {
        let row = sqlx::query("SELECT COUNT(*) AS n FROM events")
            .fetch_one(&self.pool)
            .await?;
        Ok(row.try_get("n")?)
    }
}
