package com.altumlab.tabnit.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * B-17 가운데 pack 의 일: 넘겨받은 자료에 Pack 의 제약(SHACL)을 걸어 위반을 돌려준다 (D6 6절, D2 AD-389 ~ AD-392, AD-395).
 *
 * <p>저장소를 쓰지 않는다: 자료는 부르는 쪽이 넘긴다 (D2 AD-391). 지어낸 이름의 작은 Pack 을 쓴다.
 */
class ConstraintCheckerTest {

    private static final String PREFIXES = """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex:  <urn:tabnit:pack:cc:> .
            """;

    /** 제목이 있어야 하고, 수는 1 이상이고, Base 는 Other 를 셋까지만 잇는다. */
    private static final String BASIC = """
            ex:BaseShape a sh:NodeShape ;
                sh:targetClass ex:Base ;
                sh:property [ sh:path ex:title ; sh:minCount 1 ; sh:datatype xsd:string ] ;
                sh:property [ sh:path ex:count ; sh:datatype xsd:integer ; sh:minInclusive 1 ] ;
                sh:property [ sh:path ex:links ; sh:class ex:Other ; sh:maxCount 2 ] .
            """;

    @Test
    void 제약이_없거나_자료가_없으면_위반이_없다() {
        ConstraintData data = data(List.of(entity("B-1", "Base")), List.of(), List.of());

        assertThat(ConstraintChecker.check(pack(""), data)).isEmpty();
        assertThat(ConstraintChecker.check(pack(PREFIXES), data)).isEmpty();
        assertThat(ConstraintChecker.check(pack(PREFIXES + BASIC), data(List.of(), List.of(), List.of()))).isEmpty();
    }

    @Test
    void 제약을_지킨_자료에는_위반이_없다() {
        ConstraintData data = data(List.of(entity("B-1", "Base"), entity("O-1", "Other")),
                List.of(link("B-1", "O-1", "links")),
                List.of(value("B-1", "title", "제목 하나"), value("B-1", "count", "3")));

        assertThat(ConstraintChecker.check(pack(PREFIXES + BASIC), data)).isEmpty();
    }

    @Test
    void 값이_없거나_범위를_벗어나거나_꼴이_틀리면_어긴_개체와_속성과_값과_제약의_종류를_알린다() {
        ConstraintData data = data(
                // B-1 은 제목이 없고 수가 0 이다. B-2 는 수가 숫자가 아니다. B-3 은 값도 관계도 없다: 검사하지 않는다 (D2 AD-397).
                List.of(entity("B-1", "Base"), entity("B-2", "Base"), entity("B-3", "Base")), List.of(),
                List.of(value("B-1", "count", "0"), value("B-2", "title", "제목"), value("B-2", "count", "많음")));

        List<ConstraintViolation> found = ConstraintChecker.check(pack(PREFIXES + BASIC), data);

        assertThat(found)
            .extracting(ConstraintViolation::entityId, ConstraintViolation::pathKind, ConstraintViolation::pathName,
                    ConstraintViolation::constraintKind, ConstraintViolation::value)
            .containsExactly(tuple(id("B-1"), ConstraintViolation.PathKind.ATTRIBUTE, "count", "MinInclusive", "0"),
                    tuple(id("B-1"), ConstraintViolation.PathKind.ATTRIBUTE, "title", "MinCount", null),
                    tuple(id("B-2"), ConstraintViolation.PathKind.ATTRIBUTE, "count", "Datatype", "많음"),
                    tuple(id("B-2"), ConstraintViolation.PathKind.ATTRIBUTE, "count", "MinInclusive", "많음"));
        assertThat(found).allSatisfy(violation -> {
            assertThat(violation.severity()).isEqualTo(ConstraintViolation.Severity.VIOLATION);
            assertThat(violation.constraintName()).isEqualTo("BaseShape");
            assertThat(violation.valueEntityId()).isNull();
            // 문구는 한국어이고, 이름 없는 정의의 이름이나 읽는 라이브러리의 영어 문구가 섞이지 않는다.
            assertThat(violation.message()).isNotBlank().doesNotContain("_:").doesNotContain("urn:tabnit");
            // 이 자료의 이름표와 값은 한글과 숫자뿐이다. 문구에 영어 낱말이 있으면 검사하는 쪽의 문구가 샌 것이다
            // ("Pack" 은 제품이 쓰는 낱말이다).
            assertThat(violation.message().replace("Pack", "")).doesNotContainPattern("[A-Za-z]{3,}");
        });
        // 속성의 이름표와 기대하는 값이 문구에 나온다.
        assertThat(found.get(0).message()).startsWith("속성 '수'의 값은 1 이상이어야 합니다. 지금 값: 0.");
        assertThat(found.get(1).message()).startsWith("속성 '제목'은(는) 1개 이상 있어야 합니다.");
        assertThat(found.get(2).message()).startsWith("속성 '수'의 값이 정수 꼴이 아닙니다. 지금 값: 많음.");
        // 무엇을 하면 되는지로 끝난다.
        assertThat(found).allSatisfy(violation -> assertThat(violation.message()).endsWith("다시 적재하세요."));
    }

