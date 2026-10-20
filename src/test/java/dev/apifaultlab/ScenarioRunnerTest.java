package dev.apifaultlab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.apifaultlab.DrillModels.*;
import static org.assertj.core.api.Assertions.*;

class ScenarioRunnerTest {
    private final ScenarioRunner runner = new ScenarioRunner();
    private Scenario scenario(ScenarioType type, int failures, int delay, int attempts, int timeout, Outcome expected) {
        return new Scenario(type, failures, 503, delay, attempts, timeout, expected);
    }
    @Test void normalMakesOneRealRequestAndChecksBody() {
        var result = runner.execute(scenario(ScenarioType.NORMAL, 0, 0, 2, 2000, Outcome.SUCCESS));
        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.attempts()).extracting(Attempt::status).containsExactly(200);
        assertThat(result.attempts().getFirst().body()).contains("\"orderId\":\"ORD-1001\"");
        assertThat(result.serverRequests()).isEqualTo(1);
    }
    @Test void recoversOnSecondRequest() {
        var result = runner.execute(scenario(ScenarioType.RECOVER_AFTER_FAILURES, 1, 0, 2, 2000, Outcome.RECOVERED));
        assertThat(result.attempts()).extracting(Attempt::status).containsExactly(503, 200);
        assertThat(result.serverRequests()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("PASS");
    }
    @Test void insufficientAttemptsIsAnHonestFail() {
        var result = runner.execute(scenario(ScenarioType.RECOVER_AFTER_FAILURES, 2, 0, 2, 2000, Outcome.RECOVERED));
        assertThat(result.attempts()).extracting(Attempt::status).containsExactly(503, 503);
        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.serverRequests()).isEqualTo(2);
    }
    @Test void continuousErrorsStopAtBound() {
        var result = runner.execute(scenario(ScenarioType.CONTINUOUS_ERROR, 0, 0, 3, 2000, Outcome.EXHAUSTED));
        assertThat(result.attempts()).extracting(Attempt::status).containsExactly(503, 503, 503);
        assertThat(result.status()).isEqualTo("PASS");
    }
    @Test void realTimeoutWithComfortableMargin() {
        var result = runner.execute(scenario(ScenarioType.DELAY, 0, 900, 2, 150, Outcome.TIMEOUT));
        assertThat(result.attempts()).extracting(Attempt::result).containsExactly("TIMEOUT", "TIMEOUT");
        assertThat(result.status()).isEqualTo("PASS");
    }
    @Test void shortDelaySucceeds() {
        assertThat(runner.execute(scenario(ScenarioType.DELAY, 0, 50, 1, 2000, Outcome.SUCCESS)).status()).isEqualTo("PASS");
    }
    @Test void consecutiveRunsDoNotShareWireMockState() {
        var config = scenario(ScenarioType.RECOVER_AFTER_FAILURES, 1, 0, 2, 2000, Outcome.RECOVERED);
        assertThat(runner.execute(config).attempts()).extracting(Attempt::status).containsExactly(503, 200);
        assertThat(runner.execute(config).attempts()).extracting(Attempt::status).containsExactly(503, 200);
    }
    @ParameterizedTest @ValueSource(ints = {0, 6, -1})
    void invalidAttemptCountRejectedBeforeRequests(int count) {
        assertThatThrownBy(() -> runner.execute(scenario(ScenarioType.NORMAL, 0, 0, count, 1000, Outcome.SUCCESS)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidStatusAndMixedScenariosAreRejected() {
        var validator = new ScenarioValidator();
        assertThatThrownBy(() -> validator.validate(new Scenario(ScenarioType.NORMAL, 0, 404, 0, 1, 1000, Outcome.SUCCESS)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(scenario(ScenarioType.NORMAL, 1, 100, 1, 1000, Outcome.SUCCESS)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
