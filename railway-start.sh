#!/bin/bash

set -e

echo "=== Fabric 1.21.11 Offline Chunky Bot ==="
echo "No Microsoft authentication is used."
echo "Server, username, movement and block-placement settings come from bot-settings.json.”

PORT="${PORT:-10000}"

echo "Starting Render health server on port ${PORT}…"

python3 -c '
import os
import socket

port = int(os.environ.get(“PORT”, “10000”))

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind((“0.0.0.0”, port))
server.listen(20)

print(”[Health] Listening on 0.0.0.0:” + str(port), flush=True)

while True:
conn, addr = server.accept()

try:
    conn.recv(1024)
    body = b"ChunkyBot is running\n"
    response = (
        b"HTTP/1.1 200 OK\r\n"
        b"Content-Type: text/plain\r\n"
        + ("Content-Length: " + str(len(body)) + "\r\n").encode()
        + b"Connection: close\r\n"
        + b"\r\n"
        + body
    )
    conn.sendall(response)
except Exception:
    pass
finally:
    conn.close()

’ &

echo "Starting virtual display…"

export DISPLAY=:99

Xvfb :99 
-screen 0 1024x768x24 
-ac 
+extension GLX 
+render 
-noreset 
> /tmp/xvfb.log 2>&1 &

sleep 2

echo "Starting Minecraft client…"

exec ./gradlew 
–no-daemon 
–console=plain 
-Dorg.gradle.daemon=false 
-Dorg.gradle.parallel=false 
-Dorg.gradle.jvmargs=”-Xmx1800M -Xms512M” 
runClient
