package dev.apifaultlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

public class ReadOnlyProbeService {
    public static final int MAX_REQUESTS = 3;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final long DEADLINE_MS = 12_000;
    private static final String TARGET_ID = "local-sensor-page";
    private static final String ENDPOINT = "/api/sensor/page";
    private static final Set<String> METRICS = Set.of(
            "temperature", "humidity", "co2", "tvoc", "eco2", "pressure",
            "lightLux", "presence", "distanceCm");

    public record RunRequest(int requestCount) {}
    public record TargetStatus(boolean configured, String targetId, String deviceId, String baseUrl,
                               String endpoint, String authMode, int maxRequests, String error) {}
    public record ProbeAttempt(int number, Integer httpStatus, long elapsedMs, String result,
                               Integer businessCode, int recordsReturned, boolean responseShapeValid,
                               boolean deviceIdMatches, boolean sampleTimePresent, boolean hasMetric,
                               String error) {}
    public record ProbeReport(String id, String createdAt, String mode, String status, String targetId,
                              String endpoint, int requestedRequests, List<ProbeAttempt> attempts,
                              String sideEffect, String error, DrillModels.AiAssistance aiAssistance) {
        public ProbeReport(String id, String createdAt, String mode, String status, String targetId,
                           String endpoint, int requestedRequests, List<ProbeAttempt> attempts,
                           String sideEffect, String error) {
            this(id, createdAt, mode, status, targetId, endpoint, requestedRequests, attempts,
                    sideEffect, error, DrillModels.AiAssistance.notRequested());
        }
    }

    private record TargetConfig(String targetId, String baseUrl, String deviceId) {}

    private final Path runtimeDir;
    private final JsonMapper json;
    private final Set<String> allowedBaseUrls;
    private final AiAssistanceService aiAssistance;

    public ReadOnlyProbeService(Path runtimeDir, JsonMapper json) {
        this(runtimeDir, json, List.of("http://127.0.0.1:8080"), null);
    }

    ReadOnlyProbeService(Path runtimeDir, JsonMapper json, List<String> allowedBaseUrls) {
        this(runtimeDir, json, allowedBaseUrls, null);
    }

    ReadOnlyProbeService(Path runtimeDir, JsonMapper json, List<String> allowedBaseUrls,
                         AiAssistanceService aiAssistance) {
        this.runtimeDir = runtimeDir;
        this.json = json;
        this.allowedBaseUrls = Set.copyOf(allowedBaseUrls);
        this.aiAssistance = aiAssistance;
    }

    public TargetStatus status() {
        try {
            var target = readTarget();
            return new TargetStatus(true, target.targetId(), target.deviceId(), target.baseUrl(), ENDPOINT,
                    "SOURCE_WHITELIST_NO_TOKEN", MAX_REQUESTS, "");
        } catch (RuntimeException invalid) {
            return new TargetStatus(false, TARGET_ID, "", "", ENDPOINT,
                    "SOURCE_WHITELIST_NO_TOKEN", MAX_REQUESTS, safeConfigurationError(invalid));
        }
    }

    public TargetStatus configure(String deviceId) {
        validateDeviceId(deviceId);
        var target = new TargetConfig(TARGET_ID, "http://127.0.0.1:8080", deviceId);
        try {
            Files.createDirectories(runtimeDir);
            var file = runtimeDir.resolve("readonly-target.json");
            var temporary = runtimeDir.resolve("readonly-target.tmp");
            json.writeValue(temporary.toFile(), target);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return status();
        } catch (IOException error) {
            throw new IllegalStateException("无法保存本地只读目标配置");
        }
    }

