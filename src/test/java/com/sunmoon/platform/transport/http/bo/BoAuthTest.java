package com.sunmoon.platform.transport.http.bo;

import com.sunmoon.platform.core.CoreRuntime;
import com.sunmoon.platform.core.RuntimeConfig;
import com.sunmoon.platform.domain.operator.Action;
import com.sunmoon.platform.domain.operator.OperatorRepository;
import com.sunmoon.platform.domain.operator.OperatorScreenPermission;
import com.sunmoon.platform.domain.operator.Screen;
import com.sunmoon.platform.infrastructure.auth.InMemorySessionStore;
import com.sunmoon.platform.infrastructure.auth.PasswordHasher;
import com.sunmoon.platform.infrastructure.auth.SessionStore;
import com.sunmoon.platform.infrastructure.persistence.InMemoryOperatorRepository;
import com.sunmoon.platform.transport.http.RestEndpoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the real /bo/auth/* endpoints and an AuthorizedEndpoint-wrapped test route over real HTTP — same "small, verified steps" discipline as CoreRuntimeTransportsTest. */
class BoAuthTest {

    private static final int HTTP_PORT = 18081;
    private static final int SOCKET_PORT = 19091;

    private static OperatorRepository operatorRepository;
    private static SessionStore sessionStore;

    @BeforeAll
    static void startRuntime() throws InterruptedException {
        operatorRepository = new InMemoryOperatorRepository();
        sessionStore = new InMemorySessionStore();

        operatorRepository.create("admin", PasswordHasher.hash("correct-horse"), "Super Admin", true);
        operatorRepository.create("viewer", PasswordHasher.hash("viewer-pass"), "No Perms", false);

        RestEndpoint protectedEndpoint = request -> com.sunmoon.platform.transport.http.JsonResponses.of(
                io.netty.handler.codec.http.HttpResponseStatus.OK, Map.of("ok", true));

        Map<String, RestEndpoint> routes = Map.of(
                "/bo/auth/login", new LoginEndpoint(operatorRepository, sessionStore),
                "/bo/auth/logout", new LogoutEndpoint(sessionStore),
                "/bo/auth/me", new MeEndpoint(sessionStore),
                "/bo/common-code", new AuthorizedEndpoint(Screen.COMMON_CODE, Action.VIEW, sessionStore, protectedEndpoint)
        );

        CoreRuntime runtime = new CoreRuntime(RuntimeConfig.forTest(HTTP_PORT, SOCKET_PORT), routes);
        Thread runtimeThread = new Thread(() -> {
            try {
                runtime.start();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "test-bo-runtime");
        runtimeThread.setDaemon(true);
        runtimeThread.start();
        awaitPortOpen(HTTP_PORT);
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = login(client, "admin", "wrong-password");
        assertEquals(401, response.statusCode());
    }

    @Test
    void protectedRouteRequiresLogin() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/common-code")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void superAdminBypassesPermissionCheck() throws Exception {
        HttpClient client = cookieAwareClient();
        HttpResponse<String> loginResponse = login(client, "admin", "correct-horse");
        assertEquals(200, loginResponse.statusCode());
        assertTrue(loginResponse.body().contains("\"superAdmin\":true"));

        HttpResponse<String> meResponse = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/auth/me")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, meResponse.statusCode());

        HttpResponse<String> protectedResponse = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/common-code")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, protectedResponse.statusCode());
    }

    @Test
    void operatorWithoutPermissionGetsForbidden() throws Exception {
        HttpClient client = cookieAwareClient();
        HttpResponse<String> loginResponse = login(client, "viewer", "viewer-pass");
        assertEquals(200, loginResponse.statusCode());

        HttpResponse<String> protectedResponse = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/common-code")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, protectedResponse.statusCode());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        HttpClient client = cookieAwareClient();
        login(client, "admin", "correct-horse");

        HttpResponse<String> logoutResponse = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/auth/logout"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, logoutResponse.statusCode());

        HttpResponse<String> meResponse = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/auth/me")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, meResponse.statusCode());
    }

    private static HttpResponse<String> login(HttpClient client, String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/bo/auth/login"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
            } catch (java.io.IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("port " + port + " never opened");
    }
}
