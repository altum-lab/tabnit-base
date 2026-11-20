package com.altumlab.tabnit.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * C-02 가운데 pack 의 일 (D2 AD-140): D6 7절의 매핑 정의를 읽고, 온톨로지와 맞는지 검증하고, 저장해 공개 API 로 읽힌다.
 *
 * <p>일반 시험을 먼저 둔다 (지어낸 이름의 작은 Pack). 그 위에 저장소에 들어 있는 Pack 의 매핑을 모두 읽어 본다.
 * 특정 Pack 의 이름과 수는 시험에 적지 않는다.
 */
class PackMappingTest {

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
            ex:links a owl:ObjectProperty ; rdfs:label "잇는다" ; rdfs:domain ex:Base ; rdfs:range ex:Other ;
                tabnit:impactDirection tabnit:SourceToTarget .
            ex:title a owl:DatatypeProperty ; rdfs:label "제목" ; rdfs:domain ex:Base ; rdfs:range xsd:string .
            ex:count a owl:DatatypeProperty ; rdfs:label "수" ; rdfs:domain ex:Base ; rdfs:range xsd:integer .
            ex:since a owl:DatatypeProperty ; rdfs:label "시작" ; rdfs:domain ex:Base ; rdfs:range xsd:date .
            ex:active a owl:DatatypeProperty ; rdfs:label "쓰는가" ; rdfs:domain ex:Base ; rdfs:range xsd:boolean .
            ex:otherName a owl:DatatypeProperty ; rdfs:label "다른 이름" ; rdfs:domain ex:Other ; rdfs:range xsd:string .
            """;

    /** 가장 작은 매핑: 유형 Base 의 개체 하나를 만든다. */
    private static final String MINIMAL = """
            ex:m a tabnit:Mapping ;
                tabnit:inputSourceId "src" ;
                tabnit:fileFormat tabnit:Csv ;
                tabnit:itemKeyColumns ( "코드" ) ;
                tabnit:primaryEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "코드" ] ;
                tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn "코드" ] .
            """;

    private final PackLoader loader = new PackLoader(PackTestDatabase.dataSource());

    private final PackDefinitions definitions = new PackDefinitions(PackTestDatabase.dataSource());

    @TempDir
    Path temp;

    // ── 읽기와 저장 ─────────────────────────────────────────────

    @Test
    void 매핑이_D6의_항목대로_읽히고_공개_API로_돌려준다() throws IOException {
        Path dir = pack("m-full", "full.ttl", """
                ex:m a tabnit:Mapping ;
                    tabnit:inputSourceId "full" ;
                    tabnit:fileFormat tabnit:Csv ;
                    tabnit:defaultDatePattern "yyyy'년' M'월' d'일'" ;
                    tabnit:requiredColumns ( "코드" "제목" "상대" ) ;
                    tabnit:itemKeyColumns ( "코드" "상대" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Sub ; tabnit:keyColumn "코드" ] ;
                    tabnit:entity [
                        tabnit:entityType ex:Sub ;
                        tabnit:keyColumn "코드" ;
                        tabnit:attribute [ tabnit:property ex:title ; tabnit:column "제목" ] ;
                        tabnit:attribute [ tabnit:property ex:since ; tabnit:column "시작일" ; tabnit:datePattern "yyyy.M.d" ;
                                           tabnit:validFromColumn "부터" ; tabnit:validToColumn "까지" ] ;
                        tabnit:attribute [ tabnit:property ex:count ; tabnit:column "수" ; tabnit:groupingSeparator "," ] ;
                        tabnit:attribute [ tabnit:property ex:active ; tabnit:column "쓰는가" ;
                                           tabnit:trueValue "예" ; tabnit:falseValue "아니오" ]
                    ] ;
                    tabnit:relation [
                        tabnit:relationType ex:links ;
                        tabnit:sourceEntity [ tabnit:entityType ex:Sub ; tabnit:keyColumn "코드" ] ;
                        tabnit:targetEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "상대" ] ;
                        tabnit:validFromColumn "부터"
                    ] .
                """);

        loader.load(dir, LOADED_AT);
        List<MappingDefinition> mappings = definitions.find("m-full").orElseThrow().mappings();

        MappedEntityRef sub = new MappedEntityRef("Sub", "코드");
        assertThat(mappings).containsExactly(new MappingDefinition("full", MappingFileFormat.CSV, null,
                "yyyy'년' M'월' d'일'", List.of("코드", "제목", "상대"), List.of("코드", "상대"), sub,
                // 속성은 이름의 글자 코드 순이다.
                List.of(new MappedEntity("Sub", "코드",
                        List.of(new MappedAttribute("active", "쓰는가", null, null, null, null, "예", "아니오"),
                                new MappedAttribute("count", "수", null, null, null, ",", null, null),
                                new MappedAttribute("since", "시작일", "부터", "까지", "yyyy.M.d", null, null, null),
                                new MappedAttribute("title", "제목", null, null, null, null, null, null)))),
                List.of(new MappedRelation("links", sub, new MappedEntityRef("Other", "상대"), "부터", null))));
    }

    @Test
    void 매핑이_없는_Pack도_적재되고_매핑_목록은_비어_있다() throws IOException {
        Path dir = pack("m-none", null, null);

        loader.load(dir, LOADED_AT);

        assertThat(definitions.find("m-none").orElseThrow().mappings()).isEmpty();
    }

    @Test
    void 매핑_여럿은_입력_자료의_식별자_순이고_개체와_관계는_내용_순이다() throws IOException {
        Path dir = pack("m-order", "z-first.ttl", """
                ex:mz a tabnit:Mapping ; tabnit:inputSourceId "zeta" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "k" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "k" ] ;
                    tabnit:entity [ tabnit:entityType ex:Other ; tabnit:keyColumn "k" ] ;
                    tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn "b2" ] ;
                    tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn "b1" ] ;
                    tabnit:relation [ tabnit:relationType ex:links ;
                        tabnit:sourceEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "b2" ] ;
                        tabnit:targetEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "k" ] ] ;
                    tabnit:relation [ tabnit:relationType ex:links ;
                        tabnit:sourceEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "b1" ] ;
                        tabnit:targetEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "k" ] ] .
                """);
        mapping(dir, "a-second.ttl", MINIMAL.replace("\"src\"", "\"alpha\""));

        loader.load(dir, LOADED_AT);
        PackDefinition first = definitions.find("m-order").orElseThrow();

        assertThat(first.mappings()).extracting(MappingDefinition::inputSourceId).containsExactly("alpha", "zeta");
        MappingDefinition zeta = first.mappings().get(1);
        assertThat(zeta.entities()).extracting(e -> e.entityType() + "/" + e.keyColumn())
            .containsExactly("Base/b1", "Base/b2", "Other/k");
        assertThat(zeta.relations()).extracting(r -> r.source().keyColumn()).containsExactly("b1", "b2");
        assertThat(definitions.find("m-order").orElseThrow()).isEqualTo(first);
    }

    @Test
    void 열_목록의_차례는_적힌_그대로이고_목록으로_적지_않으면_거부한다() throws IOException {
        Path ordered = pack("m-list", "m.ttl", MINIMAL.replace("( \"코드\" )", "( \"둘째\" \"첫째\" \"코드\" )"));
        Path notList = pack("m-notlist", "m.ttl", MINIMAL.replace("( \"코드\" )", "\"코드\" , \"둘째\""));
        Path emptyList = pack("m-emptylist", "m.ttl", MINIMAL.replace("( \"코드\" )", "( )"));
        Path repeated = pack("m-replist", "m.ttl", MINIMAL.replace("( \"코드\" )", "( \"코드\" \"코드\" )"));

        loader.load(ordered, LOADED_AT);

        assertThat(definitions.find("m-list").orElseThrow().mappings().get(0).itemKeyColumns())
            .containsExactly("둘째", "첫째", "코드");
        for (Path dir : List.of(notList, emptyList, repeated)) {
            assertThat(problemsOf(dir)).as(dir.toString()).singleElement().satisfies(p -> {
                assertThat(p.file()).isEqualTo("mappings/m.ttl");
                assertThat(p.item()).isEqualTo("m");
                assertThat(p.message()).contains("itemKeyColumns");
            });
        }
    }

    @Test
    void 엑셀_매핑은_시트를_적어야_하고_CSV_매핑은_시트를_적지_않는다() throws IOException {
        Path xlsx = pack("m-xlsx", "m.ttl",
                MINIMAL.replace("tabnit:Csv ;", "tabnit:Xlsx ; tabnit:sheetName \"자료\" ;"));
        Path noSheet = pack("m-xlsx-nosheet", "m.ttl", MINIMAL.replace("tabnit:Csv", "tabnit:Xlsx"));
        Path csvSheet = pack("m-csv-sheet", "m.ttl",
                MINIMAL.replace("tabnit:Csv ;", "tabnit:Csv ; tabnit:sheetName \"자료\" ;"));
        Path unknown = pack("m-format", "m.ttl", MINIMAL.replace("tabnit:Csv", "tabnit:Json"));

        loader.load(xlsx, LOADED_AT);

        MappingDefinition found = definitions.find("m-xlsx").orElseThrow().mappings().get(0);
        assertThat(found.fileFormat()).isEqualTo(MappingFileFormat.XLSX);
        assertThat(found.sheetName()).isEqualTo("자료");
        assertThat(problemsOf(noSheet)).singleElement().satisfies(p -> assertThat(p.message()).contains("sheetName"));
        assertThat(problemsOf(csvSheet)).singleElement().satisfies(p -> assertThat(p.message()).contains("sheetName"));
        assertThat(problemsOf(unknown)).singleElement().satisfies(p -> assertThat(p.message()).contains("fileFormat"));
    }

    // ── 틀린 매핑 ───────────────────────────────────────────────

    @Test
    void 반드시_적는_항목이_빠지면_어느_매핑의_무엇인지_알린다() throws IOException {
        Path noSource = pack("m-nosrc", "m.ttl", MINIMAL.replace("tabnit:inputSourceId \"src\" ;", ""));
        Path noFormat = pack("m-nofmt", "m.ttl", MINIMAL.replace("tabnit:fileFormat tabnit:Csv ;", ""));
        Path noKey = pack("m-nokey", "m.ttl", MINIMAL.replace("tabnit:itemKeyColumns ( \"코드\" ) ;", ""));
        Path noPrimary = pack("m-noprimary", "m.ttl",
                MINIMAL.replace("tabnit:primaryEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn \"코드\" ] ;", ""));

        assertThat(problemsOf(noSource)).singleElement().satisfies(p -> assertThat(p.message()).contains("inputSourceId"));
        assertThat(problemsOf(noFormat)).singleElement().satisfies(p -> assertThat(p.message()).contains("fileFormat"));
        assertThat(problemsOf(noKey)).singleElement().satisfies(p -> assertThat(p.message()).contains("itemKeyColumns"));
        assertThat(problemsOf(noPrimary)).singleElement().satisfies(p -> {
            assertThat(p.file()).isEqualTo("mappings/m.ttl");
            assertThat(p.item()).isEqualTo("m");
            assertThat(p.message()).contains("primaryEntity");
        });
        assertThat(definitions.find("m-noprimary")).isEmpty();
    }

    @Test
    void 주_개체는_하나이고_그_행이_만드는_개체_가운데_하나여야_한다() throws IOException {
        Path two = pack("m-primary-two", "m.ttl", MINIMAL.replace("tabnit:primaryEntity [",
                "tabnit:primaryEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn \"다른코드\" ] ;\n    tabnit:primaryEntity ["));
        Path stranger = pack("m-primary-stranger", "m.ttl", MINIMAL.replace(
                "tabnit:primaryEntity [ tabnit:entityType ex:Base", "tabnit:primaryEntity [ tabnit:entityType ex:Other"));
        // 관계만 담은 자료는 관계의 한쪽 끝을 주 개체로 적는다 (D2 AD-120).
        Path relationEnd = pack("m-primary-end", "m.ttl", """
                ex:m a tabnit:Mapping ; tabnit:inputSourceId "src" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "a" "b" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "a" ] ;
                    tabnit:relation [ tabnit:relationType ex:links ;
                        tabnit:sourceEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "a" ] ;
                        tabnit:targetEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "b" ] ] .
                """);

        assertThat(problemsOf(two)).singleElement().satisfies(p -> assertThat(p.message()).contains("primaryEntity"));
        assertThat(problemsOf(stranger)).singleElement()
            .satisfies(p -> assertThat(p.message()).contains("primaryEntity").contains("Other"));
        assertThat(loader.load(relationEnd, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    @Test
    void 온톨로지에_없는_유형과_속성과_관계_유형을_가리키면_거부한다() throws IOException {
        Path dir = pack("m-undefined", "m.ttl", """
                ex:m a tabnit:Mapping ; tabnit:inputSourceId "src" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "k" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ] ;
                    tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ;
                        tabnit:attribute [ tabnit:property ex:noSuchAttribute ; tabnit:column "c" ] ] ;
                    tabnit:entity [ tabnit:entityType ex:NoSuchType ; tabnit:keyColumn "n" ] ;
                    tabnit:relation [ tabnit:relationType ex:noSuchRelation ;
                        tabnit:sourceEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ] ;
                        tabnit:targetEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "o" ] ] .
                """);

        List<PackProblem> problems = problemsOf(dir);

        assertThat(problems).hasSize(3).allSatisfy(p -> {
            assertThat(p.file()).isEqualTo("mappings/m.ttl");
            assertThat(p.item()).isEqualTo("m");
        });
        assertThat(problems).extracting(PackProblem::message)
            .anySatisfy(m -> assertThat(m).contains("noSuchAttribute"))
            .anySatisfy(m -> assertThat(m).contains("NoSuchType"))
            .anySatisfy(m -> assertThat(m).contains("noSuchRelation"));
    }

    @Test
    void 속성은_그_유형이나_상위_유형의_것이어야_하고_관계의_끝은_관계_유형과_맞아야_한다() throws IOException {
        // 하위 유형 Sub 는 Base 의 속성을 갖고, Base 가 출발인 관계의 출발이 될 수 있다.
        Path subtype = pack("m-subtype", "m.ttl", """
                ex:m a tabnit:Mapping ; tabnit:inputSourceId "src" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "k" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Sub ; tabnit:keyColumn "k" ] ;
                    tabnit:entity [ tabnit:entityType ex:Sub ; tabnit:keyColumn "k" ;
                        tabnit:attribute [ tabnit:property ex:title ; tabnit:column "c" ] ] ;
                    tabnit:relation [ tabnit:relationType ex:links ;
                        tabnit:sourceEntity [ tabnit:entityType ex:Sub ; tabnit:keyColumn "k" ] ;
                        tabnit:targetEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "o" ] ] .
                """);
        Path wrongOwner = pack("m-wrong-owner", "m.ttl", MINIMAL.replace("tabnit:keyColumn \"코드\" ] .",
                "tabnit:keyColumn \"코드\" ; tabnit:attribute [ tabnit:property ex:otherName ; tabnit:column \"c\" ] ] ."));
        Path wrongEnds = pack("m-wrong-ends", "m.ttl", """
                ex:m a tabnit:Mapping ; tabnit:inputSourceId "src" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "k" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "k" ] ;
                    tabnit:relation [ tabnit:relationType ex:links ;
                        tabnit:sourceEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "k" ] ;
                        tabnit:targetEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "o" ] ] .
                """);

        assertThat(loader.load(subtype, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
        assertThat(problemsOf(wrongOwner)).singleElement()
            .satisfies(p -> assertThat(p.message()).contains("otherName").contains("Other"));
        // 출발과 도착이 둘 다 맞지 않는다.
        assertThat(problemsOf(wrongEnds)).hasSize(2).allSatisfy(p -> assertThat(p.message()).contains("links"));
    }

    @Test
    void 같은_개체나_같은_속성이나_같은_관계를_두_번_적으면_거부한다() throws IOException {
        Path entityTwice = pack("m-dup-entity", "m.ttl", MINIMAL.replace(
                "tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn \"코드\" ] .",
                "tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn \"코드\" ] ;\n"
                        + "    tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn \"코드\" ; "
                        + "tabnit:attribute [ tabnit:property ex:title ; tabnit:column \"c\" ] ] ."));
        Path attributeTwice = pack("m-dup-attribute", "m.ttl", MINIMAL.replace("tabnit:keyColumn \"코드\" ] .",
                "tabnit:keyColumn \"코드\" ; tabnit:attribute [ tabnit:property ex:title ; tabnit:column \"c1\" ] ; "
                        + "tabnit:attribute [ tabnit:property ex:title ; tabnit:column \"c2\" ] ] ."));

        assertThat(problemsOf(entityTwice)).singleElement()
            .satisfies(p -> assertThat(p.message()).contains("Base").contains("코드"));
        assertThat(problemsOf(attributeTwice)).singleElement().satisfies(p -> assertThat(p.message()).contains("title"));
    }

    @Test
    void 값을_읽는_법은_속성의_형식과_맞아야_한다() throws IOException {
        String withAttribute = MINIMAL.replace("tabnit:keyColumn \"코드\" ] .",
                "tabnit:keyColumn \"코드\" ; tabnit:attribute [ ATTRIBUTE ] ] .");
        for (String attribute : List.of(
                // 날짜의 모양은 날짜 속성에만.
                "tabnit:property ex:title ; tabnit:column \"c\" ; tabnit:datePattern \"yyyy.M.d\"",
                // 읽을 수 없는 모양.
                "tabnit:property ex:since ; tabnit:column \"c\" ; tabnit:datePattern \"yyyy-bb-dd{\"",
                // 자릿수 구분 기호는 수 속성에만.
                "tabnit:property ex:since ; tabnit:column \"c\" ; tabnit:groupingSeparator \",\"",
                // 참과 거짓은 함께 적는다.
                "tabnit:property ex:active ; tabnit:column \"c\" ; tabnit:trueValue \"예\"",
                // 참과 거짓은 참·거짓 속성에만.
                "tabnit:property ex:count ; tabnit:column \"c\" ; tabnit:trueValue \"예\" ; tabnit:falseValue \"아니오\"",
                // 참과 거짓이 같은 글자.
                "tabnit:property ex:active ; tabnit:column \"c\" ; tabnit:trueValue \"예\" ; tabnit:falseValue \"예\"")) {
            Path dir = pack("m-option", "m.ttl", withAttribute.replace("ATTRIBUTE", attribute));
            assertThat(problemsOf(dir)).as(attribute).singleElement().satisfies(p -> {
                assertThat(p.file()).isEqualTo("mappings/m.ttl");
                assertThat(p.item()).isEqualTo("m");
            });
        }
    }

    @Test
    void D6에_없는_문장은_매핑_안쪽에_있어도_거부한다() throws IOException {
        Path top = pack("m-unknown-top", "m.ttl", MINIMAL.replace("tabnit:fileFormat", "rdfs:comment \"설명\" ; tabnit:fileFormat"));
        Path nested = pack("m-unknown-nested", "m.ttl", MINIMAL.replace("tabnit:keyColumn \"코드\" ] .",
                "tabnit:keyColumn \"코드\" ; tabnit:skipWhen \"x\" ] ."));
        Path stray = pack("m-stray", "m.ttl", MINIMAL + "ex:else a tabnit:Something ; tabnit:inputSourceId \"x\" .\n");

        assertThat(problemsOf(top)).singleElement().satisfies(p -> assertThat(p.message()).contains("comment"));
        assertThat(problemsOf(nested)).singleElement().satisfies(p -> assertThat(p.message()).contains("skipWhen"));
        assertThat(problemsOf(stray)).isNotEmpty().allSatisfy(p -> {
            assertThat(p.file()).isEqualTo("mappings/m.ttl");
            assertThat(p.message()).doesNotContain("_:");
        });
    }

    @Test
    void 문법이_틀린_매핑_파일은_파일과_줄을_알린다() throws IOException {
        Path dir = pack("m-syntax", "broken.ttl", "ex:m a tabnit:Mapping ; tabnit:inputSourceId \"닫지 않음 .");

        assertThat(problemsOf(dir)).singleElement().satisfies(p -> {
            assertThat(p.file()).isEqualTo("mappings/broken.ttl");
            assertThat(p.message()).containsPattern("\\d+행");
        });
    }

    @Test
    void 날짜의_모양에는_연월일의_숫자와_구분_글자만_쓴다() throws IOException {
        String withDate = MINIMAL.replace("tabnit:keyColumn \"코드\" ] .",
                "tabnit:keyColumn \"코드\" ; tabnit:attribute [ tabnit:property ex:since ; tabnit:column \"c\" ; "
                        + "tabnit:datePattern \"PATTERN\" ] ] .");
        int n = 0;
        for (String pattern : List.of("yyyy.M.d", "yyyy-MM-dd", "yyyy/M/d", "yyyy. M. d", "yyyy'년' M'월' d'일'", "d.M.yyyy",
                "yyyyMMdd", "yyyy''M''d")) {
            Path dir = pack("m-date-ok-" + n++, "m.ttl", withDate.replace("PATTERN", pattern));
            assertThat(loader.load(dir, LOADED_AT).outcome()).as(pattern).isEqualTo(PackLoadResult.Outcome.LOADED);
            // 받은 모양으로는 날짜를 실제로 적고 다시 읽을 수 있어야 한다.
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern(pattern,
                    java.util.Locale.ROOT);
            for (java.time.LocalDate date : List.of(java.time.LocalDate.of(1998, 1, 9), java.time.LocalDate.of(2031, 12, 31))) {
                assertThat(java.time.LocalDate.parse(date.format(formatter), formatter)).as(pattern).isEqualTo(date);
            }
        }
        // 달의 이름, 요일, 시·분, 연·월·일 가운데 빠진 것, 감싸지 않은 다른 글자, 두 번 적은 것.
        // 구분 글자 없이 이어 붙여 어떤 날짜도 읽지 못하는 것, 두 자리 연도, 앞뒤의 빈칸도 받지 않는다.
        for (String pattern : List.of("d MMM yyyy", "yyyy-MM-dd E", "yyyy-MM-dd HH:mm", "HH:mm", "M.d", "yyyy.M",
                "yyyy,M,d", "yyyy년 M월 d일", "yyyy.M.d.d", "yyyy-DDD", "yyyyMd", "dMyyyy", "yy.M.d", " yyyy.M.d",
                "yyyy.M.d ", "yyyy'년")) {
            Path dir = pack("m-date-bad", "m.ttl", withDate.replace("PATTERN", pattern));
            assertThat(problemsOf(dir)).as(pattern).singleElement().satisfies(p -> {
                assertThat(p.item()).isEqualTo("m");
                assertThat(p.message()).contains("datePattern");
            });
        }
    }

    @Test
    void 날짜의_기본_모양은_매핑에_하나_적고_같은_규칙으로_확인한다() throws IOException {
        Path good = pack("m-default-date", "m.ttl",
                MINIMAL.replace("tabnit:fileFormat tabnit:Csv ;", "tabnit:fileFormat tabnit:Csv ; tabnit:defaultDatePattern \"yyyy.M.d\" ;"));
        Path bad = pack("m-default-date-bad", "m.ttl",
                MINIMAL.replace("tabnit:fileFormat tabnit:Csv ;", "tabnit:fileFormat tabnit:Csv ; tabnit:defaultDatePattern \"MMM d, yyyy\" ;"));
        Path twice = pack("m-default-date-two", "m.ttl", MINIMAL.replace("tabnit:fileFormat tabnit:Csv ;",
                "tabnit:fileFormat tabnit:Csv ; tabnit:defaultDatePattern \"yyyy.M.d\" , \"yyyy-M-d\" ;"));

        loader.load(good, LOADED_AT);

        assertThat(definitions.find("m-default-date").orElseThrow().mappings().get(0).defaultDatePattern())
            .isEqualTo("yyyy.M.d");
        // 다시 넣으면 그대로이고, 기본 모양만 바꾼 것은 매핑을 바꾼 것이다.
        assertThat(loader.load(good, LOADED_AT.plusSeconds(10)).outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
        assertThatThrownBy(() -> loader.load(pack("m-default-date", "m.ttl", MINIMAL.replace("tabnit:fileFormat tabnit:Csv ;",
                "tabnit:fileFormat tabnit:Csv ; tabnit:defaultDatePattern \"yyyy-M-d\" ;")), LOADED_AT.plusSeconds(20)))
            .isInstanceOf(PackChangeRejectedException.class);
        assertThat(problemsOf(bad)).singleElement().satisfies(p -> assertThat(p.message()).contains("defaultDatePattern"));
        assertThat(problemsOf(twice)).singleElement().satisfies(p -> assertThat(p.message()).contains("defaultDatePattern"));
    }

    @Test
    void 입력_자료의_식별자는_영어_소문자로_시작하고_소문자와_숫자와_붙임표만_쓴다() throws IOException {
        for (String id : List.of("Src", "1src", "s rc", " ", "자료", "s_rc", "src.")) {
            Path dir = pack("m-source-id", "m.ttl", MINIMAL.replace("\"src\"", "\"" + id + "\""));
            assertThat(problemsOf(dir)).as(id).singleElement()
                .satisfies(p -> assertThat(p.message()).contains("inputSourceId"));
        }
        assertThat(loader.load(pack("m-source-id-ok", "m.ttl", MINIMAL.replace("\"src\"", "\"src-2-go\"")), LOADED_AT)
            .outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    // ── 매핑끼리 (D2 AD-41, AD-137) ─────────────────────────────────

    @Test
    void 입력_자료의_식별자가_두_매핑에_겹치면_거부한다() throws IOException {
        Path dir = pack("m-dup-source", "one.ttl", MINIMAL);
        mapping(dir, "two.ttl", MINIMAL.replace("ex:m ", "ex:m2 "));

        assertThat(problemsOf(dir)).singleElement()
            .satisfies(p -> assertThat(p.message()).contains("src").contains("one.ttl").contains("two.ttl"));
    }

    @Test
    void 속성_하나를_두_매핑이_넣으면_거부하고_하위_유형이_다르면_받는다() throws IOException {
        String withTitle = """
                ex:NAME a tabnit:Mapping ; tabnit:inputSourceId "NAME" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "k" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:TYPE ; tabnit:keyColumn "k" ] ;
                    tabnit:entity [ tabnit:entityType ex:TYPE ; tabnit:keyColumn "k" ;
                        tabnit:attribute [ tabnit:property ex:title ; tabnit:column "c" ] ] .
                """;
        Path clash = pack("m-owner-clash", "one.ttl", withTitle.replace("NAME", "one").replace("TYPE", "Base"));
        mapping(clash, "two.ttl", withTitle.replace("NAME", "two").replace("TYPE", "Base"));
        Path apart = pack("m-owner-apart", "one.ttl", withTitle.replace("NAME", "one").replace("TYPE", "Base"));
        mapping(apart, "two.ttl", withTitle.replace("NAME", "two").replace("TYPE", "Sub"));

        assertThat(problemsOf(clash)).singleElement()
            .satisfies(p -> assertThat(p.message()).contains("title").contains("Base").contains("one").contains("two"));
        assertThat(loader.load(apart, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    // ── 다시 넣기 ───────────────────────────────────────────────

    @Test
    void 매핑을_더한_Pack은_다시_받고_같은_Pack은_그대로다() throws IOException {
        Path one = pack("m-grow", "one.ttl", MINIMAL);
        loader.load(one, LOADED_AT);
        assertThat(loader.load(one, LOADED_AT.plusSeconds(10)).outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);

        Path two = pack("m-grow", "one.ttl", MINIMAL);
        mapping(two, "two.ttl", MINIMAL.replace("ex:m ", "ex:m2 ").replace("\"src\"", "\"src-2\""));
        PackLoadResult result = loader.load(two, LOADED_AT.plusSeconds(20));

        assertThat(result.outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
        assertThat(definitions.find("m-grow").orElseThrow().mappings()).extracting(MappingDefinition::inputSourceId)
            .containsExactly("src", "src-2");
    }

    @Test
    void 이미_있는_매핑을_바꾸거나_지운_Pack은_거부하고_저장된_것은_그대로다() throws IOException {
        loader.load(pack("m-change", "one.ttl", MINIMAL), LOADED_AT);
        PackDefinition before = definitions.find("m-change").orElseThrow();

        assertThatThrownBy(() -> loader.load(
                pack("m-change", "one.ttl", MINIMAL.replace("( \"코드\" )", "( \"코드\" \"둘째\" )")),
                LOADED_AT.plusSeconds(10))).isInstanceOf(PackChangeRejectedException.class)
            .hasMessageContaining("src");
        assertThatThrownBy(() -> loader.load(pack("m-change", null, null), LOADED_AT.plusSeconds(20)))
            .isInstanceOf(PackChangeRejectedException.class)
            .hasMessageContaining("src");
        assertThat(definitions.find("m-change").orElseThrow()).isEqualTo(before);
    }

    // ── 문제의 차례 ──────────────────────────────────────────────

    @Test
    void 문제가_여럿이면_모두_알리고_차례가_정해져_있다() throws IOException {
        Path dir = pack("m-many", "b.ttl", MINIMAL.replace("tabnit:inputSourceId \"src\" ;", "")
            .replace("ex:Base ; tabnit:keyColumn \"코드\" ] .", "ex:Nowhere ; tabnit:keyColumn \"코드\" ] ."));
        mapping(dir, "a.ttl", MINIMAL.replace("ex:m ", "ex:other ").replace("tabnit:fileFormat tabnit:Csv ;", ""));

        List<PackProblem> first = problemsOf(dir);

        assertThat(first).extracting(PackProblem::file).startsWith("mappings/a.ttl").endsWith("mappings/b.ttl");
        assertThat(first.size()).isGreaterThanOrEqualTo(3);
        assertThat(problemsOf(dir)).isEqualTo(first);
    }

    @Test
    void 문장을_적는_차례만_다른_두_매핑은_같은_결과를_낸다() throws IOException {
        String forward = """
                ex:m a tabnit:Mapping ; tabnit:inputSourceId "src" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "k" "o" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ] ;
                    tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ;
                        tabnit:attribute [ tabnit:property ex:title ; tabnit:column "t" ] ;
                        tabnit:attribute [ tabnit:property ex:count ; tabnit:column "c" ] ] ;
                    tabnit:entity [ tabnit:entityType ex:Other ; tabnit:keyColumn "o" ] ;
                    tabnit:relation [ tabnit:relationType ex:links ;
                        tabnit:sourceEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ] ;
                        tabnit:targetEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "o" ] ] .
                """;
        String backward = """
                ex:m tabnit:relation [
                        tabnit:targetEntity [ tabnit:keyColumn "o" ; tabnit:entityType ex:Other ] ;
                        tabnit:sourceEntity [ tabnit:keyColumn "k" ; tabnit:entityType ex:Base ] ;
                        tabnit:relationType ex:links ] ;
                    tabnit:entity [ tabnit:keyColumn "o" ; tabnit:entityType ex:Other ] ;
                    tabnit:entity [
                        tabnit:attribute [ tabnit:column "c" ; tabnit:property ex:count ] ;
                        tabnit:attribute [ tabnit:column "t" ; tabnit:property ex:title ] ;
                        tabnit:keyColumn "k" ; tabnit:entityType ex:Base ] ;
                    tabnit:primaryEntity [ tabnit:keyColumn "k" ; tabnit:entityType ex:Base ] ;
                    tabnit:itemKeyColumns ( "k" "o" ) ;
                    tabnit:fileFormat tabnit:Csv ; tabnit:inputSourceId "src" .
                ex:m a tabnit:Mapping .
                """;

        PackReader.Result one = PackReader.read(pack("m-shuffle", "m.ttl", forward), LOADED_AT);
        PackReader.Result other = PackReader.read(pack("m-shuffle", "m.ttl", backward), LOADED_AT);

        assertThat(one.problems()).isEmpty();
        assertThat(other.definition().mappings()).isEqualTo(one.definition().mappings());
    }

    @Test
    void 안쪽_정의는_쓰는_곳마다_따로_적어야_하고_이름_붙인_주소로는_적지_않는다() throws IOException {
        // 라벨을 붙인 안쪽 정의 하나를 주 개체와 개체가 함께 가리킨다.
        String shared = """
                ex:m a tabnit:Mapping ; tabnit:inputSourceId "src" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "k" ) ;
                    tabnit:primaryEntity _:same ;
                    tabnit:entity _:same .
                _:same tabnit:entityType ex:Base ; tabnit:keyColumn "k" .
                """;
        String named = """
                ex:m a tabnit:Mapping ; tabnit:inputSourceId "src" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "k" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ] ;
                    tabnit:entity ex:inner .
                ex:inner tabnit:entityType ex:Base ; tabnit:keyColumn "k" .
                """;

        List<PackProblem> first = problemsOf(pack("m-shared", "m.ttl", shared));

        assertThat(first).singleElement().satisfies(p -> {
            assertThat(p.item()).isEqualTo("m");
            assertThat(p.message()).contains("두 곳");
        });
        assertThat(problemsOf(pack("m-shared", "m.ttl", shared))).isEqualTo(first);
        assertThat(problemsOf(pack("m-named", "m.ttl", named))).singleElement()
            .satisfies(p -> assertThat(p.message()).contains("tabnit:entity").contains("안쪽 정의"));
    }

    @Test
    void 파일_하나에_매핑은_하나이고_이름을_붙여_적는다() throws IOException {
        Path two = pack("m-two-in-file", "m.ttl",
                MINIMAL + MINIMAL.replace("ex:m ", "ex:m2 ").replace("\"src\"", "\"src-2\""));
        Path none = pack("m-none-in-file", "m.ttl", "# 매핑이 없는 파일\n");
        Path anonymous = pack("m-anonymous", "m.ttl", MINIMAL.replace("ex:m a tabnit:Mapping ;", "[] a tabnit:Mapping ;"));

        assertThat(problemsOf(two)).singleElement().satisfies(p -> {
            assertThat(p.file()).isEqualTo("mappings/m.ttl");
            assertThat(p.message()).contains("2개");
        });
        assertThat(problemsOf(none)).singleElement().satisfies(p -> assertThat(p.message()).contains("0개"));
        assertThat(problemsOf(anonymous)).singleElement().satisfies(p -> assertThat(p.message()).contains("이름"));
    }

    @Test
    void 저장소가_받지_않는_글자가_든_값은_저장하기_전에_거부한다() throws IOException {
        // NUL 글자. 검증을 통과하면 저장에서 실패해 "저장소의 문제"로 잘못 알려진다.
        Path column = pack("m-nul-column", "m.ttl", MINIMAL.replace("( \"코드\" )", "( \"코\\u0000드\" )"));
        Path source = pack("m-nul-source", "m.ttl", MINIMAL.replace("\"src\"", "\"s\\u0000rc\""));
        Path label = pack("m-nul-label", null, null);
        Files.writeString(label.resolve("ontology.ttl"), Files.readString(label.resolve("ontology.ttl"))
            .replace("rdfs:label \"바탕\"", "rdfs:label \"바\\u0000탕\""), StandardCharsets.UTF_8);

        assertThat(problemsOf(column)).singleElement().satisfies(p -> assertThat(p.message()).contains("itemKeyColumns"));
        assertThat(problemsOf(source)).singleElement().satisfies(p -> assertThat(p.message()).contains("inputSourceId"));
        assertThat(problemsOf(label)).singleElement().satisfies(p -> assertThat(p.message()).contains("rdfs:label"));
    }

    @Test
    void 틀린_곳_하나에_문제가_여러_건_따라붙지_않는다() throws IOException {
        Path badList = pack("m-cascade-list", "m.ttl", MINIMAL.replace("( \"코드\" )", "( \"코드\" 5 \"z\" \"y\" )"));
        Path listTwice = pack("m-cascade-twice", "m.ttl",
                MINIMAL.replace("tabnit:itemKeyColumns ( \"코드\" ) ;", "tabnit:itemKeyColumns ( \"코드\" ) , ( \"둘째\" ) ;"));
        Path twoEnds = pack("m-cascade-ends", "m.ttl", """
                ex:m a tabnit:Mapping ; tabnit:inputSourceId "src" ; tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "k" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ] ;
                    tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ] ;
                    tabnit:relation [ tabnit:relationType ex:links ;
                        tabnit:sourceEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "k" ] ,
                                            [ tabnit:entityType ex:Base ; tabnit:keyColumn "k2" ] ;
                        tabnit:targetEntity [ tabnit:entityType ex:Other ; tabnit:keyColumn "o" ] ] .
                """);

        for (Path dir : List.of(badList, listTwice, twoEnds)) {
            assertThat(problemsOf(dir)).as(dir.toString()).singleElement()
                .satisfies(p -> assertThat(p.item()).isEqualTo("m"));
        }
    }

    // ── 저장소에 들어 있는 Pack ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("repositoryPacks")
    void 저장소의_Pack의_매핑이_코드를_고치지_않고_모두_읽힌다(Path dir) throws IOException {
        String packId = dir.getFileName().toString();
        long files;
        try (Stream<Path> list = Files.list(dir.resolve("mappings"))) {
            files = list.filter(f -> f.getFileName().toString().endsWith(".ttl")).count();
        }

        loader.load(dir, LOADED_AT);
        PackLoadResult again = loader.load(dir, LOADED_AT);
        PackDefinition found = definitions.find(packId).orElseThrow();

        // 매핑 파일 하나에 매핑 하나라고 본다. 시험에 Pack 의 수를 적지 않는다.
        assertThat(found.mappings()).hasSize((int) files).isNotEmpty();
        assertThat(again.outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
        List<String> typeNames = found.entityTypes().stream().map(EntityTypeDefinition::name).toList();
        assertThat(found.mappings()).allSatisfy(m -> {
            assertThat(m.itemKeyColumns()).isNotEmpty();
            assertThat(typeNames).contains(m.primaryEntity().entityType());
        });
    }

    static Stream<Path> repositoryPacks() throws IOException {
        // 작업 폴더는 server/pack 이다. 매핑 폴더가 있는 Pack 만 고른다.
        try (Stream<Path> dirs = Files.list(Path.of("../../packs"))) {
            return dirs.filter(d -> Files.isDirectory(d.resolve("mappings")) && Files.exists(d.resolve("pack.ttl")))
                .sorted()
                .toList()
                .stream();
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 지어낸 작은 Pack 폴더를 만든다. 매핑 파일의 이름이 null 이면 매핑 폴더를 만들지 않는다. */
    private Path pack(String packId, String mappingFile, String mappingBody) throws IOException {
        Path dir = Files.createTempDirectory(temp, "p").resolve(packId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pack.ttl"),
                PREFIXES + "<urn:tabnit:pack:" + packId + ":> a tabnit:Pack ; tabnit:packId \"" + packId
                        + "\" ; rdfs:label \"이름표\" ; tabnit:defaultMaxDepth 1 .\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("ontology.ttl"),
                PREFIXES + "@prefix ex: <urn:tabnit:pack:" + packId + ":> .\n" + ONTOLOGY, StandardCharsets.UTF_8);
        if (mappingFile != null) {
            mapping(dir, mappingFile, mappingBody);
        }
        return dir;
    }

    private static void mapping(Path dir, String file, String body) throws IOException {
        Files.createDirectories(dir.resolve("mappings"));
        String packId = dir.getFileName().toString();
        Files.writeString(dir.resolve("mappings").resolve(file),
                PREFIXES + "@prefix ex: <urn:tabnit:pack:" + packId + ":> .\n" + body, StandardCharsets.UTF_8);
    }

    private List<PackProblem> problemsOf(Path dir) {
        PackInvalidException e = catchThrowableOfType(PackInvalidException.class, () -> loader.load(dir, LOADED_AT));
        assertThat(e).as("거부돼야 하는 Pack 이 적재됐다: " + dir).isNotNull();
        return e.problems();
    }
}
