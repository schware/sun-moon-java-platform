-- ETC 매출 분해 (업무 정의서 §3.3)
--
-- 문서가 이렇게 적어 뒀습니다:
--
--   · ETC 매출 원본을 ETC_SALE_LOG 에 BLOB 형태로 저장한다.
--   · TR 매출과 동일한 방식으로 주기적으로 원본을 분해한다.
--   · 분해 결과를 ETC 매출 상세 업무 테이블에 저장한다.
--   · **ETC 상세 테이블 구조는 추후 확정한다.**
--
-- 마지막 줄이 이 마이그레이션의 모양을 정합니다. **상세 구조를 지어내지
-- 않습니다.** 지금 컬럼을 만들어 두면 나중에 진짜 구조가 정해질 때
-- 옮겨야 하고, 그 사이에 쌓인 데이터가 옮기는 일을 어렵게 만듭니다.
--
-- 대신 **이름과 값의 쌍**으로 담습니다. 이 저장소에 이미 같은 판단의
-- 선례가 있습니다 — tr_sale_etc 가 그렇게 돼 있고, 이유도 같습니다
-- ("무엇이 올지 아직 안 정해져서, 오는 것을 그대로 담습니다").
--
-- 구조가 정해지는 날 할 일은 두 가지입니다: 새 표를 만들고, **원본을
-- 다시 분해합니다.** 원본을 바이트로 들고 있는 것이 그때 값을 합니다
-- (umbrella docs/adr/0013).

-- 1. 원본 표에 수명주기 컬럼 두 개 -------------------------------------
--
-- tr_sale_log 에는 있고 여기에는 없었습니다. 분해를 안 하고 있었으니
-- 필요가 없었던 것인데, 이제 같은 배치가 둘을 다룹니다.
--
-- attempts 는 "몇 번 시도했나"이고, 그 값이 뜻을 가지려면 실패한 것을
-- 배치가 계속 집어 가지 않아야 합니다. TR 에서 그것으로 한 번 당했고
-- (2026-09-18, attempts 가 388까지 올라가 신호로 못 쓰게 됨), 공통 배치는
-- 처음부터 RECEIVED 만 봅니다.
ALTER TABLE etc_sale_log ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;
ALTER TABLE etc_sale_log ADD COLUMN IF NOT EXISTS decomposed_at TIMESTAMPTZ;

-- 분해 대기 목록은 "RECEIVED 를 받은 순서대로"만 봅니다. tr_sale_log 와
-- 같은 질문이라 같은 인덱스입니다.
CREATE INDEX IF NOT EXISTS idx_etc_sale_log_pending
    ON etc_sale_log (status, received_at)
    WHERE status = 'RECEIVED';

-- 실패한 것을 매장별로 찾는 길 — 재처리 화면이 쓰는 질문입니다.
CREATE INDEX IF NOT EXISTS idx_etc_sale_log_failed
    ON etc_sale_log (store_id, received_at)
    WHERE status = 'FAILED';

-- 2. 상세 — 이름과 값의 쌍 ----------------------------------------------
--
-- log_id 로 원본을 가리킵니다. 원본이 사라지면 상세도 함께 갑니다:
-- 원본 없는 분해 결과는 되짚을 수가 없어서 남겨 둘 이유가 없습니다.
--
-- 키(판매일자/점포/POS/ETC번호)를 여기에도 적습니다. 조인 없이 "그날
-- 그 매장의 ETC" 를 물어볼 수 있어야 하고, 그게 tr_sale_header 가 같은
-- 값을 들고 있는 이유이기도 합니다.
CREATE TABLE IF NOT EXISTS etc_sale_detail (
    id            BIGSERIAL PRIMARY KEY,
    log_id        BIGINT NOT NULL REFERENCES etc_sale_log(id) ON DELETE CASCADE,
    store_id      TEXT NOT NULL,
    pos_no        TEXT NOT NULL,
    business_date DATE NOT NULL,
    etc_no        TEXT NOT NULL,
    detail_seq    INT  NOT NULL,
    name          TEXT NOT NULL,
    value         TEXT,
    decomposed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 다시 분해하면 먼저 지우고 다시 넣습니다. 그래도 제약을 거는 것은
    -- 지우기를 빠뜨린 코드가 조용히 두 벌을 쌓지 못하게 하기 위해서입니다.
    UNIQUE (log_id, detail_seq)
);

CREATE INDEX IF NOT EXISTS idx_etc_sale_detail_day
    ON etc_sale_detail (store_id, business_date);
