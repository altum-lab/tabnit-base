"""배포물에 든 구성요소의 목록을 만든다 (E-11, D2 AD-364). bash tools/components.sh 가 부른다.

읽는 것:
  - 서버: 실제로 배포되는 서버 파일 안의 라이브러리. 라이선스는 빌드 도구가 낸 표준 형식(CycloneDX)의 명세에서 읽는다.
    명세에는 있는데 서버 파일에 들지 않는 것(빌드에서만 쓰이는 것)은 담지 않는다. 서버 파일에는 들었는데 명세에
    없는 것은 deploy/base-components.json 의 extra 에서 읽고, 거기에도 없으면 확인이 필요하다고 적는다.
  - 화면: 설치 기록(web/package-lock.json). 개발에만 쓰는 꾸러미는 뺀다.
  - 컨테이너의 바탕 이미지: deploy/base-components.json. 우리가 고른 것만 적는다 (그 안의 운영체제 꾸러미는 적지 않는다).

내는 것 (모두 같은 입력이면 글자까지 같다. 돌린 시각을 넣지 않는다):
  - COMPONENTS.md   사람이 읽는 표. 확인이 필요한 것을 맨 위에 모은다
  - components.csv  같은 내용의 표
  - bom.json        표준 형식(CycloneDX)의 명세. 서버의 것에 화면과 바탕 이미지를 더한 것이다

확인이 필요한 것: 라이선스를 읽지 못한 것과, 흔한 허용형이 아닌 것. 어느 라이선스를 받아들일지는 여기서 정하지 않는다:
deploy/license-decisions.json 에 사람이 적은 판단을 읽어 함께 적는다 (E-21, D2 AD-428). 판단이 없으면 미정이다.
배포를 제약하는 라이선스의 실행용 의존이 서버나 화면에 있으면 실패로 끝난다.
"""

import csv
import io
import json
import os
import re
import sys
import zipfile
from collections import namedtuple

Row = namedtuple("Row", "kind name version license review source")

# 흔한 허용형 라이선스. 이 밖의 것은 확인이 필요하다고 표시한다 (받아들일 수 없다는 뜻이 아니다).
PERMISSIVE = frozenset({
    "0BSD", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "BlueOak-1.0.0", "CC0-1.0", "ISC", "MIT", "MIT-0",
    "PostgreSQL", "Python-2.0", "Unlicense", "Zlib",
})

OWN_LICENSE = "제품 자신"

# 목록에서의 차례.
KINDS = ("바탕 이미지", "서버", "제품", "화면")


def _fail(message):
    raise SystemExit("[실패] " + message)


def _license_ids(component):
    """명세의 구성요소에서 라이선스의 이름들을 읽는다. 식별자가 있으면 식별자를, 없으면 적힌 이름을 쓴다."""
    found = []
    for choice in component.get("licenses", []):
        if "license" in choice:
            one = choice["license"].get("id") or choice["license"].get("name")
            if one:
                found.append(one.strip())
        elif "expression" in choice:
            found.append(choice["expression"].strip())
    return found


def _review(licenses):
    """확인이 필요한 까닭. 필요 없으면 빈 글자다."""
    if not licenses:
        return "라이선스를 읽지 못했다"
    unusual = [one for one in licenses if one not in PERMISSIVE]
    if not unusual:
        return ""
    if len(licenses) > 1:
        return "여러 라이선스가 적혀 있고 흔한 허용형이 아닌 것이 있다: " + ", ".join(unusual)
    return "흔한 허용형이 아니다: " + unusual[0]


def _file_name(component):
    """서버 파일 안에서의 라이브러리의 이름 (이름-버전). 끝에 꼬리표가 더 붙을 수 있다 (이름-버전-꼬리표)."""
    return f"{component['name']}-{component.get('version', '')}"


