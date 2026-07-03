#!/usr/bin/env bash
# Build the release binaries and package everything the server needs into
# deploy/aws/pp-deploy.tgz. Run from the repo root (or anywhere; paths resolve).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AWS="$ROOT/deploy/aws"
cd "$ROOT"

echo "==> building release binaries (indexer, relayer)"
cargo build --release -p indexer -p relayer

STAGE="$(mktemp -d)"
mkdir -p "$STAGE/bin" "$STAGE/server"
cp target/release/indexer "$STAGE/bin/"
cp target/release/relayer "$STAGE/bin/"
cp "$AWS/server/"* "$STAGE/server/"
chmod +x "$STAGE/server/setup-server.sh"

tar czf "$AWS/pp-deploy.tgz" -C "$STAGE" .
rm -rf "$STAGE"
echo "==> wrote $AWS/pp-deploy.tgz"
ls -lh "$AWS/pp-deploy.tgz"
