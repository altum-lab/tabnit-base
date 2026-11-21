#!/usr/bin/env bash
# 개발 환경을 한 번에 띄운다: 앱(서버와 화면)과 PostgreSQL 을 컨테이너로 (SK-08).
#
# 쓰는 법 (저장소의 맨 위에서):
#   bash tools/up.sh                  이미지를 만들고 띄운다. 뜰 때까지 기다린다
#   bash tools/up.sh user 아이디 역할   사용자를 만든다. 역할은 ADMIN 또는 OFFICER.
#                                     비밀번호는 환경 변수 TABNIT_NEW_USER_PASSWORD 로 넘긴다 (12자 이상)
#   bash tools/up.sh logs             앱의 로그를 본다
#   bash tools/up.sh down             내린다. 데이터는 남는다
#   bash tools/up.sh destroy          내리고 데이터(데이터베이스의 볼륨)도 지운다
#
# 주소는 http://localhost:8088 이다. 포트를 바꾸려면 TABNIT_PORT 를 준다:  TABNIT_PORT=9000 bash tools/up.sh
# 이 PC 에서만 열린다. 다른 PC 나 서버에 설치하는 것은 이 명령의 일이 아니다 (E-07, E-09).
set -euo pipefail

top="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$top/deploy/.env"
port="${TABNIT_PORT:-8088}"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "[실패] Docker 가 없거나 꺼져 있습니다. Docker Desktop 을 켠 뒤 다시 실행하세요. (bash tools/doctor.sh 로 확인)" >&2
  exit 1
fi

compose() {
  docker compose -f "$top/deploy/compose.yaml" --env-file "$env_file" "$@"
}

# 데이터베이스의 비밀번호는 처음 한 번 만들어 deploy/.env 에 둔다. git 에 올라가지 않는 파일이다 (D2 AD-278).
ensure_env() {
  if [ -f "$env_file" ]; then
    if ! grep -Eq '^TABNIT_DB_PASSWORD=.{32,}$' "$env_file"; then
      echo "[실패] deploy/.env 에 데이터베이스의 비밀번호가 없거나 잘못 적혀 있습니다." >&2
      echo "       데이터베이스는 처음 만든 비밀번호만 압니다. 그 값을 모르면 처음부터 다시 해야 합니다:" >&2
      echo "         docker compose -p tabnit-dev down --volumes   (이 개발 환경의 데이터를 지웁니다)" >&2
      echo "         rm deploy/.env && bash tools/up.sh" >&2
      exit 1
    fi
    return
  fi
  local password
  if command -v openssl >/dev/null 2>&1; then
    password="$(openssl rand -hex 24)"
  else
    password="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  fi
  if [ "${#password}" -lt 32 ]; then
    echo "[실패] 데이터베이스의 비밀번호를 만들지 못했습니다. openssl 이 있는지 확인하세요." >&2
    exit 1
  fi
  (umask 077 && printf 'TABNIT_DB_PASSWORD=%s\n' "$password" > "$env_file")
  echo "데이터베이스의 비밀번호를 만들어 deploy/.env 에 두었습니다. 이 파일은 git 에 올리지 않습니다."
}

case "${1:-up}" in
  up)
    ensure_env
    echo "1) 이미지를 만들고 띄웁니다. 처음에는 몇 분 걸립니다."
    TABNIT_PORT="$port" compose up -d --build
    echo "2) 앱이 뜰 때까지 기다립니다."
    for _ in $(seq 1 90); do
      if curl -fsS "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
        echo "[확인] 떴습니다: http://localhost:$port"
        echo "       처음이면 사용자를 만드세요:"
        echo "         TABNIT_NEW_USER_PASSWORD='(12자 이상)' bash tools/up.sh user admin ADMIN"
        echo "       Pack 은 관리자로 로그인해 이름을 골라 적재합니다 (POST /api/v1/pack-loads). 저장소의 packs/ 가 붙어 있습니다."
        exit 0
      fi
      sleep 2
    done
    echo "[실패] 앱이 뜨지 않았습니다. bash tools/up.sh logs 로 원인을 확인하세요." >&2
    exit 1
    ;;
  user)
    if [ $# -ne 3 ]; then
      echo "쓰는 법: TABNIT_NEW_USER_PASSWORD='(12자 이상)' bash tools/up.sh user 아이디 역할(ADMIN 또는 OFFICER)" >&2
      exit 2
    fi
    if [ -z "${TABNIT_NEW_USER_PASSWORD:-}" ]; then
      echo "[실패] 비밀번호가 없습니다. 환경 변수 TABNIT_NEW_USER_PASSWORD 에 넣어 다시 실행하세요. 명령의 인자로는 받지 않습니다." >&2
      exit 2
    fi
    ensure_env
    # 비밀번호는 이 셸의 환경 변수를 컨테이너에 그대로 넘긴다. 값이 명령이나 파일에 남지 않는다.
    compose run --rm -e TABNIT_NEW_USER_PASSWORD app \
      "--tabnit.create-user.login-id=$2" "--tabnit.create-user.role=$3"
    ;;
  logs)
    ensure_env
    compose logs --tail 200 app
    ;;
  down)
    ensure_env
    compose down
    echo "[확인] 내렸습니다. 데이터는 남아 있습니다."
    ;;
  destroy)
    ensure_env
    compose down --volumes
    echo "[확인] 내리고 데이터를 지웠습니다. 데이터베이스의 비밀번호(deploy/.env)는 남아 있습니다."
    ;;
  *)
    echo "모르는 명령입니다: $1. 쓰는 법은 이 파일의 맨 위에 있습니다." >&2
    exit 2
    ;;
esac