def _shipped_components(bom, shipped):
    """명세의 구성요소 가운데 실제로 서버 파일에 든 것. 서버 파일 안의 이름마다 그 구성요소를 돌려준다.

    이름과 버전이 꼭 같은 것을 먼저 맞추고, 남은 것은 꼬리표가 붙은 것(이름-버전-꼬리표)으로 맞춘다.
    여럿이 맞으면 이름이 더 긴 쪽이다.
    """
    by_key = {}
    for component in bom.get("components", []):
        by_key.setdefault(_file_name(component), component)
    matched = {}
    for file_name in sorted(shipped):
        if file_name in by_key:
            matched[file_name] = by_key[file_name]
            continue
        candidates = [key for key in by_key if file_name.startswith(key + "-")]
        if candidates:
            matched[file_name] = by_key[max(candidates, key=len)]
    return matched


def _server_rows(bom, own_group, shipped, extra, own_modules=None):
    own_modules = own_modules or {}
    rows = []
    # 명세에는 빌드에서만 쓰이는 것(묶음 표시용, 개발용)도 있다. 실제로 서버 파일에 든 것만 담는다.
    matched = None if shipped is None else _shipped_components(bom, shipped)
    kept = bom.get("components", []) if matched is None else list({id(one): one for one in matched.values()}.values())
    for component in kept:
        group = component.get("group") or ""
        name = f"{group}:{component['name']}" if group else component["name"]
        version = component.get("version", "")
        if own_group and group == own_group:
            rows.append(Row("제품", name, version, OWN_LICENSE, "", "서버의 명세"))
            continue
        licenses = _license_ids(component)
        rows.append(Row("서버", name, version, " / ".join(licenses), _review(licenses), "서버의 명세"))
    # 서버 파일에는 들었는데 명세에 없는 것: 빌드 도구가 서버 파일을 만들 때 넣는 것이다.
    # 따로 적어 둔 것(deploy/base-components.json 의 extra)에서 읽고, 거기에도 없으면 숨기지 않고 적는다.
    for file_name in sorted(set(shipped or ()) - set(matched or ())):
        known = next((one for one in extra if file_name.startswith(one["file"] + "-")), None)
        # 제품 자신의 모듈인데 빌드 도구의 명세에 빠진 것. 모듈의 이름과 버전이 꼭 같은 것만이다:
        # 남의 라이브러리의 이름이 모듈의 이름으로 시작할 수 있다 (core-3.5.3).
        own = next((name for name, module_version in sorted(dict(own_modules).items())
                    if file_name == f"{name}-{module_version}"), None)
        if own:
            rows.append(Row("제품", f"{own_group}:{own}" if own_group else own, dict(own_modules)[own], OWN_LICENSE, "",
                            "서버 파일 (제품의 모듈)"))
        elif known:
            version = file_name[len(known["file"]) + 1:]
            rows.append(Row("서버", known["name"], version, known["license"], _review([known["license"]]),
                            "따로 적어 둔 것: " + known["why"]))
        else:
            # 이름과 버전은 "-숫자"가 처음 나오는 곳에서 나눈다. 그런 곳이 없으면 버전을 모른다.
            split = re.match(r"^(.*?)-(\d.*)$", file_name)
            name, version = (split.group(1), split.group(2)) if split else (file_name, "")
            rows.append(Row("서버", name, version, "",
                            "서버 파일에는 들었는데 명세에 없다. 라이선스를 읽지 못했다", "서버 파일"))
    return rows


def _web_rows(lock):
    seen = {}
    for path, entry in lock.get("packages", {}).items():
        # 맨 위(화면 자신)와 개발에만 쓰는 것은 배포물에 들어가지 않는다.
        if not path or entry.get("dev"):
            continue
        name = path.rsplit("node_modules/", 1)[-1]
        version = entry.get("version", "")
        license_ = entry.get("license")
        licenses = [license_.strip()] if isinstance(license_, str) and license_.strip() else []
        # 안쪽에 겹쳐 깔린 같은 꾸러미는 버전마다 한 번만 적는다.
        seen[(name, version)] = Row("화면", name, version, " / ".join(licenses), _review(licenses), "화면의 설치 기록")
    return list(seen.values())


