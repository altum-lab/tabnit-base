#!/usr/bin/env bash
# 배포물에 든 구성요소의 이름, 버전, 라이선스 목록을 만든다 (E-11, D2 AD-364).
#
# 쓰는 법 (저장소의 맨 위에서):
#   bash tools/components.sh             dist/components/ 에 낸다
#   bash tools/components.sh 낼_폴더     그 폴더에 낸다 (tools/package.sh 가 배포 폴더에 담을 때 쓴다)
#
# 내는 것: COMPONENTS.md (사람이 읽는 표. 확인이 필요한 것이 맨 위에 있다), components.csv, bom.json (표준 형식 CycloneDX).
# 서버는 실제로 배포되는 서버 파일 안의 라이브러리를 담고, 라이선스는 빌드 도구(Gradle 의 CycloneDX 플러그인)가 읽는다.
# 화면의 꾸러미는 설치 기록에서 읽는다. 컨테이너의 바탕 이미지와, 서버 파일에는 들었는데 빌드 도구의 명세에 없는 것은
# deploy/base-components.json 에 적어 둔 것을 쓴다: 바탕 이미지를 바꾸면 그 파일도 고친다 (다르면 알리며 멈춘다).
# Docker 는 필요 없다. 처음에는 빌드 도구가 플러그인을 내려받는다.
set -euo pipefail

top="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$top/dist/components}"

command -v python3 >/dev/null 2>&1 || { echo "[실패] python3 가 없습니다. 목록을 만드는 데 씁니다." >&2; exit 1; }

version="$(sed -n 's/^version = "\(.*\)"$/\1/p' "$top/server/api/build.gradle.kts" | head -n 1)"
group="$(sed -n 's/^group = "\(.*\)"$/\1/p' "$top/server/api/build.gradle.kts" | head -n 1)"
[ -n "$version" ] && [ -n "$group" ] || { echo "[실패] 서버의 빌드 파일에서 버전과 묶음 이름을 읽지 못했습니다." >&2; exit 1; }

echo "1) 서버 파일을 만들고 그 안에 든 것을 읽습니다."
(cd "$top/server" && ./gradlew :api:bootJar :api:cyclonedxDirectBom --console=plain --quiet)
jar="$(ls "$top"/server/api/build/libs/*.jar | grep -v -- '-plain\.jar$' | head -n 1)"
[ -n "$jar" ] || { echo "[실패] 서버 파일을 찾지 못했습니다 (server/api/build/libs)." >&2; exit 1; }
echo "2) 화면과 바탕 이미지를 더해 목록을 만듭니다."
python3 "$top/tools/components.py" "$top" "$top/server/api/build/components/server-bom.json" "$jar" "$version" "$group" "$out"
