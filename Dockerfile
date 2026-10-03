FROM gradle:9.5-jdk21

USER root
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb \
    libxi6 \
    libxtst6 \
    libxrender1 \
    libxrandr2 \
    libx11-6 \
    libgl1 \
    libglx-mesa0 \
    libgl1-mesa-dri \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# GitHub-mobile-friendly flat layout: reconstruct the normal Fabric project here.
COPY . .

COPY . .

RUN test -f gradle.properties \
    && echo "=== gradle.properties FOUND ===" \
    && cat gradle.properties

RUN mkdir -p src/main/java/com/shaurya/chunkybot src/main/resources mods \
    && cp ChunkyBotClient.java src/main/java/com/shaurya/chunkybot/ChunkyBotClient.java \
    && cp fabric.mod.json src/main/resources/fabric.mod.json \
    && find /app -maxdepth 1 -type f -name '*.jar' -exec cp {} /app/mods/ \; \
    && chmod +x railway-start.sh

RUN gradle wrapper --gradle-version 9.5.1

RUN ./gradlew --no-daemon build --refresh-dependencies --console=plain 2>&1 | tee /tmp/gradle.log; \
    status=${PIPESTATUS[0]}; \
    echo "===== LAST 100 LINES ====="; \
    tail -n 100 /tmp/gradle.log; \
    exit $status
