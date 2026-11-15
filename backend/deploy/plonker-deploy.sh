#!/usr/bin/env bash
# Pulls main and rebuilds Plonker Listen Together server.
set -euo pipefail
SRC=/opt/plonker-src
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch main https://github.com/Plonker/Plonker.git "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin main
git -C "$SRC" reset --hard FETCH_HEAD
echo "deploying $(git -C "$SRC" log -1 --format='%h %s')"
DOMAIN="$(cat /etc/plonker-domain)" bash "$SRC/backend/deploy/setup.sh"
