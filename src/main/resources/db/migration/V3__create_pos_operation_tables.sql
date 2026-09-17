-- POS 운영 관리 (업무 정의서 §3.4 ~ §3.6)
--
-- 매출(V2)이 "무엇이 팔렸나"라면 여기는 "그 단말이 지금 어떤가"입니다.
-- 셋이 한 마이그레이션에 있는 것은 우연이 아닙니다 — 개점 상태가
-- Live Check 의 한 축이고, 마스터 배포 결과가 또 한 축입니다.

-- 1. 개점·마감 (§3.4) ------------------------------------------------
--
-- 여기의 개점/마감은 **단말 단위**입니다. Order 서비스에도 개점/마감이
-- 있지만 그것은 매장이 주문을 받는가이고, 이것은 그 POS가 매출을 올릴
-- 수 있는가입니다. POS 셋 중 하나만 마감한 매장은 여전히 영업 중입니다.
--
-- 현재 상태와 이력을 나눈 이유는 **읽는 쪽이 다르기 때문**입니다.
-- 관제 화면은 "지금 열려 있나"만 묻고(한 줄), 정산과 감사는 "몇 시에
-- 열고 닫고 다시 열었나"를 묻습니다(전부). 한 표에 담으면 앞의 질문이
-- 매번 이력을 훑게 됩니다.

-- 현재 상태: 판매일자 + 매장 + POS 하나에 한 줄. MERGE 대상입니다.
CREATE TABLE IF NOT EXISTS pos_business_state (
    store_id TEXT NOT NULL,
    pos_no TEXT NOT NULL,
    business_date DATE NOT NULL,
    -- OPEN / CLOSED. 지금 이 단말이 영업 중인가만 답합니다.
    status TEXT NOT NULL,
    -- 그 상태를 만든 마지막 사건: OPEN / CLOSE / REOPEN.
    -- status 와 별개인 이유: 재개점한 POS와 처음부터 열려 있던 POS는
    -- 둘 다 OPEN 이지만, 그 둘을 못 가르면 "오늘 몇 번 닫혔나"를
    -- 현재 상태만 보고는 알 수 없습니다.
    last_event TEXT NOT NULL,
    -- 그날 몇 번 열었나. 2 이상이면 재개점이 있었다는 뜻입니다.
    open_count INT NOT NULL DEFAULT 0,
    -- POS에서 사건이 일어난 시각. 아래 received_at 과 다릅니다 —
    -- 끊겼던 POS는 한참 뒤에 올립니다.
    event_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, pos_no, business_date)
);

-- 이력: 들어온 것은 **전부** 남습니다. 늦게 와서 무시된 사건도
-- 남습니다 — 무시했다는 사실 자체가 나중에 답해야 할 질문입니다
-- ("이 POS는 왜 마감으로 안 바뀌었나").
CREATE TABLE IF NOT EXISTS pos_business_hist (
    id BIGSERIAL PRIMARY KEY,
    store_id TEXT NOT NULL,
    pos_no TEXT NOT NULL,
    business_date DATE NOT NULL,
    -- 처리 순번. 같은 키 안에서 1부터. 시각이 같은 두 사건이 올 수
    -- 있어서(초 단위 전문) 시각만으로는 순서를 못 정합니다.
    seq INT NOT NULL,
    event TEXT NOT NULL,
    -- APPLIED / DUPLICATE / IGNORED_STALE. 받았지만 상태를 바꾸지
    -- 않은 경우가 둘이고, 그 둘은 서로 다른 일입니다.
    result TEXT NOT NULL,
    message TEXT,
    event_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 원본 IRT 식별값. 같은 전문이 두 번 와도 이력이 하나여야 합니다.
    irt_id TEXT,
    UNIQUE (store_id, pos_no, business_date, seq)
);

