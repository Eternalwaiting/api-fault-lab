package dev.apifaultlab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class KnowledgeServiceTest {
    @TempDir Path temp;
    // 明确的合成向量测试桩，不用于正式运行或真实 AI 验收。
    private final AtomicInteger calls = new AtomicInteger();
    private EmbeddingModel fixture() {
        return new EmbeddingModel() {
            public float[] embed(Document doc) { return embed(doc.getText()); }
            public EmbeddingResponse call(EmbeddingRequest request) {
                calls.incrementAndGet();
                var vectors = new ArrayList<Embedding>();
                for (int i = 0; i < request.getInstructions().size(); i++) {
                    String text = request.getInstructions().get(i);
                    vectors.add(new Embedding(new float[]{text.contains("ALPHA") ? 1 : 0, text.contains("BETA") ? 1 : 0, 0.1f}, i));
                }
                return new EmbeddingResponse(vectors);
            }
        };
    }
    private LabSettings settings() {
        return new LabSettings("", "", "", "http://localhost/v1", "test-fixture", "", 3, temp.resolve("knowledge").toString(), temp.resolve("runtime").toString());
    }
    @Test void buildsVectorIndexRetrievesSourcesAndRebuildsAfterKnowledgeChange() throws Exception {
        Files.createDirectory(temp.resolve("knowledge"));
        Files.writeString(temp.resolve("knowledge/rule.md"), "# Project rule\nALPHA rule timeout=700\n");
        Files.writeString(temp.resolve("knowledge/other.md"), "# Other\nBETA unrelated text\n");
        var service = new KnowledgeService(settings(), fixture());
        assertThat(service.rebuild().ready()).isTrue();
        var hits = service.retrieve("ALPHA");
        assertThat(hits.getFirst().source()).isEqualTo("rule.md");
        assertThat(hits.getFirst().content()).contains("timeout=700");
        String previousFingerprint = service.status().fingerprint();
        Files.writeString(temp.resolve("knowledge/rule.md"), "# Project rule\nALPHA rule timeout=900\n");
        service.rebuild();
        assertThat(service.status().fingerprint()).isNotEqualTo(previousFingerprint);
        assertThat(service.retrieve("ALPHA").getFirst().content()).contains("timeout=900").doesNotContain("timeout=700");
    }
    @Test void restartUsesMatchingCacheWithoutReembeddingAllDocuments() throws Exception {
        Files.createDirectory(temp.resolve("knowledge"));
        Files.writeString(temp.resolve("knowledge/rule.md"), "# Project\nALPHA private rule");
        new KnowledgeService(settings(), fixture()).rebuild();
        int before = calls.get();
        var restarted = new KnowledgeService(settings(), fixture());
        restarted.retrieve("ALPHA");
        assertThat(restarted.status().state()).isEqualTo("CACHE_LOADED");
        // 一次维度检查加一次查询，不对整个知识库重新生成向量。
        assertThat(calls.get() - before).isEqualTo(2);
    }
    @Test void changedContentCannotReuseOldCache() throws Exception {
        Files.createDirectory(temp.resolve("knowledge"));
        Files.writeString(temp.resolve("knowledge/rule.md"), "# Project\nALPHA old rule");
        new KnowledgeService(settings(), fixture()).rebuild();
        Files.writeString(temp.resolve("knowledge/rule.md"), "# Project\nALPHA new rule");
        var restarted = new KnowledgeService(settings(), fixture());
        assertThat(restarted.retrieve("ALPHA").getFirst().content()).contains("new rule");
        assertThat(restarted.status().state()).isEqualTo("BUILT");
    }
    private void knowledgeWithLowRankedRule() throws Exception {
        Files.createDirectory(temp.resolve("knowledge"));
        for (int i = 0; i < 3; i++)
            Files.writeString(temp.resolve("knowledge/example-" + i + ".md"), "# Example " + i + "\nALPHA recovery example");
        Files.writeString(temp.resolve("knowledge/timeout-rules.md"),
                "# LAB-DEFAULT-TIMEOUT\nBETA requestTimeoutMs=1700; explicit user values take precedence.");
    }
    @ParameterizedTest @ValueSource(strings = {"ALPHA", "BETA"})
    void requiredTimeoutKnowledgeIsIncludedOnceEvenOutsideTopThree(String query) throws Exception {
        knowledgeWithLowRankedRule();
        var service = new KnowledgeService(settings(), fixture());
        service.rebuild();
        int before = calls.get();
        var hits = service.retrieve(query);
        assertThat(hits).filteredOn(hit -> hit.source().equals("timeout-rules.md")).hasSize(1);
        assertThat(hits.stream().map(dev.apifaultlab.DrillModels.Chunk::chunkId).distinct().count()).isEqualTo(hits.size());
        var rule = hits.stream().filter(hit -> hit.source().equals("timeout-rules.md")).findFirst().orElseThrow();
        assertThat(rule.content()).contains("requestTimeoutMs=1700", "explicit user values take precedence");
        if (query.equals("ALPHA")) {
            assertThat(hits).hasSize(4);
            assertThat(hits.subList(0, 3)).allMatch(hit -> hit.source().startsWith("example-") && hit.score() != null);
            assertThat(rule.score()).isNull();
        } else {
            assertThat(hits).hasSize(3);
            assertThat(rule.score()).isNotNull();
        }
        assertThat(calls.get() - before).isEqualTo(1);
    }
    @Test void requiredKnowledgeUsesCurrentContentAfterCacheRestartAndRuleChange() throws Exception {
        knowledgeWithLowRankedRule();
        new KnowledgeService(settings(), fixture()).rebuild();
        var cached = new KnowledgeService(settings(), fixture());
        assertThat(cached.retrieve("ALPHA")).anyMatch(hit -> hit.content().contains("requestTimeoutMs=1700"));
        assertThat(cached.status().state()).isEqualTo("CACHE_LOADED");
        String previousFingerprint = cached.status().fingerprint();
        Files.writeString(temp.resolve("knowledge/timeout-rules.md"), "# LAB-DEFAULT-TIMEOUT\nBETA requestTimeoutMs=1900");
        assertThat(cached.retrieve("ALPHA")).anyMatch(hit -> hit.content().contains("requestTimeoutMs=1900"))
                .noneMatch(hit -> hit.content().contains("requestTimeoutMs=1700"));
        assertThat(cached.status().fingerprint()).isNotEqualTo(previousFingerprint);
    }
    @Test void absentRequiredFileDoesNotInventDefaultKnowledge() throws Exception {
        knowledgeWithLowRankedRule();
        Files.delete(temp.resolve("knowledge/timeout-rules.md"));
        var hits = new KnowledgeService(settings(), fixture()).retrieve("ALPHA");
        assertThat(hits).hasSize(3).noneMatch(hit -> hit.content().contains("requestTimeoutMs"));
    }
    @Test void excessiveRequiredContextIsRejectedRatherThanSilentlyTruncated() throws Exception {
        knowledgeWithLowRankedRule();
        Files.writeString(temp.resolve("knowledge/timeout-rules.md"), "# LAB-DEFAULT-TIMEOUT\n" + "BETA ".repeat(800));
        var service = new KnowledgeService(settings(), fixture());
        assertThatThrownBy(() -> service.retrieve("ALPHA"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("8");
    }
}
