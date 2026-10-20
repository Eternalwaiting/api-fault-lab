package dev.apifaultlab;

import static dev.apifaultlab.DrillModels.*;
import java.util.Map;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@RequestMapping("/api")
public class DrillController {
    public record Request(@NotBlank @Size(max = 2000) String requirement, Scenario manualScenario) {}
    private final LabSettings settings;
    private final AiGateway gateway;
    private final KnowledgeService knowledge;
    private final DrillOrchestrator orchestrator;
    private final ReadOnlyProbeService readOnlyProbe;
    public DrillController(LabSettings settings, AiGateway gateway, KnowledgeService knowledge,
                           DrillOrchestrator orchestrator, ReadOnlyProbeService readOnlyProbe) {
        this.settings = settings; this.gateway = gateway; this.knowledge = knowledge; this.orchestrator = orchestrator;
        this.readOnlyProbe = readOnlyProbe;
    }
    @GetMapping("/status") public Map<String,Object> status() {
        return Map.of("busy", orchestrator.busy(), "knowledge", knowledge.status(),
                "ai", Map.of("configured", settings.configured(), "chatConfigured", settings.chatConfigured(),
                        "embeddingConfigured", settings.embeddingConfigured(), "chatModel", settings.chatModel(),
                        "embeddingModel", settings.embeddingModel(), "verification", "请查看当次演练的实际模型调用记录"),
                "modelCalls", gateway.callsAfter(Math.max(0, gateway.sequence() - 20)),
                "limits", Map.of("maxAttempts", 5, "maxFailures", 3, "maxDelayMs", 3000, "maxTimeoutMs", 5000, "runnerDeadlineMs", 30_000));
    }
    @PostMapping("/drills") public Report run(@Valid @RequestBody Request request, HttpServletRequest servlet) {
        checkOrigin(servlet);
        return orchestrator.run(request.requirement(), request.manualScenario());
    }
    @PostMapping("/knowledge/rebuild") public KnowledgeStatus rebuild(HttpServletRequest servlet) {
        checkOrigin(servlet);
        return orchestrator.rebuildKnowledge();
    }
    public record ReadOnlyTargetRequest(@NotBlank @Size(max = 64) String deviceId) {}
    public record ReadOnlyRunRequest(@Min(1) @Max(ReadOnlyProbeService.MAX_REQUESTS) int requestCount) {}
    @GetMapping("/read-only/status") public ReadOnlyProbeService.TargetStatus readOnlyStatus() {
        return readOnlyProbe.status();
    }
    @PostMapping("/read-only/config") public ReadOnlyProbeService.TargetStatus configureReadOnly(
            @Valid @RequestBody ReadOnlyTargetRequest request, HttpServletRequest servlet) {
        checkOrigin(servlet);
        return readOnlyProbe.configure(request.deviceId());
    }
    @PostMapping("/read-only/run") public ReadOnlyProbeService.ProbeReport runReadOnly(
            @Valid @RequestBody ReadOnlyRunRequest request, HttpServletRequest servlet) {
        checkOrigin(servlet);
        return readOnlyProbe.run(new ReadOnlyProbeService.RunRequest(request.requestCount()));
    }
    @GetMapping("/read-only/{id}") public ReadOnlyProbeService.ProbeReport getReadOnly(@PathVariable String id) {
        return readOnlyProbe.get(id);
    }
    @GetMapping(value = "/read-only/{id}/report", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportReadOnly(@PathVariable String id) {
        var report = readOnlyProbe.get(id);
        var text = new StringBuilder("# API Fault Lab 只读接口测试报告\n\n")
                .append("- 任务编号：").append(report.id())
                .append("\n- 时间：").append(report.createdAt())
                .append("\n- 模式：").append(report.mode())
                .append("\n- 结果：").append(report.status())
                .append("\n- 目标：").append(report.targetId())
                .append("\n- 路径：`GET ").append(report.endpoint()).append("`\n")
                .append("\n## 请求摘要\n\n");
        for (var attempt : report.attempts()) {
            text.append("- 第 ").append(attempt.number()).append(" 次：HTTP ")
                    .append(attempt.httpStatus() == null ? "无响应" : attempt.httpStatus())
                    .append("，").append(attempt.elapsedMs()).append("ms，").append(attempt.result())
                    .append("；业务码=").append(attempt.businessCode())
                    .append("；记录数=").append(attempt.recordsReturned())
                    .append("；结构有效=").append(attempt.responseShapeValid())
                    .append("；目标设备匹配=").append(attempt.deviceIdMatches())
                    .append("；采样时间存在=").append(attempt.sampleTimePresent())
                    .append("；指标存在=").append(attempt.hasMetric()).append("\n");
        }
        text.append("\n## 数据范围与副作用\n\n").append(report.sideEffect())
                .append("\n\n未保存或导出设备 ID、采样值、采样时间和响应正文。");
        text.append("\n\n## AI 辅助解释\n\n```json\n")
                .append(PlannerAgent.encode(report.aiAssistance())).append("\n```\n")
                .append("\nAI 仅收到 HTTP 状态、耗时、记录数和程序断言布尔值；其输出不改变 Java 结果。");
        if (!report.error().isBlank()) text.append("\n\n执行说明：").append(report.error());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"api-fault-lab-read-only-" + UUID.fromString(report.id()) + ".md\"")
                .body(text.toString());
    }
    @GetMapping("/drills/{id}") public Report get(@PathVariable String id) { return orchestrator.get(id); }
    @GetMapping(value = "/drills/{id}/report", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> export(@PathVariable String id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"api-fault-lab-" + id + ".md\"")
                .body(markdown(orchestrator.get(id)));
    }
    private void checkOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !List.of("http://127.0.0.1:" + request.getLocalPort(), "http://localhost:" + request.getLocalPort()).contains(origin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "禁止跨来源网页执行写入操作");
    }
    @ExceptionHandler(DrillOrchestrator.BusyException.class)
    ResponseEntity<Map<String,String>> busy() { return error(409, "BUSY", "另一个演练或知识库重建正在运行"); }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,String>> invalid() { return error(400, "INVALID_REQUEST", "请检查必填字段、场景参数范围和 JSON 类型"); }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String,String>> missing() { return error(404, "NOT_FOUND", "演练记录不存在"); }
    @ExceptionHandler(AiGateway.ModelFailure.class)
    ResponseEntity<Map<String,String>> model(AiGateway.ModelFailure exception) { return error(502, "MODEL_ERROR", exception.getMessage()); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String,String>> infrastructure() { return error(500, "EXECUTION_ERROR", "本地状态操作失败，请检查知识目录和运行目录"); }
    private ResponseEntity<Map<String,String>> error(int code, String status, String message) {
        return ResponseEntity.status(code).body(Map.of("status", status, "message", message));
    }
    private String markdown(Report report) {
        var text = new StringBuilder("# API Fault Lab 演练报告\n\n");
        text.append("- 任务编号：").append(report.id()).append("\n- 创建时间：").append(report.createdAt())
                .append("\n- 演练模式：").append(report.mode()).append("\n- 流程结果：").append(report.status())
                .append("\n- 执行阶段：").append(report.stage()).append("\n\n## 原始需求\n\n").append(report.requirement()).append("\n");
        if (!report.error().isBlank()) text.append("\n错误或澄清问题：").append(report.error()).append("\n");
        text.append("\n## 检索知识与来源\n");
        for (var chunk : report.knowledge()) text.append("\n### ").append(chunk.source()).append(" / ").append(chunk.chunkId())
                .append("\n\n相似度（不是正确概率）：").append(chunk.score()).append("\n\n").append(chunk.content()).append("\n");
        for (var round : report.rounds()) {
            text.append("\n## 第 ").append(round.number()).append(" 轮\n\n修订原因：").append(round.revisionReason())
                    .append("\n\n本轮错误：").append(round.error()).append("\n\n### 规划配置\n\n```json\n")
                    .append(PlannerAgent.encode(round.plan())).append("\n```\n");
            if (round.execution() != null) {
                text.append("\n### 实际 HTTP 执行记录\n\n程序结果：").append(round.execution().status())
                        .append("\n\n实际观察：").append(round.execution().observedOutcome())
                        .append("\n\nWireMock 请求日志条数：").append(round.execution().serverRequests()).append("\n\n");
                for (var attempt : round.execution().attempts())
                    text.append("- 第 ").append(attempt.number()).append(" 次尝试：").append(attempt.status()).append(" / ")
                            .append(attempt.result()).append(" / ").append(attempt.elapsedMs()).append("ms / ").append(attempt.target()).append("\n")
                            .append("\n```json\n").append(attempt.body()).append("\n```\n\n");
                for (var assertion : round.execution().assertions())
                    text.append("- ").append(assertion.passed() ? "PASS " : "FAIL ").append(assertion.name()).append(": ").append(assertion.detail()).append("\n");
            }
            text.append("\n### 独立审核\n\n```json\n").append(PlannerAgent.encode(round.review())).append("\n```\n");
        }
        if ("MANUAL".equals(report.mode())) {
            text.append("\n## AI 辅助解释\n\n```json\n").append(PlannerAgent.encode(report.aiAssistance()))
                    .append("\n```\n\nAI 输出仅作参考，不修改手动配置、执行记录或程序结论。\n");
        }
        text.append("\n## 模型调用元数据\n\n```json\n").append(PlannerAgent.encode(report.modelCalls())).append("\n```\n\n## 限制与未验证事项\n\n");
        for (var limit : report.limitations()) text.append("- ").append(limit).append("\n");
        return text.toString();
    }
}
