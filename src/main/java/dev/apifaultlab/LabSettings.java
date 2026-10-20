package dev.apifaultlab;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab")
public record LabSettings(String chatBaseUrl, String chatModel, String chatApiKey,
                          String embeddingBaseUrl, String embeddingModel, String embeddingApiKey,
                          int modelTimeoutSeconds, String knowledgeDir, String runtimeDir) {
    public boolean chatConfigured() { return present(chatBaseUrl) && present(chatModel) && present(chatApiKey); }
    public boolean embeddingConfigured() { return present(embeddingBaseUrl) && present(embeddingModel) && present(embeddingApiKey); }
    public boolean configured() { return chatConfigured() && embeddingConfigured(); }
    private boolean present(String value) { return value != null && !value.isBlank(); }
    @Override public String toString() { return "LabSettings[credentials=REDACTED]"; }
}
