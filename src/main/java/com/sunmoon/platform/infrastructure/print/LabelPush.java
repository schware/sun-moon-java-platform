package com.sunmoon.platform.infrastructure.print;

import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalType;
import com.sunmoon.platform.transport.ws.TerminalHandshakeHandler;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 라벨 작업을 그 매장의 KDS-MAIN 에 알립니다 — {@code {"type":"PRINT","jobIds":[…]}} (§7.2).
 *
 * <p>프레임은 "찍을 것이 생겼다" 는 알림일 뿐입니다. 셸은 작업을 다시 읽어 찍고, MAIN 이
 * 꺼져 있었으면 다시 붙을 때 QUEUED 를 가져가 찍습니다 — 놓친 프레임이 라벨을 잃지 않습니다.
 */
public final class LabelPush {

    private static final Logger log = LoggerFactory.getLogger(LabelPush.class);

    private final TerminalRegistry registry;

    public LabelPush(TerminalRegistry registry) {
        this.registry = registry;
    }

    /** @return 알린 MAIN 수 (0 이면 작업은 QUEUED 로 남아 기다립니다) */
    public int push(String storeId, List<Long> jobIds) {
        if (jobIds.isEmpty()) {
            return 0;
        }
        String frame = "{\"type\":\"PRINT\",\"storeId\":\"" + storeId.replace("\"", "") + "\",\"jobIds\":["
                + jobIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]}";
        int reached = 0;
        for (Channel channel : registry.channelsOf(storeId, TerminalType.KDS)) {
            if ("MAIN".equals(channel.attr(TerminalHandshakeHandler.KDS_ROLE).get())) {
                channel.writeAndFlush(new TextWebSocketFrame(frame));
                reached++;
            }
        }
        log.info("라벨 {}장 → {} KDS-MAIN {}대", jobIds.size(), storeId, reached);
        return reached;
    }
}
