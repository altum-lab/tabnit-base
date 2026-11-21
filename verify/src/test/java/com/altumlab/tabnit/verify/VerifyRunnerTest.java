package com.altumlab.tabnit.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SK-06, E-04: 검증 질문 실행기의 일반 시험. 어느 Pack 과도 무관하다 (D4 1절: 시험은 두 겹이다).
 *
 * <p>제품을 띄우지 않는다. 제품의 답은 이 시험이 지어내 넘긴다. 모든 이름과 값은 지어낸 것이다.
 * 제품을 실제로 띄워 묻는 것은 bash verify/run.sh 가 한다.
 */
class VerifyRunnerTest {

    private static final String PACK = "p";

    @TempDir
    Path temp;

    // ── 식별자 ────────────────────────────────────────────────────

    @Test
    void 식별자는_값을_세로_막대로_잇고_값_안의_세로_막대와_역빗금은_가린다() {
        assertThat(Ids.join(List.of("p", "TypeA", "k-1"))).isEqualTo("p|TypeA|k-1");
        assertThat(Ids.join(List.of("a|b", "c\\d"))).isEqualTo("a\\|b|c\\\\d");
        assertThat(Ids.join(List.of("하나"))).isEqualTo("하나");
        assertThat(Ids.entity(PACK, Map.of("type", "TypeA", "key", 7))).isEqualTo("p|TypeA|7");
        assertThatThrownBy(() -> Ids.entity(PACK, Map.of("type", "TypeA"))).isInstanceOf(QuestionFileException.class);
        assertThatThrownBy(() -> Ids.entity(PACK, "TypeA")).isInstanceOf(QuestionFileException.class);
    }

    // ── 질문 파일 읽기 ───────────────────────────────────────────────

    @Test
    void 질문_파일에서_시나리오와_질문을_읽는다() throws IOException {
        QuestionFile file = QuestionFile.read(write("S-01.yaml", """
                scenario:
                  id: S-01
                  title: 지어낸 시나리오
                  steps:
                    - { step: 1, inputSource: src-a, file: data/a-v1.csv, effectiveDate: 2026-01-01 }
                    - { step: 2, inputSource: src-a, file: data/a-v2.csv, effectiveDate: 2026-03-01 }
                questions:
                  - id: Q-02
                    kind: changes
                    title: 둘째
                    step: 2
                    expect: { changes: [] }
                  - id: Q-01
                    kind: relations-as-of
                    title: 첫째
                    asOf: 2026-02-01
                    entity: { type: TypeA, key: k-1 }
                    expect: { relations: [] }
                """));

        assertThat(file.fileName()).isEqualTo("S-01.yaml");
        assertThat(file.scenarioId()).isEqualTo("S-01");
        assertThat(file.steps()).containsExactly(
                new QuestionFile.Step(1, "src-a", "data/a-v1.csv", LocalDate.parse("2026-01-01")),
                new QuestionFile.Step(2, "src-a", "data/a-v2.csv", LocalDate.parse("2026-03-01")));
        // 적힌 차례 그대로 읽는다. 돌릴 때 번호 순으로 돌린다.
        assertThat(file.questions()).extracting(QuestionFile.Question::id).containsExactly("Q-02", "Q-01");
        assertThat(file.questions().get(1).kind()).isEqualTo("relations-as-of");
    }

    @Test
    void 틀린_질문_파일은_어느_파일의_어느_항목이_틀렸는지_알린다() throws IOException {
        String steps = "scenario:\n  id: S\n  title: t\n  steps:\n    - { step: 1, inputSource: a, file: data/a.csv, effectiveDate: 2026-01-01 }\n";
        Map<String, String> wrong = new LinkedHashMap<>();
        wrong.put("questions: []\n", "scenario");
        wrong.put("scenario: { id: S, title: t, steps: [] }\nquestions: []\n", "steps");
        wrong.put("scenario:\n  id: S\n  title: t\n  steps:\n    - { step: 1, inputSource: a, effectiveDate: 2026-01-01 }\nquestions: []\n", "file");
        wrong.put("scenario:\n  id: S\n  title: t\n  steps:\n    - { step: 1, inputSource: a, file: data/a.csv, effectiveDate: 어제 }\nquestions: []\n",
                "effectiveDate");
        wrong.put(steps + "    - { step: 1, inputSource: a, file: data/b.csv, effectiveDate: 2026-02-01 }\nquestions: []\n",
                "step");
        wrong.put(steps + "questions:\n  - { kind: changes, title: t, step: 1, expect: { changes: [] } }\n", "id");
        wrong.put(steps + "questions:\n  - { id: Q-1, title: t, step: 1, expect: { changes: [] } }\n", "kind");
        wrong.put(steps + "questions:\n  - { id: Q-1, kind: changes, title: t, step: 1 }\n", "expect");
        wrong.put(steps + "questions:\n  - { id: Q-1, kind: changes, title: t, step: 1, expect: { changes: [] } }\n"
                + "  - { id: Q-1, kind: changes, title: t, step: 1, expect: { changes: [] } }\n", "Q-1");
        wrong.put("scenario: [이것은 목록이다]\nquestions: []\n", "scenario");
        wrong.put("이것은: [닫히지 않았다\n", "");
        wrong.put("", "");
        int n = 0;
        for (Map.Entry<String, String> entry : wrong.entrySet()) {
            Path path = write("bad-" + (n++) + ".yaml", entry.getKey());
            assertThatThrownBy(() -> QuestionFile.read(path)).as(entry.getKey())
                .isInstanceOf(QuestionFileException.class)
                .hasMessageContaining(path.getFileName().toString())
                .hasMessageContaining(entry.getValue());
        }
    }

