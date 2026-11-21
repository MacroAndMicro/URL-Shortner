package com.Url_Shortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application with no external services: H2 replaces
 * PostgreSQL, DynamoDB is in-memory, and Redis, MongoDB and Kafka are
 * unreachable. Redirects and the API must keep working in that state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dynamodb.enabled=false",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=500ms",
        "spring.mongodb.uri=mongodb://localhost:1/urlshortener?serverSelectionTimeoutMS=500",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false"
})
class ApplicationSmokeTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private String username;
    private String token;
    private String refreshToken;

    @BeforeEach
    void registerAndLogIn() throws Exception {
        username = "user" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(post("/api/v1/auth/public/register", Map.of(
                "username", username, "email", username + "@example.com", "password", "Password123!"), null)
                .statusCode()).isEqualTo(200);
        logIn();
    }

    private void logIn() throws Exception {
        JsonNode tokens = data(post("/api/v1/auth/public/login",
                Map.of("username", username, "password", "Password123!"), null));
        token = tokens.get("token").asText();
        refreshToken = tokens.get("refreshToken").asText();
    }

    @Test
    void shortLinkRedirectsWhileRedisKafkaAndMongoAreDown() throws Exception {
        JsonNode created = data(post("/api/v1/urls/shorten", Map.of("originalUrl", "https://example.com/a"), token));
        assertThat(created.get("id").isTextual()).isTrue();

        HttpResponse<String> redirect = get("/" + created.get("shortUrl").asText(), null);

        // New users are on the Free plan, which redirects with 301.
        assertThat(redirect.statusCode()).isEqualTo(301);
        assertThat(redirect.headers().firstValue("Location")).contains("https://example.com/a");
    }

    @Test
    void guestLinksAreRefusedWhileRedisIsDown() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/guest/shorten"))
                .header("Content-Type", "application/json")
                .header("X-Guest-Id", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"originalUrl\":\"https://example.com/g\"}"))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
    }

    @Test
    void unknownCodeIsPublicAndNotFound() throws Exception {
        assertThat(get("/AAAAAAAA", null).statusCode()).isEqualTo(404);
    }

    @Test
    void missingOrInvalidTokenIsUnauthorized() throws Exception {
        assertThat(get("/api/v1/urls/myurls", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/urls/myurls", "not-a-jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void frameworkErrorsKeepTheirStatusInsteadOfBecoming401() throws Exception {
        HttpRequest put = request("/api/v1/urls/1", token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        assertThat(http.send(put, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
    }

    @Test
    void cursorPaginationWorksThroughTheApi() throws Exception {
        for (int i = 0; i < 3; i++) {
            post("/api/v1/urls/shorten", Map.of("originalUrl", "https://example.com/p" + i), token);
        }

        JsonNode first = data(get("/api/v1/urls/myurls?size=2", token));
        assertThat(first.get("content")).hasSize(2);
        assertThat(first.get("totalElements").asInt()).isEqualTo(3);

        JsonNode second = data(get("/api/v1/urls/myurls?size=2&cursor=" + first.get("nextCursor").asText(), token));
        assertThat(second.get("content")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void invalidInputAndMissingMappingsReturnClientErrors() throws Exception {
        assertThat(get("/api/v1/urls/myurls?cursor=not-valid!", token).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/urls/myurls?size=0", token).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/urls/12345", token).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/urls/analytics?shortUrl=x&startDate=bad&endDate=bad", token).statusCode())
                .isEqualTo(400);
    }

    @Test
    void duplicateRegistrationIsAConflict() throws Exception {
        assertThat(post("/api/v1/auth/public/register", Map.of(
                "username", username, "email", "other@example.com", "password", "Password123!"), null)
                .statusCode()).isEqualTo(409);
    }

    @Test
    void repeatLoginRefreshAndLogoutManageTheRefreshToken() throws Exception {
        String firstRefreshToken = refreshToken;
        logIn();
        assertThat(post("/api/v1/auth/public/refresh", Map.of("refreshToken", firstRefreshToken), null).statusCode())
                .isEqualTo(401);

        JsonNode rotated = data(post("/api/v1/auth/public/refresh", Map.of("refreshToken", refreshToken), null));
        String rotatedToken = rotated.get("refreshToken").asText();
        assertThat(post("/api/v1/auth/public/refresh", Map.of("refreshToken", refreshToken), null).statusCode())
                .isEqualTo(401);

        assertThat(post("/api/v1/auth/logout", Map.of(), token).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/auth/public/refresh", Map.of("refreshToken", rotatedToken), null).statusCode())
                .isEqualTo(401);
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).get("data");
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return http.send(request(path, bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Object body, String bearer) throws Exception {
        HttpRequest request = request(path, bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return builder;
    }
}
