*🇰🇷 Korean version: [DEPLOYMENT_kr.md](DEPLOYMENT_kr.md)*

# Deployment runbook — the Debian server

Target and rationale: [`adr/0012`](adr/0012-deploy-to-own-debian-server.md).
Follows the conventions already in use on that server (`Debian-Setting`
repo, `docs/docker.md`): clone into `~/apps/docker/`, build the image on
the server, run one container with `--network host`.

| | |
|---|---|
| Host | `192.168.0.2`, user `schware`, key-based SSH |
| Hardware | Debian 11, i5 M 480 (2010, 4 cores), 5.6 GB RAM |
| Already there | JDK 21, Docker 20.10.5, PostgreSQL `:5432`, Redis `:6379` |

## Port allocation

| Port | Service |
|---|---|
| 8080 / 8081 / 8082 | Spring Order / KDS / Delivery — **untouched** |
| **8083** | this platform — Order API (+ WebSocket `/ws`) |
| **8084** | this platform — BO |
| **9090** | this platform — Socket |

Only `BO_PORT` is overridden; `API_PORT` and `SOCKET_PORT` already default
to 8083 and 9090.

---

## Step 1 — sudo steps (yours; this session can't run sudo)

`schware` can sudo but needs a password, so these two are manual. Both are
one-liners.

**1a. Create the database.** The `sunmoon` role has no `CREATEDB`, so this
needs the postgres superuser:

```bash
sudo -u postgres createdb -O sunmoon platform_service
```

That's it — no schema work. Flyway creates every table on first boot with
a database configured (`V1`-`V4`).

**1b. Open the firewall** (UFW is active; without this the ports answer
only on the server itself):

```bash
sudo ufw allow 8083/tcp && sudo ufw allow 8084/tcp && sudo ufw allow 9090/tcp
sudo ufw status numbered
```

## Step 2 — clone and build (no sudo)

⚠️ **`-b master`** — this repository's default branch `main` is the
unrelated Spring Boot project (`adr/0009`).
⚠️ **`--network host` on `docker build`** — this server's Docker bridge DNS
is broken and `FROM` fails without it.

```bash
mkdir -p ~/apps/docker && cd ~/apps/docker
git clone -b master https://github.com/schware/sun-moon-java-platform.git sun-moon-java-platform-netty
cd sun-moon-java-platform-netty
docker build --network host -t sun-moon-netty .
```

Expect **5-10 minutes** on this CPU. It isn't stuck; `docker images` shows
new layers appearing.

## Step 3 — configure and run

Credentials go in an env file rather than on the `docker run` command line,
so they don't land in shell history or `ps` output:

```bash
cat > ~/apps/docker/sun-moon-java-platform-netty/.env <<'EOF'
BO_PORT=8084
WORKER_THREADS=8
COOKIE_SECURE=false
POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/platform_service
POSTGRES_USER=sunmoon
POSTGRES_PASSWORD=sunmoon_dev_pw
POSTGRES_POOL_SIZE=5
BO_ADMIN_USERNAME=admin
BO_ADMIN_PASSWORD=<choose one>
EOF
chmod 600 ~/apps/docker/sun-moon-java-platform-netty/.env

docker run -d --name sun-moon-netty --network host --memory=512m \
  --restart unless-stopped \
  --env-file ~/apps/docker/sun-moon-java-platform-netty/.env \
  sun-moon-netty
```

**`COOKIE_SECURE=false` is deliberate** — this server is plain HTTP on a
LAN address. Setting it `true` would mean the browser never sends the
session cookie and BO login would silently fail.

**To deploy without the database first** (proves the build, container and
every BO screen without needing step 1a), omit the four `POSTGRES_*` lines.
The log will say `using in-memory fake repositories` and data will not
survive a restart — fine for that stage, not for keeping.

## Step 4 — verify

```bash
docker logs -f sun-moon-netty
```

The line that decides which half you're running:

```
POSTGRES_JDBC_URL set — using PostgreSQL repositories (jdbc:postgresql://...)
```

If it says `using in-memory fake repositories` while you expected a
database, the env file didn't reach the container and **writes will vanish
on restart** — fix that before going further.

Then, from any machine on the LAN:

```bash
BASE=http://192.168.0.2:8084

curl $BASE/health                      # {"status":"UP"}
curl $BASE/metrics                     # Prometheus text format

curl -c c.txt -X POST $BASE/bo/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<BO_ADMIN_PASSWORD>"}'

curl -b c.txt $BASE/bo/devices          # [] with 200 → session + DB both work

curl -b c.txt -X POST $BASE/bo/common-code \
  -H "Content-Type: application/json" \
  -d '{"groupCode":"DEVICE_TYPE","code":"POS","name":"POS","sortOrder":1,"active":true}'
# 201 — restart the container and GET it again; with the database it survives

curl -X POST http://192.168.0.2:8083/orders \
  -H "Content-Type: application/json" -d '{"customerId":"c1","amount":10.5}'   # Order API
printf 'ping' | nc 192.168.0.2 9090     # Socket echo → "ping"
```

## Step 5 — landing page (optional)

The landing page at `:8000` is a static file, edited live with no restart:

```bash
vi ~/apps/docker/nginx/html/index.html   # add a card → http://192.168.0.2:8084/health
```

## Redeploying after a code change

```bash
cd ~/apps/docker/sun-moon-java-platform-netty
git pull
docker build --network host -t sun-moon-netty .
docker stop sun-moon-netty && docker rm sun-moon-netty
docker run -d --name sun-moon-netty --network host --memory=512m \
  --restart unless-stopped --env-file ./.env sun-moon-netty
```

Note that **every deploy logs all BO users out** — sessions live in memory
(`InMemorySessionStore`). Redis is already on this host, so a
`RedisSessionStore` would fix that and is the natural next piece of work.

## What this deployment proves

First execution of: the `Dockerfile`, every MyBatis mapper, all four Flyway
migrations, and the platform running on hardware other than a laptop.

**It does not prove throughput.** A 2010 4-core CPU with 5.6 GB RAM shared
with four other containers is not where the 1,000-10,000 concurrent
connection claim gets tested.

## Known caveats

- **Sessions are in-process** — see above.
- **No CORS**, so a browser Frontend on another origin can't call BO yet.
- **Plain HTTP on the LAN.** If this is ever exposed beyond the LAN it
  needs TLS, and `COOKIE_SECURE` must flip to `true` at the same time.
- Fallback if a public URL is ever wanted: Render + Neon, researched in
  [`adr/0007`](adr/0007-deployment-target-render-neon-free-forever.md).
