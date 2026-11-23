#!/usr/bin/env bash
# Tabnit Base 를 이 서버에 설치해 띄운다: 앱(서버와 화면)과 PostgreSQL, 컨테이너 둘 (E-07, v0).
#
# 이 파일은 배포 폴더에 들어 있다 (만드는 법: 저장소에서 bash tools/package.sh). 배포 폴더를 통째로 서버에 복사한 뒤
# 그 폴더에서 부른다. 인터넷이 없어도 된다: 이미지는 폴더의 images/ 에 들어 있다. Docker 가 있어야 한다.
#
# 쓰는 법 (배포 폴더에서):
#   bash install.sh                       설치하고 띄운다. 다시 불러도 된다 (있는 것은 그대로 쓴다)
#   bash install.sh user 아이디 역할        사용자를 만든다. 역할은 ADMIN 또는 OFFICER
#   bash install.sh unlock 아이디           로그인 막힘을 푼다
#   bash install.sh password 아이디         비밀번호를 바꾼다
#                                         비밀번호는 환경 변수 TABNIT_NEW_USER_PASSWORD 로 넘긴다 (12자 이상)
#                                         명령 줄에 값을 적으면 셸의 기록에 남는다. 남기지 않으려면 먼저
#                                           read -rs TABNIT_NEW_USER_PASSWORD; export TABNIT_NEW_USER_PASSWORD
#                                         로 넣은 뒤 부른다
#   bash install.sh logs                  앱의 로그를 본다
#   bash install.sh down                  내린다. 데이터는 남는다
#
# 설치할 때 정하는 것 (환경 변수로 준다. 처음 설치할 때 .env 에 적히고, 그 뒤로는 다시 주어도 .env 의 값을 쓴다):
#   TABNIT_HOST       사람들이 접속할 이 서버의 이름이나 주소. 자체 서명 인증서에 적힌다. 없으면 이 서버의 이름
#   TABNIT_PORT       여는 포트. 없으면 8443
#   TABNIT_TIME_ZONE  조회 시점이 없을 때의 "오늘"을 정하는 시간대. 없으면 Asia/Seoul
#
# HTTPS: 앱이 직접 HTTPS 로 연다 (D2 AD-363).
#   - 인증서가 있으면 이 폴더의 tls/cert.pem (인증서, 사슬 포함)과 tls/key.pem (개인 키, 암호 없음)으로 둔 뒤 설치한다.
#   - 없으면 이 스크립트가 자체 서명 인증서를 만든다. 브라우저가 경고를 띄운다: 시험과 시연에 쓴다.
#
# 비밀번호: 데이터베이스의 비밀번호는 처음 한 번 무작위로 만들어 이 폴더의 .env 에 둔다 (이 사용자만 읽는다).
# 이 파일을 지우면 데이터베이스에 붙을 수 없다. 데이터는 Docker 의 볼륨(tabnit_db-data)에 있다.
# 새 버전의 배포 폴더로 올릴 때는 앞서 설치한 폴더의 .env 를 (받은 인증서를 썼다면 tls/ 도) 새 폴더로 복사한 뒤 부른다.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
env_file="$here/.env"

fail() {
  echo "[실패] $1" >&2
  exit 1
}

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  fail "Docker 가 없거나 꺼져 있습니다. Docker 를 설치하고 켠 뒤 다시 실행하세요."
fi
if ! docker compose version >/dev/null 2>&1; then
  fail "docker compose 가 없습니다. Docker 의 compose 플러그인을 설치한 뒤 다시 실행하세요."
fi
[ -f "$here/VERSION" ] || fail "이 폴더에 VERSION 파일이 없습니다. 배포 폴더를 통째로 복사했는지 확인하세요."
version="$(tr -d ' \n\r' < "$here/VERSION")"

# 설치 구성의 값은 .env 의 것만 쓴다. 이 셸의 환경 변수에 같은 이름이 있으면 docker compose 가 그것을 먼저 쓰므로
# 지우고 부른다: 처음 설치한 뒤에 다른 값을 주고 다시 불러도, 띄우는 것과 이 스크립트가 확인하는 것이 어긋나지 않는다.
compose() {
  env -u TABNIT_VERSION -u TABNIT_DB_PASSWORD -u TABNIT_TLS_MODE -u TABNIT_TLS_PASSWORD -u TABNIT_APP_ARGS \
    -u TABNIT_HOST -u TABNIT_PORT -u TABNIT_TIME_ZONE \
    docker compose --project-directory "$here" -f "$here/compose.yaml" --env-file "$env_file" "$@"
}

