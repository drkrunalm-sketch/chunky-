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

RUN mkdir -p src/main/java/com/shaurya/chunkybot src/main/resources mods \
    && cp ChunkyBotClient.java src/main/java/com/shaurya/chunkybot/ChunkyBotClient.java \
    && cp fabric.mod.json src/main/resources/fabric.mod.json \
    && find /app -maxdepth 1 -type f -name '*.jar' -exec cp {} /app/mods/ \; \
    && chmod +x railway-start.sh

RUN gradle --no-daemon build --stacktrace --info

ENV JAVA_TOOL_OPTIONS="-Xmx1800M -Xms512M"

RUN mkdir -p /app/run

CMD ["bash", "railway-start.sh"]