    // ── 정답과 견주기 ────────────────────────────────────────────────

    @Test
    void 네_종류의_질문을_제품의_답과_견줘_번호_순으로_통과와_실패를_낸다() throws IOException {
        QuestionFile file = QuestionFile.read(write("S-02.yaml", SCENARIO + """
                questions:
                  - id: Q-04
                    kind: actions
                    title: 조치
                    change: { step: 2, itemKey: [k-1] }
                    expect:
                      actions:
                        - { rule: "0001", priority: 1, target: { type: TypeB, key: k-9 }, dueDate: 2026-03-15, basis: { entity: { type: TypeA, key: k-1 }, inputSource: src-a, edition: 2 } }
                  - id: Q-03
                    kind: impact
                    title: 영향 범위
                    change: { step: 2, itemKey: [k-1] }
                    expect:
                      targets:
                        - entity: { type: TypeB, key: k-9 }
                          depth: 1
                          path: { from: { type: TypeA, key: k-1 }, asOf: 2026-03-01, relations: [ { type: relatesTo, source: { type: TypeA, key: k-1 }, target: { type: TypeB, key: k-9 } } ] }
                      countsByType: { TypeB: 1 }
                      countsByDepth: { 1: 1 }
                      truncated: false
                  - id: Q-02
                    kind: changes
                    title: 변동 (키 열이 둘)
                    step: 2
                    expect:
                      changes:
                        - { itemKey: [k-1], kind: changed }
                        - { itemKey: [k-2, "x|y"], kind: removed }
                  - id: Q-01
                    kind: relations-as-of
                    title: 시점 조회
                    asOf: 2026-02-01
                    entity: { type: TypeA, key: k-1 }
                    expect:
                      relations:
                        - { type: relatesTo, source: { type: TypeA, key: k-1 }, target: { type: TypeB, key: k-9 }, validFrom: 2026-01-01, validTo: null }
                """));
        FakeProduct product = new FakeProduct();

        List<Evaluator.Result> results = new Evaluator(PACK, product).evaluate(file, product.steps());

        assertThat(results).extracting(Evaluator.Result::id).containsExactly("Q-01", "Q-02", "Q-03", "Q-04");
        assertThat(results).allSatisfy(result -> assertThat(result.passed()).as(result.id() + " " + result.reason()
                + "\n기대: " + result.expected() + "\n실제: " + result.actual()).isTrue());
        assertThat(results).extracting(Evaluator.Result::label)
            .containsExactly("2026-02-01", "2단계", "2단계 k-1", "2단계 k-1");
        // 제품에는 식별자로 물었다.
        assertThat(product.asked).containsExactly("relations p|TypeA|k-1 2026-02-01", "impact c-1", "actions p c-1");
    }

