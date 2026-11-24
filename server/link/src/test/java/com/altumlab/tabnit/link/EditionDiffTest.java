package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.altumlab.tabnit.core.AttributeValue;
import com.altumlab.tabnit.core.AttributeValueQuery;
import com.altumlab.tabnit.core.EntityRef;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * C-05 수용 기준: 추가, 변경, 삭제를 항목 단위로 낸다. 일반 시험(첫 판, 변화 없음, 전부 변경) 통과.
 *
 * <p>일반 시험을 먼저 둔다 (지어낸 이름의 매핑과 자료). 그 위에 저장소에 들어 있는 Pack 의 시나리오를 차례로 넣어 본다.
 * 시험마다 다른 Pack 식별자를 쓴다. 첫 판의 적용일은 D1, 둘째 판은 D2, 셋째 판은 D3 이다.
 */
class EditionDiffTest {

    private static final Instant AT = Instant.parse("2026-03-02T01:00:00Z");

    private static final LocalDate D1 = LocalDate.parse("2026-03-01");

    private static final LocalDate D2 = LocalDate.parse("2026-06-01");

    private static final LocalDate D3 = LocalDate.parse("2026-09-01");

    private static final MappedEntityRef BASE_BY_CODE = new MappedEntityRef("Base", "code");

    private static final String HEADER = "code,title,count,other\n";

    private final EditionIngest ingest = new EditionIngest(LinkTestDatabase.dataSource());

    private final EditionRecords records = new EditionRecords(LinkTestDatabase.dataSource());

    private final RelationQuery relations = new RelationQuery(LinkTestDatabase.dataSource());

    private final AttributeValueQuery values = new AttributeValueQuery(LinkTestDatabase.dataSource());

    // ── 수용 기준의 세 가지: 첫 판, 변화 없음, 전부 변경 ──────────────────────

