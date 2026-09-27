-- 기기 페어링 (umbrella docs/design/store-app.md §3, store-app 1단계)
--
-- 지금까지 단말은 자기 매장·번호·종류를 **스스로 말했습니다**
-- (/ws?storeId=..&deviceId=..&type=..). 말한 것을 BO 명단과 대조할 뿐,
-- 그 말을 한 것이 그 기기인지는 몰랐습니다. 이제 BO 가 발급한 코드로
-- 한 번 짝을 맺고, 그 뒤로는 토큰이 신원입니다.

-- 1. 페어링 코드 — 6자리, 10분, 한 번만 ------------------------------
--
-- 코드는 백만 개뿐이라 **시간이 지나면 다시 나옵니다.** 그래서 코드가
-- 키가 아닙니다: 기록은 남기고(누가 언제 어느 기기에 발급했나), 살아 있는
-- 코드끼리만 겹치지 않게 발급할 때 확인합니다.
CREATE TABLE IF NOT EXISTS device_pairing_code (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(6)  NOT NULL,
    store_id    TEXT        NOT NULL,
    device_id   TEXT        NOT NULL,
    device_type TEXT        NOT NULL,          -- POS | KDS | DID
    kds_role    TEXT,                          -- KDS 만: MAIN | SUB
    issued_by   TEXT,                          -- BO 운영자
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_device_pairing_code_live
    ON device_pairing_code (code) WHERE used_at IS NULL;

-- 2. 기기 자격 — 토큰은 해시만 ----------------------------------------
--
-- kds_role 은 **페어링 순간의 값**입니다. BO 에서 역할을 바꾸면 그 기기는
-- 다시 페어링해야 합니다 — 역할이 소리 없이 바뀌어 주방의 중심이 옮겨
-- 가는 일을 막습니다.
CREATE TABLE IF NOT EXISTS device_credential (
    id          BIGSERIAL PRIMARY KEY,
    store_id    TEXT        NOT NULL,
    device_id   TEXT        NOT NULL,
    device_type TEXT        NOT NULL,
    kds_role    TEXT,
    token_hash  TEXT        NOT NULL UNIQUE,   -- SHA-256(token), hex
    paired_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    revoked_why TEXT                           -- REPAIRED | REVOKED
);
-- 한 기기에 살아 있는 자격은 하나. 다시 페어링하면 옛 것을 끝내고 새로.
CREATE UNIQUE INDEX IF NOT EXISTS uq_device_credential_live
    ON device_credential (store_id, device_id, device_type) WHERE revoked_at IS NULL;