    @Test
    void 하위_유형의_개체에도_상위_유형의_제약이_걸린다() {
        ConstraintData data = data(List.of(entity("S-1", "Sub")), List.of(),
                List.of(new ConstraintData.Value(id("S-1", "Sub"), "count", "5")));

        assertThat(ConstraintChecker.check(pack(PREFIXES + BASIC), data))
            .extracting(ConstraintViolation::entityId, ConstraintViolation::constraintKind)
            .containsExactly(tuple(id("S-1", "Sub"), "MinCount"));
    }

    @Test
    void 관계의_제약은_가리키는_개체와_함께_알린다() {
        ConstraintData data = data(
                List.of(entity("B-1", "Base"), entity("B-2", "Base"), entity("O-1", "Other"), entity("O-2", "Other"),
                        entity("O-3", "Other")),
                // B-1 은 Other 가 아닌 것(B-2)을 잇는다. B-2 는 셋을 잇는다 (둘까지다).
                List.of(link("B-1", "B-2", "links"), link("B-2", "O-1", "links"), link("B-2", "O-2", "links"),
                        link("B-2", "O-3", "links")),
                List.of(value("B-1", "title", "하나"), value("B-2", "title", "둘")));

        List<ConstraintViolation> found = ConstraintChecker.check(pack(PREFIXES + BASIC), data);

        assertThat(found)
            .extracting(ConstraintViolation::entityId, ConstraintViolation::pathKind, ConstraintViolation::pathName,
                    ConstraintViolation::constraintKind, ConstraintViolation::valueEntityId)
            .containsExactly(tuple(id("B-1"), ConstraintViolation.PathKind.RELATION, "links", "Class", id("B-2")),
                    tuple(id("B-2"), ConstraintViolation.PathKind.RELATION, "links", "MaxCount", null));
        assertThat(found.get(0).message()).contains("잇는다").contains("다른 것");
        assertThat(found.get(1).message()).contains("잇는다").contains("2");
    }

    @Test
    void 심각도와_제약에_적은_문구를_그대로_전하고_심각한_것부터_놓는다() {
        String constraints = PREFIXES + """
                ex:Info a sh:NodeShape ; sh:targetClass ex:Base ;
                    sh:property [ sh:path ex:count ; sh:maxInclusive 10 ; sh:severity sh:Info ] .
                ex:Warn a sh:NodeShape ; sh:targetClass ex:Base ;
                    sh:property [ sh:path ex:title ; sh:maxLength 3 ; sh:severity sh:Warning ;
                                  sh:message "제목은 세 글자까지 적습니다" ] .
                # 심각도는 그것을 적은 제약의 것이다 (SHACL 의 규칙). 바깥 제약에 적은 것은 안쪽 속성의 제약에 내려가지 않는다.
                ex:Outer a sh:NodeShape ; sh:targetClass ex:Base ; sh:severity sh:Info ;
                    sh:property [ sh:path ex:count ; sh:maxInclusive 50 ] .
                ex:Hard a sh:NodeShape ; sh:targetClass ex:Base ;
                    sh:property [ sh:path ex:title ; sh:pattern "^가" ] .
                """;
        ConstraintData data = data(List.of(entity("B-1", "Base")), List.of(),
                List.of(value("B-1", "title", "나다라마"), value("B-1", "count", "99")));

        List<ConstraintViolation> found = ConstraintChecker.check(pack(constraints), data);

        assertThat(found).extracting(ConstraintViolation::severity, ConstraintViolation::constraintKind,
                ConstraintViolation::constraintName)
            .containsExactly(tuple(ConstraintViolation.Severity.VIOLATION, "MaxInclusive", "Outer"),
                    tuple(ConstraintViolation.Severity.VIOLATION, "Pattern", "Hard"),
                    tuple(ConstraintViolation.Severity.WARNING, "MaxLength", "Warn"),
                    tuple(ConstraintViolation.Severity.INFO, "MaxInclusive", "Info"));
        assertThat(found.get(2).message()).isEqualTo("제목은 세 글자까지 적습니다");
        assertThat(found.get(1).message()).contains("제목").contains("^가").contains("나다라마");
    }

