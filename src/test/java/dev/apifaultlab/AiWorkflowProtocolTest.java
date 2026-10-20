package dev.apifaultlab;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static dev.apifaultlab.DrillModels.*;

// 使用明确标记的合成 OpenAI 协议服务串联完整流程。
// 只证明组件装配和上下文传递，不证明真实模型能力。
class AiWorkflowProtocolTest {
    @TempDir Path temp;
    @Test void vectorRetrievalPlannerHttpExecutionAndReviewerAreReallyWiredTogether() throws Exception {
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            server.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(okJson("""
                {"object":"list","model":"protocol-fixture","data":[{"object":"embedding","index":0,"embedding":[0.8,0.3,0.1]}],"usage":{"prompt_tokens":3,"total_tokens":3}}
                """)));
            var directory = Files.createDirectory(temp.resolve("knowledge"));
            Files.writeString(directory.resolve("project.md"), "# Lab rule\nUNIQUE-CONTEXT: maxAttempts includes first request. Default timeout 1200.");
            Files.writeString(directory.resolve("timeout-rules.md"),
                    "# LAB-DEFAULT-TIMEOUT\nUNIQUE-REQUIRED-CONTEXT: default timeout comes from this file; explicit values take precedence.");
            var settings = new LabSettings("http://127.0.0.1:" + server.port() + "/v1", "protocol-fixture", "test-only-key",
                    "http://127.0.0.1:" + server.port() + "/v1", "protocol-fixture", "test-only-key", 3, directory.toString(), temp.resolve("runtime").toString());
            var gateway = new AiGateway(settings);
            var knowledge = new KnowledgeService(settings, gateway.embedding());
            knowledge.rebuild();
            var chunks = knowledge.retrieve("first error then recover");
            String chunkId = chunks.getFirst().chunkId();
            var plan = new Plan(new Scenario(ScenarioType.RECOVER_AFTER_FAILURES, 1, 503, 0, 2, 1200, Outcome.RECOVERED),
                    "maxAttempts includes first", List.of(chunkId), "");
            var review = new Review(ReviewStatus.APPROVED, List.of(), "Actual 503 then 200 matches config", List.of(chunkId),
                    List.of("round:1:attempt:1", "round:1:attempt:2", "round:1:assertion:expectedOutcome"), "NONE", "");
            server.stubFor(post(urlEqualTo("/v1/chat/completions")).withRequestBody(containing("规划智能体（PLANNER）"))
                    .willReturn(okJson(chatResponse(PlannerAgent.encode(plan)))));
            server.stubFor(post(urlEqualTo("/v1/chat/completions")).withRequestBody(containing("审核智能体（REVIEWER）"))
                    .willReturn(okJson(chatResponse(PlannerAgent.encode(review)))));
            var orchestrator = new DrillOrchestrator(settings, knowledge, gateway, new PlannerAgent(gateway),
                    new ReviewerAgent(gateway), new ScenarioRunner());
            var report = orchestrator.run("First 503 then recover within two attempts", null);
            assertThat(report.status()).isEqualTo("PASS");
            assertThat(report.rounds().getFirst().execution().attempts()).extracting(Attempt::status).containsExactly(503, 200);
            assertThat(report.modelCalls()).extracting(AiGateway.ModelCall::role).containsExactly("EMBEDDING", "PLANNER", "REVIEWER");
            server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                    .withRequestBody(containing("规划智能体（PLANNER）")).withRequestBody(containing("UNIQUE-CONTEXT"))
                    .withRequestBody(containing("UNIQUE-REQUIRED-CONTEXT")));
            server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                    .withRequestBody(containing("审核智能体（REVIEWER）")).withRequestBody(containing("UNIQUE-CONTEXT"))
                    .withRequestBody(containing("UNIQUE-REQUIRED-CONTEXT"))
                    .withRequestBody(containing("VALID_ORDER")));
        } finally { server.stop(); }
    }
    private String chatResponse(String content) {
        return "{\"id\":\"synthetic-fixture\",\"object\":\"chat.completion\",\"model\":\"protocol-fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + PlannerAgent.encode(content) + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";
    }
}