    public ProbeReport run(RunRequest request) {
        if (request == null || request.requestCount() < 1 || request.requestCount() > MAX_REQUESTS)
            throw new IllegalArgumentException("requestCount 必须为 1..3");

        var target = readTarget();
        var id = UUID.randomUUID().toString();
        var started = System.nanoTime();
        var attempts = new ArrayList<ProbeAttempt>();
        String status = "PASS";
        String error = "";

        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        String encodedDeviceId = URLEncoder.encode(target.deviceId(), StandardCharsets.UTF_8);
        URI uri = URI.create(target.baseUrl() + ENDPOINT + "?current=1&size=1&deviceId=" + encodedDeviceId);

        for (int number = 1; number <= request.requestCount(); number++) {
            if (elapsed(started) >= DEADLINE_MS) {
                status = "BLOCKED";
                error = "整轮执行达到 12 秒上限";
                break;
            }
            if (number > 1) {
                try {
                    Thread.sleep(350);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    status = "BLOCKED";
                    error = "执行被中断";
                    break;
                }
            }
            attempts.add(executeOne(client, uri, target, number, Math.max(100, DEADLINE_MS - elapsed(started))));
            var attempt = attempts.getLast();
            if ("AUTH_REQUIRED".equals(attempt.result())) {
                status = "BLOCKED";
                error = "该查询需要有效授权；未尝试登录、刷新或猜测凭证";
                break;
            }
            if (Set.of("UNAVAILABLE", "TIMEOUT").contains(attempt.result())) {
                status = "BLOCKED";
                error = "目标服务不可用或请求超时；已停止后续请求";
                break;
            }
            if (!"SUCCESS".equals(attempt.result())) status = "FAIL";
        }

        var report = new ProbeReport(id, Instant.now().toString(), "REAL_READ_ONLY",
                status, TARGET_ID, ENDPOINT + "?current=1&size=1&deviceId=[configured]",
                request.requestCount(), List.copyOf(attempts),
                "业务查询仅执行固定分页读取；目标服务可能按自身机制为每个请求追加访问审计记录。",
                error);
        var advice = aiAssistance == null ? DrillModels.AiAssistance.notRequested() : aiAssistance.readOnly(report);
        report = new ProbeReport(report.id(), report.createdAt(), report.mode(), report.status(), report.targetId(),
                report.endpoint(), report.requestedRequests(), report.attempts(), report.sideEffect(), report.error(), advice);
        save(report);
        return report;
    }

    public ProbeReport get(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new NoSuchElementException("只读测试记录不存在");
        try {
            return json.readValue(reportPath(id).toFile(), ProbeReport.class);
        } catch (IOException error) {
            throw new NoSuchElementException("只读测试记录不存在");
        }
    }

