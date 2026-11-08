# 배포 — macpro-debian (2026-11-08 부터)

실행 서버를 dell-debian 에서 **macpro-debian** 으로 옮겼습니다. 빌드는 그대로 dell-debian 에서 하고,
macpro 는 image 를 받아 띄우기만 합니다. dell 쪽 서버 구성은 Debian-Setting 의
[`docs/debian13.md`](https://github.com/schware/Debian-Setting/blob/master/docs/debian13.md) 에 있습니다.

| | dell-debian | macpro-debian |
|---|---|---|
| 하는 일 | **빌드** (`docker build`) | **실행** (컨테이너) |
| 하드웨어 | i5 M 480 4코어 · RAM 5.6GB | 2코어 · RAM 3.6GB · swap 3GB |
| 공개 주소 | — | `61.73.134.2` (`enp3s0` 에 바로 붙음, 공유기 없음) |
| LAN | 192.168.0.2 | 192.168.0.65 (Wi-Fi) |
| 같이 도는 것 | KEDP(`kedp-postgres` 가 127.0.0.1:5432) | KEDP(:8090, 자기 네트워크 안) |

공개 주소가 도메인(`sunmoonwork.duckdns.org`)에서 IP 로 바뀌었습니다 — DuckDNS 계정을 찾지 못해서입니다.
코드의 기본 주소는 2026-11-08 커밋에서 `61.73.134.2` 로 바꿨습니다(terminals `6cfaf90`, channel-order
`03b59e5`, bo `78f72e2`, store-app `5e2bee3`).

## 포트

| 포트 | 서비스 | 노출 |
|---|---|---|
| 8000 | 첫 화면 + POS·KDS·DID 화면 (nginx, `-p`) | 공개 |
| 8080 | BO | LAN |
| 8083 | Order | LAN |
| 8085 | channel-order (QR 웹·KIOSK) | 공개 |
| 8086 | APP-SERVER | LAN |
| 8087 | Device Server | 공개 |
| 5432 · 6379 | PostgreSQL · Redis | localhost |

서비스는 `--network host` 라 ufw 가 그대로 적용됩니다. `-p` 로 연 8000 은 Docker 가 iptables 를 직접 열어
ufw 와 관계없이 공개됩니다.

## 평소 배포 (코드가 바뀌었을 때)

GitHub 에 push 한 뒤 작업 PC(Git Bash)에서:

```bash
cd sun-moon-java-platform
scripts/ship.sh bo            # order | app-server | device | channel-order
```

dell 에서 `git pull` → `docker build` → image 를 macpro 로 → macpro 에서 `up.sh <서비스>` → health 확인.
빌드는 한 번에 하나(dell 의 잠금)입니다.

단말 화면은 sun-moon-terminals 에서:

```bash
SUNMOON_HOST=mac-debian scripts/deploy.sh pos   # kds | did
```

## 처음부터 세우기

`(sudo)` 는 직접 실행합니다. 비밀값은 서버에서 만들고 화면에 찍지 않습니다.

1. `(sudo)` `sudo mkdir -p /srv/sunmoon && sudo chown sunmoon:sunmoon /srv/sunmoon`
2. `(sudo)` 방화벽 — SSH 포트를 먼저 엽니다:
   ```bash
   sudo ufw allow 922/tcp && sudo ufw allow 8085/tcp && sudo ufw allow 8087/tcp
   sudo ufw allow from 192.168.0.0/24 to any port 8080,8083,8086 proto tcp
   ```
3. `(sudo)` Redis: `echo 'vm.overcommit_memory = 1' | sudo tee /etc/sysctl.d/90-redis.conf && sudo sysctl --system`
4. 폴더와 script: `mkdir -p /srv/sunmoon/{env,data,logs,nginx/html,bin} && chmod 700 /srv/sunmoon/env`,
   이 저장소의 `scripts/macpro/up.sh`·`start-db.sh` 를 `/srv/sunmoon/bin/` 에.
5. 비밀 파일:
   ```bash
   cd /srv/sunmoon/env && umask 077
   pw() { head -c 24 /dev/urandom | base64 | tr -d '/+=' | cut -c1-24; }
   printf 'POSTGRES_PASSWORD=%s\n' "$(pw)" > postgres-admin.env
   printf 'SUNMOON_DB_PASSWORD=%s\nBO_READER_PASSWORD=%s\n' "$(pw)" "$(pw)" > db.env
   ```
6. `start-db.sh` → 역할 `sunmoon`(비밀번호 = `SUNMOON_DB_PASSWORD`)과 DB 다섯 개
   (`bo_service` `order_service` `app_server` `pos_server` `delivery_service`, 주인 `sunmoon`).
7. BO 기준정보가 필요하면 BO 를 띄우기 **전에** dump 를 `pg_restore --no-owner --no-acl --role=sunmoon -d bo_service`.
8. env 파일 — `bo` `order` `app-server` `device` (channel-order 는 없음). 키 목록은 dell 의 `/srv/sunmoon/env/`
   와 같고, 다른 것은:
   - DB 비밀번호 **네 곳 모두** `SUNMOON_DB_PASSWORD` 와 같은 값 — bo 하나만 달라도 BO 가 재시작을 반복합니다
   - BO `DEVICE_SERVER_ADMIN_TOKEN` = Device `ADMIN_TOKEN`
   - BO `QR_TABLE_BASE_URL=http://61.73.134.2:8085/channel-order/t/`, Device `API_AUTH=warn`
9. 기동 순서 — **fdw 는 정해진 자리에서만**:
   ```bash
   up.sh bo
   # app-server-fdw.sql, app-server-fdw-store-tables.sql  (Debian-Setting server/db/, bo_reader = BO_READER_PASSWORD)
   up.sh order && up.sh app-server && up.sh device
   # pos-server-fdw.sql  — Device Server 가 뜬 **뒤에**
   up.sh channel-order && up.sh landing
   ```
   `pos-server-fdw.sql` 을 먼저 돌리면 Device Server 가 "non-empty schema public but no schema history" 로
   기동을 거부합니다. 고치는 법: `pos_server` 에서 `DROP EXTENSION postgres_fdw CASCADE; DROP SCHEMA bo CASCADE;`
   → `up.sh device` → fdw 다시.
10. 화면: `:8000` 첫 화면은 Debian-Setting `server/docker/nginx/html/` 을 `/srv/sunmoon/nginx/html/` 로,
    POS·KDS·DID 는 위의 terminals `deploy.sh`.

## 확인

```bash
for u in :8080/bo/actuator/health :8083/order/actuator/health :8086/app-server/actuator/health \
         :8087/health :8085/channel-order/actuator/health :8000/pos/; do
  curl -s -o /dev/null -w "%{http_code} $u\n" http://localhost$u; done
docker logs sun-moon-device 2>&1 | grep "POSTGRES_JDBC_URL set"     # fake 저장소가 아닌지
```

## 알고 갈 것

- **메모리** — sun-moon 전체 약 1.2GB(서비스 다섯 ~1.07GB + DB). KEDP 와 같이 돌면 여유가 1GB 남짓입니다.
- **재부팅** — 컨테이너는 전부 `--restart unless-stopped` 라 다시 뜹니다(2026-11-08 커널 업데이트 재부팅에서 확인).
- **주문·매출 데이터는 dell 에서 옮기지 않았습니다.** BO 기준정보만 옮겼습니다.
