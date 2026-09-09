package com.sunmoon.platform.transport.http.bo.commoncode;

import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.domain.commoncode.CommonCodeRepository;
import com.sunmoon.platform.domain.operator.Action;
import com.sunmoon.platform.domain.operator.OperatorRepository;
import com.sunmoon.platform.domain.operator.OperatorScreenPermission;
import com.sunmoon.platform.domain.operator.Screen;
import com.sunmoon.platform.infrastructure.auth.InMemorySessionStore;
import com.sunmoon.platform.infrastructure.auth.PasswordHasher;
import com.sunmoon.platform.infrastructure.auth.SessionStore;
import com.sunmoon.platform.infrastructure.persistence.InMemoryCommonCodeRepository;
import com.sunmoon.platform.infrastructure.persistence.InMemoryOperatorRepository;
import com.sunmoon.platform.transport.http.HttpListenerSpec;
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

/** Live HTTP against the real Netty server — full Common Code CRUD cycle plus per-action permission enforcement (view-only operator can list but not create/save/delete). */
class CommonCodeCrudTest {

    private static final int HTTP_PORT = 18082;
    private static final int SOCKET_PORT = 19092;

    @BeforeAll
    static void startRuntime() throws InterruptedException {
        OperatorRepository operatorRepository = new InMemoryOperatorRepository();
        CommonCodeRepository commonCodeRepository = new InMemoryCommonCodeRepository();
        SessionStore sessionStore = new InMemorySessionStore();

        operatorRepository.create("cc-admin", PasswordHasher.hash("cc-admin-pass"), "Common Code Admin", true);
        var viewOnly = operatorRepository.create("cc-viewer", PasswordHasher.hash("cc-viewer-pass"), "Common Code Viewer", false);
        grantViewOnly(operatorRepository, viewOnly.id());

        Map<RouteKey, RestEndpoint> routes = Map.of(
                new RouteKey(HttpMethod.POST, "/bo/auth/login"), new LoginEndpoint(operatorRepository, sessionStore, false),
                new RouteKey(HttpMethod.GET, "/bo/common-code"),
                new AuthorizedEndpoint(Screen.COMMON_CODE, Action.VIEW, sessionStore, new ListCommonCodeEndpoint(commonCodeRepository)),
                new RouteKey(HttpMethod.POST, "/bo/common-code"),
                new AuthorizedEndpoint(Screen.COMMON_CODE, Action.CREATE, sessionStore, new CreateCommonCodeEndpoint(commonCodeRepository)),
                new RouteKey(HttpMethod.PUT, "/bo/common-code"),
                new AuthorizedEndpoint(Screen.COMMON_CODE, Action.SAVE, sessionStore, new SaveCommonCodeEndpoint(commonCodeRepository)),
                new RouteKey(HttpMethod.DELETE, "/bo/common-code"),
                new AuthorizedEndpoint(Screen.COMMON_CODE, Action.DELETE, sessionStore, new DeleteCommonCodeEndpoint(commonCodeRepository))
        );

        CoreRuntime runtime = new CoreRuntime(
                List.of(new HttpListenerSpec("test-common-code", HTTP_PORT, routes, false)), SOCKET_PORT, 4);
        Thread runtimeThread = new Thread(() -> {
            try {
                runtime.start();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "test-common-code-runtime");
        runtimeThread.setDaemon(true);
        runtimeThread.start();
        awaitPortOpen(HTTP_PORT);
    }

    private static void grantViewOnly(OperatorRepository repository, long operatorId) {
        // InMemoryOperatorRepository doesn't expose a permission-grant method yet (docs/adr/0006's
        // "not yet built" list) — reach into it directly here since this is still a fake, test-only wiring.
        if (repository instanceof InMemoryOperatorRepository inMemory) {
            inMemory.grantPermission(operatorId, new OperatorScreenPermission(Screen.COMMON_CODE, true, false, false, false));
        }
    }

    @Test
    void fullCrudCycleAsAdmin() throws Exception {
        HttpClient client = cookieAwareClient();
        login(client, "cc-admin", "cc-admin-pass");

        HttpResponse<String> createResponse = send(client, "POST", "/bo/common-code",
                "{\"groupCode\":\"ORDER_STATUS\",\"code\":\"PENDING\",\"name\":\"대기\",\"sortOrder\":1,\"active\":true}");
        assertEquals(201, createResponse.statusCode());

        HttpResponse<String> duplicateResponse = send(client, "POST", "/bo/common-code",
                "{\"groupCode\":\"ORDER_STATUS\",\"code\":\"PENDING\",\"name\":\"대기\",\"sortOrder\":1,\"active\":true}");
        assertEquals(409, duplicateResponse.statusCode());

        HttpResponse<String> saveResponse = send(client, "PUT", "/bo/common-code",
                "{\"groupCode\":\"ORDER_STATUS\",\"code\":\"PENDING\",\"name\":\"대기중\",\"sortOrder\":1,\"active\":true}");
        assertEquals(200, saveResponse.statusCode());
        assertTrue(saveResponse.body().contains("대기중"));

        HttpResponse<String> listResponse = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/common-code?group=ORDER_STATUS")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResponse.statusCode());
        assertTrue(listResponse.body().contains("대기중"));

        HttpResponse<String> deleteResponse = send(client, "DELETE", "/bo/common-code",
                "{\"groupCode\":\"ORDER_STATUS\",\"code\":\"PENDING\"}");
        assertEquals(204, deleteResponse.statusCode());

        HttpResponse<String> listAfterDelete = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/common-code?group=ORDER_STATUS")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("[]", listAfterDelete.body());
    }

    @Test
    void saveOnMissingRowReturns404() throws Exception {
        HttpClient client = cookieAwareClient();
        login(client, "cc-admin", "cc-admin-pass");

        HttpResponse<String> response = send(client, "PUT", "/bo/common-code",
                "{\"groupCode\":\"NOPE\",\"code\":\"NOPE\",\"name\":\"x\",\"sortOrder\":0,\"active\":true}");
        assertEquals(404, response.statusCode());
    }

    @Test
    void viewOnlyOperatorCanListButNotCreate() throws Exception {
        HttpClient client = cookieAwareClient();
        login(client, "cc-viewer", "cc-viewer-pass");

        HttpResponse<String> listResponse = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/common-code")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResponse.statusCode());

        HttpResponse<String> createResponse = send(client, "POST", "/bo/common-code",
                "{\"groupCode\":\"X\",\"code\":\"Y\",\"name\":\"z\",\"sortOrder\":0,\"active\":true}");
        assertEquals(403, createResponse.statusCode());
    }

    private static HttpResponse<String> send(HttpClient client, String method, String path, String jsonBody) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + path))
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void login(HttpClient client, String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/auth/login"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
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
