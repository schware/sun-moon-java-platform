package com.sunmoon.platform.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalType;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Listens to BO's {@code menu-events} channel and nudges the terminals of
 * the store it names.
 *
 * <p>Separate from {@link OrderEventSubscriber} on purpose. That one is
 * about one order and has to decide which single screen cares; this one
 * is about the store's master data and goes to everyone working from it.
 * Folding them together would mean a switch on the payload shape inside
 * routing logic that is already the trickiest part of this server.
 *
 * <p>The frame is forwarded as BO wrote it. A terminal treats it as a
 * nudge — "your menu master is stale" — and re-reads {@code /menus}. That
 * is why nothing here has to understand what changed, and why a terminal
 * that missed the frame still converges: it re-reads on connect too.
 */
public final class MenuEventSubscriber {

    public static final String CHANNEL = "menu-events";

    private static final Logger log = LoggerFactory.getLogger(MenuEventSubscriber.class);

    /**
     * Who keeps a menu master. POS prices and sells; KDS shows what it is
     * cooking and needs to know a line went out. DID shows finished orders
     * and has no menu of its own, so it is not woken for this.
     */
    private static final List<TerminalType> AUDIENCE = List.of(TerminalType.POS, TerminalType.KDS);

    private final RedissonClient redisson;
    private final TerminalRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MenuEventSubscriber(RedissonClient redisson, TerminalRegistry registry) {
        this.redisson = redisson;
        this.registry = registry;
    }

    public void start() {
        // StringCodec for the same reason OrderEventSubscriber uses it:
        // BO publishes plain UTF-8 JSON through Spring's
        // StringRedisTemplate, and Redisson's default codec is Kryo.
        // Agreeing on a channel name is not agreeing on a wire format.
        RTopic topic = redisson.getTopic(CHANNEL, StringCodec.INSTANCE);
        topic.addListener(String.class, (channel, message) -> onEvent(message));
        log.info("subscribed to Redis channel '{}'", CHANNEL);
    }

    private void onEvent(String json) {
        try {
            JsonNode event = objectMapper.readTree(json);
            String storeId = event.path("storeId").asText(null);
            if (storeId == null || storeId.isBlank()) {
                // Nothing to route on. Dropping it is right: pushing to
                // every shop would tell one store another's menu changed.
                log.warn("menu event has no storeId; not routed: {}", json);
                return;
            }

            int reached = 0;
            for (TerminalType type : AUDIENCE) {
                var channels = registry.channelsOf(storeId, type);
                reached += channels.size();
                channels.writeAndFlush(new TextWebSocketFrame(json));
            }

            // Nobody connected is not a problem here, unlike a placed
            // order with no POS. A 결품 nobody saw live is still in the
            // list every terminal reads when it opens.
            log.info("menu event {} {}/{} -> {} terminal(s)",
                    event.path("type").asText(), storeId, event.path("menuCode").asText(), reached);
        } catch (Exception e) {
            // A malformed event must not kill the subscription.
            log.warn("could not handle menu event: {}", json, e);
        }
    }
}
