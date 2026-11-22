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
 * B-14 (D2 AD-338 ~ AD-340): 조건이 규칙의 대상과 어느 관계로 이어진 대상을 본다 (tabnit:LinkedTarget, tabnit:linkedBy).
 * 읽고 검증하고 저장한다.
 *
 * <p>어느 Pack 과도 무관한 일반 시험이다. 유형과 속성의 이름은 자리만 잡은 것이다.
 */
class PackRuleLinkedTest {

    private static final Instant LOADED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final String PREFIXES = """
            @prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:    <http://www.w3.org/2002/07/owl#> .
            @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .
            @prefix tabnit: <urn:tabnit:vocab:> .
            """;

    /** Doc 이 Unit 을 다루고 (covers), Unit 을 Owner 가 가진다 (heldBy). Apart 는 어느 것과도 이어지지 않는다. */
    private static final String ONTOLOGY = """
            ex:Doc a owl:Class ; rdfs:label "문서" .
            ex:Unit a owl:Class ; rdfs:label "단위" .
            ex:SubUnit a owl:Class ; rdfs:label "아래 단위" ; rdfs:subClassOf ex:Unit .
            ex:Owner a owl:Class ; rdfs:label "가진 쪽" .
            ex:Apart a owl:Class ; rdfs:label "따로" .
            ex:covers a owl:ObjectProperty ; rdfs:label "다룬다" ; rdfs:domain ex:Doc ; rdfs:range ex:Unit ;
                tabnit:impactDirection tabnit:SourceToTarget .
            ex:heldBy a owl:ObjectProperty ; rdfs:label "가진다" ; rdfs:domain ex:Unit ; rdfs:range ex:Owner ;
                tabnit:impactDirection tabnit:SourceToTarget .
            ex:near a owl:ObjectProperty ; rdfs:label "곁" ; rdfs:domain ex:Apart ; rdfs:range ex:Apart ;
                tabnit:impactDirection tabnit:None .
            ex:level a owl:DatatypeProperty ; rdfs:label "등급" ; rdfs:domain ex:Doc ; rdfs:range xsd:integer .
            ex:grade a owl:DatatypeProperty ; rdfs:label "단위의 등급" ; rdfs:domain ex:Unit ; rdfs:range xsd:integer .
            ex:checked a owl:DatatypeProperty ; rdfs:label "확인한 날" ; rdfs:domain ex:Unit ; rdfs:range xsd:date .
            ex:ownerName a owl:DatatypeProperty ; rdfs:label "이름" ; rdfs:domain ex:Owner ; rdfs:range xsd:string .
            ex:apartName a owl:DatatypeProperty ; rdfs:label "따로의 이름" ; rdfs:domain ex:Apart ; rdfs:range xsd:string .
            """;

    /** 대상이 Owner 인 규칙의 앞부분. 뒤에 조건을 이어 적고 마침표로 끝낸다. */
    private static final String RULE = """
            ex:r1 a tabnit:Rule ;
                tabnit:ruleNo "0001" ;
                rdfs:label "이름" ;
                tabnit:priority 1 ;
                tabnit:appliesTo tabnit:ImpactTarget ;
                tabnit:targetType ex:Owner ;
                tabnit:actionText "할 일" ;
                tabnit:dueInDays 7
            """;

    private final PackLoader loader = new PackLoader(PackTestDatabase.dataSource());

    private final PackDefinitions definitions = new PackDefinitions(PackTestDatabase.dataSource());

    @TempDir
    Path temp;

