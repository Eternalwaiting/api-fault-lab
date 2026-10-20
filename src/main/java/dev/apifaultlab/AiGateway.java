package dev.apifaultlab;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.*;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import com.openai.errors.OpenAIServiceException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AiGateway {
    private static final int MANUAL_ASSISTANCE_TIMEOUT_SECONDS = 12;
    private final LabSettings settings;
    private final List<ModelCall> calls = new ArrayList<>();
    private final AtomicInteger dimension = new AtomicInteger();
    private EmbeddingModel embedding;
    private final Map<Integer, OpenAiChatModel> chats = new ConcurrentHashMap<>();
    public record ModelCall(long sequence, String role, String model, String status, long elapsedMs,
                            Integer promptTokens, Integer completionTokens, Integer dimensions, String error) {}
    public static class ModelFailure extends IllegalStateException {
        public ModelFailure(String message) { super(message); }
        private ModelFailure(String message, Exception cause) { super(message); }
    }
    public AiGateway(LabSettings settings) { this.settings = settings; }
    public synchronized List<ModelCall> callsAfter(long sequence) {
        return calls.stream().filter(c -> c.sequence() > sequence).toList();
    }
    public synchronized long sequence() { return calls.isEmpty() ? 0 : calls.getLast().sequence(); }
    private synchronized void record(String role, String model, String status, long start, Integer input, Integer output, Integer dimensions, String error) {
        long next = sequence() + 1;
        calls.add(new ModelCall(next, role, model, status, (System.nanoTime() - start) / 1_000_000, input, output, dimensions, error));
        if (calls.size() > 256) calls.removeFirst();
    }
    public synchronized EmbeddingModel embedding() {
        // 使用时才校验配置，让不依赖 AI 的手动演练仍能启动。
        if (embedding == null) embedding = new EmbeddingModel() {
            private OpenAiEmbeddingModel delegate;
            public float[] embed(Document document) { return embed(document.getText()); }
            public int dimensions() {
                if (dimension.get() == 0) embed("API Fault Lab dimension check");
                return dimension.get();
            }
            public EmbeddingResponse call(EmbeddingRequest request) {
                long start = System.nanoTime();
                int timeout = timeoutSeconds("EMBEDDING");
                try {
                    if (!settings.embeddingConfigured()) throw new ModelFailure("缺少 Embedding 配置");
                    validateBaseUrl(settings.embeddingBaseUrl());
                    if (delegate == null) delegate = OpenAiEmbeddingModel.builder()
                            .metadataMode(MetadataMode.EMBED)
                            .options(OpenAiEmbeddingOptions.builder().baseUrl(settings.embeddingBaseUrl())
                                    .apiKey(settings.embeddingApiKey()).model(settings.embeddingModel())
                                    .timeout(Duration.ofSeconds(timeout)).maxRetries(0).build())
                            .build();
                    var response = delegate.call(request);
                    if (response.getResults().size() != request.getInstructions().size()) throw new ModelFailure("Embedding 向量数量与输入数量不一致");
                    for (var result : response.getResults()) {
                        var vector = result.getOutput();
                        if (vector.length == 0) throw new ModelFailure("Embedding 返回空向量");
                        double norm = 0;
                        for (float value : vector) {
                            if (!Float.isFinite(value)) throw new ModelFailure("Embedding 向量包含非有限数值");
                            norm += value * (double) value;
                        }
                        if (norm == 0) throw new ModelFailure("Embedding 返回零向量");
                        dimension.compareAndSet(0, vector.length);
                        if (dimension.get() != vector.length) throw new ModelFailure("Embedding 向量维度发生变化");
                    }
                    record("EMBEDDING", settings.embeddingModel(), "SUCCESS", start, response.getMetadata().getUsage().getPromptTokens(), null, dimension.get(), "");
                    return response;
                } catch (Exception error) {
                    String safe = failureDiagnostic(error, "EMBEDDING_REQUEST", start, timeout);
                    record("EMBEDDING", settings.embeddingModel(), "ERROR", start, null, null, null, safe);
                    throw new ModelFailure(safe, error);
                }
            }
        };
        return embedding;
    }
    public String chat(String role, String system, String user) {
        long start = System.nanoTime();
        int timeout = timeoutSeconds(role);
        try {
            if (!settings.chatConfigured()) throw new ModelFailure("缺少对话模型配置");
            validateBaseUrl(settings.chatBaseUrl());
            var model = chats.computeIfAbsent(timeout, ignored -> OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder().baseUrl(settings.chatBaseUrl())
                            .apiKey(settings.chatApiKey()).model(settings.chatModel())
                            .timeout(Duration.ofSeconds(timeout)).maxRetries(0)
                            .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                                    .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT).build())
                            .build())
                    .build());
            var response = model.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
            String content = response.getResult().getOutput().getText();
            if (content == null || content.isBlank() || content.length() > 24_000) throw new ModelFailure("对话模型输出为空或超过长度上限");
            var usage = response.getMetadata().getUsage();
            record(role, settings.chatModel(), "SUCCESS", start, usage.getPromptTokens(), usage.getCompletionTokens(), null, "");
            return content;
        } catch (Exception error) {
            String safe = failureDiagnostic(error, "CHAT_REQUEST", start, timeout);
            record(role, settings.chatModel(), "ERROR", start, null, null, null, safe);
            throw new ModelFailure(safe, error);
        }
    }
    private int timeoutSeconds(String role) {
        int configured = Math.clamp(settings.modelTimeoutSeconds(), 1, 120);
        return "MANUAL_ASSISTANCE".equals(role)
                ? Math.min(configured, MANUAL_ASSISTANCE_TIMEOUT_SECONDS) : configured;
    }
    private String failureDiagnostic(Exception error, String stage, long start, int timeoutSeconds) {
        if (error instanceof ModelFailure) return error.getMessage();
        return safeError(error) + "；阶段=" + stage + "；耗时=" + ((System.nanoTime() - start) / 1_000_000)
                + "ms；超时配置=" + timeoutSeconds + "s";
    }
    private void validateBaseUrl(String baseUrl) {
        var uri = java.net.URI.create(baseUrl);
        boolean local = Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(Objects.toString(uri.getHost(), ""));
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local))) {
            throw new ModelFailure("模型 Base URL 必须使用 HTTPS；仅回环测试地址允许 HTTP");
        }
    }
    private String safeError(Exception error) {
        if (error instanceof ModelFailure) return error.getMessage();
        if (error instanceof OpenAIServiceException serviceError)
            return "模型请求失败：HTTP " + serviceError.statusCode()
                    + "；异常类=" + exceptionTypeChain(serviceError);
        // Only exception types cross this boundary; messages and stack traces may contain credentials or response data.
        return "模型请求失败：" + exceptionTypeChain(error);
    }
    private String exceptionTypeChain(Throwable error) {
        var names = new ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = error; current != null && seen.add(current) && names.size() < 6;
             current = current.getCause()) {
            names.add(current.getClass().getSimpleName());
        }
        return String.join(" -> ", names);
    }
}
