package ink.icoding.llm.core.model.impl;

import com.sun.net.httpserver.HttpServer;
import ink.icoding.llm.core.entity.Message;
import ink.icoding.llm.core.model.LLMModel;
import ink.icoding.llm.core.model.LLMResult;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StreamCompletionTest {
    @Test
    void successfulResultDoesNotNotifyLateFailure() throws Exception {
        AtomicInteger errors = new AtomicInteger();
        LLMResult result = new LLMResult(r -> {}).error(e -> errors.incrementAndGet());
        result.complete("answer");
        result.fail(new IOException("Socket closed"));
        assertEquals("answer", result.get());
        assertEquals(0, errors.get());
    }

    @Test
    void failureNotifiesOnlyOnceAndPreservesCause() {
        AtomicInteger errors = new AtomicInteger();
        IOException cause = new IOException("connection reset");
        LLMResult result = new LLMResult(r -> {}).error(e -> {
            assertSame(cause, e);
            errors.incrementAndGet();
        });
        result.fail(cause);
        result.fail(new IOException("late failure"));
        assertSame(cause, assertThrows(Exception.class, result::get).getCause());
        assertEquals(1, errors.get());
    }

    @Test
    void responsesCompletionIgnoresHttp1Cancellation() throws Exception {
        verifyStream("responses", "data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n", true);
    }

    @Test
    void anthropicCompletionIgnoresHttp1Cancellation() throws Exception {
        verifyStream("anthropic", "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n", true);
    }

    @Test
    void chatCompletionIgnoresHttp1Cancellation() throws Exception {
        verifyStream("chat", "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\n", true);
    }

    @Test
    void realDisconnectWithHttp200RemainsFailure() throws Exception {
        for (String protocol : new String[]{"responses", "anthropic", "chat"}) {
            verifyStream(protocol, ": heartbeat\n\n", false);
        }
    }

    private void verifyStream(String protocol, String events, boolean success) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            // Deliberately leave the HTTP body incomplete: after a terminal event this
            // must be harmless, but without a terminal event it is a real failure.
            exchange.sendResponseHeaders(200, bytes.length + 1000);
            try {
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().flush();
            } finally {
                exchange.close();
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        LLMModel model = switch (protocol) {
            case "responses" -> new OpenAIResponseModel(url, "test", "key");
            case "anthropic" -> new AnthropicModel(url, "test", "key");
            default -> new OpenAIChatModel(url, "test", "key");
        };
        var field = model.getClass().getDeclaredField("client");
        field.setAccessible(true);
        OkHttpClient client = (OkHttpClient) field.get(model);
        CountDownLatch idle = new CountDownLatch(1);
        client.dispatcher().setIdleCallback(idle::countDown);
        AtomicInteger errors = new AtomicInteger();
        LLMResult result = model.ask(Message.fromUser("hi")).error(e -> errors.incrementAndGet());
        try {
            result.execute();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                if (success) {
                    assertEquals("answer", result.get());
                } else {
                    Throwable failure = assertThrows(Exception.class, result::get).getCause();
                    assertTrue(failure.getMessage().contains("HTTP 200"));
                    assertNotNull(failure.getCause());
                }
                assertTrue(idle.await(5, TimeUnit.SECONDS));
            });
            assertEquals(success ? 0 : 1, errors.get());
        } finally {
            server.stop(0);
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }
}
