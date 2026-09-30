# Fabric 1.21.11 Offline Chunky Bot — GitHub Mobile Flat Edition

Every project file is at the repository root so GitHub mobile can upload them individually. The Dockerfile reconstructs the normal Fabric source/resource folders during the Railway build.

## Files to upload to GitHub

Upload every file in this package to the repository root:

- `Dockerfile`
- `bot-settings.json`
- `build.gradle`
- `gradle.properties`
- `settings.gradle`
- `railway.toml`
- `ChunkyBotClient.java`
- `fabric.mod.json`
- `railway-start.sh`
- `.gitignore`

Then upload your required client `.jar` mods **also to the repository root**. Do not create a `mods` folder. The Dockerfile automatically copies root-level `.jar` files into the Fabric `mods` directory when Railway builds the container.

## Configure the bot

Edit `bot-settings.json` in GitHub:

- `server.address` = your Aternos address
- `server.port` = your Aternos port
- `account.username` = the offline/cracked bot username
- movement, mob avoidance and block-placement settings can also be changed there

The server must use `online-mode=false`.

No Microsoft authentication is used.
