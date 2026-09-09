*🇬🇧 English version: [DEPLOYMENT.md](DEPLOYMENT.md)*

# 배포 절차 — Debian 서버

> ⚠️ **2026-09-09 기준 보류.** 아래의 포트 배정(BO 8084)은 잘못됐습니다.
> 서버에는 번호 규칙이 있고 — **BO 8080, API 서비스 8081~8089, Socket
> 9090** — 컨테이너 하나가 그중 세 칸을 물는 구조는 그 규칙과 맞지
> 않습니다. 컨테이너는 서버에서 제거했고, 이미지와 clone은 남아 있습니다.
> 나머지 내용(Dockerfile, env 파일, DB 단계, 검증 절차)은 포트와 BO 분리가
> 정해지면 그대로 유효합니다. ADR-0013 참고.

배포 대상과 근거: [`adr/0012`](adr/0012-deploy-to-own-debian-server.md).
이미 그 서버에서 쓰고 있는 관례(`Debian-Setting` 저장소 `docs/docker.md`)를
그대로 따릅니다 — `~/apps/docker/`에 clone, 서버에서 이미지 빌드,
`--network host`로 컨테이너 하나 실행.

| | |
|---|---|
| 호스트 | `192.168.0.2`, 사용자 `schware`, 공개키 SSH |
| 하드웨어 | Debian 11, i5 M 480 (2010, 4코어), RAM 5.6GB |
| 이미 있는 것 | JDK 21, Docker 20.10.5, PostgreSQL `:5432`, Redis `:6379` |

## 포트 배정

| 포트 | 서비스 |
|---|---|
| 8080 / 8081 / 8082 | Spring Order / KDS / Delivery — **건드리지 않음** |
| **8083** | 이 플랫폼 — Order API (+ WebSocket `/ws`) |
| **(미정)** | 이 플랫폼 — BO — 8084는 잘못된 배정, 위 안내 참고 |
| **9090** | 이 플랫폼 — Socket |

`BO_PORT`만 덮어씁니다. `API_PORT`와 `SOCKET_PORT`는 기본값이 이미
8083, 9090입니다.

---

## 1단계 — sudo가 필요한 작업 (직접 해주셔야 합니다)

`schware`는 sudo에 비밀번호가 필요해서 이 세션에서는 실행할 수 없습니다.
둘 다 한 줄짜리입니다.

**1a. 데이터베이스 생성.** `sunmoon` 역할에 `CREATEDB` 권한이 없어서
postgres 슈퍼유저가 필요합니다:

```bash
sudo -u postgres createdb -O sunmoon platform_service
```

이게 전부입니다 — 테이블은 만들 필요 없습니다. DB만 있으면 첫 기동 때
Flyway가 `V1`~`V4`를 전부 생성합니다.

**1b. 방화벽 개방** — BO와 API는 **의도적으로 노출 범위가 다릅니다.**
이 서버는 인터넷에서 접근 가능하고(랜딩 페이지가 공인 IP로 링크합니다),
BO는 `Secure` 없는 session cookie를 쓰는 평문 HTTP 관리자 콘솔이라
**공개되면 안 됩니다**:

```bash
sudo ufw allow 8083/tcp                                          # Order API — 기존처럼 공개
sudo ufw allow from 192.168.0.0/24 to any port 8080 proto tcp    # BO — LAN 전용
sudo ufw status numbered
```

⚠️ 이건 컨테이너를 `--network host`로 띄울 때만 성립합니다. Docker는 `-p`로
포트를 게시하면 iptables를 직접 건드려 **UFW를 우회**하므로, BO를
`-p 8080:8080`으로 바꾸면 위 규칙에도 불구하고 인터넷에서 접근됩니다.
TLS가 붙으면 다시 판단합니다(그때 `COOKIE_SECURE=true`로 바꾸고 BO를
공개할 수 있습니다).

## 2단계 — clone과 빌드 (sudo 불필요)

⚠️ **`-b master`** — 이 저장소의 기본 브랜치 `main`은 무관한 Spring Boot
프로젝트입니다(`adr/0009`).
⚠️ **`docker build`에 `--network host`** — 이 서버는 Docker 브릿지 DNS가
깨져 있어서 안 붙이면 `FROM` 단계에서 실패합니다.

```bash
mkdir -p ~/apps/docker && cd ~/apps/docker
git clone -b master https://github.com/schware/sun-moon-java-platform.git sun-moon-java-platform-netty
cd sun-moon-java-platform-netty
docker build --network host -t sun-moon-netty .
```

이 CPU에서 **5~10분** 걸립니다. 멈춘 게 아니고, `docker images`로 새
레이어가 계속 생기는지 확인하면서 기다리시면 됩니다.

## 3단계 — 설정과 실행

자격 증명은 `docker run` 명령줄이 아니라 env 파일에 둡니다 — 셸 히스토리와
`ps` 출력에 남지 않게 하려는 것입니다:

