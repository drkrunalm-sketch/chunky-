package com.shaurya.chunkybot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class ChunkyBotClient implements ClientModInitializer {

    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final Random RANDOM = new Random();

    private static Settings settings;

    private static long connectedAt = 0;
    private static long nextMovementChange = 0;
    private static long nextIdleChange = 0;
    private static long nextBlockPlace = 0;
    private static long lastMobScan = 0;
    private static long lastReconnectAttempt = 0;

    private static boolean moving = false;
    private static boolean strafing = false;
    private static boolean idling = false;
    private static boolean fleeing = false;

    private static float movementYaw = 0.0f;
    private static float currentStrafe = 0.0f;

    private static Vec3d patrolOrigin = null;
    private static HostileEntity nearestHostile = null;

    @Override
    public void onInitializeClient() {

        System.out.println("========================================");
        System.out.println(" Fabric 1.21.11 Offline Chunky Bot");
        System.out.println("========================================");
        System.out.println("No Microsoft authentication is used.");

        settings = loadSettings();

        if (settings == null) {
            System.err.println("[Bot] Could not load bot-settings.json.");
            return;
        }

        System.out.println("[Bot] Server: "
                + settings.server.address + ":"
                + settings.server.port);

        System.out.println("[Bot] Username: "
                + settings.account.username);

        /*
         * Render requires an HTTP port for Web Services.
         * This server does NOT control the Minecraft bot.
         * It only answers Render health checks.
         */
        startRenderHealthServer();

        /*
         * Keep the actual Minecraft startup on the client thread.
         */
        client.execute(() -> {
            connectToServer();
        });
    }

    // ============================================================
    // SETTINGS
    // ============================================================

    private static Settings loadSettings() {

        try {

            var stream = ChunkyBotClient.class
                    .getClassLoader()
                    .getResourceAsStream("bot-settings.json");

            if (stream == null) {
                System.err.println("[Bot] bot-settings.json not found.");
                return null;
            }

            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         stream,
                                         StandardCharsets.UTF_8))) {

                Gson gson = new GsonBuilder().create();

                return gson.fromJson(reader, Settings.class);
            }

        } catch (Exception e) {

            System.err.println(
                    "[Bot] Failed to load settings: "
                            + e.getMessage()
            );

            e.printStackTrace();

            return null;
        }
    }

    // ============================================================
    // SERVER CONNECTION
    // ============================================================

    private static void connectToServer() {

        if (client.world != null) {
            return;
        }

        if (settings == null) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastReconnectAttempt <
                settings.server.connect_delay_seconds * 1000L) {
            return;
        }

        lastReconnectAttempt = now;

        String addressString =
                settings.server.address + ":" + settings.server.port;

        System.out.println(
                "[Bot] Connecting to " + addressString
        );

        try {

            ServerAddress address =
                    ServerAddress.parse(addressString);

            ServerInfo serverInfo =
                    new ServerInfo(
                            "Chunky Bot Server",
                            addressString,
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
                    "[Bot] Connection attempt failed: "
                            + e.getMessage()
            );

            scheduleReconnect();
        }
    }

    private static void scheduleReconnect() {

        lastReconnectAttempt =
                System.currentTimeMillis()
                        + settings.server.reconnect_delay_seconds * 1000L;
    }

    // ============================================================
    // MAIN TICK
    // ============================================================

    private static void tick() {

        if (client.player == null ||
                client.world == null) {

            stopMovement();

            if (settings.bot.auto_reconnect) {
                tryReconnect();
            }

            return;
        }

        if (connectedAt == 0) {

            connectedAt =
                    System.currentTimeMillis();

            patrolOrigin =
                    client.player.getPos();

            movementYaw =
                    client.player.getYaw();

            System.out.println(
                    "[Bot] Connected successfully."
            );
        }

        /*
         * Resource-saving camera:
         *
         * Looking straight upward means the client renders
         * considerably less useful world geometry.
         */
        client.player.setPitch(-90.0f);

        /*
         * Scan hostile mobs only every 10 ticks.
         */
        if (client.world.getTime() - lastMobScan >= 10) {

            lastMobScan =
                    client.world.getTime();

            scanForHostiles();
        }

        if (settings.bot.movement.enabled) {

            if (fleeing) {
                fleeFromHostile();
            } else {
                patrolMovement();
            }

        } else {

            stopMovement();
        }

        if (settings.bot.block_placing.enabled) {

            long now =
                    System.currentTimeMillis();

            if (now >= nextBlockPlace) {

                placeConfiguredBlock();

                nextBlockPlace =
                        now
                                + settings.bot.block_placing.interval_seconds
                                * 1000L;
            }
        }
    }

    // ============================================================
    // MOVEMENT / PATROL
    // ============================================================

    private static void patrolMovement() {

        long now =
                System.currentTimeMillis();

        /*
         * Check whether the bot has wandered too far.
         */
        if (patrolOrigin != null) {

            double distance =
                    client.player.getPos()
                            .distanceTo(patrolOrigin);

            double radius =
                    settings.bot.movement.patrol_radius;

            if (distance > radius) {

                moveToward(patrolOrigin);

                moving = true;
                idling = false;

                return;
            }
        }

        /*
         * Idle state.
         */
        if (idling) {

            stopMovement();

            if (now >= nextIdleChange) {

                idling = false;

                nextMovementChange =
                        now
                                + randomBetween(
                                        settings.bot.movement
                                                .patrol_min_seconds,
                                        settings.bot.movement
                                                .patrol_max_seconds
                                ) * 1000L;
            }

            return;
        }

        /*
         * Pick a new direction.
         */
        if (now >= nextMovementChange) {

            moving = true;

            movementYaw =
                    client.player.getYaw()
                            + randomFloat(-140.0f, 140.0f);

            currentStrafe = 0.0f;

            if (settings.bot.movement.random_strafe) {

                currentStrafe =
                        RANDOM.nextBoolean()
                                ? -1.0f
                                : 1.0f;
            }

            nextMovementChange =
                    now
                            + randomBetween(
                                    settings.bot.movement
                                            .patrol_min_seconds,
                                    settings.bot.movement
                                            .patrol_max_seconds
                            ) * 1000L;

            /*
             * Occasionally idle.
             */
            if (RANDOM.nextInt(100) < 12) {

                idling = true;

                nextIdleChange =
                        now
                                + randomBetween(
                                        settings.bot.movement
                                                .idle_min_seconds,
                                        settings.bot.movement
                                                .idle_max_seconds
                                ) * 1000L;

                stopMovement();

                return;
            }
        }

        /*
         * Turn toward patrol direction.
         */
        if (settings.bot.movement.random_look) {

            float currentYaw =
                    client.player.getYaw();

            float difference =
                    wrapDegrees(
                            movementYaw - currentYaw
                    );

            client.player.setYaw(
                    currentYaw + difference * 0.08f
            );
        }

        /*
         * Forward movement.
         */
        client.options.forwardKey.setPressed(true);

        client.options.backKey.setPressed(false);

        /*
         * Random strafing.
         */
        if (settings.bot.movement.random_strafe) {

            if (currentStrafe < 0) {

                client.options.leftKey.setPressed(true);
                client.options.rightKey.setPressed(false);

            } else if (currentStrafe > 0) {

                client.options.rightKey.setPressed(true);
                client.options.leftKey.setPressed(false);

            }

        } else {

            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }

        /*
         * Sprint.
         */
        client.options.sprintKey.setPressed(
                settings.bot.movement.sprint
        );

        /*
         * Jump over obstacles.
         */
        if (settings.bot.movement.jump_over_obstacles) {

            if (client.player.horizontalCollision) {

                client.options.jumpKey.setPressed(true);

            } else {

                client.options.jumpKey.setPressed(false);
            }
        }

        /*
         * Occasional random jump.
         */
        if (RANDOM.nextInt(300) == 0) {

            client.options.jumpKey.setPressed(true);
        }
    }

    // ============================================================
    // RETURN TO PATROL AREA
    // ============================================================

    private static void moveToward(Vec3d target) {

        Vec3d current =
                client.player.getPos();

        double dx =
                target.x - current.x;

        double dz =
                target.z - current.z;

        double yaw =
                Math.toDegrees(
                        Math.atan2(-dx, dz)
                );

        movementYaw =
                (float) yaw;

        client.options.forwardKey.setPressed(true);
        client.options.backKey.setPressed(false);

        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);

        client.options.sprintKey.setPressed(
                settings.bot.movement.sprint
        );

        if (settings.bot.movement.jump_over_obstacles &&
                client.player.horizontalCollision) {

            client.options.jumpKey.setPressed(true);

        } else {

            client.options.jumpKey.setPressed(false);
        }

        float currentYaw =
                client.player.getYaw();

        float difference =
                wrapDegrees(
                        movementYaw - currentYaw
                );

        client.player.setYaw(
                currentYaw + difference * 0.12f
        );
    }

    // ============================================================
    // MOB AVOIDANCE
    // ============================================================

    private static void scanForHostiles() {

        nearestHostile = null;

        if (!settings.bot.mob_avoidance.enabled) {
            fleeing = false;
            return;
        }

        double radius =
                settings.bot.mob_avoidance.detection_radius;

        Box box =
                client.player
                        .getBoundingBox()
                        .expand(radius);

        double closestDistance =
                Double.MAX_VALUE;

        for (HostileEntity hostile :
                client.world.getEntitiesByClass(
                        HostileEntity.class,
                        box,
                        entity -> entity.isAlive()
                )) {

            double distance =
                    hostile.squaredDistanceTo(
                            client.player
                    );

            if (distance < closestDistance) {

                closestDistance = distance;

                nearestHostile = hostile;
            }
        }

        if (nearestHostile == null) {

            fleeing = false;
            return;
        }

        double distance =
                Math.sqrt(closestDistance);

        if (distance <=
                settings.bot.mob_avoidance.flee_radius) {

            fleeing = true;

            System.out.println(
                    "[Bot] Hostile detected at "
                            + String.format("%.1f", distance)
                            + " blocks."
            );

        } else {

            fleeing = false;
        }
    }

    private static void fleeFromHostile() {

        if (nearestHostile == null ||
                !nearestHostile.isAlive()) {

            fleeing = false;
            return;
        }

        Vec3d bot =
                client.player.getPos();

        Vec3d mob =
                nearestHostile.getPos();

        double dx =
                bot.x - mob.x;

        double dz =
                bot.z - mob.z;

        double length =
                Math.sqrt(dx * dx + dz * dz);

        if (length < 0.001) {

            dx = 1;
            dz = 0;
            length = 1;
        }

        dx /= length;
        dz /= length;

        Vec3d escapeTarget =
                bot.add(
                        dx * 20.0,
                        0,
                        dz * 20.0
                );

        moveToward(escapeTarget);

        /*
         * Emergency sprint + jumping.
         */
        client.options.sprintKey.setPressed(true);

        if (settings.bot.movement.jump_over_obstacles) {

            client.options.jumpKey.setPressed(true);
        }

        double emergencyRadius =
                settings.bot.mob_avoidance.emergency_radius;

        double distance =
                client.player.distanceTo(
                        nearestHostile
                );

        if (distance <= emergencyRadius) {

            client.options.sprintKey.setPressed(true);
            client.options.jumpKey.setPressed(true);
        }
    }

    // ============================================================
    // BLOCK PLACEMENT
    // ============================================================

    private static void placeConfiguredBlock() {

        if (client.player == null ||
                client.world == null ||
                client.interactionManager == null) {

            return;
        }

        int slot =
                settings.bot.block_placing.hotbar_slot;

        if (slot < 0 || slot > 8) {
            return;
        }

        client.player
                .getInventory()
                .setSelectedSlot(slot);

        ItemStack stack =
                client.player
                        .getInventory()
                        .getStack(slot);

        /*
         * Never create blocks.
         * Only use an actual BlockItem already in the
         * configured hotbar slot.
         */
        if (stack.isEmpty() ||
                !(stack.getItem() instanceof BlockItem)) {

            if (settings.bot.block_placing.only_if_block_in_slot) {
                return;
            }

            return;
        }

        /*
         * Find a block underneath / ahead of the player.
         */
        Vec3d playerPos =
                client.player.getPos();

        float yaw =
                client.player.getYaw();

        double radians =
                Math.toRadians(yaw);

        double forwardX =
                -Math.sin(radians);

        double forwardZ =
                Math.cos(radians);

        int distance =
                settings.bot.block_placing.place_ahead;

        BlockPos target =
                BlockPos.ofFloored(
                        playerPos.x
                                + forwardX * distance,
                        playerPos.y - 1,
                        playerPos.z
                                + forwardZ * distance
                );

        BlockState targetState =
                client.world.getBlockState(target);

        /*
         * We click the top of the target block so that the
         * held block can be placed on it.
         */
        if (!targetState.isAir()) {

            Vec3d hitPos =
                    Vec3d.ofCenter(target)
                            .add(0, 0.5, 0);

            BlockHitResult hit =
                    new BlockHitResult(
                            hitPos,
                            Direction.UP,
                            target,
                            false
                    );

            try {

                client.interactionManager.interactBlock(
                        client.player,
                        Hand.MAIN_HAND,
                        hit
                );

            } catch (Exception e) {

                System.err.println(
                        "[Bot] Block placement failed: "
                                + e.getMessage()
                );
            }
        }
    }

    // ============================================================
    // RECONNECT
    // ============================================================

    private static void tryReconnect() {

        if (!settings.bot.auto_reconnect) {
            return;
        }

        long now =
                System.currentTimeMillis();

        long delay =
                settings.server.reconnect_delay_seconds
                        * 1000L;

        if (now - lastReconnectAttempt < delay) {
            return;
        }

        lastReconnectAttempt = now;

        System.out.println(
                "[Bot] Attempting reconnect..."
        );

        connectToServer();
    }

    // ============================================================
    // STOP MOVEMENT
    // ============================================================

    private static void stopMovement() {

        if (client == null) {
            return;
        }

        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);

        moving = false;
        strafing = false;
    }

    // ============================================================
    // RENDER HEALTH SERVER
    // ============================================================

    private static void startRenderHealthServer() {

        String portString =
                System.getenv("PORT");

        if (portString == null ||
                portString.isBlank()) {

            System.out.println(
                    "[Render] PORT not set; health server disabled."
            );

            return;
        }

        int port;

        try {

            port =
                    Integer.parseInt(portString);

        } catch (NumberFormatException e) {

            System.err.println(
                    "[Render] Invalid PORT: "
                            + portString
            );

            return;
        }

        Thread healthThread =
                new Thread(() -> {

                    try (ServerSocket server =
                                 new ServerSocket(port)) {

                        System.out.println(
                                "[Render] Health server listening on port "
                                        + port
                        );

                        while (true) {

                            try (Socket socket =
                                         server.accept();
                                 BufferedReader in =
                                         new BufferedReader(
                                                 new InputStreamReader(
                                                         socket.getInputStream(),
                                                         StandardCharsets.UTF_8
                                                 )
                                         );
                                 OutputStream out =
                                         socket.getOutputStream()) {

                                String requestLine =
                                        in.readLine();

                                if (requestLine == null) {
                                    continue;
                                }

                                /*
                                 * IMPORTANT:
                                 *
                                 * "Chunky Bot is alive" is exactly
                                 * 19 bytes in UTF-8.
                                 */
                                String body =
                                        "Chunky Bot is alive";

                                byte[] bodyBytes =
                                        body.getBytes(
                                                StandardCharsets.UTF_8
                                        );

                                String response =
                                        "HTTP/1.1 200 OK\r\n"
                                                + "Content-Type: text/plain\r\n"
                                                + "Content-Length: "
                                                + bodyBytes.length
                                                + "\r\n"
                                                + "Connection: close\r\n"
                                                + "\r\n";

                                out.write(
                                        response.getBytes(
                                                StandardCharsets.UTF_8
                                        )
                                );

                                out.write(bodyBytes);

                                out.flush();

                            } catch (Exception ignored) {
                                /*
                                 * Ignore individual Render health
                                 * check connection errors.
                                 */
                            }
                        }

                    } catch (Exception e) {

                        System.err.println(
                                "[Render] Health server failed: "
                                        + e.getMessage()
                        );
                    }

                }, "render-health-server");

        healthThread.setDaemon(true);
        healthThread.start();
    }

    // ============================================================
    // UTILITY
    // ============================================================

    private static int randomBetween(
            int min,
            int max) {

        if (max <= min) {
            return min;
        }

        return min +
                RANDOM.nextInt(
                        max - min + 1
                );
    }

    private static float randomFloat(
            float min,
            float max) {

        return min +
                RANDOM.nextFloat()
                        * (max - min);
    }

    private static float wrapDegrees(
            float degrees) {

        while (degrees >= 180.0f) {
            degrees -= 360.0f;
        }

        while (degrees < -180.0f) {
            degrees += 360.0f;
        }

        return degrees;
    }

    // ============================================================
    // SETTINGS CLASSES
    // ============================================================

    public static class Settings {

        public Server server =
                new Server();

        public Account account =
                new Account();

        public Bot bot =
                new Bot();
    }

    public static class Server {

        public String address =
                "YOUR-ATERNOS-ADDRESS";

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
