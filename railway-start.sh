#!/usr/bin/env bash
set -euo pipefail

mkdir -p /app/run

echo "=== Fabric 1.21.11 Offline Chunky Bot ==="
echo "No Microsoft authentication is used."
echo "Server, username, movement and block-placement settings come from bot-settings.json."

echo "Starting Fabric client with a virtual display..."

exec xvfb-run -a -s "-screen 0 1280x720x24 -ac" gradle --no-daemon runClient
