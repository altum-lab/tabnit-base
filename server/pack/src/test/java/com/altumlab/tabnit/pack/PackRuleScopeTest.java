package com.altumlab.tabnit.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B-03 (규칙의 형식 넓히기, D2 AD-289 ~ AD-292): 규칙이 걸리는 입력 자료, 바뀐 속성과 관계 유형, 판의 적용일이나 다른
 * 대상의 속성과 견주는 조건을 읽고 저장한다.
 *
 * <p>어느 Pack 과도 무관한 일반 시험이다. 유형과 속성의 이름은 자리만 잡은 것이다.
 */
class PackRuleScopeTest {

    private static final Instant LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final String PREFIXES = """
            @prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:    <http://www.w3.org/2002/07/owl#> .
            @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .
            @prefix tabnit: <urn:tabnit:vocab:> .
            """;

    private static final String ONTOLOGY = """
            ex:Doc a owl:Class ; rdfs:label "문서" .
            ex:Unit a owl:Class ; rdfs:label "단위" .
            ex:covers a owl:ObjectProperty ; rdfs:label "다룬다" ; rdfs:domain ex:Doc ; rdfs:range ex:Unit ;
                tabnit:impactDirection tabnit:SourceToTarget .
            ex:title a owl:DatatypeProperty ; rdfs:label "제목" ; rdfs:domain ex:Doc ; rdfs:range xsd:string .
            ex:revised a owl:DatatypeProperty ; rdfs:label "고친 날" ; rdfs:domain ex:Doc ; rdfs:range xsd:date .
            ex:level a owl:DatatypeProperty ; rdfs:label "등급" ; rdfs:domain ex:Doc ; rdfs:range xsd:integer .
            ex:checked a owl:DatatypeProperty ; rdfs:label "확인한 날" ; rdfs:domain ex:Unit ; rdfs:range xsd:date .
            ex:grade a owl:DatatypeProperty ; rdfs:label "단위의 등급" ; rdfs:domain ex:Unit ; rdfs:range xsd:integer .
            ex:name a owl:DatatypeProperty ; rdfs:label "이름" ; rdfs:domain ex:Unit ; rdfs:range xsd:string .
            """;

    private static final String DOC_MAPPING = """
            ex:m-doc a tabnit:Mapping ;
                tabnit:inputSourceId "doc" ;
                tabnit:fileFormat tabnit:Csv ;
                tabnit:requiredColumns ( "no" "title" "unit" ) ;
                tabnit:itemKeyColumns ( "no" ) ;
                tabnit:primaryEntity [ tabnit:entityType ex:Doc ; tabnit:keyColumn "no" ] ;
                tabnit:entity [ tabnit:entityType ex:Doc ; tabnit:keyColumn "no" ;
                                tabnit:attribute [ tabnit:property ex:title ; tabnit:column "title" ] ] ;
                tabnit:relation [ tabnit:relationType ex:covers ;
                                  tabnit:sourceEntity [ tabnit:entityType ex:Doc ; tabnit:keyColumn "no" ] ;
                                  tabnit:targetEntity [ tabnit:entityType ex:Unit ; tabnit:keyColumn "unit" ] ] .
            """;

    private static final String UNIT_MAPPING = """
            ex:m-unit a tabnit:Mapping ;
                tabnit:inputSourceId "unit" ;
                tabnit:fileFormat tabnit:Csv ;
                tabnit:requiredColumns ( "code" "name" ) ;
                tabnit:itemKeyColumns ( "code" ) ;
                tabnit:primaryEntity [ tabnit:entityType ex:Unit ; tabnit:keyColumn "code" ] ;
                tabnit:entity [ tabnit:entityType ex:Unit ; tabnit:keyColumn "code" ;
                                tabnit:attribute [ tabnit:property ex:name ; tabnit:column "name" ] ] .
            """;

    /** 규칙의 앞부분. 뒤에 항목을 이어 적고 마침표로 끝낸다. */
    private static final String RULE = """
            ex:r%s a tabnit:Rule ;
                tabnit:ruleNo "%s" ;
                rdfs:label "이름" ;
                tabnit:priority 1 ;
                tabnit:appliesTo tabnit:ImpactTarget ;
                tabnit:targetType ex:Unit ;
                tabnit:actionText "할 일" ;
                tabnit:dueInDays 7
            """;

