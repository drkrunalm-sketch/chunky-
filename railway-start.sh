#!/bin/bash

set -e

echo "=== Fabric 1.21.11 Offline Chunky Bot ==="
echo "No Microsoft authentication is used."
echo "Server, username, movement and block-placement settings come from bot-settings.json."

PORT=${PORT:-10000}

echo "Starting Render health server on port $PORT..."

python3 /app/health_server.py &

echo "Starting virtual display..."

export DISPLAY=:99

Xvfb :99 -screen 0 1024x768x24 -ac +extension GLX +render -noreset > /tmp/xvfb.log 2>&1 &

sleep 2

echo "Starting Minecraft client..."

exec ./gradlew --no-daemon --console=plain -Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs="-Xmx1800M -Xms512M" runClient
