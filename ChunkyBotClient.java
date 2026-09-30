package com.shaurya.chunkybot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.block.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offline-mode Fabric 1.21.11 client bot.
 * The server must have online-mode=false/cracked mode enabled.
 */
public class ChunkyBotClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkyBot");

    private Settings settings;
    private int ticks;
    private int reconnectTicks;
    private int patrolTicks;
    private int patrolDuration;
    private int idleTicks;
    private int idleDuration;
    private int placeTicks;
    private boolean firstConnectDone;
    private boolean connecting;
    private boolean hasAnchor;
    private double anchorX, anchorY, anchorZ;
    private float patrolYaw;
    private int randomState = 0x4D595DF4;

    @Override
    public void onInitializeClient() {
        try {
            settings = Settings.load();
        } catch (Exception e) {
            LOGGER.error("Could not load bot-settings.json", e);
            return;
        }

        LOGGER.info("Offline Chunky Bot loaded: {}:{} | MC {} | username={}",
                settings.address, settings.port, settings.minecraftVersion, settings.username);
        LOGGER.info("Movement={}, mob avoidance={}, block placing={}",
                settings.movementEnabled, settings.mobAvoidanceEnabled, settings.blockPlacingEnabled);

        patrolDuration = randomPatrolTicks();
        idleDuration = randomIdleTicks();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        ticks++;

        if (client.getNetworkHandler() == null || client.player == null || client.world == null) {
            releaseMovement(client);
            if (!connecting) {
                if (!firstConnectDone && ticks >= settings.connectDelayTicks()) {
                    connect(client);
                } else if (firstConnectDone && settings.autoReconnect) {
                    reconnectTicks++;
                    if (reconnectTicks >= settings.reconnectDelayTicks()) {
                        reconnectTicks = 0;
                        connect(client);
                    }
                }
            }
            return;
        }

        firstConnectDone = true;
        connecting = false;
        reconnectTicks = 0;

        if (!hasAnchor) {
            anchorX = client.player.getX();
            anchorY = client.player.getY();
            anchorZ = client.player.getZ();
            patrolYaw = client.player.getYaw();
            hasAnchor = true;
            LOGGER.info("Movement anchor set at ({}, {}, {})", anchorX, anchorY, anchorZ);
        }

        controlBot(client);

        if (settings.blockPlacingEnabled) {
            placeTicks++;
            if (placeTicks >= settings.placeIntervalTicks()) {
                placeTicks = 0;
                tryPlaceBlock(client);
            }
        }
    }

    private void controlBot(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            releaseMovement(client);
            return;
        }

        if (!settings.movementEnabled) {
            releaseMovement(client);
            return;
        }

        HostileEntity threat = settings.mobAvoidanceEnabled ? nearestHostile(client) : null;
        if (threat != null && client.player.squaredDistanceTo(threat) <= settings.fleeRadius * settings.fleeRadius) {
            fleeFrom(client, threat);
            idleTicks = 0;
            return;
        }

        double dx = client.player.getX() - anchorX;
        double dz = client.player.getZ() - anchorZ;
        double distanceFromAnchor = Math.sqrt(dx * dx + dz * dz);

        if (distanceFromAnchor > settings.patrolRadius) {
            patrolYaw = yawToward(client.player.getX(), client.player.getZ(), anchorX, anchorZ);
            walkForward(client, patrolYaw, true);
            idleTicks = 0;
            return;
        }

        if (idleTicks > 0) {
            idleTicks--;
            releaseMovement(client);
            if (settings.randomLook && idleTicks % 10 == 0) {
                patrolYaw += randomTurn();
                client.player.setYaw(patrolYaw);
                client.player.setHeadYaw(patrolYaw);
            }
            return;
        }

        patrolTicks++;
        if (patrolTicks >= patrolDuration) {
            patrolTicks = 0;
            patrolYaw += randomTurn();
            patrolDuration = randomPatrolTicks();
            if (nextRandom(5) == 0) {
                idleTicks = idleDuration = randomIdleTicks();
            }
        }

        walkForward(client, patrolYaw, false);
    }

    private HostileEntity nearestHostile(MinecraftClient client) {
        Box scanBox = client.player.getBoundingBox().expand(settings.detectionRadius);
        List<HostileEntity> mobs = client.world.getEntitiesByClass(
                HostileEntity.class, scanBox, mob -> !mob.isRemoved() && mob.isAlive());

        HostileEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (HostileEntity mob : mobs) {
            double distance = client.player.squaredDistanceTo(mob);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = mob;
            }
        }
        return nearest;
    }

    private void fleeFrom(MinecraftClient client, HostileEntity threat) {
        double awayX = client.player.getX() - threat.getX();
        double awayZ = client.player.getZ() - threat.getZ();
        if (Math.abs(awayX) + Math.abs(awayZ) < 0.001) {
            awayX = 1.0;
            awayZ = 0.0;
        }
        float fleeYaw = yawFromVector(awayX, awayZ);
        patrolYaw = fleeYaw;
        boolean emergency = client.player.squaredDistanceTo(threat)
                <= settings.emergencyRadius * settings.emergencyRadius;
        walkForward(client, fleeYaw, emergency);
    }

    private void walkForward(MinecraftClient client, float yaw, boolean emergency) {
        client.player.setYaw(yaw);
        client.player.setHeadYaw(yaw);
        client.options.forwardKey.setPressed(true);
        client.options.backKey.setPressed(false);

        boolean strafeLeft = settings.randomStrafe && nextRandom(12) == 0;
        boolean strafeRight = settings.randomStrafe && !strafeLeft && nextRandom(12) == 0;
        client.options.leftKey.setPressed(strafeLeft);
        client.options.rightKey.setPressed(strafeRight);
        client.options.sprintKey.setPressed(settings.sprint && emergency);

        boolean shouldJump = settings.jumpOverObstacles &&
                (client.player.horizontalCollision || (nextRandom(80) == 0 && client.player.isOnGround()));
        client.options.jumpKey.setPressed(shouldJump);
    }

    private void releaseMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private void tryPlaceBlock(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) return;
        if (!client.player.isAlive()) return;

        int slot = Math.max(0, Math.min(8, settings.hotbarSlot - 1));
        ItemStack stack = client.player.getInventory().getStack(slot);
        if (settings.onlyIfBlockInSlot && (stack.isEmpty() || !(stack.getItem() instanceof BlockItem))) {
            return;
        }
        if (stack.isEmpty()) return;

        client.player.getInventory().selectedSlot = slot;

        Direction facing = horizontalDirection(client.player.getYaw());
        BlockPos support = client.player.getBlockPos().down();
        if (settings.placeAhead) support = support.offset(facing);
        BlockPos target = support.up();

        if (!client.world.getBlockState(target).isAir()) return;
        BlockState supportState = client.world.getBlockState(support);
        if (supportState.isAir()) return;

        Vec3d hitPos = Vec3d.ofCenter(support).add(0.0, 0.5, 0.0);
        BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, support, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
        LOGGER.info("Attempted block placement at {} using hotbar slot {}", target, slot + 1);
    }

    private Direction horizontalDirection(float yaw) {
        float normalized = (yaw % 360.0f + 360.0f) % 360.0f;
        if (normalized >= 45 && normalized < 135) return Direction.WEST;
        if (normalized >= 135 && normalized < 225) return Direction.NORTH;
        if (normalized >= 225 && normalized < 315) return Direction.EAST;
        return Direction.SOUTH;
    }

    private int randomPatrolTicks() {
        int min = Math.max(1, settings.patrolMinSeconds * 20);
        int max = Math.max(min, settings.patrolMaxSeconds * 20);
        return min + nextRandom(max - min + 1);
    }

    private int randomIdleTicks() {
        int min = Math.max(1, settings.idleMinSeconds * 20);
        int max = Math.max(min, settings.idleMaxSeconds * 20);
        return min + nextRandom(max - min + 1);
    }

    private float yawToward(double fromX, double fromZ, double toX, double toZ) {
        return yawFromVector(toX - fromX, toZ - fromZ);
    }

    private float yawFromVector(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    private float randomTurn() {
        return switch (nextRandom(8)) {
            case 0 -> -110.0f;
            case 1 -> -75.0f;
            case 2 -> -45.0f;
            case 3 -> -25.0f;
            case 4 -> 25.0f;
            case 5 -> 45.0f;
            case 6 -> 75.0f;
            default -> 110.0f;
        };
    }

    private int nextRandom(int bound) {
        randomState ^= randomState << 13;
        randomState ^= randomState >>> 17;
        randomState ^= randomState << 5;
        return (randomState & 0x7fffffff) % Math.max(bound, 1);
    }

    private void connect(MinecraftClient client) {
        try {
            String addressString = settings.address + ":" + settings.port;
            ServerAddress address = ServerAddress.parse(addressString);
            ServerInfo info = new ServerInfo("Chunky Bot Target", addressString, ServerInfo.ServerType.OTHER);
            connecting = true;
            LOGGER.info("Connecting offline account '{}' to {}...", settings.username, addressString);
            ConnectScreen.connect(null, client, address, info, false, null);
        } catch (Exception e) {
            connecting = false;
            LOGGER.error("Connection attempt failed", e);
        }
    }

    private static final class Settings {
        String address;
        int port;
        String minecraftVersion;
        String username;
        boolean autoReconnect;
        int reconnectDelaySeconds;
        int connectDelaySeconds;
        boolean movementEnabled;
        double patrolRadius;
        boolean sprint;
        boolean jumpOverObstacles;
        boolean randomStrafe;
        boolean randomLook;
        int patrolMinSeconds;
        int patrolMaxSeconds;
        int idleMinSeconds;
        int idleMaxSeconds;
        boolean mobAvoidanceEnabled;
        double detectionRadius;
        double fleeRadius;
        double emergencyRadius;
        boolean blockPlacingEnabled;
        int hotbarSlot;
        int placeIntervalSeconds;
        boolean placeAhead;
        boolean onlyIfBlockInSlot;

        static Settings load() throws IOException {
            try (InputStream in = ChunkyBotClient.class.getResourceAsStream("/bot-settings.json")) {
                if (in == null) throw new IOException("bot-settings.json not found");
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject server = root.getAsJsonObject("server");
                JsonObject account = root.getAsJsonObject("account");
                JsonObject bot = root.getAsJsonObject("bot");
                JsonObject movement = bot.getAsJsonObject("movement");
                JsonObject mobs = bot.getAsJsonObject("mob_avoidance");
                JsonObject placing = bot.getAsJsonObject("block_placing");

                Settings s = new Settings();
                s.address = server.get("address").getAsString().trim();
                s.port = server.get("port").getAsInt();
                s.minecraftVersion = server.get("minecraft_version").getAsString();
                s.username = account.get("username").getAsString().trim();
                s.autoReconnect = bot.get("auto_reconnect").getAsBoolean();
                s.reconnectDelaySeconds = bot.get("reconnect_delay_seconds").getAsInt();
                s.connectDelaySeconds = bot.get("connect_delay_seconds").getAsInt();
                s.movementEnabled = movement.get("enabled").getAsBoolean();
                s.patrolRadius = movement.get("patrol_radius").getAsDouble();
                s.sprint = movement.get("sprint").getAsBoolean();
                s.jumpOverObstacles = movement.get("jump_over_obstacles").getAsBoolean();
                s.randomStrafe = movement.get("random_strafe").getAsBoolean();
                s.randomLook = movement.get("random_look").getAsBoolean();
                s.patrolMinSeconds = movement.get("patrol_min_seconds").getAsInt();
                s.patrolMaxSeconds = movement.get("patrol_max_seconds").getAsInt();
                s.idleMinSeconds = movement.get("idle_min_seconds").getAsInt();
                s.idleMaxSeconds = movement.get("idle_max_seconds").getAsInt();
                s.mobAvoidanceEnabled = mobs.get("enabled").getAsBoolean();
                s.detectionRadius = mobs.get("detection_radius").getAsDouble();
                s.fleeRadius = mobs.get("flee_radius").getAsDouble();
                s.emergencyRadius = mobs.get("emergency_radius").getAsDouble();
                s.blockPlacingEnabled = placing.get("enabled").getAsBoolean();
                s.hotbarSlot = placing.get("hotbar_slot").getAsInt();
                s.placeIntervalSeconds = placing.get("interval_seconds").getAsInt();
                s.placeAhead = placing.get("place_ahead").getAsBoolean();
                s.onlyIfBlockInSlot = placing.get("only_if_block_in_slot").getAsBoolean();

                if (s.address.isBlank() || s.address.equalsIgnoreCase("YOUR-ATERNNOS-ADDRESS"))
                    throw new IllegalArgumentException("Set server.address in bot-settings.json");
                if (s.port < 1 || s.port > 65535) throw new IllegalArgumentException("Invalid server.port");
                if (s.username.isBlank() || s.username.length() > 16) throw new IllegalArgumentException("Invalid account.username");
                if (s.patrolRadius <= 0) throw new IllegalArgumentException("patrol_radius must be > 0");
                if (s.patrolMinSeconds <= 0 || s.patrolMaxSeconds < s.patrolMinSeconds)
                    throw new IllegalArgumentException("Invalid patrol_min_seconds/patrol_max_seconds");
                if (s.idleMinSeconds <= 0 || s.idleMaxSeconds < s.idleMinSeconds)
                    throw new IllegalArgumentException("Invalid idle_min_seconds/idle_max_seconds");
                if (s.hotbarSlot < 1 || s.hotbarSlot > 9) throw new IllegalArgumentException("hotbar_slot must be 1-9");
                if (s.placeIntervalSeconds <= 0) throw new IllegalArgumentException("interval_seconds must be > 0");
                return s;
            }
        }

        int connectDelayTicks() { return Math.max(1, connectDelaySeconds * 20); }
        int reconnectDelayTicks() { return Math.max(1, reconnectDelaySeconds * 20); }
        int placeIntervalTicks() { return Math.max(1, placeIntervalSeconds * 20); }
    }
}
