package com.datalens.model;

import com.datalens.common.web.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Minimal OpenAI-compatible, non-streaming chat protocol. No contact at startup, no retries. */
@Component
public class OpenAiTransport implements ModelTransport {
    static final int MAX_BODY_BYTES = 128 * 1024;
    private final ModelEndpointPolicy policy;
    private final ObjectMapper json;
    private final Sender sender;

    @FunctionalInterface interface Sender {
        CompletableFuture<Reply> send(HttpRequest request);
    }
    record Reply(int status, byte[] body) {
        @Override public String toString() { return "Reply[status=" + status + ", body=REDACTED]"; }
    }
    @Autowired
    public OpenAiTransport(ModelEndpointPolicy policy, ObjectMapper json) {
        this(policy, json, defaultSender());
    }
    OpenAiTransport(ModelEndpointPolicy policy, ObjectMapper json, Sender sender) {
        this.policy = policy; this.json = json; this.sender = sender;
    }
    private static Sender defaultSender() {
        // Never inherit system proxies: they can reinterpret the numeric destination or bypass policy.
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).proxy(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
        return request -> {
            CompletableFuture<HttpResponse<byte[]>> raw = client.sendAsync(request, info -> new LimitedBody());
            CompletableFuture<Reply> mapped = raw.thenApply(r -> new Reply(r.statusCode(), r.body()));
            mapped.whenComplete((reply, error) -> { if (mapped.isCancelled()) raw.cancel(true); });
            return mapped;
        };
    }
    @Override
    public String complete(String endpoint, String modelId, String apiKey, String prompt,
                           int maxOutputTokens, int timeoutSeconds) {
        policy.validate(endpoint);
        if (modelId == null || modelId.isBlank() || modelId.length() > 200
                || apiKey == null || apiKey.isBlank() || apiKey.length() > 4096
                || apiKey.chars().anyMatch(c -> c < 33 || c > 126)
                || prompt == null || prompt.length() > 65536
                || maxOutputTokens < 1 || maxOutputTokens > 8192 || timeoutSeconds < 1 || timeoutSeconds > 120)
            throw failed();
        CompletableFuture<Reply> future = null;
        try {
            String payload = json.writeValueAsString(Map.of("model", modelId, "stream", false,
                    "max_tokens", maxOutputTokens, "messages", List.of(Map.of("role", "user", "content", prompt))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            future = sender.send(request);
            // Covers the entire body subscription, including a stalled body after response headers.
            Reply reply = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (reply.status() == 429) throw new DomainException(503, "MODEL_RATE_LIMITED", "模型服务暂时限流");
            if (reply.status() < 200 || reply.status() >= 300 || reply.body() == null
                    || reply.body().length > MAX_BODY_BYTES) throw failed();
            var root = json.readTree(reply.body());
            var content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asString().isBlank()) throw failed();
            return content.asString();
        } catch (TimeoutException ex) {
            if (future != null) future.cancel(true);
            throw timeout();
        } catch (InterruptedException ex) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            throw timeout();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof HttpTimeoutException) throw timeout();
            throw failed();
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw failed();
        }
    }
    private static DomainException failed() { return new DomainException(502, "MODEL_PROVIDER_FAILED", "模型服务响应无效或暂不可用"); }
    private static DomainException timeout() { return new DomainException(504, "MODEL_TIMEOUT", "模型请求超时"); }

    /** Rejects overflow as bytes arrive rather than after buffering an unlimited response. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) { subscription.cancel(); return; }
            this.subscription = subscription; subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Model response exceeds limit")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
