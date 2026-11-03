package com.datalens.model;

import com.datalens.common.web.DomainException;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiTransportTest {
    static final String ENDPOINT = "https://8.8.8.8/v1/chat/completions";
    OpenAiTransport transport(OpenAiTransport.Sender sender) {
        return new OpenAiTransport(new ModelEndpointPolicy(ENDPOINT), JsonMapper.builder().build(), sender);
    }
    String call(OpenAiTransport transport) { return transport.complete(ENDPOINT, "test-model", "test-placeholder", "question", 500, 1); }
    OpenAiTransport.Reply reply(int status, String text) { return new OpenAiTransport.Reply(status, text.getBytes(StandardCharsets.UTF_8)); }
    @Test void sendsBoundedNonStreamingRequestAndParsesContent() {
        var transport = transport(request -> {
            assertEquals("POST", request.method());
            assertEquals(ENDPOINT, request.uri().toString());
            assertEquals("Bearer test-placeholder", request.headers().firstValue("Authorization").orElseThrow());
            assertEquals(1, request.timeout().orElseThrow().toSeconds());
            return CompletableFuture.completedFuture(reply(200, "{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}"));
        });
        assertEquals("answer", call(transport));
    }
    @Test void normalizesRateLimitAndDoesNotLeakProviderError() {
        var error = assertThrows(DomainException.class, () -> call(transport(r -> CompletableFuture.completedFuture(reply(429, "secret upstream details")))));
        assertEquals("MODEL_RATE_LIMITED", error.code());
        assertFalse(error.getMessage().contains("secret"));
        for (int status : new int[]{301, 302, 401, 403, 500, 503})
            assertEquals("MODEL_PROVIDER_FAILED", assertThrows(DomainException.class,
                    () -> call(transport(r -> CompletableFuture.completedFuture(reply(status, "secret upstream details"))))).code());
    }
    @Test void malformedMissingContentAndOversizeAreFailures() {
        for (String body : List.of("not-json", "{}", "{\"choices\":[{\"message\":{\"content\":null}}]}", " ".repeat(OpenAiTransport.MAX_BODY_BYTES + 1)))
            assertEquals("MODEL_PROVIDER_FAILED", assertThrows(DomainException.class,
                    () -> call(transport(r -> CompletableFuture.completedFuture(reply(200, body))))).code());
    }
    @Test void deadlineIncludesStalledBodyAndCancelsRequest() {
        var pending = new CompletableFuture<OpenAiTransport.Reply>();
        assertTimeout(java.time.Duration.ofSeconds(3), () -> {
            assertEquals("MODEL_TIMEOUT", assertThrows(DomainException.class, () -> call(transport(r -> pending))).code());
        });
        assertTrue(pending.isCancelled());
    }
    @Test void httpTimeoutNormalized() {
        assertEquals("MODEL_TIMEOUT", assertThrows(DomainException.class, () -> call(transport(r ->
                CompletableFuture.failedFuture(new HttpTimeoutException("sensitive URL"))))).code());
    }
    @Test void noNetworkAtConstructionAndPolicyCheckedBeforeSending() {
        var count = new AtomicInteger();
        var transport = transport(r -> { count.incrementAndGet(); return CompletableFuture.completedFuture(reply(500, "")); });
        assertEquals(0, count.get());
        assertThrows(DomainException.class, () -> transport.complete("https://127.0.0.1/chat", "model", "secret", "prompt", 5, 1));
        assertThrows(DomainException.class, () -> transport.complete(ENDPOINT, "model", "secret\r\nHeader: injected", "prompt", 5, 1));
        assertEquals(0, count.get());
    }
    @Test void boundedSubscriberCancelsOnOverflowAndAcceptsExactLimit() {
        var cancelled = new AtomicInteger();
        Flow.Subscription subscription = new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { cancelled.incrementAndGet(); }
        };
        var body = new OpenAiTransport.LimitedBody();
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[OpenAiTransport.MAX_BODY_BYTES])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        assertEquals(1, cancelled.get());
        var exact = new OpenAiTransport.LimitedBody();
        exact.onSubscribe(subscription);
        exact.onNext(List.of(ByteBuffer.wrap(new byte[OpenAiTransport.MAX_BODY_BYTES])));
        exact.onComplete();
        assertEquals(OpenAiTransport.MAX_BODY_BYTES, exact.getBody().toCompletableFuture().join().length);
    }
}