    private ProbeAttempt executeOne(HttpClient client, URI uri, TargetConfig target, int number, long remainingMs) {
        long started = System.nanoTime();
        try {
            var request = HttpRequest.newBuilder(uri).GET()
                    .timeout(Duration.ofMillis(Math.max(100, Math.min(5000, remainingMs))))
                    .header("Accept", "application/json")
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                int statusCode = response.statusCode();
                if (statusCode == 401 || statusCode == 403)
                    return attempt(number, statusCode, started, "AUTH_REQUIRED", null, 0, false, false, false, false, "");
                if (statusCode < 200 || statusCode >= 300)
                    return attempt(number, statusCode, started, statusCode >= 500 ? "UNAVAILABLE" : "HTTP_ERROR",
                            null, 0, false, false, false, false, "");
                if (response.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("json") == false)
                    return attempt(number, statusCode, started, "INVALID_RESPONSE", null, 0, false, false, false, false, "");
                byte[] body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES)
                    return attempt(number, statusCode, started, "INVALID_RESPONSE", null, 0, false, false, false, false, "");
                return inspect(number, statusCode, started, body, target);
            }
        } catch (java.net.http.HttpTimeoutException timeout) {
            return attempt(number, null, started, "TIMEOUT", null, 0, false, false, false, false, "");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return attempt(number, null, started, "UNAVAILABLE", null, 0, false, false, false, false, "");
        } catch (IOException | IllegalArgumentException failure) {
            return attempt(number, null, started, "UNAVAILABLE", null, 0, false, false, false, false, "");
        }
    }

    private ProbeAttempt inspect(int number, int statusCode, long started, byte[] body, TargetConfig target) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode records = root.path("data").path("records");
            int businessCode = root.path("code").isIntegralNumber() ? root.path("code").intValue() : -1;
            boolean shape = businessCode == 200 && records.isArray()
                    && records.size() <= 1 && root.path("data").path("total").isIntegralNumber()
                    && root.path("data").path("current").asInt(-1) == 1
                    && root.path("data").path("size").asInt(-1) == 1;
            if (!shape)
                return attempt(number, statusCode, started, businessCode == 200 ? "INVALID_RESPONSE" : "BUSINESS_ERROR",
                        businessCode < 0 ? null : businessCode, records.isArray() ? records.size() : 0,
                        false, false, false, false, "");
            if (records.isEmpty())
                return attempt(number, statusCode, started, "BUSINESS_ERROR", businessCode, 0,
                        true, false, false, false, "");

            JsonNode sample = records.get(0);
            boolean matchesDevice = target.deviceId().equals(sample.path("deviceId").asText(""));
            boolean sampleTime = sample.path("sampleTime").isTextual() && !sample.path("sampleTime").asText().isBlank();
            boolean hasMetric = METRICS.stream().anyMatch(field -> !sample.path(field).isMissingNode()
                    && !sample.path(field).isNull());
            boolean valid = matchesDevice && sampleTime && hasMetric;
            return attempt(number, statusCode, started, valid ? "SUCCESS" : "BUSINESS_ERROR",
                    businessCode, 1, true, matchesDevice, sampleTime, hasMetric, "");
        } catch (IOException | RuntimeException invalid) {
            return attempt(number, statusCode, started, "INVALID_RESPONSE", null, 0,
                    false, false, false, false, "");
        }
    }

    private ProbeAttempt attempt(int number, Integer httpStatus, long started, String result, Integer businessCode,
                                 int count, boolean shape, boolean deviceMatches, boolean sampleTime, boolean metric,
                                 String safeError) {
        return new ProbeAttempt(number, httpStatus, elapsed(started), result, businessCode, count,
                shape, deviceMatches, sampleTime, metric, safeError);
    }

    private TargetConfig readTarget() {
        Path path = runtimeDir.resolve("readonly-target.json");
        try {
            var config = json.readValue(path.toFile(), TargetConfig.class);
            validateTarget(config);
            return config;
        } catch (IOException error) {
            throw new IllegalArgumentException("缺少本地只读目标配置，请填写设备 ID 后保存");
        }
    }

    private void validateTarget(TargetConfig config) {
        if (config == null || !TARGET_ID.equals(config.targetId()))
            throw new IllegalArgumentException("只允许预设的本地分页查询目标");
        validateDeviceId(config.deviceId());
        URI base;
        try {
            base = URI.create(config.baseUrl());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("目标地址无效");
        }
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost())
                || base.getPort() < 1 || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                || !allowedBaseUrls.contains(config.baseUrl()))
            throw new IllegalArgumentException("目标必须匹配预先允许的本机回环地址");
    }

    private void validateDeviceId(String deviceId) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("设备 ID 只能包含字母、数字、点、下划线和连字符，长度为 1..64");
    }

    private String safeConfigurationError(RuntimeException error) {
        return error.getMessage() == null ? "本地目标配置无效" : error.getMessage();
    }

    private void save(ProbeReport report) {
        try {
            Files.createDirectories(runtimeDir.resolve("reports"));
            var temporary = runtimeDir.resolve("reports").resolve("readonly-" + report.id() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
            Files.move(temporary, reportPath(report.id()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new IllegalStateException("无法保存只读测试摘要");
        }
    }

    private Path reportPath(String id) {
        return runtimeDir.resolve("reports").resolve("readonly-" + id + ".json");
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
