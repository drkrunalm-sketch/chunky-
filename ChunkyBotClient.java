package com.shaurya.chunkybot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;

import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

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

    private static final MinecraftClient client =
            MinecraftClient.getInstance();

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private static final Random RANDOM =
            new Random();

    private static BotSettings settings;

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

    // ============================================================
    // INITIALIZATION
    // ============================================================

    @Override
    public void onInitializeClient() {

        loadSettings();

        startRenderHealthServer();

        ClientTickEvents.END_CLIENT_TICK.register(
                client -> tick()
        );

        System.out.println(
                "[ChunkyBot] Client initialized."
        );

        System.out.println(
                "[ChunkyBot] Server: "
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

            InputStream input =
                    ChunkyBotClient.class
                            .getClassLoader()
                            .getResourceAsStream(
                                    "bot-settings.json"
                            );

            if (input == null) {
                throw new IOException(
                        "bot-settings.json was not found."
                );
            }

            String json =
                    new String(
                            input.readAllBytes(),
                            StandardCharsets.UTF_8
                    );

            input.close();

            settings =
                    GSON.fromJson(
                            json,
                            BotSettings.class
                    );

            if (settings == null) {
                throw new IOException(
                        "Settings loaded as null."
                );
            }

            validateSettings();

            System.out.println(
                    "[ChunkyBot] Settings loaded."
            );

        } catch (Exception e) {

            System.err.println(
                    "[ChunkyBot] Failed to load settings:"
            );

            e.printStackTrace();

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

        if (settings.bot == null) {
            throw new IllegalArgumentException(
                    "Missing bot settings."
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
                    "Invalid server.port."
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
                    "Username is longer than 16 characters."
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
         * Not currently inside a Minecraft world.
         */
        if (client.world == null
                || client.player == null) {

            clearMovementKeys();

            connectedAt = 0;

            tryReconnect();

            return;
        }

        /*
         * First tick after joining.
         */
        if (connectedAt == 0) {

            connectedAt =
                    System.currentTimeMillis();

            System.out.println(
                    "[ChunkyBot] Joined the server."
            );
        }

        /*
         * Look straight upward to reduce rendering workload.
         */
        client.player.setPitch(-90.0f);

        /*
         * Scan hostile mobs approximately twice per second.
         */
        if (System.currentTimeMillis()
                - lastMobScan >= 500) {

            lastMobScan =
                    System.currentTimeMillis();

            scanForHostiles();
        }

        /*
         * Hostile mob avoidance takes priority.
         */
        if (settings.bot.mob_avoidance.enabled
                && nearestHostile != null) {

            double distance =
                    distanceTo(
                            entityPosition(
                                    nearestHostile
                            )
                    );

            if (distance
                    <= settings.bot.mob_avoidance.flee_radius) {

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

        if (settings == null
                || settings.server == null) {

            return;
        }

        long now =
                System.currentTimeMillis();

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

            ServerAddress address =
                    ServerAddress.parse(
                            addressText
                    );

            ServerInfo serverInfo =
                    new ServerInfo(
                            "Chunky Bot Server",
                            addressText,
                            ServerInfo.ServerType.OTHER
                    );

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
                    "[ChunkyBot] Connection failed:"
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

        long now =
                System.currentTimeMillis();

        long delay =
                settings.bot.reconnect_delay_seconds
                        * 1000L;

        if (now - lastReconnectAttempt < delay) {
            return;
        }

        /*
         * Don't repeatedly start connections while Minecraft
         * is already displaying a connection screen.
         */
        if (client.currentScreen
                instanceof ConnectScreen) {

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

        long now =
                System.currentTimeMillis();

        if (idling) {

            clearMovementKeys();

            if (now >= nextIdleChange) {

                idling = false;

                nextMovementChange =
                        now
                                + randomMovementDuration();
            }

            return;
        }

        if (now >= nextMovementChange) {

            chooseNewMovement();

            /*
             * 15% chance to idle.
             */
            if (RANDOM.nextInt(100) < 15) {

                idling = true;

                nextIdleChange =
                        now + randomIdleDuration();

                clearMovementKeys();

                return;
            }
        }

        applyMovement();

        if (settings.bot.movement.random_look) {
            randomLook();
        }

        if (settings.bot.movement.jump_over_obstacles) {
            tryJump();
        }
    }

    private static void chooseNewMovement() {

        long now =
                System.currentTimeMillis();

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

        if (client.player != null) {

            client.player.setYaw(
                    movementYaw
            );
        }

        System.out.println(
                "[ChunkyBot] New direction: "
                        + movementYaw
        );
    }

    private static void applyMovement() {

        if (client.player == null) {
            return;
        }

        client.options.forwardKey
                .setPressed(false);

        client.options.backKey
                .setPressed(false);

        client.options.leftKey
                .setPressed(false);

        client.options.rightKey
                .setPressed(false);

        if (!moving) {
            return;
        }

        client.options.forwardKey
                .setPressed(true);

        if (strafing) {

            if (RANDOM.nextBoolean()) {

                client.options.leftKey
                        .setPressed(true);

            } else {

                client.options.rightKey
                        .setPressed(true);
            }
        }

        client.options.sprintKey
                .setPressed(
                        settings.bot.movement.sprint
                                && sprinting
                );
    }

    private static void randomLook() {

        if (client.player == null) {
            return;
        }

        client.player.setPitch(-90.0f);

        if (RANDOM.nextInt(100) < 8) {

            float yaw =
                    client.player.getYaw();

            float change =
                    RANDOM.nextFloat() * 80.0f
                            - 40.0f;

            client.player.setYaw(
                    yaw + change
            );
        }
    }

    private static void tryJump() {

        if (client.player == null) {
            return;
        }

        if (client.player.isOnGround()
                && RANDOM.nextInt(100) < 3) {

            client.options.jumpKey
                    .setPressed(true);

            jumping = true;

        } else {

            client.options.jumpKey
                    .setPressed(false);

            jumping = false;
        }
    }

    // ============================================================
    // HOSTILE MOB DETECTION
    // ============================================================

    private static void scanForHostiles() {

        if (client.world == null
                || client.player == null) {

            nearestHostile = null;

            return;
        }

        double radius =
                settings.bot.mob_avoidance
                        .detection_radius;

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
                    distanceTo(
                            entityPosition(entity)
                    );

            if (distance < closestDistance) {

                closestDistance =
                        distance;

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
                entityPosition(
                        nearestHostile
                );

        double dx =
                playerPos.x
                        - hostilePos.x;

        double dz =
                playerPos.z
                        - hostilePos.z;

        double length =
                Math.sqrt(
                        dx * dx
                                + dz * dz
                );

        if (length < 0.001) {

            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }

        dx /= length;
        dz /= length;

        float escapeYaw =
                (float) Math.toDegrees(
                        Math.atan2(
                                -dx,
                                dz
                        )
                );

        client.player.setYaw(
                escapeYaw
        );

        client.options.forwardKey
                .setPressed(true);

        client.options.sprintKey
                .setPressed(true);

        if (client.player.isOnGround()) {

            client.options.jumpKey
                    .setPressed(true);
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

        long now =
                System.currentTimeMillis();

        long interval =
                Math.max(
                        1,
                        settings.bot.block_placing
                                .interval_seconds
                ) * 1000L;

        if (now < nextBlockPlace) {
            return;
        }

        nextBlockPlace =
                now + interval;

        int slot =
                Math.max(
                        0,
                        Math.min(
                                8,
                                settings.bot
                                        .block_placing
                                        .hotbar_slot
                        )
                );

        ItemStack stack =
                client.player
                        .getInventory()
                        .getStack(slot);

        if (settings.bot.block_placing
                .only_if_block_in_slot) {

            if (!(stack.getItem()
                    instanceof BlockItem)) {

                return;
            }
        }

        client.player
                .getInventory()
                .setSelectedSlot(slot);

        BlockPos below =
                client.player
                        .getBlockPos()
                        .down();

        BlockState belowState =
                client.world
                        .getBlockState(below);

        if (belowState.isAir()) {
            return;
        }

        Vec3d playerPos =
                playerPosition();

        float yaw =
                client.player.getYaw();

        double radians =
                Math.toRadians(yaw);

        double forwardX =
                -Math.sin(radians);

        double forwardZ =
                Math.cos(radians);

        int placeAhead =
                Math.max(
                        1,
                        settings.bot.block_placing
                                .place_ahead
                );

        BlockPos target =
                BlockPos.ofFloored(
                        playerPos.x
                                + forwardX
                                * placeAhead,
                        playerPos.y,
                        playerPos.z
                                + forwardZ
                                * placeAhead
                );

        BlockState targetState =
                client.world
                        .getBlockState(target);

        if (!targetState.isAir()) {
            return;
        }

        BlockPos support =
                target.down();

        BlockState supportState =
                client.world
                        .getBlockState(support);

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

            client.interactionManager
                    .interactBlock(
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

    private static Vec3d entityPosition(
            Entity entity
    ) {

        return new Vec3d(
                entity.getX(),
                entity.getY(),
                entity.getZ()
        );
    }

    private static double distanceTo(
            Vec3d position
    ) {

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
        }

        client.options.forwardKey
                .setPressed(false);

        client.options.backKey
                .setPressed(false);

        client.options.leftKey
                .setPressed(false);

        client.options.rightKey
                .setPressed(false);

        client.options.jumpKey
                .setPressed(false);

        client.options.sprintKey
                .setPressed(false);

        moving = false;
        strafing = false;
        sprinting = false;
        jumping = false;
    }

    // ============================================================
    // RANDOM TIMERS
    // ============================================================

    private static long randomMovementDuration() {

        int min =
                Math.max(
                        1,
                        settings.bot.movement
                                .patrol_min_seconds
                );

        int max =
                Math.max(
                        min,
                        settings.bot.movement
                                .patrol_max_seconds
                );

        int seconds =
                min + RANDOM.nextInt(
                        max - min + 1
                );

        return seconds * 1000L;
    }

    private static long randomIdleDuration() {

        int min =
                Math.max(
                        1,
                        settings.bot.movement
                                .idle_min_seconds
                );

        int max =
                Math.max(
                        min,
                        settings.bot.movement
                                .idle_max_seconds
                );

        int seconds =
                min + RANDOM.nextInt(
                        max - min + 1
                );

        return seconds * 1000L;
    }

    // ============================================================
    // RENDER HEALTH SERVER
    // ============================================================

    private static void startRenderHealthServer() {

        if (healthThread != null
                && healthThread.isAlive()) {

            return;
        }

        healthThread =
                new Thread(
                        () -> {

                            String portString =
                                    System.getenv("PORT");

                            int port = 10000;

                            if (portString != null) {

                                try {

                                    port =
                                            Integer.parseInt(
                                                    portString
                                            );

                                } catch (
                                        NumberFormatException ignored
                                ) {

                                    System.out.println(
                                            "[ChunkyBot] Invalid PORT; using 10000."
                                    );
                                }
                            }

                            try (
                                    ServerSocket
                                            serverSocket =
                                            new ServerSocket()
                            ) {

                                serverSocket
                                        .setReuseAddress(
                                                true
                                        );

                                serverSocket.bind(
                                        new InetSocketAddress(
                                                "0.0.0.0",
                                                port
                                        )
                                );

                                System.out.println(
                                        "[ChunkyBot] Health server listening on "
                                                + port
                                );

                                while (true) {

                                    try (
                                            Socket socket =
                                                    serverSocket
                                                            .accept()
                                    ) {

                                        handleHealthRequest(
                                                socket
                                        );

                                    } catch (
                                            Exception e
                                    ) {

                                        System.err.println(
                                                "[ChunkyBot] Health request error:"
                                        );

                                        e.printStackTrace();
                                    }
                                }

                            } catch (
                                    Exception e
                            ) {

                                System.err.println(
                                        "[ChunkyBot] Failed to start health server:"
                                );

                                e.printStackTrace();
                            }

                        },
                        "Render-Health-Server"
                );

        healthThread.setDaemon(true);

        healthThread.start();
    }

    private static void handleHealthRequest(
            Socket socket
    ) {

        try {

            socket.setSoTimeout(2000);

            InputStream input =
                    socket.getInputStream();

            byte[] buffer =
                    new byte[1024];

            input.read(buffer);

            String body =
                    "ChunkyBot is running\n";

            byte[] bodyBytes =
                    body.getBytes(
                            StandardCharsets.UTF_8
                    );

            OutputStream output =
                    socket.getOutputStream();

            String response =
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/plain\r\n"
                            + "Content-Length: "
                            + bodyBytes.length
                            + "\r\n"
                            + "Connection: close\r\n"
                            + "\r\n";

            output.write(
                    response.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            output.write(bodyBytes);

            output.flush();

        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // CONFIGURATION CLASSES
    // ============================================================

    public static class BotSettings {

        public Server server =
                new Server();

        public Account account =
                new Account();

        public Bot bot =
                new Bot();
    }

    public static class Server {

        public String address =
                "localhost";

        public int port =
                25565;

        public String minecraft_version =
                "1.21.11";

        public boolean auto_reconnect =
                true;

        public int reconnect_delay_seconds =
                10;

        public int connect_delay_seconds =
                3;
    }

    public static class Account {

        public String username =
                "ChunkyBot";
    }

    public static class Bot {

        public boolean auto_reconnect =
                true;

        public int reconnect_delay_seconds =
                10;

        public int connect_delay_seconds =
                3;

        public Movement movement =
                new Movement();

        public MobAvoidance mob_avoidance =
                new MobAvoidance();

        public BlockPlacing block_placing =
                new BlockPlacing();
    }

    public static class Movement {

        public boolean enabled =
                true;

        public double patrol_radius =
                20.0;

        public boolean sprint =
                true;

        public boolean jump_over_obstacles =
                true;

        public boolean random_strafe =
                true;

        public boolean random_look =
                true;

        public int patrol_min_seconds =
                6;

        public int patrol_max_seconds =
                10;

        public int idle_min_seconds =
                2;

        public int idle_max_seconds =
                5;
    }

    public static class MobAvoidance {

        public boolean enabled =
                true;

        public double detection_radius =
                16.0;

        public double flee_radius =
                11.0;

        public double emergency_radius =
                6.0;
    }

    public static class BlockPlacing {

        public boolean enabled =
                false;

        public int hotbar_slot =
                0;

        public int interval_seconds =
                10;

        public int place_ahead =
                1;

        public boolean only_if_block_in_slot =
                true;
    }
}
