package com.sunmoon.platform.transport;

import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.ListenerSpec;
import com.sunmoon.platform.transport.http.HealthCheckEndpoint;
import com.sunmoon.platform.transport.socket.SocketServerInitializer;
import com.sunmoon.platform.transport.ws.WsEchoHandler;
import com.sunmoon.platform.transport.http.HttpServerInitializer;
import com.sunmoon.platform.transport.http.RouteKey;
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
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real Core Runtime on ephemeral-ish test ports and drives each
 * transport with a real client — the same "small, verified end-to-end
 * steps" discipline used for sun-moon-python-platform/sun-moon-c-server,
 * just as JUnit tests instead of manual curl/PowerShell calls.
 */
class CoreRuntimeTransportsTest {

    private static final int HTTP_PORT = 18080;
    private static final int SOCKET_PORT = 19090;

    private static Thread runtimeThread;

    @BeforeAll
    static void startRuntime() throws InterruptedException {
        CoreRuntime runtime = new CoreRuntime(List.of(
                new ListenerSpec("test-http", HTTP_PORT, new HttpServerInitializer(
                        Map.of(new RouteKey(HttpMethod.GET, "/health"), new HealthCheckEndpoint()),
                        WsEchoHandler::new, Executors.newFixedThreadPool(4))),
                new ListenerSpec("test-socket", SOCKET_PORT, new SocketServerInitializer())));
        runtimeThread = new Thread(() -> {
            try {
                runtime.start();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "test-core-runtime");
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
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/health")).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""));
    }

    @Test
    void unknownRestPathReturns404() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/nope")).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }

    @Test
    void webSocketEchoesTextFrames() throws Exception {
        CountDownLatch replyReceived = new CountDownLatch(1);
        StringBuilder received = new StringBuilder();

        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                received.append(data);
                replyReceived.countDown();
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
        };

        WebSocket webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + HTTP_PORT + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);

        webSocket.sendText("hello", true);
        assertTrue(replyReceived.await(5, TimeUnit.SECONDS));
        assertEquals("echo: hello", received.toString());
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void rawSocketEchoesBytes() throws IOException {
        try (Socket socket = new Socket("localhost", SOCKET_PORT)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] payload = "ping".getBytes(StandardCharsets.UTF_8);
            out.write(payload);
            out.flush();

            byte[] buffer = new byte[payload.length];
            int read = in.readNBytes(buffer, 0, buffer.length);

            assertEquals(payload.length, read);
            assertEquals("ping", new String(buffer, StandardCharsets.UTF_8));
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
