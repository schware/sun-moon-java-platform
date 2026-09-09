*🇰🇷 Korean version: [DEPLOYMENT_kr.md](DEPLOYMENT_kr.md)*

# Deployment runbook — Render + Neon

Target and rationale: [`adr/0007`](adr/0007-deployment-target-render-neon-free-forever.md).
Both services are permanently free (not trial credit) as of 2026-09.

Everything in the repo is ready; what remains needs accounts, which only
you can create. Steps 1-2 are yours, step 3 is a button, steps 4-5 verify.

---

## 1. Create the Neon database

1. Sign up at <https://neon.com> (free plan, no card).
2. Create a project — any name; pick the region closest to your Render region.
3. From the connection details, note the **host**, **database**, **user**
   and **password**.
4. Build the JDBC URL (Neon shows a `postgresql://` URI; JDBC needs the
   `jdbc:` prefix and `sslmode=require`):

   ```
   jdbc:postgresql://<host>/<database>?sslmode=require
   ```

You do **not** need to create tables — Flyway runs `V1`-`V4` automatically
on first boot (`Bootstrap.buildRepositories`).

## 2. Create the Render service

1. Sign up at <https://render.com> and connect the GitHub account that owns
   `schware/sun-moon-java-platform`.
2. **New → Blueprint**, select that repository, and set the branch to
   **`master`** — ⚠️ not `main`, which holds an unrelated Spring Boot
   project (`adr/0009`).
3. Render reads [`render.yaml`](../render.yaml) and creates the service.
   It will prompt for the five `sync: false` variables:

   | Variable | Value |
   |---|---|
   | `POSTGRES_JDBC_URL` | the URL from step 1.4 |
   | `POSTGRES_USER` | Neon user |
   | `POSTGRES_PASSWORD` | Neon password |
   | `BO_ADMIN_USERNAME` | your choice — the first BO login |
   | `BO_ADMIN_PASSWORD` | your choice — use a generated password |

   These are entered in Render's dashboard and never committed.

## 3. Deploy

Render builds the [`Dockerfile`](../Dockerfile) (multi-stage: JDK builds,
JRE runs) and starts the service. First build takes a few minutes;
subsequent pushes to `master` deploy automatically.

## 4. Verify

```bash
BASE=https://<your-service>.onrender.com

curl $BASE/health
# {"status":"UP"}

curl -c cookies.txt -X POST $BASE/bo/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"<BO_ADMIN_USERNAME>","password":"<BO_ADMIN_PASSWORD>"}'
# 200 {"username":"...","displayName":"Super Admin","superAdmin":true}

curl -b cookies.txt $BASE/bo/devices
# [] — empty, but 200 proves the session cookie and Postgres both work

curl -b cookies.txt -X POST $BASE/bo/common-code \
  -H "Content-Type: application/json" \
  -d '{"groupCode":"DEVICE_TYPE","code":"POS","name":"POS","sortOrder":1,"active":true}'
# 201 — a row that survives a restart, unlike the in-memory fakes
```

In the Render logs, the line that confirms the real adapters are live is:

```
Bootstrap - POSTGRES_JDBC_URL set — using PostgreSQL repositories (jdbc:postgresql://...)
```

If it instead says `using in-memory fake repositories`, `POSTGRES_JDBC_URL`
didn't reach the service and **data will silently vanish on restart** —
fix the variable before going further.

## 5. What this deployment does and doesn't prove

**Proves** (first time for all of it): the Dockerfile builds; the MyBatis
mappers, `MyBatisConfig` and all four Flyway migrations execute against a
real PostgreSQL; the BO session cookie works over real TLS with `Secure`
set; the whole platform runs somewhere other than a laptop.

**Doesn't prove**: throughput or the 1,000-10,000 connection target —
Render's free plan is a small shared instance that sleeps after 15 minutes
idle (~1 minute cold start), and Neon's free compute scales to zero too.
That's fine for a demonstrable URL; it is not a load-test environment.

**Not deployed by this**: the Order API (8083) and Socket (9090) listeners
still run inside the container, but Render publishes only one port, so
they aren't reachable from outside. That's deliberate — BO is the surface
being deployed (`adr/0009`).

## Known caveats

- **Free instance sleeps.** First request after idle takes ~1 minute.
- **Sessions are in-process.** `InMemorySessionStore` is still the only
  implementation, so every deploy or restart logs everyone out, and this
  cannot be scaled past one instance until `RedisSessionStore` exists.
- **No CORS yet.** A browser Frontend on another origin cannot call BO
  until CORS with credentials is added.
- **Neon free limits**: 0.5 GB storage, 100 compute-hours/month per
  project. Ample here; worth knowing before adding real volume.
