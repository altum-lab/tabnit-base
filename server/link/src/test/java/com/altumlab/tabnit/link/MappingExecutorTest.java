package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.altumlab.tabnit.core.AttributeValue;
import com.altumlab.tabnit.core.AttributeValueQuery;
import com.altumlab.tabnit.core.EntityNotFoundException;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.Relation;
import com.altumlab.tabnit.core.RelationQuery;
import com.altumlab.tabnit.core.ValidityPeriod;
import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedEntityRef;
import com.altumlab.tabnit.pack.MappedRelation;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.MappingFileFormat;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.pack.PackImpactDirection;
import com.altumlab.tabnit.pack.PackLoader;
import com.altumlab.tabnit.pack.RelationTypeDefinition;
import com.altumlab.tabnit.pack.ValueFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * C-03 수용 기준: 입력 파일을 넣으면 관계가 유효기간과 출처와 함께 적재된다.
 *
 * <p>범위는 매핑 실행과 core 적재까지다 (D2 AD-173). 판 번호와 적용일은 시험이 넘긴다.
 * 일반 시험을 먼저 둔다 (지어낸 이름의 매핑과 자료). 그 위에 저장소에 들어 있는 Pack 의 첫 판을 넣어 본다.
 * 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다 (D6 7절).
 */
class MappingExecutorTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final LocalDate EFFECTIVE = LocalDate.parse("2026-03-01");

    private static final MappedEntityRef BASE_BY_CODE = new MappedEntityRef("Base", "code");

    private final MappingExecutor executor = new MappingExecutor(LinkTestDatabase.dataSource());

    private final RelationQuery relations = new RelationQuery(LinkTestDatabase.dataSource());

    private final AttributeValueQuery values = new AttributeValueQuery(LinkTestDatabase.dataSource());

    // ── 적재: 유효기간과 출처 ──────────────────────────────────────

    @Test
    void 행이_개체와_속성_값과_관계가_되고_유효기간은_적용일부터_출처는_입력_자료와_판이다() {
        PackDefinition pack = pack("x-basic", full("src"));

        MappingOutcome outcome = run(pack, "src", 1, "code,title,other\nB-1,첫째,O-1\n");

        String base = "x-basic|Base|B-1";
        String other = "x-basic|Other|O-1";
        ValidityPeriod open = new ValidityPeriod(EFFECTIVE, null);
        Provenance provenance = new Provenance("src", 1);
        assertThat(relations.relationsAsOf(base, EFFECTIVE)).singleElement().satisfies(r -> {
            assertThat(r.sourceEntityId()).isEqualTo(base);
            assertThat(r.targetEntityId()).isEqualTo(other);
            assertThat(r.relationType()).isEqualTo("links");
            assertThat(r.period()).isEqualTo(open);
            assertThat(r.provenance()).isEqualTo(provenance);
            assertThat(r.recordedAt()).isEqualTo(RECORDED_AT);
        });
        assertThat(values.valuesAsOf(base, EFFECTIVE)).singleElement().satisfies(v -> {
            assertThat(v.attributeName()).isEqualTo("title");
            assertThat(v.value()).isEqualTo("첫째");
            assertThat(v.period()).isEqualTo(open);
            assertThat(v.provenance()).isEqualTo(provenance);
        });
        // 적용일 전날에는 아무것도 유효하지 않다.
        assertThat(relations.relationsAsOf(base, EFFECTIVE.minusDays(1))).isEmpty();
        assertThat(values.valuesAsOf(base, EFFECTIVE.minusDays(1))).isEmpty();
        assertThat(Notices.exceptReferenceOnly(outcome.notices())).isEmpty();
    }

    @Test
    void 결과에_행마다_항목의_키와_행_번호와_만든_것이_정해진_차례로_담긴다() {
        PackDefinition pack = pack("x-outcome", full("src"));

        MappingOutcome outcome = run(pack, "src", 1, "code,title,other\nB-2,둘째,O-1\nB-1,첫째,O-1\n");

        // 항목은 항목의 키 순이다. 파일에 적힌 차례가 아니다.
        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("B-1", "B-2");
        assertThat(outcome.items()).extracting(IngestedItem::rowNo).containsExactly(3, 2);
        IngestedItem first = outcome.items().get(0);
        // 만든 것은 종류(개체, 관계, 속성 값), 식별자 순이다. 관계의 끝으로만 나온 개체도 그 행이 만든 개체다.
        assertThat(first.outputs()).containsExactly(new ItemOutput(ItemOutput.Kind.ENTITY, "x-outcome|Base|B-1"),
                new ItemOutput(ItemOutput.Kind.ENTITY, "x-outcome|Other|O-1"),
                new ItemOutput(ItemOutput.Kind.RELATION, "x-outcome|Base|B-1|Other|O-1|links|src|1|2026-03-01"),
                new ItemOutput(ItemOutput.Kind.ATTRIBUTE_VALUE, "x-outcome|Base|B-1|title|2026-03-01"));
    }

    @Test
    void 항목의_키는_키_열의_값을_적힌_차례로_식별자와_같은_규칙으로_잇는다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("other", "code"), BASE_BY_CODE, List.of(new MappedEntity("Base", "code", List.of())), List.of());
        PackDefinition pack = pack("x-itemkey", mapping);

        MappingOutcome outcome = run(pack, "src", 1, "code,other\nB-1,\"a|b\"\nB-2,\"c\\d\"\n");

        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("a\\|b|B-1", "c\\\\d|B-2");
    }

    @Test
    void 관계의_끝에_적힌_개체는_없으면_만든다() {
        PackDefinition pack = pack("x-ref", full("src"));

        run(pack, "src", 1, "code,title,other\nB-1,첫째,O-9\n");

        // 속성 없이 식별자만 가진 개체다. 조회가 "없는 개체"로 실패하지 않는다.
        assertThat(values.valuesAsOf("x-ref|Other|O-9", EFFECTIVE)).isEmpty();
        assertThat(relations.relationsAsOf("x-ref|Other|O-9", EFFECTIVE)).hasSize(1);
    }

    @Test
    void 주_개체는_관계의_끝으로만_적혀_있고_다른_끝이_비어도_만든다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE, List.of(),
                List.of(new MappedRelation("links", BASE_BY_CODE, new MappedEntityRef("Other", "other"), null, null)));
        PackDefinition pack = pack("x-primary", mapping);

        MappingOutcome outcome = run(pack, "src", 1, "code,other\nB-1,\nB-2,O-1\n");

        assertThat(outcome.items().get(0).outputs())
            .containsExactly(new ItemOutput(ItemOutput.Kind.ENTITY, "x-primary|Base|B-1"));
        assertThat(values.valuesAsOf("x-primary|Base|B-1", EFFECTIVE)).isEmpty();
        // 다른 끝이 빈 행(2 번째 줄)의 주 개체는 아무도 가리키지 않았다: 알리지 않는다. 3 번째 줄은 속성 없는 두 개체를
        // 관계로 이었다 (D2 AD-378).
        assertThat(outcome.notices()).containsExactly(
                new IngestNotice("REFERENCE_ONLY_ENTITY", 3, "NO_ATTRIBUTE_VALUES", "x-primary|Base|B-2", null),
                new IngestNotice("REFERENCE_ONLY_ENTITY", 3, "NO_ATTRIBUTE_VALUES", "x-primary|Other|O-1", null));
    }

    @Test
    void 같은_속성을_적은_행_가운데_둘만_겹쳐도_그_속성의_값은_어느_것도_넣지_않는다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("line"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", "from", "to", null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("x-partial", mapping);

        MappingOutcome outcome = run(pack, "src", 1, """
                line,code,title,from,to
                1,B-1,x,2026-01-01,2026-02-01
                2,B-1,y,2026-02-01,
                3,B-1,z,2026-02-01,2026-03-01
                """);

        assertThat(values.valuesAsOf("x-partial|Base|B-1", LocalDate.parse("2026-01-15"))).isEmpty();
        assertThat(outcome.items()).hasSize(3);
        assertThat(outcome.notices()).extracting(IngestNotice::kind, IngestNotice::rowNo)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("ATTRIBUTE_CONFLICT", 2),
                    org.assertj.core.groups.Tuple.tuple("ATTRIBUTE_CONFLICT", 3),
                    org.assertj.core.groups.Tuple.tuple("ATTRIBUTE_CONFLICT", 4));
    }

    @Test
    void 값이_틀려_건너뛴_행도_항목의_키가_겹치는지_볼_때_센다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("count", "count", null, null, null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("x-dup-invalid", mapping);

        MappingOutcome outcome = run(pack, "src", 1, "code,count\nB-1,5\nB-1,x\n");

        // 어느 행이 맞는지 고르지 않는다. 멀쩡한 행도 받지 않는다 (D2 AD-181).
        assertThat(outcome.items()).isEmpty();
        assertThat(outcome.notices()).containsExactly(skipped(2, "DUPLICATE_ITEM_KEY", null, "B-1"),
                skipped(3, "DUPLICATE_ITEM_KEY", null, "B-1"), skipped(3, "INVALID_VALUE", "count", "x"));
        assertThatThrownBy(() -> values.valuesAsOf("x-dup-invalid|Base|B-1", EFFECTIVE))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 키가_같은_행_셋_가운데_둘이_틀려도_셋_모두_건너뛴다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("count", "count", null, null, null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("x-dup-three", mapping);

        MappingOutcome outcome = run(pack, "src", 1, "code,count\nB-1,x\nB-1,5\nB-1,y\nB-2,7\n");

        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("B-2");
        // 키가 겹치면서 값도 틀린 행은 알림을 둘 받는다.
        assertThat(outcome.notices()).containsExactly(skipped(2, "DUPLICATE_ITEM_KEY", null, "B-1"),
                skipped(2, "INVALID_VALUE", "count", "x"), skipped(3, "DUPLICATE_ITEM_KEY", null, "B-1"),
                skipped(4, "DUPLICATE_ITEM_KEY", null, "B-1"), skipped(4, "INVALID_VALUE", "count", "y"));
    }

    @Test
    void 가장_긴_키_둘을_잇는_관계도_저장된다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                List.of(new MappedRelation("peers", BASE_BY_CODE, new MappedEntityRef("Base", "peer"), null, null)));
        PackDefinition pack = pack("x-longest", mapping);
        // 4바이트 글자 200자: 키 하나가 800바이트다. 되풀이가 없어 압축되지 않는다.
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        for (int i = 0; i < MappingExecutor.MAX_KEY_LENGTH; i++) {
            left.appendCodePoint(0x20000 + (i * 7919 + 11) % 40000);
            right.appendCodePoint(0x20000 + (i * 6113 + 29) % 40000);
        }

        MappingOutcome outcome = run(pack, "src", 1, "code,title,peer\n" + left + ",이름," + right + "\n");

        assertThat(Notices.exceptReferenceOnly(outcome.notices())).isEmpty();
        assertThat(relations.relationsAsOf("x-longest|Base|" + left, EFFECTIVE)).singleElement()
            .satisfies(r -> assertThat(r.targetEntityId()).isEqualTo("x-longest|Base|" + right));
        assertThat(valuesOf("x-longest|Base|" + left)).containsExactly(Map.entry("title", "이름"));
    }

    @Test
    void 키_열의_값이_200자를_넘는_행은_건너뛰고_나머지는_넣는다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("line"), BASE_BY_CODE, List.of(new MappedEntity("Base", "code", List.of())),
                List.of(new MappedRelation("links", BASE_BY_CODE, new MappedEntityRef("Other", "other"), null, null)));
        PackDefinition pack = pack("x-long", mapping);
        // 되풀이가 없어 압축되지 않는 글자. 길이는 글자(코드 포인트)로 센다: 보충 문자 하나는 한 글자다.
        StringBuilder mixed = new StringBuilder();
        for (int i = 0; mixed.length() < 4000; i++) {
            mixed.append(Integer.toString(i * 7919 + 13, 36)).append("가힣".charAt(i % 2));
        }
        String at = mixed.substring(0, 200);
        String over = mixed.substring(0, 201);
        String emoji = "\uD83D\uDE00".repeat(200);

        MappingOutcome outcome = run(pack, "src", 1, "line,code,other\n1," + at + "," + emoji + "\n2," + over + ",O-1\n3,B-3,"
                + over + "\n" + over + ",B-4,O-1\n5,B-5,O-1\n");

        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("1", "5");
        assertThat(relations.relationsAsOf("x-long|Base|" + at, EFFECTIVE)).hasSize(1);
        assertThat(Notices.exceptReferenceOnly(outcome.notices())).containsExactly(skipped(3, "KEY_TOO_LONG", "code", "201"),
                skipped(4, "KEY_TOO_LONG", "other", "201"), skipped(5, "KEY_TOO_LONG", "line", "201"));
    }

    // ── 값을 읽는 법 ─────────────────────────────────────────────

    @Test
    void 형식마다_값을_정해진_표기로_다듬어_저장한다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, "yyyy.M.d", List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("count", "count", null, null, null, ",", null, null),
                                new MappedAttribute("ratio", "ratio", null, null, null, null, null, null),
                                new MappedAttribute("since", "since", null, null, "yyyy'년' M'월' d'일'", null, null, null),
                                new MappedAttribute("until", "until", null, null, null, null, null, null),
                                new MappedAttribute("active", "active", null, null, null, null, "예", "아니오"),
                                new MappedAttribute("flag", "flag", null, null, null, null, null, null),
                                new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("x-values", mapping);

        run(pack, "src", 1, """
                code,count,ratio,since,until,active,flag,title
                B-1,"1,234",3.50,2026년 3월 1일,2026.12.31,예,true,  두 낱말
                B-2,007,2.0,2025년 11월 9일,2027.1.5,아니오,false,x
                B-3,+5,.5,2024년 2월 29일,2026.3.1,예,true,y
                B-4,-0,-0.0,2026년 1월 1일,2026.3.1,예,true,z
                """);

        assertThat(valuesOf("x-values|Base|B-1")).containsExactly(Map.entry("active", "true"), Map.entry("count", "1234"),
                Map.entry("flag", "true"), Map.entry("ratio", "3.5"), Map.entry("since", "2026-03-01"),
                Map.entry("title", "두 낱말"), Map.entry("until", "2026-12-31"));
        assertThat(valuesOf("x-values|Base|B-2")).containsEntry("count", "7")
            .containsEntry("ratio", "2")
            .containsEntry("active", "false")
            .containsEntry("flag", "false")
            .containsEntry("since", "2025-11-09")
            .containsEntry("until", "2027-01-05");
        assertThat(valuesOf("x-values|Base|B-3")).containsEntry("count", "5").containsEntry("ratio", "0.5");
        assertThat(valuesOf("x-values|Base|B-4")).containsEntry("count", "0").containsEntry("ratio", "0");
    }

    @Test
    void 모양을_적지_않은_날짜는_표준_표기만_받는다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("since", "since", null, null, null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("x-iso", mapping);

        MappingOutcome outcome = run(pack, "src", 1, "code,since\nB-1,2026-03-01\nB-2,2026.3.1\nB-3,2026-3-1\n");

        assertThat(valuesOf("x-iso|Base|B-1")).containsEntry("since", "2026-03-01");
        assertThat(outcome.notices()).extracting(IngestNotice::rowNo, IngestNotice::code)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(3, "INVALID_VALUE"),
                    org.assertj.core.groups.Tuple.tuple(4, "INVALID_VALUE"));
    }

    @Test
    void 형식에_맞지_않는_값이_든_행은_건너뛰고_어느_열의_어떤_값인지_남긴다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, "yyyy.M.d", List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("count", "count", null, null, null, null, null, null),
                                new MappedAttribute("ratio", "ratio", null, null, null, null, null, null),
                                new MappedAttribute("since", "since", null, null, null, null, null, null),
                                new MappedAttribute("active", "active", null, null, null, null, "예", "아니오"),
                                new MappedAttribute("flag", "flag", null, null, null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("x-invalid", mapping);

        MappingOutcome outcome = run(pack, "src", 1, """
                code,count,ratio,since,active,flag
                B-1,12,1.5,2026.3.1,예,true
                B-2,twelve,1.5,2026.3.1,예,true
                B-3,12,1e3,2026.3.1,예,true
                B-4,12,1.5,2026.2.30,예,true
                B-5,12,1.5,2026.3.1,yes,true
                B-6,12,1.5,2026.3.1,예,TRUE
                B-7,"1,234",1.5,2026.3.1,예,true
                B-8,12,1.5,03/01/2026,예,true
                B-9,12,1.5,12026.3.1,예,true
                """);

        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("B-1");
        // 건너뛴 행은 아무것도 만들지 않는다. 개체도 없다.
        assertThatThrownBy(() -> values.valuesAsOf("x-invalid|Base|B-2", EFFECTIVE))
            .isInstanceOf(EntityNotFoundException.class);
        assertThat(outcome.notices()).containsExactly(skipped(3, "INVALID_VALUE", "count", "twelve"),
                skipped(4, "INVALID_VALUE", "ratio", "1e3"), skipped(5, "INVALID_VALUE", "since", "2026.2.30"),
                skipped(6, "INVALID_VALUE", "active", "yes"), skipped(7, "INVALID_VALUE", "flag", "TRUE"),
                // 자릿수 구분 기호를 적지 않은 열의 쉼표.
                skipped(8, "INVALID_VALUE", "count", "1,234"), skipped(9, "INVALID_VALUE", "since", "03/01/2026"),
                skipped(10, "INVALID_VALUE", "since", "12026.3.1"));
    }

    // ── 빈 칸 ───────────────────────────────────────────────────

    @Test
    void 빈_속성_칸은_값이_없는_것이고_빈_관계의_끝은_관계를_만들지_않는다() {
        PackDefinition pack = pack("x-blank", full("src"));

        MappingOutcome outcome = run(pack, "src", 1, "code,title,other\nB-1,,\nB-2,   ,O-1\n");

        assertThat(values.valuesAsOf("x-blank|Base|B-1", EFFECTIVE)).isEmpty();
        assertThat(relations.relationsAsOf("x-blank|Base|B-1", EFFECTIVE)).isEmpty();
        assertThat(relations.relationsAsOf("x-blank|Base|B-2", EFFECTIVE)).hasSize(1);
        assertThat(outcome.items()).hasSize(2);
        assertThat(Notices.exceptReferenceOnly(outcome.notices())).isEmpty();
    }

    @Test
    void 항목의_키나_주_개체의_키가_빈_행은_건너뛴다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("line"), BASE_BY_CODE, List.of(new MappedEntity("Base", "code", List.of())), List.of());
        PackDefinition pack = pack("x-blank-key", mapping);

        MappingOutcome outcome = run(pack, "src", 1, "line,code\n1,B-1\n,B-2\n3,\n4,B-4\n");

        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("1", "4");
        assertThat(outcome.notices()).containsExactly(skipped(3, "BLANK_KEY", "line", null),
                skipped(4, "BLANK_KEY", "code", null));
    }

    @Test
    void 주_개체가_아닌_개체의_키가_비면_그_개체만_만들지_않고_속성_칸에_값이_있으면_행을_건너뛴다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code", List.of()),
                        new MappedEntity("Other", "other",
                                List.of(new MappedAttribute("otherName", "otherName", null, null, null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("x-optional", mapping);

        MappingOutcome outcome = run(pack, "src", 1, "code,other,otherName\nB-1,O-1,상대\nB-2,,\nB-3,,이름만\n");

        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("B-1", "B-2");
        assertThat(outcome.items().get(1).outputs())
            .containsExactly(new ItemOutput(ItemOutput.Kind.ENTITY, "x-optional|Base|B-2"));
        assertThat(outcome.notices()).containsExactly(skipped(4, "VALUE_WITHOUT_KEY", "other", null));
    }

    // ── 파일 수준 ───────────────────────────────────────────────

    @Test
    void 매핑이_쓰는_열과_반드시_있어야_하는_열이_파일에_없으면_파일을_거부하고_모두_알린다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of("extra"),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", "from", "to", null, null, null, null)))),
                List.of(new MappedRelation("links", BASE_BY_CODE, new MappedEntityRef("Other", "other"), "relFrom", null)));
        PackDefinition pack = pack("x-missing", mapping);

        TableFileRejectedException e = catchThrowableOfType(TableFileRejectedException.class,
                () -> run(pack, "src", 1, "code,title\nB-1,첫째\n"));

        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo("MISSING_COLUMN");
        assertThat(e).hasMessageContaining("extra, from, other, relFrom, to");
        assertThatThrownBy(() -> values.valuesAsOf("x-missing|Base|B-1", EFFECTIVE))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 열_이름만_있는_파일은_아무것도_만들지_않는다() {
        PackDefinition pack = pack("x-empty", full("src"));

        MappingOutcome outcome = run(pack, "src", 1, "code,title,other\n");

        assertThat(outcome.items()).isEmpty();
        assertThat(outcome.notices()).isEmpty();
    }

    @Test
    void 읽다가_건너뛴_행도_알림에_담긴다() {
        PackDefinition pack = pack("x-reader", full("src"));

        MappingOutcome outcome = run(pack, "src", 1, "code,title,other\nB-1,첫째\nB-2,둘째,O-1\n");

        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("B-2");
        assertThat(Notices.exceptReferenceOnly(outcome.notices())).containsExactly(skipped(2, "CELL_COUNT_MISMATCH", null, "2"));
    }

    @Test
    void 엑셀_매핑에_CSV_가_오거나_없는_입력_자료이면_받지_않는다() {
        MappingDefinition xlsx = new MappingDefinition("sheet", MappingFileFormat.XLSX, "자료", null, List.of(),
                List.of("code"), BASE_BY_CODE, List.of(new MappedEntity("Base", "code", List.of())), List.of());
        PackDefinition pack = pack("x-format", xlsx);

        assertThat(catchThrowableOfType(TableFileRejectedException.class,
                () -> run(pack, "sheet", 1, "code\nB-1\n")).code()).isEqualTo("NOT_XLSX");
        assertThatThrownBy(() -> run(pack, "nowhere", 1, "code\nB-1\n")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nowhere");
    }

    // ── 행끼리: 겹치는 키, 부딪히는 값 ─────────────────────────────────

    @Test
    void 항목의_키가_겹치는_행은_모두_건너뛰고_행의_차례에_결과가_달라지지_않는다() {
        PackDefinition one = pack("x-dup-1", full("src"));
        PackDefinition two = pack("x-dup-2", full("src"));

        MappingOutcome forward = run(one, "src", 1, "code,title,other\nB-1,첫째,O-1\nB-2,둘째,O-1\nB-1,다시,O-2\n");
        MappingOutcome backward = run(two, "src", 1, "code,title,other\nB-1,다시,O-2\nB-2,둘째,O-1\nB-1,첫째,O-1\n");

        assertThat(forward.items()).extracting(IngestedItem::itemKey).containsExactly("B-2");
        assertThat(Notices.exceptReferenceOnly(forward.notices())).containsExactly(skipped(2, "DUPLICATE_ITEM_KEY", null, "B-1"),
                skipped(4, "DUPLICATE_ITEM_KEY", null, "B-1"));
        assertThat(backward.items()).extracting(IngestedItem::itemKey).containsExactly("B-2");
        assertThat(valuesOf("x-dup-2|Base|B-2")).isEqualTo(valuesOf("x-dup-1|Base|B-2"));
        assertThatThrownBy(() -> values.valuesAsOf("x-dup-1|Base|B-1", EFFECTIVE))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 여러_행이_같은_개체의_같은_속성에_같은_값을_적으면_한_번만_저장한다() {
        PackDefinition pack = pack("x-same", shared("src"));

        MappingOutcome outcome = run(pack, "src", 1, "line,other,otherName\n1,O-1,상대\n2,O-1,상대\n");

        assertThat(valuesOf("x-same|Other|O-1")).containsExactly(Map.entry("otherName", "상대"));
        ItemOutput value = new ItemOutput(ItemOutput.Kind.ATTRIBUTE_VALUE, "x-same|Other|O-1|otherName|2026-03-01");
        assertThat(outcome.items()).allSatisfy(item -> assertThat(item.outputs()).contains(value));
        assertThat(outcome.notices()).isEmpty();
    }

    @Test
    void 여러_행이_같은_개체의_같은_속성에_다른_값을_적으면_그_값은_넣지_않고_행의_나머지는_받는다() {
        PackDefinition one = pack("x-conflict-1", shared("src"));
        PackDefinition two = pack("x-conflict-2", shared("src"));

        MappingOutcome forward = run(one, "src", 1, "line,other,otherName\n1,O-1,하나\n2,O-1,둘\n3,O-2,셋\n");
        MappingOutcome backward = run(two, "src", 1, "line,other,otherName\n3,O-2,셋\n2,O-1,둘\n1,O-1,하나\n");

        // 부딪힌 값은 어느 것도 들어가지 않는다. 앞에 있는 행이 이기지 않는다.
        assertThat(values.valuesAsOf("x-conflict-1|Other|O-1", EFFECTIVE)).isEmpty();
        assertThat(values.valuesAsOf("x-conflict-2|Other|O-1", EFFECTIVE)).isEmpty();
        assertThat(valuesOf("x-conflict-1|Other|O-2")).containsExactly(Map.entry("otherName", "셋"));
        // 행은 받는다. 개체는 만든다.
        assertThat(forward.items()).extracting(IngestedItem::itemKey).containsExactly("1", "2", "3");
        assertThat(forward.notices()).containsExactly(
                new IngestNotice("ATTRIBUTE_CONFLICT", 2, "CONFLICTING_VALUES", "x-conflict-1|Other|O-1|otherName", "하나"),
                new IngestNotice("ATTRIBUTE_CONFLICT", 3, "CONFLICTING_VALUES", "x-conflict-1|Other|O-1|otherName", "둘"));
        assertThat(backward.items()).extracting(IngestedItem::itemKey).containsExactly("1", "2", "3");
        assertThat(backward.notices()).extracting(IngestNotice::detail).containsExactlyInAnyOrder("하나", "둘");
    }

    // ── 유효기간의 열 ────────────────────────────────────────────

    @Test
    void 유효기간의_열이_있으면_그_날짜를_쓰고_빈_칸이면_시작일은_적용일이다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, "yyyy.M.d", List.of(),
                List.of("line"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", "from", "to", null, null, null, null)))),
                List.of(new MappedRelation("links", BASE_BY_CODE, new MappedEntityRef("Other", "other"), "from", "to")));
        PackDefinition pack = pack("x-period", mapping);

        run(pack, "src", 1, """
                line,code,title,other,from,to
                1,B-1,옛 이름,O-1,2025.1.1,2026.1.1
                2,B-1,새 이름,O-2,2026.1.1,
                3,B-2,이름,O-1,,
                """);

        // 한 파일에 같은 개체의 같은 속성이 서로 겹치지 않는 기간으로 둘 적혀 있다. 부딪힘이 아니다.
        assertThat(values.valueAsOf("x-period|Base|B-1", "title", LocalDate.parse("2025-12-31")))
            .map(AttributeValue::value)
            .contains("옛 이름");
        assertThat(values.valueAsOf("x-period|Base|B-1", "title", LocalDate.parse("2026-01-01")))
            .map(AttributeValue::value)
            .contains("새 이름");
        assertThat(relations.relationsAsOf("x-period|Base|B-1", LocalDate.parse("2025-06-01")))
            .extracting(Relation::targetEntityId)
            .containsExactly("x-period|Other|O-1");
        assertThat(relations.relationsAsOf("x-period|Base|B-1", LocalDate.parse("2026-06-01")))
            .extracting(Relation::targetEntityId)
            .containsExactly("x-period|Other|O-2");
        // 빈 칸이면 시작일은 판의 적용일이고 종료일은 없다.
        assertThat(values.valueAsOf("x-period|Base|B-2", "title", EFFECTIVE).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(EFFECTIVE, null));
    }

    @Test
    void 종료일이_시작일보다_뒤가_아니거나_유효기간의_날짜가_틀린_행은_건너뛴다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", "from", "to", null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("x-period-bad", mapping);

        MappingOutcome outcome = run(pack, "src", 1, """
                code,title,from,to
                B-1,이름,2026-03-01,2026-03-01
                B-2,이름,2026-03-01,2026-02-01
                B-3,이름,2026/03/01,
                B-4,이름,,2026-03-01
                B-5,이름,2026-01-01,2026-06-01
                """);

        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("B-5");
        // 넷째 행: 시작일이 비어 적용일(2026-03-01)이 되고, 종료일이 그와 같아 기간이 성립하지 않는다.
        assertThat(outcome.notices()).extracting(IngestNotice::rowNo, IngestNotice::code)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "INVALID_PERIOD"),
                    org.assertj.core.groups.Tuple.tuple(3, "INVALID_PERIOD"),
                    org.assertj.core.groups.Tuple.tuple(4, "INVALID_VALUE"),
                    org.assertj.core.groups.Tuple.tuple(5, "INVALID_PERIOD"));
    }

    @Test
    void 출발과_도착이_같은_개체인_관계가_나오는_행은_건너뛴다() {
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), BASE_BY_CODE, List.of(new MappedEntity("Base", "code", List.of())),
                List.of(new MappedRelation("peers", BASE_BY_CODE, new MappedEntityRef("Base", "peer"), null, null)));
        PackDefinition pack = pack("x-self", mapping);

        MappingOutcome outcome = run(pack, "src", 1, "code,peer\nB-1,B-2\nB-3,B-3\n");

        assertThat(outcome.items()).extracting(IngestedItem::itemKey).containsExactly("B-1");
        assertThat(Notices.exceptReferenceOnly(outcome.notices())).containsExactly(skipped(3, "SELF_RELATION", "peer", "B-3"));
    }

    // ── 다시 넣기, 전부 되거나 전부 안 되거나 ──────────────────────────

    @Test
    void 같은_파일을_같은_판으로_다시_넣어도_결과와_저장된_것이_같다() {
        PackDefinition pack = pack("x-twice", full("src"));
        String csv = "code,title,other\nB-1,첫째,O-1\nB-2,둘째,O-1\nbad\n";

        MappingOutcome first = run(pack, "src", 1, csv);
        Map<String, String> before = valuesOf("x-twice|Base|B-1");
        MappingOutcome second = run(pack, "src", 1, csv);

        assertThat(second).isEqualTo(first);
        assertThat(valuesOf("x-twice|Base|B-1")).isEqualTo(before);
        assertThat(relations.relationsAsOf("x-twice|Other|O-1", EFFECTIVE)).hasSize(2);
    }

    @Test
    void 같은_판_번호로_값을_바꿔_다시_넣으면_먼저_넣은_값과_겹쳐_넣지_않고_알린다() {
        PackDefinition pack = pack("x-rerun", full("src"));
        run(pack, "src", 1, "code,title,other\nB-1,첫째,O-1\n");

        MappingOutcome again = run(pack, "src", 1, "code,title,other\nB-1,고친 이름,O-1\n");

        assertThat(Notices.exceptReferenceOnly(again.notices())).extracting(IngestNotice::code).containsExactly("OVERLAPS_STORED_VALUE");
        assertThat(valuesOf("x-rerun|Base|B-1")).containsExactly(Map.entry("title", "첫째"));
    }

    @Test
    void 저장소가_거부하면_그_실행에서_넣은_것이_하나도_남지_않는다() {
        // 저장소가 한 값을 받지 않게 해 둔다 (다른 시험과 섞이지 않게 빈 데이터베이스에서).
        // 이미 있는 값과 겹치는 것은 이제 저장 실패가 아니라 알림이다 (D2 AD-344).
        javax.sql.DataSource broken = LinkTestDatabase.fresh();
        new org.springframework.jdbc.core.JdbcTemplate(broken)
            .execute("alter table attribute_value add constraint test_refuse check (value <> '받지 않는 값')");
        MappingExecutor failing = new MappingExecutor(broken);
        AttributeValueQuery stored = new AttributeValueQuery(broken);
        PackDefinition pack = pack("x-rollback", full("src"));
        failing.execute(pack, "src", "code,title,other\nB-1,첫째,O-1\n".getBytes(StandardCharsets.UTF_8), 1, EFFECTIVE,
                RECORDED_AT);

        assertThatThrownBy(() -> failing.execute(pack, "src",
                "code,title,other\nA-0,새 개체,O-7\nB-2,받지 않는 값,O-1\n".getBytes(StandardCharsets.UTF_8), 2,
                LocalDate.parse("2026-04-01"), RECORDED_AT)).isInstanceOf(MappingExecutionException.class);

        // 먼저 처리된 A-0 도 남지 않는다.
        assertThatThrownBy(() -> stored.valuesAsOf("x-rollback|Base|A-0", EFFECTIVE))
            .isInstanceOf(EntityNotFoundException.class);
        assertThat(stored.valuesAsOf("x-rollback|Base|B-1", EFFECTIVE)).extracting(AttributeValue::value)
            .containsExactly("첫째");
    }

    @Test
    void 행이_많아도_모두_넣는다() {
        PackDefinition pack = pack("x-many", full("src"));
        StringBuilder csv = new StringBuilder("code,title,other\n");
        int count = 1500;
        for (int i = 0; i < count; i++) {
            // 넣는 차례를 섞는다 (7 은 1500 과 서로소다).
            int n = (i * 7) % count;
            csv.append(String.format("B-%04d,이름 %d,O-%d%n", n, n, n % 10));
        }

        MappingOutcome outcome = run(pack, "src", 1, csv.toString());

        assertThat(outcome.items()).hasSize(count);
        assertThat(outcome.items().get(0).itemKey()).isEqualTo("B-0000");
        assertThat(outcome.items().get(count - 1).itemKey()).isEqualTo("B-1499");
        assertThat(relations.relationsAsOf("x-many|Other|O-3", EFFECTIVE)).hasSize(count / 10);
    }

    // ── 저장소에 들어 있는 Pack ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("repositoryPacks")
    void 저장소의_Pack의_첫_판이_코드를_고치지_않고_적재된다(Path dir) throws IOException {
        String packId = dir.getFileName().toString();
        // Pack 식별자를 바꿀 수 없으므로 다른 시험과 섞이지 않게 빈 데이터베이스를 따로 쓴다.
        javax.sql.DataSource fresh = LinkTestDatabase.fresh();
        MappingExecutor executor = new MappingExecutor(fresh);
        new PackLoader(fresh).load(dir, RECORDED_AT);
        PackDefinition pack = new PackDefinitions(fresh).find(packId).orElseThrow();

        // 시나리오에서 입력 자료마다 처음 나오는 판을 차례대로 넣는다. 시험에 Pack 의 이름과 수를 적지 않는다.
        Map<String, String[]> firstEditions = firstEditions(dir);
        assertThat(firstEditions).isNotEmpty();
        int itemCount = 0;
        for (Map.Entry<String, String[]> step : firstEditions.entrySet()) {
            byte[] content = Files.readAllBytes(dir.resolve(step.getValue()[0]));
            MappingOutcome outcome = executor.execute(pack, step.getKey(), content, 1, LocalDate.parse(step.getValue()[1]),
                    RECORDED_AT);

            // 예시 자료에는 건너뛴 행도 부딪힌 값도 없어야 한다. 행마다 항목이 하나이고 주 개체를 만든다.
            assertThat(Notices.exceptReferenceOnly(outcome.notices())).as(step.getKey()).isEmpty();
            assertThat(outcome.items()).as(step.getKey()).hasSize(CsvTableReader.read(content).rows().size());
            assertThat(outcome.items()).allSatisfy(item -> assertThat(item.outputs())
                .anySatisfy(o -> assertThat(o.kind()).isEqualTo(ItemOutput.Kind.ENTITY)));
            itemCount += outcome.items().size();
        }
        assertThat(itemCount).isPositive();
    }

    static Stream<Path> repositoryPacks() throws IOException {
        // 작업 폴더는 server/link 다. 시나리오가 있는 Pack 만 고른다.
        try (Stream<Path> dirs = Files.list(Path.of("../../packs"))) {
            return dirs.filter(d -> Files.isDirectory(d.resolve("questions")) && Files.exists(d.resolve("pack.ttl")))
                .sorted()
                .toList()
                .stream();
        }
    }

    /** 시나리오의 단계에서 입력 자료마다 처음 나오는 파일과 적용일. 단계의 차례 그대로다. */
    private static Map<String, String[]> firstEditions(Path dir) throws IOException {
        Pattern step = Pattern.compile("inputSource:\\s*([^,\\s}]+)\\s*,\\s*file:\\s*([^,\\s}]+)\\s*,\\s*effectiveDate:\\s*([0-9-]+)");
        Map<String, String[]> first = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir.resolve("questions"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                Matcher m = step.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    first.putIfAbsent(m.group(1), new String[] { m.group(2), m.group(3) });
                }
            }
        }
        return first;
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private MappingOutcome run(PackDefinition pack, String inputSourceId, int editionNo, String csv) {
        return executor.execute(pack, inputSourceId, csv.getBytes(StandardCharsets.UTF_8), editionNo, EFFECTIVE,
                RECORDED_AT);
    }

    private Map<String, String> valuesOf(String entityId) {
        Map<String, String> found = new LinkedHashMap<>();
        for (AttributeValue v : values.valuesAsOf(entityId, EFFECTIVE)) {
            found.put(v.attributeName(), v.value());
        }
        return found;
    }

    private static IngestNotice skipped(int rowNo, String code, String subject, String detail) {
        return new IngestNotice("SKIPPED_ROW", rowNo, code, subject, detail);
    }

    /** 유형 Base 의 개체를 만들고 제목을 넣고 Other 로 잇는 매핑. 열: code, title, other. */
    private static MappingDefinition full(String source) {
        return new MappingDefinition(source, MappingFileFormat.CSV, null, null, List.of(), List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                List.of(new MappedRelation("links", BASE_BY_CODE, new MappedEntityRef("Other", "other"), null, null)));
    }

    /** 여러 행이 같은 Other 개체를 가리키며 그 이름을 적는 매핑. 열: line, other, otherName. */
    private static MappingDefinition shared(String source) {
        MappedEntityRef other = new MappedEntityRef("Other", "other");
        return new MappingDefinition(source, MappingFileFormat.CSV, null, null, List.of(), List.of("line"), other,
                List.of(new MappedEntity("Other", "other",
                        List.of(new MappedAttribute("otherName", "otherName", null, null, null, null, null, null)))),
                List.of());
    }

    /** 지어낸 작은 Pack 의 정의. 저장소에 적재하지 않고 손으로 만든다. */
    private static PackDefinition pack(String packId, MappingDefinition... mappings) {
        List<AttributeDefinition> attributes = new ArrayList<>(List.of(
                new AttributeDefinition("active", "쓰는가", "Base", ValueFormat.BOOLEAN),
                new AttributeDefinition("count", "수", "Base", ValueFormat.INTEGER),
                new AttributeDefinition("flag", "표시", "Base", ValueFormat.BOOLEAN),
                new AttributeDefinition("otherName", "다른 이름", "Other", ValueFormat.STRING),
                new AttributeDefinition("ratio", "비율", "Base", ValueFormat.DECIMAL),
                new AttributeDefinition("since", "시작", "Base", ValueFormat.DATE),
                new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING),
                new AttributeDefinition("until", "끝", "Base", ValueFormat.DATE)));
        return new PackDefinition(packId, "이름표", 3, RECORDED_AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET),
                        new RelationTypeDefinition("peers", "나란하다", "Base", "Base", PackImpactDirection.BOTH)),
                attributes, List.of(), List.of(mappings));
    }
}
