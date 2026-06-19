//! HTTP API: cursor-paginated event feed for the wallet to sync from.

use axum::{
    extract::{Query, State},
    http::StatusCode,
    response::IntoResponse,
    routing::get,
    Json, Router,
};
use serde::Deserialize;
use serde_json::json;

use crate::db::Db;

pub fn router(db: Db) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/events", get(events))
        .with_state(db)
}

async fn health(State(db): State<Db>) -> impl IntoResponse {
    let count = db.count().await.unwrap_or(-1);
    Json(json!({ "status": "ok", "events": count }))
}

#[derive(Deserialize)]
struct EventsQuery {
    #[serde(default)]
    cursor: i64,
    limit: Option<i64>,
}

/// `GET /events?cursor=<seq>&limit=<n>` — events after `cursor`, plus the new
/// cursor (the last `seq` returned). The wallet persists `cursor` and only
/// fetches deltas on each sync.
async fn events(State(db): State<Db>, Query(q): Query<EventsQuery>) -> impl IntoResponse {
    let limit = q.limit.unwrap_or(300).clamp(1, 1000);
    match db.query_events(q.cursor, limit).await {
        Ok(events) => {
            let next = events.last().map(|e| e.seq).unwrap_or(q.cursor);
            Json(json!({ "cursor": next, "count": events.len(), "events": events }))
                .into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "error": e.to_string() })),
        )
            .into_response(),
    }
}
