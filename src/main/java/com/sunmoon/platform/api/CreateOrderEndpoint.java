package com.sunmoon.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.order.Order;
import com.sunmoon.platform.infrastructure.messaging.EventPublisher;
import com.sunmoon.platform.transport.http.RestEndpoint;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * POST /orders — the first endpoint to exercise Validation (Jakarta Bean
 * Validation), Resilience4j (wrapping the event-publish call), and the
 * Event Bus ({@link EventPublisher}) together.
 */
public final class CreateOrderEndpoint implements RestEndpoint {

    public record OrderRequest(@NotBlank String customerId, @Positive BigDecimal amount) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final EventPublisher eventPublisher;
    private final CircuitBreaker circuitBreaker;

    public CreateOrderEndpoint(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        this.circuitBreaker = CircuitBreakerRegistry.ofDefaults().circuitBreaker("order-events");
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) {
        if (!HttpMethod.POST.equals(request.method())) {
            return jsonResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "use POST"));
        }

        OrderRequest parsed;
        try {
            parsed = JSON.readValue(request.content().toString(StandardCharsets.UTF_8), OrderRequest.class);
        } catch (Exception e) {
            return jsonResponse(HttpResponseStatus.BAD_REQUEST, Map.of("error", "invalid JSON body"));
        }

        Set<ConstraintViolation<OrderRequest>> violations = VALIDATOR.validate(parsed);
        if (!violations.isEmpty()) {
            return jsonResponse(HttpResponseStatus.BAD_REQUEST,
                    Map.of("error", violations.iterator().next().getMessage()));
        }

        Order order = new Order(NEXT_ID.getAndIncrement(), parsed.customerId(), parsed.amount());

        circuitBreaker.executeRunnable(() ->
                eventPublisher.publish("orders", String.valueOf(order.id()), toJson(order)));

        return jsonResponse(HttpResponseStatus.CREATED,
                Map.of("id", order.id(), "customerId", order.customerId(), "amount", order.amount()));
    }

    private String toJson(Order order) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "id", order.id(), "customerId", order.customerId(), "amount", order.amount()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private FullHttpResponse jsonResponse(HttpResponseStatus status, Object body) {
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        return response;
    }
}
