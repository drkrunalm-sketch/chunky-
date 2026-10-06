package com.shaurya.chunkybot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.gui.screen.ConnectScreen;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;

import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

public class ChunkyBotClient implements ClientModInitializer {

    private static final MinecraftClient client = MinecraftClient.getInstance();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static BotSettings settings;

    private static final Random RANDOM = new Random();

    private static long lastReconnectAttempt = 0;
    private static long connectedAt = 0;
    private static long nextMovementChange = 0;
    private static long nextIdleChange = 0;
    private static long nextBlockPlace = 0;
    private static long lastMobScan = 0;

    private static boolean moving = false;
    private static boolean strafing = false;
    private static boolean sprinting = false;
    private static boolean jumping = false;
    private static boolean idling = false;

    private static float movementYaw = 0.0f;

    private static HostileEntity nearestHostile = null;

    private static Thread healthThread;

    @Override
    public void onInitializeClient() {

        loadSettings();

        startRenderHealthServer();

        /*
         * This is important:
         * it makes the bot's tick() method actually run.
         */
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());

        System.out.println("[ChunkyBot] Client initialized.");

        System.out.println(
                "[ChunkyBot] Target server: "
                        + settings.server.address
                        + ":"
                        + settings.server.port
        );

        System.out.println(
                "[ChunkyBot] Username: "
                        + settings.account.username
        );

