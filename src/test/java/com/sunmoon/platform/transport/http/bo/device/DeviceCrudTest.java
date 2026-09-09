package com.sunmoon.platform.transport.http.bo.device;

import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.domain.device.DeviceRepository;
import com.sunmoon.platform.domain.operator.Action;
import com.sunmoon.platform.domain.operator.OperatorRepository;
import com.sunmoon.platform.domain.operator.OperatorScreenPermission;
import com.sunmoon.platform.domain.operator.Screen;
import com.sunmoon.platform.infrastructure.auth.InMemorySessionStore;
import com.sunmoon.platform.infrastructure.auth.PasswordHasher;
import com.sunmoon.platform.infrastructure.auth.SessionStore;
import com.sunmoon.platform.infrastructure.persistence.InMemoryDeviceRepository;
import com.sunmoon.platform.infrastructure.persistence.InMemoryOperatorRepository;
import com.sunmoon.platform.core.ListenerSpec;
import com.sunmoon.platform.transport.http.HttpServerInitializer;
import com.sunmoon.platform.transport.http.RestEndpoint;
import com.sunmoon.platform.transport.http.RouteKey;
import com.sunmoon.platform.transport.http.bo.AuthorizedEndpoint;
import com.sunmoon.platform.transport.http.bo.LoginEndpoint;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live HTTP against the real Netty server — Device master-data CRUD plus per-action permission enforcement. */
class DeviceCrudTest {

    private static final int HTTP_PORT = 18083;
    private static final int SOCKET_PORT = 19093;

    @BeforeAll
    static void startRuntime() throws InterruptedException {
        InMemoryOperatorRepository operatorRepository = new InMemoryOperatorRepository();
        DeviceRepository deviceRepository = new InMemoryDeviceRepository();
        SessionStore sessionStore = new InMemorySessionStore();

        operatorRepository.create("dev-admin", PasswordHasher.hash("dev-admin-pass"), "Device Admin", true);
        var editor = operatorRepository.create("dev-editor", PasswordHasher.hash("dev-editor-pass"), "Device Editor", false);
        // View + create, but deliberately no delete — proves the four action bits are independent.
        operatorRepository.grantPermission(editor.id(),
                new OperatorScreenPermission(Screen.DEVICE, true, true, false, false));

        Map<RouteKey, RestEndpoint> routes = Map.of(
                new RouteKey(HttpMethod.POST, "/bo/auth/login"), new LoginEndpoint(operatorRepository, sessionStore, false),
                new RouteKey(HttpMethod.GET, "/bo/devices"),
                new AuthorizedEndpoint(Screen.DEVICE, Action.VIEW, sessionStore, new ListDeviceEndpoint(deviceRepository)),
                new RouteKey(HttpMethod.POST, "/bo/devices"),
                new AuthorizedEndpoint(Screen.DEVICE, Action.CREATE, sessionStore, new CreateDeviceEndpoint(deviceRepository)),
                new RouteKey(HttpMethod.PUT, "/bo/devices"),
                new AuthorizedEndpoint(Screen.DEVICE, Action.SAVE, sessionStore, new SaveDeviceEndpoint(deviceRepository)),
                new RouteKey(HttpMethod.DELETE, "/bo/devices"),
                new AuthorizedEndpoint(Screen.DEVICE, Action.DELETE, sessionStore, new DeleteDeviceEndpoint(deviceRepository)));

        CoreRuntime runtime = new CoreRuntime(List.of(new ListenerSpec("test-device", HTTP_PORT, new HttpServerInitializer(
                        routes, null, java.util.concurrent.Executors.newFixedThreadPool(4)))));
        Thread runtimeThread = new Thread(() -> {
            try {
                runtime.start();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "test-device-runtime");
        runtimeThread.setDaemon(true);
        runtimeThread.start();
        awaitPortOpen(HTTP_PORT);
    }

    @Test
    void fullCrudCycleAsAdmin() throws Exception {
        HttpClient client = cookieAwareClient();
        login(client, "dev-admin", "dev-admin-pass");

        HttpResponse<String> create = send(client, "POST", "/bo/devices",
                "{\"deviceId\":\"POS-001\",\"name\":\"1번 계산대\",\"deviceType\":\"POS\",\"location\":\"본점 1층\",\"active\":true}");
        assertEquals(201, create.statusCode());

        assertEquals(409, send(client, "POST", "/bo/devices",
                "{\"deviceId\":\"POS-001\",\"name\":\"중복\",\"deviceType\":\"POS\",\"location\":\"x\",\"active\":true}").statusCode());

        HttpResponse<String> save = send(client, "PUT", "/bo/devices",
                "{\"deviceId\":\"POS-001\",\"name\":\"1번 계산대(수정)\",\"deviceType\":\"POS\",\"location\":\"본점 2층\",\"active\":false}");
        assertEquals(200, save.statusCode());
        assertTrue(save.body().contains("본점 2층"));

        HttpResponse<String> listByType = get(client, "/bo/devices?type=POS");
        assertEquals(200, listByType.statusCode());
        assertTrue(listByType.body().contains("1번 계산대(수정)"));

        assertEquals(204, send(client, "DELETE", "/bo/devices", "{\"deviceId\":\"POS-001\"}").statusCode());
        assertEquals("[]", get(client, "/bo/devices").body());
    }

    @Test
    void saveOnMissingDeviceReturns404() throws Exception {
        HttpClient client = cookieAwareClient();
        login(client, "dev-admin", "dev-admin-pass");

        assertEquals(404, send(client, "PUT", "/bo/devices",
                "{\"deviceId\":\"NOPE\",\"name\":\"x\",\"deviceType\":\"POS\",\"location\":null,\"active\":true}").statusCode());
    }

    @Test
    void blankDeviceIdIsRejected() throws Exception {
        HttpClient client = cookieAwareClient();
        login(client, "dev-admin", "dev-admin-pass");

        HttpResponse<String> response = send(client, "POST", "/bo/devices",
                "{\"deviceId\":\"\",\"name\":\"x\",\"deviceType\":\"POS\",\"location\":null,\"active\":true}");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("must not be blank"));
    }

    @Test
    void editorCanCreateButNotDelete() throws Exception {
        HttpClient client = cookieAwareClient();
        login(client, "dev-editor", "dev-editor-pass");

        assertEquals(201, send(client, "POST", "/bo/devices",
                "{\"deviceId\":\"KIOSK-001\",\"name\":\"키오스크\",\"deviceType\":\"KIOSK\",\"location\":null,\"active\":true}").statusCode());
        assertEquals(200, get(client, "/bo/devices").statusCode());
        assertEquals(403, send(client, "DELETE", "/bo/devices", "{\"deviceId\":\"KIOSK-001\"}").statusCode());
        assertEquals(403, send(client, "PUT", "/bo/devices",
                "{\"deviceId\":\"KIOSK-001\",\"name\":\"x\",\"deviceType\":\"KIOSK\",\"location\":null,\"active\":true}").statusCode());
    }

    private static HttpResponse<String> send(HttpClient client, String method, String path, String jsonBody) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + path))
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/auth/login"))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    private static HttpClient cookieAwareClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
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
