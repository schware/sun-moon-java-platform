#!/bin/sh
# sun-moon 의 PostgreSQL·Redis 컨테이너 (2026-10-02, Debian 13 새 서버).
#
# - --network host 에 localhost 만: 서비스는 예전처럼 localhost:5432 / localhost:6379 로 붙고,
#   밖에서는 DB 에 닿지 않습니다.
# - 데이터는 /srv/sunmoon/data 아래. 비밀번호는 /srv/sunmoon/env/*.env (600) 에만.
# - localhost(TCP)도 비밀번호를 묻습니다 — postgres_fdw 는 비밀번호를 안 묻는 서버에 일반 계정으로
#   붙지 않습니다. 처음 만들 때는 POSTGRES_INITDB_ARGS 가 그렇게 만들고, 데이터 폴더가 이미 있으면
#   initdb 가 안 돌므로 pg_hba.conf 를 직접 고칩니다(2026-10-03 에 그렇게 고침).
# - 이미 있는 컨테이너는 건너뜁니다 — 다시 만들려면 먼저 docker rm -f.
set -eu

if ! docker container inspect sun-moon-postgres >/dev/null 2>&1; then
  docker run -d --name sun-moon-postgres --network host --restart unless-stopped \
    --env-file /srv/sunmoon/env/postgres-admin.env \
    -e POSTGRES_INITDB_ARGS="--auth-host=scram-sha-256 --auth-local=trust" \
    -v /srv/sunmoon/data/postgres:/var/lib/postgresql/data \
    postgres:17 \
    -c listen_addresses=localhost -c timezone=Asia/Seoul -c log_timezone=Asia/Seoul
fi

if ! docker container inspect sun-moon-redis >/dev/null 2>&1; then
  docker run -d --name sun-moon-redis --network host --restart unless-stopped \
    -v /srv/sunmoon/data/redis:/data \
    redis:7 \
    redis-server --bind 127.0.0.1 --port 6379 --save 60 1000
fi

docker ps --filter name=sun-moon-postgres --filter name=sun-moon-redis --format '{{.Names}}\t{{.Status}}'