        connectToServer();
    }

    // ============================================================
    // SETTINGS
    // ============================================================

    private static void loadSettings() {

        try {

            InputStream input = ChunkyBotClient.class
                    .getClassLoader()
                    .getResourceAsStream("bot-settings.json");

            if (input == null) {
                throw new IOException(
                        "bot-settings.json was not found in the built mod."
                );
            }

            String json = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );

            input.close();

            settings = GSON.fromJson(json, BotSettings.class);

            if (settings == null) {
                throw new IOException(
                        "bot-settings.json produced a null configuration."
                );
            }

            validateSettings();

            System.out.println("[ChunkyBot] Settings loaded successfully.");

        } catch (Exception e) {

            System.err.println(
                    "[ChunkyBot] Failed to load bot-settings.json:"
            );

            e.printStackTrace();

            /*
             * Keep the process alive so Render can still see the
             * health server and provide useful logs.
             */
            settings = new BotSettings();
        }
    }

    private static void validateSettings() {

        if (settings.server == null) {
            throw new IllegalArgumentException(
                    "Missing server settings."
            );
        }

        if (settings.account == null) {
            throw new IllegalArgumentException(
                    "Missing account settings."
            );
        }

        if (settings.server.address == null
                || settings.server.address.isBlank()) {

            throw new IllegalArgumentException(
                    "server.address is empty."
            );
        }

        if (settings.server.port < 1
                || settings.server.port > 65535) {

            throw new IllegalArgumentException(
                    "server.port must be between 1 and 65535."
            );
        }

        if (settings.account.username == null
                || settings.account.username.isBlank()) {

            throw new IllegalArgumentException(
                    "account.username is empty."
            );
        }

        if (settings.account.username.length() > 16) {

            throw new IllegalArgumentException(
                    "Minecraft usernames cannot exceed 16 characters."
            );
        }
    }

    // ============================================================
    // MAIN TICK
    // ============================================================

    private static void tick() {

        if (client == null) {
            return;
        }

        /*
         * If we are not currently in a world, the bot is either:
         *
         * - still connecting
         * - disconnected
         * - on a menu
         *
         * In that situation, don't run movement logic.
         */
        if (client.world == null || client.player == null) {

            clearMovementKeys();

            connectedAt = 0;

            tryReconnect();

            return;
        }

        /*
         * We are actually in the world.
         */
        if (connectedAt == 0) {

            connectedAt = System.currentTimeMillis();

            System.out.println(
                    "[ChunkyBot] Joined world successfully."
            );
        }

        /*
         * Keep the bot looking straight upward.
         *
         * This reduces the amount of world geometry that has to be
         * rendered compared with looking horizontally.
         */
        client.player.setPitch(-90.0f);

        /*
         * Hostile mob scanning is deliberately throttled.
         * We don't need to search the entity list every tick.
         */
        if (System.currentTimeMillis() - lastMobScan >= 500) {

            lastMobScan = System.currentTimeMillis();

            scanForHostiles();
        }

        /*
         * Emergency hostile-mob avoidance gets priority over normal
         * wandering.
         */
        if (settings.bot.mob_avoidance.enabled
                && nearestHostile != null) {

            double distance = distanceTo(
                    entityPosition(nearestHostile)
            );

            if (distance <= settings.bot.mob_avoidance.emergency_radius) {

                fleeFromHostile();

                return;
            }

            if (distance <= settings.bot.mob_avoidance.flee_radius) {

                fleeFromHostile();

                return;
            }
        }

        /*
         * Normal movement.
         */
        if (settings.bot.movement.enabled) {

            updateMovement();
        } else {

            clearMovementKeys();
        }

        /*
         * Optional block placement.
         */
        if (settings.bot.block_placing.enabled) {

            handleBlockPlacement();
        }
    }

    // ============================================================
    // CONNECTION
    // ============================================================

    private static void connectToServer() {

        if (settings == null || settings.server == null) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastReconnectAttempt < 3000) {
            return;
        }

        lastReconnectAttempt = now;

        String addressText =
                settings.server.address
                        + ":"
                        + settings.server.port;

        System.out.println(
                "[ChunkyBot] Connecting to "
                        + addressText
        );

        try {

            ServerAddress address = ServerAddress.parse(
                    addressText
            );

            ServerInfo serverInfo = new ServerInfo(
                    "Chunky Bot Server",
                    addressText,
                    ServerInfo.ServerType.OTHER
            );

            /*
             * Offline/cracked server login:
             *
             * The username/UUID/access token are supplied through
             * the Gradle runClient arguments in build.gradle.
             */
            ConnectScreen.connect(
                    null,
                    client,
                    address,
                    serverInfo,
                    false,
                    null
            );

        } catch (Exception e) {

            System.err.println(
                    "[ChunkyBot] Connection attempt failed:"
            );

            e.printStackTrace();
        }
    }

    private static void tryReconnect() {

        if (!settings.bot.auto_reconnect) {
            return;
        }

        if (client.world != null) {
            return;
        }

        long now = System.currentTimeMillis();

        long delay =
                settings.bot.reconnect_delay_seconds
                        * 1000L;

        if (now - lastReconnectAttempt < delay) {
            return;
        }

        /*
         * Don't repeatedly trigger connection attempts while the
         * ConnectScreen is already active.
         */
        if (client.currentScreen instanceof ConnectScreen) {
            return;
        }

        System.out.println(
                "[ChunkyBot] Attempting reconnect..."
        );

        connectToServer();
    }

    // ============================================================
    // MOVEMENT
    // ============================================================

    private static void updateMovement() {

        long now = System.currentTimeMillis();

        /*
         * If we're idling, occasionally leave the idle state.
         */
        if (idling) {

            clearMovementKeys();

            if (now >= nextIdleChange) {

                idling = false;

                nextMovementChange =
                        now + randomMovementDuration();

                System.out.println(
                        "[ChunkyBot] Leaving idle state."
                );
            }

            return;
        }

        /*
         * Pick a new direction once the current movement period ends.
         */
        if (now >= nextMovementChange) {

            chooseNewMovement();

            /*
             * Sometimes stop moving for a few seconds.
             */
            int idleChance = RANDOM.nextInt(100);

            if (idleChance < 15) {

                idling = true;

                nextIdleChange =
                        now + randomIdleDuration();

                clearMovementKeys();

                return;
            }
        }

        /*
         * Keep movement direction.
         */
        applyMovement();

        /*
         * Optional random looking.
         *
         * The bot still forces the pitch upward at the beginning
         * of tick(), but this can change yaw.
         */
        if (settings.bot.movement.random_look) {

            randomLook();
        }

        /*
         * Random jumping.
         */
        if (settings.bot.movement.jump_over_obstacles) {

            tryJump();
        }
    }

    private static void chooseNewMovement() {

        long now = System.currentTimeMillis();

        movementYaw =
                RANDOM.nextFloat() * 360.0f;

        strafing =
                settings.bot.movement.random_strafe
                        && RANDOM.nextBoolean();

        sprinting =
                settings.bot.movement.sprint
                        && RANDOM.nextInt(100) < 70;

        moving = true;

        jumping = false;

        nextMovementChange =
                now + randomMovementDuration();

        client.player.setYaw(movementYaw);

        System.out.println(
                "[ChunkyBot] New movement direction: "
                        + movementYaw
        );
    }

    private static void applyMovement() {

        if (client.player == null) {
            return;
        }

        /*
         * Clear all keys first so states don't get stuck.
         */
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);

        if (!moving) {
            return;
        }

        /*
         * Always move forward as the primary movement.
         */
        client.options.forwardKey.setPressed(true);

        /*
         * Occasionally strafe.
         */
        if (strafing) {

            if (RANDOM.nextBoolean()) {

                client.options.leftKey.setPressed(true);

            } else {

                client.options.rightKey.setPressed(true);
            }
        }

        /*
         * Sprint when configured.
         */
        if (settings.bot.movement.sprint
                && sprinting) {

            client.options.sprintKey.setPressed(true);

        } else {

            client.options.sprintKey.setPressed(false);
        }
    }

    private static void randomLook() {

        if (client.player == null) {
            return;
        }

        /*
         * Keep the pitch looking upward for low rendering load.
         */
        client.player.setPitch(-90.0f);

        /*
         * Small random yaw changes.
         */
        if (RANDOM.nextInt(100) < 8) {

            float currentYaw =
                    client.player.getYaw();

            float change =
                    RANDOM.nextFloat() * 80.0f - 40.0f;

            client.player.setYaw(
                    currentYaw + change
            );
        }
    }

    private static void tryJump() {

        if (client.player == null) {
            return;
        }

        /*
         * Don't spam jumps.
         */
        if (client.player.isOnGround()
                && RANDOM.nextInt(100) < 3) {

            client.options.jumpKey.setPressed(true);

            jumping = true;

        } else {

            client.options.jumpKey.setPressed(false);

            jumping = false;
        }
    }

    // ============================================================
    // HOSTILE MOB AVOIDANCE
    // ============================================================

    private static void scanForHostiles() {

        if (client.world == null
                || client.player == null) {

            nearestHostile = null;

            return;
        }

        double radius =
                settings.bot.mob_avoidance.detection_radius;

        List<HostileEntity> entities =
                client.world.getEntitiesByClass(
                        HostileEntity.class,
                        client.player
                                .getBoundingBox()
                                .expand(radius),
                        entity -> entity.isAlive()
                );

        HostileEntity closest = null;

        double closestDistance =
                Double.MAX_VALUE;

        for (HostileEntity entity : entities) {

            double distance =
                    distanceTo(entityPosition(entity));

            if (distance < closestDistance) {

                closestDistance = distance;

                closest = entity;
            }
        }

        nearestHostile = closest;
    }

    private static void fleeFromHostile() {

        if (client.player == null
                || nearestHostile == null) {

            return;
        }

        Vec3d playerPos =
                playerPosition();

        Vec3d hostilePos =
                entityPosition(nearestHostile);

        /*
         * Direction from hostile -> player.
         */
        double dx =
                playerPos.x - hostilePos.x;

        double dz =
                playerPos.z - hostilePos.z;

        double length =
                Math.sqrt(dx * dx + dz * dz);

        if (length < 0.001) {

            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }

        dx /= length;
        dz /= length;

        /*
         * Convert the escape vector to a Minecraft yaw.
         */
        float escapeYaw =
                (float) (
                        Math.toDegrees(
                                Math.atan2(-dx, dz)
                        )
                );

        client.player.setYaw(escapeYaw);

        /*
         * Sprint away.
         */
        client.options.forwardKey.setPressed(true);

        client.options.sprintKey.setPressed(true);

        /*
         * Emergency jump.
         */
        if (client.player.isOnGround()) {

            client.options.jumpKey.setPressed(true);
        }

        moving = true;
        sprinting = true;
    }

    // ============================================================
    // BLOCK PLACEMENT
    // ============================================================

    private static void handleBlockPlacement() {

        if (client.player == null
                || client.world == null
                || client.interactionManager == null) {

            return;
        }

        long now = System.currentTimeMillis();

        long interval =
                Math.max(
                        1,
                        settings.bot.block_placing
                                .interval_seconds
                ) * 1000L;

        if (now < nextBlockPlace) {
            return;
        }

        nextBlockPlace = now + interval;

        int slot =
                Math.max(
                        0,
                        Math.min(
                                8,
                                settings.bot.block_placing
                                        .hotbar_slot
                        )
                );

        ItemStack stack =
                client.player
                        .getInventory()
                        .getStack(slot);

        /*
         * Only place if the selected slot contains a block.
         */
        if (settings.bot.block_placing.only_if_block_in_slot) {

            if (!(stack.getItem() instanceof BlockItem)) {

                System.out.println(
                        "[ChunkyBot] Hotbar slot "
                                + slot
                                + " does not contain a block."
                );

                return;
            }
        }

        /*
         * Switch to configured hotbar slot.
         */
        client.player
                .getInventory()
                .setSelectedSlot(slot);

        /*
         * Find the block immediately below the player.
         */
        BlockPos below =
                client.player
                        .getBlockPos()
                        .down();

        BlockState belowState =
                client.world.getBlockState(below);

        /*
         * Only attempt placement if the target block can act
         * as a placement surface.
         */
        if (belowState.isAir()) {
            return;
        }

        /*
         * Place one block in front of the player.
         */
        Vec3d playerPos =
                playerPosition();

        float yaw =
                client.player.getYaw();

        double yawRadians =
                Math.toRadians(yaw);

        double forwardX =
                -Math.sin(yawRadians);

        double forwardZ =
                Math.cos(yawRadians);

        BlockPos target =
                BlockPos.ofFloored(
                        playerPos.x + forwardX,
                        playerPos.y,
                        playerPos.z + forwardZ
                );

        BlockState targetState =
                client.world.getBlockState(target);

        if (!targetState.isAir()) {
            return;
        }

        /*
         * Find an adjacent solid block to place against.
         */
        BlockPos support = target.down();

        BlockState supportState =
                client.world.getBlockState(support);

        if (supportState.isAir()) {
            return;
        }

        BlockHitResult hitResult =
                new BlockHitResult(
                        new Vec3d(
                                target.getX() + 0.5,
                                target.getY(),
                                target.getZ() + 0.5
                        ),
                        Direction.UP,
                        support,
                        false
                );

        try {

            client.interactionManager.interactBlock(
                    client.player,
                    Hand.MAIN_HAND,
                    hitResult
            );

        } catch (Exception e) {

            System.err.println(
                    "[ChunkyBot] Block placement failed:"
            );

            e.printStackTrace();
        }
    }

    // ============================================================
    // POSITION HELPERS
    // ============================================================

    /*
     * IMPORTANT:
     *
     * Minecraft 1.21.11 Yarn mappings used by this project don't
     * expose ClientPlayerEntity.getPos() the way the previous
     * version expected.
     *
     * Therefore positions are constructed from getX/getY/getZ.
     */

    private static Vec3d playerPosition() {

        if (client.player == null) {

            return Vec3d.ZERO;
        }

        return new Vec3d(
                client.player.getX(),
                client.player.getY(),
                client.player.getZ()
        );
    }

    private static Vec3d entityPosition(Entity entity) {

        return new Vec3d(
                entity.getX(),
                entity.getY(),
                entity.getZ()
        );
    }

    private static double distanceTo(Vec3d position) {

        Vec3d player =
                playerPosition();

        double dx =
                player.x - position.x;

        double dy =
                player.y - position.y;

        double dz =
                player.z - position.z;

        return Math.sqrt(
                dx * dx
                        + dy * dy
                        + dz * dz
        );
    }

    // ============================================================
    // MOVEMENT CLEANUP
    // ============================================================

    private static void clearMovementKeys() {

        if (client == null) {
            return;