    @Test
    void 첫_판은_변동_이벤트를_만들지_않는다() {
        PackDefinition pack = pack("d-first", full());

        IngestResult first = put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\n");

        assertThat(first.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(records.changeEvents("d-first|src|1")).isEmpty();
        assertThat(records.items("d-first|src|1")).extracting(IngestedItem::primaryEntityId)
            .containsExactly("d-first|Base|B-1", "d-first|Base|B-2");
    }

    @Test
    void 바뀐_것이_없는_판은_변동이_없고_아무것도_끝내지도_새로_넣지도_않는다() {
        PackDefinition pack = pack("d-same", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\n");

        // 행의 차례, 매핑이 쓰지 않는 열, 같은 수의 다른 표기만 다르다. 파일의 바이트는 다르므로 새 판이 된다.
        IngestResult second = put(pack, 2, D2, "code,title,count,other,memo\nB-2,둘째,+2,O-1,메모\nB-1,첫째,01,O-1,\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(second.editionId()).isEqualTo("d-same|src|2");
        assertThat(records.changeEvents("d-same|src|2")).isEmpty();
        // 둘째 판의 항목은 첫 판이 만든 것을 그대로 가리킨다. 행 번호만 새 파일의 것이다.
        assertThat(outputsOf("d-same|src|2")).isEqualTo(outputsOf("d-same|src|1"));
        assertThat(records.items("d-same|src|2")).extracting(IngestedItem::rowNo).containsExactly(3, 2);
        // 관계와 속성 값은 첫 판의 것이 시작일과 출처를 지킨 채 이어진다.
        assertThat(relations.relationsAsOf("d-same|Base|B-1", D2)).singleElement().satisfies(r -> {
            assertThat(r.period()).isEqualTo(new ValidityPeriod(D1, null));
            assertThat(r.provenance().editionNo()).isEqualTo(1);
        });
        assertThat(values.valueAsOf("d-same|Base|B-1", "title", D2).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(D1, null));
    }

    @Test
    void 모든_행이_바뀐_판은_행마다_변경을_낸다() {
        PackDefinition pack = pack("d-all", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\n");

        put(pack, 2, D2, HEADER + "B-1,첫째 고침,1,O-1\nB-2,둘째,2,O-2\n");

        List<ChangeEvent> events = records.changeEvents("d-all|src|2");
        assertThat(events).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(tuple("B-1", ChangeKind.CHANGED), tuple("B-2", ChangeKind.CHANGED));
        assertThat(events).allSatisfy(e -> {
            assertThat(e.status()).isEqualTo(ChangeEventStatus.PENDING);
            assertThat(e.failedStage()).isNull();
            assertThat(e.failureCode()).isNull();
            assertThat(e.editionId()).isEqualTo("d-all|src|2");
        });
        assertThat(events.get(0).id()).isEqualTo("d-all|src|2|B-1");
        assertThat(events.get(0).primaryEntityId()).isEqualTo("d-all|Base|B-1");
        // 변경된 행의 변동 대상은 앞 판과 새 판에서 만든 개체를 합친 것이다 (D2 AD-58). 식별자 순이다.
        assertThat(events.get(0).targetEntityIds()).containsExactly("d-all|Base|B-1", "d-all|Other|O-1");
        assertThat(events.get(1).targetEntityIds()).containsExactly("d-all|Base|B-2", "d-all|Other|O-1",
                "d-all|Other|O-2");
    }

    // ── 추가, 변경, 삭제 ────────────────────────────────────────────

    @Test
    void 추가된_행은_새_판의_적용일부터_들어가고_추가_변동이_된다() {
        PackDefinition pack = pack("d-add", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\n");

        put(pack, 2, D2, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-9\n");

        assertThat(records.changeEvents("d-add|src|2")).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(ChangeKind.ADDED);
            assertThat(e.itemKey()).isEqualTo("B-2");
            assertThat(e.primaryEntityId()).isEqualTo("d-add|Base|B-2");
            assertThat(e.targetEntityIds()).containsExactly("d-add|Base|B-2", "d-add|Other|O-9");
        });
        assertThat(relations.relationsAsOf("d-add|Base|B-2", D2.minusDays(1))).isEmpty();
        assertThat(relations.relationsAsOf("d-add|Base|B-2", D2)).singleElement().satisfies(r -> {
            assertThat(r.period()).isEqualTo(new ValidityPeriod(D2, null));
            assertThat(r.provenance().editionNo()).isEqualTo(2);
        });
    }

    @Test
    void 없어진_행이_넣었던_관계와_속성_값은_새_판의_적용일로_끝나고_삭제_변동이_된다() {
        PackDefinition pack = pack("d-remove", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-2\n");

        put(pack, 2, D2, HEADER + "B-1,첫째,1,O-1\n");

        assertThat(records.changeEvents("d-remove|src|2")).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(ChangeKind.REMOVED);
            assertThat(e.itemKey()).isEqualTo("B-2");
            // 삭제된 행의 주 개체와 변동 대상은 앞 판의 그 행의 것이다 (D2 AD-58, AD-120).
            assertThat(e.primaryEntityId()).isEqualTo("d-remove|Base|B-2");
            assertThat(e.targetEntityIds()).containsExactly("d-remove|Base|B-2", "d-remove|Other|O-2");
        });
        // 시작일은 포함, 종료일은 제외다. 지우지 않는다: 적용일 전날에는 그대로 보인다.
        assertThat(relations.relationsAsOf("d-remove|Base|B-2", D2.minusDays(1))).singleElement()
            .satisfies(r -> assertThat(r.period()).isEqualTo(new ValidityPeriod(D1, D2)));
        assertThat(relations.relationsAsOf("d-remove|Base|B-2", D2)).isEmpty();
        assertThat(values.valuesAsOf("d-remove|Base|B-2", D2.minusDays(1))).hasSize(2);
        assertThat(values.valuesAsOf("d-remove|Base|B-2", D2)).isEmpty();
        assertThat(records.items("d-remove|src|2")).extracting(IngestedItem::itemKey).containsExactly("B-1");
        // 남은 행의 것은 끝나지 않는다.
        assertThat(relations.relationsAsOf("d-remove|Base|B-1", D2)).hasSize(1);
    }

    @Test
    void 바뀐_행은_바뀐_것만_끝내고_다시_넣는다() {
        PackDefinition pack = pack("d-change", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\n");

        put(pack, 2, D2, HEADER + "B-1,고친 이름,1,O-1\n");

        // 바뀐 속성: 앞 값은 적용일로 끝나고 새 값이 적용일부터 들어간다.
        assertThat(values.valueAsOf("d-change|Base|B-1", "title", D2.minusDays(1))).hasValueSatisfying(v -> {
            assertThat(v.value()).isEqualTo("첫째");
            assertThat(v.period()).isEqualTo(new ValidityPeriod(D1, D2));
        });
        assertThat(values.valueAsOf("d-change|Base|B-1", "title", D2)).hasValueSatisfying(v -> {
            assertThat(v.value()).isEqualTo("고친 이름");
            assertThat(v.period()).isEqualTo(new ValidityPeriod(D2, null));
            assertThat(v.provenance().editionNo()).isEqualTo(2);
        });
        // 바뀌지 않은 속성과 관계: 첫 판의 것이 그대로다.
        assertThat(values.valueAsOf("d-change|Base|B-1", "count", D2).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(D1, null));
        assertThat(relations.relationsAsOf("d-change|Base|B-1", D2)).singleElement().satisfies(r -> {
            assertThat(r.period()).isEqualTo(new ValidityPeriod(D1, null));
            assertThat(r.provenance().editionNo()).isEqualTo(1);
        });
        // 둘째 판의 항목에는 이어진 것과 새로 만든 것이 함께 적힌다 (D2 AD-151).
        assertThat(records.items("d-change|src|2").get(0).outputs()).containsExactly(
                new ItemOutput(ItemOutput.Kind.ENTITY, "d-change|Base|B-1"),
                new ItemOutput(ItemOutput.Kind.ENTITY, "d-change|Other|O-1"),
                new ItemOutput(ItemOutput.Kind.RELATION, "d-change|Base|B-1|Other|O-1|links|src|1|2026-03-01"),
                new ItemOutput(ItemOutput.Kind.ATTRIBUTE_VALUE, "d-change|Base|B-1|count|2026-03-01"),
                new ItemOutput(ItemOutput.Kind.ATTRIBUTE_VALUE, "d-change|Base|B-1|title|2026-06-01"));
        assertThat(records.changeEvents("d-change|src|2")).extracting(ChangeEvent::kind)
            .containsExactly(ChangeKind.CHANGED);
    }

    @Test
    void 관계의_끝이_바뀌면_앞_관계는_끝나고_새_관계가_들어가며_빈_칸이_되면_끝나기만_한다() {
        PackDefinition pack = pack("d-relation", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\n");

        put(pack, 2, D2, HEADER + "B-1,첫째,1,O-2\nB-2,둘째,,\n");

        assertThat(relations.relationsAsOf("d-relation|Base|B-1", D2)).extracting(Relation::targetEntityId)
            .containsExactly("d-relation|Other|O-2");
        assertThat(relations.relationsAsOf("d-relation|Base|B-1", D2.minusDays(1))).extracting(Relation::targetEntityId)
            .containsExactly("d-relation|Other|O-1");
        // 빈 칸이 된 속성과 관계는 끝난다 (D2 AD-94).
        assertThat(relations.relationsAsOf("d-relation|Base|B-2", D2)).isEmpty();
        assertThat(values.valueAsOf("d-relation|Base|B-2", "count", D2)).isEmpty();
        assertThat(values.valueAsOf("d-relation|Base|B-2", "count", D2.minusDays(1))).isPresent();
        assertThat(values.valueAsOf("d-relation|Base|B-2", "title", D2)).isPresent();
        // 빈 칸이 된 행의 변동 대상에는 앞 판에서 이어져 있던 상대도 든다.
        assertThat(records.changeEvents("d-relation|src|2").get(1).targetEntityIds())
            .containsExactly("d-relation|Base|B-2", "d-relation|Other|O-1");
    }

    @Test
    void 판이_여럿_쌓여도_바로_앞_판과_비교한다() {
        PackDefinition pack = pack("d-three", full());
        put(pack, 1, D1, HEADER + "B-1,하나,1,O-1\nB-2,둘째,2,O-1\n");
        put(pack, 2, D2, HEADER + "B-1,둘,1,O-1\n");

        // 셋째 판: B-1 은 다시 바뀌고, 둘째 판에서 없어진 B-2 가 돌아온다.
        IngestResult third = put(pack, 3, D3, HEADER + "B-1,셋,1,O-1\nB-2,둘째,2,O-1\n");

        assertThat(third.editionId()).isEqualTo("d-three|src|3");
        assertThat(records.changeEvents("d-three|src|3")).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(tuple("B-1", ChangeKind.CHANGED), tuple("B-2", ChangeKind.ADDED));
        assertThat(titleAt("d-three|Base|B-1", D1)).isEqualTo("하나");
        assertThat(titleAt("d-three|Base|B-1", D2)).isEqualTo("둘");
        assertThat(titleAt("d-three|Base|B-1", D3)).isEqualTo("셋");
        // 돌아온 행의 관계는 새 관계다. 사이에는 없었다.
        assertThat(relations.relationsAsOf("d-three|Base|B-2", D2.minusDays(1))).hasSize(1);
        assertThat(relations.relationsAsOf("d-three|Base|B-2", D2)).isEmpty();
        assertThat(relations.relationsAsOf("d-three|Base|B-2", D3)).singleElement()
            .satisfies(r -> assertThat(r.period()).isEqualTo(new ValidityPeriod(D3, null)));
        assertThat(records.ingestRecords("d-three", "src")).extracting(IngestRecord::editionId)
            .containsExactly("d-three|src|1", "d-three|src|2", "d-three|src|3");
    }

    @Test
    void 행이_없는_파일이_다음_판으로_오면_모든_행이_삭제가_된다() {
        PackDefinition pack = pack("d-empty", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\n");

        put(pack, 2, D2, HEADER);

        assertThat(records.changeEvents("d-empty|src|2")).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(tuple("B-1", ChangeKind.REMOVED), tuple("B-2", ChangeKind.REMOVED));
        assertThat(relations.relationsAsOf("d-empty|Other|O-1", D2)).isEmpty();
        assertThat(relations.relationsAsOf("d-empty|Other|O-1", D2.minusDays(1))).hasSize(2);
        assertThat(records.items("d-empty|src|2")).isEmpty();
    }

    @Test
    void 행의_차례만_다른_두_파일은_같은_변동과_같은_저장_결과를_낸다() {
        String first = HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\nB-3,셋째,3,O-2\n";
        PackDefinition one = pack("d-order-1", full());
        PackDefinition two = pack("d-order-2", full());
        put(one, 1, D1, first);
        put(two, 1, D1, first);

        put(one, 2, D2, HEADER + "B-4,넷째,4,O-2\nB-1,첫째 고침,1,O-1\nB-3,셋째,3,O-2\n");
        put(two, 2, D2, HEADER + "B-3,셋째,3,O-2\nB-1,첫째 고침,1,O-1\nB-4,넷째,4,O-2\n");

        assertThat(shape(records.changeEvents("d-order-1|src|2"), "d-order-1"))
            .isEqualTo(shape(records.changeEvents("d-order-2|src|2"), "d-order-2"))
            .hasSize(3);
        assertThat(strip(outputsOf("d-order-1|src|2"), "d-order-1")).isEqualTo(strip(outputsOf("d-order-2|src|2"), "d-order-2"));
    }

    // ── 여러 행이 같은 것을 적을 때 ───────────────────────────────────

    @Test
    void 없어진_행이_넣었던_값을_다른_행이_여전히_넣고_있으면_끝내지_않는다() {
        PackDefinition pack = pack("d-shared", shared());
        put(pack, 1, D1, "line,other,otherName\n1,O-1,상대\n2,O-1,상대\n");

        put(pack, 2, D2, "line,other,otherName\n2,O-1,상대\n");

        // 같은 개체의 같은 값을 2번 행이 여전히 넣고 있다 (D2 AD-121).
        assertThat(values.valueAsOf("d-shared|Other|O-1", "otherName", D2).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(D1, null));
        assertThat(records.changeEvents("d-shared|src|2")).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(tuple("1", ChangeKind.REMOVED));

        // 마지막 행까지 없어지면 끝난다.
        put(pack, 3, D3, "line,other,otherName\n");
        assertThat(values.valueAsOf("d-shared|Other|O-1", "otherName", D3)).isEmpty();
        assertThat(values.valueAsOf("d-shared|Other|O-1", "otherName", D3.minusDays(1))).isPresent();
    }

    @Test
    void 새_판에서_값이_부딪히면_앞_판의_값을_그대로_두고_그_속성의_변동을_만들지_않는다() {
        PackDefinition pack = pack("d-conflict", shared());
        put(pack, 1, D1, "line,other,otherName\n1,O-1,상대\n2,O-1,상대\n");

        // 2번 행만 다른 값을 적었다. 어느 것이 맞는지 고르지 않는다 (D2 AD-146).
        IngestResult second = put(pack, 2, D2, "line,other,otherName\n1,O-1,상대\n2,O-1,다른 이름\n");

        assertThat(values.valueAsOf("d-conflict|Other|O-1", "otherName", D2)).hasValueSatisfying(v -> {
            assertThat(v.value()).isEqualTo("상대");
            assertThat(v.period()).isEqualTo(new ValidityPeriod(D1, null));
        });
        assertThat(records.changeEvents("d-conflict|src|2")).isEmpty();
        assertThat(second.mapping().notices()).extracting(IngestNotice::kind, IngestNotice::rowNo, IngestNotice::detail)
            .containsExactly(tuple("ATTRIBUTE_CONFLICT", 2, "상대"), tuple("ATTRIBUTE_CONFLICT", 3, "다른 이름"));

        // 다음 판에서 두 행이 같은 값으로 맞춰지면 그때 바뀐다.
        put(pack, 3, D3, "line,other,otherName\n1,O-1,다른 이름\n2,O-1,다른 이름\n");
        assertThat(values.valueAsOf("d-conflict|Other|O-1", "otherName", D3)).hasValueSatisfying(v -> {
            assertThat(v.value()).isEqualTo("다른 이름");
            assertThat(v.period()).isEqualTo(new ValidityPeriod(D3, null));
        });
        assertThat(values.valueAsOf("d-conflict|Other|O-1", "otherName", D3.minusDays(1)).orElseThrow().value())
            .isEqualTo("상대");
        assertThat(records.changeEvents("d-conflict|src|3")).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(tuple("1", ChangeKind.CHANGED), tuple("2", ChangeKind.CHANGED));
    }

    // ── 건너뛴 행: 앞 판의 것을 이어 둔다 ────────────────────────────────

    @Test
    void 키가_겹치거나_값이_틀려_건너뛴_행은_앞_판의_항목을_그대로_이어_둔다() {
        PackDefinition pack = pack("d-carry", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\nB-3,셋째,3,O-1\n");

        // B-1 은 두 번 적혔고, B-2 는 수가 아닌 값이 들었다. B-9 는 처음 나온 키인데 값이 틀렸다.
        IngestResult second = put(pack, 2, D2,
                HEADER + "B-1,고침,1,O-1\nB-1,또 고침,1,O-1\nB-2,둘째 고침,둘,O-1\nB-3,셋째,3,O-1\nB-9,새 행,아홉,O-1\n");

        // 끝내지 않고, 변동도 만들지 않는다 (D2 AD-157, AD-196).
        assertThat(records.changeEvents("d-carry|src|2")).isEmpty();
        assertThat(titleAt("d-carry|Base|B-1", D2)).isEqualTo("첫째");
        assertThat(titleAt("d-carry|Base|B-2", D2)).isEqualTo("둘째");
        assertThat(relations.relationsAsOf("d-carry|Other|O-1", D2)).hasSize(3);
        // 이어 둔 항목은 앞 판의 내용 해시와 만든 것을 그대로 옮겨 적는다. 처음 나온 키의 틀린 행은 항목이 되지 않는다.
        assertThat(outputsOf("d-carry|src|2")).isEqualTo(outputsOf("d-carry|src|1"));
        assertThat(hashesOf("d-carry|src|2")).isEqualTo(hashesOf("d-carry|src|1"));
        assertThat(second.mapping().notices()).extracting(IngestNotice::rowNo, IngestNotice::code)
            .containsExactly(tuple(2, "DUPLICATE_ITEM_KEY"), tuple(3, "DUPLICATE_ITEM_KEY"), tuple(4, "INVALID_VALUE"),
                    tuple(6, "INVALID_VALUE"));

        // 다음 판에서 바르게 적히면 그때 바뀐다.
        put(pack, 3, D3, HEADER + "B-1,고침,1,O-1\nB-2,둘째,2,O-1\nB-3,셋째,3,O-1\n");
        assertThat(records.changeEvents("d-carry|src|3")).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(tuple("B-1", ChangeKind.CHANGED));
        assertThat(titleAt("d-carry|Base|B-1", D3)).isEqualTo("고침");
    }

    @Test
    void 키를_읽지_못한_행은_이을_수_없어_앞_판의_항목이_삭제가_된다() {
        PackDefinition pack = pack("d-unread", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\n");

        // 둘째 행은 칸 수가 맞지 않아 어느 항목인지 알 수 없다.
        put(pack, 2, D2, HEADER + "B-1,첫째,1,O-1\nB-2,둘째\n");

        assertThat(records.changeEvents("d-unread|src|2")).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(tuple("B-2", ChangeKind.REMOVED));
    }

    // ── 유효기간의 열이 있는 매핑 ──────────────────────────────────────

    @Test
    void 아직_시작하지_않은_것을_끝내야_하는_판은_거부한다() {
        PackDefinition pack = pack("d-future", dated());
        put(pack, 1, D1, "code,title,from,to\nB-1,앞으로의 이름,2027-01-01,\nB-2,이름,,\n");

        // B-1 의 값은 2027-01-01 에 시작한다. 2026-06-01 의 판이 그것을 뺐다. 종료일은 시작일보다 뒤여야 한다.
        IngestResult second = put(pack, 2, D2, "code,title,from,to\nB-2,이름,,\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.REJECTED);
        assertThat(second.reasonCode()).isEqualTo("CANNOT_END");
        assertThat(second.reasonDetail()).isEqualTo("d-future|Base|B-1|title|2027-01-01");
        assertThat(records.findEdition("d-future|src|2")).isEmpty();
        assertThat(values.valueAsOf("d-future|Base|B-1", "title", LocalDate.parse("2027-01-01"))).isPresent();
        // 적용일을 그 시작일 뒤로 잡으면 들어간다.
        assertThat(put(pack, 2, LocalDate.parse("2027-02-01"), "code,title,from,to\nB-2,이름,,\n").outcome())
            .isEqualTo(IngestOutcome.ACCEPTED);
    }

    @Test
    void 같은_시작일로_값만_고치면_새_값은_새_판의_적용일부터_들어가고_알린다() {
        PackDefinition pack = pack("d-clip", dated());
        put(pack, 1, D1, "code,title,from,to\nB-1,틀린 이름,2026-01-01,\n");

        IngestResult second = put(pack, 2, D2, "code,title,from,to\nB-1,바른 이름,2026-01-01,\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        // 과거의 시점 조회 결과는 바뀌지 않는다.
        assertThat(values.valueAsOf("d-clip|Base|B-1", "title", D2.minusDays(1))).hasValueSatisfying(v -> {
            assertThat(v.value()).isEqualTo("틀린 이름");
            assertThat(v.period()).isEqualTo(new ValidityPeriod(LocalDate.parse("2026-01-01"), D2));
        });
        assertThat(values.valueAsOf("d-clip|Base|B-1", "title", D2)).hasValueSatisfying(v -> {
            assertThat(v.value()).isEqualTo("바른 이름");
            assertThat(v.period()).isEqualTo(new ValidityPeriod(D2, null));
        });
        assertThat(second.mapping().notices()).containsExactly(new IngestNotice("PERIOD_ADJUSTED", 2,
                "STARTS_AT_EFFECTIVE_DATE", "d-clip|Base|B-1|title", "2026-01-01~"));
        assertThat(records.changeEvents("d-clip|src|2")).extracting(ChangeEvent::kind).containsExactly(ChangeKind.CHANGED);

        // 다음 판에서 그 행이 그대로면 이어진다. 다시 알리지 않는다.
        IngestResult third = put(pack, 3, D3, "code,title,from,to,memo\nB-1,바른 이름,2026-01-01,,메모\n");
        assertThat(third.mapping().notices()).isEmpty();
        assertThat(records.changeEvents("d-clip|src|3")).isEmpty();
        assertThat(values.valueAsOf("d-clip|Base|B-1", "title", D3).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(D2, null));
    }

    @Test
    void 이미_지난_기간의_값을_고친_것은_넣지_않고_알린다() {
        PackDefinition pack = pack("d-past", dated());
        put(pack, 1, D1, "code,title,from,to\nB-1,옛 이름,2025-01-01,2026-01-01\n");

        IngestResult second = put(pack, 2, D2, "code,title,from,to\nB-1,고친 옛 이름,2025-01-01,2026-01-01\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(values.valueAsOf("d-past|Base|B-1", "title", LocalDate.parse("2025-06-01")).orElseThrow().value())
            .isEqualTo("옛 이름");
        assertThat(second.mapping().notices()).containsExactly(new IngestNotice("PERIOD_ADJUSTED", 2, "ALREADY_PAST",
                "d-past|Base|B-1|title", "2025-01-01~2026-01-01"));
        // 저장된 것이 바뀌지 않았으므로 변동도 없다 (C-10, D2 AD-372). 그 행은 앞 판의 값을 그대로 이어 적는다.
        assertThat(records.changeEvents("d-past|src|2")).isEmpty();
        // 다음 판에서도 같다: 알림은 다시 나오고 변동은 없다.
        IngestResult third = put(pack, 3, D3, "code,title,from,to\nB-1,고친 옛 이름,2025-01-01,2026-01-01\n\n");
        assertThat(third.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(third.mapping().notices()).extracting(IngestNotice::code).containsExactly("ALREADY_PAST");
        assertThat(records.changeEvents("d-past|src|3")).isEmpty();
    }

    // C-10 (D2 AD-372): 지난 기간의 값을 고친 것은 변동이 아니지만, 열려 있던 값을 지난 날짜로 끝냈다고 적은 것은
    // 저장된 값이 실제로 끝나므로 변동이다.
    @Test
    void 열려_있던_값을_지난_종료일로_적으면_값이_끝나고_변동이다() {
        PackDefinition pack = pack("d-past-open", dated());
        put(pack, 1, D1, "code,title,from,to\nB-1,이름,2026-01-01,\n");

        // 종료일 2026-04-01 은 둘째 판의 적용일(2026-06-01)보다 앞이다: 새 줄은 넣지 않고, 열려 있던 앞의 값은 적용일로 끝난다.
        IngestResult second = put(pack, 2, D2, "code,title,from,to\nB-1,이름,2026-01-01,2026-04-01\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(titleAt("d-past-open|Base|B-1", D2.minusDays(1))).isEqualTo("이름");
        assertThat(titleAt("d-past-open|Base|B-1", D2)).isNull();
        assertThat(records.changeEvents("d-past-open|src|2")).extracting(ChangeEvent::itemKey, ChangeEvent::changedValues)
            .containsExactly(tuple("B-1", List.of(new ValueChange("d-past-open|Base|B-1", "title", "이름", null))));
    }

    // C-10 (D2 AD-372): 시작일을 비우고 종료일만 적은 행.
    @Test
    void 시작일을_비우고_종료일만_적은_행은_그_종료일이_지난_뒤의_판에서도_건너뛰지_않고_이어진다() {
        PackDefinition pack = pack("d-blank-from", datedWithCount());
        String header = "code,title,from,to,count\n";
        // 첫 판(적용일 2026-03-01): 시작일이 빈 칸이면 적용일부터다. 2026-05-01 에 끝난다.
        put(pack, 1, D1, header + "B-1,이름,,2026-05-01,1\nB-2,둘째,,,2\n");

        // 둘째 판(적용일 2026-06-01)은 그 종료일이 지난 뒤다. B-1 은 그대로이고 B-2 의 수만 바뀐다.
        IngestResult second = put(pack, 2, D2, header + "B-1,이름,,2026-05-01,1\nB-2,둘째,,,20\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        // 바뀐 것이 없는 행을 건너뛰지 않고 알림도 내지 않는다.
        assertThat(second.mapping().notices()).isEmpty();
        assertThat(records.changeEvents("d-blank-from|src|2")).extracting(ChangeEvent::itemKey).containsExactly("B-2");
        // 셋째 판: 그 행의 다른 칸(수)을 고치면 반영된다. 지난 제목은 그대로다.
        IngestResult third = put(pack, 3, D3, header + "B-1,이름,,2026-05-01,7\nB-2,둘째,,,20\n");
        assertThat(third.mapping().notices()).isEmpty();
        assertThat(values.valueAsOf("d-blank-from|Base|B-1", "count", D3).orElseThrow().value()).isEqualTo("7");
        assertThat(titleAt("d-blank-from|Base|B-1", LocalDate.parse("2026-04-30"))).isEqualTo("이름");
        assertThat(records.changeEvents("d-blank-from|src|3")).extracting(ChangeEvent::itemKey, ChangeEvent::changedValues)
            .containsExactly(tuple("B-1", List.of(new ValueChange("d-blank-from|Base|B-1", "count", "1", "7"))));
    }

    @Test
    void 둘째_판에_새로_나온_시작일_빈_칸과_지난_종료일은_그_값만_넣지_않고_행의_나머지는_받는다() {
        PackDefinition pack = pack("d-blank-new", datedWithCount());
        String header = "code,title,from,to,count\n";
        put(pack, 1, D1, header + "B-1,이름,,,1\n");

        // B-3 은 새 행이다. 제목의 종료일 2026-04-01 은 적용일(2026-06-01)보다 앞이라 넣을 기간이 없다.
        IngestResult second = put(pack, 2, D2, header + "B-1,이름,,,1\nB-3,지난 이름,,2026-04-01,3\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(second.mapping().notices()).containsExactly(new IngestNotice("PERIOD_ADJUSTED", 3, "ALREADY_PAST",
                "d-blank-new|Base|B-3|title", "~2026-04-01"));
        // 행은 받았다: 개체가 생기고 수가 들어갔다. 제목은 없다.
        assertThat(values.valueAsOf("d-blank-new|Base|B-3", "count", D2).orElseThrow().value()).isEqualTo("3");
        assertThat(titleAt("d-blank-new|Base|B-3", D2)).isNull();
        assertThat(records.changeEvents("d-blank-new|src|2")).extracting(ChangeEvent::itemKey, ChangeEvent::kind,
                ChangeEvent::changedValues)
            .containsExactly(tuple("B-3", ChangeKind.ADDED, List.of(new ValueChange("d-blank-new|Base|B-3", "count", null, "3"))));
    }

    // 고친 것의 검토에서 나온 경우를 시험으로 고정한다 (C-10).
    @Test
    void 시작일을_비운_행의_종료일을_아직_오지_않은_날에서_지난_날로_당기면_판_전체를_거부한다() {
        PackDefinition pack = pack("d-blank-pull", datedWithCount());
        String header = "code,title,from,to,count\n";
        // 첫 판: [2026-03-01, 2026-09-01) 로 저장된다.
        put(pack, 1, D1, header + "B-1,이름,,2026-09-01,1\n");

        // 둘째 판(적용일 2026-06-01): 종료일을 2026-05-01 로 당겼다. 저장된 값은 2026-09-01 에 끝나기로 되어 있고
        // 한 번 넣은 종료일은 바꾸지 않으므로 (D2 AD-162, AD-198) 당겨 끝낼 수 없다. 시작일을 적은 행과 같다.
        IngestResult second = put(pack, 2, D2, header + "B-1,이름,,2026-05-01,1\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.REJECTED);
        assertThat(second.reasonCode()).isEqualTo("CANNOT_END");
        assertThat(records.findEdition("d-blank-pull|src|2")).isEmpty();
        assertThat(titleAt("d-blank-pull|Base|B-1", LocalDate.parse("2026-08-31"))).isEqualTo("이름");
    }

    @Test
    void 종료일이_판의_적용일과_같은_날인_시작일_빈_칸의_줄도_이미_지난_것이다() {
        PackDefinition pack = pack("d-blank-edge", datedWithCount());
        String header = "code,title,from,to,count\n";
        // 종료일이 둘째 판의 적용일(2026-06-01)과 같다: 종료일은 제외이므로 그날에는 유효하지 않다.
        put(pack, 1, D1, header + "B-1,이름,,2026-06-01,1\n");

        IngestResult second = put(pack, 2, D2, header + "B-1,이름,,2026-06-01,2\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(second.mapping().notices()).isEmpty();
        assertThat(titleAt("d-blank-edge|Base|B-1", D2.minusDays(1))).isEqualTo("이름");
        assertThat(titleAt("d-blank-edge|Base|B-1", D2)).isNull();
        assertThat(records.changeEvents("d-blank-edge|src|2").get(0).changedValues())
            .containsExactly(new ValueChange("d-blank-edge|Base|B-1", "count", "1", "2"));
    }

    @Test
    void 지난_기간의_값의_칸을_비우면_저장된_것은_그대로여도_그_행이_더는_적지_않으므로_변동이다() {
        PackDefinition pack = pack("d-past-blank", dated());
        put(pack, 1, D1, "code,title,from,to\nB-1,옛 이름,2025-01-01,2026-01-01\n");

        // 값을 고친 것과 다르다: 칸을 비우면 그 행은 그 속성을 더는 적지 않는다. 지난 값은 저장소에 그대로 남는다.
        put(pack, 2, D2, "code,title,from,to\nB-1,,2025-01-01,2026-01-01\n");

        assertThat(values.valueAsOf("d-past-blank|Base|B-1", "title", LocalDate.parse("2025-06-01")).orElseThrow().value())
            .isEqualTo("옛 이름");
        assertThat(records.changeEvents("d-past-blank|src|2")).extracting(ChangeEvent::kind, ChangeEvent::changedValues)
            .containsExactly(tuple(ChangeKind.CHANGED, List.of(new ValueChange("d-past-blank|Base|B-1", "title", "옛 이름", null))));
    }

    @Test
    void 첫_판에서_시작일을_비우고_적용일보다_앞선_종료일을_적은_행은_기간이_틀린_것이라_건너뛴다() {
        PackDefinition pack = pack("d-blank-first", datedWithCount());

        // 첫 판은 적힌 그대로 넣는다. 빈 시작일은 적용일(2026-03-01)이고 종료일 2026-02-01 은 그보다 앞이다.
        IngestResult first = put(pack, 1, D1, "code,title,from,to,count\nB-1,이름,,2026-02-01,1\nB-2,둘째,,,2\n");

        assertThat(first.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(first.mapping().notices()).extracting(IngestNotice::kind, IngestNotice::rowNo, IngestNotice::code)
            .containsExactly(tuple("SKIPPED_ROW", 2, "INVALID_PERIOD"));
        assertThat(records.items("d-blank-first|src|1")).extracting(IngestedItem::itemKey).containsExactly("B-2");
    }

    @Test
    void 같은_것을_다르게_적은_두_행은_다음_판에서도_부딪히지_않는다() {
        MappedEntityRef other = new MappedEntityRef("Other", "other");
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("line"), other,
                List.of(new MappedEntity("Other", "other",
                        List.of(new MappedAttribute("otherName", "otherName", "from", null, null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("d-notation", mapping);
        // 1번 행은 시작일을 비웠고 2번 행은 적용일과 같은 날짜를 적었다. 값과 기간이 같으므로 하나만 저장된다.
        IngestResult first = put(pack, 1, D1, "line,other,otherName,from\n1,O-1,상대,\n2,O-1,상대,2026-03-01\n");
        assertThat(first.mapping().notices()).isEmpty();

        // 두 행 모두 그대로다. 매핑이 쓰지 않는 열만 더했다.
        IngestResult second = put(pack, 2, D2,
                "line,other,otherName,from,memo\n1,O-1,상대,,메모\n2,O-1,상대,2026-03-01,메모\n");

        assertThat(second.mapping().notices()).isEmpty();
        assertThat(records.changeEvents("d-notation|src|2")).isEmpty();
        assertThat(values.valueAsOf("d-notation|Other|O-1", "otherName", D2).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(D1, null));
        assertThat(outputsOf("d-notation|src|2")).isEqualTo(outputsOf("d-notation|src|1"));
    }

    @Test
    void 둘째_판부터는_새로_넣는_것이_그_판의_적용일보다_앞에서_시작하지_않는다() {
        PackDefinition pack = pack("d-back", dated());
        put(pack, 1, D1, "code,title,from,to\nB-1,하나,2026-01-01,\n");
        // 첫 판은 적힌 날짜 그대로 넣는다.
        assertThat(values.valueAsOf("d-back|Base|B-1", "title", LocalDate.parse("2026-01-01"))).isPresent();

        // 둘째 판: B-1 의 값이 빈 칸이 되어 끝난다. 뒤늦게 적힌 B-2 는 시작일이 과거다.
        IngestResult second = put(pack, 2, D2, "code,title,from,to\nB-1,,,\nB-2,둘,2025-07-01,\n");
        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(values.valueAsOf("d-back|Base|B-2", "title", D2.minusDays(1))).isEmpty();
        assertThat(values.valueAsOf("d-back|Base|B-2", "title", D2).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(D2, null));
        assertThat(second.mapping().notices()).containsExactly(new IngestNotice("PERIOD_ADJUSTED", 3,
                "STARTS_AT_EFFECTIVE_DATE", "d-back|Base|B-2|title", "2025-07-01~"));

        // 셋째 판: 끝났던 B-1 의 값이 같은 행으로 돌아온다. 끝난 기간과 겹치지 않게 적용일부터 들어간다.
        IngestResult third = put(pack, 3, D3, "code,title,from,to\nB-1,하나,2026-01-01,\nB-2,둘,2025-07-01,\n");
        assertThat(third.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(values.valueAsOf("d-back|Base|B-1", "title", D2.minusDays(1)).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(LocalDate.parse("2026-01-01"), D2));
        assertThat(values.valueAsOf("d-back|Base|B-1", "title", D2)).isEmpty();
        assertThat(values.valueAsOf("d-back|Base|B-1", "title", D3).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(D3, null));
        // 그대로인 B-2 는 이어진다. 다시 알리지 않는다.
        assertThat(third.mapping().notices()).extracting(IngestNotice::rowNo, IngestNotice::code)
            .containsExactly(tuple(2, "STARTS_AT_EFFECTIVE_DATE"));
        assertThat(records.changeEvents("d-back|src|3")).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(tuple("B-1", ChangeKind.CHANGED));
    }

    @Test
    void 시작일을_당긴_알림은_과거의_시작일을_적은_행에만_남는다() {
        MappedEntityRef other = new MappedEntityRef("Other", "other");
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("line"), other,
                List.of(new MappedEntity("Other", "other",
                        List.of(new MappedAttribute("otherName", "otherName", "from", null, null, null, null, null)))),
                List.of());
        PackDefinition pack = pack("d-notice", mapping);
        put(pack, 1, D1, "line,other,otherName,from\n");

        // 두 행이 같은 값을 적는다. 1번 행은 시작일을 비웠고 2번 행은 과거의 날짜를 적었다. 넣을 기간은 같다.
        IngestResult second = put(pack, 2, D2, "line,other,otherName,from\n1,O-1,상대,\n2,O-1,상대,2026-01-01\n");

        assertThat(values.valueAsOf("d-notice|Other|O-1", "otherName", D2).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(D2, null));
        assertThat(second.mapping().notices()).containsExactly(new IngestNotice("PERIOD_ADJUSTED", 3,
                "STARTS_AT_EFFECTIVE_DATE", "d-notice|Other|O-1|otherName", "2026-01-01~"));
    }

    @Test
    void 이미_지난_기간을_적은_행이_계속_있어도_다음_판들이_들어간다() {
        PackDefinition pack = pack("d-stale", dated());
        put(pack, 1, D1, "code,title,from,to\nB-1,이름,2026-01-01,\n");

        // 둘째 판이 종료일을 과거로 적었다. 열려 있던 값은 적용일로 끝나고, 적힌 기간은 이미 지나 넣지 않는다.
        IngestResult second = put(pack, 2, D2, "code,title,from,to\nB-1,이름,2026-01-01,2026-05-01\n");
        IngestResult third = put(pack, 3, D3, "code,title,from,to,memo\nB-1,이름,2026-01-01,2026-05-01,메모\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(third.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(values.valueAsOf("d-stale|Base|B-1", "title", D2.minusDays(1)).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(LocalDate.parse("2026-01-01"), D2));
        assertThat(values.valueAsOf("d-stale|Base|B-1", "title", D2)).isEmpty();
        // 그 행이 파일에 남아 있는 동안은 판마다 같은 알림이 나온다.
        assertThat(third.mapping().notices()).extracting(IngestNotice::code).containsExactly("ALREADY_PAST");
        assertThat(records.changeEvents("d-stale|src|3")).isEmpty();
    }

    @Test
    void 부딪힌_속성의_앞_판의_값은_그것을_적었던_행에만_이어_적는다() {
        PackDefinition pack = pack("d-owner", shared());
        // 2번 행은 이름을 적지 않았다.
        put(pack, 1, D1, "line,other,otherName\n1,O-1,상대\n2,O-1,\n");

        // 2번 행이 다른 이름을 적어 1번 행과 부딪힌다. 저장된 것은 바뀌지 않으므로 변동이 없다.
        put(pack, 2, D2, "line,other,otherName\n1,O-1,상대\n2,O-1,다른 이름\n");

        assertThat(records.changeEvents("d-owner|src|2")).isEmpty();
        assertThat(outputsOf("d-owner|src|2")).isEqualTo(outputsOf("d-owner|src|1"));
    }

    // ── 저장소에 들어 있는 Pack ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("repositoryPacks")
    void 저장소의_Pack의_시나리오를_차례로_넣으면_둘째_판부터_변동이_나온다(Path dir) throws IOException {
        String packId = dir.getFileName().toString();
        // Pack 식별자를 바꿀 수 없으므로 다른 시험과 섞이지 않게 빈 데이터베이스를 따로 쓴다.
        javax.sql.DataSource fresh = LinkTestDatabase.fresh();
        EditionIngest ingest = new EditionIngest(fresh);
        EditionRecords records = new EditionRecords(fresh);
        new PackLoader(fresh).load(dir, AT);
        PackDefinition pack = new PackDefinitions(fresh).find(packId).orElseThrow();

        // 시나리오의 단계를 적힌 차례대로 넣는다. 시험에 Pack 의 이름과 수를 적지 않는다.
        Map<String, Integer> editions = new TreeMap<>();
        List<String[]> steps = steps(dir);
        assertThat(steps).isNotEmpty();
        for (String[] step : steps) {
            byte[] content = Files.readAllBytes(dir.resolve(step[1]));
            IngestResult result = ingest.ingest(pack, step[0], step[1], content, LocalDate.parse(step[2]), AT);

            int editionNo = editions.merge(step[0], 1, Integer::sum);
            assertThat(result.outcome()).as(step[1]).isEqualTo(IngestOutcome.ACCEPTED);
            assertThat(result.mapping().notices()).as(step[1]).isEmpty();
            List<ChangeEvent> events = records.changeEvents(result.editionId());
            if (editionNo == 1) {
                assertThat(events).as(step[1]).isEmpty();
            }
            else {
                // 예시 자료의 다음 판은 무엇인가 바뀐 판이다. 변동마다 주 개체가 변동 대상에 든다 (삭제도).
                assertThat(events).as(step[1]).isNotEmpty();
                assertThat(events).allSatisfy(e -> assertThat(e.targetEntityIds()).contains(e.primaryEntityId()));
            }
        }
    }

    static Stream<Path> repositoryPacks() throws IOException {
        try (Stream<Path> dirs = Files.list(Path.of("../../packs"))) {
            return dirs.filter(d -> Files.isDirectory(d.resolve("questions")) && Files.exists(d.resolve("pack.ttl")))
                .sorted()
                .toList()
                .stream();
        }
    }

    /** 시나리오의 단계: 입력 자료, 파일, 적용일. 적힌 차례 그대로다. */
    private static List<String[]> steps(Path dir) throws IOException {
        Pattern step = Pattern.compile("inputSource:\\s*([^,\\s}]+)\\s*,\\s*file:\\s*([^,\\s}]+)\\s*,\\s*effectiveDate:\\s*([0-9-]+)");
        List<String[]> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir.resolve("questions"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                Matcher m = step.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    found.add(new String[] { m.group(1), m.group(2), m.group(3) });
                }
            }
        }
        return found;
    }

    // ── 그 행에서 바뀐 것 (B-03, D2 AD-291) ──────────────────────

    @Test
    void 변경된_행은_바뀐_속성과_관계_유형만_남긴다() {
        PackDefinition pack = pack("d-items", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\nB-3,셋째,3,O-1\nB-4,넷째,4,O-1\nB-5,다섯째,5,O-1\n");

        // B-1 은 제목만, B-2 는 잇는 곳만, B-3 은 수와 잇는 곳이 바뀐다. B-4 는 수가 빈 칸이 된다. B-5 는 그대로다.
        put(pack, 2, D2, HEADER + "B-1,첫째 고침,1,O-1\nB-2,둘째,+2,O-2\nB-3,셋째,30,O-2\nB-4,넷째,,O-1\nB-5,다섯째,5,O-1\n");

        assertThat(records.changeEvents("d-items|src|2"))
            .extracting(ChangeEvent::itemKey, ChangeEvent::changedAttributes, ChangeEvent::changedRelationTypes)
            .containsExactly(tuple("B-1", List.of("title"), List.of()), tuple("B-2", List.of(), List.of("links")),
                    tuple("B-3", List.of("count"), List.of("links")), tuple("B-4", List.of("count"), List.of()));
    }

    @Test
    void 추가된_행은_넣은_것_전부를_삭제된_행은_넣었던_것_전부를_남긴다() {
        PackDefinition pack = pack("d-items-all", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,,\n");

        // B-1 과 B-2 가 없어지고 B-3 (수가 빈 칸)과 B-4 가 생긴다.
        put(pack, 2, D2, HEADER + "B-3,셋째,,O-1\nB-4,넷째,4,O-2\n");

        assertThat(records.changeEvents("d-items-all|src|2"))
            .extracting(ChangeEvent::itemKey, ChangeEvent::kind, ChangeEvent::changedAttributes,
                    ChangeEvent::changedRelationTypes)
            .containsExactly(tuple("B-1", ChangeKind.REMOVED, List.of("count", "title"), List.of("links")),
                    // 빈 칸이던 것은 넣었던 것이 아니다.
                    tuple("B-2", ChangeKind.REMOVED, List.of("title"), List.of()),
                    tuple("B-3", ChangeKind.ADDED, List.of("title"), List.of("links")),
                    tuple("B-4", ChangeKind.ADDED, List.of("count", "title"), List.of("links")));
    }

    @Test
    void 삭제된_행은_다른_행이_같은_값을_넣고_있어_끝나지_않은_것도_넣었던_것으로_남긴다() {
        PackDefinition pack = pack("d-items-shared", shared());
        put(pack, 1, D1, "line,other,otherName\n1,O-1,상대\n2,O-1,상대\n");

        put(pack, 2, D2, "line,other,otherName\n2,O-1,상대\n");

        // 저장된 이름은 2번 행이 넣고 있어 끝나지 않는다 (D2 AD-121). 그래도 1번 행이 넣었던 것이다 (D2 AD-291).
        assertThat(values.valueAsOf("d-items-shared|Other|O-1", "otherName", D2)).isPresent();
        assertThat(records.changeEvents("d-items-shared|src|2"))
            .extracting(ChangeEvent::itemKey, ChangeEvent::kind, ChangeEvent::changedAttributes)
            .containsExactly(tuple("1", ChangeKind.REMOVED, List.of("otherName")));
    }

    @Test
    void 값은_같고_유효기간만_달라져도_그_속성이_바뀐_것이다() {
        PackDefinition pack = pack("d-items-period", dated());
        put(pack, 1, D1, "code,title,from,to\nB-1,이름,2026-01-01,\nB-2,다른 이름,2026-01-01,\n");

        // B-1 의 값은 그대로이고 종료일만 생겼다.
        put(pack, 2, D2, "code,title,from,to\nB-1,이름,2026-01-01,2026-12-01\nB-2,다른 이름,2026-01-01,\n");

        assertThat(records.changeEvents("d-items-period|src|2"))
            .extracting(ChangeEvent::itemKey, ChangeEvent::changedAttributes, ChangeEvent::changedRelationTypes)
            .containsExactly(tuple("B-1", List.of("title"), List.of()));
    }

    @Test
    void 추가된_행이_적었지만_이미_지난_기간이라_넣지_않은_것은_바뀐_것에_들지_않는다() {
        PackDefinition pack = pack("d-items-past", dated());
        put(pack, 1, D1, "code,title,from,to\nB-2,이름,2026-01-01,\n");

        // 새 행 B-1 의 제목은 적용일(2026-06-01)보다 앞에 끝난 기간이다. 넣지 않는다.
        put(pack, 2, D2, "code,title,from,to\nB-1,옛 이름,2025-01-01,2026-01-01\nB-2,이름,2026-01-01,\n");

        List<ChangeEvent> events = records.changeEvents("d-items-past|src|2");
        assertThat(events).extracting(ChangeEvent::itemKey, ChangeEvent::kind)
            .containsExactly(tuple("B-1", ChangeKind.ADDED));
        // 바뀐 것이 없다(빈 목록)이지 모른다(null)가 아니다.
        assertThat(events.get(0).changedAttributes()).isNotNull().isEmpty();
        assertThat(events.get(0).changedRelationTypes()).isNotNull().isEmpty();
    }

    @Test
    void 키와_값의_앞뒤에_줄바꿈하지_않는_빈칸이_붙어도_같은_항목이고_변동이_아니다() {
        PackDefinition pack = pack("d-nbsp", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-1\n");

        // 둘째 판은 엑셀을 거치며 키와 값의 앞뒤에 눈에 보이지 않는 빈칸이 붙었다 (D2 AD-313).
        IngestResult second = put(pack, 2, D2, HEADER + "B-1\u00A0,\u00A0첫째,1\u00A0,\u00A0O-1\nB-2,둘째,2,O-1\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(records.changeEvents("d-nbsp|src|2")).isEmpty();
        assertThat(records.items("d-nbsp|src|2")).extracting(IngestedItem::itemKey).containsExactly("B-1", "B-2");
    }

    @Test
    void 키의_칸이_줄바꿈하지_않는_빈칸뿐이면_빈_키라서_그_행을_건너뛴다() {
        PackDefinition pack = pack("d-nbsp-key", full());

        IngestResult first = put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\n\u00A0,둘째,2,O-1\n");

        assertThat(first.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(records.items("d-nbsp-key|src|1")).extracting(IngestedItem::itemKey).containsExactly("B-1");
        assertThat(records.notices("d-nbsp-key|src|1")).extracting(IngestNotice::rowNo, IngestNotice::code)
            .containsExactly(tuple(3, "BLANK_KEY"));
    }

    @Test
    void 앞_판의_값을_넣던_행이_빠지고_남은_행들의_값이_부딪히면_저장된_값은_그대로여도_그_속성이_바뀐_것으로_남는다() {
        PackDefinition pack = pack("d-items-conflict", shared());
        // 앞 판의 값을 넣던 행이 새 판에도 있으면 변동이 없다 (위의 d-owner 시험). 이 시험은 그 행이 빠진 경우다.
        // 이름은 1번 행만 넣었다. 2번과 3번 행은 같은 개체를 가리키되 이름을 적지 않았다.
        put(pack, 1, D1, "line,other,otherName\n1,O-1,상대\n2,O-1,\n3,O-1,\n");

        // 1번 행이 빠지고, 2번과 3번 행이 서로 다른 이름을 적어 부딪힌다. 어느 것도 고르지 않는다 (D2 AD-146).
        IngestResult second = put(pack, 2, D2, "line,other,otherName\n2,O-1,둘째 이름\n3,O-1,셋째 이름\n");

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        // 부딪혔다고 알린다.
        assertThat(records.notices("d-items-conflict|src|2")).extracting(IngestNotice::code)
            .containsExactly("CONFLICTING_VALUES", "CONFLICTING_VALUES");
        // 저장된 이름은 앞 판의 것 그대로다.
        assertThat(values.valueAsOf("d-items-conflict|Other|O-1", "otherName", D2).map(AttributeValue::value))
            .contains("상대");
        // 그래도 "그 행에서 바뀐 것"은 행이 적은 것을 기준으로 남는다 (D2 AD-305): 부딪힌 두 행은 변경이고 이름이 바뀐 것이다.
        assertThat(records.changeEvents("d-items-conflict|src|2"))
            .extracting(ChangeEvent::itemKey, ChangeEvent::kind, ChangeEvent::changedAttributes)
            .containsExactly(tuple("1", ChangeKind.REMOVED, List.of("otherName")),
                    tuple("2", ChangeKind.CHANGED, List.of("otherName")),
                    tuple("3", ChangeKind.CHANGED, List.of("otherName")));
    }

    @Test
    void 바뀐_것은_어느_조회로_읽어도_같고_다시_읽어도_같다() {
        PackDefinition pack = pack("d-items-read", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\n");
        put(pack, 2, D2, HEADER + "B-1,첫째 고침,2,O-2\n");
        ChangeEvents events = new ChangeEvents(LinkTestDatabase.dataSource());

        ChangeEvent listed = records.changeEvents("d-items-read|src|2").get(0);

        assertThat(listed.changedAttributes()).containsExactly("count", "title");
        assertThat(listed.changedRelationTypes()).containsExactly("links");
        assertThat(events.find(listed.id())).contains(listed);
        assertThat(events.unprocessed("d-items-read")).containsExactly(listed);
        assertThat(records.changeEvents("d-items-read|src|2")).containsExactly(listed);
    }

    @Test
    void 바뀐_것을_남기기_전에_만들어진_변동_이벤트는_바뀐_것을_모른다고_답한다() {
        PackDefinition pack = pack("d-items-old", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\n");
        put(pack, 2, D2, HEADER + "B-1,첫째 고침,1,O-1\n");
        String eventId = records.changeEvents("d-items-old|src|2").get(0).id();
        // 스키마 변경 전에 만들어진 이벤트의 모양으로 되돌린다: 바뀐 것의 행이 없고 남겼다는 표시가 없다.
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                LinkTestDatabase.dataSource());
        jdbc.update("delete from change_event_changed_item where change_event_id = ?", eventId);
        jdbc.update("update change_event set changed_items_recorded = false where id = ?", eventId);

        ChangeEvent old = records.changeEvents("d-items-old|src|2").get(0);

        // 바뀐 것이 없다(빈 목록)가 아니라 모른다(null)이다.
        assertThat(old.changedAttributes()).isNull();
        assertThat(old.changedRelationTypes()).isNull();
        assertThat(new ChangeEvents(LinkTestDatabase.dataSource()).find(eventId)).contains(old);
    }

    @Test
    void 이름에_세로_막대와_역빗금이_있어도_바뀐_것의_이름을_그대로_남긴다() {
        MappedEntityRef base = new MappedEntityRef("Base", "code");
        MappingDefinition mapping = new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(),
                List.of("code"), base,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("a|b\\c", "title", null, null, null, null, null, null)))),
                List.of(new MappedRelation("r\\|s", base, new MappedEntityRef("Other", "other"), null, null)));
        PackDefinition pack = new PackDefinition("d-items-esc", "이름표", 3, AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null)),
                List.of(new RelationTypeDefinition("r\\|s", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(new AttributeDefinition("a|b\\c", "제목", "Base", ValueFormat.STRING)), List.of(), List.of(mapping));
        put(pack, 1, D1, HEADER + "B|1,첫째,1,O-1\n");

        put(pack, 2, D2, HEADER + "B|1,첫째 고침,1,O\\2\n");

        assertThat(records.changeEvents("d-items-esc|src|2"))
            .extracting(ChangeEvent::changedAttributes, ChangeEvent::changedRelationTypes)
            .containsExactly(tuple(List.of("a|b\\c"), List.of("r\\|s")));
    }

    // ── 바뀐 속성의 이전 값과 새 값 (C-07, D2 AD-367) ─────────────────────

    @Test
    void 변경된_행은_바뀐_속성마다_이전_값과_새_값을_남긴다() {
        PackDefinition pack = pack("d-values", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-2\nB-3,셋째,3,O-1\n");

        // B-1 은 제목과 수가, B-2 는 잇는 곳만 바뀐다. B-3 은 그대로다.
        put(pack, 2, D2, HEADER + "B-1,첫째 고침,10,O-1\nB-2,둘째,2,O-1\nB-3,셋째,3,O-1\n");

        assertThat(records.changeEvents("d-values|src|2")).extracting(ChangeEvent::itemKey, ChangeEvent::changedValues)
            .containsExactly(
                    // 속성의 이름의 글자 코드 순이다.
                    tuple("B-1", List.of(new ValueChange("d-values|Base|B-1", "count", "1", "10"),
                            new ValueChange("d-values|Base|B-1", "title", "첫째", "첫째 고침"))),
                    // 관계만 바뀐 행은 바뀐 값이 없다 (빈 목록이지 모른다가 아니다).
                    tuple("B-2", List.of()));
    }

    @Test
    void 추가된_행은_이전_값이_없고_삭제된_행은_새_값이_없다() {
        PackDefinition pack = pack("d-values-all", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\n");

        put(pack, 2, D2, HEADER + "B-2,둘째,,O-1\n");

        assertThat(records.changeEvents("d-values-all|src|2"))
            .extracting(ChangeEvent::itemKey, ChangeEvent::kind, ChangeEvent::changedValues)
            .containsExactly(
                    tuple("B-1", ChangeKind.REMOVED,
                            List.of(new ValueChange("d-values-all|Base|B-1", "count", "1", null),
                                    new ValueChange("d-values-all|Base|B-1", "title", "첫째", null))),
                    // 빈 칸은 넣은 것이 아니다.
                    tuple("B-2", ChangeKind.ADDED, List.of(new ValueChange("d-values-all|Base|B-2", "title", null, "둘째"))));
    }

    @Test
    void 빈_칸이_된_속성은_새_값이_없고_채워진_속성은_이전_값이_없다() {
        PackDefinition pack = pack("d-values-blank", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,,O-1\n");

        put(pack, 2, D2, HEADER + "B-1,첫째,,O-1\nB-2,둘째,7,O-1\n");

        assertThat(records.changeEvents("d-values-blank|src|2")).extracting(ChangeEvent::itemKey, ChangeEvent::changedValues)
            .containsExactly(tuple("B-1", List.of(new ValueChange("d-values-blank|Base|B-1", "count", "1", null))),
                    tuple("B-2", List.of(new ValueChange("d-values-blank|Base|B-2", "count", null, "7"))));
    }

    @Test
    void 값은_같고_유효기간만_달라진_속성은_이전_값과_새_값이_같다() {
        PackDefinition pack = pack("d-values-period", dated());
        put(pack, 1, D1, "code,title,from,to\nB-1,이름,2026-01-01,\n");

        put(pack, 2, D2, "code,title,from,to\nB-1,이름,2026-01-01,2026-12-01\n");

        assertThat(records.changeEvents("d-values-period|src|2").get(0).changedValues())
            .containsExactly(new ValueChange("d-values-period|Base|B-1", "title", "이름", "이름"));
    }

    @Test
    void 줄이_여럿이고_쉼표와_따옴표가_든_긴_글도_글자_그대로_견준다() {
        PackDefinition pack = pack("d-values-text", full());
        // 지어낸 조문이다.
        String before = "제3조 (점검) 담당자는 분기마다 한 번 점검한다.\n다만, \"가\" 등급은 반기마다 한다.";
        String after = "제3조 (점검) 담당자는 달마다 한 번 점검한다.\n다만, \"가\" 등급은 분기마다 한다.";
        put(pack, 1, D1, HEADER + "B-1," + quoted(before) + ",1,O-1\n");

        put(pack, 2, D2, HEADER + "B-1," + quoted(after) + ",1,O-1\n");

        assertThat(records.changeEvents("d-values-text|src|2").get(0).changedValues())
            .containsExactly(new ValueChange("d-values-text|Base|B-1", "title", before, after));
    }

    @Test
    void 바뀌지_않고_이어진_값도_뒤의_판에서_바뀌면_이전_값으로_나온다() {
        PackDefinition pack = pack("d-values-carried", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\n");
        // 둘째 판에서는 수만 바뀐다. 제목은 첫 판의 것이 이어진다.
        put(pack, 2, D2, HEADER + "B-1,첫째,2,O-1\n");

        put(pack, 3, D3, HEADER + "B-1,첫째 고침,2,O-1\n");

        assertThat(records.changeEvents("d-values-carried|src|3").get(0).changedValues())
            .containsExactly(new ValueChange("d-values-carried|Base|B-1", "title", "첫째", "첫째 고침"));
    }

    @Test
    void 값을_남기기_전에_들어간_판과_견준_변동은_바뀐_값을_모른다고_답하고_그_뒤의_판부터는_안다() {
        PackDefinition pack = pack("d-values-old", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\n");
        // 스키마 변경 전에 들어간 판의 모양으로 되돌린다: 만든 것에 값이 적혀 있지 않다.
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                LinkTestDatabase.dataSource());
        jdbc.update("update edition_item_output set value = null where edition_id = ?", "d-values-old|src|1");

        // 제목만 바뀐다. 수는 그대로 이어진다.
        put(pack, 2, D2, HEADER + "B-1,첫째 고침,1,O-1\n");
        put(pack, 3, D3, HEADER + "B-1,첫째 고침,5,O-1\n");

        ChangeEvent second = records.changeEvents("d-values-old|src|2").get(0);
        // 바뀐 값이 없다(빈 목록)가 아니라 모른다(null)이다. 바뀐 속성의 이름은 안다.
        assertThat(second.changedValues()).isNull();
        assertThat(second.changedAttributes()).containsExactly("title");
        // 이어진 수의 값은 둘째 판의 행이 적은 것으로 남는다. 그래서 셋째 판에서는 안다.
        assertThat(records.changeEvents("d-values-old|src|3").get(0).changedValues())
            .containsExactly(new ValueChange("d-values-old|Base|B-1", "count", "1", "5"));
    }

    @Test
    void 바뀐_값을_남기기_전에_만들어진_변동_이벤트는_바뀐_값을_모른다고_답한다() {
        PackDefinition pack = pack("d-values-old-event", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\n");
        put(pack, 2, D2, HEADER + "B-1,첫째 고침,1,O-1\n");
        String eventId = records.changeEvents("d-values-old-event|src|2").get(0).id();
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                LinkTestDatabase.dataSource());
        jdbc.update("delete from change_event_changed_value where change_event_id = ?", eventId);
        jdbc.update("update change_event set changed_values_recorded = false where id = ?", eventId);

        assertThat(records.changeEvents("d-values-old-event|src|2").get(0).changedValues()).isNull();
    }

    @Test
    void 바뀐_값은_어느_조회로_읽어도_같고_같은_자료를_다른_Pack에_넣어도_같다() {
        String first = HEADER + "B-1,첫째,1,O-1\nB-2,둘째,2,O-2\n";
        String second = HEADER + "B-1,첫째 고침,3,O-2\nB-3,셋째,,O-1\n";
        ChangeEvents events = new ChangeEvents(LinkTestDatabase.dataSource());
        List<List<String>> shapes = new ArrayList<>();
        for (String packId : List.of("d-values-read-1", "d-values-read-2")) {
            PackDefinition pack = pack(packId, full());
            put(pack, 1, D1, first);
            put(pack, 2, D2, second);

            List<ChangeEvent> listed = records.changeEvents(packId + "|src|2");

            assertThat(listed).extracting(ChangeEvent::itemKey).containsExactly("B-1", "B-2", "B-3");
            for (ChangeEvent one : listed) {
                assertThat(events.find(one.id())).contains(one);
            }
            assertThat(events.unprocessed(packId)).containsExactlyElementsOf(listed);
            assertThat(records.changeEvents(packId + "|src|2")).containsExactlyElementsOf(listed);
            shapes.add(listed.stream()
                .map(one -> one.itemKey() + " " + one.changedValues().toString().replace(packId + "|", ""))
                .toList());
        }
        assertThat(shapes.get(0)).isEqualTo(shapes.get(1));
    }

    @Test
    void 값이_부딪혀_넣지_않은_속성은_적은_값이_아니라_그대로_남은_앞_판의_값을_남긴다() {
        PackDefinition pack = pack("d-values-conflict", shared());
        put(pack, 1, D1, "line,other,otherName\n1,O-1,상대\n2,O-1,\n3,O-1,\n");

        // 1번 행이 빠지고, 2번과 3번 행이 서로 다른 이름을 적어 부딪힌다. 어느 것도 넣지 않는다 (D2 AD-146).
        put(pack, 2, D2, "line,other,otherName\n2,O-1,둘째 이름\n3,O-1,셋째 이름\n");

        assertThat(records.changeEvents("d-values-conflict|src|2"))
            .extracting(ChangeEvent::itemKey, ChangeEvent::changedAttributes, ChangeEvent::changedValues)
            .containsExactly(
                    tuple("1", List.of("otherName"),
                            List.of(new ValueChange("d-values-conflict|Other|O-1", "otherName", "상대", null))),
                    // 부딪힌 행이 적은 "둘째 이름", "셋째 이름"은 들어가지 않았다. 그 행들은 그대로 남은 앞 판의 값을
                    // 이어 적는다 (D2 AD-146). 바뀐 값은 그 행이 만든 것(이어 적은 것 포함)을 기준으로 남는다.
                    tuple("2", List.of("otherName"),
                            List.of(new ValueChange("d-values-conflict|Other|O-1", "otherName", null, "상대"))),
                    tuple("3", List.of("otherName"),
                            List.of(new ValueChange("d-values-conflict|Other|O-1", "otherName", null, "상대"))));
    }

    @Test
    void 키에_세로_막대와_역빗금이_있어도_바뀐_값의_개체를_바르게_가리킨다() {
        PackDefinition pack = pack("d-values-bar", full());
        String key = "B|1\\끝\\";
        put(pack, 1, D1, HEADER + key + ",첫째,1,O-1\n");

        put(pack, 2, D2, HEADER + key + ",첫째 고침,1,O-1\n");

        // 개체의 식별자는 core 가 만드는 것과 같아야 한다.
        String entityId = new EntityRef("d-values-bar", "Base", key).id();
        assertThat(values.valueAsOf(entityId, "title", D2).map(AttributeValue::value)).contains("첫째 고침");
        assertThat(records.changeEvents("d-values-bar|src|2").get(0).changedValues())
            .containsExactly(new ValueChange(entityId, "title", "첫째", "첫째 고침"));
    }

    @Test
    void 머리글만_있는_판을_올리면_모든_행이_삭제되고_넣었던_값이_이전_값으로_남는다() {
        PackDefinition pack = pack("d-values-empty", full());
        put(pack, 1, D1, HEADER + "B-1,첫째,1,O-1\nB-2,둘째,,O-1\n");

        IngestResult second = put(pack, 2, D2, HEADER);

        assertThat(second.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(records.changeEvents("d-values-empty|src|2"))
            .extracting(ChangeEvent::itemKey, ChangeEvent::kind, ChangeEvent::changedValues)
            .containsExactly(
                    tuple("B-1", ChangeKind.REMOVED,
                            List.of(new ValueChange("d-values-empty|Base|B-1", "count", "1", null),
                                    new ValueChange("d-values-empty|Base|B-1", "title", "첫째", null))),
                    tuple("B-2", ChangeKind.REMOVED,
                            List.of(new ValueChange("d-values-empty|Base|B-2", "title", "둘째", null))));
    }

    @Test
    void 한_행이_같은_속성의_값을_기간을_달리해_둘_넣으면_시작일의_차례로_짝짓고_짝이_없는_쪽은_비운다() {
        PackDefinition pack = pack("d-values-two", twoPeriods());
        // 첫 판은 제목 하나만 적는다.
        put(pack, 1, D1, "code,t1,from1,to1,t2,from2,to2\nB-1,가,2026-01-01,,,,\n");

        // 둘째 판은 그 제목을 2026-08-01 에 끝내고 그날부터의 제목을 하나 더 적는다.
        put(pack, 2, D2, "code,t1,from1,to1,t2,from2,to2\nB-1,가,2026-01-01,2026-08-01,나,2026-08-01,\n");

        assertThat(records.changeEvents("d-values-two|src|2").get(0).changedValues()).containsExactly(
                new ValueChange("d-values-two|Base|B-1", "title", "가", "가"),
                new ValueChange("d-values-two|Base|B-1", "title", null, "나"));
        // 셋째 판은 뒤의 제목만 뺀다: 앞의 것은 그대로 이어지고 뒤의 것만 없어진다.
        put(pack, 3, D3, "code,t1,from1,to1,t2,from2,to2\nB-1,가,2026-01-01,2026-08-01,,,\n");

        assertThat(records.changeEvents("d-values-two|src|3").get(0).changedValues())
            .containsExactly(new ValueChange("d-values-two|Base|B-1", "title", "나", null));
    }

    /** CSV 의 칸 하나로 적는다: 따옴표로 싸고 안의 따옴표는 둘로 적는다. */
    private static String quoted(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private IngestResult put(PackDefinition pack, int editionNo, LocalDate effectiveDate, String csv) {
        return ingest.ingest(pack, "src", "판 " + editionNo + ".csv", csv.getBytes(StandardCharsets.UTF_8), effectiveDate,
                AT.plusSeconds(editionNo));
    }

    private String titleAt(String entityId, LocalDate asOf) {
        return values.valueAsOf(entityId, "title", asOf).map(AttributeValue::value).orElse(null);
    }

    private Map<String, List<ItemOutput>> outputsOf(String editionId) {
        Map<String, List<ItemOutput>> outputs = new TreeMap<>();
        records.items(editionId).forEach(item -> outputs.put(item.itemKey(), item.outputs()));
        return outputs;
    }

    private Map<String, String> hashesOf(String editionId) {
        Map<String, String> hashes = new TreeMap<>();
        records.items(editionId).forEach(item -> hashes.put(item.itemKey(), item.contentHash()));
        return hashes;
    }

    /** 변동의 모양: Pack 의 식별자를 뺀 것. 서로 다른 Pack 에 넣은 결과를 견줄 때 쓴다. */
    private static List<String> shape(List<ChangeEvent> events, String packId) {
        return events.stream()
            .map(e -> (e.itemKey() + " " + e.kind() + " " + e.primaryEntityId() + " " + e.targetEntityIds())
                .replace(packId + "|", ""))
            .toList();
    }

    private static Map<String, List<String>> strip(Map<String, List<ItemOutput>> outputs, String packId) {
        Map<String, List<String>> stripped = new TreeMap<>();
        outputs.forEach((key, list) -> stripped.put(key,
                list.stream().map(o -> o.kind() + " " + o.objectId().replace(packId + "|", "")).toList()));
        return stripped;
    }

    /** 유형 Base 의 개체에 제목과 수를 넣고 Other 로 잇는 매핑. 열: code, title, count, other. */
    private static MappingDefinition full() {
        return new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(), List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", null, null, null, null, null, null),
                                new MappedAttribute("count", "count", null, null, null, null, null, null)))),
                List.of(new MappedRelation("links", BASE_BY_CODE, new MappedEntityRef("Other", "other"), null, null)));
    }

    /** 여러 행이 같은 Other 개체를 가리키며 그 이름을 적는 매핑. 열: line, other, otherName. */
    private static MappingDefinition shared() {
        MappedEntityRef other = new MappedEntityRef("Other", "other");
        return new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(), List.of("line"), other,
                List.of(new MappedEntity("Other", "other",
                        List.of(new MappedAttribute("otherName", "otherName", null, null, null, null, null, null)))),
                List.of());
    }

    /** 제목을 기간을 달리해 둘 적을 수 있는 매핑. 열: code, t1, from1, to1, t2, from2, to2. */
    private static MappingDefinition twoPeriods() {
        return new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(), List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "t1", "from1", "to1", null, null, null, null),
                                new MappedAttribute("title", "t2", "from2", "to2", null, null, null, null)))),
                List.of());
    }

    /** 제목은 유효기간을 열에서 읽고 수는 읽지 않는 매핑. 열: code, title, from, to, count. */
    private static MappingDefinition datedWithCount() {
        return new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(), List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", "from", "to", null, null, null, null),
                                new MappedAttribute("count", "count", null, null, null, null, null, null)))),
                List.of());
    }

    /** 제목의 유효기간을 열에서 읽는 매핑. 열: code, title, from, to. */
    private static MappingDefinition dated() {
        return new MappingDefinition("src", MappingFileFormat.CSV, null, null, List.of(), List.of("code"), BASE_BY_CODE,
                List.of(new MappedEntity("Base", "code",
                        List.of(new MappedAttribute("title", "title", "from", "to", null, null, null, null)))),
                List.of());
    }

    private static PackDefinition pack(String packId, MappingDefinition mapping) {
        return new PackDefinition(packId, "이름표", 3, AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null),
                        new EntityTypeDefinition("Other", "다른 것", List.of(), null)),
                List.of(new RelationTypeDefinition("links", "잇는다", "Base", "Other", PackImpactDirection.SOURCE_TO_TARGET)),
                List.of(new AttributeDefinition("count", "수", "Base", ValueFormat.INTEGER),
                        new AttributeDefinition("otherName", "다른 이름", "Other", ValueFormat.STRING),
                        new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING)),
                List.of(), List.of(mapping));
    }
}