    @Test
    void 한_제약에_같은_종류를_값_여럿으로_적어도_위반을_하나로_합치지_않는다() {
        String constraints = PREFIXES + """
                ex:Two a sh:NodeShape ; sh:targetClass ex:Base ;
                    sh:property [ sh:path ex:title ; sh:pattern "^a" ; sh:pattern "^b" ] ;
                    sh:property [ sh:path ex:count ; sh:minInclusive 0 ; sh:minInclusive 5 ] .
                """;
        ConstraintData data = data(List.of(entity("B-1", "Base")), List.of(),
                List.of(value("B-1", "title", "xyz"), value("B-1", "count", "-1")));

        List<ConstraintViolation> found = ConstraintChecker.check(pack(constraints), data);

        // 꼴 둘과 범위 둘을 모두 어긴다: 넷이다. 글자가 같아 보여도 둘씩이다.
        assertThat(found).extracting(ConstraintViolation::pathName, ConstraintViolation::constraintKind,
                ConstraintViolation::value)
            .containsExactly(tuple("count", "MinInclusive", "-1"), tuple("count", "MinInclusive", "-1"),
                    tuple("title", "Pattern", "xyz"), tuple("title", "Pattern", "xyz"));
        // 문구에는 적은 값이 모두 나온다.
        assertThat(found.get(2).message()).contains("^a").contains("^b");
        assertThat(found.get(0).message()).contains("0").contains("5");
    }

    @Test
    void 경로의_식은_종류마다_다르게_적는다() {
        String constraints = PREFIXES + """
                ex:Paths a sh:NodeShape ; sh:targetClass ex:Base ;
                    sh:property [ sh:path [ sh:oneOrMorePath ex:links ] ; sh:minCount 1 ] ;
                    sh:property [ sh:path [ sh:zeroOrOnePath ex:links ] ; sh:minCount 5 ] ;
                    sh:property [ sh:path [ sh:zeroOrMorePath ex:links ] ; sh:minCount 5 ] ;
                    sh:property [ sh:path ( ex:links ex:otherName ) ; sh:minCount 1 ] ;
                    sh:property [ sh:path [ sh:alternativePath ( ex:title ex:count ) ] ; sh:minCount 1 ] .
                """;
        ConstraintData data = data(List.of(entity("B-1", "Base")), List.of(), List.of(value("B-1", "otherName", "제약이 보지 않는 값")));

        assertThat(ConstraintChecker.check(pack(constraints), data))
            .extracting(ConstraintViolation::pathKind, ConstraintViolation::pathName)
            .containsExactlyInAnyOrder(tuple(ConstraintViolation.PathKind.EXPRESSION, "(links)+"),
                    tuple(ConstraintViolation.PathKind.EXPRESSION, "(links)?"),
                    tuple(ConstraintViolation.PathKind.EXPRESSION, "(links)*"),
                    tuple(ConstraintViolation.PathKind.EXPRESSION, "links/otherName"),
                    tuple(ConstraintViolation.PathKind.EXPRESSION, "(title|count)"));
    }

    @Test
    void 닫힌_제약은_제품이_붙인_유형을_정해지지_않은_속성으로_알리지_않는다() {
        String constraints = PREFIXES + """
                ex:Closed a sh:NodeShape ; sh:targetClass ex:Base ; sh:closed true ;
                    sh:property [ sh:path ex:title ] .
                """;
        ConstraintData data = data(List.of(entity("B-1", "Base")), List.of(),
                List.of(value("B-1", "title", "제목"), value("B-1", "count", "3")));

        // 정해지지 않은 것은 수 하나다. 개체의 유형은 알리지 않는다.
        assertThat(ConstraintChecker.check(pack(constraints), data))
            .extracting(ConstraintViolation::pathKind, ConstraintViolation::pathName, ConstraintViolation::constraintKind)
            .containsExactly(tuple(ConstraintViolation.PathKind.ATTRIBUTE, "count", "Closed"));
    }

