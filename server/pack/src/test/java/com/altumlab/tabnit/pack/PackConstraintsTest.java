package com.altumlab.tabnit.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B-01: 제약 파일(constraints.ttl, SHACL)을 읽고 검증해 저장한다 (D6 6절, D2 AD-389, AD-393).
 *
 * <p>제약을 자료에 거는 일은 여기서 보지 않는다 (B-17). 지어낸 이름의 작은 Pack 을 쓴다. 그 위에 저장소의 Pack 의
 * 제약 파일이 모두 읽히는지 본다. 특정 Pack 의 이름과 수는 시험에 적지 않는다.
 */
class PackConstraintsTest {

    private static final Instant LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final String PREFIXES = """
            @prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:    <http://www.w3.org/2002/07/owl#> .
            @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .
            @prefix sh:     <http://www.w3.org/ns/shacl#> .
            @prefix tabnit: <urn:tabnit:vocab:> .
            """;

    /** 유형 Base 아래에 Sub, 따로 Other. Base 에서 Other 로 가는 관계 하나, 속성 셋. */
    private static final String ONTOLOGY = """
            ex:Base a owl:Class ; rdfs:label "바탕" .
            ex:Sub a owl:Class ; rdfs:label "아래" ; rdfs:subClassOf ex:Base .
            ex:Other a owl:Class ; rdfs:label "다른 것" .
            ex:links a owl:ObjectProperty ; rdfs:label "잇는다" ; rdfs:domain ex:Base ; rdfs:range ex:Other ;
                tabnit:impactDirection tabnit:SourceToTarget .
            ex:title a owl:DatatypeProperty ; rdfs:label "제목" ; rdfs:domain ex:Base ; rdfs:range xsd:string .
            ex:count a owl:DatatypeProperty ; rdfs:label "수" ; rdfs:domain ex:Base ; rdfs:range xsd:integer .
            ex:otherName a owl:DatatypeProperty ; rdfs:label "다른 이름" ; rdfs:domain ex:Other ; rdfs:range xsd:string .
            """;

    /** 받는 제약: 개수, 자료형, 범위, 그리고 지금 Pack 들이 쓰지 않는 표현 몇 가지. */
    private static final String GOOD = """
            ex:BaseShape a sh:NodeShape ;
                sh:targetClass ex:Base ;
                sh:property [ sh:path ex:title ; sh:minCount 1 ; sh:datatype xsd:string ; sh:maxLength 40 ;
                              sh:pattern "^[^ ]" ; sh:message "제목은 빈칸으로 시작하지 않습니다" ] ;
                sh:property [ sh:path ex:count ; sh:minInclusive 0 ; sh:maxExclusive 1000 ; sh:in ( 1 2 3 ) ] ;
                sh:property [ sh:path ex:links ; sh:class ex:Other ; sh:maxCount 3 ] ;
                sh:or ( [ sh:path ex:title ; sh:minCount 1 ] [ sh:path ex:count ; sh:minCount 1 ] ) ;
                sh:not [ sh:path ex:count ; sh:hasValue 13 ] ;
                sh:severity sh:Warning ; sh:nodeKind sh:IRI ; sh:closed false .
            ex:NamedPart a sh:PropertyShape ; sh:path ex:count ; sh:maxCount 1 .
            ex:LinkedShape a sh:NodeShape ;
                sh:targetClass ex:Sub ;
                sh:property ex:NamedPart ;
                sh:node ex:BaseShape ;
                sh:xone ( [ sh:path ex:title ; sh:minLength 1 ] [ sh:path ex:title ; sh:maxCount 0 ] ) ;
                sh:property [ sh:path ex:links ; sh:qualifiedValueShape [ sh:class ex:Other ] ; sh:qualifiedMinCount 0 ] .
            ex:OtherShape a sh:NodeShape ;
                sh:targetSubjectsOf ex:otherName ;
                sh:property [ sh:path [ sh:inversePath ex:links ] ; sh:minCount 1 ] ;
                sh:property [ sh:path ( [ sh:inversePath ex:links ] ex:title ) ; sh:maxCount 5 ] .
            """;

    private final PackLoader loader = new PackLoader(PackTestDatabase.dataSource());

    private final PackDefinitions definitions = new PackDefinitions(PackTestDatabase.dataSource());

    @TempDir
    Path temp;

