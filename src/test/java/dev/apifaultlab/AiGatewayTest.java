package dev.apifaultlab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class AiGatewayTest {
    @ParameterizedTest
    @ValueSource(ints = {401, 404, 429, 502})
    void providerHttpStatusSurvivesWithoutErrorBodyOrRetry(int status) {
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            server.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":{\"message\":\"sensitive-fixture-body test-key-only\"}}")));
            var gateway = new AiGateway(new LabSettings("", "", "", "http://127.0.0.1:" + server.port() + "/v1",
                    "fixture", "test-key-only", 3, "knowledge", "runtime"));
            var failure = catchThrowable(() -> gateway.embedding().embed("HTTP status fixture"));
            assertThat(failure).isInstanceOf(AiGateway.ModelFailure.class).hasMessageContaining("HTTP " + status);
            var trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain("sensitive-fixture-body", "test-key-only");
            assertThat(gateway.callsAfter(0)).hasSize(1);
            assertThat(gateway.callsAfter(0).getFirst().error()).contains("HTTP " + status)
                    .doesNotContain("sensitive-fixture-body", "test-key-only");
            server.verify(1, postRequestedFor(urlEqualTo("/v1/embeddings")));
        } finally { server.stop(); }
    }
    @Test void chatHttpStatusSurvivesWithoutErrorBodyOrRetry() {
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(502)
                    .withHeader("Content-Type", "text/html").withBody("sensitive-fixture-body test-key-only")));
            var gateway = new AiGateway(new LabSettings("http://127.0.0.1:" + server.port() + "/v1",
                    "fixture", "test-key-only", "", "", "", 3, "knowledge", "runtime"));
            var failure = catchThrowable(() -> gateway.chat("PLANNER", "JSON fixture", "requirement"));
            assertThat(failure).isInstanceOf(AiGateway.ModelFailure.class).hasMessageContaining("HTTP 502");
            var trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain("sensitive-fixture-body", "test-key-only");
            assertThat(gateway.callsAfter(0).getFirst().error()).contains("HTTP 502");
            server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        } finally { server.stop(); }
    }
    @Test void chatTransportTimeoutRecordsCauseClassesAndNeverRetries() {
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                    .withFixedDelay(2500).withHeader("Content-Type", "application/json")
                    .withBody("sensitive-fixture-body test-key-only")));
            var gateway = new AiGateway(new LabSettings("http://127.0.0.1:" + server.port() + "/v1",
                    "fixture", "test-key-only", "", "", "", 1, "knowledge", "runtime"));

            var failure = catchThrowable(() -> gateway.chat("MANUAL_ASSISTANCE", "system secret", "user secret"));

            assertThat(failure).isInstanceOf(AiGateway.ModelFailure.class);
            var call = gateway.callsAfter(0).getFirst();
            assertThat(call.error()).contains("阶段=CHAT_REQUEST", "超时配置=1s", "OpenAIIoException")
                    .containsAnyOf("InterruptedIOException", "SocketException", "SocketTimeoutException")
                    .doesNotContain("sensitive-fixture-body", "test-key-only", "system secret", "user secret");
            assertThat(call.elapsedMs()).isBetween(900L, 5000L);
            var trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain("sensitive-fixture-body", "test-key-only", "system secret", "user secret");
            server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        } finally { server.stop(); }
    }
    @Test void providerErrorBodyIsRedactedAndNotRetried() {
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            server.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(401)
                    .withHeader("Content-Type", "application/json").withBody("{\"error\":{\"message\":\"sensitive-fixture-body\"}}")));
            var gateway = new AiGateway(new LabSettings("", "", "", "http://127.0.0.1:" + server.port() + "/v1",
                    "fixture", "test-key-only", 3, "knowledge", "runtime"));
            assertThatThrownBy(() -> gateway.embedding().embed("auth failure fixture"))
                    .isInstanceOf(AiGateway.ModelFailure.class).hasMessageNotContaining("sensitive-fixture-body").hasMessageNotContaining("test-key-only");
            assertThat(gateway.callsAfter(0).toString()).doesNotContain("sensitive-fixture-body", "test-key-only");
            server.verify(1, postRequestedFor(urlEqualTo("/v1/embeddings")));
        } finally { server.stop(); }
    }
    @Test void chatUsesRealTransportAndIndependentRoleInstructions() {
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(okJson("""
                {"id":"fixture-chat","object":"chat.completion","model":"fixture","choices":[{"index":0,"message":{"role":"assistant","content":"{\\"ok\\":true}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                """)));
            var gateway = new AiGateway(new LabSettings("http://127.0.0.1:" + server.port() + "/v1", "fixture", "test-key-only", "", "", "", 3, "knowledge", "runtime"));
            assertThat(gateway.chat("PLANNER", "planner JSON instruction", "requirement")).isEqualTo("{\"ok\":true}");
            assertThat(gateway.chat("REVIEWER", "reviewer JSON instruction", "evidence")).isEqualTo("{\"ok\":true}");
            server.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        } finally { server.stop(); }
    }
    @Test void missingCredentialsNeverFallBackToFakeVectors() {
        var gateway = new AiGateway(new LabSettings("", "", "", "", "", "", 3, "knowledge", "runtime"));
        assertThatThrownBy(() -> gateway.embedding().embed("hello")).isInstanceOf(IllegalStateException.class);
    }
    @Test void realHttpAdapterSupportsV1BaseUrlAndStableDimensions() {
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            server.start();
            server.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(okJson("""
                {"object":"list","model":"fixture","data":[{"object":"embedding","index":0,"embedding":[0.2,0.3,0.4]}],"usage":{"prompt_tokens":1,"total_tokens":1}}
                """)));
            var gateway = new AiGateway(new LabSettings("", "", "", "http://127.0.0.1:" + server.port() + "/v1", "fixture", "test-key-only", 3, "knowledge", "runtime"));
            assertThat(gateway.embedding().embed("actual HTTP to fixture")).hasSize(3);
            server.verify(1, postRequestedFor(urlEqualTo("/v1/embeddings")).withHeader("Authorization", equalTo("Bearer test-key-only")));
            server.stubFor(post(urlEqualTo("/v1/embeddings")).willReturn(okJson("""
                {"object":"list","model":"fixture","data":[{"object":"embedding","index":0,"embedding":[0.2,0.3]}]}
                """)));
            assertThatThrownBy(() -> gateway.embedding().embed("dimension drift")).isInstanceOf(IllegalStateException.class);
        } finally { server.stop(); }
    }
}
