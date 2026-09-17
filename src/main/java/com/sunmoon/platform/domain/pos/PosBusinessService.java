package com.sunmoon.platform.domain.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 개점·마감 IRT 의 규칙 (업무 정의서 §3.4).
 *
 * <p>규칙은 셋이고, 셋 다 "받은 것을 그대로 믿지 않는다"입니다.
 *
 * <ol>
 *   <li><b>재개점은 서버가 판단합니다.</b> POS는 열었다/닫았다만
 *       말합니다. 재부팅한 POS는 자기가 오늘 이미 열었다는 것을
 *       모르므로, 그 판단을 POS에 맡기면 이력에서 재개점이 사라집니다.
 *   <li><b>지나간 사건은 상태를 바꾸지 않습니다.</b> 끊겼던 POS가
 *       밀린 전문을 몰아 올리면 09시의 개점이 18시의 마감 뒤에
 *       도착할 수 있습니다. 시각을 안 보면 마감한 단말이 열립니다.
 *   <li><b>같은 말이 두 번 와도 오류가 아닙니다.</b> 응답을 못 받은
 *       POS는 다시 보냅니다. 거기서 오류를 내면 POS는 영원히 재시도
 *       합니다.
 * </ol>
 *
 * <p>셋 다 <b>이력에는 남습니다</b>. 무시했다는 사실이 나중에 답해야
 * 할 질문이기 때문입니다 — "이 POS는 왜 마감으로 안 바뀌었나".
 */
public final class PosBusinessService {

    private static final Logger log = LoggerFactory.getLogger(PosBusinessService.class);

    private final PosBusinessRepository repository;

    public PosBusinessService(PosBusinessRepository repository) {
        this.repository = repository;
    }

    /**
     * @param recordedEvent 이력에 적힌 사건. POS가 OPEN 을 보내도
     *                      서버가 REOPEN 으로 바꿔 적을 수 있습니다.
     */
    public record Outcome(BusinessState state, BusinessEvent recordedEvent,
                          IrtResult result, int seq, String message) {
    }

    public Outcome receive(PosBusinessIrt irt) {
        Optional<BusinessState> current =
                repository.find(irt.storeId(), irt.posNo(), irt.businessDate());

        if (current.isEmpty()) {
            return first(irt);
        }
        BusinessState state = current.get();

        if (irt.eventAt().isBefore(state.eventAt())) {
            // 상태는 그대로. 이력에만 남기고, 무엇보다 오래됐는지를
            // 메시지에 적습니다 — 몇 초 밀린 것과 아홉 시간 밀린 것은
            // 같은 사건이 아닙니다.
            String message = "지나간 사건입니다: event_at=" + irt.eventAt()
                    + " 이 현재 상태의 " + state.eventAt() + " 보다 오래됐습니다";
            int seq = repository.record(irt, IrtResult.IGNORED_STALE, message);
            log.warn("{}/{} {} 지나간 {} IRT 를 무시했습니다 ({})",
                    irt.storeId(), irt.posNo(), irt.businessDate(), irt.event(), message);
            return new Outcome(state, irt.event(), IrtResult.IGNORED_STALE, seq, message);
        }

        boolean wantsOpen = irt.event() == BusinessEvent.OPEN;
        if (wantsOpen == state.open()) {
            String message = "이미 " + state.status() + " 입니다";
            int seq = repository.record(irt, IrtResult.DUPLICATE, message);
            return new Outcome(state, irt.event(), IrtResult.DUPLICATE, seq, message);
        }

        // 닫혀 있던 단말이 다시 여는 것이 재개점입니다. 이 한 줄이
        // 이 서비스가 존재하는 이유의 절반입니다.
        BusinessEvent recorded = wantsOpen ? BusinessEvent.REOPEN : BusinessEvent.CLOSE;
        BusinessState next = new BusinessState(
                irt.storeId(), irt.posNo(), irt.businessDate(),
                wantsOpen ? BusinessStatus.OPEN : BusinessStatus.CLOSED,
                recorded,
                wantsOpen ? state.openCount() + 1 : state.openCount(),
                irt.eventAt(), Instant.now());

        int seq = repository.apply(next, recorded, IrtResult.APPLIED, null, irt.eventAt(), irt.irtId());
        log.info("{}/{} {} {} (그날 {}번째 개점)",
                irt.storeId(), irt.posNo(), irt.businessDate(), recorded, next.openCount());
        return new Outcome(next, recorded, IrtResult.APPLIED, seq, null);
    }

    /**
     * 그 단말의 그날 첫 전문.
     *
     * <p>개점 없이 마감이 먼저 오는 경우를 <b>거절하지 않습니다</b>.
     * 개점 전문이 유실됐거나 서버가 내려가 있었다는 뜻인데, 여기서
     * 막으면 그 단말은 그날 내내 "열려 있음"으로 남습니다 — 실제로는
     * 닫혀 있는데도.
     */
    private Outcome first(PosBusinessIrt irt) {
        boolean opening = irt.event() == BusinessEvent.OPEN;
        String message = opening ? null : "개점 기록 없이 마감을 받았습니다";

        BusinessState state = new BusinessState(
                irt.storeId(), irt.posNo(), irt.businessDate(),
                opening ? BusinessStatus.OPEN : BusinessStatus.CLOSED,
                irt.event(), opening ? 1 : 0,
                irt.eventAt(), Instant.now());

        int seq = repository.apply(state, irt.event(), IrtResult.APPLIED, message, irt.eventAt(), irt.irtId());
        if (message != null) {
            log.warn("{}/{} {} {}", irt.storeId(), irt.posNo(), irt.businessDate(), message);
        }
        return new Outcome(state, irt.event(), IrtResult.APPLIED, seq, message);
    }

    public List<BusinessState> statesOf(String storeId, LocalDate businessDate) {
        return repository.statesOf(storeId, businessDate);
    }

    public List<PosBusinessRepository.BusinessHistoryEntry> historyOf(
            String storeId, String posNo, LocalDate businessDate) {
        return repository.historyOf(storeId, posNo, businessDate);
    }

    public Optional<BusinessState> stateOf(String storeId, String posNo, LocalDate businessDate) {
        return repository.find(storeId, posNo, businessDate);
    }
}
