#!/usr/bin/env bash
# Run ON the EC2 instance, from inside the unpacked bundle at /opt/pp.
#   scp pp-deploy.tgz ubuntu@<ip>:/opt/pp/ && ssh ubuntu@<ip>
#   cd /opt/pp && tar xzf pp-deploy.tgz && ./server/setup-server.sh
set -euo pipefail
cd /opt/pp

echo "==> Postgres (docker)"
# Plain `docker run` — the ubuntu docker.io package ships no compose plugin.
sudo docker rm -f pp-indexer-pg >/dev/null 2>&1 || true
sudo docker run -d --name pp-indexer-pg --restart unless-stopped \
  -e POSTGRES_USER=indexer -e POSTGRES_PASSWORD=indexer -e POSTGRES_DB=indexer \
  -p 127.0.0.1:5434:5432 \
  -v pp_pgdata:/var/lib/postgresql/data \
  postgres:16-alpine
# Wait for it to accept connections.
for i in $(seq 1 30); do
  if sudo docker exec pp-indexer-pg pg_isready -U indexer >/dev/null 2>&1; then break; fi
  sleep 1
done

echo "==> systemd units"
sudo cp server/pp-indexer.service /etc/systemd/system/
sudo cp server/pp-relayer.service /etc/systemd/system/
sudo systemctl daemon-reload

echo "==> indexer"
sudo systemctl enable --now pp-indexer
sleep 2

echo "==> relayer (first run mints + friendbot-funds an account; we persist it)"
if [ ! -f /opt/pp/relayer.env ]; then
  sudo systemctl enable --now pp-relayer
  sleep 6
  # Scrape the generated account from the journal and persist it so the relayer
  # keeps a stable address across restarts.
  line="$(journalctl -u pp-relayer --no-pager | grep -m1 'generated a fresh relayer account' || true)"
  addr="$(echo "$line" | grep -oE 'G[A-Z2-7]{55}' | head -1 || true)"
  sec="$(echo "$line"  | grep -oE 'secret_hex=[0-9a-f]{64}' | head -1 | cut -d= -f2 || true)"
  if [ -n "$addr" ] && [ -n "$sec" ]; then
    printf 'RELAYER_ADDRESS=%s\nRELAYER_SECRET_HEX=%s\n' "$addr" "$sec" | sudo tee /opt/pp/relayer.env >/dev/null
    sudo chmod 600 /opt/pp/relayer.env
    sudo systemctl restart pp-relayer
    echo "    persisted relayer account $addr"
  else
    echo "    WARN: could not scrape relayer account from journal; check 'journalctl -u pp-relayer'"
  fi
else
  sudo systemctl enable --now pp-relayer
fi

echo "==> nginx (HTTP)"
sudo cp server/nginx-pp.conf /etc/nginx/sites-available/pp.conf
sudo ln -sf /etc/nginx/sites-available/pp.conf /etc/nginx/sites-enabled/pp.conf
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

echo
echo "==> Done. Verify:"
echo "    curl http://localhost/health"
echo "    curl 'http://localhost/events?cursor=0&limit=1'"
echo
echo "Next: point a domain at this IP, then:  sudo certbot --nginx -d <domain>"
