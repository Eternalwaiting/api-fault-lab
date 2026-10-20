package dev.apifaultlab;

import java.util.List;

public final class DrillModels {
    private DrillModels() {}
    public enum ScenarioType { NORMAL, CONTINUOUS_ERROR, DELAY, RECOVER_AFTER_FAILURES }
    public enum Outcome { SUCCESS, RECOVERED, EXHAUSTED, TIMEOUT }
    public record Scenario(ScenarioType scenarioType, int failureCount, int failureStatus,
                           int responseDelayMs, int maxAttempts, int requestTimeoutMs,
                           Outcome expectedOutcome) {}
    public record Attempt(int number, Integer status, long elapsedMs, String result, String body, String target) {}
    public record Assertion(String name, boolean passed, String detail) {}
    public record Execution(String status, String observedOutcome, List<Attempt> attempts,
                            List<Assertion> assertions, int serverRequests, long elapsedMs) {}
    public record Chunk(String chunkId, String source, String title, String content, Double score) {}
    public record KnowledgeStatus(boolean ready, int chunks, int dimensions, String fingerprint, String state) {}
    public record Plan(Scenario scenario, String explanation, List<String> knowledgeChunkIds, String clarification) {}
    public enum ReviewStatus { APPROVED, NEEDS_REVISION, EVIDENCE_INSUFFICIENT }
    public record Review(ReviewStatus status, List<String> issues, String explanation,
                         List<String> knowledgeChunkIds, List<String> evidenceIds, String revisionReason, String requirementQuote) {}
    public record Round(int number, Plan plan, Execution execution, Review review, String revisionReason, String error) {}
    public record AiAssistance(String status, String explanation, List<String> findings, String error) {
        public static AiAssistance notRequested() { return new AiAssistance("NOT_REQUESTED", "", List.of(), ""); }
    }
    public record Report(String id, String createdAt, String mode, String requirement, String status, String stage,
                         List<Chunk> knowledge, List<Round> rounds, List<AiGateway.ModelCall> modelCalls,
                         String error, List<String> limitations, AiAssistance aiAssistance) {
        public Report(String id, String createdAt, String mode, String requirement, String status, String stage,
                      List<Chunk> knowledge, List<Round> rounds, List<AiGateway.ModelCall> modelCalls,
                      String error, List<String> limitations) {
            this(id, createdAt, mode, requirement, status, stage, knowledge, rounds, modelCalls,
                    error, limitations, AiAssistance.notRequested());
        }
    }
}