    @Test
    void 항목은_같아도_순서가_다르면_실패이고_기대와_실제를_함께_낸다() throws IOException {
        QuestionFile file = QuestionFile.read(write("S-03.yaml", SCENARIO + """
                questions:
                  - id: Q-01
                    kind: changes
                    title: 순서가 뒤집힌 정답
                    step: 2
                    expect:
                      changes:
                        - { itemKey: [k-2, "x|y"], kind: removed }
                        - { itemKey: [k-1], kind: changed }
                  - id: Q-02
                    kind: relations-as-of
                    title: 날짜가 하루 다른 정답
                    asOf: 2026-02-01
                    entity: { type: TypeA, key: k-1 }
                    expect:
                      relations:
                        - { type: relatesTo, source: { type: TypeA, key: k-1 }, target: { type: TypeB, key: k-9 }, validFrom: 2026-01-02, validTo: null }
                  - id: Q-03
                    kind: impact
                    title: 경로가 다른 정답 (대상은 맞다)
                    change: { step: 2, itemKey: [k-1] }
                    expect:
                      targets:
                        - entity: { type: TypeB, key: k-9 }
                          depth: 1
                          path: { from: { type: TypeA, key: k-1 }, asOf: 2026-03-01, relations: [ { type: otherRelation, source: { type: TypeA, key: k-1 }, target: { type: TypeB, key: k-9 } } ] }
                """));
        FakeProduct product = new FakeProduct();

        List<Evaluator.Result> results = new Evaluator(PACK, product).evaluate(file, product.steps());

        assertThat(results).extracting(Evaluator.Result::passed).containsExactly(false, false, false);
        assertThat(results.get(0).expected()).contains("k-2").isNotEqualTo(results.get(0).actual());
        assertThat(results.get(0).actual()).contains("k-1");
        assertThat(results.get(1).expected()).contains("2026-01-02");
        assertThat(results.get(1).actual()).contains("2026-01-01");
        assertThat(results.get(2).expected()).contains("otherRelation");
        assertThat(results.get(2).actual()).contains("relatesTo");
    }

    @Test
    void 만들지_않은_종류와_근거가_빠진_정답과_오류를_낸_질문은_실패이고_나머지는_계속_돌린다() throws IOException {
        QuestionFile file = QuestionFile.read(write("S-04.yaml", SCENARIO + """
                questions:
                  - id: Q-01
                    kind: something-new
                    title: 아직 만들지 않은 종류
                    expect: { anything: [] }
                  - id: Q-02
                    kind: impact
                    title: 근거 경로를 뺀 정답
                    change: { step: 2, itemKey: [k-1] }
                    expect:
                      targets:
                        - { entity: { type: TypeB, key: k-9 }, depth: 1 }
                  - id: Q-03
                    kind: actions
                    title: 근거를 뺀 정답
                    change: { step: 2, itemKey: [k-1] }
                    expect:
                      actions:
                        - { rule: "0001", priority: 1, target: { type: TypeB, key: k-9 }, dueDate: 2026-03-15 }
                  - id: Q-04
                    kind: relations-as-of
                    title: 제품이 오류를 낸다
                    asOf: 2026-02-01
                    entity: { type: TypeA, key: boom }
                    expect: { relations: [] }
                  - id: Q-05
                    kind: impact
                    title: 없는 변동을 가리킨다
                    change: { step: 2, itemKey: [no-such-key] }
                    expect: { targets: [] }
                  - id: Q-06
                    kind: changes
                    title: 없는 단계를 가리킨다
                    step: 9
                    expect: { changes: [] }
                  - id: Q-07
                    kind: relations-as-of
                    title: 제품이 없다고 답한다
                    asOf: 2026-02-01
                    entity: { type: TypeA, key: missing }
                    expect: { relations: [] }
                  - id: Q-08
                    kind: changes
                    title: 그래도 이 질문은 통과한다
                    step: 2
                    expect:
                      changes:
                        - { itemKey: [k-1], kind: changed }
                        - { itemKey: [k-2, "x|y"], kind: removed }
                """));
        FakeProduct product = new FakeProduct();

        List<Evaluator.Result> results = new Evaluator(PACK, product).evaluate(file, product.steps());

        assertThat(results).extracting(Evaluator.Result::passed)
            .containsExactly(false, false, false, false, false, false, false, true);
        assertThat(results.get(0).reason()).contains("아직 만들지 않은 종류");
        assertThat(results.get(1).reason()).contains("path");
        assertThat(results.get(2).reason()).contains("basis");
        assertThat(results.get(3).reason()).contains("지어낸 고장");
        assertThat(results.get(4).reason()).contains("no-such-key");
        assertThat(results.get(5).reason()).contains("9");
        assertThat(results.get(6).reason()).contains("404");
    }