# .env 에서 값 하나를 읽는다. 없으면 빈 값이다.
env_value() {
  [ -f "$env_file" ] || return 0
  sed -n "s/^$1=//p" "$env_file" | head -n 1
}

require_installed() {
  [ -f "$env_file" ] || fail "아직 설치하지 않았습니다. 먼저 bash install.sh 를 실행하세요."
}

random_secret() {
  local value
  if command -v openssl >/dev/null 2>&1; then
    value="$(openssl rand -hex 24)"
  else
    value="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  fi
  [ "${#value}" -ge 32 ] || fail "무작위 값을 만들지 못했습니다. openssl 이 있는지 확인하세요."
  printf '%s' "$value"
}

# 배포 폴더가 이 서버의 CPU 용인지 본다. 다른 CPU 용 이미지는 뜨지 않거나 아주 느리다.
check_architecture() {
  [ -f "$here/ARCH" ] || return 0
  local built host
  built="$(tr -d ' \n\r' < "$here/ARCH")"
  case "$(docker info --format '{{.Architecture}}' 2>/dev/null)" in
    x86_64 | amd64) host="amd64" ;;
    aarch64 | arm64) host="arm64" ;;
    *) host="" ;;
  esac
  if [ -n "$host" ] && [ "$host" != "$built" ]; then
    fail "이 배포 폴더는 $built 용인데 이 서버는 $host 입니다. $host 용 배포 폴더를 받아 다시 실행하세요."
  fi
}

# 옮기다가 파일이 상하지 않았는지 본다 (누가 바꿔치기한 것을 막는 장치는 아니다: 지문도 같은 폴더에 있다).
# packs/ 는 보지 않는다: 설치한 뒤에 Pack 을 더하거나 고치는 곳이다.
check_files() {
  [ -f "$here/SHA256SUMS" ] || return 0
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$here" && sha256sum --quiet -c SHA256SUMS) || fail "파일이 상했습니다 (SHA256SUMS 와 다릅니다). 배포 폴더를 다시 복사하세요."
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$here" && shasum -a 256 -c SHA256SUMS >/dev/null) || fail "파일이 상했습니다 (SHA256SUMS 와 다릅니다). 배포 폴더를 다시 복사하세요."
  else
    echo "   (sha256sum 이 없어 파일이 상했는지 확인하지 못했습니다.)"
  fi
}

