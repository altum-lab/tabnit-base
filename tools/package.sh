#!/usr/bin/env bash
# 배포 폴더를 만든다: 컨테이너 이미지 둘(앱, PostgreSQL)과 설치 스크립트를 묶어, 다른 PC 나 서버에 복사해 설치할 수
# 있게 한다 (E-07, D2 AD-363). 받는 쪽은 인터넷이 없어도 된다.
#
# 쓰는 법 (저장소의 맨 위에서):
#   bash tools/package.sh                 amd64 와 arm64 용을 둘 다 만든다
#   bash tools/package.sh amd64           인텔·AMD 서버용만
#   bash tools/package.sh arm64           애플 실리콘 맥과 ARM 서버용만
#
# 만들어지는 것: dist/tabnit-base-<버전>-linux-<CPU>/ (git 에 올리지 않는다)
#   images/tabnit-base-app.tar, images/postgres.tar   이미지 둘
#   compose.yaml, install.sh                          설치 구성과 스크립트 (deploy/install 의 것)
#   packs/                                            저장소의 Pack 들 (가상 데이터)
#   VERSION, ARCH, SHA256SUMS
#
# 설치하는 법: 그 폴더를 통째로 서버에 복사한 뒤, 그 폴더에서 bash install.sh
#
# 버전은 서버의 빌드 파일에 적힌 버전에 git 의 커밋을 붙인 것이다. 커밋하지 않은 변경이 있으면 -dirty 가 붙는다.
# Docker 가 켜져 있어야 한다. 다른 CPU 용 이미지는 실행하는 부분만 흉내 내어 만든다 (빌드는 이 PC 의 CPU 로 돈다).
set -euo pipefail

top="$(cd "$(dirname "$0")/.." && pwd)"

fail() {
  echo "[실패] $1" >&2
  exit 1
}

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  fail "Docker 가 없거나 꺼져 있습니다. Docker Desktop 을 켠 뒤 다시 실행하세요."
fi
docker buildx version >/dev/null 2>&1 || fail "docker buildx 가 없습니다. 다른 CPU 용 이미지를 만드는 데 씁니다."

if [ $# -eq 0 ]; then
  set -- amd64 arm64
fi
for arch in "$@"; do
  case "$arch" in
    amd64 | arm64) ;;
    *) fail "모르는 CPU 입니다: $arch. amd64 또는 arm64 를 주세요." ;;
  esac
done

base="$(sed -n 's/^version = "\(.*\)"$/\1/p' "$top/server/api/build.gradle.kts" | head -n 1)"
[ -n "$base" ] || fail "서버의 빌드 파일(server/api/build.gradle.kts)에서 버전을 읽지 못했습니다."
commit="$(git -C "$top" rev-parse --short HEAD)"
version="$base-$commit"
if [ -n "$(git -C "$top" status --porcelain)" ]; then
  version="$version-dirty"
  echo "(커밋하지 않은 변경이 있습니다. 버전에 -dirty 를 붙입니다.)"
fi

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$@"
  else
    shasum -a 256 "$@"
  fi
}

# 읽어 들인 이미지가 그 CPU 의 것인지 본다. 다른 CPU 의 것이 담기면 받는 쪽에서 뜨지 않는다.
require_architecture() {
  local found
  found="$(docker image inspect --format '{{.Architecture}}' "$1")"
  [ "$found" = "$2" ] || fail "$1 이 $2 용이 아닙니다 ($found). Docker 의 설정에서 다른 CPU 의 이미지를 다룰 수 있는지 확인하세요."
}

for arch in "$@"; do
  out="$top/dist/tabnit-base-$version-linux-$arch"
  echo "== $arch: $out"
  rm -rf "$out"
  mkdir -p "$out/images"

  echo "1) 앱의 이미지를 만듭니다."
  docker buildx build --platform "linux/$arch" -f "$top/deploy/Dockerfile" -t "tabnit-base-app:$version" --load "$top"
  require_architecture "tabnit-base-app:$version" "$arch"
  docker save -o "$out/images/tabnit-base-app.tar" "tabnit-base-app:$version"

  echo "2) PostgreSQL 의 이미지를 받습니다."
  docker pull --quiet --platform "linux/$arch" postgres:18 >/dev/null
  require_architecture postgres:18 "$arch"
  docker save -o "$out/images/postgres.tar" postgres:18

  echo "3) 설치 구성과 Pack 을 담습니다."
  cp "$top/deploy/install/compose.yaml" "$top/deploy/install/install.sh" "$out/"
  chmod +x "$out/install.sh"
  cp -R "$top/packs" "$out/packs"
  # 지침 파일은 설치하는 쪽에 필요 없다.
  find "$out/packs" -name 'CLAUDE.md' -delete
  printf '%s\n' "$version" > "$out/VERSION"
  printf '%s\n' "$arch" > "$out/ARCH"
  (cd "$out" && find . -type f ! -name SHA256SUMS | LC_ALL=C sort | while read -r file; do checksum "$file"; done > SHA256SUMS)
  echo "[확인] $out ($(du -sh "$out" | cut -f1))"
done

# 다른 CPU 의 PostgreSQL 이미지를 받으면 이 PC 의 postgres:18 이 그것으로 바뀐다. 서버 시험이 같은 이름을 쓰므로
# 이 PC 의 CPU 의 것으로 되돌려 놓는다.
docker pull --quiet postgres:18 >/dev/null

echo "설치하는 법: 폴더를 통째로 서버에 복사한 뒤 그 폴더에서  bash install.sh"
