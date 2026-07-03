# AWS deploy — indexer + relayer

Provisions one EC2 box running the **indexer** (`:8080`), **relayer** (`:8090`),
and **Postgres** (docker, localhost only), fronted by **nginx** on 80/443.
The wallet talks to a single origin: `/events` → indexer, `/relay` → relayer.

The binaries are **prebuilt on your dev machine** (they pull the heavy
circuits/w2c2 toolchain, which we don't reproduce on the server) and shipped in
`pp-deploy.tgz`. Target AMI is Ubuntu 24.04 to match the build glibc (2.39).

## Contracts baked in (fresh, empty history)

| Contract | ID |
|---|---|
| Pool | `CCB7ORVJLFDUUEK55CCPIVDJR3IUN2G6F5KJMAGSGY7JAWWDQQGXRYJK` |
| ASP membership | `CBAC6AKBPK325WFTWXS3QRYBIDEFUGPC3ZZXMOW3JVTA4F5QSFRI5LF2` |
| ASP non-membership | `CDS5Q4CFZXTFQCTKKYKXROLWVEIM4IBQZFLL3YBDITD3ZOU5SSSRW2GN` |
| Verifier | `CAKRG62BKMRIR5S2SUOC4T5EFLLRBRJ7NTOFZOTCNYKGW4ZJME7BR4N6` |

Compiled as defaults in `indexer` + `relayer`; override via systemd env if you
redeploy again.

## Steps

### 1. Package the bundle (dev machine)
```bash
./deploy/aws/package.sh        # → deploy/aws/pp-deploy.tgz
```

### 2. Provision the box (zero console)
Uses the AWS creds already on this machine. Terraform generates the SSH key
pair itself (written to `pp-key.pem`), so no console steps.
```bash
cd deploy/aws
terraform init
terraform apply -var ssh_cidr=<your-ip>/32     # or omit for 0.0.0.0/0
# outputs: public_ip, ssh, indexer_base_url
```

### 3. Ship + set up
```bash
IP=<public_ip>
scp pp-deploy.tgz ubuntu@$IP:/opt/pp/
ssh ubuntu@$IP 'cd /opt/pp && tar xzf pp-deploy.tgz && ./server/setup-server.sh'
```
`setup-server.sh` brings up Postgres, both services, persists a funded relayer
account, and configures nginx (HTTP).

### 4. Verify
```bash
curl http://$IP/health
curl "http://$IP/events?cursor=0&limit=1"
```

### 5. Domain + TLS (decided after host is up)
Point an A record at the EIP, then:
```bash
ssh ubuntu@$IP 'sudo certbot --nginx -d api.example.com'
```
certbot rewrites the nginx block for 443 + HTTP→HTTPS redirect.

### 6. Point the app + build APK
Set the base URL in the two Kotlin clients to `https://api.example.com`
(see `IndexerClient.kt` / `RelayerClient.kt`), then build the release APK.
Once on HTTPS with a real cert, the app's `InsecureTls` path is no longer
needed for these endpoints.

## Operating

```bash
sudo systemctl status pp-indexer pp-relayer
journalctl -u pp-indexer -f
journalctl -u pp-relayer -f
# relayer account persisted at /opt/pp/relayer.env
```

Redeploy binaries: re-run `package.sh`, scp the tgz, `tar xzf`, then
`sudo systemctl restart pp-indexer pp-relayer`.
