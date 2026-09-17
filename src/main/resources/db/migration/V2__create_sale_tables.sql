-- POS-Server 매출 수신·분해 (업무 정의서 §3.1 ~ §3.3)
--
-- POS가 만든 거래를 **원본 그대로** 받아 두고, 나중에 주기적으로
-- 분해합니다. 받는 것과 분해하는 것을 가르는 이유가 셋입니다:
--
--   1. 받는 쪽은 빨라야 합니다. POS는 매장에서 손님을 세워 두고
--      기다립니다 — 분해가 느리다고 수신이 느려지면 안 됩니다.
--   2. 분해에 실패해도 원본은 남아야 합니다. 그게 재처리의 기준이고,
--      "매출이 안 올라왔다"는 문의에 답할 수 있는 유일한 근거입니다.
--   3. 분해 규칙이 바뀌면 원본으로 **다시** 분해할 수 있어야 합니다.
--      분해 결과만 들고 있으면 그날의 규칙에 영원히 묶입니다.

-- 1. TR 매출 원본 ----------------------------------------------------
--
-- payload 가 BYTEA 인 것은 일부러입니다. 지금은 JSON 이지만 POS의
-- 전문 형식은 바뀔 수 있고(EDI, 고정길이, 압축), 그때 이 표를 다시
-- 만들지 않으려면 "무엇이 왔는지"를 해석하지 않고 그대로 담아야
-- 합니다. 해석은 분해가 합니다.
--
-- 유일 제약이 **중복 수신을 막습니다** (§7.3). POS가 배치로 다시
-- 올리거나 응답을 못 받아 재시도할 때, 같은 거래가 두 번 쌓이면
-- 매출이 두 배가 됩니다. 애플리케이션에서 "이미 있나"를 먼저 보는
-- 방식은 그 확인과 쓰기 사이에 창이 남습니다.
--
-- 키는 매출의 키 그대로입니다 — 판매일자 + 점포 + POS번호 + 거래번호
-- (umbrella docs/adr/0009).
CREATE TABLE IF NOT EXISTS tr_sale_log (
    id BIGSERIAL PRIMARY KEY,
    store_id TEXT NOT NULL,
    pos_no TEXT NOT NULL,
    business_date DATE NOT NULL,
    tran_no TEXT NOT NULL,
    content_type TEXT NOT NULL DEFAULT 'application/json',
    payload BYTEA NOT NULL,
    -- RECEIVED → DECOMPOSED, 또는 FAILED. 실패는 지우지 않고 남겨
    -- 두고 다시 돌립니다.
    status TEXT NOT NULL DEFAULT 'RECEIVED',
    error TEXT,
    attempts INT NOT NULL DEFAULT 0,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decomposed_at TIMESTAMPTZ,
    UNIQUE (business_date, store_id, pos_no, tran_no)
);

-- 아직 분해하지 않은 것만 찾습니다. 부분 인덱스라 이미 끝난 거래는
-- 인덱스에 들어가지도 않습니다 — 찾으려는 것이 항상 소수입니다.
CREATE INDEX IF NOT EXISTS idx_tr_sale_log_pending
    ON tr_sale_log (received_at)
    WHERE status <> 'DECOMPOSED';