    private final PackLoader loader = new PackLoader(PackTestDatabase.dataSource());

    private final PackDefinitions definitions = new PackDefinitions(PackTestDatabase.dataSource());

    @TempDir
    Path temp;

    @Test
    void 걸리는_입력_자료와_바뀐_것과_견주는_상대를_읽어_저장하고_그대로_읽는다() throws IOException {
        Path dir = pack("s-read", rule("0001") + " .\n" + rule("0002") + """
                    ; tabnit:whenInputSource "unit" , "doc"
                    ; tabnit:whenChanged ex:title , ex:covers , ex:level
                    ; tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:checked ;
                                         tabnit:operator tabnit:LessThan ; tabnit:compareTo tabnit:EffectiveDate ]
                    ; tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:grade ;
                                         tabnit:operator tabnit:LessThan ;
                                         tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:level ]
                    ; tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:property ex:level ;
                                         tabnit:operator tabnit:GreaterThan ; tabnit:value 3 ] .
                """);

        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
        List<RuleDefinition> rules = definitions.find("s-read").orElseThrow().rules();

        // 적지 않은 규칙은 지금까지와 같다: 모든 입력 자료, 무엇이 바뀌든.
        RuleDefinition plain = rules.get(0);
        assertThat(plain.inputSources()).isEmpty();
        assertThat(plain.changedAttributes()).isEmpty();
        assertThat(plain.changedRelationTypes()).isEmpty();
        RuleDefinition scoped = rules.get(1);
        // 글자 코드 순으로 둔다. 적은 차례에 달라지지 않는다.
        assertThat(scoped.inputSources()).containsExactly("doc", "unit");
        assertThat(scoped.changedAttributes()).containsExactly("level", "title");
        assertThat(scoped.changedRelationTypes()).containsExactly("covers");
        assertThat(scoped.conditions()).containsExactlyInAnyOrder(
                new RuleCondition(RuleSubject.CHANGE_TARGET, null, "level", RuleOperator.GREATER_THAN, List.of("3")),
                new RuleCondition(RuleSubject.IMPACT_TARGET, null, "checked", RuleOperator.LESS_THAN, List.of(),
                        RuleCompareTo.EFFECTIVE_DATE, null, null),
                new RuleCondition(RuleSubject.IMPACT_TARGET, null, "grade", RuleOperator.LESS_THAN, List.of(),
                        RuleCompareTo.ATTRIBUTE, RuleSubject.CHANGE_TARGET, "level"));
        // 고정 값과 견주는 조건은 지금까지의 모양 그대로다.
        assertThat(scoped.conditions().stream().filter(c -> c.attributeName().equals("level")).findFirst().orElseThrow()
            .compareTo()).isEqualTo(RuleCompareTo.VALUE);
    }

    @Test
    void 적는_차례가_달라도_같은_규칙이고_고친_것은_다시_적재된다() throws IOException {
        Path dir = pack("s-order", rule("0001") + """
                    ; tabnit:whenInputSource "doc" , "unit" ; tabnit:whenChanged ex:covers , ex:title .
                """);
        loader.load(dir, LOADED_AT);
        rules(dir, rule("0001") + """
                    ; tabnit:whenChanged ex:title , ex:covers ; tabnit:whenInputSource "unit" , "doc" , "doc" .
                """);
        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);

