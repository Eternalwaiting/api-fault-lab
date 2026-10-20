package dev.apifaultlab;

import static dev.apifaultlab.DrillModels.*;

public class ScenarioValidator {
    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_FAILURES = 3;
    public static final int MAX_DELAY_MS = 3000;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 5000;
    public static final int DEADLINE_MS = 30_000;
    public static final java.util.Set<Integer> FAILURE_STATUSES = java.util.Set.of(500, 502, 503, 504);

    public void validate(Scenario scenario) {
        if (scenario == null || scenario.scenarioType() == null || scenario.expectedOutcome() == null)
            throw new IllegalArgumentException("必须填写 scenarioType 和 expectedOutcome");
        if (scenario.maxAttempts() < 1 || scenario.maxAttempts() > MAX_ATTEMPTS)
            throw new IllegalArgumentException("maxAttempts 必须为 1..5，包含首次请求");
        if (scenario.failureCount() < 0 || scenario.failureCount() > MAX_FAILURES)
            throw new IllegalArgumentException("failureCount 必须为 0..3");
        if (!FAILURE_STATUSES.contains(scenario.failureStatus()))
            throw new IllegalArgumentException("failureStatus 只能是 500、502、503 或 504");
        if (scenario.responseDelayMs() < 0 || scenario.responseDelayMs() > MAX_DELAY_MS)
            throw new IllegalArgumentException("responseDelayMs 必须为 0..3000");
        if (scenario.requestTimeoutMs() < MIN_TIMEOUT_MS || scenario.requestTimeoutMs() > MAX_TIMEOUT_MS)
            throw new IllegalArgumentException("requestTimeoutMs 必须为 100..5000");
        if (scenario.scenarioType() != ScenarioType.RECOVER_AFTER_FAILURES && scenario.failureCount() != 0)
            throw new IllegalArgumentException("仅失败后恢复场景允许非零 failureCount");
        if (scenario.scenarioType() == ScenarioType.RECOVER_AFTER_FAILURES && scenario.failureCount() == 0)
            throw new IllegalArgumentException("失败后恢复场景的故障次数必须为 1..3");
        if (scenario.scenarioType() != ScenarioType.DELAY && scenario.responseDelayMs() != 0)
            throw new IllegalArgumentException("仅 DELAY 场景允许非零 responseDelayMs");
    }
}