    @Test
    void 제약_파일을_읽어_글_그대로_저장하고_공개_API로_돌려준다() throws IOException {
        Path dir = pack("c-read", GOOD);
        String text = Files.readString(dir.resolve("constraints.ttl"), StandardCharsets.UTF_8);

        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);

        assertThat(definitions.find("c-read").orElseThrow().constraints()).isEqualTo(text);
        // 같은 것을 다시 적재하면 바뀐 것이 없다.
        assertThat(loader.load(dir, LOADED_AT.plusSeconds(60)).outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
    }

    @Test
    void 제약만_고친_Pack_은_판이_들어온_뒤에도_다시_적재된다() throws IOException {
        Path dir = pack("c-reload", GOOD);
        // 입력 자료 하나. 판이 들어온 것으로 알린다: 그 매핑은 잠긴다.
        Files.createDirectories(dir.resolve("mappings"));
        String mapping = PREFIXES + """
                @prefix ex: <urn:tabnit:pack:c-reload:> .
                ex:m a tabnit:Mapping ;
                    tabnit:inputSourceId "src" ;
                    tabnit:fileFormat tabnit:Csv ;
                    tabnit:itemKeyColumns ( "code" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Base ; tabnit:keyColumn "code" ] ;
                    tabnit:entity [ tabnit:entityType ex:Base ; tabnit:keyColumn "code" ] .
                """;
        Files.writeString(dir.resolve("mappings/src.ttl"), mapping, StandardCharsets.UTF_8);
        loader.load(dir, LOADED_AT, List.of());

        constraints(dir, GOOD.replace("sh:maxLength 40", "sh:maxLength 80"));
        assertThat(loader.load(dir, LOADED_AT.plusSeconds(60), List.of("src")).outcome())
            .isEqualTo(PackLoadResult.Outcome.UPDATED);
        assertThat(definitions.find("c-reload").orElseThrow().constraints()).contains("sh:maxLength 80");
        // 제약을 모두 지워도 된다 (빈 파일).
        Files.writeString(dir.resolve("constraints.ttl"), "", StandardCharsets.UTF_8);
        assertThat(loader.load(dir, LOADED_AT.plusSeconds(120), List.of("src")).outcome())
            .isEqualTo(PackLoadResult.Outcome.UPDATED);
        assertThat(definitions.find("c-reload").orElseThrow().constraints()).isEmpty();
        // 잠긴 매핑을 함께 고치면 여전히 거부된다: 제약이 그 확인을 비켜 가게 하지 않는다.
        constraints(dir, GOOD);
        Files.writeString(dir.resolve("mappings/src.ttl"), mapping.replace("( \"code\" )", "( \"other\" )")
            .replace("tabnit:keyColumn \"code\"", "tabnit:keyColumn \"other\""), StandardCharsets.UTF_8);
        assertThat(catchThrowableOfType(PackChangeRejectedException.class,
                () -> loader.load(dir, LOADED_AT.plusSeconds(180), List.of("src")))).isNotNull();
    }

