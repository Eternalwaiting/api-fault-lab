package dev.apifaultlab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static dev.apifaultlab.DrillModels.*;

class DrillOrchestratorTest {
    @TempDir Path temp;
    private final Scenario scenario = new Scenario(ScenarioType.RECOVER_AFTER_FAILURES, 2, 503, 0, 2, 1000, Outcome.RECOVERED);
    private final Plan plan = new Plan(scenario, "two attempts includes first", List.of("k1"), "");
    private final Chunk chunk = new Chunk("k1", "rule.md", "Attempts", "maxAttempts includes first", .8);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final PlannerAgent planner = mock(PlannerAgent.class);
    private final ReviewerAgent reviewer = mock(ReviewerAgent.class);
    private final ScenarioRunner runner = mock(ScenarioRunner.class);
    private DrillOrchestrator orchestrator() {
        when(knowledge.retrieve(anyString())).thenReturn(List.of(chunk));
        when(planner.plan(anyString(), anyList(), anyString())).thenReturn(plan);
        when(runner.execute(any())).thenReturn(new Execution("FAIL", "EXHAUSTED",
                List.of(new Attempt(1, 503, 5, "HTTP_ERROR", "{}", "http://127.0.0.1:1/orders/ORD-1001")),
                List.of(new Assertion("expectedOutcome", false, "not recovered")), 1, 5));
        return new DrillOrchestrator(new LabSettings("", "", "", "", "", "", 3, "knowledge", temp.toString()),
                knowledge, gateway, planner, reviewer, runner);
    }
    private Review approved() { return new Review(ReviewStatus.APPROVED, List.of(), "FAIL is expected from evidence", List.of("k1"), List.of("round:1:attempt:1"), "NONE", ""); }

    @Test void failAndApprovedStaySeparate() {
        var orchestrator = orchestrator();
        when(reviewer.review(anyString(), anyList(), any())).thenReturn(approved());
        var result = orchestrator.run("two failures with two attempts", null);
        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.rounds().getFirst().review().status()).isEqualTo(ReviewStatus.APPROVED);
        verify(planner, times(1)).plan(anyString(), anyList(), anyString());
    }
    @Test void revisionRetainsOriginalRoundAndStopsAfterTwo() {
        var orchestrator = orchestrator();
        when(reviewer.review(anyString(), anyList(), any())).thenReturn(new Review(ReviewStatus.NEEDS_REVISION,
                List.of("configuration translation"), "needs correction", List.of("k1"), List.of(),
                "CONFIG_TRANSLATION", "two attempts"));
        var result = orchestrator.run("two failures with two attempts", null);
        assertThat(result.rounds()).hasSize(2);
        assertThat(result.rounds().getFirst().execution().status()).isEqualTo("FAIL");
        assertThat(result.status()).isEqualTo("INCOMPLETE");
        verify(planner, times(2)).plan(anyString(), anyList(), anyString());
        verify(reviewer, times(2)).review(anyString(), anyList(), any());
    }
    @Test void inventedKnowledgeCitationsNeverExecuteAndStopAfterOneRepair() {
        var orchestrator = orchestrator();
        when(planner.plan(anyString(), anyList(), anyString()))
                .thenReturn(new Plan(scenario, "bad citation", List.of("invented"), ""));
        var report = orchestrator.run("two attempts", null);
        assertThat(report.status()).isEqualTo("MODEL_ERROR");
        assertThat(report.rounds()).hasSize(2);
        verifyNoInteractions(runner);
    }
    @Test void providerFailurePreservesErrorWithoutCreatingExecution() {
        var orchestrator = orchestrator();
        when(planner.plan(anyString(), anyList(), anyString())).thenThrow(new AiGateway.ModelFailure("provider timeout"));
        var report = orchestrator.run("two attempts", null);
        assertThat(report.status()).isEqualTo("MODEL_ERROR");
        verifyNoInteractions(runner);
        verify(planner, times(1)).plan(anyString(), anyList(), anyString());
    }
    @Test void insufficientEvidenceDoesNotCausePassSeekingRevision() {
        var orchestrator = orchestrator();
        when(reviewer.review(anyString(), anyList(), any())).thenReturn(new Review(ReviewStatus.EVIDENCE_INSUFFICIENT,
                List.of("missing evidence"), "cannot approve", List.of("k1"), List.of(), "NONE", ""));
        var report = orchestrator.run("two attempts", null);
        assertThat(report.status()).isEqualTo("INCOMPLETE");
        verify(planner, times(1)).plan(anyString(), anyList(), anyString());
    }
    @Test void clarificationMakesNoHttpRequests() {
        var orchestrator = orchestrator();
        when(planner.plan(anyString(), anyList(), anyString())).thenReturn(new Plan(null, "", List.of("k1"), "What is the attempt limit?"));
        assertThat(orchestrator.run("retry please", null).status()).isEqualTo("NEEDS_CLARIFICATION");
        verifyNoInteractions(runner, reviewer);
    }
    @Test void manualModeKeepsJavaResultWhenAssistanceFails() {
        var orchestrator = orchestrator();
        var report = orchestrator.run("manual config", scenario);
        assertThat(report.mode()).isEqualTo("MANUAL");
        assertThat(report.status()).isEqualTo("FAIL");
        assertThat(report.aiAssistance().status()).isEqualTo("FAILED");
        assertThat(report.rounds().getFirst().execution().status()).isEqualTo("FAIL");
        verifyNoInteractions(planner, reviewer);
        verify(knowledge, never()).retrieve(anyString());
        verify(gateway, times(1)).chat(eq("MANUAL_ASSISTANCE"), anyString(), anyString());
    }
    @Test void busyRejectsConcurrentDrillAndRebuildAndReleasesAfterFinish() throws Exception {
        var orchestrator = orchestrator();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(planner.plan(anyString(), anyList(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
            return plan;
        });
        when(reviewer.review(anyString(), anyList(), any())).thenReturn(approved());
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> orchestrator.run("two attempts", null));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> orchestrator.run("concurrent", scenario)).isInstanceOf(DrillOrchestrator.BusyException.class);
                assertThatThrownBy(orchestrator::rebuildKnowledge).isInstanceOf(DrillOrchestrator.BusyException.class);
            } finally { release.countDown(); }
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(orchestrator.busy()).isFalse();
        }
    }
    @Test void reviewerFailureRetainsActualHttpEvidence() {
        var orchestrator = orchestrator();
        when(reviewer.review(anyString(), anyList(), any())).thenThrow(new AiGateway.ModelFailure("review service timeout"));
        var report = orchestrator.run("two attempts", null);
        assertThat(report.status()).isEqualTo("MODEL_ERROR");
        assertThat(report.rounds().getFirst().execution().attempts()).hasSize(1);
        verify(planner, times(1)).plan(anyString(), anyList(), anyString());
    }
}