    @Test
    void 문구가_여러_언어이면_한국어를_고르고_개체_자신에_건_제약은_가리키는_개체를_두지_않는다() {
        String constraints = PREFIXES + """
                ex:Lang a sh:NodeShape ; sh:targetClass ex:Base ;
                    sh:property [ sh:path ex:title ; sh:minCount 1 ;
                                  sh:message "A title is required"@en , "제목을 적으세요"@ko , "zz" ] .
                ex:Self a sh:NodeShape ; sh:targetClass ex:Base ; sh:not [ sh:class ex:Base ] .
                ex:Pair a sh:NodeShape ; sh:targetClass ex:Base ;
                    sh:property [ sh:path ex:title ; sh:equals ex:count ] .
                """;
        ConstraintData data = data(List.of(entity("B-1", "Base")), List.of(), List.of(value("B-1", "count", "3")));

        List<ConstraintViolation> found = ConstraintChecker.check(pack(constraints), data);

        assertThat(found).filteredOn(v -> "Lang".equals(v.constraintName())).singleElement()
            .satisfies(v -> assertThat(v.message()).isEqualTo("제목을 적으세요"));
        assertThat(found).filteredOn(v -> "Self".equals(v.constraintName())).singleElement().satisfies(v -> {
            assertThat(v.pathKind()).isEqualTo(ConstraintViolation.PathKind.NONE);
            assertThat(v.valueEntityId()).isNull();
            assertThat(v.message()).startsWith("이 개체이(가) 맞으면 안 되는 조건에 맞습니다.").doesNotContain("가리키는");
        });
        // 견주는 상대 속성도 이름표로 적는다.
        assertThat(found).filteredOn(v -> "Pair".equals(v.constraintName())).isNotEmpty()
            .allSatisfy(v -> assertThat(v.message()).contains("'제목'").contains("수").doesNotContain("count"));
    }

    @Test
    void 값도_관계도_없는_개체는_검사하지_않고_관계의_끝으로만_있는_개체는_검사한다() {
        String constraints = PREFIXES + BASIC + """
                ex:OtherShape a sh:NodeShape ; sh:targetClass ex:Other ;
                    sh:property [ sh:path ex:otherName ; sh:minCount 1 ] ;
                    # 다른 개체를 보는 제약: 자기를 잇는 것이 하나 이상 있어야 한다. 가려진 개체는 여기에도 걸리지 않는다.
                    sh:property [ sh:path [ sh:inversePath ex:links ] ; sh:minCount 1 ; sh:class ex:Base ] .
                """;
        ConstraintData data = data(
                // B-gone: 행이 없어져 값이 끝난 개체. O-gone: 아직 들어오지 않은 개체. 둘 다 그 날짜에 유효한 것이 없다.
                // B-1 은 값이 있고, O-1 은 관계의 도착 끝으로만, B-2 는 관계의 출발 끝으로만 있다.
                List.of(entity("B-gone", "Base"), entity("O-gone", "Other"), entity("B-1", "Base"), entity("B-2", "Base"),
                        entity("O-1", "Other")),
                List.of(link("B-2", "O-1", "links")), List.of(value("B-1", "count", "3")));

        List<ConstraintViolation> found = ConstraintChecker.check(pack(constraints), data);

        assertThat(found).extracting(ConstraintViolation::entityId, ConstraintViolation::pathName,
                ConstraintViolation::constraintKind)
            .containsExactly(tuple(id("B-1"), "title", "MinCount"), tuple(id("B-2"), "title", "MinCount"),
                    tuple(id("O-1"), "otherName", "MinCount"));
        // 개체가 모두 비어 있으면 위반이 없다.
        assertThat(ConstraintChecker.check(pack(constraints),
                data(List.of(entity("B-gone", "Base"), entity("O-gone", "Other")), List.of(), List.of()))).isEmpty();
    }

    @Test
    void 자료를_넘기는_차례가_달라도_결과와_순서가_같다() {
        List<ConstraintData.Entity> entities = new ArrayList<>();
        List<ConstraintData.Value> values = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            entities.add(entity("B-" + i, "Base"));
            if (i % 3 != 0) {
                values.add(value("B-" + i, "count", String.valueOf(i % 4 - 1)));
            }
        }
        PackDefinition pack = pack(PREFIXES + BASIC);
        List<ConstraintViolation> first = ConstraintChecker.check(pack, data(entities, List.of(), values));

