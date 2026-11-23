"""E-11 수용 기준: 배포물에 든 구성요소의 이름, 버전, 라이선스 목록이 자동으로 나온다.

목록을 만드는 일(tools/components.py)의 일반 시험이다. 지어낸 구성요소로 본다: 실제 라이브러리의 목록에 기대지 않는다.
돌리는 법: python3 -m unittest tools/test_components.py
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import components  # noqa: E402


def server_bom(*items):
    """지어낸 서버 명세. items: (group, name, version, [라이선스...])"""
    listed = []
    for group, name, version, licenses in items:
        entry = {"type": "library", "group": group, "name": name, "version": version,
                 "purl": f"pkg:maven/{group}/{name}@{version}"}
        if licenses:
            entry["licenses"] = [{"license": {"id": one}} if not one.startswith("이름:")
                                 else {"license": {"name": one[3:]}} for one in licenses]
        listed.append(entry)
    return {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "metadata": {"timestamp": "2026-01-01T00:00:00Z", "component": {"type": "application", "name": "api"}},
            "components": listed}


def web_lock(*items):
    """지어낸 화면의 설치 기록. items: (경로, version, license, dev)"""
    packages = {"": {"name": "web", "version": "0.1.0"}}
    for path, version, license_, dev in items:
        entry = {"version": version}
        if license_ is not None:
            entry["license"] = license_
        if dev:
            entry["dev"] = True
        packages[path] = entry
    return {"packages": packages}


BASE = [{"image": "runtime:1", "where": "어딘가", "components": [
    {"name": "실행 환경", "version": "1", "license": "GPL-2.0-with-classpath-exception"}]}]


class ComponentsTest(unittest.TestCase):

    def collect(self, bom, lock, base=BASE, own_group="com.example.own", shipped=None, extra=()):
        return components.collect(bom, lock, base, own_group, shipped, list(extra))

    def test_서버는_실제로_서버_파일에_든_것만_담고_명세에_없는_것은_따로_적어_둔_것에서_읽는다(self):
        bom = server_bom(("org.a", "alpha", "1.0", ["Apache-2.0"]), ("org.a", "starter-only", "1.0", ["Apache-2.0"]),
                         ("com.example.own", "core", "0.1", []))
        # 서버 파일 안의 라이브러리 (이름-버전). starter-only 는 들어 있지 않고, 명세에 없는 둘이 들어 있다.
        shipped = ["alpha-1.0", "core-0.1", "packer-tool-4.1.2", "mystery-9"]
        extra = [{"file": "packer-tool", "name": "org.packer:packer-tool", "license": "Apache-2.0", "why": "빌드 도구가 넣는다"}]

        rows = self.collect(bom, web_lock(), base=[], shipped=shipped, extra=extra)

        self.assertEqual([(row.kind, row.name, row.version, row.license) for row in rows], [
            ("서버", "mystery", "9", ""),
            ("서버", "org.a:alpha", "1.0", "Apache-2.0"),
            ("서버", "org.packer:packer-tool", "4.1.2", "Apache-2.0"),
            ("제품", "com.example.own:core", "0.1", "제품 자신"),
        ])
        by_name = {row.name: row for row in rows}
        # 명세에도 따로 적어 둔 것에도 없는 것은 숨기지 않고 확인이 필요하다고 적는다.
        self.assertIn("명세에 없다", by_name["mystery"].review)
        self.assertEqual(by_name["org.packer:packer-tool"].review, "")
        # 표준 형식의 명세에도 실제로 든 것만 담는다.
        with tempfile.TemporaryDirectory() as out:
            components.write(rows, bom, "1", out, shipped)
            with open(os.path.join(out, "bom.json"), encoding="utf-8") as merged:
                names = [component["name"] for component in json.load(merged)["components"]]
        self.assertNotIn("starter-only", names)
        self.assertIn("alpha", names)
        self.assertIn("packer-tool", names)

    def test_서버와_화면과_바탕을_모두_이름과_버전과_라이선스로_담고_정해진_순서로_둔다(self):
        rows = self.collect(
            server_bom(("org.b", "zeta", "2.0", ["MIT"]), ("org.a", "alpha", "1.0", ["Apache-2.0"]),
                       ("com.example.own", "core", "0.1", [])),
            web_lock(("node_modules/widget", "3.1.0", "ISC", False), ("node_modules/@scope/part", "0.2.0", "MIT", False)))

        self.assertEqual([(row.kind, row.name, row.version, row.license) for row in rows], [
            ("바탕 이미지", "실행 환경", "1", "GPL-2.0-with-classpath-exception"),
            ("서버", "org.a:alpha", "1.0", "Apache-2.0"),
            ("서버", "org.b:zeta", "2.0", "MIT"),
            ("제품", "com.example.own:core", "0.1", "제품 자신"),
            ("화면", "@scope/part", "0.2.0", "MIT"),
            ("화면", "widget", "3.1.0", "ISC"),
        ])

    def test_이름_끝에_꼬리표가_붙은_서버의_라이브러리도_명세의_이름과_버전과_라이선스로_담는다(self):
        bom = server_bom(("org.n", "native", "1.2", ["MIT"]), ("org.n", "plain", "1.0-SNAPSHOT", ["Apache-2.0"]))
        # 꼬리표가 붙은 것, 그리고 명세에 없는 것 둘 (버전에 붙임표가 있는 것, 버전이 없는 것).
        shipped = ["native-1.2-linux-x86_64", "plain-1.0-SNAPSHOT", "lost-2.0-RC1", "noversion"]

        rows = self.collect(bom, web_lock(), base=[], shipped=shipped)

        self.assertEqual([(row.name, row.version, row.license, bool(row.review)) for row in rows], [
            ("lost", "2.0-RC1", "", True),
            ("noversion", "", "", True),
            ("org.n:native", "1.2", "MIT", False),
            ("org.n:plain", "1.0-SNAPSHOT", "Apache-2.0", False),
        ])

    def test_표준_형식의_명세에는_뺀_구성요소를_가리키는_의존_관계가_남지_않는다(self):
        bom = server_bom(("g", "kept", "1", ["MIT"]), ("g", "dropped", "1", ["MIT"]), ("g", "leaf", "1", ["MIT"]))
        for component in bom["components"]:
            component["bom-ref"] = "ref:" + component["name"]
        bom["dependencies"] = [
            {"ref": "ref:root", "dependsOn": ["ref:kept", "ref:dropped"]},
            {"ref": "ref:kept", "dependsOn": ["ref:dropped", "ref:leaf"]},
            {"ref": "ref:dropped", "dependsOn": ["ref:leaf"]},
            {"ref": "ref:leaf"},
        ]
        shipped = ["kept-1", "leaf-1"]
        rows = self.collect(bom, web_lock(), base=[], shipped=shipped)

        with tempfile.TemporaryDirectory() as out:
            components.write(rows, bom, "1", out, shipped)
            with open(os.path.join(out, "bom.json"), encoding="utf-8") as merged:
                document = json.load(merged)

        refs = {component.get("bom-ref") for component in document["components"]}
        self.assertEqual(refs, {"ref:kept", "ref:leaf"})
        self.assertEqual(document["dependencies"], [{"dependsOn": ["ref:leaf"], "ref": "ref:kept"}, {"ref": "ref:leaf"}])

    def test_바탕_이미지의_표가_잘못_적혀_있으면_어느_항목이_없는지_알린다(self):
        for wrong, missing in (({"extra": []}, "images"), ({"images": [{"image": "a:1"}]}, "components"),
                               ({"images": [{"image": "a:1", "where": "x", "components": [{"name": "n", "version": "1"}]}]}, "license"),
                               ({"images": [], "extra": [{"file": "f", "name": "n", "license": "MIT"}]}, "why"),
                               ([], "images")):
            with self.assertRaises(SystemExit) as stopped:
                components.described(wrong)
            self.assertIn("deploy/base-components.json", str(stopped.exception))
            self.assertIn(missing, str(stopped.exception))
        base, extra = components.described({"images": BASE})
        self.assertEqual(base, BASE)
        self.assertEqual(extra, [])

    def test_개발에만_쓰는_화면의_꾸러미는_담지_않고_안쪽에_겹쳐_깔린_것은_버전마다_한_번_담는다(self):
        rows = self.collect(server_bom(), web_lock(
            ("node_modules/tool", "1.0.0", "MIT", True),
            ("node_modules/lib", "1.0.0", "MIT", False),
            ("node_modules/other/node_modules/lib", "2.0.0", "MIT", False),
            ("node_modules/another/node_modules/lib", "2.0.0", "MIT", False)), base=[])

        self.assertEqual([(row.name, row.version) for row in rows], [("lib", "1.0.0"), ("lib", "2.0.0")])

    def test_라이선스를_읽지_못한_것과_흔한_허용형이_아닌_것을_확인이_필요하다고_표시한다(self):
        rows = self.collect(
            server_bom(("g", "none", "1", []), ("g", "copyleft", "1", ["LGPL-2.1-only"]),
                       ("g", "dual", "1", ["EPL-2.0", "LGPL-2.1-only"]), ("g", "both-open", "1", ["CC0-1.0", "BSD-2-Clause"]),
                       ("g", "named", "1", ["이름:Some Custom License"]), ("g", "fine", "1", ["Apache-2.0"]),
                       ("com.example.own", "mine", "1", [])),
            web_lock(("node_modules/odd", "1", None, False), ("node_modules/expr", "1", "(MIT OR GPL-3.0)", False),
                     ("node_modules/ok", "1", "BSD-3-Clause", False)), base=[])
        flagged = {row.name: row.review for row in rows if row.review}

        self.assertEqual(set(flagged), {"g:none", "g:copyleft", "g:dual", "g:named", "odd", "expr"})
        self.assertIn("읽지 못했다", flagged["g:none"])
        self.assertIn("읽지 못했다", flagged["odd"])
        self.assertIn("LGPL-2.1-only", flagged["g:copyleft"])
        # 여러 라이선스가 적힌 것: 하나라도 허용형이 아니면 표시한다. 모두 허용형이면 표시하지 않는다.
        self.assertIn("EPL-2.0", flagged["g:dual"])
        by_name = {row.name: row for row in rows}
        self.assertEqual(by_name["g:dual"].license, "EPL-2.0 / LGPL-2.1-only")
        self.assertEqual(by_name["g:both-open"].license, "CC0-1.0 / BSD-2-Clause")
        self.assertEqual(by_name["g:both-open"].review, "")
        # 제품 자신의 모듈은 확인할 것이 아니다.
        self.assertEqual(by_name["com.example.own:mine"].review, "")

    def test_빈_입력에서도_목록이_나오고_확인할_것이_없다고_적는다(self):
        rows = self.collect(server_bom(), web_lock(), base=[])

        self.assertEqual(rows, [])
        text = components.markdown(rows, "1.2.3")
        self.assertIn("확인이 필요한 것은 없습니다", text)
        self.assertIn("1.2.3", text)

    def test_같은_입력이면_글자까지_같은_목록과_명세가_나온다(self):
        bom = server_bom(("org.b", "zeta", "2.0", ["MIT"]), ("org.a", "alpha", "1.0", []))
        lock = web_lock(("node_modules/widget", "3.1.0", "ISC", False))
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            for out in (first, second):
                components.write(self.collect(bom, lock), bom, "9.9.9", out, None)
            for name in ("COMPONENTS.md", "components.csv", "bom.json"):
                with open(os.path.join(first, name), encoding="utf-8") as a, open(os.path.join(second, name), encoding="utf-8") as b:
                    self.assertEqual(a.read(), b.read(), name)
            with open(os.path.join(first, "bom.json"), encoding="utf-8") as merged:
                document = json.load(merged)
        # 표준 형식의 명세: 서버의 것에 화면과 바탕을 더한다. 돌릴 때마다 달라지는 시각은 넣지 않는다.
        self.assertEqual(document["bomFormat"], "CycloneDX")
        self.assertNotIn("timestamp", document["metadata"])
        names = [component["name"] for component in document["components"]]
        self.assertEqual(names, sorted(names))
        self.assertIn("widget", names)
        self.assertIn("실행 환경", names)
        widget = next(component for component in document["components"] if component["name"] == "widget")
        self.assertEqual(widget["purl"], "pkg:npm/widget@3.1.0")
        self.assertEqual(widget["licenses"], [{"license": {"id": "ISC"}}])

    def test_목록의_글에는_표를_깨는_글자를_그대로_두지_않는다(self):
        rows = self.collect(server_bom(("g", "pipe|name", "1", ["이름:A | B"])), web_lock(), base=[])

        text = components.markdown(rows, "1")
        line = next(one for one in text.splitlines() if "pipe" in one and one.startswith("| 서버"))
        self.assertEqual(line.count("|"), 6)

    def test_바탕_이미지의_표가_실제로_쓰는_이미지와_다르면_알리고_멈춘다(self):
        with tempfile.TemporaryDirectory() as top:
            os.makedirs(os.path.join(top, "deploy", "install"))
            with open(os.path.join(top, "deploy", "Dockerfile"), "w", encoding="utf-8") as dockerfile:
                dockerfile.write("FROM --platform=$BUILDPLATFORM builder:1 AS build\nFROM runtime:2\n")
            with open(os.path.join(top, "deploy", "install", "compose.yaml"), "w", encoding="utf-8") as compose:
                compose.write("services:\n  db:\n    image: database:5\n  app:\n    image: product:${V}\n")
            base = [{"image": "runtime:1", "where": "x", "components": []}, {"image": "database:5", "where": "y", "components": []}]

            with self.assertRaises(SystemExit) as stopped:
                components.require_base_matches(base, top)
            self.assertIn("runtime:2", str(stopped.exception))
            # 맞으면 지나간다.
            base[0]["image"] = "runtime:2"
            components.require_base_matches(base, top)


if __name__ == "__main__":
    unittest.main()
