package dev.apifaultlab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiTest {
    @LocalServerPort int port;
    private static final java.nio.file.Path RUNTIME;
    static {
        try { RUNTIME = java.nio.file.Files.createTempDirectory("afl-api-test-"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) {
        registry.add("lab.runtime-dir", () -> RUNTIME.toString());
        registry.add("lab.chat-api-key", () -> "");
        registry.add("lab.embedding-api-key", () -> "");
    }
    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private HttpResponse<String> post(String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/drills"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void statusIsCredentialFreeAndDoesNotCallModel() throws Exception {
        var response = get("/api/status");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"configured\":false").doesNotContain("apiKey", "Authorization", "Bearer");
    }
    @Test void readonlyConfigurationAcceptsOnlyDeviceIdAndExposesNoCredentialField() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/read-only/config"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"deviceId\":\"example-device\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"configured\":true", "\"targetId\":\"local-sensor-page\"",
                    "\"endpoint\":\"/api/sensor/page\"", "\"authMode\":\"SOURCE_WHITELIST_NO_TOKEN\"")
                    .doesNotContain("token", "password", "Authorization");

            var invalid = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/read-only/config"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"deviceId\":\"example-device&size=1000\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(invalid.statusCode()).isEqualTo(400);
        }
    }
    @Test void toolPageAndAssetsAreServedBySpringBoot() throws Exception {
        var response = get("/");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("API Fault Lab", "id=\"drill-form\"", "app.js");
        assertThat(get("/app.js").statusCode()).isEqualTo(200);
    }
    @Test void manualHttpDrillAndExportReferToSameEvidence() throws Exception {
        var response = post("""
          {"requirement":"manual recovery","manualScenario":{"scenarioType":"RECOVER_AFTER_FAILURES","failureCount":1,"failureStatus":503,"responseDelayMs":0,"maxAttempts":2,"requestTimeoutMs":1000,"expectedOutcome":"RECOVERED"}}
          """);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = PlannerAgent.JSON.readTree(response.body());
        assertThat(json.path("status").asText()).isEqualTo("PASS");
        assertThat(json.path("aiAssistance").path("status").asText()).isEqualTo("FAILED");
        assertThat(json.path("status").asText()).isEqualTo("PASS");
        var exported = get("/api/drills/" + json.path("id").asText() + "/report");
        assertThat(exported.statusCode()).isEqualTo(200);
        assertThat(exported.body()).contains(json.path("id").asText(), "503", "200", "MANUAL");
    }
    @Test void invalidConfigRejectedBeforeExecution() throws Exception {
        var response = post("""
          {"requirement":"invalid","manualScenario":{"scenarioType":"NORMAL","failureCount":0,"failureStatus":503,"responseDelayMs":0,"maxAttempts":6,"requestTimeoutMs":1000,"expectedOutcome":"SUCCESS"}}
          """);
        assertThat(response.statusCode()).isEqualTo(400);
    }
    @Test void rejectsCrossOriginMutationAndNonUuidReportPath() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/drills"))
                    .header("Origin", "https://unrelated.example").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"requirement\":\"test\",\"manualScenario\":null}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
        }
        assertThat(get("/api/drills/unknown").statusCode()).isEqualTo(404);
    }
    @Test void aiWithoutCredentialsReturnsExplicitModelError() throws Exception {
        var response = post("""
          {"requirement":"第一次503后恢复，最多请求两次","manualScenario":null}
          """);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"MODEL_ERROR\"", "\"rounds\":[]").doesNotContain("\"status\":\"PASS\"");
    }
}