-- 2. 분해 결과 -------------------------------------------------------
--
-- Header / Detail / Payment / ETC 로 가릅니다 (§3.2). 원본 한 건이
-- Header 한 줄이고, 나머지는 그 밑에 붙습니다.
--
-- log_id 로 원본을 가리킵니다. 분해를 다시 돌릴 때 전에 만든 행을
-- 지우고 새로 넣기 위해서이고, 그래서 ON DELETE CASCADE 입니다 —
-- 반쪽만 남은 분해 결과는 원본보다 나쁩니다.
CREATE TABLE IF NOT EXISTS tr_sale_header (
    id BIGSERIAL PRIMARY KEY,
    log_id BIGINT NOT NULL REFERENCES tr_sale_log(id) ON DELETE CASCADE,
    store_id TEXT NOT NULL,
    pos_no TEXT NOT NULL,
    business_date DATE NOT NULL,
    tran_no TEXT NOT NULL,
    -- 판매 / 취소 / 반품. POS가 보낸 값을 그대로 적습니다.
    tran_type TEXT NOT NULL DEFAULT 'SALE',
    total_amount NUMERIC(14,2) NOT NULL,
    discount_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    -- POS에서 실제로 거래가 일어난 시각. 서버가 받은 시각과 다릅니다 —
    -- 오프라인 POS는 한참 뒤에 올릴 수 있습니다.
    sold_at TIMESTAMPTZ,
    UNIQUE (business_date, store_id, pos_no, tran_no)
);

CREATE TABLE IF NOT EXISTS tr_sale_detail (
    id BIGSERIAL PRIMARY KEY,
    log_id BIGINT NOT NULL REFERENCES tr_sale_log(id) ON DELETE CASCADE,
    header_id BIGINT NOT NULL REFERENCES tr_sale_header(id) ON DELETE CASCADE,
    detail_seq INT NOT NULL,
    menu_code TEXT,
    menu_name TEXT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    unit_price NUMERIC(14,2) NOT NULL,
    detail_amount NUMERIC(14,2) NOT NULL,
    discount_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    UNIQUE (header_id, detail_seq)
);

CREATE TABLE IF NOT EXISTS tr_sale_payment (
    id BIGSERIAL PRIMARY KEY,
    log_id BIGINT NOT NULL REFERENCES tr_sale_log(id) ON DELETE CASCADE,
    header_id BIGINT NOT NULL REFERENCES tr_sale_header(id) ON DELETE CASCADE,
    payment_seq INT NOT NULL,
    payment_type TEXT NOT NULL,
    payment_amount NUMERIC(14,2) NOT NULL,
    approval_no TEXT,
    card_no_masked TEXT,
    UNIQUE (header_id, payment_seq)
);

-- ETC 는 거래에 딸린 그 밖의 것들 — 쿠폰, 스탬프, 주문번호 참조 등.
-- 이름과 값의 쌍으로 두는 이유: 무엇이 올지 아직 안 정해졌고
-- (§3.3 "ETC 상세 테이블 구조는 추후 확정한다"), 컬럼을 지어 두면
-- 그 모양이 정해지는 날 두 번 만들게 됩니다.
CREATE TABLE IF NOT EXISTS tr_sale_etc (
    id BIGSERIAL PRIMARY KEY,
    log_id BIGINT NOT NULL REFERENCES tr_sale_log(id) ON DELETE CASCADE,
    header_id BIGINT NOT NULL REFERENCES tr_sale_header(id) ON DELETE CASCADE,
    etc_seq INT NOT NULL,
    etc_key TEXT NOT NULL,
    etc_value TEXT,
    UNIQUE (header_id, etc_seq)
);

-- 3. ETC 매출 원본 ---------------------------------------------------
--
-- TR 과 같은 방식으로 받아 둡니다 (§3.3). **분해는 아직 없습니다** —
-- 상세 표의 모양이 확정되지 않았기 때문입니다. 그래도 원본을 받는
-- 자리는 지금 만들어 둡니다: 안 받으면 그 사이 거래가 영영 사라지고,
-- 받아만 두면 나중에 분해할 수 있습니다.
CREATE TABLE IF NOT EXISTS etc_sale_log (
    id BIGSERIAL PRIMARY KEY,
    store_id TEXT NOT NULL,
    pos_no TEXT NOT NULL,
    business_date DATE NOT NULL,
    etc_no TEXT NOT NULL,
    content_type TEXT NOT NULL DEFAULT 'application/json',
    payload BYTEA NOT NULL,
    status TEXT NOT NULL DEFAULT 'RECEIVED',
    error TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_date, store_id, pos_no, etc_no)
);
