package dev.apifaultlab;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import static dev.apifaultlab.DrillModels.*;

public class KnowledgeService {
    private final LabSettings settings;
    private final EmbeddingModel embedding;
    private final JsonMapper json = JsonMapper.builder().build();
    private SimpleVectorStore store;
    private volatile KnowledgeStatus state = new KnowledgeStatus(false, 0, 0, "", "NOT_BUILT");
    private record Manifest(String fingerprint, int dimensions, int chunks, String indexHash) {}
    private record Corpus(String fingerprint, List<Document> documents) {}

    public KnowledgeService(LabSettings settings, EmbeddingModel embedding) {
        this.settings = settings;
        this.embedding = embedding;
    }
    public synchronized KnowledgeStatus rebuild() {
        return build(readCorpus());
    }
    public synchronized List<Chunk> retrieve(String requirement) {
        var corpus = readCorpus();
        if (store == null || !state.fingerprint().equals(corpus.fingerprint())) {
            if (!loadCache(corpus)) build(corpus);
        }
        var context = new LinkedHashMap<String, Chunk>();
        for (var doc : store.similaritySearch(SearchRequest.builder().query(requirement).topK(3).similarityThresholdAll().build())) {
            context.put(doc.getId(), new Chunk(doc.getId(), (String) doc.getMetadata().get("source"),
                    (String) doc.getMetadata().get("title"), doc.getText(), doc.getScore()));
        }
        // 排名可能漏掉示例引用的默认规则；补入当前知识原文，不在代码中定义规则数值。
        for (var doc : corpus.documents()) {
            if ("timeout-rules.md".equals(doc.getMetadata().get("source")))
                context.putIfAbsent(doc.getId(), new Chunk(doc.getId(), (String) doc.getMetadata().get("source"),
                        (String) doc.getMetadata().get("title"), doc.getText(), null));
        }
        if (context.size() > 8) throw new IllegalStateException("检索与必需规则上下文超过 8 个片段，请精简 timeout-rules.md");
        return List.copyOf(context.values());
    }
    public KnowledgeStatus status() {
        if (state.ready()) {
            try {
                if (!readCorpus().fingerprint().equals(state.fingerprint()))
                    return new KnowledgeStatus(false, state.chunks(), state.dimensions(), state.fingerprint(), "STALE");
            } catch (RuntimeException invalid) {
                return new KnowledgeStatus(false, state.chunks(), state.dimensions(), state.fingerprint(), "KNOWLEDGE_ERROR");
            }
        }
        return state;
    }

    private KnowledgeStatus build(Corpus corpus) {
        int dimensions = validDimensions(embedding.embed("API Fault Lab dimension check"));
        var next = SimpleVectorStore.builder(fixedDimensions(dimensions)).build();
        next.add(corpus.documents());
        try {
            var dir = Path.of(settings.runtimeDir(), "index");
            Files.createDirectories(dir);
            var temporary = dir.resolve("vectors.tmp");
            var index = dir.resolve("vectors.json");
            next.save(temporary.toFile());
            Files.move(temporary, index, StandardCopyOption.REPLACE_EXISTING);
            var manifest = new Manifest(corpus.fingerprint(), dimensions, corpus.documents().size(), sha(Files.readAllBytes(index)));
            var metadata = dir.resolve("manifest.tmp");
            json.writeValue(metadata.toFile(), manifest);
            Files.move(metadata, dir.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
            store = next;
            state = new KnowledgeStatus(true, manifest.chunks(), dimensions, corpus.fingerprint(), "BUILT");
            return state;
        } catch (java.io.IOException error) { throw new IllegalStateException("无法保存知识索引"); }
    }

    private boolean loadCache(Corpus corpus) {
        var dir = Path.of(settings.runtimeDir(), "index");
        try {
            var manifest = json.readValue(dir.resolve("manifest.json").toFile(), Manifest.class);
            var index = dir.resolve("vectors.json");
            if (!manifest.fingerprint().equals(corpus.fingerprint()) || !manifest.indexHash().equals(sha(Files.readAllBytes(index))))
                return false;
            if (validDimensions(embedding.embed("API Fault Lab dimension check")) != manifest.dimensions())
                return false;
            var next = SimpleVectorStore.builder(fixedDimensions(manifest.dimensions())).build();
            next.load(index.toFile());
            store = next;
            state = new KnowledgeStatus(true, manifest.chunks(), manifest.dimensions(), manifest.fingerprint(), "CACHE_LOADED");
            return true;
        } catch (java.io.IOException | IllegalArgumentException corruptCache) { return false; }
    }

    private Corpus readCorpus() {
        try (var files = Files.list(Path.of(settings.knowledgeDir()))) {
            var paths = files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
            if (paths.isEmpty() || paths.size() > 20) throw new IllegalStateException("知识库必须包含 1..20 个 Markdown 文件");
            var docs = new ArrayList<Document>();
            var fingerprint = new StringBuilder("chunk-v1:600:60|").append(settings.embeddingBaseUrl()).append('|').append(settings.embeddingModel());
            for (var path : paths) {
                if (Files.size(path) > 128_000) throw new IllegalStateException("单个知识文件超过 128 KB");
                String content = Files.readString(path);
                String source = path.getFileName().toString();
                fingerprint.append('|').append(source).append('|').append(content);
                String title = source;
                var section = new StringBuilder();
                for (String line : content.lines().toList()) {
                    if (line.matches("^#{1,6}\\s+.*")) {
                        split(docs, source, title, section.toString());
                        section.setLength(0);
                        title = line.replaceFirst("^#{1,6}\\s+", "");
                    } else { section.append(line).append('\n'); }
                }
                split(docs, source, title, section.toString());
            }
            if (docs.isEmpty() || docs.size() > 64) throw new IllegalStateException("知识库必须包含 1..64 个片段");
            return new Corpus(sha(fingerprint.toString().getBytes(StandardCharsets.UTF_8)), List.copyOf(docs));
        } catch (java.io.IOException error) { throw new IllegalStateException("知识目录不可读取"); }
    }
    private void split(List<Document> docs, String source, String title, String content) {
        String text = content.trim();
        for (int start = 0; start < text.length(); start += 540) {
            int end = Math.min(start + 600, text.length());
            String part = title + "\n" + text.substring(start, end);
            String id = sha((source + "|" + start + "|" + part).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
            docs.add(new Document(id, part, Map.of("source", source, "title", title, "chunkId", id)));
            if (end == text.length()) break;
        }
    }
    private int validDimensions(float[] vector) {
        if (vector.length == 0) throw new IllegalStateException("Embedding 返回空向量");
        return vector.length;
    }
    private EmbeddingModel fixedDimensions(int dimensions) {
        // 向量库创建观测元数据时会读取维度；复用已验证值，避免额外请求。
        return new EmbeddingModel() {
            public int dimensions() { return dimensions; }
            public float[] embed(Document doc) { return embedding.embed(doc); }
            public org.springframework.ai.embedding.EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
                return embedding.call(request);
            }
        };
    }
    private String sha(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
