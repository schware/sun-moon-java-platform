package com.sunmoon.platform.transport.http;

import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.ListenerSpec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the property docs/adr/0010 exists for: endpoint code must not run
 * on a Netty event-loop thread, because a blocking call there stalls every
 * other connection that thread serves.
 */
class BlockingWorkOffloadTest {

    private static final int HTTP_PORT = 18084;
    private static final int SOCKET_PORT = 19094;
    private static final int WORKER_THREADS = 4;

    private static final AtomicReference<String> handlerThreadName = new AtomicReference<>();
    private static final CountDownLatch releaseSlowEndpoint = new CountDownLatch(1);

    @BeforeAll
    static void startRuntime() throws InterruptedException {
        RestEndpoint threadNameEndpoint = request -> {
            handlerThreadName.set(Thread.currentThread().getName());
            return JsonResponses.of(HttpResponseStatus.OK, Map.of("thread", Thread.currentThread().getName()));
        };

        // Blocks until the test releases it — stands in for a slow JDBC call.
        RestEndpoint slowEndpoint = request -> {
            try {
                releaseSlowEndpoint.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return JsonResponses.of(HttpResponseStatus.OK, Map.of("slow", true));
        };

        RestEndpoint boomEndpoint = request -> {
            throw new IllegalStateException("boom");
        };

        Map<RouteKey, RestEndpoint> routes = Map.of(
                new RouteKey(HttpMethod.GET, "/thread"), threadNameEndpoint,
                new RouteKey(HttpMethod.GET, "/slow"), slowEndpoint,
                new RouteKey(HttpMethod.GET, "/boom"), boomEndpoint);

        CoreRuntime runtime = new CoreRuntime(List.of(
                new ListenerSpec("test-offload", HTTP_PORT, new HttpServerInitializer(
                        routes, null, Executors.newFixedThreadPool(WORKER_THREADS,
                                Thread.ofPlatform().name("platform-worker-", 0).daemon(true).factory())))));
        Thread runtimeThread = new Thread(() -> {
            try {
                runtime.start();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "test-offload-runtime");
        runtimeThread.setDaemon(true);
        runtimeThread.start();
        awaitPortOpen(HTTP_PORT);
    }

    @Test
    void endpointsRunOnAWorkerThreadNotTheEventLoop() throws Exception {
        HttpResponse<String> response = get("/thread");

        assertEquals(200, response.statusCode());
        String thread = handlerThreadName.get();
        assertTrue(thread.startsWith("platform-worker-"), "expected a worker thread, got: " + thread);
        assertFalse(thread.contains("nioEventLoopGroup"), "endpoint ran on the event loop: " + thread);
    }

    @Test
    void aBlockedEndpointDoesNotStallOtherConnections() throws Exception {
        Thread slowCaller = new Thread(() -> {
            try {
                get("/slow");
            } catch (Exception ignored) {
                // the assertion that matters is on the other connection
            }
        });
        slowCaller.start();
        Thread.sleep(200); // let /slow reach the endpoint and block a worker

        try {
            // A different connection must still be served while /slow is stuck.
            assertEquals(200, get("/thread").statusCode());
        } finally {
            releaseSlowEndpoint.countDown();
            slowCaller.join(5_000);
        }
    }

    @Test
    void anEndpointThrowingBecomes500RatherThanADroppedConnection() throws Exception {
        assertEquals(500, get("/boom").statusCode());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + path)).build(),
                HttpResponse.BodyHandlers.ofString());
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