```bash
cat > ~/apps/docker/sun-moon-java-platform-netty/.env <<'EOF'
BO_PORT=<정해지면 기입>
WORKER_THREADS=8
COOKIE_SECURE=false
POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/platform_service
POSTGRES_USER=sunmoon
POSTGRES_PASSWORD=sunmoon_dev_pw
POSTGRES_POOL_SIZE=5
BO_ADMIN_USERNAME=admin
BO_ADMIN_PASSWORD=<직접 정하세요>
EOF
chmod 600 ~/apps/docker/sun-moon-java-platform-netty/.env

docker run -d --name sun-moon-netty --network host --memory=512m \
  --restart unless-stopped \
  --env-file ~/apps/docker/sun-moon-java-platform-netty/.env \
  sun-moon-netty
```

**`COOKIE_SECURE=false`는 의도된 것입니다** — 이 서버는 LAN 주소의 평문
HTTP입니다. `true`로 두면 브라우저가 session cookie를 아예 안 보내서 BO
로그인이 조용히 실패합니다.

**DB 없이 먼저 띄우려면**(1a 없이도 빌드·컨테이너·BO 화면 전부 검증
가능) `POSTGRES_*` 네 줄을 빼면 됩니다. 로그에
`using in-memory fake repositories`가 찍히고 재기동 시 데이터가 사라집니다 —
그 단계에서는 괜찮지만, 그대로 두면 안 됩니다.

## 4단계 — 확인

```bash
docker logs -f sun-moon-netty
```

어느 쪽으로 돌고 있는지 판가름하는 줄:

```
POSTGRES_JDBC_URL set — using PostgreSQL repositories (jdbc:postgresql://...)
```

DB를 기대했는데 `using in-memory fake repositories`가 나오면 env 파일이
컨테이너에 전달되지 않은 것이고, **재기동 때마다 데이터가 사라집니다** —
더 진행하기 전에 고쳐야 합니다.

그다음 LAN의 아무 PC에서나:

```bash
BASE=http://192.168.0.2:<BO_PORT>

curl $BASE/health                      # {"status":"UP"}
curl $BASE/metrics                     # Prometheus 텍스트 포맷

curl -c c.txt -X POST $BASE/bo/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<BO_ADMIN_PASSWORD>"}'

curl -b c.txt $BASE/bo/devices          # 200에 [] → session과 DB 둘 다 정상

curl -b c.txt -X POST $BASE/bo/common-code \
  -H "Content-Type: application/json" \
  -d '{"groupCode":"DEVICE_TYPE","code":"POS","name":"POS","sortOrder":1,"active":true}'
# 201 — 컨테이너를 재기동하고 다시 조회해보면, DB가 붙어 있으면 남아 있습니다

curl -X POST http://192.168.0.2:8083/orders \
  -H "Content-Type: application/json" -d '{"customerId":"c1","amount":10.5}'   # Order API
printf 'ping' | nc 192.168.0.2 9090     # Socket echo → "ping"
```

## 5단계 — 랜딩 페이지 (선택)

`:8000` 랜딩 페이지는 정적 파일이라 재시작 없이 바로 반영됩니다:

```bash
vi ~/apps/docker/nginx/html/index.html   # 카드 추가 → http://192.168.0.2:<BO_PORT>/health
```

## 코드 변경 후 재배포

```bash
cd ~/apps/docker/sun-moon-java-platform-netty
git pull
docker build --network host -t sun-moon-netty .
docker stop sun-moon-netty && docker rm sun-moon-netty
docker run -d --name sun-moon-netty --network host --memory=512m \
  --restart unless-stopped --env-file ./.env sun-moon-netty
```

**재배포할 때마다 BO 사용자가 전원 로그아웃됩니다** — session이 메모리에
있기 때문입니다(`InMemorySessionStore`). 이 호스트에 Redis가 이미 떠
있으니 `RedisSessionStore`를 만들면 해결되고, 그게 다음 작업으로 가장
자연스럽습니다.

## 이 배포가 증명하는 것

처음으로 실행되는 것들: `Dockerfile`, 모든 MyBatis mapper, Flyway
migration 4개, 그리고 노트북이 아닌 하드웨어 위에서의 플랫폼 전체.

**처리량은 증명하지 않습니다.** 2010년형 4코어 CPU에 5.6GB RAM을 다른
컨테이너 4개와 나눠 쓰는 환경은 1,000~10,000 동시 접속을 검증할 자리가
아닙니다.

## 알려진 제약

- **Session이 프로세스 안에 있습니다** — 위 참고.
- **CORS가 없어서** 다른 origin의 브라우저 Frontend는 아직 BO를 호출할 수
  없습니다.
- **LAN 평문 HTTP.** LAN 밖으로 노출한다면 TLS가 필요하고, 그때
  `COOKIE_SECURE`도 같이 `true`로 바꿔야 합니다.
- 공개 URL이 필요해지면 대안은 Render + Neon입니다 —
  [`adr/0007`](adr/0007-deployment-target-render-neon-free-forever.md)에
  조사 내용이 남아 있습니다.
