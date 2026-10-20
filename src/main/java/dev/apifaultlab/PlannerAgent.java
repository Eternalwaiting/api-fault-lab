package dev.apifaultlab;
import java.util.List;
import static dev.apifaultlab.DrillModels.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.core.io.ClassPathResource;

public class PlannerAgent {
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final AiGateway gateway;
    public PlannerAgent(AiGateway gateway) { this.gateway = gateway; }
    public Plan plan(String requirement, List<Chunk> chunks, String correction) {
        String response = gateway.chat("PLANNER", prompt("规划智能体"), encode(java.util.Map.of(
                "requirement", requirement, "retrievedKnowledge", chunks, "correction", correction)));
        try {
            var result = JSON.readValue(response, Plan.class);
            if (result == null || result.explanation() == null || result.knowledgeChunkIds() == null || result.clarification() == null)
                throw new IllegalArgumentException("规划结果缺少必填字段");
            if (result.explanation().length() > 2000 || result.clarification().length() > 500)
                throw new IllegalArgumentException("规划说明超过长度上限");
            return result;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("规划结果不是有效的 JSON"); }
    }
    static String prompt(String name) {
        try (var stream = new ClassPathResource("prompts/" + name + ".txt").getInputStream()) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) { throw new IllegalStateException("提示词资源不存在"); }
    }
    static String encode(Object data) {
        try { return JSON.writeValueAsString(data); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("无法编码模型上下文"); }
    }
}
