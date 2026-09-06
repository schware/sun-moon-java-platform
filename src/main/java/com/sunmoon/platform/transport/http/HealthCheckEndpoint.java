package com.sunmoon.platform.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.observability.MetricsRegistry;
import com.sunmoon.platform.observability.TracingConfig;
import io.micrometer.core.instrument.Counter;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.util.Map;

/** GET /health — the first, smallest possible end-to-end proof the Core Runtime is alive. Also the first proof point for Metrics (a request counter) and Tracing (a span per request). */
public final class HealthCheckEndpoint implements RestEndpoint {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Counter HEALTH_CHECK_COUNTER = Counter.builder("health_check_requests_total")
            .description("Number of times GET /health has been called")
            .register(MetricsRegistry.get());

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        Span span = TracingConfig.tracer().spanBuilder("health-check").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            HEALTH_CHECK_COUNTER.increment();

            byte[] body;
            try {
                body = JSON.writeValueAsBytes(Map.of("status", "UP"));
            } catch (Exception e) {
                throw new IllegalStateException("failed to serialize health check response", e);
            }

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            return response;
        } finally {
            span.end();
        }
    }
}
