package com.sunmoon.platform.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalType;
import com.sunmoon.platform.infrastructure.order.OrderClient;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Listens to Order's {@code order-events} channel and turns each event
 * into a push to the terminals that care about it.
 *
 * <p>Which terminals care is decided here rather than by the publisher.
 * Order does not know that POS screens exist, and should not: it announces
 * that an order changed state, and this server — whose whole job is
 * terminals — decides who needs to see it.
 */
public final class OrderEventSubscriber {

    public static final String CHANNEL = "order-events";

    private static final Logger log = LoggerFactory.getLogger(OrderEventSubscriber.class);

    private final RedissonClient redisson;
    private final TerminalRegistry registry;
    private final OrderClient orders;
    private final Duration terminalGrace;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrderEventSubscriber(RedissonClient redisson, TerminalRegistry registry,
                                OrderClient orders, Duration terminalGrace) {
        this.redisson = redisson;
        this.registry = registry;
        this.orders = orders;
        this.terminalGrace = terminalGrace;
    }

    public void start() {
        RTopic topic = redisson.getTopic(CHANNEL);
        topic.addListener(String.class, (channel, message) -> onEvent(message));
        log.info("subscribed to Redis channel '{}'", CHANNEL);
    }

    private void onEvent(String json) {
        try {
            JsonNode event = objectMapper.readTree(json);
            String status = event.path("status").asText();
            TerminalType audience = audienceFor(status);

            if (audience == null) {
                log.debug("no terminal audience for status {}", status);
                return;
            }

            long orderId = event.path("orderId").asLong();
            int reached = registry.channelsOf(audience).size();

            // A placed order with nowhere to go is refused rather than left
            // waiting for a timeout: the customer finds out now, and the
            // store is not asked to answer for a screen that was switched
            // off. Only PLACED — every later state is informational, and a
            // paid order must not be undone because a display is offline.
            //
            // But a terminal that was here moments ago is probably
            // reconnecting, not gone. Within the grace period the order is
            // left PLACED and the terminal picks it up when it comes back,
            // because it fetches the list rather than replaying frames. A
            // dropped wifi should cost a delay, not a customer.
            if (reached == 0 && "PLACED".equals(status)) {
                if (registry.isPresentOrRecentlySeen(audience, terminalGrace)) {
                    log.info("order {} left PLACED: no {} terminal connected, but one was seen within {}",
                            orderId, audience, terminalGrace);
                    return;
                }
                rejectUnattended(orderId);
                return;
            }

            registry.channelsOf(audience).writeAndFlush(new TextWebSocketFrame(json));
            log.info("order {} -> {}: pushed to {} {} terminal(s)", orderId, status, reached, audience);
        } catch (Exception e) {
            // A malformed event must not kill the subscription — the next
            // one still has to arrive.
            log.warn("could not handle order event: {}", json, e);
        }
    }

    /**
     * Tells Order that nobody was there to take it. The decision is made
     * here because connectedness is only known here; the transition itself
     * is still Order's to allow, so this asks rather than asserts.
     */
    private void rejectUnattended(long orderId) {
        try {
            var response = orders.changeStatus(orderId, "REJECTED", null);
            log.info("order {} rejected: no POS terminal connected (Order answered {})",
                    orderId, response.statusCode());
        } catch (Exception e) {
            // Leaving it PLACED is survivable — the acceptance timeout in
            // Order will expire it. Better a late expiry than a lost order.
            log.warn("could not reject unattended order {}; leaving it for the acceptance timeout", orderId, e);
        }
    }

    /**
     * Who needs to see this state.
     *
     * <p>PLACED goes to POS because a POS terminal is what accepts it.
     * ACCEPTED goes to KDS because that is the kitchen's cue. PRODUCED
     * goes to DID, the store-facing display. The delivery states are
     * shown on POS, which is where a store watches an order leave.
     */
    private static TerminalType audienceFor(String status) {
        return switch (status) {
            case "PLACED", "REJECTED", "DELIVERING", "COMPLETED" -> TerminalType.POS;
            case "ACCEPTED" -> TerminalType.KDS;
            case "PRODUCED" -> TerminalType.DID;
            default -> null;
        };
    }
}
