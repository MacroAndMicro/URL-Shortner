package com.Url_Shortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the full flow against real infrastructure. Requires Docker;
 * skipped when Docker is unavailable. Run with: ./mvnw verify
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class UrlShortenerIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>("amazon/dynamodb-local:2.5.4")
            .withCommand("-jar DynamoDBLocal.jar -sharedDb -inMemory")
            .withExposedPorts(8000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.mongodb.uri", () -> MONGO.getConnectionString() + "/urlshortener");
        registry.add("spring.mongodb.database", () -> "urlshortener");
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.dev.plan-switch-enabled", () -> "true");
        // Every test registers and logs in from the same address.
        registry.add("app.rate-limit.auth-per-minute", () -> "1000");
        // Small breaker windows so the outage test trips and recovers quickly.
        registry.add("app.circuit-breaker.window-size", () -> "4");
        registry.add("app.circuit-breaker.minimum-calls", () -> "2");
        registry.add("app.circuit-breaker.open-duration", () -> "3s");
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private String token;
    private String refreshToken;

    @BeforeEach
    void registerAndLogIn() throws Exception {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .until(() -> dynamoDbClient.describeTable(r -> r.tableName("url_mappings")) != null);

        String username = "user" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> credentials = Map.of(
                "username", username, "email", username + "@example.com", "password", "Password123!");
        assertThat(post("/api/v1/auth/public/register", credentials, null).statusCode()).isEqualTo(200);

        JsonNode tokens = data(post("/api/v1/auth/public/login",
                Map.of("username", username, "password", "Password123!"), null));
        token = tokens.get("token").asText();
        refreshToken = tokens.get("refreshToken").asText();
    }

    @Test
    void clickFlowsFromRedirectThroughKafkaToMongoAndDynamoDb() throws Exception {
        changePlan("MONTHLY");
        JsonNode created = shorten("https://example.com/flow");
        String code = created.get("shortUrl").asText();

        HttpResponse<String> redirect = get("/" + code, null);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).contains("https://example.com/flow");

        String start = LocalDateTime.now().minusHours(1).toString();
        String end = LocalDateTime.now().plusHours(1).toString();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            JsonNode byDate = data(get("/api/v1/urls/analytics?shortUrl=" + code
                    + "&startDate=" + start + "&endDate=" + end, token));
            assertThat(byDate).hasSize(1);
            assertThat(byDate.get(0).get("count").asLong()).isEqualTo(1);

            JsonNode mapping = data(get("/api/v1/urls/" + created.get("id").asText(), token));
            assertThat(mapping.get("clickCount").asInt()).isEqualTo(1);
        });
    }

    @Test
    void shorteningTheSameUrlTwiceReturnsTheSameLink() throws Exception {
        JsonNode first = shorten("https://example.com/same");
        assertThat(first.get("shortUrl").asText()).matches("[0-9A-Za-z]{10,11}");

        // DynamoDB Local updates indexes synchronously; real DynamoDB may lag briefly.
        JsonNode second = shorten("https://example.com/same");
        JsonNode other = shorten("https://example.com/different");

        assertThat(second.get("shortUrl").asText()).isEqualTo(first.get("shortUrl").asText());
        assertThat(other.get("shortUrl").asText()).isNotEqualTo(first.get("shortUrl").asText());
    }

    @Test
    void paginatesPastTheFirstPage() throws Exception {
        for (int i = 0; i < 25; i++) {
            shorten("https://example.com/page/" + i);
        }

        JsonNode first = data(get("/api/v1/urls/myurls?size=20", token));
        assertThat(first.get("content")).hasSize(20);
        assertThat(first.get("totalElements").asInt()).isEqualTo(25);
        String cursor = first.get("nextCursor").asText();

        JsonNode second = data(get("/api/v1/urls/myurls?size=20&cursor=" + cursor, token));
        assertThat(second.get("content")).hasSize(5);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void stringIdsWorkForDeleteAndTheLinkStopsResolving() throws Exception {
        JsonNode created = shorten("https://example.com/delete");
        assertThat(created.get("id").isTextual()).isTrue();

        HttpRequest delete = request("/api/v1/urls/" + created.get("id").asText(), token).DELETE().build();
        assertThat(http.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        assertThat(get("/" + created.get("shortUrl").asText(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void freeUsersGetCached301RedirectsAndNoAnalytics() throws Exception {
        JsonNode created = shorten("https://example.com/free");
        assertThat(created.get("expiresAt").isNull()).isFalse();
        // Times carry their zone offset, so browsers in other zones read them correctly.
        assertThat(created.get("createdDate").asText()).matches(".*T.*(Z|[+-]\\d\\d:\\d\\d)");
        assertThat(created.get("editableUntil").asText()).isNotEmpty();

        HttpResponse<String> redirect = get("/" + created.get("shortUrl").asText(), null);
        assertThat(redirect.statusCode()).isEqualTo(301);
        assertThat(redirect.headers().firstValue("Location")).contains("https://example.com/free");
        assertThat(redirect.headers().firstValue("Cache-Control").orElseThrow()).matches("max-age=\\d+, public");

        assertThat(get("/api/v1/urls/dashboard", token).statusCode()).isEqualTo(403);

        JsonNode plan = data(get("/api/v1/account/plan", token));
        assertThat(plan.get("effectivePlan").asText()).isEqualTo("FREE");
        assertThat(plan.get("linkLimit").asInt()).isEqualTo(50);
        assertThat(plan.get("limitPeriod").asText()).isEqualTo("ACTIVE");
        assertThat(plan.get("editWindowHours").asInt()).isEqualTo(3);
        assertThat(plan.get("linksUsed").asInt()).isEqualTo(1);
        assertThat(plan.get("redirectStatus").asInt()).isEqualTo(301);
    }

    @Test
    void upgradingSwitchesExistingLinksTo302AndUnlocksAnalytics() throws Exception {
        JsonNode created = shorten("https://example.com/upgrade");

        JsonNode plan = changePlan("YEARLY");
        assertThat(plan.get("effectivePlan").asText()).isEqualTo("YEARLY");
        assertThat(plan.get("linkLimit").asInt()).isEqualTo(2000);
        assertThat(plan.get("limitPeriod").asText()).isEqualTo("YEAR");
        assertThat(plan.get("customCodes").asBoolean()).isTrue();

        assertThat(get("/" + created.get("shortUrl").asText(), null).statusCode()).isEqualTo(302);
        assertThat(get("/api/v1/urls/dashboard", token).statusCode()).isEqualTo(200);
        JsonNode mine = data(get("/api/v1/urls/myurls", token));
        assertThat(mine.get("content").get(0).get("expiresAt").isNull()).isTrue();
    }

    @Test
    void lapsedPlanFallsBackToFreeAfterTheGracePeriod() throws Exception {
        JsonNode inGrace = changePlan("MONTHLY", LocalDateTime.now().minusDays(3));
        assertThat(inGrace.get("effectivePlan").asText()).isEqualTo("MONTHLY");
        assertThat(inGrace.get("inGracePeriod").asBoolean()).isTrue();

        JsonNode lapsed = changePlan("MONTHLY", LocalDateTime.now().minusDays(8));
        assertThat(lapsed.get("subscribedPlan").asText()).isEqualTo("MONTHLY");
        assertThat(lapsed.get("effectivePlan").asText()).isEqualTo("FREE");
        assertThat(lapsed.get("paidLinksExpireAt").isNull()).isFalse();
    }

    @Test
    void yearlyUsersChooseCustomCodesThatWorkInAnyCase() throws Exception {
        String code = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> refused = post("/api/v1/urls/shorten",
                Map.of("originalUrl", "https://example.com/custom", "customCode", code), token);
        assertThat(refused.statusCode()).isEqualTo(403);

        changePlan("YEARLY");
        JsonNode created = data(post("/api/v1/urls/shorten",
                Map.of("originalUrl", "https://example.com/custom", "customCode", code), token));
        assertThat(created.get("shortUrl").asText()).isEqualTo(code.toLowerCase());

        HttpResponse<String> redirect = get("/" + code.toUpperCase(), null);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).contains("https://example.com/custom");

        HttpResponse<String> taken = post("/api/v1/urls/shorten",
                Map.of("originalUrl", "https://example.com/other", "customCode", code.toLowerCase()), token);
        assertThat(taken.statusCode()).isEqualTo(409);

        assertThat(post("/api/v1/urls/shorten",
                Map.of("originalUrl", "https://example.com/other", "customCode", "admin"), token).statusCode())
                .isEqualTo(400);
    }

    @Test
    void paidLimitCountsCreationsAndDeletingDoesNotGiveThemBack() throws Exception {
        JsonNode plan = changePlan("MONTHLY");
        assertThat(plan.get("limitPeriod").asText()).isEqualTo("MONTH");
        assertThat(plan.get("linksUsed").asInt()).isZero();

        JsonNode created = shorten("https://example.com/quota");
        HttpRequest delete = request("/api/v1/urls/" + created.get("id").asText(), token).DELETE().build();
        assertThat(http.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        shorten("https://example.com/quota-2");

        assertThat(data(get("/api/v1/account/plan", token)).get("linksUsed").asInt()).isEqualTo(2);
    }

    @Test
    void freeLinksCanBeEditedRightAfterCreation() throws Exception {
        JsonNode created = shorten("https://example.com/edit");
        HttpRequest patch = request("/api/v1/urls/" + created.get("id").asText(), token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"originalUrl\":\"https://example.com/edited\"}"))
                .build();

        HttpResponse<String> response = http.send(patch, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(get("/" + created.get("shortUrl").asText(), null).headers().firstValue("Location"))
                .contains("https://example.com/edited");
    }

    @Test
    void guestsGetOneLinkPerBrowser() throws Exception {
        String guestId = UUID.randomUUID().toString();

        JsonNode created = data(guestShorten("https://example.com/guest", guestId));
        assertThat(created.get("expiresAt").isNull()).isFalse();
        assertThat(get("/" + created.get("shortUrl").asText(), null).statusCode()).isEqualTo(301);

        HttpResponse<String> second = guestShorten("https://example.com/guest-2", guestId);
        assertThat(second.statusCode()).isEqualTo(403);
        assertThat(second.body()).contains("Sign up");

        assertThat(guestShorten("https://example.com/guest", null).statusCode()).isEqualTo(400);
    }

    @Test
    void clicksMadeWhileMongoDbIsDownArriveOnceItIsBack() throws Exception {
        changePlan("YEARLY");
        String code = shorten("https://example.com/outage").get("shortUrl").asText();
        String start = LocalDateTime.now().minusHours(1).toString();
        String end = LocalDateTime.now().plusHours(1).toString();
        String analytics = "/api/v1/urls/analytics?shortUrl=" + code + "&startDate=" + start + "&endDate=" + end;

        var docker = MONGO.getDockerClient();
        docker.pauseContainerCmd(MONGO.getContainerId()).exec();
        try {
            // Redirects do not need MongoDB.
            for (int i = 0; i < 3; i++) {
                assertThat(get("/" + code, null).statusCode()).isEqualTo(302);
            }
            // Analytics say they are unavailable instead of showing zero; once
            // the breaker is open they say so at once.
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                long t0 = System.nanoTime();
                HttpResponse<String> response = get(analytics, token);
                long tookMs = (System.nanoTime() - t0) / 1_000_000;
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(response.body()).contains("temporarily unavailable");
                assertThat(tookMs).isLessThan(1000);
            });
        } finally {
            docker.unpauseContainerCmd(MONGO.getContainerId()).exec();
        }

        // The consumer waited instead of dead-lettering: all three clicks arrive.
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            HttpResponse<String> response = get(analytics, token);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode byDay = json.readTree(response.body()).get("data");
            assertThat(byDay).hasSize(1);
            assertThat(byDay.get(0).get("count").asLong()).isEqualTo(3);
        });
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        assertThat(get("/api/v1/urls/myurls", null).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshRotatesTheTokenAndLogoutRevokesIt() throws Exception {
        JsonNode rotated = data(post("/api/v1/auth/public/refresh", Map.of("refreshToken", refreshToken), null));
        String newToken = rotated.get("refreshToken").asText();

        assertThat(post("/api/v1/auth/public/refresh", Map.of("refreshToken", refreshToken), null).statusCode())
                .isEqualTo(401);
        assertThat(post("/api/v1/auth/logout", Map.of(), token).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/auth/public/refresh", Map.of("refreshToken", newToken), null).statusCode())
                .isEqualTo(401);
    }

    @Test
    void malformedEventGoesToTheDeadLetterTopic() throws Exception {
        byte[] marker = ("not json " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>("analytics-events", marker)).get();
        }

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of("analytics-events-dlt"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(1));
                return !records.isEmpty();
            });
        }
    }

    private JsonNode shorten(String url) throws Exception {
        return data(post("/api/v1/urls/shorten", Map.of("originalUrl", url), token));
    }

    private JsonNode changePlan(String plan) throws Exception {
        return data(post("/api/v1/account/plan/dev", Map.of("plan", plan), token));
    }

    private JsonNode changePlan(String plan, LocalDateTime periodEnd) throws Exception {
        return data(post("/api/v1/account/plan/dev",
                Map.of("plan", plan, "periodEnd", periodEnd.withNano(0).toString()), token));
    }

    private HttpResponse<String> guestShorten(String url, String guestId) throws Exception {
        HttpRequest.Builder builder = request("/api/v1/guest/shorten", null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("originalUrl", url))));
        if (guestId != null) {
            builder.header("X-Guest-Id", guestId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