-- 같은 IRT는 한 번만. 부분 유일 인덱스라 irt_id 를 안 보낸 POS도
-- 막히지 않습니다 — 그 경우는 중복을 가릴 근거가 없을 뿐입니다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pos_business_hist_irt
    ON pos_business_hist (irt_id) WHERE irt_id IS NOT NULL;

-- 2. Live Check (§3.5) -----------------------------------------------
--
-- 표에는 **POS가 보고한 것만** 담깁니다. 5종 가운데 Connection 은
-- 소켓 레지스트리가, Business 는 위의 pos_business_state 가 이미
-- 알고 있어서, 여기 또 적으면 두 곳이 서로 다른 말을 하는 날이
-- 옵니다. 읽을 때 합칩니다.
--
-- 문서가 못 박은 것: "WebSocket 연결 상태만으로 정상 영업 여부를
-- 판단하지 않는다." 그래서 축마다 자기 시각을 들고 있습니다 —
-- 보고가 끊긴 축은 OK 도 DOWN 도 아니고 UNKNOWN 입니다.
CREATE TABLE IF NOT EXISTS pos_live_report (
    store_id TEXT NOT NULL,
    pos_no TEXT NOT NULL,
    -- OK / WARN / DOWN. POS가 자기 자신에 대해 말한 값입니다.
    application_status TEXT NOT NULL DEFAULT 'UNKNOWN',
    device_status TEXT NOT NULL DEFAULT 'UNKNOWN',
    data_status TEXT NOT NULL DEFAULT 'UNKNOWN',
    -- §3.5 POS Device 상태 수신. 버전은 마스터/프로그램 배포가
    -- 대상을 고를 때 쓰는 값이라 Live Check 와 같은 줄에 둡니다.
    program_version TEXT,
    db_version TEXT,
    -- 연결 장비 목록. 모양이 장비마다 달라서 JSON 그대로 둡니다.
    devices JSONB,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, pos_no)
);

-- 3. 마스터 IRT 배포 (§3.6) ------------------------------------------
--
-- BO가 만든 마스터를 POS-Server 가 받아 장비에 내려보냅니다. 여기서
-- 관리하는 것은 마스터의 **내용이 아니라 버전과 적용 결과**입니다 —
-- 내용은 BO의 View 가 갖고 있고, 그것을 이 표에 복제하면 두 벌이
-- 어긋나는 날이 옵니다.

-- 매장별·마스터별 현재 버전. BO가 바꾸면 올라갑니다.
CREATE TABLE IF NOT EXISTS pos_master_version (
    store_id TEXT NOT NULL,
    master_type TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, master_type)
);

-- 장비별 적용 결과. 키가 (매장, 장비유형, 장비번호, 마스터)인 것은
-- 문서의 "장비별 수신, 적용, 실패 결과와 적용 버전을 관리한다" 그대로
-- 입니다. 장비번호만으로는 매장이 갈리지 않고, 장비유형까지 있어야
-- 같은 번호의 POS와 KIOSK가 서로를 덮지 않습니다.
CREATE TABLE IF NOT EXISTS pos_master_dispatch (
    id BIGSERIAL PRIMARY KEY,
    store_id TEXT NOT NULL,
    device_type TEXT NOT NULL,
    device_no TEXT NOT NULL,
    master_type TEXT NOT NULL,
    -- 장비가 실제로 적용한 버전. NULL 이면 한 번도 받지 못한 장비이고,
    -- 그때는 변경분이 아니라 전체를 보냅니다 (최초 설치·복구).
    applied_version BIGINT,
    -- SENT / APPLIED / FAILED.
    status TEXT NOT NULL DEFAULT 'SENT',
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    sent_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    UNIQUE (store_id, device_type, device_no, master_type)
);

-- 재전송 대상을 찾는 자리. 끝난 것은 인덱스에 들어가지도 않습니다.
CREATE INDEX IF NOT EXISTS idx_pos_master_dispatch_unfinished
    ON pos_master_dispatch (store_id, device_type, device_no)
    WHERE status <> 'APPLIED';
