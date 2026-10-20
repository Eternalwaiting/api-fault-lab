package dev.apifaultlab;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class AiAssistanceServiceTest {
    @TempDir Path temp;

    @Test void readonlyAdviceSendsOnlySanitizedAttemptSummary() throws Exception {
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            server.stubFor(post(urlEqualTo("/v1/chat/completions"))
                    .willReturn(okJson(chatResponse("{\"explanation\":\"结构与指标断言均通过。\",\"findings\":[]}"))));
            var service = service(server);
            var attempt = new ReadOnlyProbeService.ProbeAttempt(1, 200, 18, "SUCCESS", 200, 1,
                    true, true, true, true, "");
            var report = new ReadOnlyProbeService.ProbeReport("00000000-0000-0000-0000-000000000001",
                    "2026-10-19T00:00:00Z", "REAL_READ_ONLY", "PASS", "local-sensor-page",
                    "/api/sensor/page?current=1&size=1&deviceId=[configured]", 1, List.of(attempt),
                    "只读查询，审计写入", "");

            var advice = service.readOnly(report);

            assertThat(advice.status()).isEqualTo("COMPLETED");
            server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
            var body = PlannerAgent.JSON.readTree(server.getAllServeEvents().getFirst().getRequest().getBodyAsString())
                    .path("messages").get(1).path("content").asText();
            assertThat(body).contains("\"httpStatus\":200", "\"elapsedMs\":18", "\"recordsReturned\":1",
                    "\"responseShapeValid\":true", "\"deviceIdMatches\":true", "\"sampleTimePresent\":true", "\"hasMetric\":true")
                    .doesNotContain("example-device", "24.5", "12:00:00", "deviceId=[configured]");
        } finally {
            server.stop();
        }
    }

    @Test void manualAdviceCannotReturnOrChangeScenarioConfiguration() throws Exception {
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            server.stubFor(post(urlEqualTo("/v1/chat/completions"))
                    .willReturn(okJson(chatResponse("""
                            {"explanation":"执行记录与需求一致。","findings":[],"scenario":{"maxAttempts":99}}
                            """))));
            var service = service(server);
            var scenario = new DrillModels.Scenario(DrillModels.ScenarioType.RECOVER_AFTER_FAILURES,
                    1, 503, 0, 2, 1200, DrillModels.Outcome.RECOVERED);
            var execution = new DrillModels.Execution("PASS", "RECOVERED", List.of(
                    new DrillModels.Attempt(1, 503, 12, "HTTP_ERROR", "", "http://127.0.0.1:9999/orders"),
                    new DrillModels.Attempt(2, 200, 8, "SUCCESS", "{\"amount\":42}", "http://127.0.0.1:9999/orders")),
                    List.of(new DrillModels.Assertion("expectedOutcome", true, "sensitive detail")), 2, 25);

            var advice = service.manual("恢复请求，最多两次", scenario, execution);

            assertThat(advice.status()).isEqualTo("FAILED");
            server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
            var body = PlannerAgent.JSON.readTree(server.getAllServeEvents().getFirst().getRequest().getBodyAsString())
                    .path("messages").get(1).path("content").asText();
            assertThat(body).contains("\"maxAttempts\":2", "恢复请求，最多两次")
                    .doesNotContain("amount", "42", "127.0.0.1", "sensitive detail");
        } finally {
            server.stop();
        }
    }

    @Test void missingModelConfigurationReturnsSeparateFailureStatus() {
        var service = new AiAssistanceService(new AiGateway(
                new LabSettings("", "", "", "", "", "", 3, "knowledge", temp.toString())));

        var advice = service.readOnly(new ReadOnlyProbeService.ProbeReport("id", "now", "REAL_READ_ONLY",
                "PASS", "target", "fixed", 1, List.of(), "side effect", ""));

        assertThat(advice.status()).isEqualTo("FAILED");
        assertThat(advice.error()).isEqualTo("缺少对话模型配置");
    }

    private AiAssistanceService service(WireMockServer server) {
        var settings = new LabSettings("http://127.0.0.1:" + server.port() + "/v1", "fixture",
                "test-only-key", "", "", "", 3, "knowledge", temp.toString());
        return new AiAssistanceService(new AiGateway(settings));
    }

    private String chatResponse(String content) {
        return "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + PlannerAgent.encode(content) + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }
}
