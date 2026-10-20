package dev.apifaultlab;

import static dev.apifaultlab.DrillModels.*;
import java.util.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class DrillOrchestrator {
    private final LabSettings settings;
    private final KnowledgeService knowledge;
    private final AiGateway gateway;
    private final PlannerAgent planner;
    private final ReviewerAgent reviewer;
    private final ScenarioRunner runner;
    private final AiAssistanceService aiAssistance;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Map<String, Report> reports = new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    public static class BusyException extends IllegalStateException {
        public BusyException() { super("另一个演练或知识库重建正在运行"); }
    }
    private static final List<String> LIMITATIONS = List.of(
            "仅验证固定的本地 WireMock 订单样例，不代表生产系统可靠性。",
            "AI 审核与程序 PASS/FAIL 分开记录，模型对需求的理解仍可能出错。",
            "客户端请求超时后，WireMock 请求日志可能尚未完成记录。",
            "MANUAL 手动模式不检索知识；AI 辅助只解释需求、固定配置和执行摘要，不改变 Java 结果。");
    public DrillOrchestrator(LabSettings settings, KnowledgeService knowledge, AiGateway gateway,
                             PlannerAgent planner, ReviewerAgent reviewer, ScenarioRunner runner) {
        this(settings, knowledge, gateway, planner, reviewer, runner, new AiAssistanceService(gateway));
    }
    public DrillOrchestrator(LabSettings settings, KnowledgeService knowledge, AiGateway gateway,
                             PlannerAgent planner, ReviewerAgent reviewer, ScenarioRunner runner,
                             AiAssistanceService aiAssistance) {
        this.settings = settings; this.knowledge = knowledge; this.gateway = gateway;
        this.planner = planner; this.reviewer = reviewer; this.runner = runner; this.aiAssistance = aiAssistance;
    }
    public boolean busy() { return busy.get(); }
    public KnowledgeStatus rebuildKnowledge() {
        enter();
        try { return knowledge.rebuild(); } finally { busy.set(false); }
    }
    private void enter() { if (!busy.compareAndSet(false, true)) throw new BusyException(); }
    public Report get(String id) {
        if (!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new NoSuchElementException("演练记录不存在");
        if (reports.containsKey(id)) return reports.get(id);
        try { return json.readValue(Path.of(settings.runtimeDir(), "reports", id + ".json").toFile(), Report.class); }
        catch (java.io.IOException error) { throw new NoSuchElementException("演练记录不存在"); }
    }
    public Report run(String requirement, Scenario manual) {
        if (requirement == null || requirement.isBlank() || requirement.length() > 2000)
            throw new IllegalArgumentException("需求必须包含 1..2000 个字符");
        if (manual != null) new ScenarioValidator().validate(manual);
        enter();
        String id = UUID.randomUUID().toString();
        String created = Instant.now().toString();
        String mode = manual == null ? "AI" : "MANUAL";
        long modelStart = gateway.sequence();
        var rounds = new ArrayList<Round>();
        List<Chunk> chunks = List.of();
        String stage = "RETRIEVAL";
        try {
            save(report(id, created, mode, requirement, "INCOMPLETE", stage, chunks, rounds, modelStart, ""));
            if (manual != null) {
                var execution = runner.execute(manual);
                rounds.add(new Round(1, new Plan(manual, "使用用户明确填写的手动配置", List.of(), ""), execution, null, "", ""));
                var advice = aiAssistance.manual(requirement, manual, execution);
                return save(report(id, created, mode, requirement, execution.status(), "DONE",
                        chunks, rounds, modelStart, "", advice));
            }
            chunks = knowledge.retrieve(requirement);
            if (chunks.isEmpty()) return save(report(id, created, mode, requirement, "NEEDS_CLARIFICATION", stage, chunks, rounds, modelStart, "未检索到知识片段"));
            String correction = "";
            for (int number = 1; number <= 2; number++) {
                Plan plan = null; Execution execution = null; Review review = null;
                String revision = correction;
                try {
                    stage = "PLANNING";
                    save(report(id, created, mode, requirement, "INCOMPLETE", stage, chunks, rounds, modelStart, ""));
                    plan = planner.plan(requirement, chunks, correction);
                    validateCitations(plan.knowledgeChunkIds(), chunks);
                    if (!plan.clarification().isBlank()) {
                        rounds.add(new Round(number, plan, null, null, revision, ""));
                        return save(report(id, created, mode, requirement, "NEEDS_CLARIFICATION", stage, chunks, rounds, modelStart, plan.clarification()));
                    }
                    new ScenarioValidator().validate(plan.scenario());
                    stage = "EXECUTION";
                    execution = runner.execute(plan.scenario());
                    stage = "REVIEW";
                    var current = new Round(number, plan, execution, null, revision, "");
                    review = reviewer.review(requirement, chunks, current);
                    validateReview(requirement, chunks, current, review);
                    rounds.add(new Round(number, plan, execution, review, revision, ""));
                    save(report(id, created, mode, requirement, "INCOMPLETE", stage, chunks, rounds, modelStart, ""));
                    if (review.status() == ReviewStatus.APPROVED)
                        return save(report(id, created, mode, requirement, execution.status(), "DONE", chunks, rounds, modelStart, ""));
                    if (review.status() == ReviewStatus.EVIDENCE_INSUFFICIENT || number == 2)
                        return save(report(id, created, mode, requirement, "INCOMPLETE", "DONE", chunks, rounds, modelStart, "审核未通过，已保留实际执行结果"));
                    correction = review.revisionReason() + ": " + PlannerAgent.encode(review.issues())
                            + "；原始需求摘录：" + review.requirementQuote()
                            + "；保留原始意图，禁止为获得 PASS 放宽参数。";
                } catch (IllegalArgumentException invalidOutput) {
                    rounds.add(new Round(number, plan, execution, review, revision, invalidOutput.getMessage()));
                    if (number == 2)
                        return save(report(id, created, mode, requirement, "MODEL_ERROR", stage, chunks, rounds, modelStart, invalidOutput.getMessage()));
                    correction = "结构、配置或引用无效：" + invalidOutput.getMessage() + "。只能依据原始需求修正。";
                } catch (AiGateway.ModelFailure provider) {
                    rounds.add(new Round(number, plan, execution, review, revision, provider.getMessage()));
                    return save(report(id, created, mode, requirement, "MODEL_ERROR", stage, chunks, rounds, modelStart, provider.getMessage()));
                }
            }
            throw new IllegalStateException("意外达到轮次上限分支");
        } catch (AiGateway.ModelFailure provider) {
            return save(report(id, created, mode, requirement, "MODEL_ERROR", stage, chunks, rounds, modelStart, provider.getMessage()));
        } catch (RuntimeException error) {
            return save(report(id, created, mode, requirement, "EXECUTION_ERROR", stage, chunks, rounds, modelStart,
                    "基础设施错误：" + error.getClass().getSimpleName()));
        } finally { busy.set(false); }
    }
    private void validateCitations(List<String> ids, List<Chunk> chunks) {
        var known = chunks.stream().map(Chunk::chunkId).toList();
        if (ids == null || ids.isEmpty() || !known.containsAll(ids))
            throw new IllegalArgumentException("知识引用必须来自本次实际检索结果");
    }
    private void validateReview(String requirement, List<Chunk> chunks, Round round, Review review) {
        if (review == null || review.status() == null) throw new IllegalArgumentException("缺少审核状态");
        validateCitations(review.knowledgeChunkIds(), chunks);
        var evidence = new HashSet<String>();
        for (var attempt : round.execution().attempts()) evidence.add("round:" + round.number() + ":attempt:" + attempt.number());
        for (var assertion : round.execution().assertions()) evidence.add("round:" + round.number() + ":assertion:" + assertion.name());
        if (review.evidenceIds() == null || !evidence.containsAll(review.evidenceIds()))
            throw new IllegalArgumentException("审核引用的执行证据不存在");
        if (review.status() == ReviewStatus.APPROVED && review.evidenceIds().isEmpty())
            throw new IllegalArgumentException("审核通过必须引用实际执行证据");
        if (review.status() == ReviewStatus.NEEDS_REVISION) {
            if (review.issues() == null || review.issues().isEmpty()) throw new IllegalArgumentException("修订必须指出具体问题");
            if (!Set.of("CONFIG_TRANSLATION", "INVALID_CITATION").contains(review.revisionReason()))
                throw new IllegalArgumentException("仅允许修订需求翻译或引用问题");
            if (review.revisionReason().equals("CONFIG_TRANSLATION")
                    && (review.requirementQuote() == null || review.requirementQuote().length() < 2 || !requirement.contains(review.requirementQuote())))
                throw new IllegalArgumentException("需求翻译修订必须逐字引用原始需求");
        }
    }
    private Report report(String id, String created, String mode, String requirement, String status, String stage,
                          List<Chunk> chunks, List<Round> rounds, long modelStart, String error) {
        return report(id, created, mode, requirement, status, stage, chunks, rounds, modelStart, error,
                AiAssistance.notRequested());
    }
    private Report report(String id, String created, String mode, String requirement, String status, String stage,
                          List<Chunk> chunks, List<Round> rounds, long modelStart, String error,
                          AiAssistance aiAssistance) {
        return new Report(id, created, mode, requirement, status, stage, List.copyOf(chunks), List.copyOf(rounds),
                gateway.callsAfter(modelStart), error, LIMITATIONS, aiAssistance);
    }
    private Report save(Report report) {
        reports.put(report.id(), report);
        if (reports.size() > 100) reports.values().stream().filter(r -> !r.status().equals("INCOMPLETE"))
                .min(Comparator.comparing(Report::createdAt)).ifPresent(old -> reports.remove(old.id()));
        try {
            var dir = Path.of(settings.runtimeDir(), "reports");
            Files.createDirectories(dir);
            var temp = dir.resolve(report.id() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), report);
            Files.move(temp, dir.resolve(report.id() + ".json"), StandardCopyOption.REPLACE_EXISTING);
            return report;
        } catch (java.io.IOException error) { throw new IllegalStateException("无法保存演练报告"); }
    }
}
