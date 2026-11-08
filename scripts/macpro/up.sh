#!/bin/sh
# sun-moon 서비스를 미리 빌드한 image 로 띄웁니다 (macpro-debian 용, 2026-11-08).
#
#   up.sh load                 # /srv/sunmoon/release/images-sunmoon.tar.gz 를 docker load (처음 세울 때)
#   up.sh bo|order|app-server|device|channel-order|landing
#   up.sh all                  # 위 순서대로 전부 (fdw SQL 은 docs/DEPLOY-MACPRO.md 순서대로 따로)
#
# - dell-debian 의 deploy.sh 에서 git pull·docker build 를 뺀 것입니다. 이 서버는 빌드하지 않습니다.
# - 평소 배포는 작업 PC 의 scripts/ship.sh 가 image 를 넣고 이 script 를 부릅니다.
# - 서버의 /srv/sunmoon/bin/up.sh 가 이 파일의 사본입니다.
# - 실행 설정은 /srv/sunmoon/env/<서비스>.env 에서만 읽습니다(channel-order 는 env 없음).
set -eu

S=/srv/sunmoon
R=$S/release

run() {
  svc=$1
  case "$svc" in
    bo)            mem=512m; health=http://localhost:8080/bo/actuator/health ;;
    order)         mem=512m; health=http://localhost:8083/order/actuator/health ;;
    app-server)    mem=384m; health=http://localhost:8086/app-server/actuator/health ;;
    device)        mem=512m; health=http://localhost:8087/health ;;
    channel-order) mem=384m; health=http://localhost:8085/channel-order/actuator/health ;;
    landing)
      docker rm -f sun-moon-landing >/dev/null 2>&1 || true
      docker run -d --name sun-moon-landing -p 8000:80 --restart unless-stopped \
        -v $S/nginx/html:/usr/share/nginx/html:ro nginx:alpine >/dev/null
      echo "sun-moon-landing: :8000"; return 0 ;;
    *) echo "사용: $0 load|all|bo|order|app-server|device|channel-order|landing" >&2; exit 2 ;;
  esac
  name=sun-moon-$svc
  env_file=$S/env/$svc.env
  docker rm -f "$name" >/dev/null 2>&1 || true
  if [ -f "$env_file" ]; then
    docker run -d --name "$name" --network host --memory="$mem" --restart unless-stopped \
      --env-file "$env_file" "$name" >/dev/null
  else
    docker run -d --name "$name" --network host --memory="$mem" --restart unless-stopped "$name" >/dev/null
  fi
  i=0
  while [ $i -lt 36 ]; do
    sleep 5; i=$((i + 1))
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$health")" = 200 ]; then
      echo "$name: health 200 ($((i * 5))초)"; return 0
    fi
  done
  echo "$name: 3분 안에 health 가 200 이 아닙니다 — docker logs $name" >&2
  exit 1
}

case "${1:-}" in
  load) gunzip -c "$R/images-sunmoon.tar.gz" | docker load ;;
  all)  for s in bo order app-server device channel-order landing; do run $s; done ;;
  *)    run "${1:-}" ;;
esac
