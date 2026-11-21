package com.altumlab.tabnit.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * B-04 수용 기준: D8 형식의 규칙을 읽고 잘못된 규칙은 거부한다.
 *
 * <p>규칙 파일은 pack 이 읽어 저장하고 공개 API 로 내보낸다 (D8 2절, D2 AD-213).
 * 일반 시험을 먼저 둔다 (지어낸 이름의 작은 Pack). 그 위에 저장소에 들어 있는 Pack 의 규칙을 모두 읽어 본다.
 * 특정 Pack 의 규칙과 수는 시험에 적지 않는다.
 */
class PackRuleTest {

    private static final Instant LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final String PREFIXES = """
            @prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:    <http://www.w3.org/2002/07/owl#> .
            @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .
            @prefix tabnit: <urn:tabnit:vocab:> .
            """;

    /** 시험이 함께 쓰는 온톨로지. 유형 Base 아래에 Sub, 따로 Other. 속성은 형식마다 하나. */
    private static final String ONTOLOGY = """
            ex:Base a owl:Class ; rdfs:label "바탕" .
            ex:Sub a owl:Class ; rdfs:label "아래" ; rdfs:subClassOf ex:Base .
            ex:Other a owl:Class ; rdfs:label "다른 것" .
            ex:title a owl:DatatypeProperty ; rdfs:label "제목" ; rdfs:domain ex:Base ; rdfs:range xsd:string .
            ex:count a owl:DatatypeProperty ; rdfs:label "수" ; rdfs:domain ex:Base ; rdfs:range xsd:integer .
            ex:ratio a owl:DatatypeProperty ; rdfs:label "비율" ; rdfs:domain ex:Base ; rdfs:range xsd:decimal .
            ex:since a owl:DatatypeProperty ; rdfs:label "시작" ; rdfs:domain ex:Base ; rdfs:range xsd:date .
            ex:active a owl:DatatypeProperty ; rdfs:label "쓰는가" ; rdfs:domain ex:Base ; rdfs:range xsd:boolean .
            ex:otherName a owl:DatatypeProperty ; rdfs:label "다른 이름" ; rdfs:domain ex:Other ; rdfs:range xsd:string .
            """;

    /** 가장 작은 규칙: 반드시 적는 항목만 있다. */
    private static final String MINIMAL = """
            ex:r1 a tabnit:Rule ;
                tabnit:ruleNo "0001" ;
                rdfs:label "이름" ;
                tabnit:priority 1 ;
                tabnit:appliesTo tabnit:ImpactTarget ;
                tabnit:targetType ex:Base ;
                tabnit:actionText "할 일" ;
                tabnit:dueInDays 7 .
            """;

    private final PackLoader loader = new PackLoader(PackTestDatabase.dataSource());

    private final PackDefinitions definitions = new PackDefinitions(PackTestDatabase.dataSource());

    @TempDir
    Path temp;

    // ── 읽기와 저장 ─────────────────────────────────────────────