def _base_rows(base):
    rows = []
    for image in base:
        for component in image.get("components", []):
            license_ = component["license"]
            review = "" if license_ in PERMISSIVE else "흔한 허용형이 아니다: " + license_
            rows.append(Row("바탕 이미지", component["name"], component["version"], license_, review, image["image"]))
    return rows


def collect(bom, lock, base, own_group, shipped=None, extra=(), own_modules=None):
    """구성요소를 모아 정해진 차례로 돌려준다: 구분, 이름, 버전 (글자 코드 순).

    shipped: 서버 파일 안의 라이브러리의 이름들 (이름-버전). 없으면 명세의 것을 모두 담는다.
    extra: 서버 파일에는 들었는데 명세에 없는 것의 이름과 라이선스.
    own_modules: 제품 자신의 모듈의 이름마다 그 버전. 명세에 빠졌어도 그 이름과 버전의 파일이 서버 파일에 있으면 제품으로 담는다.
    """
    shipped = None if shipped is None else set(shipped)
    rows = _base_rows(base) + _server_rows(bom, own_group, shipped, list(extra), dict(own_modules or {})) + _web_rows(lock)
    return sorted(rows, key=lambda row: (KINDS.index(row.kind), row.name, row.version))


# ── 받아들이는지의 판단과 배포를 제약하는 라이선스 (E-21, D2 AD-428) ─────────────────────────

# 배포를 제약하는 라이선스. 이런 것이 서버나 화면의 실행용 의존에 들어오면 목록을 만드는 명령이 실패로 알린다.
# 강한 카피레프트(예외 없는 GPL, AGPL), 소스 공개를 서비스에까지 요구하는 것(SSPL), 쓰임을 막는 것(BUSL, Commons Clause),
# CC 의 비상업과 변경 금지. LGPL, EPL, MPL 과 예외가 붙은 GPL 은 여기 들지 않는다: 확인이 필요한 것으로만 표시한다.
_RESTRICTING = (
    (re.compile(r"(?<![A-Za-z])AGPL|affero", re.I), "AGPL"),
    (re.compile(r"(?<![A-Za-z])SSPL|server side public license", re.I), "SSPL"),
    (re.compile(r"(?<![A-Za-z])BUSL|business source license", re.I), "BUSL"),
    (re.compile(r"commons[- ]clause", re.I), "Commons Clause"),
    (re.compile(r"^CC-BY(-[A-Z]+)*-(NC|ND)\b", re.I), "CC 의 비상업 또는 변경 금지"),
)

# GPL: 식별자(GPL-3.0-only, GPLv3)와 이름(GNU General Public License, GNU GPL). 앞에 글자가 붙은 것(LGPL)과
# "Lesser", "Library" 가 낀 이름은 맞지 않는다. 예외가 붙은 것은 제약으로 보지 않는다.
_GPL = re.compile(r"(?<![A-Za-z])GPL|GNU General Public", re.I)
_GPL_EXCEPTION = re.compile(r"exception|classpath|(?<![A-Za-z])CPE(?![A-Za-z])|-with-", re.I)


def _restricting(license_):
    """그 라이선스가 배포를 제약하는 것이면 어느 갈래인지, 아니면 빈 글자. 식별자와 이름을 모두 알아본다."""
    text = license_.strip()
    found = next((label for pattern, label in _RESTRICTING if pattern.search(text)), "")
    if found:
        return found
    if _GPL.search(text) and not _GPL_EXCEPTION.search(text):
        return "예외 없는 GPL"
    return ""


class _Unreadable(Exception):
    """라이선스의 식을 읽지 못했다."""