    @Test
    void 제약_파일이_없으면_거부하고_비어_있거나_주석만_있으면_받는다() throws IOException {
        Path none = pack("c-none", GOOD);
        Files.delete(none.resolve("constraints.ttl"));
        assertThat(problemsOf(none)).singleElement().satisfies(problem -> {
            assertThat(problem.file()).isEqualTo("constraints.ttl");
            assertThat(problem.message()).contains("파일이 없습니다").contains("빈 파일");
        });

        Path empty = pack("c-empty", GOOD);
        Files.writeString(empty.resolve("constraints.ttl"), "", StandardCharsets.UTF_8);
        assertThat(loader.load(empty, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);

        Path comments = pack("c-comments", GOOD);
        Files.writeString(comments.resolve("constraints.ttl"),
                "# 제약이 없다\n@prefix sh: <http://www.w3.org/ns/shacl#> .\n", StandardCharsets.UTF_8);
        assertThat(loader.load(comments, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    @Test
    void Turtle_문법이_틀린_제약_파일은_어느_줄인지_알리며_거부한다() throws IOException {
        Path dir = pack("c-syntax", "ex:BaseShape a sh:NodeShape ;\n    sh:targetClass ex:Base\n    sh:property [ ] .\n");

        assertThat(problemsOf(dir)).singleElement().satisfies(problem -> {
            assertThat(problem.file()).isEqualTo("constraints.ttl");
            assertThat(problem.message()).contains("Turtle").contains("행");
        });
    }

    @Test
    void 제약이_가리키는_유형과_속성과_관계가_온톨로지에_없으면_모두_알리며_거부한다() throws IOException {
        Path dir = pack("c-refs", """
                ex:AShape a sh:NodeShape ;
                    sh:targetClass ex:NoType ;
                    sh:property [ sh:path ex:noAttribute ; sh:minCount 1 ] .
                ex:BShape a sh:NodeShape ;
                    sh:targetClass ex:Base ;
                    sh:property [ sh:path ex:links ; sh:class ex:NoClass ] ;
                    sh:property [ sh:path ( ex:links ex:noStep ) ; sh:minCount 1 ] ;
                    sh:property [ sh:path [ sh:inversePath ex:noInverse ] ; sh:minCount 1 ] ;
                    sh:property [ sh:path ex:title ; sh:equals ex:noOther ] ;
                    sh:node ex:NoShape .
                ex:CShape a sh:NodeShape ;
                    sh:targetObjectsOf ex:noRelation ;
                    sh:targetSubjectsOf <urn:other:thing> .
                ex:DShape a sh:NodeShape ;
                    sh:targetClass ex:Base ;
                    sh:property [ sh:path <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ; sh:minCount 1 ] .
                """);

        List<PackProblem> problems = problemsOf(dir);

        assertThat(problems).allSatisfy(problem -> assertThat(problem.file()).isEqualTo("constraints.ttl"));
        // 어느 제약의 어느 항목이 무엇을 가리켜 걸렸는지를 정확히 본다. 맞게 가리킨 것(Base, title, links)은 없다.
        String at = "<urn:tabnit:pack:c-refs:";
        assertThat(problems).extracting(PackProblem::item, problem -> problem.message().substring(0, problem.message().indexOf(" 이(가) ", problem.message().indexOf("가리키는")) ))
            .containsExactlyInAnyOrder(tuple("AShape", "sh:targetClass 이(가) 가리키는 " + at + "NoType>"),
                    tuple("AShape", "sh:path 이(가) 가리키는 " + at + "noAttribute>"),
                    tuple("BShape", "sh:class 이(가) 가리키는 " + at + "NoClass>"),
                    tuple("BShape", "sh:path 이(가) 가리키는 " + at + "noStep>"),
                    tuple("BShape", "sh:path 이(가) 가리키는 " + at + "noInverse>"),
                    tuple("BShape", "sh:equals 이(가) 가리키는 " + at + "noOther>"),
                    tuple("BShape", "sh:node 이(가) 가리키는 " + at + "NoShape>"),
                    tuple("CShape", "sh:targetObjectsOf 이(가) 가리키는 " + at + "noRelation>"),
                    tuple("CShape", "sh:targetSubjectsOf 이(가) 가리키는 <urn:other:thing>"),
                    tuple("DShape", "sh:path 이(가) 가리키는 <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"));
        // 두 번 읽어도 같은 문제가 같은 차례로 나온다.
        assertThat(problemsOf(dir)).isEqualTo(problems);
    }

    @Test
    void SPARQL_과_JavaScript_와_규칙과_들여오기는_받지_않는다() throws IOException {
        // 적은 것, 문구에 나와야 하는 낱말, 문제의 항목.
        List<String[]> refused = List.of(
                new String[] { "ex:S a sh:NodeShape ; sh:targetClass ex:Base ; sh:sparql [ sh:select \"SELECT $this WHERE { $this ?p ?o }\" ] .", "sh:sparql", "S" },
                new String[] { "ex:S a sh:NodeShape ; sh:target [ a sh:SPARQLTarget ; sh:select \"SELECT ?this WHERE { ?this ?p ?o }\" ] .", "sh:SPARQLTarget", "S" },
                new String[] { "ex:S a sh:NodeShape ; sh:targetClass ex:Base ; sh:sparql [ sh:ask \"ASK { ?s ?p ?o }\" ; sh:prefixes ex:P ] .", "sh:prefixes", "S" },
                new String[] { "ex:P sh:declare [ sh:prefix \"ex\" ; sh:namespace \"urn:x:\" ] .", "sh:declare", "P" },
                new String[] { "ex:S a sh:NodeShape ; sh:targetClass ex:Base ; sh:js [ sh:jsFunctionName \"check\" ; sh:jsLibrary [ sh:jsLibraryURL \"http://example.invalid/a.js\" ] ] .", "sh:jsLibraryURL", "S" },
                new String[] { "ex:S a sh:NodeShape ; sh:targetClass ex:Base ; sh:rule [ a sh:TripleRule ; sh:subject sh:this ; sh:predicate ex:title ; sh:object \"x\" ; sh:condition ex:S ] .", "sh:rule", "S" },
                new String[] { "ex:C a sh:ConstraintComponent ; sh:parameter [ sh:path ex:title ] ; sh:validator [ a sh:SPARQLAskValidator ; sh:ask \"ASK { ?s ?p ?o }\" ] .", "sh:validator", "C" },
                new String[] { "ex:F a sh:SPARQLFunction ; sh:select \"SELECT ?x WHERE { ?x ?p ?o }\" .", "sh:SPARQLFunction", "F" },
                new String[] { "ex:S a sh:NodeShape ; sh:targetClass ex:Base ; sh:expression [ sh:path ex:title ] .", "sh:expression", "S" },
                new String[] { "<urn:tabnit:pack:c-refused:shapes> owl:imports <http://example.invalid/shapes> .", "owl:imports", "shapes" });
        for (String[] one : refused) {
            Path dir = pack("c-refused", one[0] + "\n");

            List<PackProblem> problems = problemsOf(dir);

            // 받지 않는 것 하나로만 알린다: SHACL 로 읽어 보기 전에 거부한다.
            assertThat(problems).as(one[0]).singleElement().satisfies(problem -> {
                assertThat(problem.file()).isEqualTo("constraints.ttl");
                assertThat(problem.item()).isEqualTo(one[2]);
                assertThat(problem.message()).contains(one[1]).contains("받지 않습니다");
            });
        }
    }

    @Test
    void SHACL_로_읽을_수_없는_제약은_어느_제약인지_알리며_거부하고_문구가_읽을_때마다_같다() throws IOException {
        // 경로가 둘인 속성의 제약, 경로가 없는 속성의 제약, 정수가 아닌 개수. 읽히는 제약 하나가 섞여 있다.
        List<String> bodies = List.of(
                "ex:TwoPaths a sh:NodeShape ; sh:targetClass ex:Base ; sh:property [ sh:path ex:title , ex:count ; sh:minCount 1 ] .",
                "ex:NoPath a sh:NodeShape ; sh:targetClass ex:Base ; sh:property [ sh:minCount 1 ] .",
                "ex:BadCount a sh:NodeShape ; sh:targetClass ex:Base ; sh:property [ sh:path ex:title ; sh:minCount \"x\" ] .");
        for (String body : bodies) {
            Path dir = pack("c-shacl", body + "\nex:Fine a sh:NodeShape ; sh:targetClass ex:Other ;"
                    + " sh:property [ sh:path ex:otherName ; sh:minCount 1 ] .\n");

            List<PackProblem> problems = problemsOf(dir);

            assertThat(problems).as(body).singleElement().satisfies(problem -> {
                assertThat(problem.file()).isEqualTo("constraints.ttl");
                // 읽히지 않는 제약의 이름이 항목이다. 읽히는 제약은 알리지 않는다.
                assertThat(body).startsWith("ex:" + problem.item() + " ");
                assertThat(problem.message()).contains("SHACL").doesNotContain("_:");
            });
            // 이름 없는 정의의 이름은 읽을 때마다 달라진다. 문구에 섞이면 여기서 걸린다.
            assertThat(problemsOf(dir)).as(body).isEqualTo(problems);
        }
    }

    @Test
    void 매우_깊게_겹치거나_제_자신을_가리키는_제약도_멈추지_않고_문제로_알리거나_받는다() throws IOException {
        // 수천 겹의 sh:not. 읽는 쪽이 겹마다 제 자신을 부르면 스택이 넘친다.
        StringBuilder deep = new StringBuilder("ex:Deep a sh:NodeShape ; sh:targetClass ex:Base ; ");
        int depth = 20_000;
        for (int i = 0; i < depth; i++) {
            deep.append("sh:not [ ");
        }
        deep.append("sh:path ex:title ; sh:minCount 1 ");
        deep.append("] ".repeat(depth)).append(".\n");
        // 제 자신을 가리키는 이름 없는 정의: 경로와 목록.
        List<String> bodies = List.of(deep.toString(),
                "ex:Loop a sh:NodeShape ; sh:targetClass ex:Base ; sh:property [ sh:path _:p ; sh:minCount 1 ] .\n_:p sh:inversePath _:p .\n",
                "ex:Loop a sh:NodeShape ; sh:targetClass ex:Base ; sh:property [ sh:path ex:count ; sh:in _:l ] .\n_:l <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> 1 ; <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> _:l .\n",
                "ex:Loop a sh:NodeShape ; sh:targetClass ex:Base ; sh:node ex:Loop ; sh:not [ sh:node ex:Loop ] .\n");
        for (String body : bodies) {
            Path dir = pack("c-deep", body);
            String shown = body.substring(0, Math.min(80, body.length()));

            // 끝나야 한다. 받든 거부하든, 거부하면 제약 파일의 문제로 알린다 (예기치 않은 오류로 끝나지 않는다).
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(60), () -> {
                try {
                    loader.load(dir, LOADED_AT);
                }
                catch (PackInvalidException e) {
                    assertThat(e.problems()).as(shown).isNotEmpty()
                        .allSatisfy(problem -> assertThat(problem.file()).isEqualTo("constraints.ttl"));
                }
            }, shown);
        }
    }

    @Test
    void BOM_이_있는_제약_파일은_그_까닭을_알리며_거부한다() throws IOException {
        Path dir = pack("c-bom", GOOD);
        Files.writeString(dir.resolve("constraints.ttl"),
                "\uFEFF" + Files.readString(dir.resolve("constraints.ttl"), StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(problemsOf(dir)).singleElement()
            .satisfies(problem -> assertThat(problem.message()).contains("BOM"));
    }

    @Test
    void NUL_이_든_제약_파일은_저장할_수_없어_거부한다() throws IOException {
        Path dir = pack("c-nul", "# 주석 \u0000\n" + GOOD);

        assertThat(problemsOf(dir)).isNotEmpty()
            .allSatisfy(problem -> assertThat(problem.file()).isEqualTo("constraints.ttl"));
    }

    @Test
    void 저장소에_들어_있는_Pack_의_제약_파일이_모두_읽힌다() throws IOException {
        Path packs = Path.of("..", "..", "packs").toAbsolutePath().normalize();
        try (Stream<Path> found = Files.list(packs)) {
            List<Path> dirs = found.filter(Files::isDirectory).sorted().toList();
            assertThat(dirs).isNotEmpty();
            for (Path dir : dirs) {
                PackReader.Result read = PackReader.read(dir, LOADED_AT);
                assertThat(read.problems()).as(dir.getFileName().toString()).isEmpty();
                assertThat(read.definition().constraints()).as(dir.getFileName().toString())
                    .isEqualTo(Files.readString(dir.resolve("constraints.ttl"), StandardCharsets.UTF_8));
            }
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private Path pack(String packId, String constraintsBody) throws IOException {
        Path dir = Files.createTempDirectory(temp, "p").resolve(packId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pack.ttl"),
                PREFIXES + "<urn:tabnit:pack:" + packId + ":> a tabnit:Pack ; tabnit:packId \"" + packId
                        + "\" ; rdfs:label \"이름표\" ; tabnit:defaultMaxDepth 1 .\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("ontology.ttl"),
                PREFIXES + "@prefix ex: <urn:tabnit:pack:" + packId + ":> .\n" + ONTOLOGY, StandardCharsets.UTF_8);
        constraints(dir, constraintsBody);
        return dir;
    }

    private static void constraints(Path dir, String body) throws IOException {
        Files.writeString(dir.resolve("constraints.ttl"),
                PREFIXES + "@prefix ex: <urn:tabnit:pack:" + dir.getFileName() + ":> .\n" + body, StandardCharsets.UTF_8);
    }

    private List<PackProblem> problemsOf(Path dir) {
        PackInvalidException e = catchThrowableOfType(PackInvalidException.class, () -> loader.load(dir, LOADED_AT));
        assertThat(e).as("거부돼야 하는 Pack 이 적재됐다: " + dir).isNotNull();
        return e.problems();
    }
}
