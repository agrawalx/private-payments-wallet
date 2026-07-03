#!/usr/bin/env bash
# Runs once at first boot (cloud-init). Installs base packages only; the app
# binaries + config are shipped separately via the deploy bundle.
set -euxo pipefail

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y nginx docker.io certbot python3-certbot-nginx

systemctl enable --now docker
systemctl enable --now nginx

# 2GB swap — cheap OOM insurance on a 1GB t3.micro (postgres + 2 services).
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Where the deploy bundle is unpacked.
install -d -o ubuntu -g ubuntu /opt/pp