    @Test
    void 규칙을_읽어_저장하고_공개_API로_그대로_읽는다() throws IOException {
        Path dir = pack("r-read", "rules.ttl", """
                ex:r2 a tabnit:Rule ;
                    tabnit:ruleNo "0002" ;
                    rdfs:label "둘째 규칙" ;
                    tabnit:priority 3 ;
                    tabnit:whenChangeKind tabnit:Removed , tabnit:Changed ;
                    tabnit:appliesTo tabnit:ImpactTarget ;
                    tabnit:targetType ex:Base ;
                    tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:count ;
                                       tabnit:operator tabnit:GreaterThan ; tabnit:value 10 ] ;
                    tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:typeIs ex:Other ] ;
                    tabnit:actionText "해야 할 일의 문구" ;
                    tabnit:dueInDays 14 .
                """ + MINIMAL);

        PackLoadResult first = loader.load(dir, LOADED_AT);
        PackLoadResult again = loader.load(dir, LOADED_AT);
        PackDefinition found = definitions.find("r-read").orElseThrow();

        assertThat(first.outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
        assertThat(again.outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
        // 규칙 번호의 글자 코드 순이다.
        assertThat(found.rules()).extracting(RuleDefinition::ruleNo).containsExactly("0001", "0002");
        RuleDefinition minimal = found.rules().get(0);
        assertThat(minimal.label()).isEqualTo("이름");
        assertThat(minimal.priority()).isEqualTo(1);
        // 변동의 종류를 적지 않으면 모든 종류다.
        assertThat(minimal.changeKinds()).containsExactly(RuleChangeKind.ADDED, RuleChangeKind.CHANGED,
                RuleChangeKind.REMOVED);
        assertThat(minimal.appliesTo()).isEqualTo(RuleSubject.IMPACT_TARGET);
        assertThat(minimal.targetType()).isEqualTo("Base");
        assertThat(minimal.conditions()).isEmpty();
        assertThat(minimal.actionText()).isEqualTo("할 일");
        assertThat(minimal.dueInDays()).isEqualTo(7);
        RuleDefinition second = found.rules().get(1);
        assertThat(second.changeKinds()).containsExactly(RuleChangeKind.CHANGED, RuleChangeKind.REMOVED);
        // 조건은 정해진 차례다: 대상(변동 대상이 먼저), 유형을 보는 것, 속성, 비교, 값.
        assertThat(second.conditions()).containsExactly(
                new RuleCondition(RuleSubject.CHANGE_TARGET, "Other", null, null, List.of()),
                new RuleCondition(RuleSubject.IMPACT_TARGET, null, "count", RuleOperator.GREATER_THAN, List.of("10")));
    }

    @Test
    void 조건의_값은_속성의_형식에_맞는_정해진_표기로_읽는다() throws IOException {
        Path dir = pack("r-values", "rules.ttl", MINIMAL.replace("tabnit:actionText", """
                tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:count ;
                                   tabnit:operator tabnit:Equals ; tabnit:value 007 ] ;
                tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:ratio ;
                                   tabnit:operator tabnit:LessThanOrEqual ; tabnit:value 3.50 ] ;
                tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:ratio ;
                                   tabnit:operator tabnit:GreaterThanOrEqual ; tabnit:value 2 ] ;
                tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:since ;
                                   tabnit:operator tabnit:LessThan ; tabnit:value "2026-03-01"^^xsd:date ] ;
                tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:active ;
                                   tabnit:operator tabnit:NotEquals ; tabnit:value true ] ;
                tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:title ;
                                   tabnit:operator tabnit:In ; tabnit:value "나" , " 가 " , "나" , "다" ] ;
                tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:title ;
                                   tabnit:operator tabnit:HasValue ] ;
                tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:since ;
                                   tabnit:operator tabnit:HasNoValue ] ;
                tabnit:actionText"""));

        loader.load(dir, LOADED_AT);

        List<RuleCondition> conditions = definitions.find("r-values").orElseThrow().rules().get(0).conditions();
        RuleSubject impact = RuleSubject.IMPACT_TARGET;
        // 수는 값이 같으면 같은 표기다 (D2 AD-176). 글자는 그대로다. 목록의 값은 겹치지 않게 글자 코드 순으로 둔다.
        assertThat(conditions).containsExactly(
                new RuleCondition(impact, null, "active", RuleOperator.NOT_EQUALS, List.of("true")),
                new RuleCondition(impact, null, "count", RuleOperator.EQUALS, List.of("7")),
                new RuleCondition(impact, null, "ratio", RuleOperator.GREATER_THAN_OR_EQUAL, List.of("2")),
                new RuleCondition(impact, null, "ratio", RuleOperator.LESS_THAN_OR_EQUAL, List.of("3.5")),
                new RuleCondition(impact, null, "since", RuleOperator.LESS_THAN, List.of("2026-03-01")),
                new RuleCondition(impact, null, "since", RuleOperator.HAS_NO_VALUE, List.of()),
                new RuleCondition(impact, null, "title", RuleOperator.IN, List.of(" 가 ", "나", "다")),
                new RuleCondition(impact, null, "title", RuleOperator.HAS_VALUE, List.of()));
    }

    @Test
    void 규칙_폴더가_없거나_비어_있으면_규칙이_없는_Pack이다() throws IOException {
        Path none = pack("r-none", null, null);
        Path empty = pack("r-empty", null, null);
        Files.createDirectories(empty.resolve("rules"));
        // 이름이 .ttl 로 끝나지 않는 파일은 읽지 않는다.
        Files.writeString(empty.resolve("rules").resolve("읽지 않는 파일.txt"), "규칙이 아니다", StandardCharsets.UTF_8);

        loader.load(none, LOADED_AT);
        loader.load(empty, LOADED_AT);

        assertThat(definitions.find("r-none").orElseThrow().rules()).isEmpty();
        assertThat(definitions.find("r-empty").orElseThrow().rules()).isEmpty();
    }

    @Test
    void 규칙은_여러_파일에_나눠_적을_수_있고_규칙_번호는_Pack_안에서_겹치지_않는다() throws IOException {
        Path dir = pack("r-files", "a.ttl", MINIMAL);
        rules(dir, "b.ttl", MINIMAL.replace("ex:r1", "ex:r2").replace("\"0001\"", "\"0002\""));
        loader.load(dir, LOADED_AT);
        assertThat(definitions.find("r-files").orElseThrow().rules()).extracting(RuleDefinition::ruleNo)
            .containsExactly("0001", "0002");

        Path duplicate = pack("r-dup", "a.ttl", MINIMAL);
        rules(duplicate, "b.ttl", MINIMAL.replace("ex:r1", "ex:r2"));
        assertThat(problemsOf(duplicate)).singleElement().satisfies(p -> {
            assertThat(p.message()).contains("0001").contains("a.ttl").contains("b.ttl");
        });
    }

    @Test
    void 규칙_번호의_자릿수가_서로_다르면_거부한다() throws IOException {
        Path dir = pack("r-digits", "rules.ttl",
                MINIMAL + MINIMAL.replace("ex:r1", "ex:r2").replace("\"0001\"", "\"2\"")
                        + MINIMAL.replace("ex:r1", "ex:r3").replace("\"0001\"", "\"0010\""));

        // 규칙 번호는 글자 코드 순으로 견준다. 자릿수가 다르면 뜻한 차례가 아닐 수 있다 (D2 AD-103, AD-215).
        assertThat(problemsOf(dir)).singleElement().satisfies(p -> {
            assertThat(p.file()).isEqualTo("rules/");
            assertThat(p.message()).contains("\"2\"").contains("\"0001\"").contains("\"0010\"");
        });
    }

    @Test
    void 규칙_번호는_영문과_숫자와_붙임표로_50자까지_적는다() throws IOException {
        // 빈칸, 줄바꿈, 그 밖의 글자, 너무 긴 번호는 받지 않는다 (D2 AD-217).
        for (String no : List.of(" ", "0001 ", " 0001", "00 01", "a/b", "0001\\n", "가나다라", "0001_", "0.1",
                "A".repeat(51), "0".repeat(3000))) {
            Path dir = pack("r-number-bad", "rules.ttl", MINIMAL.replace("\"0001\"", "\"" + no + "\""));
            assertThat(problemsOf(dir)).as(no).singleElement().satisfies(p -> {
                assertThat(p.file()).isEqualTo("rules/rules.ttl");
                assertThat(p.item()).isEqualTo("r1");
                assertThat(p.message()).contains("tabnit:ruleNo");
            });
        }
        int accepted = 0;
        for (String no : List.of("R-0001", "safe-012", "A", "9".repeat(50))) {
            Path dir = pack("r-number-ok-" + (++accepted), "rules.ttl", MINIMAL.replace("\"0001\"", "\"" + no + "\""));
            loader.load(dir, LOADED_AT);
            assertThat(definitions.find("r-number-ok-" + accepted).orElseThrow().rules())
                .extracting(RuleDefinition::ruleNo)
                .containsExactly(no);
        }
    }

    @Test
    void 틀린_곳이_하나면_안쪽에_무엇이_겹쳐_있어도_문제는_하나다() throws IOException {
        String subject = "tabnit:subject tabnit:ImpactTarget ; ";
        for (String condition : List.of(
                // 값의 자리에 안쪽 정의.
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:Equals ; "
                        + "tabnit:value [ rdfs:label \"가\" ; rdfs:comment \"나\" ]",
                // 조건의 안쪽에 또 조건.
                subject + "tabnit:typeIs ex:Base ; tabnit:condition [ " + subject + "tabnit:typeIs ex:Other ; "
                        + "tabnit:condition [ " + subject + "tabnit:typeIs ex:Sub ] ]")) {
            Path dir = pack("r-nested", "rules.ttl",
                    MINIMAL.replace("tabnit:actionText", "tabnit:condition [ " + condition + " ] ;\n    tabnit:actionText"));
            assertThat(problemsOf(dir)).as(condition).singleElement()
                .satisfies(p -> assertThat(p.item()).isEqualTo("r1"));
        }
    }

    // ── 거부 ───────────────────────────────────────────────────

    @Test
    void 반드시_적는_항목이_없거나_값이_틀린_규칙은_거부한다() throws IOException {
        for (String[] change : new String[][] {
                // 규칙 번호, 이름, 문구.
                { "tabnit:ruleNo \"0001\" ;", "" }, { "tabnit:ruleNo \"0001\"", "tabnit:ruleNo \"\"" },
                { "tabnit:ruleNo \"0001\"", "tabnit:ruleNo 1" }, { "rdfs:label \"이름\" ;", "" },
                { "tabnit:actionText \"할 일\" ;", "" }, { "tabnit:actionText \"할 일\"", "tabnit:actionText \"가\" , \"나\"" },
                // 우선순위: 1 이상의 정수.
                { "tabnit:priority 1 ;", "" }, { "tabnit:priority 1", "tabnit:priority 0" },
                { "tabnit:priority 1", "tabnit:priority \"1\"" }, { "tabnit:priority 1", "tabnit:priority 1.5" },
                { "tabnit:priority 1", "tabnit:priority 1 , 2" },
                // 기한: 0 이상의 정수.
                { "tabnit:dueInDays 7", "tabnit:dueInDays -1" }, { "tabnit:dueInDays 7", "tabnit:dueInDays 7.0" },
                { "tabnit:actionText \"할 일\" ;\n    tabnit:dueInDays 7 .", "tabnit:actionText \"할 일\" ." },
                // 대상.
                { "tabnit:appliesTo tabnit:ImpactTarget ;", "" },
                { "tabnit:appliesTo tabnit:ImpactTarget", "tabnit:appliesTo tabnit:Everything" },
                { "tabnit:appliesTo tabnit:ImpactTarget", "tabnit:appliesTo tabnit:ImpactTarget , tabnit:ChangeTarget" },
                { "tabnit:targetType ex:Base ;", "" }, { "tabnit:targetType ex:Base", "tabnit:targetType ex:Nowhere" },
                { "tabnit:targetType ex:Base", "tabnit:targetType \"Base\"" },
                // 변동의 종류.
                { "tabnit:priority 1 ;", "tabnit:priority 1 ; tabnit:whenChangeKind tabnit:Renamed ;" },
                // D8 에 없는 문장.
                { "tabnit:priority 1 ;", "tabnit:priority 1 ; tabnit:owner \"누군가\" ;" },
                { "ex:r1 a tabnit:Rule", "ex:r1 a tabnit:Rule , tabnit:Mapping" } }) {
            assertThat(MINIMAL).as(change[0]).contains(change[0]);
            Path dir = pack("r-bad", "rules.ttl", MINIMAL.replace(change[0], change[1]));
            assertThat(problemsOf(dir)).as(change[1]).singleElement().satisfies(p -> {
                assertThat(p.file()).isEqualTo("rules/rules.ttl");
                assertThat(p.item()).isEqualTo("r1");
            });
        }
    }

    @Test
    void 조건이_틀린_규칙은_규칙_번호와_함께_거부한다() throws IOException {
        String subject = "tabnit:subject tabnit:ImpactTarget ; ";
        for (String condition : List.of(
                // 대상.
                "tabnit:property ex:count ; tabnit:operator tabnit:Equals ; tabnit:value 1",
                "tabnit:subject tabnit:Nothing ; tabnit:property ex:count ; tabnit:operator tabnit:Equals ; tabnit:value 1",
                // 없는 속성, 없는 유형.
                subject + "tabnit:property ex:nowhere ; tabnit:operator tabnit:Equals ; tabnit:value 1",
                subject + "tabnit:typeIs ex:Nowhere",
                // 비교.
                subject + "tabnit:property ex:count ; tabnit:value 1",
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:Roughly ; tabnit:value 1",
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:Equals , tabnit:NotEquals ; tabnit:value 1",
                // 크기를 견주는 비교는 정수, 소수, 날짜에만.
                subject + "tabnit:property ex:title ; tabnit:operator tabnit:GreaterThan ; tabnit:value \"가\"",
                subject + "tabnit:property ex:active ; tabnit:operator tabnit:LessThanOrEqual ; tabnit:value true",
                // 값의 수.
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:Equals",
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:Equals ; tabnit:value 1 , 2",
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:In",
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:HasValue ; tabnit:value 1",
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:HasNoValue ; tabnit:value 1",
                // 값이 속성의 형식과 맞지 않는다.
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:Equals ; tabnit:value \"1\"",
                subject + "tabnit:property ex:count ; tabnit:operator tabnit:Equals ; tabnit:value 1.5",
                subject + "tabnit:property ex:ratio ; tabnit:operator tabnit:Equals ; tabnit:value 1e3",
                subject + "tabnit:property ex:ratio ; tabnit:operator tabnit:Equals ; tabnit:value \"0.5\"",
                subject + "tabnit:property ex:active ; tabnit:operator tabnit:Equals ; tabnit:value 1",
                subject + "tabnit:property ex:active ; tabnit:operator tabnit:Equals ; tabnit:value \"true\"",
                subject + "tabnit:property ex:since ; tabnit:operator tabnit:Equals ; tabnit:value \"2026-03-01\"",
                subject + "tabnit:property ex:title ; tabnit:operator tabnit:Equals ; tabnit:value 5",
                subject + "tabnit:property ex:title ; tabnit:operator tabnit:Equals ; tabnit:value \"가\"@ko",
                subject + "tabnit:property ex:title ; tabnit:operator tabnit:In ; tabnit:value \"가\" , 5",
                // 유형을 보는 것과 속성을 보는 것을 한 조건에 섞었다. 아무것도 보지 않는 조건.
                subject + "tabnit:typeIs ex:Base ; tabnit:property ex:count ; tabnit:operator tabnit:Equals ; tabnit:value 1",
                subject + "tabnit:typeIs ex:Base ; tabnit:value 1",
                subject + "tabnit:typeIs ex:Base , ex:Other",
                "tabnit:subject tabnit:ImpactTarget",
                // D8 에 없는 문장.
                subject + "tabnit:typeIs ex:Base ; tabnit:note \"메모\"")) {
            Path dir = pack("r-condition", "rules.ttl",
                    MINIMAL.replace("tabnit:actionText", "tabnit:condition [ " + condition + " ] ;\n    tabnit:actionText"));
            assertThat(problemsOf(dir)).as(condition).singleElement().satisfies(p -> {
                assertThat(p.file()).isEqualTo("rules/rules.ttl");
                assertThat(p.item()).isEqualTo("r1");
                assertThat(p.message()).contains("0001");
            });
        }
        // 없는 날짜와 모양이 틀린 날짜는 Turtle 을 읽는 단계에서 걸린다. 어느 줄인지 알리며 거부한다.
        for (String value : List.of("\"2026-02-30\"^^xsd:date", "\"2026-3-1\"^^xsd:date")) {
            Path dir = pack("r-condition-date", "rules.ttl",
                    MINIMAL.replace("tabnit:actionText", "tabnit:condition [ " + subject
                            + "tabnit:property ex:since ; tabnit:operator tabnit:Equals ; tabnit:value " + value
                            + " ] ;\n    tabnit:actionText"));
            assertThat(problemsOf(dir)).as(value).singleElement()
                .satisfies(p -> assertThat(p.file()).isEqualTo("rules/rules.ttl"));
        }
        // 조건은 안쪽 정의로 적는다.
        Path named = pack("r-condition-named", "rules.ttl",
                MINIMAL.replace("tabnit:actionText", "tabnit:condition ex:c1 ;\n    tabnit:actionText"));
        assertThat(problemsOf(named)).singleElement().satisfies(p -> assertThat(p.item()).isEqualTo("r1"));
    }

    @Test
    void 대상이_변동_대상인_규칙은_영향_대상을_보는_조건을_가질_수_없다() throws IOException {
        String changeTargetRule = MINIMAL.replace("tabnit:appliesTo tabnit:ImpactTarget",
                "tabnit:appliesTo tabnit:ChangeTarget");
        Path allowed = pack("r-change-ok", "rules.ttl", changeTargetRule.replace("tabnit:actionText",
                "tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:property ex:count ; "
                        + "tabnit:operator tabnit:LessThanOrEqual ; tabnit:value 30 ] ;\n    tabnit:actionText"));
        Path rejected = pack("r-change-bad", "rules.ttl", changeTargetRule.replace("tabnit:actionText",
                "tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:typeIs ex:Base ] ;\n    tabnit:actionText"));

        loader.load(allowed, LOADED_AT);
        assertThat(definitions.find("r-change-ok").orElseThrow().rules().get(0).appliesTo())
            .isEqualTo(RuleSubject.CHANGE_TARGET);
        // 대상이 변동 대상 자신이면 영향 대상이 없다 (D2 AD-118).
        assertThat(problemsOf(rejected)).singleElement()
            .satisfies(p -> assertThat(p.message()).contains("0001").contains("tabnit:ImpactTarget"));
    }

    @Test
    void 이름이_없거나_이_Pack의_주소가_아닌_규칙과_어느_규칙에도_속하지_않은_문장은_거부한다() throws IOException {
        String body = MINIMAL.substring(MINIMAL.indexOf("a tabnit:Rule"));
        Path unnamed = pack("r-unnamed", "rules.ttl", "[] " + body);
        Path foreign = pack("r-foreign", "rules.ttl", "<urn:elsewhere:r1> " + body);
        Path stray = pack("r-stray", "rules.ttl", MINIMAL + "ex:other rdfs:label \"규칙이 아닌 것\" .\n");
        Path syntax = pack("r-syntax", "rules.ttl", MINIMAL + "이것은 Turtle 이 아니다\n");

        assertThat(problemsOf(unnamed)).singleElement().satisfies(p -> assertThat(p.item()).isEqualTo("(이름 없음)"));
        assertThat(problemsOf(foreign)).singleElement()
            .satisfies(p -> assertThat(p.item()).isEqualTo("urn:elsewhere:r1"));
        assertThat(problemsOf(stray)).singleElement().satisfies(p -> assertThat(p.item()).isEqualTo("other"));
        assertThat(problemsOf(syntax)).singleElement()
            .satisfies(p -> assertThat(p.file()).isEqualTo("rules/rules.ttl"));
    }

    @Test
    void 문제는_한_번에_모두_알리고_정해진_차례로_나온다() throws IOException {
        Path dir = pack("r-all", "b.ttl", MINIMAL.replace("tabnit:priority 1", "tabnit:priority 0")
            .replace("tabnit:dueInDays 7", "tabnit:dueInDays -1"));
        rules(dir, "a.ttl", MINIMAL.replace("ex:r1", "ex:r9").replace("\"0001\"", "\"0009\"")
            .replace("tabnit:targetType ex:Base", "tabnit:targetType ex:Nowhere"));

        List<PackProblem> problems = problemsOf(dir);

        assertThat(problems).extracting(PackProblem::file).containsExactly("rules/a.ttl", "rules/b.ttl", "rules/b.ttl");
        assertThat(problemsOf(dir)).isEqualTo(problems);
    }

    // ── 고친 Pack 을 다시 적재할 때 ───────────────────────────────────

    @Test
    void 규칙은_더하고_고치고_빼도_다시_적재된다() throws IOException {
        Path dir = pack("r-change", "rules.ttl", MINIMAL);
        loader.load(dir, LOADED_AT);
        String second = MINIMAL.replace("ex:r1", "ex:r2").replace("\"0001\"", "\"0002\"");

        // 더한다.
        rules(dir, "rules.ttl", MINIMAL + second);
        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
        assertThat(definitions.find("r-change").orElseThrow().rules()).hasSize(2);
        // 고친다: 문구, 우선순위, 조건.
        rules(dir, "rules.ttl", MINIMAL.replace("\"할 일\"", "\"고친 할 일\"").replace("tabnit:priority 1", "tabnit:priority 5")
            .replace("tabnit:actionText",
                    "tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:typeIs ex:Sub ] ;\n    tabnit:actionText")
                + second);
        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
        RuleDefinition changed = definitions.find("r-change").orElseThrow().rules().get(0);
        assertThat(changed.actionText()).isEqualTo("고친 할 일");
        assertThat(changed.priority()).isEqualTo(5);
        assertThat(changed.conditions()).hasSize(1);
        // 뺀다.
        rules(dir, "rules.ttl", second);
        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
        assertThat(definitions.find("r-change").orElseThrow().rules()).extracting(RuleDefinition::ruleNo)
            .containsExactly("0002");
    }

    // ── 같은 입력에는 같은 결과, 큰 입력 ────────────────────────────────

    @Test
    void 적는_차례가_달라도_같은_규칙이_된다() throws IOException {
        String conditionA = "tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:count ; "
                + "tabnit:operator tabnit:In ; tabnit:value 3 , 1 , 2 ] ;\n    ";
        String conditionB = "tabnit:condition [ tabnit:value 2 , 3 , 1 ; tabnit:operator tabnit:In ; "
                + "tabnit:property ex:count ; tabnit:subject tabnit:ImpactTarget ] ;\n    ";
        String typeCondition = "tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:typeIs ex:Other ] ;\n    ";
        String second = MINIMAL.replace("ex:r1", "ex:r2").replace("\"0001\"", "\"0002\"");
        Path one = pack("r-order-1", "rules.ttl",
                MINIMAL.replace("tabnit:actionText", conditionA + typeCondition + "tabnit:actionText") + second);
        Path two = pack("r-order-2", "rules.ttl",
                second + MINIMAL.replace("tabnit:actionText", typeCondition + conditionB + "tabnit:actionText"));

        loader.load(one, LOADED_AT);
        loader.load(two, LOADED_AT);

        assertThat(definitions.find("r-order-1").orElseThrow().rules())
            .isEqualTo(definitions.find("r-order-2").orElseThrow().rules());
    }

    @Test
    void 규칙이_많아도_모두_읽는다() throws IOException {
        StringBuilder body = new StringBuilder();
        int count = 400;
        for (int i = 0; i < count; i++) {
            int n = (i * 7) % count;
            body.append(MINIMAL.replace("ex:r1", "ex:rule-" + n)
                .replace("\"0001\"", String.format("\"%04d\"", n))
                .replace("tabnit:actionText",
                        "tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:count ; "
                                + "tabnit:operator tabnit:GreaterThan ; tabnit:value " + n + " ] ;\n    tabnit:actionText"));
        }
        Path dir = pack("r-many", "rules.ttl", body.toString());

        loader.load(dir, LOADED_AT);

        List<RuleDefinition> rules = definitions.find("r-many").orElseThrow().rules();
        assertThat(rules).hasSize(count);
        assertThat(rules.get(0).ruleNo()).isEqualTo("0000");
        assertThat(rules.get(count - 1).ruleNo()).isEqualTo("0399");
        assertThat(rules.get(count - 1).conditions().get(0).values()).containsExactly("399");
    }

    // ── 저장소에 들어 있는 Pack ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("repositoryPacks")
    void 저장소의_Pack의_규칙이_코드를_고치지_않고_읽힌다(Path dir) {
        String packId = dir.getFileName().toString();

        loader.load(dir, LOADED_AT);
        PackLoadResult again = loader.load(dir, LOADED_AT);
        PackDefinition found = definitions.find(packId).orElseThrow();

        // 시험에 Pack 의 규칙과 수를 적지 않는다.
        assertThat(again.outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
        assertThat(found.rules()).isNotEmpty();
        List<String> typeNames = found.entityTypes().stream().map(EntityTypeDefinition::name).toList();
        List<String> attributeNames = found.attributes().stream().map(AttributeDefinition::name).toList();
        assertThat(found.rules()).allSatisfy(rule -> {
            assertThat(typeNames).contains(rule.targetType());
            assertThat(rule.changeKinds()).isNotEmpty();
            assertThat(rule.conditions()).allSatisfy(condition -> {
                if (condition.typeIs() != null) {
                    assertThat(typeNames).contains(condition.typeIs());
                }
                else {
                    assertThat(attributeNames).contains(condition.attributeName());
                }
            });
        });
    }

    static Stream<Path> repositoryPacks() throws IOException {
        // 작업 폴더는 server/pack 이다. 규칙 폴더가 있는 Pack 만 고른다.
        try (Stream<Path> dirs = Files.list(Path.of("../../packs"))) {
            return dirs.filter(d -> Files.isDirectory(d.resolve("rules")) && Files.exists(d.resolve("pack.ttl")))
                .sorted()
                .toList()
                .stream();
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 지어낸 작은 Pack 폴더를 만든다. 규칙 파일의 이름이 null 이면 규칙 폴더를 만들지 않는다. */
    private Path pack(String packId, String ruleFile, String ruleBody) throws IOException {
        Path dir = Files.createTempDirectory(temp, "p").resolve(packId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pack.ttl"),
                PREFIXES + "<urn:tabnit:pack:" + packId + ":> a tabnit:Pack ; tabnit:packId \"" + packId
                        + "\" ; rdfs:label \"이름표\" ; tabnit:defaultMaxDepth 1 .\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("ontology.ttl"),
                PREFIXES + "@prefix ex: <urn:tabnit:pack:" + packId + ":> .\n" + ONTOLOGY, StandardCharsets.UTF_8);
        if (ruleFile != null) {
            rules(dir, ruleFile, ruleBody);
        }
        return dir;
    }

    private static void rules(Path dir, String file, String body) throws IOException {
        Files.createDirectories(dir.resolve("rules"));
        String packId = dir.getFileName().toString();
        Files.writeString(dir.resolve("rules").resolve(file),
                PREFIXES + "@prefix ex: <urn:tabnit:pack:" + packId + ":> .\n" + body, StandardCharsets.UTF_8);
    }

    private List<PackProblem> problemsOf(Path dir) {
        PackInvalidException e = catchThrowableOfType(PackInvalidException.class, () -> loader.load(dir, LOADED_AT));
        assertThat(e).as("거부돼야 하는 Pack 이 적재됐다: " + dir).isNotNull();
        return e.problems();
    }
}
