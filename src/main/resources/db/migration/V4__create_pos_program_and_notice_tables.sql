-- POS 시스템 통신의 나머지 (업무 정의서 §3.5)
--
-- V3 가 "그 단말이 지금 어떤가"였다면 여기는 "그 단말에 무엇을 내려
-- 보내는가"입니다. 셋 다 서버가 장비에 **거는** 일이고, 셋 다 같은
-- 모양을 씁니다 — 목표가 있고, 장비별 적용 결과가 따로 있습니다.

-- 1. System Date 동기화 -----------------------------------------------
--
-- POS 시각을 따로 표에 두지 않고 Live 보고에 붙입니다. 시간이 틀어진
-- 것은 그 자체로 하나의 사건이 아니라 **그 단말의 상태**이고, Heartbeat
-- 가 올 때마다 새로 재는 값이기 때문입니다.
--
-- 왜 재야 하는가: POS 시각이 틀어지면 판매일자가 틀어지고, 판매일자가
-- 틀어지면 그날 매출이 다른 날로 갑니다 (umbrella docs/adr/0006).
-- 영수증의 시각도 손님이 보는 값입니다.
ALTER TABLE pos_live_report ADD COLUMN IF NOT EXISTS pos_time TIMESTAMPTZ;
-- 서버 - POS. 양수면 POS가 느린 것입니다. 부호를 지우지 않는 이유는
-- 빠른 것과 느린 것이 다른 고장이기 때문입니다.
ALTER TABLE pos_live_report ADD COLUMN IF NOT EXISTS clock_drift_seconds INT;

-- 2. 프로그램 배포 -----------------------------------------------------
--
-- 마스터 배포(§3.6)와 같은 모양입니다: 목표 버전이 있고, 장비별 적용
-- 결과가 따로 있습니다. 그런데 표를 같이 쓰지 않는 이유가 둘입니다 —
-- 프로그램에는 받을 파일(주소와 체크섬)이 있고, 버전이 숫자가 아니라
-- 이름입니다(1.4.2).

-- 무엇을 깔아야 하는가. store_id 가 NULL 이면 전 매장입니다.
CREATE TABLE IF NOT EXISTS pos_program_release (
    id BIGSERIAL PRIMARY KEY,
    store_id TEXT,
    device_type TEXT NOT NULL,
    -- 어떤 프로그램인가 — POS 본체, 결제 모듈, 프린터 드라이버 등.
    program TEXT NOT NULL,
    version TEXT NOT NULL,
    package_url TEXT NOT NULL,
    -- 받은 파일이 그 파일인지 장비가 확인할 근거. 없이 설치하면
    -- 중간에 잘린 파일도 설치됩니다.
    checksum TEXT,
    -- 필수면 장비가 미루지 않습니다. 결제 모듈 긴급 패치 같은 것.
    mandatory BOOLEAN NOT NULL DEFAULT false,
    note TEXT,
    released_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- store_id 가 NULL 일 수 있어서 보통 UNIQUE 로는 중복이 막히지 않습니다
-- (NULL 은 서로 같지 않습니다). COALESCE 로 '*' 을 만들어 잠급니다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pos_program_release
    ON pos_program_release (COALESCE(store_id, '*'), device_type, program, version);

-- 최신 배포를 찾는 자리. 같은 프로그램의 여러 버전이 쌓입니다.
CREATE INDEX IF NOT EXISTS idx_pos_program_release_latest
    ON pos_program_release (device_type, program, released_at DESC);

-- 장비별 설치 결과. 마스터와 달리 중간 상태(DOWNLOADED)가 있습니다 —
-- 받는 데 실패한 장비와 깔다 실패한 장비는 손볼 곳이 다릅니다.
CREATE TABLE IF NOT EXISTS pos_program_install (
    id BIGSERIAL PRIMARY KEY,
    store_id TEXT NOT NULL,
    device_type TEXT NOT NULL,
    device_no TEXT NOT NULL,
    program TEXT NOT NULL,
    -- 장비가 "깔았다"고 말한 버전. NULL 이면 아직 아무것도 못 깔았습니다.
    installed_version TEXT,
    -- 지금 받고 있거나 깔고 있는 버전. 실패하면 여기 남아서 무엇을
    -- 하다 실패했는지가 보입니다.
    target_version TEXT,
    -- OFFERED / DOWNLOADED / INSTALLED / FAILED
    status TEXT NOT NULL DEFAULT 'OFFERED',
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    offered_at TIMESTAMPTZ,
    installed_at TIMESTAMPTZ,
    UNIQUE (store_id, device_type, device_no, program)
);

CREATE INDEX IF NOT EXISTS idx_pos_program_install_unfinished
    ON pos_program_install (store_id)
    WHERE status <> 'INSTALLED';

-- 3. 공지 및 긴급 메시지 -----------------------------------------------
--
-- 일반 공지는 장비가 물어보고, 긴급 메시지는 서버가 밀어붙입니다
-- (문서: "긴급 메시지는 WebSocket 기반 실시간 알림 방식을 검토한다").
-- 그런데 **표는 하나**입니다: 긴급도 결국 조회로 다시 읽혀야 합니다 —
-- 그 순간 꺼져 있던 장비가 켜졌을 때 못 보면 긴급이 아닙니다.
CREATE TABLE IF NOT EXISTS pos_notice (
    id BIGSERIAL PRIMARY KEY,
    -- NULL 이면 전 매장, 전 장비 유형.
    store_id TEXT,
    device_type TEXT,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    urgent BOOLEAN NOT NULL DEFAULT false,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL 이면 끝이 없습니다. 있으면 그때 지나면 조회에서 빠집니다 —
    -- 지우지 않는 이유는 지난 공지도 "그때 뭐라고 붙었었나"의 답이기
    -- 때문입니다.
    ends_at TIMESTAMPTZ,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pos_notice_window
    ON pos_notice (starts_at DESC, ends_at);

-- 긴급 메시지를 그 장비가 봤는가. 일반 공지에는 필요 없지만 긴급에는
-- 필요합니다 — 안 본 장비가 있으면 전화를 걸어야 하고, 그 목록이
-- 없으면 전부에게 걸게 됩니다.
CREATE TABLE IF NOT EXISTS pos_notice_receipt (
    notice_id BIGINT NOT NULL REFERENCES pos_notice(id) ON DELETE CASCADE,
    store_id TEXT NOT NULL,
    device_type TEXT NOT NULL,
    device_no TEXT NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (notice_id, store_id, device_type, device_no)
);
