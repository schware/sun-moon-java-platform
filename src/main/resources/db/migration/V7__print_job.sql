-- 라벨 작업 (umbrella docs/design/store-app.md §7, store-app 5단계)
--
-- 주문이 다 만들어지면 줄 × 수량만큼 라벨을 찍습니다(음료 3잔 = 3장). 찍는 것은
-- KDS-MAIN 셸이고, 여기는 **무엇을 찍어야 하고 찍혔는지**를 적는 자리입니다.
--
-- content 는 **만드는 순간 고정**합니다. 재출력은 이걸 그대로 복사합니다 — 다시
-- 그리지 않습니다. 그 사이 BO 에서 메뉴 이름을 바꿔도 손님 컵에 붙은 라벨과 다시
-- 찍은 라벨이 같아야, 어느 컵이 누구 것인지 헷갈리지 않습니다.
CREATE TABLE IF NOT EXISTS print_job (
    id           BIGSERIAL PRIMARY KEY,
    store_id     TEXT        NOT NULL,
    printer_id   TEXT        NOT NULL,          -- BO 장비 device_id (LABEL_PRINTER)
    kind         TEXT        NOT NULL,          -- LABEL (나중에 KITCHEN 등)
    order_id     BIGINT      NOT NULL,
    line_seq     INT,
    unit_no      INT,                           -- 수량 중 몇 번째 (1/3)
    unit_total   INT,
    copy_no      INT         NOT NULL DEFAULT 0, -- 0 = 처음, 1.. = 재출력 차례
    original_id  BIGINT REFERENCES print_job(id),
    content      JSONB       NOT NULL,
    requested_by TEXT        NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status       TEXT        NOT NULL DEFAULT 'QUEUED',   -- QUEUED | PRINTED | FAILED
    error        TEXT,
    attempts     INT         NOT NULL DEFAULT 0,
    printed_at   TIMESTAMPTZ,
    -- 처음 출력은 단위마다 한 번(주문 이벤트가 두 번 와도). 재출력은 copy_no 가 달라 여러 번.
    UNIQUE (order_id, line_seq, unit_no, copy_no)
);

CREATE INDEX IF NOT EXISTS idx_print_job_queue
    ON print_job (store_id, status) WHERE status <> 'PRINTED';
CREATE INDEX IF NOT EXISTS idx_print_job_order ON print_job (order_id);
