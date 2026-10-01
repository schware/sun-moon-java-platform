-- 기기 페어링을 걷어냅니다 (2026-10-01 Sun&Moon 결정)
--
-- 단말 토큰은 제외하고(단말의 API 토큰은 결국 장비 토큰이 되므로), KDS·DID 의 짝은
-- STORE-ID 로 갑니다. 그래서 V6 의 코드·자격 표는 쓰는 곳이 없습니다. 남은 행은 시험 때
-- 만든 것과 해제된 자격뿐이었습니다. V6 파일은 이력이라 그대로 둡니다.
DROP TABLE IF EXISTS device_credential;
DROP TABLE IF EXISTS device_pairing_code;