    @Test
    void 투입이_실패한_단계를_묻는_질문은_실패다() throws IOException {
        QuestionFile file = QuestionFile.read(write("S-05.yaml", SCENARIO + """
                questions:
                  - id: Q-01
                    kind: changes
                    title: 거부된 단계의 변동
                    step: 2
                    expect: { changes: [] }
                """));
        FakeProduct product = new FakeProduct();
        Map<Integer, Product.Reply> steps = new LinkedHashMap<>(product.steps());
        steps.put(2, new Product.Reply(422, Map.of("code", "EDITION_REJECTED", "detail", "지어낸 거부")));

        List<Evaluator.Result> results = new Evaluator(PACK, product).evaluate(file, steps);

        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).reason()).contains("422").contains("EDITION_REJECTED");
    }

    // ── 다른 모양의 Pack ─────────────────────────────────────────────

    @Test
    void 정답의_키와_이름은_적힌_글자_그대로_읽는다_숫자나_참거짓이나_날짜로_바꿔_읽지_않는다() throws IOException {
        // 앞이 0 인 번호, 참거짓처럼 보이는 말, 날짜처럼 보이는 키, 소수처럼 보이는 키를 따옴표 없이 적은 Pack.
        QuestionFile file = QuestionFile.read(write("S-06.yaml", SCENARIO + """
                questions:
                  - id: Q-01
                    kind: changes
                    title: 글자 그대로
                    step: 2
                    expect:
                      changes:
                        - { itemKey: [007, yes], kind: added }
                        - { itemKey: [2026-01-01, 1.50], kind: removed }
                  - id: Q-02
                    kind: relations-as-of
                    title: 따옴표로 적은 날짜도 받는다
                    asOf: "2026-02-01"
                    entity: { type: TypeA, key: 0123 }
                    expect: { relations: [] }
                """));
        FakeProduct product = new FakeProduct();
        Map<Integer, Product.Reply> steps = new LinkedHashMap<>(product.steps());
        steps.put(2, new Product.Reply(200, Map.of("changes", List.of(
                Map.of("changeEventId", "c-7", "itemKey", "007|yes", "kind", "ADDED"),
                Map.of("changeEventId", "c-8", "itemKey", "2026-01-01|1.50", "kind", "REMOVED")))));

        List<Evaluator.Result> results = new Evaluator(PACK, product).evaluate(file, steps);

        assertThat(results.get(0).passed()).as(results.get(0).expected() + " / " + results.get(0).actual()).isTrue();
        assertThat(product.asked).contains("relations p|TypeA|0123 2026-02-01");
    }

    @Test
    void 글자로는_같아_보여도_묶음이_다르면_다른_것이다() throws IOException {
        QuestionFile file = QuestionFile.read(write("S-07.yaml", SCENARIO + """
                questions:
                  - id: Q-01
                    kind: changes
                    title: 값 안에 쉼표와 묶음의 글자가 든 키
                    step: 2
                    expect:
                      changes:
                        - { itemKey: ["a, kind: ADDED}, {itemKey: b"], kind: added }
                  - id: Q-02
                    kind: changes
                    title: 역빗금이 든 키
                    step: 1
                    expect:
                      changes:
                        - { itemKey: ['c\\d', e], kind: changed }
                """));
        FakeProduct product = new FakeProduct();
        Map<Integer, Product.Reply> steps = new LinkedHashMap<>();
        steps.put(1, new Product.Reply(200, Map.of("changes", List.of(Map.of("changeEventId", "c-1", "itemKey", "c\\\\d|e", "kind", "CHANGED")))));
        // 제품은 항목 둘을 냈다. 정답은 그 둘을 한 글자로 이어 붙인 것처럼 보이는 항목 하나다.
        steps.put(2, new Product.Reply(200, Map.of("changes", List.of(Map.of("changeEventId", "c-2", "itemKey", "a", "kind", "ADDED"),
                Map.of("changeEventId", "c-3", "itemKey", "b", "kind", "ADDED")))));

        List<Evaluator.Result> results = new Evaluator(PACK, product).evaluate(file, steps);

        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(1).passed()).as(results.get(1).expected() + " / " + results.get(1).actual()).isTrue();
    }

    @Test
    void 적은_수와_한계는_견주고_적지_않은_것은_견주지_않으며_빈_목록과_조치의_어긋남도_잡는다() throws IOException {
        String target = """
                        - entity: { type: TypeB, key: k-9 }
                          depth: 1
                          path: { from: { type: TypeA, key: k-1 }, asOf: 2026-03-01, relations: [ { type: relatesTo, source: { type: TypeA, key: k-1 }, target: { type: TypeB, key: k-9 } } ] }
                """;
        String action = "{ rule: \"0001\", priority: %s, target: { type: TypeB, key: k-9 }, dueDate: %s, basis: { entity: { type: TypeA, key: k-1 }, inputSource: %s, edition: %s } }";
        QuestionFile file = QuestionFile.read(write("S-08.yaml", SCENARIO + "questions:\n"
                + impact("Q-01", "수를 적지 않았다", target, "")
                + impact("Q-02", "유형별 수가 틀렸다", target, "      countsByType: { TypeB: 2 }\n")
                + impact("Q-03", "깊이별 수가 틀렸다", target, "      countsByDepth: { 1: 1, 2: 1 }\n")
                + impact("Q-04", "한계에서 멈췄다고 적었다", target, "      truncated: true\n")
                + impact("Q-05", "깊이가 틀렸다", target.replace("depth: 1", "depth: 2"), "")
                + "  - { id: Q-06, kind: impact, title: 영향 대상이 없다고 적었다, change: { step: 2, itemKey: [k-1] }, expect: { targets: [] } }\n"
                + "  - { id: Q-07, kind: changes, title: 변동이 없다고 적었다, step: 2, expect: { changes: [] } }\n"
                + "  - { id: Q-08, kind: changes, title: 변동이 없는 단계에 있다고 적었다, step: 1, expect: { changes: [ { itemKey: [k-1], kind: added } ] } }\n"
                + actions("Q-09", action.formatted("1", "2026-03-15", "src-a", "2"))
                + actions("Q-10", action.formatted("2", "2026-03-15", "src-a", "2"))
                + actions("Q-11", action.formatted("1", "2026-03-16", "src-a", "2"))
                + actions("Q-12", action.formatted("1", "2026-03-15", "src-b", "2"))
                + actions("Q-13", action.formatted("1", "2026-03-15", "src-a", "3"))
                + "  - { id: Q-14, kind: actions, title: 조치가 없다고 적었다, change: { step: 2, itemKey: [k-1] }, expect: { actions: [] } }\n"));
        FakeProduct product = new FakeProduct();

        List<Evaluator.Result> results = new Evaluator(PACK, product).evaluate(file, product.steps());

        assertThat(results).extracting(result -> result.id() + ":" + result.passed())
            .containsExactly("Q-01:true", "Q-02:false", "Q-03:false", "Q-04:false", "Q-05:false", "Q-06:false", "Q-07:false",
                    "Q-08:false", "Q-09:true", "Q-10:false", "Q-11:false", "Q-12:false", "Q-13:false", "Q-14:false");
        // 견준 실패에는 기대와 실제가 붙는다.
        assertThat(results.get(1).expected()).contains("countsByType");
        assertThat(results.get(1).actual()).contains("countsByType");
    }

    @Test
    void 틀린_날짜나_단계_번호가_든_질문만_실패하고_나머지는_돈다() throws IOException {
        QuestionFile file = QuestionFile.read(write("S-09.yaml", SCENARIO + """
                questions:
                  - { id: Q-01, kind: relations-as-of, title: 날짜가 아니다, asOf: 어제, entity: { type: TypeA, key: k-1 }, expect: { relations: [] } }
                  - { id: Q-02, kind: changes, title: 단계의 번호가 수가 아니다, step: 둘째, expect: { changes: [] } }
                  - { id: Q-03, kind: changes, title: 이것은 돈다, step: 1, expect: { changes: [] } }
                """));
        FakeProduct product = new FakeProduct();

        List<Evaluator.Result> results = new Evaluator(PACK, product).evaluate(file, product.steps());

        assertThat(results).extracting(Evaluator.Result::passed).containsExactly(false, false, true);
        assertThat(results.get(0).reason()).contains("asOf");
        assertThat(results.get(1).reason()).contains("step");
    }

    // ── Pack 폴더를 읽기 ─────────────────────────────────────────────

    @Test
    void Pack_폴더에서_질문_파일을_모두_읽고_모자란_것과_겹친_번호와_짧은_시나리오를_알린다() throws IOException {
        Path packs = Files.createDirectories(this.temp.resolve("packs"));
        // 정의가 없는 폴더, 정의는 없는데 질문만 있는 폴더, 질문이 없는 Pack, 파일이 여럿인 Pack.
        Files.createDirectories(packs.resolve("b-empty"));
        Files.createDirectories(packs.resolve("c-questions-only/questions"));
        Files.writeString(packs.resolve("c-questions-only/questions/S.yaml"), SCENARIO + "questions: []\n");
        Files.createDirectories(packs.resolve("d-no-questions"));
        Files.writeString(packs.resolve("d-no-questions/pack.ttl"), "# 지어낸 Pack\n");
        Path many = Files.createDirectories(packs.resolve("a-many/questions"));
        Files.writeString(packs.resolve("a-many/pack.ttl"), "# 지어낸 Pack\n");
        Files.writeString(many.resolve("S-02.yml"), SCENARIO + "questions:\n  - { id: Q-01, kind: changes, title: t, step: 1, expect: { changes: [] } }\n");
        Files.writeString(many.resolve("S-01.yaml"), SCENARIO.replace("id: S", "id: S-1")
                + "    - { step: 3, inputSource: src-a, file: data/a-v3.csv, effectiveDate: 2026-06-01 }\n"
                + "questions:\n  - { id: Q-01, kind: changes, title: t, step: 1, expect: { changes: [] } }\n");
        Files.writeString(many.resolve("broken.yaml"), "이것은: [닫히지 않았다\n");
        Files.writeString(many.resolve("notes.txt"), "질문 파일이 아니다");
        Files.writeString(packs.resolve("not-a-folder.txt"), "폴더가 아니다");

        // 폴더는 이름의 글자 코드 순이다.
        assertThat(VerifyMain.packFolders(packs)).extracting(path -> path.getFileName().toString())
            .containsExactly("a-many", "b-empty", "c-questions-only", "d-no-questions");
        assertThat(VerifyMain.readPack(packs.resolve("b-empty")).hasContent()).isFalse();
        assertThat(VerifyMain.readPack(packs.resolve("b-empty")).problems()).isEmpty();
        // 질문은 있는데 정의가 없으면 내용이 없는 것으로 넘기지 않는다.
        VerifyMain.PackQuestions questionsOnly = VerifyMain.readPack(packs.resolve("c-questions-only"));
        assertThat(questionsOnly.hasContent()).isTrue();
        assertThat(questionsOnly.problems()).anyMatch(problem -> problem.contains("pack.ttl"));
        VerifyMain.PackQuestions none = VerifyMain.readPack(packs.resolve("d-no-questions"));
        assertThat(none.hasContent()).isTrue();
        assertThat(none.files()).isEmpty();
        assertThat(none.problems()).anyMatch(problem -> problem.contains("10"));

        VerifyMain.PackQuestions read = VerifyMain.readPack(packs.resolve("a-many"));
        // 읽을 수 있는 파일은 이름 순으로 모두 읽는다. 확장자는 yaml 과 yml 을 받는다.
        assertThat(read.files()).extracting(QuestionFile::fileName).containsExactly("S-01.yaml", "S-02.yml");
        assertThat(read.problems()).anyMatch(problem -> problem.contains("broken.yaml"));
        assertThat(read.problems()).anyMatch(problem -> problem.contains("Q-01") && problem.contains("S-02.yml"));
        // 판이 셋에 못 미치는 시나리오 (D2 AD-112).
        assertThat(read.problems()).anyMatch(problem -> problem.contains("S-02.yml") && problem.contains("3"));
        assertThat(read.problems()).noneMatch(problem -> problem.contains("S-01.yaml") && problem.contains("단계가"));
        assertThat(read.problems()).anyMatch(problem -> problem.contains("actions"));
    }

    // ── 제품에 묻는 길 ───────────────────────────────────────────────

    @Test
    void 제품에는_쿠키와_토큰을_들고_묻고_주소의_값은_가려_보낸다() throws Exception {
        List<String> seen = new ArrayList<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
            .create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath() + " ? "
                    + exchange.getRequestURI().getRawQuery() + " | 쿠키 " + exchange.getRequestHeaders().getFirst("Cookie") + " | 토큰 "
                    + exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN") + " | " + body.replace("\r\n", "⏎"));
            if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/api/v1/session")) {
                exchange.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=tok-1; Path=/");
            }
            if (exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getPath().equals("/api/v1/session")) {
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=ses-1; Path=/; HttpOnly; Secure");
            }
            byte[] answer = "{\"relations\":[],\"n\":1,\"name\":\"가 \\\"나\\\"\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/relations") ? 404 : 200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        try {
            HttpProduct product = new HttpProduct(server.getAddress().getPort());

            assertThat(product.login("id \"x\"", "pw\\1").status()).isEqualTo(200);
            Product.Reply relations = product.relations("p|TypeA|k 1&x=y", LocalDate.parse("2026-02-01"));
            product.loadPack("p");
            product.submitEdition("p", "src-a", LocalDate.parse("2026-03-01"), "지어낸 파일 \"이름\".csv",
                    "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));

            assertThat(relations.status()).isEqualTo(404);
            assertThat(relations.body()).isEqualTo(Map.of("relations", List.of(), "n", 1, "name", "가 \"나\""));
            // 로그인 전에 내 정보를 물어 토큰을 받는다. 아이디와 비밀번호의 따옴표와 역빗금은 가려 보낸다.
            assertThat(seen.get(0)).startsWith("GET /api/v1/session").contains("쿠키 null").contains("토큰 null");
            assertThat(seen.get(1)).startsWith("POST /api/v1/session").contains("쿠키 XSRF-TOKEN=tok-1").contains("토큰 tok-1")
                .contains("{\"loginId\":\"id \\\"x\\\"\",\"password\":\"pw\\\\1\"}");
            // 묻는 요청에는 세션 쿠키가 실린다. 식별자의 세로 막대와 빈칸과 & 는 주소에서 가려진다. GET 에는 토큰을 싣지 않는다.
            assertThat(seen.get(2)).startsWith("GET /api/v1/relations ? entityId=p%7CTypeA%7Ck+1%26x%3Dy&asOf=2026-02-01")
                .contains("JSESSIONID=ses-1")
                .contains("토큰 null");
            assertThat(seen.get(3)).startsWith("POST /api/v1/pack-loads").contains("토큰 tok-1").contains("{\"packId\":\"p\"}");
            // 판 올리기: 항목 셋과 파일. 파일의 이름은 헤더를 깨지 않는 글자만 남는다.
            assertThat(seen.get(4)).startsWith("POST /api/v1/editions").contains("토큰 tok-1")
                .contains("name=\"packId\"⏎⏎p⏎")
                .contains("name=\"inputSourceId\"⏎⏎src-a⏎")
                .contains("name=\"effectiveDate\"⏎⏎2026-03-01⏎")
                .contains("filename=\"")
                .doesNotContain("filename=\"지어낸 파일 \"이름")
                .contains("⏎⏎a,b\n1,2\n⏎");
        }
        finally {
            server.stop(0);
        }
    }

    // ── 최소 수, 두 번 실행 ───────────────────────────────────────────

    @Test
    void 질문은_열_개_이상이고_종류마다_둘_이상이어야_한다() {
        List<QuestionFile.Question> enough = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            enough.add(question("A-" + i, "relations-as-of"));
        }
        for (String kind : List.of("changes", "impact", "actions")) {
            enough.add(question(kind + "-1", kind));
            enough.add(question(kind + "-2", kind));
        }
        assertThat(Report.shortfalls(enough)).isEmpty();

        // 열 개에서 하나 모자라다.
        assertThat(Report.shortfalls(enough.subList(1, enough.size()))).hasSize(1).first().asString().contains("9").contains("10");
        // 종류 하나가 하나뿐이다.
        List<QuestionFile.Question> lopsided = new ArrayList<>(enough.subList(0, enough.size() - 1));
        lopsided.add(question("A-9", "relations-as-of"));
        assertThat(Report.shortfalls(lopsided)).hasSize(1).first().asString().contains("actions");
        assertThat(Report.shortfalls(List.of())).hasSize(5);
    }

    @Test
    void 두_번_돌린_결과가_다르면_어느_질문이_달랐는지_알린다() {
        List<Evaluator.Result> first = List.of(result("Q-01", true, "[a]"), result("Q-02", true, "[b]"), result("Q-03", false, "[c]"));

        assertThat(Report.differences(first, first)).isEmpty();
        // 통과 여부는 같아도 실제 값이 다르면 다른 것이다.
        assertThat(Report.differences(first,
                List.of(result("Q-01", true, "[a]"), result("Q-02", true, "[b, x]"), result("Q-03", false, "[c]"))))
            .containsExactly("Q-02");
        assertThat(Report.differences(first, List.of(result("Q-01", false, "[a]"), result("Q-02", true, "[b]"))))
            .containsExactly("Q-01", "Q-03");
    }

    @Test
    void 보고는_질문별_목록이고_실패에는_기대와_실제가_붙는다() {
        String text = Report.scenario(PACK, "S-01", List.of(
                new Evaluator.Result("Q-01", "relations-as-of", "2026-02-01", true, "[a]", "[a]", null),
                new Evaluator.Result("Q-02", "impact", "2단계 k-1", false, "[a, b]", "[a]", null),
                new Evaluator.Result("Q-03", "something-new", "", false, null, null, "아직 만들지 않은 종류")), List.of());

        assertThat(text).contains("Pack: p").contains("시나리오 S-01").contains("통과 1 / 3").contains("두 번 실행: 같음");
        assertThat(text).containsSubsequence("통과  Q-01", "실패  Q-02", "기대: [a, b]", "실제: [a]", "실패  Q-03", "아직 만들지 않은 종류");
        assertThat(Report.scenario(PACK, "S-01", List.of(), List.of("Q-02"))).contains("두 번 실행: 다름 (Q-02)");
    }

    // ── 도우미 ────────────────────────────────────────────────────

    private static final String SCENARIO = """
            scenario:
              id: S
              title: 지어낸 시나리오
              steps:
                - { step: 1, inputSource: src-a, file: data/a-v1.csv, effectiveDate: 2026-01-01 }
                - { step: 2, inputSource: src-a, file: data/a-v2.csv, effectiveDate: 2026-03-01 }
            """;

    private Path write(String name, String content) throws IOException {
        Path path = this.temp.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static String impact(String id, String title, String target, String extra) {
        return "  - id: " + id + "\n    kind: impact\n    title: " + title + "\n    change: { step: 2, itemKey: [k-1] }\n    expect:\n      targets:\n"
                + target.replace("                        - ", "        - ").replace("                          ", "          ") + extra;
    }

    private static String actions(String id, String action) {
        return "  - { id: " + id + ", kind: actions, title: 조치, change: { step: 2, itemKey: [k-1] }, expect: { actions: [ " + action
                + " ] } }\n";
    }

    private static QuestionFile.Question question(String id, String kind) {
        return new QuestionFile.Question(id, kind, "t", Map.of());
    }

    private static Evaluator.Result result(String id, boolean passed, String actual) {
        return new Evaluator.Result(id, "changes", "", passed, "[x]", actual, null);
    }

    /** 지어낸 제품. 정해 둔 답을 준다. */
    private static final class FakeProduct implements Product {

        final List<String> asked = new ArrayList<>();

        Map<Integer, Reply> steps() {
            Map<Integer, Reply> steps = new LinkedHashMap<>();
            steps.put(1, new Reply(200, Map.of("outcome", "ACCEPTED", "changes", List.of())));
            steps.put(2, new Reply(200, Map.of("outcome", "ACCEPTED", "changes", List.of(
                    Map.of("changeEventId", "c-1", "itemKey", "k-1", "kind", "CHANGED"),
                    Map.of("changeEventId", "c-2", "itemKey", "k-2|x\\|y", "kind", "REMOVED")))));
            return steps;
        }

        @Override
        public Reply relations(String entityId, LocalDate asOf) {
            this.asked.add("relations " + entityId + " " + asOf);
            if (entityId.endsWith("boom")) {
                throw new IllegalStateException("지어낸 고장");
            }
            if (entityId.endsWith("missing")) {
                return new Reply(404, Map.of("code", "ENTITY_NOT_FOUND", "detail", "없다"));
            }
            Map<String, Object> relation = new LinkedHashMap<>();
            relation.put("id", "r-1");
            relation.put("relationType", "relatesTo");
            relation.put("sourceEntityId", "p|TypeA|k-1");
            relation.put("targetEntityId", "p|TypeB|k-9");
            relation.put("validFrom", "2026-01-01");
            relation.put("validTo", null);
            relation.put("recordedAt", "2026-07-01T00:00:01Z");
            return new Reply(200, Map.of("asOf", asOf.toString(), "entityId", entityId, "relations", List.of(relation)));
        }

        @Override
        public Reply impactScope(String changeEventId) {
            this.asked.add("impact " + changeEventId);
            Map<String, Object> step = Map.of("stepNo", 1, "relationId", "r-1", "relationType", "relatesTo", "sourceEntityId",
                    "p|TypeA|k-1", "targetEntityId", "p|TypeB|k-9");
            Map<String, Object> target = Map.of("entityId", "p|TypeB|k-9", "entityType", "TypeB", "depth", 1, "path",
                    Map.of("startEntityId", "p|TypeA|k-1", "asOf", "2026-03-01", "steps", List.of(step)));
            return new Reply(200, Map.of("truncated", false, "total", 1, "countsByType",
                    List.of(Map.of("entityType", "TypeB", "count", 1)), "countsByDepth", List.of(Map.of("depth", 1, "count", 1)),
                    "targets", List.of(target)));
        }

        @Override
        public Reply actions(String packId, String changeEventId) {
            this.asked.add("actions " + packId + " " + changeEventId);
            Map<String, Object> action = Map.of("id", "a-1", "ruleNo", "0001", "priority", 1, "dueDate", "2026-03-15", "target",
                    Map.of("entityId", "p|TypeB|k-9", "entityType", "TypeB"), "basis",
                    Map.of("entity", Map.of("entityId", "p|TypeA|k-1", "entityType", "TypeA"), "inputSourceId", "src-a",
                            "editionNo", 2, "effectiveDate", "2026-03-01"));
            return new Reply(200, Map.of("total", 1, "actions", List.of(action)));
        }
    }
}