def _expression_restricts(text):
    """화면의 꾸러미의 라이선스 식(SPDX)이 배포를 제약하는가.

    "가운데 하나"(OR)는 제약하지 않는 갈래가 하나라도 있으면 고를 수 있고, "모두"(AND)는 하나라도 제약하면 제약한다.
    괄호를 지키고, AND 가 OR 보다 먼저 묶인다. "X WITH 예외"는 예외가 붙은 하나의 라이선스다.
    읽지 못하는 식은 넘기지 않는다: 제약하는 이름이 들어 있으면 제약하는 것으로 본다.
    """
    tokens = re.findall(r"\(|\)|[^\s()]+", text)
    position = 0

    def peek():
        return tokens[position] if position < len(tokens) else None

    def take():
        nonlocal position
        position += 1
        return tokens[position - 1]

    def unit():
        if peek() is None:
            raise _Unreadable()
        if peek() == "(":
            take()
            value = either()
            if peek() != ")":
                raise _Unreadable()
            take()
            return value
        name = take()
        if name in (")", "AND", "OR", "WITH"):
            raise _Unreadable()
        if peek() == "WITH":
            take()
            if peek() in (None, "(", ")", "AND", "OR", "WITH"):
                raise _Unreadable()
            name += " WITH " + take()
            # 예외가 붙은 것은 제약으로 보지 않는다 (예외의 이름이 무엇이든).
            return bool(_restricting(name.split(" WITH ")[0])) and not _GPL.search(name.split(" WITH ")[0])
        return bool(_restricting(name))

    def every():
        value = unit()
        while peek() == "AND":
            take()
            value = unit() or value
        return value

    def either():
        value = every()
        while peek() == "OR":
            take()
            value = every() and value
        return value

    try:
        result = either()
        if position != len(tokens):
            raise _Unreadable()
        return result
    except _Unreadable:
        return any(_restricting(token) for token in tokens if token not in ("(", ")", "AND", "OR", "WITH"))


def decisions_of(data):
    """deploy/license-decisions.json 의 내용을 확인해 판단의 목록을 돌려준다."""
    where = "deploy/license-decisions.json"
    if not isinstance(data, dict) or not isinstance(data.get("decisions"), list):
        _fail(f"{where} 의 맨 위에 decisions 목록이 없습니다. 파일을 고쳐 다시 실행하세요.")
    for one in data["decisions"]:
        for key in ("kind", "name", "license", "accepted", "why", "decided"):
            if not isinstance(one, dict) or key not in one:
                _fail(f"{where} 의 decisions 의 항목에 {key} 가 없습니다. 파일을 고쳐 다시 실행하세요.")
        if not isinstance(one["accepted"], bool):
            _fail(f"{where} 의 {one['name']} 의 accepted 는 true 나 false 여야 합니다.")
    return list(data["decisions"])


def _decision(row, decisions):
    """그 구성요소의 판단. 구분, 이름, 라이선스가 모두 같아야 한다: 라이선스가 달라지면 옛 판단을 쓰지 않는다. 버전은 보지 않는다."""
    return next((one for one in decisions
                 if (one["kind"], one["name"], one["license"]) == (row.kind, row.name, row.license)), None)


def undecided(rows, decisions):
    """확인이 필요한데 판단이 없는 것."""
    return [row for row in rows if row.review and _decision(row, decisions) is None]


def unused(rows, decisions):
    """어느 구성요소에도 쓰이지 않은 판단. 구성요소가 빠졌거나 라이선스가 달라졌다."""
    used = {id(one) for one in (_decision(row, decisions) for row in rows if row.review) if one is not None}
    return [one for one in decisions if id(one) not in used]