    @Test
    void 이어진_대상을_보는_조건을_읽어_저장하고_그대로_읽는다() throws IOException {
        Path dir = pack("l-read", RULE.stripTrailing() + """
                    ; tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy ex:heldBy ;
                                         tabnit:property ex:grade ; tabnit:operator tabnit:GreaterThan ; tabnit:value 3 ]
                    ; tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy ex:heldBy ;
                                         tabnit:typeIs ex:SubUnit ]
                    ; tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy ex:heldBy ;
                                         tabnit:property ex:grade ; tabnit:operator tabnit:LessThan ;
                                         tabnit:compareToSubject tabnit:ChangeTarget ; tabnit:compareToProperty ex:level ]
                    ; tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy ex:heldBy ;
                                         tabnit:property ex:checked ; tabnit:operator tabnit:LessThan ;
                                         tabnit:compareTo tabnit:EffectiveDate ]
                    ; tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:ownerName ;
                                         tabnit:operator tabnit:HasValue ] .
                """);

        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
        List<RuleCondition> conditions = definitions.find("l-read").orElseThrow().rules().get(0).conditions();

        assertThat(conditions).containsExactlyInAnyOrder(
                new RuleCondition(RuleSubject.IMPACT_TARGET, null, "ownerName", RuleOperator.HAS_VALUE, List.of()),
                new RuleCondition(RuleSubject.LINKED_TARGET, "SubUnit", null, null, List.of(), RuleCompareTo.VALUE, null,
                        null, "heldBy"),
                new RuleCondition(RuleSubject.LINKED_TARGET, null, "grade", RuleOperator.GREATER_THAN, List.of("3"),
                        RuleCompareTo.VALUE, null, null, "heldBy"),
                new RuleCondition(RuleSubject.LINKED_TARGET, null, "grade", RuleOperator.LESS_THAN, List.of(),
                        RuleCompareTo.ATTRIBUTE, RuleSubject.CHANGE_TARGET, "level", "heldBy"),
                new RuleCondition(RuleSubject.LINKED_TARGET, null, "checked", RuleOperator.LESS_THAN, List.of(),
                        RuleCompareTo.EFFECTIVE_DATE, null, null, "heldBy"));
        // 이어진 대상을 보지 않는 조건에는 관계 유형이 없다.
        assertThat(conditions.stream().filter(c -> c.subject() == RuleSubject.IMPACT_TARGET).findFirst().orElseThrow()
            .linkedBy()).isNull();
    }