        rules(dir, rule("0001") + " ; tabnit:whenInputSource \"doc\" .\n");
        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
        RuleDefinition changed = definitions.find("s-order").orElseThrow().rules().get(0);
        assertThat(changed.inputSources()).containsExactly("doc");
        assertThat(changed.changedAttributes()).isEmpty();
        assertThat(changed.changedRelationTypes()).isEmpty();
    }

    @Test
    void 없는_입력_자료와_없는_이름과_틀린_모양은_규칙_번호와_함께_거부한다() throws IOException {
        String[][] wrong = {
                // 그 Pack 의 매핑에 없는 입력 자료
                { "; tabnit:whenInputSource \"no-such\"", "no-such" },
                { "; tabnit:whenInputSource \"\"", "whenInputSource" },
                { "; tabnit:whenInputSource ex:doc", "whenInputSource" },
                { "; tabnit:whenInputSource 7", "whenInputSource" },
                // 속성도 관계 유형도 아닌 이름
                { "; tabnit:whenChanged ex:nothing", "nothing" },
                { "; tabnit:whenChanged ex:Doc", "Doc" },
                { "; tabnit:whenChanged \"title\"", "whenChanged" },
                // 적용일과 견주는 것은 날짜 속성만, 크기와 같음의 비교만, 값 없이
                { cond("ex:name", "tabnit:LessThan", "tabnit:compareTo tabnit:EffectiveDate"), "name" },
                { cond("ex:grade", "tabnit:LessThan", "tabnit:compareTo tabnit:EffectiveDate"), "grade" },
                { cond("ex:checked", "tabnit:In", "tabnit:compareTo tabnit:EffectiveDate"), "checked" },
                { cond("ex:checked", "tabnit:HasValue", "tabnit:compareTo tabnit:EffectiveDate"), "checked" },
                { cond("ex:checked", "tabnit:LessThan", "tabnit:compareTo tabnit:EffectiveDate ; tabnit:value \"2026-01-01\"^^xsd:date"),
                        "checked" },
                { cond("ex:checked", "tabnit:LessThan", "tabnit:compareTo tabnit:Today"), "compareTo" },
                { cond("ex:checked", "tabnit:LessThan", "tabnit:compareTo tabnit:EffectiveDate , tabnit:EffectiveDate ; tabnit:compareTo \"x\""),
                        "compareTo" },
                // 다른 대상의 속성과 견주는 것: 둘을 함께 적고, 형식이 같아야 하고, 값이나 적용일과 함께 적지 않는다
                { cond("ex:grade", "tabnit:LessThan", "tabnit:compareToProperty ex:level"), "compareToSubject" },
                { cond("ex:grade", "tabnit:LessThan", "tabnit:compareToSubject tabnit:ChangeTarget"), "compareToProperty" },
                { cond("ex:grade", "tabnit:LessThan", "tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:nothing"),
                        "nothing" },
                { cond("ex:grade", "tabnit:LessThan", "tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:revised"),
                        "revised" },
                { cond("ex:name", "tabnit:LessThan", "tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:title"),
                        "name" },
                { cond("ex:grade", "tabnit:In", "tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:level"), "grade" },
                { cond("ex:grade", "tabnit:LessThan",
                        "tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:level ; tabnit:value 3"), "grade" },
                { cond("ex:checked", "tabnit:LessThan",
                        "tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:revised ; tabnit:compareTo tabnit:EffectiveDate"),
                        "checked" },
                { cond("ex:grade", "tabnit:LessThan", "tabnit:compareToSubject tabnit:Nobody ; tabnit:compareToProperty ex:level"),
                        "compareToSubject" } };
        for (int i = 0; i < wrong.length; i++) {
            Path dir = pack("s-wrong-" + i, rule("0007") + wrong[i][0] + " .\n");
            List<PackProblem> problems = problemsOf(dir);
            assertThat(problems).as(wrong[i][0]).hasSize(1);
            assertThat(problems.get(0).message()).as(wrong[i][0]).contains(wrong[i][1]).contains("0007");
            assertThat(definitions.find("s-wrong-" + i)).isEmpty();
        }
    }

    @Test
    void 글자와_참거짓은_같은지만_다른_대상의_속성과_견줄_수_있다() throws IOException {
        Path dir = pack("s-text", rule("0001") + cond("ex:name", "tabnit:NotEquals",
                "tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:title") + " .\n");

        loader.load(dir, LOADED_AT);

        assertThat(definitions.find("s-text").orElseThrow().rules().get(0).conditions()).containsExactly(
                new RuleCondition(RuleSubject.IMPACT_TARGET, null, "name", RuleOperator.NOT_EQUALS, List.of(),
                        RuleCompareTo.ATTRIBUTE, RuleSubject.CHANGE_TARGET, "title"));
    }

    @Test
    void 대상이_변동_대상인_규칙은_영향_대상의_속성과_견줄_수_없다() throws IOException {
        String rule = """
                ex:r1 a tabnit:Rule ; tabnit:ruleNo "0001" ; rdfs:label "이름" ; tabnit:priority 1 ;
                    tabnit:appliesTo tabnit:ChangeTarget ; tabnit:targetType ex:Doc ;
                    tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:property ex:level ;
                                       tabnit:operator tabnit:GreaterThan ;
                                       tabnit:compareToSubject tabnit:ImpactTarget ; tabnit:compareToProperty ex:grade ] ;
                    tabnit:actionText "할 일" ; tabnit:dueInDays 7 .
                """;
        List<PackProblem> problems = problemsOf(pack("s-change-side", rule));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).message()).contains("0001").contains("ImpactTarget");
    }

    @Test
    void 규칙의_대상의_속성과_견줄_때도_그_속성은_대상에_값이_있을_수_있는_것이어야_한다() throws IOException {
        // 대상은 Unit 인데 견주는 상대로 Unit 의 것이 아닌 속성(Doc 의 level)을 영향 대상에서 읽으려 한다 (D2 AD-223).
        String rule = """
                ex:r1 a tabnit:Rule ; tabnit:ruleNo "0001" ; rdfs:label "이름" ; tabnit:priority 1 ;
                    tabnit:appliesTo tabnit:ImpactTarget ; tabnit:targetType ex:Unit ;
                    tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:property ex:level ;
                                       tabnit:operator tabnit:GreaterThan ;
                                       tabnit:compareToSubject tabnit:ImpactTarget ; tabnit:compareToProperty ex:level ] ;
                    tabnit:actionText "할 일" ; tabnit:dueInDays 7 .
                """;
        List<PackProblem> problems = problemsOf(pack("s-compare-owner", rule));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).message()).contains("0001").contains("level").contains("Unit");

        // 다른 쪽(변동 대상)의 속성과 견주는 것은 그 유형을 미리 알 수 없어 받는다.
        Path fine = pack("s-compare-other", rule.replace("tabnit:subject tabnit:ChangeTarget ; tabnit:property ex:level",
                "tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:grade")
            .replace("tabnit:compareToSubject tabnit:ImpactTarget", "tabnit:compareToSubject tabnit:ChangeTarget"));
        assertThat(loader.load(fine, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    @Test
    void 규칙이_가리키는_입력_자료의_매핑을_빼면_Pack_을_거부한다() throws IOException {
        Path dir = pack("s-mapping", rule("0001") + " ; tabnit:whenInputSource \"unit\" .\n");
        loader.load(dir, LOADED_AT);
        // unit 의 매핑 파일을 지운다.
        Files.delete(dir.resolve("mappings/unit.ttl"));

        // 매핑을 빼는 것 자체가 받지 않는 변경이거나, 규칙이 없는 입력 자료를 가리키는 문제다. 어느 쪽이든 적재되지 않는다.
        Throwable rejected = org.assertj.core.api.Assertions.catchThrowable(() -> loader.load(dir, LOADED_AT));
        assertThat(rejected).isInstanceOfAny(PackInvalidException.class, PackChangeRejectedException.class);
        assertThat(definitions.find("s-mapping").orElseThrow().rules().get(0).inputSources()).containsExactly("unit");
    }

    // ── 적재할 때의 경고 (D2 AD-323) ──────────────────────────────

    /** 영향 대상(Unit)에 거는 규칙의 앞부분. 조건과 그 밖의 항목을 이어 적는다. */
    private static String impactRule(String no, String rest) {
        return rule(no) + rest + " .\n";
    }

    @Test
    void 개체를_여럿_만드는_입력_자료에_걸리는_규칙이_유형을_정하지_않고_변동_대상의_속성을_보면_경고한다() throws IOException {
        // doc 의 행은 Doc 과 Unit 을 만든다. unit 의 행은 Unit 하나만 만든다.
        String changeLevel = "; tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:property ex:level ; "
                + "tabnit:operator tabnit:GreaterThan ; tabnit:value 3 ]";
        String compareToChange = "; tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:grade ; "
                + "tabnit:operator tabnit:LessThan ; tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:level ]";
        String typed = "; tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:typeIs ex:Doc ]";
        Path dir = pack("s-warn",
                // 입력 자료를 가리지 않는다: doc 에도 걸린다. 유형을 정하지 않았다.
                impactRule("0001", changeLevel)
                        // 견주는 상대가 변동 대상의 속성인 것도 같다.
                        + impactRule("0002", "; tabnit:whenInputSource \"doc\" " + compareToChange)
                        // 변동 대상의 유형을 정했다.
                        + impactRule("0003", changeLevel + typed)
                        // 개체를 하나만 만드는 입력 자료에만 걸린다.
                        + impactRule("0004", "; tabnit:whenInputSource \"unit\" " + changeLevel)
                        // 변동 대상의 속성을 보지 않는다.
                        + impactRule("0005", "; tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:grade ; "
                                + "tabnit:operator tabnit:GreaterThan ; tabnit:value 3 ]")
                        // 이어진 대상의 값을 변동 대상의 속성과 견주는 것도 변동 대상의 속성을 보는 것이다.
                        + impactRule("0006", "; tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy ex:covers ; "
                                + "tabnit:property ex:level ; tabnit:operator tabnit:LessThan ; "
                                + "tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:level ]"));

        PackLoadResult first = loader.load(dir, LOADED_AT);
        PackLoadResult again = loader.load(dir, LOADED_AT);

        assertThat(first.outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
        // 경고는 적재를 막지 않는다. 규칙 번호 순이고, 어느 입력 자료와 어느 속성인지와 무엇을 하면 되는지를 담는다.
        assertThat(first.warnings()).extracting(PackWarning::item).containsExactly("0001", "0002", "0006");
        assertThat(first.warnings().get(0).message()).contains("doc").contains("level").contains("typeIs");
        assertThat(first.warnings().get(0).file()).isEqualTo("rules");
        // 같은 Pack 을 다시 적재해도 같은 경고가 나온다.
        assertThat(again.outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
        assertThat(again.warnings()).isEqualTo(first.warnings());
    }

    @Test
    void 저장소의_Pack_은_경고_없이_적재된다() throws IOException {
        // 저장소의 Pack 은 본보기다. 경고가 나는 규칙을 두지 않는다. 규칙을 고쳐 경고가 생기면 여기서 드러난다.
        List<Path> packs;
        try (java.util.stream.Stream<Path> children = Files.list(Path.of("../../packs"))) {
            packs = children.filter(dir -> Files.isRegularFile(dir.resolve("pack.ttl"))).sorted().toList();
        }
        assertThat(packs).hasSizeGreaterThanOrEqualTo(3);
        for (Path dir : packs) {
            assertThat(loader.load(dir, LOADED_AT).warnings()).as(dir.getFileName().toString()).isEmpty();
        }
    }

    @Test
    void 같은_유형의_개체만_여럿_만드는_입력_자료는_경고에_들지_않는다() throws IOException {
        Path dir = pack("s-warn-text", impactRule("0001", "; tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; "
                + "tabnit:property ex:level ; tabnit:operator tabnit:GreaterThan ; tabnit:value 3 ]"));
        // 한 행이 Unit 둘을 잇는 입력 자료를 더한다: 유형은 하나이고 개체는 둘이다.
        String ex = "@prefix ex: <urn:tabnit:pack:s-warn-text:> .\n";
        Files.writeString(dir.resolve("ontology.ttl"), Files.readString(dir.resolve("ontology.ttl"), StandardCharsets.UTF_8)
                + "ex:next a owl:ObjectProperty ; rdfs:label \"다음\" ; rdfs:domain ex:Unit ; rdfs:range ex:Unit ; "
                + "tabnit:impactDirection tabnit:None .\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mappings/pair.ttl"), PREFIXES + ex + """
                ex:m-pair a tabnit:Mapping ;
                    tabnit:inputSourceId "pair" ;
                    tabnit:fileFormat tabnit:Csv ;
                    tabnit:requiredColumns ( "from" "to" ) ;
                    tabnit:itemKeyColumns ( "from" "to" ) ;
                    tabnit:primaryEntity [ tabnit:entityType ex:Unit ; tabnit:keyColumn "from" ] ;
                    tabnit:relation [ tabnit:relationType ex:next ;
                                      tabnit:sourceEntity [ tabnit:entityType ex:Unit ; tabnit:keyColumn "from" ] ;
                                      tabnit:targetEntity [ tabnit:entityType ex:Unit ; tabnit:keyColumn "to" ] ] .
                """, StandardCharsets.UTF_8);

        List<PackWarning> warnings = loader.load(dir, LOADED_AT).warnings();

        // 유형이 다른 개체를 만드는 doc 만 적는다. pair 는 유형 조건으로 가를 수 없어 적지 않는다 (D2 AD-343).
        assertThat(warnings).singleElement()
            .satisfies(warning -> assertThat(warning.message()).contains("doc (Doc, Unit)").doesNotContain("pair"));
    }

    @Test
    void 대상이_변동_대상인_규칙과_경고할_것이_없는_Pack_은_경고가_없다() throws IOException {
        Path dir = pack("s-warn-none", """
                ex:r1 a tabnit:Rule ; tabnit:ruleNo "0001" ; rdfs:label "이름" ; tabnit:priority 1 ;
                    tabnit:appliesTo tabnit:ChangeTarget ; tabnit:targetType ex:Doc ;
                    tabnit:condition [ tabnit:subject tabnit:ChangeTarget ; tabnit:property ex:level ;
                                       tabnit:operator tabnit:GreaterThan ; tabnit:value 3 ] ;
                    tabnit:actionText "할 일" ; tabnit:dueInDays 7 .
                """);

        // 대상이 변동 대상 자신이면 조건이 보는 변동 대상도 그 개체다. 대상 유형이 이미 가린다.
        assertThat(loader.load(dir, LOADED_AT).warnings()).isEmpty();
    }

    private static String rule(String no) {
        return RULE.formatted(no, no).stripTrailing();
    }

    private static String cond(String property, String operator, String rest) {
        return "; tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property " + property + " ; tabnit:operator "
                + operator + " ; " + rest + " ]";
    }

    private Path pack(String packId, String ruleBody) throws IOException {
        Path dir = Files.createTempDirectory(temp, "p").resolve(packId);
        Files.createDirectories(dir.resolve("mappings"));
        String ex = "@prefix ex: <urn:tabnit:pack:" + packId + ":> .\n";
        Files.writeString(dir.resolve("pack.ttl"), PREFIXES + "<urn:tabnit:pack:" + packId + ":> a tabnit:Pack ; tabnit:packId \""
                + packId + "\" ; rdfs:label \"이름표\" ; tabnit:defaultMaxDepth 1 .\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("ontology.ttl"), PREFIXES + ex + ONTOLOGY, StandardCharsets.UTF_8);
        // 제약 파일은 반드시 있어야 한다 (D2 AD-393). 제약이 없는 Pack 은 빈 파일을 둔다.
        Files.writeString(dir.resolve("constraints.ttl"), "", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mappings/doc.ttl"), PREFIXES + ex + DOC_MAPPING, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mappings/unit.ttl"), PREFIXES + ex + UNIT_MAPPING, StandardCharsets.UTF_8);
        rules(dir, ruleBody);
        return dir;
    }

    private static void rules(Path dir, String body) throws IOException {
        Files.createDirectories(dir.resolve("rules"));
        Files.writeString(dir.resolve("rules/rules.ttl"),
                PREFIXES + "@prefix ex: <urn:tabnit:pack:" + dir.getFileName() + ":> .\n" + body, StandardCharsets.UTF_8);
    }

    private List<PackProblem> problemsOf(Path dir) {
        PackInvalidException e = catchThrowableOfType(PackInvalidException.class, () -> loader.load(dir, LOADED_AT));
        assertThat(e).as("거부돼야 하는 Pack 이 적재됐다: " + dir).isNotNull();
        return e.problems();
    }
}