def restricted(rows, decisions):
    """배포를 제약하는 라이선스의 실행용 의존. (구성요소, 까닭) 의 목록이다. 바탕 이미지와 제품 자신은 보지 않는다."""
    found = []
    for row in rows:
        if row.kind not in ("서버", "화면") or not row.license:
            continue
        decision = _decision(row, decisions)
        chosen = (decision or {}).get("chosen") or ""
        if row.kind == "화면":
            # 화면의 꾸러미는 식이 "가운데 하나"(OR)인지 "모두"(AND)인지를 적는다. 괄호를 지켜 읽는다.
            if _expression_restricts(row.license):
                found.append((row, "배포를 제약하는 라이선스다: " + row.license))
            continue
        listed = [one.strip() for one in row.license.split(" / ")]
        if len(listed) == 1 and re.search(r"\(|\s(OR|AND|WITH)\s", listed[0]):
            # 명세가 라이선스를 식 하나로 적었다: 화면의 꾸러미와 같이 식으로 읽는다.
            if _expression_restricts(listed[0]):
                found.append((row, "배포를 제약하는 라이선스다: " + listed[0]))
            continue
        hits = [one for one in listed if _restricting(one)]
        if not hits:
            continue
        if len(listed) == 1 or len(hits) == len(listed):
            found.append((row, "배포를 제약하는 라이선스다: " + ", ".join(hits)))
        elif chosen not in listed or _restricting(chosen):
            # 서버의 명세는 여러 라이선스가 "가운데 하나"인지 "모두"인지 알려 주지 않는다.
            found.append((row, "배포를 제약하는 라이선스가 섞여 있다: " + ", ".join(hits)
                          + ". 가운데 하나를 고를 수 있는 것이면 판단의 표에 고른 라이선스(chosen)를 적는다"))
    return found


def _judgement(row, decisions):
    """확인이 필요한 것의 표에 적을 판단의 글."""
    decision = _decision(row, decisions)
    if decision is None:
        return "미정", ""
    verdict = "받아들인다" if decision["accepted"] else "받아들이지 않는다"
    if decision.get("chosen"):
        verdict += f" ({decision['chosen']} 을 고른다)"
    return verdict, f"{decision['why']} ({decision['decided']})"


def described(data):
    """deploy/base-components.json 의 내용을 확인해 바탕 이미지의 표와 따로 적어 둔 것을 돌려준다."""
    where = "deploy/base-components.json"

    def need(item, key, what):
        if not isinstance(item, dict) or key not in item:
            _fail(f"{where} 의 {what} 에 {key} 가 없습니다. 파일을 고쳐 다시 실행하세요.")
        return item[key]

    images = need(data, "images", "맨 위")
    for image in images:
        name = need(image, "image", "images 의 항목")
        need(image, "where", f"images 의 {name}")
        for component in need(image, "components", f"images 의 {name}"):
            for key in ("name", "version", "license"):
                need(component, key, f"{name} 의 components 의 항목")
    extra = data.get("extra", [])
    for one in extra:
        for key in ("file", "name", "license", "why"):
            need(one, key, "extra 의 항목")
    return images, extra


def require_base_matches(base, top):
    """바탕 이미지의 표가 실제로 쓰는 이미지와 같은지 본다. 다르면 목록이 틀린 것이다."""
    with open(os.path.join(top, "deploy", "Dockerfile"), encoding="utf-8") as dockerfile:
        stages = re.findall(r"^FROM\s+(?:--platform=\S+\s+)?(\S+)", dockerfile.read(), re.M)
    with open(os.path.join(top, "deploy", "install", "compose.yaml"), encoding="utf-8") as compose:
        images = re.findall(r"^\s*image:\s*(\S+)", compose.read(), re.M)
    # 실행하는 이미지는 Dockerfile 의 마지막 단계다. 설치 구성에서는 우리 제품의 이미지가 아닌 것이 바탕이다.
    used = set(stages[-1:]) | {image for image in images if "${" not in image}
    listed = {image["image"] for image in base}
    if used != listed:
        _fail("바탕 이미지의 표(deploy/base-components.json)가 실제로 쓰는 이미지와 다릅니다. 쓰는 것: "
              + ", ".join(sorted(used)) + ". 표에 있는 것: " + ", ".join(sorted(listed))
              + ". deploy/Dockerfile 이나 deploy/install/compose.yaml 을 바꿨으면 표도 함께 고치세요.")


def _cell(text):
    # 표의 칸을 나누는 글자와 줄바꿈은 표를 깬다.
    return str(text).replace("|", "/").replace("\n", " ").strip()


