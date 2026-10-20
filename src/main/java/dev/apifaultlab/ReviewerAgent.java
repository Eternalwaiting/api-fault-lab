package dev.apifaultlab;
import java.util.List;
import static dev.apifaultlab.DrillModels.*;

public class ReviewerAgent {
    private final AiGateway gateway;
    public ReviewerAgent(AiGateway gateway) { this.gateway = gateway; }
    public Review review(String requirement, List<Chunk> chunks, Round round) {
        String response = gateway.chat("REVIEWER", PlannerAgent.prompt("审核智能体"), PlannerAgent.encode(java.util.Map.of(
                "requirement", requirement, "retrievedKnowledge", chunks, "round", round)));
        try {
            var result = PlannerAgent.JSON.readValue(response, Review.class);
            if (result == null || result.status() == null || result.issues() == null || result.explanation() == null
                    || result.knowledgeChunkIds() == null || result.evidenceIds() == null
                    || result.revisionReason() == null || result.requirementQuote() == null)
                throw new IllegalArgumentException("审核结果缺少必填字段");
            if (result.issues().size() > 8 || result.explanation().length() > 3000)
                throw new IllegalArgumentException("审核说明超过长度上限");
            return result;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("审核结果不是有效的 JSON"); }
    }
}
