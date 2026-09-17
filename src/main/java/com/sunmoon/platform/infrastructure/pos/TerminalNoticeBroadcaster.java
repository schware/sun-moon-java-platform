package com.sunmoon.platform.infrastructure.pos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunmoon.platform.domain.pos.Notice;
import com.sunmoon.platform.domain.pos.NoticeService;
import com.sunmoon.platform.domain.terminal.TerminalRegistry;
import com.sunmoon.platform.domain.terminal.TerminalSession;
import com.sunmoon.platform.domain.terminal.TerminalType;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 긴급 메시지를 지금 붙어 있는 단말에 밀어 넣습니다 (§3.5).
 *
 * <p>Redis 를 거치지 않습니다. 결품이나 주문 이벤트와 다른 점이 있어서
 * 입니다 — 저것들은 <b>다른 서비스</b>(BO, Order)에서 일어난 일이라
 * 채널을 건너와야 하지만, 긴급 메시지는 <b>이 서버가 직접 받은</b>
 * 것입니다. 자기 안에서 일어난 일을 Redis 로 한 바퀴 돌리면 Redis 가
 * 죽은 날 이 서버만으로 할 수 있는 일까지 못 하게 됩니다.
 *
 * <p>대상은 공지가 정합니다. 매장이 비어 있으면 전 매장, 장비 유형이
 * 비어 있으면 전 유형 — 그래서 붙어 있는 단말을 훑으며 거릅니다.
 * 채널 그룹을 쓰지 않는 이유는 "전 매장"을 그룹으로는 표현할 수 없기
 * 때문입니다.
 */
public final class TerminalNoticeBroadcaster implements NoticeService.Broadcaster {

    private static final Logger log = LoggerFactory.getLogger(TerminalNoticeBroadcaster.class);

    private final TerminalRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TerminalNoticeBroadcaster(TerminalRegistry registry) {
        this.registry = registry;
    }

    @Override
    public int push(Notice notice) {
        String payload = frame(notice);
        int reached = 0;

        for (TerminalSession session : registry.connected()) {
            if (!notice.appliesTo(session.storeId(), session.type().name())) {
                continue;
            }
            Optional<Channel> channel = channelOf(session);
            if (channel.isPresent() && channel.get().isActive()) {
                channel.get().writeAndFlush(new TextWebSocketFrame(payload));
                reached++;
            }
        }
        log.info("긴급 메시지 '{}' -> 단말 {}대", notice.title(), reached);
        return reached;
    }

    private Optional<Channel> channelOf(TerminalSession session) {
        TerminalType type = session.type();
        if (session.storeId() == null) {
            // 여러 매장을 보는 DID. 긴급 메시지의 대상이 아닙니다 —
            // 손님이 보는 화면이고, 매장 직원에게 하는 말이 거기 뜨면
            // 안 됩니다.
            return Optional.empty();
        }
        return registry.channelFor(session.storeId(), session.deviceId(), type);
    }

    /**
     * 단말이 이미 아는 모양으로 보냅니다 — {@code type} 이 맨 앞에 있는
     * 한 덩어리 JSON. 단말의 소켓 처리기는 {@code type} 으로만 갈라서,
     * 새 종류가 늘어도 기존 처리가 안 깨집니다.
     */
    private String frame(Notice notice) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "NOTICE");
        node.put("noticeId", notice.id());
        node.put("title", notice.title());
        node.put("body", notice.body());
        node.put("urgent", notice.urgent());
        if (notice.storeId() != null) {
            node.put("storeId", notice.storeId());
        }
        return node.toString();
    }
}