def markdown(rows, version, decisions=None):
    lines = ["# Tabnit Base 에 든 구성요소", "",
             f"제품의 버전: {version}", "",
             "배포물(컨테이너 이미지 둘)에 든 것의 이름, 버전, 라이선스다. `bash tools/components.sh` 가 만든다. 손으로 고치지 않는다.",
             "",
             "- 서버: 실제로 배포되는 서버 파일 안의 라이브러리. 라이선스는 빌드 도구가 낸 표준 형식(CycloneDX)의 명세에서 읽었다.",
             "- 화면: 실행할 때 쓰이는 꾸러미. 화면의 설치 기록에서 읽었다. 개발에만 쓰는 것은 들어 있지 않다.",
             "- 바탕 이미지: 우리가 고른 것만 적었다. **그 안의 운영체제 꾸러미는 하나하나 적지 않았다.**",
             "- 제품: 이 제품 자신의 모듈이다.",
             "",
             "같은 내용이 `components.csv` 에, 표준 형식으로는 `bom.json` 에 있다.", "",
             "## 확인이 필요한 것", ""]
    review = [row for row in rows if row.review]
    if review:
        lines += ["라이선스를 읽지 못했거나 흔한 허용형(MIT, Apache-2.0, BSD, ISC 등)이 아닌 것이다. "
                  "받아들일 수 없다는 뜻이 아니다: 사람이 보고 정한다.", ""]
        if decisions is None:
            lines += ["| 구분 | 이름 | 버전 | 라이선스 | 까닭 |", "|---|---|---|---|---|"]
            lines += [f"| {_cell(row.kind)} | {_cell(row.name)} | {_cell(row.version)} | {_cell(row.license) or '(없음)'} | {_cell(row.review)} |"
                      for row in review]
        else:
            lines += ["받아들이는지는 `deploy/license-decisions.json` 에 적은 것이다 (구분, 이름, 라이선스가 같은 것에 쓴다). "
                      "**법률 검토가 아니다**: 제출 전에 라이선스를 아는 사람이 확인한다.", "",
                      "| 구분 | 이름 | 버전 | 라이선스 | 까닭 | 받아들이는가 | 판단의 까닭 |", "|---|---|---|---|---|---|---|"]
            for row in review:
                verdict, why = _judgement(row, decisions)
                lines.append(f"| {_cell(row.kind)} | {_cell(row.name)} | {_cell(row.version)} | {_cell(row.license) or '(없음)'} "
                             f"| {_cell(row.review)} | {_cell(verdict)} | {_cell(why)} |")
    else:
        lines.append("확인이 필요한 것은 없습니다.")
    if decisions is not None:
        lines += ["", "## 배포를 제약하는 라이선스", "",
                  "서버와 화면의 실행용 의존 가운데 배포를 제약하는 라이선스의 것이다: 예외 없는 GPL, AGPL, SSPL, BUSL, "
                  "Commons Clause, CC 의 비상업·변경 금지. 있으면 이 목록을 만드는 명령이 실패로 끝난다. "
                  "바탕 이미지는 여기서 보지 않는다 (위의 판단으로 다룬다).", ""]
        blocked = restricted(rows, decisions)
        if blocked:
            lines += ["| 구분 | 이름 | 버전 | 라이선스 | 까닭 |", "|---|---|---|---|---|"]
            lines += [f"| {_cell(row.kind)} | {_cell(row.name)} | {_cell(row.version)} | {_cell(row.license)} | {_cell(why)} |"
                      for row, why in blocked]
        else:
            lines.append("서버와 화면의 실행용 의존에 배포를 제약하는 라이선스가 없습니다.")
        stale = unused(rows, decisions)
        if stale:
            lines += ["", "쓰이지 않은 판단 (구성요소가 빠졌거나 라이선스가 달라졌다. 표를 고친다): "
                      + ", ".join(_cell(f"{one['name']} ({one['license']})") for one in stale)]
    lines += ["", "## 모두", ""]
    for kind in KINDS:
        count = sum(1 for row in rows if row.kind == kind)
        lines.append(f"- {kind}: {count}개")
    lines += ["", "| 구분 | 이름 | 버전 | 라이선스 |", "|---|---|---|---|"]
    lines += [f"| {_cell(row.kind)} | {_cell(row.name)} | {_cell(row.version)} | {_cell(row.license) or '(읽지 못했다)'} |"
              for row in rows]
    return "\n".join(lines) + "\n"


