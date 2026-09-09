*🇬🇧 English version: [DEPLOYMENT.md](DEPLOYMENT.md)*

# 배포 절차 — Render + Neon

배포 대상과 그 근거: [`adr/0007`](adr/0007-deployment-target-render-neon-free-forever.md).
두 서비스 모두 2026-09 기준으로 체험판 크레딧이 아니라 영구 무료다.

저장소 쪽 준비는 끝났고, 남은 것은 계정이 필요한 부분이라 본인만 할 수
있다. 1~2단계가 직접 하실 일이고, 3단계는 버튼 하나, 4~5단계는 확인이다.

---

## 1. Neon 데이터베이스 생성

1. <https://neon.com>에서 가입한다(무료 플랜, 카드 불필요).
2. 프로젝트를 만든다 — 이름은 아무거나, region은 Render region과 가까운
   곳으로.
3. 연결 정보에서 **host**, **database**, **user**, **password**를 확인한다.
4. JDBC URL을 만든다(Neon은 `postgresql://` 형태로 보여주는데, JDBC는
   `jdbc:` 접두사와 `sslmode=require`가 필요하다):

   ```
   jdbc:postgresql://<host>/<database>?sslmode=require
   ```

테이블을 직접 만들 필요는 **없다** — 첫 기동 때 Flyway가 `V1`~`V4`를
자동으로 실행한다(`Bootstrap.buildRepositories`).

## 2. Render 서비스 생성

1. <https://render.com>에 가입하고, `schware/sun-moon-java-platform`을
   소유한 GitHub 계정을 연결한다.
2. **New → Blueprint**에서 그 저장소를 고르고, 브랜치를 **`master`** 로
   지정한다 — ⚠️ `main`이 아니다. `main`에는 무관한 Spring Boot
   프로젝트가 들어 있다(`adr/0009`).
3. Render가 [`render.yaml`](../render.yaml)을 읽어 서비스를 만든다.
   `sync: false`로 표시된 다섯 개 변수를 입력하라고 물어본다:

   | 변수 | 값 |
   |---|---|
   | `POSTGRES_JDBC_URL` | 1.4에서 만든 URL |
   | `POSTGRES_USER` | Neon user |
   | `POSTGRES_PASSWORD` | Neon password |
   | `BO_ADMIN_USERNAME` | 원하는 값 — 최초 BO 로그인 계정 |
   | `BO_ADMIN_PASSWORD` | 원하는 값 — 생성기로 만든 비밀번호 권장 |

   이 값들은 Render 대시보드에만 입력되고 저장소에는 커밋되지 않는다.

## 3. 배포

Render가 [`Dockerfile`](../Dockerfile)을 빌드하고(멀티스테이지: JDK로
빌드, JRE로 실행) 서비스를 기동한다. 첫 빌드는 몇 분 걸리고, 이후
`master`에 push하면 자동 배포된다.

## 4. 확인

```bash
BASE=https://<서비스명>.onrender.com

curl $BASE/health
# {"status":"UP"}

curl -c cookies.txt -X POST $BASE/bo/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"<BO_ADMIN_USERNAME>","password":"<BO_ADMIN_PASSWORD>"}'
# 200 {"username":"...","displayName":"Super Admin","superAdmin":true}

curl -b cookies.txt $BASE/bo/devices
# [] — 비어 있지만, 200이면 session cookie와 Postgres가 모두 동작한다는 뜻

curl -b cookies.txt -X POST $BASE/bo/common-code \
  -H "Content-Type: application/json" \
  -d '{"groupCode":"DEVICE_TYPE","code":"POS","name":"POS","sortOrder":1,"active":true}'
# 201 — in-memory fake와 달리 재기동해도 남아 있는 행
```

Render 로그에서 실제 adapter가 살아 있음을 확인해주는 줄은 이것이다:

```
Bootstrap - POSTGRES_JDBC_URL set — using PostgreSQL repositories (jdbc:postgresql://...)
```

만약 `using in-memory fake repositories`로 나온다면 `POSTGRES_JDBC_URL`이
서비스에 전달되지 않은 것이고, **재기동 때마다 데이터가 조용히
사라진다.** 더 진행하기 전에 변수부터 고쳐야 한다.

## 5. 이 배포가 증명하는 것과 증명하지 않는 것

**증명한다**(전부 처음으로): Dockerfile이 빌드된다; MyBatis mapper들과
`MyBatisConfig`, Flyway migration 4개가 실제 PostgreSQL에서 실행된다;
BO session cookie가 실제 TLS 위에서 `Secure`로 동작한다; 플랫폼 전체가
노트북이 아닌 곳에서 돌아간다.

**증명하지 않는다**: 처리량이나 1,000~10,000 동시 접속 목표. Render 무료
플랜은 작은 공유 인스턴스이고 15분 유휴 시 잠들며(재기동 약 1분), Neon
무료 컴퓨팅도 0으로 축소된다. 보여줄 수 있는 URL로는 충분하지만 부하
테스트 환경은 아니다.

**이 배포에 포함되지 않는 것**: Order API(8083)와 Socket(9090) listener는
컨테이너 안에서 계속 돌지만 Render는 port 하나만 공개하므로 외부에서
접근되지 않는다. 의도된 것이다 — 배포 대상 표면은 BO다(`adr/0009`).

## 알려진 제약

- **무료 인스턴스는 잠든다.** 유휴 후 첫 요청은 약 1분 걸린다.
- **Session이 프로세스 안에 있다.** `InMemorySessionStore`가 유일한
  구현이라, 배포/재기동 때마다 전원 로그아웃되고 인스턴스를 하나 이상으로
  늘릴 수 없다. `RedisSessionStore`가 생기기 전까지는 그렇다.
- **CORS가 아직 없다.** 다른 origin의 브라우저 Frontend는 CORS(credentials
  포함)가 붙기 전까지 BO를 호출할 수 없다.
- **Neon 무료 한도**: 프로젝트당 스토리지 0.5GB, 월 컴퓨팅 100시간.
  현재 규모에는 충분하지만, 실제 데이터가 쌓이기 전에 알아둘 것.
