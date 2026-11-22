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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SK-04 수용 기준: Pack 을 적재하면 Pack 의 정의(개체 유형, 관계 유형, 속성)가 저장되고 pack 의 공개 API 로 읽힌다.
 *
 * <p>일반 시험을 먼저 둔다 (지어낸 이름의 작은 Pack: 가장 작은 것, 빈 것, 틀린 것, 다시 넣기).
 * 그 위에 저장소에 들어 있는 Pack 폴더를 모두 적재해 본다. 특정 Pack 의 이름과 수는 시험에 적지 않는다.
 */
class PackLoaderTest {

    private static final Instant LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final String PREFIXES = """
            @prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:    <http://www.w3.org/2002/07/owl#> .
            @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .
            @prefix tabnit: <urn:tabnit:vocab:> .
            """;

    private final PackLoader loader = new PackLoader(PackTestDatabase.dataSource());

    private final PackDefinitions definitions = new PackDefinitions(PackTestDatabase.dataSource());

    private final JdbcTemplate jdbc = new JdbcTemplate(PackTestDatabase.dataSource());

    @TempDir
    Path temp;

    // ── 적재와 읽기 ─────────────────────────────────────────────

    @Test
    void 유형_하나뿐인_가장_작은_Pack이_적재되고_읽힌다() throws IOException {
        Path dir = pack("p-min", 2, "ex:Thing a owl:Class ; rdfs:label \"것\" .");

        PackLoadResult result = loader.load(dir, LOADED_AT);
        PackDefinition found = definitions.find("p-min").orElseThrow();

        assertThat(result.packId()).isEqualTo("p-min");
        assertThat(result.outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
        assertThat(found.packId()).isEqualTo("p-min");
        assertThat(found.label()).isEqualTo("이름표 p-min");
        assertThat(found.defaultMaxDepth()).isEqualTo(2);
        assertThat(found.loadedAt()).isEqualTo(LOADED_AT);
        assertThat(found.entityTypes()).containsExactly(new EntityTypeDefinition("Thing", "것", List.of(), null));
        assertThat(found.relationTypes()).isEmpty();
        assertThat(found.attributes()).isEmpty();
    }

    @Test
    void 개체_유형이_하나도_없는_Pack은_거부하고_관계_유형과_속성이_없는_것은_받는다() throws IOException {
        Path empty = pack("p-empty", 1, "");
        Path typeOnly = pack("p-type-only", 1, "ex:A a owl:Class ; rdfs:label \"가\" .");

        assertThat(problemsOf(empty)).singleElement().satisfies(p -> {
            assertThat(p.file()).isEqualTo("ontology.ttl");
            assertThat(p.message()).contains("개체 유형");
        });
        assertThat(definitions.find("p-empty")).isEmpty();
        assertThat(loader.load(typeOnly, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    @Test
    void 개체_유형과_관계_유형과_속성이_D6의_항목대로_읽힌다() throws IOException {
        Path dir = pack("p-full", 3, """
                ex:Base a owl:Class ; rdfs:label "바탕" ; tabnit:displayNameProperty ex:title .
                ex:Other a owl:Class ; rdfs:label "다른 바탕" .
                ex:Child a owl:Class ; rdfs:label "아래" ; rdfs:subClassOf ex:Other , ex:Base .
                ex:Far a owl:Class ; rdfs:label "먼 것" .

                ex:s2t a owl:ObjectProperty ; rdfs:label "앞으로" ; rdfs:domain ex:Base ; rdfs:range ex:Far ;
                    tabnit:impactDirection tabnit:SourceToTarget .
                ex:t2s a owl:ObjectProperty ; rdfs:label "뒤로" ; rdfs:domain ex:Far ; rdfs:range ex:Child ;
                    tabnit:impactDirection tabnit:TargetToSource .
                ex:both a owl:ObjectProperty ; rdfs:label "양쪽" ; rdfs:domain ex:Far ; rdfs:range ex:Far ;
                    tabnit:impactDirection tabnit:Both .
                ex:none a owl:ObjectProperty ; rdfs:label "안 퍼짐" ; rdfs:domain ex:Base ; rdfs:range ex:Base ;
                    tabnit:impactDirection tabnit:None .

                ex:title a owl:DatatypeProperty ; rdfs:label "제목" ; rdfs:domain ex:Base ; rdfs:range xsd:string .
                ex:count a owl:DatatypeProperty ; rdfs:label "수" ; rdfs:domain ex:Far ; rdfs:range xsd:integer .
                ex:ratio a owl:DatatypeProperty ; rdfs:label "비율" ; rdfs:domain ex:Far ; rdfs:range xsd:decimal .
                ex:since a owl:DatatypeProperty ; rdfs:label "시작" ; rdfs:domain ex:Far ; rdfs:range xsd:date .
                ex:active a owl:DatatypeProperty ; rdfs:label "쓰는가" ; rdfs:domain ex:Far ; rdfs:range xsd:boolean .
                """);

        loader.load(dir, LOADED_AT);
        PackDefinition found = definitions.find("p-full").orElseThrow();

        // 이름의 글자 코드 순이다. 상위 유형도 이름 순이다.
        assertThat(found.entityTypes()).containsExactly(new EntityTypeDefinition("Base", "바탕", List.of(), "title"),
                new EntityTypeDefinition("Child", "아래", List.of("Base", "Other"), null),
                new EntityTypeDefinition("Far", "먼 것", List.of(), null),
                new EntityTypeDefinition("Other", "다른 바탕", List.of(), null));
        assertThat(found.relationTypes()).containsExactly(
                new RelationTypeDefinition("both", "양쪽", "Far", "Far", PackImpactDirection.BOTH),
                new RelationTypeDefinition("none", "안 퍼짐", "Base", "Base", PackImpactDirection.NONE),
                new RelationTypeDefinition("s2t", "앞으로", "Base", "Far", PackImpactDirection.SOURCE_TO_TARGET),
                new RelationTypeDefinition("t2s", "뒤로", "Far", "Child", PackImpactDirection.TARGET_TO_SOURCE));
        assertThat(found.attributes()).containsExactly(
                new AttributeDefinition("active", "쓰는가", "Far", ValueFormat.BOOLEAN),
                new AttributeDefinition("count", "수", "Far", ValueFormat.INTEGER),
                new AttributeDefinition("ratio", "비율", "Far", ValueFormat.DECIMAL),
                new AttributeDefinition("since", "시작", "Far", ValueFormat.DATE),
                new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING));
    }

    @Test
    void 이름은_글자_코드_순으로_돌려주고_두_번_읽어도_같다() throws IOException {
        Path dir = pack("p-order", 1, """
                ex:a a owl:Class ; rdfs:label "소문자" .
                ex:가 a owl:Class ; rdfs:label "한글" .
                ex:B a owl:Class ; rdfs:label "대문자" .
                """);

        loader.load(dir, LOADED_AT);
        PackDefinition first = definitions.find("p-order").orElseThrow();
        PackDefinition second = definitions.find("p-order").orElseThrow();

        assertThat(first.entityTypes()).extracting(EntityTypeDefinition::name).containsExactly("B", "a", "가");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void 적재하지_않은_Pack은_없다고_답한다() {
        assertThat(definitions.find("p-never-loaded")).isEmpty();
    }

    @Test
    void Pack_둘은_서로_섞이지_않는다() throws IOException {
        loader.load(pack("p-one", 1, "ex:Same a owl:Class ; rdfs:label \"하나의 것\" ."), LOADED_AT);
        loader.load(pack("p-two", 4, "ex:Same a owl:Class ; rdfs:label \"둘의 것\" ."), LOADED_AT);

        assertThat(definitions.find("p-one").orElseThrow().entityTypes())
            .containsExactly(new EntityTypeDefinition("Same", "하나의 것", List.of(), null));
        assertThat(definitions.find("p-two").orElseThrow().defaultMaxDepth()).isEqualTo(4);
    }

    @Test
    void 적재된_Pack_의_식별자를_글자_코드_순으로_돌려준다() throws IOException {
        // 넣는 차례는 일부러 섞는다. 숫자가 글자보다 앞서고, 수가 아니라 글자로 견준다 (10 이 2 보다 앞선다).
        for (String packId : List.of("p-ids-b", "p-ids-a", "p-ids-10", "p-ids-2")) {
            loader.load(pack(packId, 1, "ex:Thing a owl:Class ; rdfs:label \"것\" ."), LOADED_AT);
        }

        List<String> first = definitions.packIds();
        List<String> second = definitions.packIds();

        // 이 데이터베이스에는 다른 시험이 넣은 Pack 도 있다. 이 시험이 넣은 것만 골라 차례를 본다.
        assertThat(first.stream().filter(id -> id.startsWith("p-ids-")))
            .containsExactly("p-ids-10", "p-ids-2", "p-ids-a", "p-ids-b");
        assertThat(first).doesNotHaveDuplicates().isSortedAccordingTo(PackOrder.STRINGS);
        assertThat(second).isEqualTo(first);
        // 식별자가 있으면 정의도 읽힌다.
        assertThat(definitions.find("p-ids-a")).isPresent();
    }

    @Test
    void Turtle을_적는_여러_방법이_같은_정의로_읽힌다() throws IOException {
        // 주석, 접두사 없이 전체 주소로 적기, rdf:type 풀어 적기, 한 정의를 여러 곳에 나눠 적기, 한글 이름.
        Path dir = pack("p-styles", 1, """
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                # ex:Ghost a owl:Class ; rdfs:label "주석 안의 것은 읽지 않는다" .
                <urn:tabnit:pack:p-styles:설비> rdf:type owl:Class .
                ex:설비 rdfs:label "설비" .
                ex:하위설비 a owl:Class ; rdfs:label "하위 설비" ; rdfs:subClassOf ex:설비 , ex:설비 .
                """);

        loader.load(dir, LOADED_AT);

        assertThat(definitions.find("p-styles").orElseThrow().entityTypes()).containsExactly(
                new EntityTypeDefinition("설비", "설비", List.of(), null),
                new EntityTypeDefinition("하위설비", "하위 설비", List.of("설비"), null));
    }

    @Test
    void 최대_깊이는_상한까지_받고_넘으면_상한을_알리며_거부한다() throws IOException {
        // 상한은 20 이다 (D2 AD-333). 잘못 적은 값 하나로 영향 범위의 계산이 오래 멈추지 않게 한다.
        Path atLimit = pack("p-depth-limit", 20, "ex:A a owl:Class ; rdfs:label \"가\" .");
        loader.load(atLimit, LOADED_AT);
        assertThat(definitions.find("p-depth-limit").orElseThrow().defaultMaxDepth()).isEqualTo(20);

        for (int depth : List.of(21, 800, 2147483647)) {
            Path over = pack("p-depth-over", depth, "ex:A a owl:Class ; rdfs:label \"가\" .");
            assertThat(problemsOf(over)).as("깊이 %d", depth).singleElement()
                .satisfies(problem -> assertThat(problem.message()).contains("defaultMaxDepth").contains("20"));
            assertThat(definitions.find("p-depth-over")).isEmpty();
        }
        // 정수 형식에 담기지 않는 큰 수도 상한을 알린다.
        Path huge = pack("p-depth-huge", 1, "ex:A a owl:Class ; rdfs:label \"가\" .");
        write(huge, "pack.ttl", PREFIXES + "<urn:tabnit:pack:p-depth-huge:> a tabnit:Pack ; tabnit:packId \"p-depth-huge\" ; "
                + "rdfs:label \"이름표\" ; tabnit:defaultMaxDepth 99999999999 .\n");
        assertThat(problemsOf(huge)).singleElement()
            .satisfies(problem -> assertThat(problem.message()).contains("defaultMaxDepth").contains("20"));
    }

    @Test
    void 최대_깊이와_이름표의_경계_값은_거부한다() throws IOException {
        for (String depth : List.of("0", "-1", "2.5", "\"3\"", "99999999999")) {
            Path dir = pack("p-edge", 1, "ex:A a owl:Class ; rdfs:label \"가\" .");
            write(dir, "pack.ttl", PREFIXES + "<urn:tabnit:pack:p-edge:> a tabnit:Pack ; tabnit:packId \"p-edge\" ; "
                    + "rdfs:label \"이름표\" ; tabnit:defaultMaxDepth " + depth + " .\n");
            assertThat(problemsOf(dir)).as(depth).singleElement()
                .satisfies(p -> assertThat(p.message()).contains("defaultMaxDepth"));
        }
        for (String label : List.of("\"\"", "5", "ex:A", "\"\"@en , \"가\"")) {
            Path dir = pack("p-edge-label", 1, "ex:A a owl:Class ; rdfs:label " + label + " .");
            assertThat(problemsOf(dir)).as(label).singleElement()
                .satisfies(p -> assertThat(p.message()).contains("rdfs:label"));
        }
    }

    @Test
    void 상위_유형의_사슬이_아주_길어도_읽고_그_끝이_돌아오면_알린다() throws IOException {
        int length = 20000;
        StringBuilder chain = new StringBuilder("ex:T0 a owl:Class ; rdfs:label \"0\" ; tabnit:displayNameProperty ex:n .\n"
                + "ex:n a owl:DatatypeProperty ; rdfs:label \"이름\" ; rdfs:domain ex:T0 ; rdfs:range xsd:string .\n");
        for (int i = 1; i < length; i++) {
            chain.append("ex:T").append(i).append(" a owl:Class ; rdfs:label \"").append(i)
                .append("\" ; rdfs:subClassOf ex:T").append(i - 1).append(" .\n");
        }
        // 저장소까지 가지 않고 읽기와 검증만 본다.
        PackReader.Result straight = PackReader.read(pack("p-chain", 1, chain.toString()), LOADED_AT);
        PackReader.Result looped = PackReader.read(
                pack("p-chain-loop", 1, chain + "ex:T0 rdfs:subClassOf ex:T" + (length - 1) + " .\n"), LOADED_AT);

        assertThat(straight.problems()).isEmpty();
        assertThat(straight.definition().entityTypes()).hasSize(length);
        assertThat(looped.definition()).isNull();
        // 고리 하나에 한 건이다. 긴 경로는 줄여 적는다.
        assertThat(looped.problems()).singleElement().satisfies(p -> {
            assertThat(p.item()).isEqualTo("T0");
            assertThat(p.message()).contains("돌아").contains("개 더").hasSizeLessThan(400);
        });
    }

    // ── 이름표 (D2 AD-131) ────────────────────────────────────────

    @Test
    void 기본_이름표_위에_언어별_이름표를_더_적을_수_있고_공개_API로_읽힌다() throws IOException {
        Path dir = pack("p-lang", 1, """
                ex:A a owl:Class ; rdfs:label "가" , "A"@en , "ア"@JA-jp .
                ex:n a owl:DatatypeProperty ; rdfs:label "이름" , "name"@en ; rdfs:domain ex:A ; rdfs:range xsd:string .
                """);
        write(dir, "pack.ttl", PREFIXES + """
                <urn:tabnit:pack:p-lang:> a tabnit:Pack ; tabnit:packId "p-lang" ;
                    rdfs:label "언어 Pack" , "Language pack"@en ; tabnit:defaultMaxDepth 1 .
                """);

        loader.load(dir, LOADED_AT);
        PackDefinition found = definitions.find("p-lang").orElseThrow();

        // 기본 이름표는 그대로다. 언어별 이름표는 이름, 언어 순이고 언어 표시는 소문자다. Pack 자신은 이름이 빈 글자다.
        assertThat(found.label()).isEqualTo("언어 Pack");
        assertThat(found.entityTypes().get(0).label()).isEqualTo("가");
        assertThat(found.localizedLabels()).containsExactly(new LocalizedLabel("", "en", "Language pack"),
                new LocalizedLabel("A", "en", "A"), new LocalizedLabel("A", "ja-jp", "ア"),
                new LocalizedLabel("n", "en", "name"));
    }

    @Test
    void 기본_이름표가_없거나_둘이거나_같은_언어가_둘이면_거부한다() throws IOException {
        for (String labels : List.of("\"A\"@en", "\"가\" , \"나\"", "\"가\" , \"A\"@en , \"B\"@en",
                "\"가\" , \"A\"@ko-KR , \"B\"@ko-kr")) {
            Path dir = pack("p-lang-bad", 1, "ex:A a owl:Class ; rdfs:label " + labels + " .");
            assertThat(problemsOf(dir)).as(labels).singleElement().satisfies(p -> {
                assertThat(p.item()).isEqualTo("A");
                assertThat(p.message()).contains("rdfs:label");
            });
        }
    }

    @Test
    void Pack_자신의_이름표도_기본_이름표가_있어야_한다() throws IOException {
        Path dir = pack("p-lang-pack", 1, "ex:A a owl:Class ; rdfs:label \"가\" .");
        write(dir, "pack.ttl", PREFIXES + """
                <urn:tabnit:pack:p-lang-pack:> a tabnit:Pack ; tabnit:packId "p-lang-pack" ;
                    rdfs:label "Only English"@en ; tabnit:defaultMaxDepth 1 .
                """);

        assertThat(problemsOf(dir)).singleElement().satisfies(p -> {
            assertThat(p.file()).isEqualTo("pack.ttl");
            assertThat(p.message()).contains("rdfs:label").contains("언어 표시 없는");
        });
    }

    @Test
    void 틀린_이름표가_여럿이면_모두_알리고_적는_차례와_무관하다() throws IOException {
        Path one = pack("p-lang-many", 1, "ex:A a owl:Class ; rdfs:label \"\" , 5 , \"X\"@en , \"Y\"@EN , \"\"@ja .");
        Path other = pack("p-lang-many", 1, "ex:A a owl:Class ; rdfs:label \"\"@ja , \"Y\"@EN , 5 , \"X\"@en , \"\" .");

        List<PackProblem> first = problemsOf(one);

        // 빈 글자, 글자가 아닌 값, 빈 글자(언어 표시 있음), 같은 언어 둘.
        assertThat(first).hasSize(4).allSatisfy(p -> assertThat(p.item()).isEqualTo("A"));
        assertThat(problemsOf(other)).isEqualTo(first);
    }

    // ── 이름과 Pack 식별자 (D2 AD-127, AD-134) ───────────────────────

    @Test
    void 조합형이_아닌_이름은_거부하고_조합형_한글_이름은_받는다() throws IOException {
        // "한" 을 풀어쓴 꼴(ㅎ + ㅏ + ㄴ 의 조합용 자모)로 적었다. 눈에는 같아 보인다.
        Path decomposed = pack("p-nfd", 1, "ex:\u1112\u1161\u11AB a owl:Class ; rdfs:label \"풀어쓴 이름\" .");
        Path composed = pack("p-nfc", 1, "ex:\uD55C a owl:Class ; rdfs:label \"조합형 이름\" .");

        assertThat(problemsOf(decomposed)).singleElement()
            .satisfies(p -> assertThat(p.message()).contains("조합형"));
        // 정의는 조합형인데 가리키는 쪽만 풀어쓴 꼴이면, "정의되지 않았다"만이 아니라 조합형이 아니라고도 알린다.
        Path reference = pack("p-nfd-ref", 1, "ex:\uD55C a owl:Class ; rdfs:label \"조합형 이름\" .\n"
                + "ex:Sub a owl:Class ; rdfs:label \"아래\" ; rdfs:subClassOf ex:\u1112\u1161\u11AB .");
        assertThat(problemsOf(reference)).isNotEmpty().allSatisfy(p -> assertThat(p.item()).isEqualTo("Sub"))
            .anySatisfy(p -> assertThat(p.message()).contains("조합형"));
        // 풀어쓴 이름을 정의하고 같은 꼴로 가리키면, 문제는 그 이름 자체에 대한 것뿐이다.
        Path both = pack("p-nfd-both", 1, "ex:\u1112\u1161\u11AB a owl:Class ; rdfs:label \"풀어쓴 이름\" .\n"
                + "ex:Sub a owl:Class ; rdfs:label \"아래\" ; rdfs:subClassOf ex:\u1112\u1161\u11AB .");
        assertThat(problemsOf(both)).hasSize(2).allSatisfy(p -> assertThat(p.message()).contains("조합형"));
        loader.load(composed, LOADED_AT);
        assertThat(definitions.find("p-nfc").orElseThrow().entityTypes()).extracting(EntityTypeDefinition::name)
            .containsExactly("\uD55C");
    }

    @Test
    void Pack_식별자는_영어_소문자로_시작하고_소문자와_숫자와_붙임표만_쓴다() throws IOException {
        for (String id : List.of("Upper", "1abc", "under_score", "한글", "dot.ted", "-lead")) {
            Path dir = pack(id, 1, "ex:A a owl:Class ; rdfs:label \"가\" .");
            assertThat(problemsOf(dir)).as(id).singleElement().satisfies(p -> {
                assertThat(p.message()).contains("Pack 식별자").contains(id);
            });
        }
        assertThat(loader.load(pack("ok-2-go", 1, "ex:A a owl:Class ; rdfs:label \"가\" ."), LOADED_AT).outcome())
            .isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    // ── 표시 이름 속성 (D2 AD-132) ──────────────────────────────────

    @Test
    void 유형의_표시_이름_속성과_이름표를_묻는다_스스로_적지_않았으면_상위_유형의_것을_받는다() throws IOException {
        loader.load(pack("p-display-ask", 1, """
                ex:Top a owl:Class ; rdfs:label "위" ; tabnit:displayNameProperty ex:topName .
                ex:Mid a owl:Class ; rdfs:label "가운데" ; rdfs:subClassOf ex:Top .
                ex:Low a owl:Class ; rdfs:label "아래" ; rdfs:subClassOf ex:Mid .
                ex:Own a owl:Class ; rdfs:label "제 것" ; rdfs:subClassOf ex:Top ; tabnit:displayNameProperty ex:ownName .
                ex:Plain a owl:Class ; rdfs:label "없는 것" .
                ex:topName a owl:DatatypeProperty ; rdfs:label "위 이름" ; rdfs:domain ex:Top ; rdfs:range xsd:string .
                ex:ownName a owl:DatatypeProperty ; rdfs:label "제 이름" ; rdfs:domain ex:Own ; rdfs:range xsd:string .
                """), LOADED_AT);
        PackDefinition found = definitions.find("p-display-ask").orElseThrow();

        assertThat(found.displayNameAttributeOf("Top")).contains("topName");
        // 여러 단계 위의 것도 받는다. 스스로 적은 것이 있으면 그것이다.
        assertThat(found.displayNameAttributeOf("Mid")).contains("topName");
        assertThat(found.displayNameAttributeOf("Low")).contains("topName");
        assertThat(found.displayNameAttributeOf("Own")).contains("ownName");
        // 어디에도 적지 않았으면 없다. 없는 유형도 없다.
        assertThat(found.displayNameAttributeOf("Plain")).isEmpty();
        assertThat(found.displayNameAttributeOf("NoSuchType")).isEmpty();
        assertThat(found.entityTypeLabelOf("Low")).contains("아래");
        assertThat(found.entityTypeLabelOf("NoSuchType")).isEmpty();
        // 두 번 물어도 같다.
        assertThat(found.displayNameAttributeOf("Low")).isEqualTo(found.displayNameAttributeOf("Low"));
        assertThatThrownBy(() -> found.displayNameAttributeOf(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 표시_이름_속성은_그_유형이나_상위_유형의_속성이어야_한다() throws IOException {
        String common = """
                ex:Top a owl:Class ; rdfs:label "위" .
                ex:Mid a owl:Class ; rdfs:label "가운데" ; rdfs:subClassOf ex:Top .
                ex:Other a owl:Class ; rdfs:label "다른 것" .
                ex:topName a owl:DatatypeProperty ; rdfs:label "위 이름" ; rdfs:domain ex:Top ; rdfs:range xsd:string .
                ex:otherCount a owl:DatatypeProperty ; rdfs:label "다른 수" ; rdfs:domain ex:Other ; rdfs:range xsd:integer .
                ex:lowName a owl:DatatypeProperty ; rdfs:label "아래 이름" ; rdfs:domain ex:Low ; rdfs:range xsd:string .
                """;
        Path fromAncestor = pack("p-display-ok", 1, common
                + "ex:Low a owl:Class ; rdfs:label \"아래\" ; rdfs:subClassOf ex:Mid ; tabnit:displayNameProperty ex:topName .");
        Path fromStranger = pack("p-display-bad", 1, common
                + "ex:Low a owl:Class ; rdfs:label \"아래\" ; rdfs:subClassOf ex:Mid ; tabnit:displayNameProperty ex:otherCount .");
        // 하위 유형의 속성은 상위 유형의 표시 이름이 될 수 없다.
        Path fromChild = pack("p-display-child", 1, common
                + "ex:Low a owl:Class ; rdfs:label \"아래\" ; rdfs:subClassOf ex:Mid .\n"
                + "ex:Top tabnit:displayNameProperty ex:lowName .");

        // 상위 유형이 여럿일 때 그 가운데 하나의 속성이어도 된다.
        Path fromOneOfParents = pack("p-display-multi", 1, common
                + "ex:Low a owl:Class ; rdfs:label \"아래\" ; rdfs:subClassOf ex:Mid , ex:Other ; tabnit:displayNameProperty ex:otherCount .");
        assertThat(loader.load(fromOneOfParents, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
        assertThat(loader.load(fromAncestor, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
        assertThat(problemsOf(fromStranger)).singleElement().satisfies(p -> {
            assertThat(p.item()).isEqualTo("Low");
            assertThat(p.message()).contains("otherCount").contains("Other");
        });
        assertThat(problemsOf(fromChild)).singleElement().satisfies(p -> assertThat(p.item()).isEqualTo("Top"));
    }

    // ── 다시 넣기 ───────────────────────────────────────────────

    @Test
    void 같은_Pack을_다시_넣어도_중복되지_않고_아무것도_바뀌지_않는다() throws IOException {
        Path dir = pack("p-twice", 2, """
                ex:A a owl:Class ; rdfs:label "가" .
                ex:r a owl:ObjectProperty ; rdfs:label "관계" ; rdfs:domain ex:A ; rdfs:range ex:A ;
                    tabnit:impactDirection tabnit:Both .
                ex:n a owl:DatatypeProperty ; rdfs:label "이름" ; rdfs:domain ex:A ; rdfs:range xsd:string .
                """);
        loader.load(dir, LOADED_AT);
        PackDefinition before = definitions.find("p-twice").orElseThrow();

        PackLoadResult again = loader.load(dir, LOADED_AT.plusSeconds(3600));

        assertThat(again.outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
        assertThat(definitions.find("p-twice").orElseThrow()).isEqualTo(before);
        assertThat(rows("pack_definition", "p-twice")).isEqualTo(1);
        assertThat(rows("pack_entity_type", "p-twice")).isEqualTo(1);
        assertThat(rows("pack_relation_type", "p-twice")).isEqualTo(1);
        assertThat(rows("pack_attribute", "p-twice")).isEqualTo(1);
    }

    @Test
    void 정의를_더하거나_이름표와_영향_방향과_최대_깊이를_바꾼_Pack은_다시_받는다() throws IOException {
        loader.load(pack("p-grow", 2, """
                ex:A a owl:Class ; rdfs:label "가" .
                ex:r a owl:ObjectProperty ; rdfs:label "관계" ; rdfs:domain ex:A ; rdfs:range ex:A ;
                    tabnit:impactDirection tabnit:Both .
                """), LOADED_AT);
        Instant later = LOADED_AT.plusSeconds(60);

        PackLoadResult result = loader.load(pack("p-grow", 5, """
                ex:A a owl:Class ; rdfs:label "가 (고침)" .
                ex:B a owl:Class ; rdfs:label "나" .
                ex:r a owl:ObjectProperty ; rdfs:label "관계" ; rdfs:domain ex:A ; rdfs:range ex:A ;
                    tabnit:impactDirection tabnit:None .
                ex:n a owl:DatatypeProperty ; rdfs:label "이름" ; rdfs:domain ex:B ; rdfs:range xsd:string .
                """), later);

        PackDefinition found = definitions.find("p-grow").orElseThrow();
        assertThat(result.outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
        assertThat(found.defaultMaxDepth()).isEqualTo(5);
        assertThat(found.loadedAt()).isEqualTo(later);
        assertThat(found.entityTypes()).extracting(EntityTypeDefinition::label).containsExactly("가 (고침)", "나");
        assertThat(found.relationTypes().get(0).impactDirection()).isEqualTo(PackImpactDirection.NONE);
        assertThat(found.attributes()).hasSize(1);
    }

    @Test
    void 표시_이름_속성과_언어별_이름표를_바꾼_Pack은_다시_받는다() throws IOException {
        String common = """
                ex:first a owl:DatatypeProperty ; rdfs:label "첫 이름" ; rdfs:domain ex:A ; rdfs:range xsd:string .
                ex:second a owl:DatatypeProperty ; rdfs:label "둘째 이름" ; rdfs:domain ex:A ; rdfs:range xsd:string .
                """;
        loader.load(pack("p-display-change", 1,
                common + "ex:A a owl:Class ; rdfs:label \"가\" ; tabnit:displayNameProperty ex:first ."), LOADED_AT);

        PackLoadResult result = loader.load(pack("p-display-change", 1,
                common + "ex:A a owl:Class ; rdfs:label \"가\" , \"A\"@en ; tabnit:displayNameProperty ex:second ."),
                LOADED_AT.plusSeconds(60));

        PackDefinition found = definitions.find("p-display-change").orElseThrow();
        assertThat(result.outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
        assertThat(found.entityTypes().get(0).displayNameAttribute()).isEqualTo("second");
        assertThat(found.localizedLabels()).containsExactly(new LocalizedLabel("A", "en", "A"));

        // 언어별 이름표를 지우는 것도 이름표를 바꾸는 것이다.
        PackLoadResult removed = loader.load(pack("p-display-change", 1,
                common + "ex:A a owl:Class ; rdfs:label \"가\" ; tabnit:displayNameProperty ex:second ."),
                LOADED_AT.plusSeconds(120));
        assertThat(removed.outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
        assertThat(definitions.find("p-display-change").orElseThrow().localizedLabels()).isEmpty();
    }

    @Test
    void 상위_유형이나_관계_유형의_끝이나_속성의_개체_유형을_바꾼_Pack은_거부한다() throws IOException {
        loader.load(pack("p-reshape", 1, """
                ex:A a owl:Class ; rdfs:label "가" .
                ex:B a owl:Class ; rdfs:label "나" ; rdfs:subClassOf ex:A .
                ex:r a owl:ObjectProperty ; rdfs:label "관계" ; rdfs:domain ex:A ; rdfs:range ex:B ;
                    tabnit:impactDirection tabnit:Both .
                ex:n a owl:DatatypeProperty ; rdfs:label "이름" ; rdfs:domain ex:A ; rdfs:range xsd:string .
                """), LOADED_AT);

        assertThatThrownBy(() -> loader.load(pack("p-reshape", 1, """
                ex:A a owl:Class ; rdfs:label "가" .
                ex:B a owl:Class ; rdfs:label "나" .
                ex:r a owl:ObjectProperty ; rdfs:label "관계" ; rdfs:domain ex:B ; rdfs:range ex:B ;
                    tabnit:impactDirection tabnit:Both .
                ex:n a owl:DatatypeProperty ; rdfs:label "이름" ; rdfs:domain ex:B ; rdfs:range xsd:string .
                """), LOADED_AT.plusSeconds(60))).isInstanceOfSatisfying(PackChangeRejectedException.class,
                        e -> assertThat(e.reasons()).hasSize(3));
    }

    @Test
    void 유형을_지우거나_속성의_형식을_바꾼_Pack은_거부하고_저장된_것은_그대로다() throws IOException {
        loader.load(pack("p-shrink", 2, """
                ex:A a owl:Class ; rdfs:label "가" .
                ex:B a owl:Class ; rdfs:label "나" .
                ex:n a owl:DatatypeProperty ; rdfs:label "이름" ; rdfs:domain ex:A ; rdfs:range xsd:string .
                """), LOADED_AT);
        PackDefinition before = definitions.find("p-shrink").orElseThrow();

        assertThatThrownBy(() -> loader.load(pack("p-shrink", 2, """
                ex:A a owl:Class ; rdfs:label "가" .
                ex:n a owl:DatatypeProperty ; rdfs:label "이름" ; rdfs:domain ex:A ; rdfs:range xsd:integer .
                """), LOADED_AT.plusSeconds(60))).isInstanceOf(PackChangeRejectedException.class)
            .hasMessageContaining("B")
            .hasMessageContaining("n");
        assertThat(definitions.find("p-shrink").orElseThrow()).isEqualTo(before);
    }

    // ── 틀린 Pack: 파일과 항목을 알리고 아무것도 넣지 않는다 ───────────────────

    @Test
    void 정의_파일이_없으면_어느_파일인지_알린다() throws IOException {
        Path noOntology = pack("p-no-onto", 1, "");
        Files.delete(noOntology.resolve("ontology.ttl"));
        Path noPack = pack("p-no-pack", 1, "");
        Files.delete(noPack.resolve("pack.ttl"));

        assertThat(problemsOf(noOntology)).extracting(PackProblem::file).containsExactly("ontology.ttl");
        assertThat(problemsOf(noPack)).extracting(PackProblem::file).contains("pack.ttl");
        assertThat(definitions.find("p-no-onto")).isEmpty();
    }

    @Test
    void 없는_폴더를_주면_어느_폴더인지_알린다() {
        Path missing = temp.resolve("p-missing");

        assertThatThrownBy(() -> loader.load(missing, LOADED_AT)).isInstanceOf(PackInvalidException.class)
            .hasMessageContaining("p-missing");
    }

    @Test
    void 문법이_틀리면_파일과_줄을_알린다() throws IOException {
        Path dir = pack("p-syntax", 1, "ex:A a owl:Class ; rdfs:label \"닫지 않은 글자 .");

        List<PackProblem> problems = problemsOf(dir);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).file()).isEqualTo("ontology.ttl");
        assertThat(problems.get(0).message()).containsPattern("\\d+ *(행|줄)|line");
        assertThat(definitions.find("p-syntax")).isEmpty();
    }

    @Test
    void Pack_자신의_정보가_틀리면_알린다() throws IOException {
        Path wrongId = pack("p-id", 1, "");
        write(wrongId, "pack.ttl", PREFIXES + """
                <urn:tabnit:pack:p-id:> a tabnit:Pack ; tabnit:packId "other" ; rdfs:label "이름표" ;
                    tabnit:defaultMaxDepth 1 .
                """);
        Path zeroDepth = pack("p-depth", 0, "");
        Path noLabel = pack("p-nolabel", 1, "");
        write(noLabel, "pack.ttl", PREFIXES + """
                <urn:tabnit:pack:p-nolabel:> a tabnit:Pack ; tabnit:packId "p-nolabel" ; tabnit:defaultMaxDepth 1 .
                """);

        assertThat(problemsOf(wrongId)).anySatisfy(p -> {
            assertThat(p.file()).isEqualTo("pack.ttl");
            assertThat(p.message()).contains("other").contains("p-id");
        });
        assertThat(problemsOf(zeroDepth)).anySatisfy(p -> assertThat(p.message()).contains("defaultMaxDepth"));
        assertThat(problemsOf(noLabel)).anySatisfy(p -> assertThat(p.message()).contains("label"));
    }

    @Test
    void 관계_유형이_틀리면_어느_항목인지_알린다() throws IOException {
        Path dir = pack("p-rel", 1, """
                ex:A a owl:Class ; rdfs:label "가" .
                ex:B a owl:Class ; rdfs:label "나" .
                ex:undefinedEnd a owl:ObjectProperty ; rdfs:label "없는 유형" ; rdfs:domain ex:A ; rdfs:range ex:Missing ;
                    tabnit:impactDirection tabnit:Both .
                ex:noDirection a owl:ObjectProperty ; rdfs:label "방향 없음" ; rdfs:domain ex:A ; rdfs:range ex:B .
                ex:badDirection a owl:ObjectProperty ; rdfs:label "모르는 방향" ; rdfs:domain ex:A ; rdfs:range ex:B ;
                    tabnit:impactDirection tabnit:Sideways .
                ex:twoStarts a owl:ObjectProperty ; rdfs:label "출발 둘" ; rdfs:domain ex:A , ex:B ; rdfs:range ex:B ;
                    tabnit:impactDirection tabnit:Both .
                """);

        List<PackProblem> problems = problemsOf(dir);

        assertThat(problems).extracting(PackProblem::item)
            .containsExactly("badDirection", "noDirection", "twoStarts", "undefinedEnd");
        assertThat(problems).allSatisfy(p -> assertThat(p.file()).isEqualTo("ontology.ttl"));
        assertThat(problems.get(3).message()).contains("Missing");
        assertThat(definitions.find("p-rel")).isEmpty();
    }

    @Test
    void 속성이_틀리면_어느_항목인지_알린다() throws IOException {
        Path dir = pack("p-attr", 1, """
                ex:A a owl:Class ; rdfs:label "가" .
                ex:badFormat a owl:DatatypeProperty ; rdfs:label "형식" ; rdfs:domain ex:A ; rdfs:range xsd:dateTime .
                ex:noOwner a owl:DatatypeProperty ; rdfs:label "주인 없음" ; rdfs:range xsd:string .
                ex:noLabel a owl:DatatypeProperty ; rdfs:domain ex:A ; rdfs:range xsd:string .
                """);

        assertThat(problemsOf(dir)).extracting(PackProblem::item).containsExactly("badFormat", "noLabel", "noOwner");
    }

    @Test
    void 상위_유형이_돌아오거나_정의되지_않았으면_거부한다() throws IOException {
        Path cycle = pack("p-cycle", 1, """
                ex:A a owl:Class ; rdfs:label "가" ; rdfs:subClassOf ex:C .
                ex:B a owl:Class ; rdfs:label "나" ; rdfs:subClassOf ex:A .
                ex:C a owl:Class ; rdfs:label "다" ; rdfs:subClassOf ex:B .
                """);
        Path self = pack("p-self", 1, "ex:A a owl:Class ; rdfs:label \"가\" ; rdfs:subClassOf ex:A .");
        Path missing = pack("p-noparent", 1, "ex:A a owl:Class ; rdfs:label \"가\" ; rdfs:subClassOf ex:Nowhere .");

        assertThat(problemsOf(cycle)).singleElement().satisfies(p -> {
            assertThat(p.item()).isEqualTo("A");
            assertThat(p.message()).contains("A → C → B → A");
        });
        assertThat(problemsOf(self)).singleElement().satisfies(p -> assertThat(p.message()).contains("A → A"));
        assertThat(problemsOf(missing)).anySatisfy(p -> assertThat(p.message()).contains("Nowhere"));
    }

    @Test
    void 여러_상위_유형이_서로_다른_표시_이름_속성을_내려주면_거부하고_스스로_적으면_받는다() throws IOException {
        String common = """
                ex:Left a owl:Class ; rdfs:label "왼쪽" ; tabnit:displayNameProperty ex:leftName .
                ex:Right a owl:Class ; rdfs:label "오른쪽" ; tabnit:displayNameProperty ex:rightName .
                ex:leftName a owl:DatatypeProperty ; rdfs:label "왼 이름" ; rdfs:domain ex:Left ; rdfs:range xsd:string .
                ex:rightName a owl:DatatypeProperty ; rdfs:label "오른 이름" ; rdfs:domain ex:Right ; rdfs:range xsd:string .
                """;
        Path conflict = pack("p-conflict", 1,
                common + "ex:Child a owl:Class ; rdfs:label \"아래\" ; rdfs:subClassOf ex:Left , ex:Right .");
        Path own = pack("p-own", 1, common
                + "ex:Child a owl:Class ; rdfs:label \"아래\" ; rdfs:subClassOf ex:Left , ex:Right ; tabnit:displayNameProperty ex:leftName .");

        assertThat(problemsOf(conflict)).singleElement().satisfies(p -> {
            assertThat(p.item()).isEqualTo("Child");
            assertThat(p.message()).contains("leftName").contains("rightName");
        });
        assertThat(loader.load(own, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    @Test
    void D6에_없는_문장은_무시하지_않고_거부한다() throws IOException {
        Path dir = pack("p-unknown", 1, """
                ex:A a owl:Class ; rdfs:label "가" ; rdfs:comment "설명" .
                ex:r a owl:ObjectProperty ; rdfs:label "관계" ; rdfs:domain ex:A ; rdfs:range ex:A ;
                    tabnit:impactDirection tabnit:Both ; owl:inverseOf ex:r .
                ex:Odd a owl:Restriction .
                """);

        List<PackProblem> problems = problemsOf(dir);

        assertThat(problems).extracting(PackProblem::item).containsExactly("A", "Odd", "r");
        assertThat(problems.get(0).message()).contains("comment");
        assertThat(problems.get(2).message()).contains("inverseOf");
        assertThat(definitions.find("p-unknown")).isEmpty();
    }

    @Test
    void 이름_없는_정의와_값은_거부하고_문구가_읽을_때마다_같다() throws IOException {
        Path dir = pack("p-blank", 1, """
                ex:A a owl:Class ; rdfs:label "가" ; rdfs:subClassOf [ a owl:Class ; rdfs:label "이름 없는 위" ] .
                [ a owl:Class ; rdfs:label "이름 없는 것" ] .
                [ a owl:Class ; rdfs:label "또 이름 없는 것" ] .
                """);

        List<PackProblem> first = problemsOf(dir);
        List<PackProblem> second = problemsOf(dir);

        // 빈 노드의 라벨은 읽을 때마다 달라진다. 문구에 들어가면 안 된다.
        assertThat(second).isEqualTo(first);
        assertThat(first).extracting(PackProblem::item).containsExactly("(이름 없음)", "A");
        assertThat(first).allSatisfy(p -> assertThat(p.message()).doesNotContain("_:"));
    }

    @Test
    void 다른_문서를_들여오라는_문장은_거부하고_밖에서_가져오지_않는다() throws IOException {
        // 주소의 호스트는 존재할 수 없는 이름이다. 가져오려 했다면 여기서 멈추거나 다른 오류가 난다.
        Path dir = pack("p-imports", 1, """
                ex:A a owl:Class ; rdfs:label "가" .
                <urn:tabnit:pack:p-imports:> a owl:Ontology ; owl:imports <http://pack-source.invalid/other.ttl> .
                """);

        List<PackProblem> problems = problemsOf(dir);

        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.file()).isEqualTo("ontology.ttl");
            assertThat(p.message()).contains("owl:imports");
        });
        assertThat(definitions.find("p-imports")).isEmpty();
    }

    @Test
    void 다른_Pack의_주소로_적은_정의와_이름이_겹치는_정의는_거부한다() throws IOException {
        Path foreign = pack("p-foreign", 1, "<urn:tabnit:pack:elsewhere:A> a owl:Class ; rdfs:label \"남의 것\" .");
        Path clash = pack("p-clash", 1, """
                ex:A a owl:Class , owl:DatatypeProperty ; rdfs:label "둘 다" .
                """);

        assertThat(problemsOf(foreign)).isNotEmpty().allSatisfy(p -> assertThat(p.message()).contains("elsewhere"));
        assertThat(problemsOf(clash)).isNotEmpty();
    }

    @Test
    void 오류가_여럿이면_한_번에_모두_알리고_차례가_정해져_있다() throws IOException {
        Path dir = pack("p-many", 0, """
                ex:z a owl:DatatypeProperty ; rdfs:label "끝" ; rdfs:range xsd:string .
                ex:A a owl:Class .
                """);

        List<PackProblem> first = problemsOf(dir);
        List<PackProblem> second = problemsOf(dir);

        assertThat(first).extracting(PackProblem::file).containsExactly("ontology.ttl", "ontology.ttl", "pack.ttl");
        assertThat(first).extracting(PackProblem::item).startsWith("A", "z");
        assertThat(second).isEqualTo(first);
    }

    // ── 저장소에 들어 있는 Pack ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("repositoryPacks")
    void 저장소의_Pack이_코드를_고치지_않고_적재되고_적힌_정의가_모두_읽힌다(Path dir) throws IOException {
        String packId = dir.getFileName().toString();
        String ontology = Files.readString(dir.resolve("ontology.ttl"), StandardCharsets.UTF_8);

        loader.load(dir, LOADED_AT);
        PackLoadResult again = loader.load(dir, LOADED_AT);
        PackDefinition found = definitions.find(packId).orElseThrow();

        // 파일에 적힌 정의의 수를 글자로 세어 견준다. 시험에 Pack 의 수를 적지 않는다.
        // 정의를 "이름 a owl:Class" 꼴로 적고 주석에 같은 글자가 없다고 본다. 다르게 적은 Pack 이 들어오면
        // 이 시험이 실패로 알린다. 그때 세는 법을 고친다.
        assertThat(found.entityTypes()).hasSize(count(ontology, " a owl:Class"));
        assertThat(found.relationTypes()).hasSize(count(ontology, " a owl:ObjectProperty"));
        assertThat(found.attributes()).hasSize(count(ontology, " a owl:DatatypeProperty"));
        assertThat(found.entityTypes()).isNotEmpty();
        assertThat(again.outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
        // 관계 유형과 속성이 가리키는 유형은 모두 정의돼 있다.
        List<String> typeNames = found.entityTypes().stream().map(EntityTypeDefinition::name).toList();
        assertThat(found.relationTypes()).allSatisfy(r -> assertThat(typeNames).contains(r.sourceType(), r.targetType()));
        assertThat(found.attributes()).allSatisfy(a -> assertThat(typeNames).contains(a.entityType()));
    }

    static Stream<Path> repositoryPacks() throws IOException {
        // 작업 폴더는 server/pack 이다. 정의 파일이 있는 Pack 폴더만 고른다.
        try (Stream<Path> dirs = Files.list(Path.of("../../packs"))) {
            return dirs.filter(d -> Files.exists(d.resolve("pack.ttl"))).sorted().toList().stream();
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 지어낸 작은 Pack 폴더를 만든다. 폴더 이름이 Pack 식별자다. */
    private Path pack(String packId, int maxDepth, String ontologyBody) throws IOException {
        Path dir = temp.resolve(packId + "-" + System.identityHashCode(ontologyBody)).resolve(packId);
        Files.createDirectories(dir);
        write(dir, "pack.ttl", PREFIXES + "<urn:tabnit:pack:" + packId + ":> a tabnit:Pack ; tabnit:packId \"" + packId
                + "\" ; rdfs:label \"이름표 " + packId + "\" ; tabnit:defaultMaxDepth " + maxDepth + " .\n");
        write(dir, "ontology.ttl", PREFIXES + "@prefix ex: <urn:tabnit:pack:" + packId + ":> .\n" + ontologyBody);
        return dir;
    }

    private static void write(Path dir, String file, String content) throws IOException {
        Files.writeString(dir.resolve(file), content, StandardCharsets.UTF_8);
    }

    private List<PackProblem> problemsOf(Path dir) {
        PackInvalidException e = catchThrowableOfType(PackInvalidException.class, () -> loader.load(dir, LOADED_AT));
        assertThat(e).as("거부돼야 하는 Pack 이 적재됐다: " + dir).isNotNull();
        return e.problems();
    }

    private int rows(String table, String packId) {
        return jdbc.queryForObject("select count(*) from " + table + " where pack_id = ?", Integer.class, packId);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }
}