load_images() {
  local image
  for image in "$here"/images/*.tar; do
    [ -f "$image" ] || fail "이 폴더의 images/ 에 이미지 파일이 없습니다. 배포 폴더를 통째로 복사했는지 확인하세요."
    docker load --quiet -i "$image" >/dev/null
  done
  docker image inspect "tabnit-base-app:$version" >/dev/null 2>&1 \
    || fail "앱의 이미지(tabnit-base-app:$version)를 읽어 들이지 못했습니다. images/ 의 파일을 확인하세요."
  docker image inspect postgres:18 >/dev/null 2>&1 \
    || fail "PostgreSQL 의 이미지(postgres:18)를 읽어 들이지 못했습니다. images/ 의 파일을 확인하세요."
}

# 처음 한 번 .env 를 만든다. 있으면 그대로 쓴다: 데이터베이스는 처음 만든 비밀번호만 안다.
ensure_env() {
  local tls_mode="self"
  if [ -f "$here/tls/cert.pem" ] && [ -f "$here/tls/key.pem" ]; then
    tls_mode="given"
  elif [ -e "$here/tls/cert.pem" ] || [ -e "$here/tls/key.pem" ]; then
    fail "tls/cert.pem 과 tls/key.pem 가운데 하나만 있습니다. 둘 다 두거나 둘 다 빼고 다시 실행하세요."
  fi
  if [ -f "$env_file" ]; then
    grep -Eq '^TABNIT_DB_PASSWORD=.{32,}$' "$env_file" \
      || fail ".env 에 데이터베이스의 비밀번호가 없거나 잘못 적혀 있습니다. 데이터베이스는 처음 만든 비밀번호만 압니다. .env 를 되돌려 놓으세요."
    # 버전은 이 배포 폴더의 것으로 맞춘다 (새 배포 폴더로 다시 설치하는 경우).
    if [ "$(env_value TABNIT_VERSION)" != "$version" ]; then
      local kept
      kept="$(grep -v '^TABNIT_VERSION=' "$env_file")"
      (umask 077 && printf '%s\nTABNIT_VERSION=%s\n' "$kept" "$version" > "$env_file")
    fi
    return
  fi
  # .env 가 없는데 데이터가 이미 있으면 새로 만들지 않는다: 데이터베이스는 처음 만든 비밀번호만 안다.
  # 새 버전의 배포 폴더에서 처음 부를 때가 이 경우다.
  if docker volume inspect tabnit_db-data >/dev/null 2>&1; then
    fail "이 서버에 앞서 설치한 데이터가 있는데 이 폴더에 .env 가 없습니다. 앞서 설치한 폴더의 .env 를 (받은 인증서를 썼다면 tls/ 도) 이 폴더로 복사한 뒤 다시 실행하세요. 새로 만들면 데이터베이스에 붙을 수 없습니다."
  fi
  local arguments tls_password=""
  if [ "$tls_mode" = "given" ]; then
    arguments="--server.port=8443 --server.ssl.certificate=file:/app/tls/cert.pem --server.ssl.certificate-private-key=file:/app/tls/key.pem"
  else
    arguments="--server.port=8443 --server.ssl.key-store=file:/app/tls/keystore.p12 --server.ssl.key-store-type=PKCS12"
    tls_password="$(random_secret)"
  fi
  (
    umask 077
    {
      printf 'TABNIT_VERSION=%s\n' "$version"
      printf 'TABNIT_DB_PASSWORD=%s\n' "$(random_secret)"
      printf 'TABNIT_TLS_MODE=%s\n' "$tls_mode"
      printf 'TABNIT_TLS_PASSWORD=%s\n' "$tls_password"
      printf 'TABNIT_APP_ARGS=%s\n' "$arguments"
      printf 'TABNIT_HOST=%s\n' "${TABNIT_HOST:-$(hostname)}"
      printf 'TABNIT_PORT=%s\n' "${TABNIT_PORT:-8443}"
      printf 'TABNIT_TIME_ZONE=%s\n' "${TABNIT_TIME_ZONE:-Asia/Seoul}"
    } > "$env_file"
  )
  echo "   비밀번호를 만들어 .env 에 두었습니다. 이 파일을 지우거나 고치지 마세요."
}

# HTTPS 의 인증서를 앱이 읽는 곳(볼륨)에 마련한다.
ensure_certificate() {
  if [ "$(env_value TABNIT_TLS_MODE)" = "given" ]; then
    [ -f "$here/tls/cert.pem" ] && [ -f "$here/tls/key.pem" ] \
      || fail "처음 설치할 때 받은 인증서를 썼는데 지금은 tls/cert.pem 과 tls/key.pem 이 없습니다. 두 파일을 되돌려 놓으세요."
    # 개인 키는 이 폴더에도 남는다 (다시 설치할 때 쓴다). 다른 사람이 읽을 수 있게 두지 않았는지 알린다.
    if [ -n "$(find "$here/tls/key.pem" \( -perm -g+r -o -perm -o+r \) 2>/dev/null)" ]; then
      echo "   (주의) tls/key.pem 을 이 사용자 말고도 읽을 수 있습니다. chmod 600 tls/key.pem 으로 좁히세요."
    fi
    # 앱은 관리자 권한 없이 돌아 이 폴더의 파일을 읽지 못할 수 있다. 볼륨에 옮겨 앱의 사용자만 읽게 한다.
    compose run --rm --no-deps --user 0 --entrypoint sh -v "$here/tls:/in:ro" app -c \
      'cp /in/cert.pem /in/key.pem /app/tls/ && chown 10001 /app/tls/cert.pem /app/tls/key.pem && chmod 600 /app/tls/key.pem' \
      || fail "받은 인증서를 옮기지 못했습니다. tls/cert.pem 과 tls/key.pem 을 읽을 수 있는지 확인하세요."
    echo "   받은 인증서(tls/cert.pem)를 씁니다."
    return
  fi
  if compose run --rm --no-deps --entrypoint sh app -c 'test -s /app/tls/keystore.p12' >/dev/null 2>&1; then
    echo "   앞서 만든 자체 서명 인증서를 씁니다."
    return
  fi
  local host names
  host="$(env_value TABNIT_HOST)"
  names="dns:localhost,ip:127.0.0.1"
  if printf '%s' "$host" | grep -Eq '^[0-9]+(\.[0-9]+){3}$'; then
    names="ip:$host,$names"
  else
    names="dns:$host,$names"
  fi
  compose run --rm --no-deps --entrypoint keytool app -genkeypair -alias tabnit -keyalg RSA -keysize 2048 \
    -validity 825 -storetype PKCS12 -keystore /app/tls/keystore.p12 -storepass:env SERVER_SSL_KEY_STORE_PASSWORD \
    -dname "CN=$host" -ext "SAN=$names" >/dev/null 2>&1 \
    || fail "자체 서명 인증서를 만들지 못했습니다. bash install.sh logs 와 docker 의 상태를 확인하세요."
  echo "   자체 서명 인증서를 만들었습니다 ($host). 브라우저가 경고를 띄웁니다: 시험과 시연에 쓰세요."
  echo "   받은 인증서를 쓰려면 처음 설치하기 전에 tls/cert.pem 과 tls/key.pem 을 두어야 합니다."
}

wait_until_up() {
  local port
  port="$(env_value TABNIT_PORT)"
  if ! command -v curl >/dev/null 2>&1; then
    echo "   (curl 이 없어 떴는지 확인하지 못했습니다. bash install.sh logs 로 확인하세요.)"
    return
  fi
  for _ in $(seq 1 90); do
    # 자체 서명 인증서일 수 있어 인증서는 확인하지 않는다. 이 서버 안에서 떴는지만 본다.
    if curl -kfsS "https://localhost:$port/actuator/health" >/dev/null 2>&1; then
      return
    fi
    sleep 2
  done
  fail "앱이 뜨지 않았습니다. bash install.sh logs 로 원인을 확인하세요."
}

case "${1:-install}" in
  install)
    echo "1) 배포 폴더를 확인합니다 (버전 $version)."
    check_architecture
    check_files
    echo "2) 이미지를 읽어 들입니다."
    load_images
    echo "3) 비밀번호와 HTTPS 의 인증서를 마련합니다."
    ensure_env
    ensure_certificate
    echo "4) 띄웁니다."
    compose up -d
    wait_until_up
    echo "[확인] 떴습니다: https://$(env_value TABNIT_HOST):$(env_value TABNIT_PORT)"
    echo "       처음이면 사용자를 만드세요:"
    echo "         TABNIT_NEW_USER_PASSWORD='(12자 이상)' bash install.sh user admin ADMIN"
    echo "       Pack 은 이 폴더의 packs/ 에 두고, 관리자로 로그인해 이름을 골라 적재합니다."
    ;;
  user)
    [ $# -eq 3 ] || { echo "쓰는 법: TABNIT_NEW_USER_PASSWORD='(12자 이상)' bash install.sh user 아이디 역할(ADMIN 또는 OFFICER)" >&2; exit 2; }
    [ -n "${TABNIT_NEW_USER_PASSWORD:-}" ] || fail "비밀번호가 없습니다. 환경 변수 TABNIT_NEW_USER_PASSWORD 에 넣어 다시 실행하세요. 명령의 인자로는 받지 않습니다."
    require_installed
    # 비밀번호는 이 셸의 환경 변수를 컨테이너에 그대로 넘긴다. 이 스크립트가 부르는 명령이나 파일에는 값이 남지 않는다
    # (부른 사람이 명령 줄에 값을 적었다면 그 셸의 기록에는 남는다: 맨 위의 쓰는 법).
    compose run --rm -e TABNIT_NEW_USER_PASSWORD app "--tabnit.create-user.login-id=$2" "--tabnit.create-user.role=$3"
    ;;
  unlock)
    [ $# -eq 2 ] || { echo "쓰는 법: bash install.sh unlock 아이디" >&2; exit 2; }
    require_installed
    compose run --rm app "--tabnit.unlock-user.login-id=$2"
    ;;
  password)
    [ $# -eq 2 ] || { echo "쓰는 법: TABNIT_NEW_USER_PASSWORD='(12자 이상)' bash install.sh password 아이디" >&2; exit 2; }
    [ -n "${TABNIT_NEW_USER_PASSWORD:-}" ] || fail "새 비밀번호가 없습니다. 환경 변수 TABNIT_NEW_USER_PASSWORD 에 넣어 다시 실행하세요. 명령의 인자로는 받지 않습니다."
    require_installed
    compose run --rm -e TABNIT_NEW_USER_PASSWORD app "--tabnit.change-password.login-id=$2"
    ;;
  logs)
    require_installed
    compose logs --tail 200 app
    ;;
  down)
    require_installed
    compose down
    echo "[확인] 내렸습니다. 데이터는 남아 있습니다. 다시 띄우려면 bash install.sh 를 실행하세요."
    ;;
  *)
    echo "모르는 명령입니다: $1. 쓰는 법은 이 파일의 맨 위에 있습니다." >&2
    exit 2
    ;;
esac
