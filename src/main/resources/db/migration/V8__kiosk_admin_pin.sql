-- KIOSK 관리자 PIN (umbrella docs/design/store-app.md §5.3, §9)
--
-- KIOSK 는 전체 화면에서 빠져나갈 수 없게 잠겨 있습니다(Alt+F4·작업 표시줄 막힘). 매장
-- 직원이 설정을 보거나 끄려면 **화면 구석을 5번 누르고 관리자 PIN** 을 넣습니다.
--
-- PIN 은 BO 장비 화면에서 정하고, 여기에는 **해시만** 둡니다(PBKDF2). 셸이 PIN 을 이 서버에
-- 물어 확인합니다 — 셸에 해시를 내려보내면 KIOSK PC 를 가진 사람이 그걸 풀려고 시도할 수
-- 있습니다. 틀린 PIN 은 기기마다 10분에 5번까지.
CREATE TABLE IF NOT EXISTS kiosk_admin_pin (
    store_id   TEXT        NOT NULL,
    device_id  TEXT        NOT NULL,
    pin_hash   TEXT        NOT NULL,     -- base64(PBKDF2-HMAC-SHA256)
    salt       TEXT        NOT NULL,     -- base64
    iterations INT         NOT NULL,
    set_by     TEXT,
    set_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, device_id)
);
