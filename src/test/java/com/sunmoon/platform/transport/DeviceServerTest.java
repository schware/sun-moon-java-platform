package com.sunmoon.platform.transport;

import com.sunmoon.platform.api.TerminalEndpoints;
import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.ListenerSpec;
import com.sunmoon.platform.domain.terminal.AcceptKnownFormatDirectory;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.transport.http.HealthCheckEndpoint;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.http.RouteKey;
import com.sunmoon.platform.transport.socket.SocketServerInitializer;
import com.sunmoon.platform.transport.ws.DeviceServerInitializer;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real Device Server and drives it with real clients — the same
 * discipline used for the transports before it, now covering the thing
 * that actually matters here: a terminal is identified at the handshake,
 * and one that cannot be identified never gets a socket.
 */
class DeviceServerTest {

    private static final int HTTP_PORT = 18087;
    private static final int SOCKET_PORT = 19011;

    private static Thread runtimeThread;
    private static TerminalRegistry registry;

    @BeforeAll
    static void startRuntime() throws InterruptedException {
        registry = new TerminalRegistry();
        TerminalEndpoints endpoints = new TerminalEndpoints(registry);

        Map<RouteKey, RestEndpoint> routes = Map.of(
                new RouteKey(HttpMethod.GET, "/health"), new HealthCheckEndpoint(),
                new RouteKey(HttpMethod.GET, "/terminals"), endpoints.list(),
                new RouteKey(HttpMethod.POST, "/terminals/push"), endpoints.pushToDevice(),
                new RouteKey(HttpMethod.POST, "/terminals/broadcast"), endpoints.broadcastToType());

        CoreRuntime runtime = new CoreRuntime(List.of(
                new ListenerSpec("test-device-server", HTTP_PORT, new DeviceServerInitializer(
                        routes, Executors.newFixedThreadPool(4), new AcceptKnownFormatDirectory(), registry)),
                new ListenerSpec("test-socket", SOCKET_PORT, new SocketServerInitializer())));

        runtimeThread = new Thread(() -> {
            try {
                runtime.start();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "test-device-server-runtime");
        runtimeThread.setDaemon(true);
        runtimeThread.start();
        awaitPortOpen(HTTP_PORT);
        awaitPortOpen(SOCKET_PORT);
    }

    @AfterAll
    static void stopRuntime() {
        runtimeThread.interrupt();
    }

    @Test
    void restHealthEndpointRespondsOk() throws Exception {
        HttpResponse<String> response = get("/health");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""));
    }

    @Test
    void aTerminalIsWelcomedByNameOnceItIdentifiesItself() throws Exception {
        Frames frames = new Frames();
        WebSocket socket = connect("pos-01", "POS", frames);

        assertTrue(frames.await(), "no WELCOME frame arrived");
        assertTrue(frames.text().contains("\"deviceId\":\"pos-01\""), frames.text());
        assertTrue(frames.text().contains("\"terminal\":\"POS\""), frames.text());

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    /**
     * The point of identifying at handshake: a client that gets it wrong is
     * refused with a status it can read, not left holding a socket that
     * silently never delivers anything.
     */
    @Test
    void aConnectionWithoutADeviceIdIsRefusedAtTheHandshake() {
        assertThrows(ExecutionException.class,
                () -> HttpClient.newHttpClient().newWebSocketBuilder()
                        .buildAsync(URI.create("ws://localhost:" + HTTP_PORT + "/ws"), new Frames())
                        .get(5, TimeUnit.SECONDS));
    }

    @Test
    void anUnknownTerminalTypeIsRefusedToo() {
        assertThrows(ExecutionException.class,
                () -> HttpClient.newHttpClient().newWebSocketBuilder()
                        .buildAsync(URI.create("ws://localhost:" + HTTP_PORT + "/ws?deviceId=pos-02&type=FRIDGE"),
                                new Frames())
                        .get(5, TimeUnit.SECONDS));
    }

    @Test
    void aConnectedTerminalIsListedAndReachable() throws Exception {
        Frames frames = new Frames();
        WebSocket socket = connect("kds-01", "KDS", frames);
        assertTrue(frames.await());

        assertTrue(get("/terminals").body().contains("kds-01"));

        Frames pushed = frames.next();
        HttpResponse<String> response = post("/terminals/push?deviceId=kds-01", "{\"type\":\"TEST\"}");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"delivered\":true"));
        assertTrue(pushed.await(), "the pushed frame never arrived at the terminal");
        assertTrue(pushed.text().contains("TEST"), pushed.text());

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    /** An offline terminal is an ordinary state, so the caller is told so rather than misled. */
    @Test
    void pushingToATerminalThatIsNotConnectedIsReportedAsUndelivered() throws Exception {
        HttpResponse<String> response = post("/terminals/push?deviceId=nobody-here", "{}");

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("\"delivered\":false"), response.body());
    }

    @Test
    void rawSocketStillEchoes() throws IOException {
        try (Socket socket = new Socket("localhost", SOCKET_PORT)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] payload = "ping".getBytes(StandardCharsets.UTF_8);
            out.write(payload);
            out.flush();

            byte[] buffer = new byte[payload.length];
            assertEquals(payload.length, in.readNBytes(buffer, 0, buffer.length));
            assertEquals("ping", new String(buffer, StandardCharsets.UTF_8));
        }
    }

    private static WebSocket connect(String deviceId, String type, Frames frames) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + HTTP_PORT + "/ws?deviceId=" + deviceId + "&type=" + type),
                        frames)
                .get(5, TimeUnit.SECONDS);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Collects one text frame at a time so a test can wait for a specific arrival. */
    private static final class Frames implements WebSocket.Listener {
        private CountDownLatch latch = new CountDownLatch(1);
        private final StringBuilder received = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            received.setLength(0);
            received.append(data);
            latch.countDown();
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        boolean await() throws InterruptedException {
            return latch.await(5, TimeUnit.SECONDS);
        }

        String text() {
            return received.toString();
        }

        Frames next() {
            latch = new CountDownLatch(1);
            return this;
        }
    }

    private static void awaitPortOpen(int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (Socket probe = new Socket("localhost", port)) {
                return;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("port " + port + " never opened");
    }
}