    @Test
    void 이어진_대상의_조건을_고치면_다시_적재된다() throws IOException {
        Path dir = pack("l-update", RULE.stripTrailing() + linked("ex:heldBy", "ex:grade", "tabnit:GreaterThan", "3") + " .\n");
        loader.load(dir, LOADED_AT);
        rules(dir, RULE.stripTrailing() + linked("ex:heldBy", "ex:grade", "tabnit:GreaterThan", "5") + " .\n");

        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.UPDATED);
        assertThat(definitions.find("l-update").orElseThrow().rules().get(0).conditions()).containsExactly(
                new RuleCondition(RuleSubject.LINKED_TARGET, null, "grade", RuleOperator.GREATER_THAN, List.of("5"),
                        RuleCompareTo.VALUE, null, null, "heldBy"));
    }

    @Test
    void 대상이_변동_대상인_규칙도_이어진_대상을_볼_수_있다() throws IOException {
        // 대상은 Unit 이다. covers 로 이어진 반대쪽(Doc)의 속성을 본다.
        Path dir = pack("l-change", """
                ex:r1 a tabnit:Rule ; tabnit:ruleNo "0001" ; rdfs:label "이름" ; tabnit:priority 1 ;
                    tabnit:appliesTo tabnit:ChangeTarget ; tabnit:targetType ex:SubUnit ;
                    tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy ex:covers ;
                                       tabnit:property ex:level ; tabnit:operator tabnit:Equals ; tabnit:value 2 ] ;
                    tabnit:actionText "할 일" ; tabnit:dueInDays 7 .
                """);

        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    @Test
    void 양_끝이_같은_유형인_관계와_퍼지지_않는_관계도_적을_수_있다() throws IOException {
        // near 는 Apart 와 Apart 를 잇고 영향이 퍼지지 않는다. 이어진 것을 보는 데는 영향 방향이 상관없다.
        Path dir = pack("l-same-ends", """
                ex:r1 a tabnit:Rule ; tabnit:ruleNo "0001" ; rdfs:label "이름" ; tabnit:priority 1 ;
                    tabnit:appliesTo tabnit:ChangeTarget ; tabnit:targetType ex:Apart ;
                    tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy ex:near ;
                                       tabnit:property ex:apartName ; tabnit:operator tabnit:HasValue ] ;
                    tabnit:actionText "할 일" ; tabnit:dueInDays 7 .
                """);

        assertThat(loader.load(dir, LOADED_AT).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
    }

    @Test
    void 대상이_변동_대상인_규칙의_이어진_조건도_영향_대상의_속성과는_견줄_수_없다() throws IOException {
        List<PackProblem> problems = problemsOf(pack("l-change-impact", """
                ex:r1 a tabnit:Rule ; tabnit:ruleNo "0001" ; rdfs:label "이름" ; tabnit:priority 1 ;
                    tabnit:appliesTo tabnit:ChangeTarget ; tabnit:targetType ex:Unit ;
                    tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy ex:covers ;
                                       tabnit:property ex:level ; tabnit:operator tabnit:GreaterThan ;
                                       tabnit:compareToSubject tabnit:ImpactTarget ; tabnit:compareToProperty ex:grade ] ;
                    tabnit:actionText "할 일" ; tabnit:dueInDays 7 .
                """));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).message()).contains("0001").contains("ImpactTarget");
    }

    @Test
    void 틀린_모양은_규칙_번호와_함께_거부한다() throws IOException {
        String[][] wrong = {
                // 이어진 대상을 보려면 어느 관계인지 반드시 적는다
                { "; tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:property ex:grade ; tabnit:operator tabnit:GreaterThan ; tabnit:value 3 ]",
                        "linkedBy" },
                // 관계는 이어진 대상을 볼 때만 적는다
                { "; tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:linkedBy ex:heldBy ; tabnit:property ex:ownerName ; tabnit:operator tabnit:HasValue ]",
                        "linkedBy" },
                { linked("ex:heldBy , ex:covers", "ex:grade", "tabnit:GreaterThan", "3"), "linkedBy" },
                // 없는 관계 유형, 관계 유형이 아닌 이름
                { linked("ex:nothing", "ex:grade", "tabnit:GreaterThan", "3"), "nothing" },
                { linked("ex:grade", "ex:grade", "tabnit:GreaterThan", "3"), "grade" },
                // 규칙의 대상(Owner)이 그 관계의 어느 끝도 아니다
                { linked("ex:near", "ex:grade", "tabnit:GreaterThan", "3"), "near" },
                { linked("ex:covers", "ex:level", "tabnit:GreaterThan", "3"), "covers" },
                // 관계의 반대쪽(Unit)에 값이 있을 수 없는 속성과 유형
                { linked("ex:heldBy", "ex:level", "tabnit:GreaterThan", "3"), "level" },
                { linked("ex:heldBy", "ex:ownerName", "tabnit:Equals", "\"가\""), "ownerName" },
                { "; tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy ex:heldBy ; tabnit:typeIs ex:Doc ]", "Doc" },
                // 견주는 상대는 변동 대상이나 영향 대상이다. 이어진 대상과는 견주지 않는다
                { "; tabnit:condition [ tabnit:subject tabnit:ImpactTarget ; tabnit:property ex:ownerName ; tabnit:operator tabnit:Equals ; "
                        + "tabnit:compareToSubject tabnit:LinkedTarget ; tabnit:compareToProperty ex:ownerName ]", "compareToSubject" } };
        for (int i = 0; i < wrong.length; i++) {
            Path dir = pack("l-wrong-" + i, RULE.stripTrailing() + wrong[i][0] + " .\n");
            List<PackProblem> problems = problemsOf(dir);
            assertThat(problems).as(wrong[i][0]).hasSize(1);
            assertThat(problems.get(0).message()).as(wrong[i][0]).contains(wrong[i][1]).contains("0001");
            assertThat(definitions.find("l-wrong-" + i)).isEmpty();
        }
    }

    @Test
    void 조치의_대상은_이어진_대상이_될_수_없다() throws IOException {
        List<PackProblem> problems = problemsOf(pack("l-applies", RULE.replace("tabnit:appliesTo tabnit:ImpactTarget",
                "tabnit:appliesTo tabnit:LinkedTarget").stripTrailing() + " .\n"));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).message()).contains("appliesTo").contains("0001");
    }

    private static String linked(String relation, String property, String operator, String value) {
        return "; tabnit:condition [ tabnit:subject tabnit:LinkedTarget ; tabnit:linkedBy " + relation + " ; tabnit:property "
                + property + " ; tabnit:operator " + operator + " ; tabnit:value " + value + " ]";
    }

    private Path pack(String packId, String ruleBody) throws IOException {
        Path dir = Files.createTempDirectory(temp, "p").resolve(packId);
        Files.createDirectories(dir);
        String ex = "@prefix ex: <urn:tabnit:pack:" + packId + ":> .\n";
        Files.writeString(dir.resolve("pack.ttl"), PREFIXES + "<urn:tabnit:pack:" + packId + ":> a tabnit:Pack ; tabnit:packId \""
                + packId + "\" ; rdfs:label \"이름표\" ; tabnit:defaultMaxDepth 2 .\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("ontology.ttl"), PREFIXES + ex + ONTOLOGY, StandardCharsets.UTF_8);
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
