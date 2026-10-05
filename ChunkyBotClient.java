    }

    private static void startRenderHealthServer() {
        String portString = System.getenv("PORT");

        if (portString == null || portString.isBlank()) {
            System.out.println("[Render] PORT not set; health server disabled.");
            return;
        }

        int port = Integer.parseInt(portString);

        Thread healthThread = new Thread(() -> {
            try (java.net.ServerSocket server =
                         new java.net.ServerSocket(port)) {

                System.out.println(
                        "[Render] Health server listening on port " + port
                );

                while (true) {
                    try (java.net.Socket socket = server.accept();
                         java.io.BufferedReader in =
                                 new java.io.BufferedReader(
                                         new java.io.InputStreamReader(
                                                 socket.getInputStream()
                                         )
                                 );
                         java.io.OutputStream out =
                                 socket.getOutputStream()) {

                        String requestLine = in.readLine();

                        if (requestLine == null) {
                            continue;
                        }

                        String response =
                                "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: 19\r\n" +
                                "Connection: close\r\n" +
                                "\r\n" +
                                "Chunky Bot is alive";

                        out.write(
                                response.getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8
                                )
                        );

                        out.flush();

                    } catch (Exception ignored) {
                        // Ignore individual health-check connection errors.
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
}