        Collections.reverse(entities);
        Collections.reverse(values);
        List<ConstraintViolation> second = ConstraintChecker.check(pack, data(entities, List.of(), values));

        assertThat(first).isNotEmpty();
        assertThat(second).isEqualTo(first);
        // 개체의 식별자의 글자 코드 순이다 (B-10 이 B-2 보다 앞선다).
        List<String> ids = first.stream().map(ConstraintViolation::entityId).distinct().toList();
        assertThat(ids).isSortedAccordingTo(PackOrder.STRINGS);
        assertThat(ids.indexOf(id("B-10"))).isLessThan(ids.indexOf(id("B-2")));
    }

    @Test
    void 식별자와_값에_특수한_글자가_들어도_그대로_돌려준다() {
        String odd = "키 | \\ % # < > \" ' 😀";
        ConstraintData data = data(List.of(entity(odd, "Base")), List.of(), List.of(value(odd, "count", "-5")));

        List<ConstraintViolation> found = ConstraintChecker.check(pack(PREFIXES + BASIC), data);

        assertThat(found).extracting(ConstraintViolation::entityId, ConstraintViolation::constraintKind,
                ConstraintViolation::value)
            .containsExactly(tuple(id(odd), "MinInclusive", "-5"), tuple(id(odd), "MinCount", null));
    }

    @Test
    void 경로가_식이거나_제약이_개체_자신에_걸려도_문구와_종류가_나온다() {
        String constraints = PREFIXES + """
                ex:OtherShape a sh:NodeShape ;
                    sh:targetClass ex:Other ;
                    sh:property [ sh:path [ sh:inversePath ex:links ] ; sh:minCount 1 ] ;
                    sh:or ( [ sh:path ex:otherName ; sh:minCount 1 ] [ sh:path [ sh:inversePath ex:links ] ; sh:minCount 2 ] ) .
                """;
        // 제약이 보지 않는 값 하나를 둔다: 값도 관계도 없는 개체는 검사하지 않는다.
        ConstraintData data = data(List.of(entity("O-1", "Other")), List.of(), List.of(value("O-1", "count", "1")));

        List<ConstraintViolation> found = ConstraintChecker.check(pack(constraints), data);

        assertThat(found).extracting(ConstraintViolation::pathKind, ConstraintViolation::constraintKind)
            .containsExactlyInAnyOrder(tuple(ConstraintViolation.PathKind.EXPRESSION, "MinCount"),
                    tuple(ConstraintViolation.PathKind.NONE, "Or"));
        assertThat(found).allSatisfy(violation -> {
            assertThat(violation.entityId()).isEqualTo(id("O-1", "Other"));
            assertThat(violation.message()).isNotBlank().doesNotContain("_:");
        });
        // 두 번 걸어도 같다 (이름 없는 정의의 이름이 문구나 순서에 닿지 않는다).
        assertThat(ConstraintChecker.check(pack(constraints), data)).isEqualTo(found);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private static PackDefinition pack(String constraints) {
        return new PackDefinition("cc", "이름표", 1, Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Sub", "아래", List.of("Base"), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING),
                        new AttributeDefinition("count", "수", "Base", ValueFormat.INTEGER),
                        new AttributeDefinition("otherName", "다른 이름", "Other", ValueFormat.STRING)),
                List.of(), List.of(), List.of(), constraints);
    }

    private static ConstraintData data(List<ConstraintData.Entity> entities, List<ConstraintData.Link> links,
            List<ConstraintData.Value> values) {
        return new ConstraintData(entities, links, values);
    }

    /** 개체의 식별자는 부르는 쪽의 것이다. 검사기는 풀어 읽지 않는다. 여기서는 "유형|키" 꼴로 지어낸다. */
    private static String id(String key) {
        return id(key, "Base".equals(typeOf(key)) ? "Base" : "Other");
    }

    private static String id(String key, String type) {
        return type + "|" + key;
    }

    private static String typeOf(String key) {
        return key.startsWith("O-") ? "Other" : "Base";
    }

    private static ConstraintData.Entity entity(String key, String type) {
        return new ConstraintData.Entity(id(key, type), type);
    }

    private static ConstraintData.Link link(String source, String target, String type) {
        return new ConstraintData.Link(id(source), id(target), type);
    }

    private static ConstraintData.Value value(String key, String attribute, String value) {
        return new ConstraintData.Value(id(key), attribute, value);
    }
}