def _csv(rows, decisions=None):
    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\n")
    if decisions is None:
        writer.writerow(["구분", "이름", "버전", "라이선스", "확인이 필요한 까닭", "읽은 곳"])
        for row in rows:
            writer.writerow([row.kind, row.name, row.version, row.license, row.review, row.source])
        return out.getvalue()
    writer.writerow(["구분", "이름", "버전", "라이선스", "확인이 필요한 까닭", "읽은 곳", "받아들이는가", "판단의 까닭"])
    for row in rows:
        verdict, why = _judgement(row, decisions) if row.review else ("", "")
        writer.writerow([row.kind, row.name, row.version, row.license, row.review, row.source, verdict, why])
    return out.getvalue()


def _merged_bom(rows, bom, version, shipped):
    """표준 형식의 명세: 서버의 것(실제로 서버 파일에 든 것)에 화면과 바탕 이미지를 더한다."""
    document = {key: value for key, value in bom.items() if key != "components"}
    metadata = {key: value for key, value in bom.get("metadata", {}).items() if key != "timestamp"}
    metadata["component"] = {"type": "application", "name": "tabnit-base", "version": version}
    document["metadata"] = metadata
    if shipped is None:
        listed = list(bom.get("components", []))
    else:
        listed = list({id(one): one for one in _shipped_components(bom, shipped).values()}.values())
    known = {_file_name(component) for component in listed}
    # 뺀 구성요소를 가리키는 의존 관계는 함께 뺀다: 남기면 없는 것을 가리키게 된다.
    refs = {component["bom-ref"] for component in listed if "bom-ref" in component}
    if "dependencies" in document:
        kept_dependencies = []
        for dependency in document["dependencies"]:
            if dependency.get("ref") not in refs:
                continue
            entry = dict(dependency)
            if "dependsOn" in entry:
                entry["dependsOn"] = [ref for ref in entry["dependsOn"] if ref in refs]
            kept_dependencies.append(entry)
        document["dependencies"] = kept_dependencies
    for row in rows:
        if row.kind == "서버" and not row.source.startswith("서버의 명세"):
            # 명세에 없던 것 (따로 적어 둔 것, 또는 서버 파일에서만 본 것).
            name = row.name.split(":")[-1]
            if f"{name}-{row.version}" not in known:
                entry = {"type": "library", "name": name, "version": row.version}
                if ":" in row.name:
                    entry["group"] = row.name.split(":")[0]
                if row.license:
                    entry["licenses"] = [{"license": {"id": row.license}} if row.license in PERMISSIVE
                                         else {"license": {"name": row.license}}]
                listed.append(entry)
        elif row.kind == "화면":
            entry = {"type": "library", "name": row.name, "version": row.version,
                     "purl": f"pkg:npm/{row.name.replace('@', '%40', 1) if row.name.startswith('@') else row.name}@{row.version}"}
            if row.license:
                entry["licenses"] = [{"license": {"id": row.license}} if row.license in PERMISSIVE
                                     else {"license": {"name": row.license}}]
            listed.append(entry)
        elif row.kind == "바탕 이미지":
            listed.append({"type": "container" if "운영체제" in row.name else "application", "name": row.name,
                           "version": row.version, "licenses": [{"license": {"name": row.license}}]})
    document["components"] = sorted(listed, key=lambda one: (one["name"], one.get("group", ""), one.get("version", "")))
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def write(rows, bom, version, out, shipped=None, decisions=None):
    os.makedirs(out, exist_ok=True)
    shipped = None if shipped is None else set(shipped)
    for name, text in (("COMPONENTS.md", markdown(rows, version, decisions)), ("components.csv", _csv(rows, decisions)),
                       ("bom.json", _merged_bom(rows, bom, version, shipped))):
        with open(os.path.join(out, name), "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)


def main(arguments):
    if len(arguments) != 6:
        _fail("인자가 여섯이어야 합니다: 저장소의 맨 위, 서버의 명세, 서버 파일, 제품의 버전, 제품의 모듈 묶음 이름, 낼 곳")
    top, bom_path, jar_path, version, own_group, out = arguments
    try:
        with open(bom_path, encoding="utf-8") as handle:
            bom = json.load(handle)
        with open(os.path.join(top, "web", "package-lock.json"), encoding="utf-8") as handle:
            lock = json.load(handle)
        with open(os.path.join(top, "deploy", "base-components.json"), encoding="utf-8") as handle:
            written = json.load(handle)
        with open(os.path.join(top, "deploy", "license-decisions.json"), encoding="utf-8") as handle:
            decisions = decisions_of(json.load(handle))
        # 제품 자신의 모듈: 서버의 빌드에 든 폴더들이다.
        server = os.path.join(top, "server")
        own_modules = {}
        for name in sorted(os.listdir(server)):
            build = os.path.join(server, name, "build.gradle.kts")
            if os.path.isfile(build):
                with open(build, encoding="utf-8") as handle:
                    declared = re.search(r'^version = "(.*)"$', handle.read(), re.M)
                if declared:
                    own_modules[name] = declared.group(1)
        # 서버 파일 안의 라이브러리: 실제로 배포되는 것이다.
        with zipfile.ZipFile(jar_path) as jar:
            shipped = sorted(name.rsplit("/", 1)[-1][:-4] for name in jar.namelist()
                             if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"))
    except (OSError, ValueError, zipfile.BadZipFile) as problem:
        _fail(f"읽지 못했습니다: {problem}. 서버의 명세는 bash tools/components.sh 가 먼저 만듭니다.")
    base, extra = described(written)
    require_base_matches(base, top)
    if not shipped:
        _fail(f"서버 파일({jar_path})에 라이브러리가 하나도 없습니다. 서버 파일이 맞는지 확인하세요.")
    rows = collect(bom, lock, base, own_group, shipped, extra, own_modules)
    write(rows, bom, version, out, shipped, decisions)
    review = sum(1 for row in rows if row.review)
    waiting = undecided(rows, decisions)
    print(f"구성요소 {len(rows)}개 ("
          + ", ".join(f"{kind} {sum(1 for row in rows if row.kind == kind)}" for kind in KINDS)
          + f"). 확인이 필요한 것 {review}개 (받아들이는지가 미정인 것 {len(waiting)}개).")
    for row in waiting:
        print(f"[미정] {row.kind} {row.name} {row.version}: {row.license or '(없음)'}. "
              "받아들이는지를 deploy/license-decisions.json 에 적으세요.")
    for one in unused(rows, decisions):
        print(f"[낡음] 쓰이지 않은 판단: {one['name']} ({one['license']}). deploy/license-decisions.json 에서 고치거나 지우세요.")
    print(f"[확인] {os.path.join(out, 'COMPONENTS.md')}")
    blocked = restricted(rows, decisions)
    if blocked:
        for row, why in blocked:
            print(f"[실패] {row.kind} {row.name} {row.version}: {why}", file=sys.stderr)
        _fail(f"배포를 제약하는 라이선스의 실행용 의존이 {len(blocked)}개 있습니다. 그 의존을 빼거나 다른 것으로 바꾸세요. "
              "여러 라이선스 가운데 고를 수 있는 것이면 deploy/license-decisions.json 에 고른 라이선스(chosen)를 적으세요.")


if __name__ == "__main__":
    main(sys.argv[1:])
