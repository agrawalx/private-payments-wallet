package com.privatepayments.net

/**
 * Single source of truth for the wallet's backend origins.
 *
 * **Local dev** (host-run services reached via `adb reverse tcp:8080`/`8090`):
 * indexer on `:8080`, relayer on `:8090` — the defaults below.
 *
 * **AWS** (nginx single origin): set BOTH to `https://<your-domain>`. nginx
 * routes `/events` → indexer and `/relay` → relayer, so one origin serves both.
 * Flip the two constants once the EC2 box + domain are live (see
 * `deploy/aws/README.md`), then rebuild the APK.
 */
object Endpoints {
    // --- AWS (hosted indexer+relayer behind nginx on the EC2 Elastic IP) ---
    // Single origin: nginx routes /events -> indexer, /relay -> relayer.
    const val INDEXER_BASE = "http://52.66.141.112"
    const val RELAYER_BASE = "http://52.66.141.112"

    // --- Local dev (host services via adb reverse tcp:8080 / 8090) ---
    // const val INDEXER_BASE = "http://127.0.0.1:8080"
    // const val RELAYER_BASE = "http://127.0.0.1:8090"
}
