#!/bin/sh
# 서비스 하나를 macpro-debian 에 배포합니다 — dell-debian 에서 빌드 → image 를 macpro 로 → 다시 띄우기.
#
#   scripts/ship.sh bo | order | app-server | device | channel-order
#
# - 작업 PC(Git Bash)에서 돌립니다. 서버 별명은 ~/.ssh/config 의 것(BUILD_HOST, RUN_HOST 로 바꿀 수 있음).
# - 빌드는 GitHub 에 push 된 커밋으로 합니다(dell 의 /srv/sunmoon/src/<저장소> 에서 git pull). push 먼저.
# - macpro 는 RAM 3.6GB·2코어라 빌드하지 않습니다. 빌드는 dell, 실행은 macpro (docs/DEPLOY-MACPRO.md).
# - dell 에서는 컨테이너를 띄우지 않습니다 — 그곳의 5432 는 다른 프로젝트가 씁니다.
# - 단말 화면(POS·KDS·DID)은 sun-moon-terminals 의 scripts/deploy.sh 로: SUNMOON_HOST=mac-debian scripts/deploy.sh pos
set -eu

BUILD=${BUILD_HOST:-dell-debian}
RUN=${RUN_HOST:-mac-debian}
svc=${1:-}
case "$svc" in
  bo)            repo=sun-moon-java-platform-bo ;;
  order)         repo=sun-moon-java-platform-order ;;
  app-server)    repo=sun-moon-java-platform-app-server ;;
  device)        repo=sun-moon-device-server ;;
  channel-order) repo=sun-moon-java-platform-channel-order ;;
  *) echo "사용: $0 bo|order|app-server|device|channel-order" >&2; exit 2 ;;
esac

echo "[1/3] $BUILD 에서 빌드"
ssh "$BUILD" "set -e
  exec 9>/tmp/sunmoon-build.lock
  flock -n 9 || { echo '다른 빌드가 돌고 있습니다' >&2; exit 1; }
  cd /srv/sunmoon/src/$repo
  git pull --ff-only -q
  git submodule update --init -q
  echo \"  $svc: \$(git log -1 --format='%h %s' | cut -c1-70)\"
  start=\$(date +%s)
  if ! docker build --network host -t sun-moon-$svc . > /srv/sunmoon/logs/build-$svc.log 2>&1; then
    echo '  빌드 실패 — /srv/sunmoon/logs/build-$svc.log' >&2
    grep -n 'What went wrong\|ERROR' -A3 /srv/sunmoon/logs/build-$svc.log | head -15 >&2
    exit 1
  fi
  echo \"  빌드 \$(( \$(date +%s) - start ))초\"
  docker builder prune -f --filter until=72h >/dev/null 2>&1 || true
  docker image prune -f >/dev/null 2>&1 || true"

echo "[2/3] image 를 $RUN 으로"
ssh "$BUILD" "docker save sun-moon-$svc | gzip -1" | ssh "$RUN" "gunzip | docker load"

echo "[3/3] $RUN 에서 다시 띄우기"
ssh "$RUN" "set -e; /srv/sunmoon/bin/up.sh $svc; docker image prune -f >/dev/null 2>&1 || true"
