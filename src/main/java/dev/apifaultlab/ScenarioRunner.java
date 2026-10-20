package dev.apifaultlab;

import static dev.apifaultlab.DrillModels.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ScenarioRunner {
    private static final String PATH = "/orders/ORD-1001";
    private static final String ORDER = "{\"orderId\":\"ORD-1001\",\"status\":\"PAID\",\"amount\":128.50}";

    public Execution execute(DrillModels.Scenario scenario) {
        new ScenarioValidator().validate(scenario);
        long started = System.nanoTime();
        var attempts = new ArrayList<Attempt>();
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            configure(server, scenario);
            var uri = URI.create("http://127.0.0.1:" + server.port() + PATH);
            boolean success = false;
            boolean infrastructureError = false;
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER).build()) {
                for (int number = 1; number <= scenario.maxAttempts(); number++) {
                    long remaining = ScenarioValidator.DEADLINE_MS - elapsed(started);
                    if (remaining <= 0) break;
                    long attemptStarted = System.nanoTime();
                    var request = HttpRequest.newBuilder(uri).GET()
                            .timeout(Duration.ofMillis(Math.min(remaining, scenario.requestTimeoutMs()))).build();
                    try {
                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        boolean validBody = response.statusCode() == 200 && validOrder(response.body());
                        attempts.add(new Attempt(number, response.statusCode(), elapsed(attemptStarted),
                                validBody ? "VALID_ORDER" : "HTTP_ERROR", response.body(), uri.toString()));
                        if (validBody) { success = true; break; }
                        if (!ScenarioValidator.FAILURE_STATUSES.contains(response.statusCode())) {
                            infrastructureError = true;
                            break;
                        }
                    } catch (HttpTimeoutException timeout) {
                        attempts.add(new Attempt(number, null, elapsed(attemptStarted), "TIMEOUT", "", uri.toString()));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        attempts.add(new Attempt(number, null, elapsed(attemptStarted), "INTERRUPTED", "", uri.toString()));
                        infrastructureError = true;
                        break;
                    } catch (java.io.IOException error) {
                        attempts.add(new Attempt(number, null, elapsed(attemptStarted), "IO_ERROR", "", uri.toString()));
                        infrastructureError = true;
                        break;
                    }
                }
            }
            String observed = success ? (attempts.size() > 1 ? "RECOVERED" : "SUCCESS")
                    : (!attempts.isEmpty() && attempts.stream().allMatch(a -> a.result().equals("TIMEOUT")) ? "TIMEOUT" : "EXHAUSTED");
            int journalCount = server.getAllServeEvents().size();
            var assertions = List.of(
                    new Assertion("expectedOutcome", observed.equals(scenario.expectedOutcome().name()), "预期=" + scenario.expectedOutcome() + "，实际=" + observed),
                    new Assertion("attemptBound", attempts.size() <= scenario.maxAttempts(), attempts.size() + "/" + scenario.maxAttempts()),
                    new Assertion("bodyCheck", !success || validOrder(attempts.getLast().body()), success ? "已核对预设 orderId、status 和 amount" : "本次没有观察到有效成功正文"),
                    new Assertion("deadline", elapsed(started) < ScenarioValidator.DEADLINE_MS, "清理前耗时 " + elapsed(started) + "ms"));
            return new Execution(infrastructureError ? "EXECUTION_ERROR" : assertions.stream().allMatch(Assertion::passed) ? "PASS" : "FAIL",
                    observed, List.copyOf(attempts), assertions, journalCount, elapsed(started));
        } catch (RuntimeException error) {
            return new Execution("EXECUTION_ERROR", "UNKNOWN", List.copyOf(attempts),
                    List.of(new Assertion("infrastructure", false, error.getClass().getSimpleName())), 0, elapsed(started));
        } finally {
            server.stop();
        }
    }

    private void configure(WireMockServer server, DrillModels.Scenario config) {
        if (config.scenarioType() == ScenarioType.RECOVER_AFTER_FAILURES) {
            for (int n = 0; n < config.failureCount(); n++) {
                server.stubFor(get(urlEqualTo(PATH)).inScenario("recovery")
                        .whenScenarioStateIs(n == 0 ? Scenario.STARTED : "failure-" + n)
                        .willSetStateTo("failure-" + (n + 1))
                        .willReturn(aResponse().withStatus(config.failureStatus()).withBody("{\"error\":\"injected\"}")));
            }
            server.stubFor(get(urlEqualTo(PATH)).inScenario("recovery")
                    .whenScenarioStateIs("failure-" + config.failureCount())
                    .willReturn(okJson(ORDER)));
        } else if (config.scenarioType() == ScenarioType.CONTINUOUS_ERROR) {
            server.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(config.failureStatus()).withBody("{\"error\":\"injected\"}")));
        } else {
            server.stubFor(get(urlEqualTo(PATH)).willReturn(okJson(ORDER).withFixedDelay(config.responseDelayMs())));
        }
    }

    private boolean validOrder(String body) {
        try {
            var json = JsonMapper.builder().build().readTree(body);
            return json.path("orderId").asText().equals("ORD-1001") && json.path("status").asText().equals("PAID")
                    && json.path("amount").decimalValue().compareTo(new java.math.BigDecimal("128.50")) == 0;
        } catch (Exception invalid) { return false; }
    }
    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
