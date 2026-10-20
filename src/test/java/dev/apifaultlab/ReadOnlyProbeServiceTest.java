package dev.apifaultlab;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class ReadOnlyProbeServiceTest {
    @TempDir Path runtime;
    private HttpServer fixture;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private ReadOnlyProbeService service(String response, int status) throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/api/sensor/page", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fixture.start();
        var base = "http://127.0.0.1:" + fixture.getAddress().getPort();
        Files.writeString(runtime.resolve("readonly-target.json"),
                "{\"targetId\":\"local-sensor-page\",\"baseUrl\":\"" + base + "\",\"deviceId\":\"example-device\"}");
        return new ReadOnlyProbeService(runtime, JsonMapper.builder().build(), List.of(base));
    }

    private String validPage() {
        return """
                {"code":200,"message":"ok","data":{"records":[
                  {"id":19,"deviceId":"example-device","sampleTime":"2026-10-19T12:00:00","temperature":24.5}
                ],"total":1,"current":1,"size":1}}
                """;
    }

    @AfterEach void stopFixture() {
        if (fixture != null) fixture.stop(0);
    }

    @Test void executesOnlyTheRequestedSerialGetsAndNeverStoresTelemetryBody() throws Exception {
        var report = service(validPage(), 200).run(new ReadOnlyProbeService.RunRequest(3));

        assertThat(report.status()).isEqualTo("PASS");
        assertThat(report.attempts()).hasSize(3);
        assertThat(requests).hasSize(3).allMatch(request -> request.startsWith("GET /api/sensor/page?"));
        assertThat(report.attempts()).allSatisfy(attempt -> {
            assertThat(attempt.responseShapeValid()).isTrue();
            assertThat(attempt.deviceIdMatches()).isTrue();
            assertThat(attempt.sampleTimePresent()).isTrue();
            assertThat(attempt.hasMetric()).isTrue();
            assertThat(attempt.toString()).doesNotContain("24.5", "example-device", "T12:00:00");
        });
        assertThat(PlannerAgent.encode(report)).doesNotContain("24.5", "example-device", "T12:00:00");
    }

    @Test void aiAssistanceFailureDoesNotChangeReadonlyJavaResult() throws Exception {
        service(validPage(), 200);
        var guarded = new ReadOnlyProbeService(runtime, JsonMapper.builder().build(),
                List.of("http://127.0.0.1:" + fixture.getAddress().getPort()),
                new AiAssistanceService(new AiGateway(new LabSettings("", "", "", "", "", "", 3, "knowledge", runtime.toString()))));

        var report = guarded.run(new ReadOnlyProbeService.RunRequest(1));

        assertThat(report.status()).isEqualTo("PASS");
        assertThat(report.attempts()).hasSize(1);
        assertThat(report.aiAssistance().status()).isEqualTo("FAILED");
        assertThat(guarded.get(report.id()).aiAssistance().error()).isEqualTo("缺少对话模型配置");
        assertThat(requests).hasSize(1);
    }

    @Test void capsRequestsAtThreeAndRejectsInvalidCountBeforeSending() throws Exception {
        var service = service(validPage(), 200);

        assertThatThrownBy(() -> service.run(new ReadOnlyProbeService.RunRequest(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test void aValidHttpResponseWithUnexpectedBusinessCodeIsFail() throws Exception {
        var response = """
                {"code":500,"message":"sensitive backend detail","data":null}
                """;
        var report = service(response, 200).run(new ReadOnlyProbeService.RunRequest(1));

        assertThat(report.status()).isEqualTo("FAIL");
        assertThat(report.attempts().getFirst().result()).isEqualTo("BUSINESS_ERROR");
        assertThat(PlannerAgent.encode(report)).doesNotContain("sensitive backend detail");
    }

    @Test void unauthorizedResponsesAreReportedWithoutLeakingResponseBody() throws Exception {
        var report = service("{\"message\":\"invalid token SECRET-DO-NOT-LEAK\"}", 401)
                .run(new ReadOnlyProbeService.RunRequest(1));

        assertThat(report.status()).isEqualTo("BLOCKED");
        assertThat(report.attempts().getFirst().result()).isEqualTo("AUTH_REQUIRED");
        assertThat(PlannerAgent.encode(report)).doesNotContain("SECRET-DO-NOT-LEAK");
    }

    @Test void rejectsUnapprovedHostsAndUnsafeDeviceIdsBeforeAnyRequest() throws Exception {
        service(validPage(), 200);
        Files.writeString(runtime.resolve("readonly-target.json"),
                "{\"targetId\":\"local-sensor-page\",\"baseUrl\":\"http://example.com\",\"deviceId\":\"example-device\"}");
        var badHost = new ReadOnlyProbeService(runtime, JsonMapper.builder().build(), List.of());
        assertThatThrownBy(() -> badHost.run(new ReadOnlyProbeService.RunRequest(1)))
                .isInstanceOf(IllegalArgumentException.class);

        Files.writeString(runtime.resolve("readonly-target.json"),
                "{\"targetId\":\"local-sensor-page\",\"baseUrl\":\"http://127.0.0.1:"
                        + fixture.getAddress().getPort()
                        + "\",\"deviceId\":\"example-device&size=999\"}");
        assertThatThrownBy(() -> new ReadOnlyProbeService(runtime, JsonMapper.builder().build(),
                List.of("http://127.0.0.1:" + fixture.getAddress().getPort()))
                .run(new ReadOnlyProbeService.RunRequest(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }
}
