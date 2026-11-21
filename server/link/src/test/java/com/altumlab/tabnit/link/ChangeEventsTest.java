package com.altumlab.tabnit.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.MappedAttribute;
import com.altumlab.tabnit.pack.MappedEntity;
import com.altumlab.tabnit.pack.MappedEntityRef;
import com.altumlab.tabnit.pack.MappingDefinition;
import com.altumlab.tabnit.pack.MappingFileFormat;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.ValueFormat;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * C-06 가운데 link 가 맡는 것: 변동 이벤트를 읽고 뒤 단계의 성공과 실패를 표시하는 공개 API (D2 AD-44, AD-154, AD-205).
 * 영향 범위 계산과 규칙 실행은 link 가 하지 않는다. api 가 이 API 로 읽고 표시한다.
 *
 * <p>지어낸 이름의 매핑과 자료로 판을 둘 넣어 변동 이벤트를 만든 뒤 본다. 시험마다 다른 Pack 식별자를 쓴다.
 */
class ChangeEventsTest {

    private static final Instant AT = Instant.parse("2026-03-02T01:00:00Z");

    private static final LocalDate D1 = LocalDate.parse("2026-03-01");

    private static final LocalDate D2 = LocalDate.parse("2026-06-01");

    private final EditionIngest ingest = new EditionIngest(LinkTestDatabase.dataSource());

    private final EditionRecords records = new EditionRecords(LinkTestDatabase.dataSource());

    private final ChangeEvents events = new ChangeEvents(LinkTestDatabase.dataSource());

    // ── 읽기 ───────────────────────────────────────────────────

    @Test
    void 처리되지_않은_변동은_판을_받은_차례로_그_안에서는_항목의_키_순으로_나온다() {
        PackDefinition pack = pack("e-order", "alpha", "zeta");
        // zeta 의 둘째 판을 alpha 의 둘째 판보다 먼저 받는다. 입력 자료의 이름 순이 아니라 받은 차례다.
        put(pack, "alpha", D1, AT, "code,title\nB-1,하나\nB-2,둘\n");
        put(pack, "zeta", D1, AT, "code,title\nZ-1,하나\n");
        put(pack, "zeta", D2, AT.plusSeconds(10), "code,title\nZ-1,고침\nZ-0,새 행\n");
        put(pack, "alpha", D2, AT.plusSeconds(20), "code,title\nB-2,고침\nB-1,고침\n");

        assertThat(events.unprocessed("e-order")).extracting(ChangeEvent::editionId, ChangeEvent::itemKey)
            .containsExactly(tuple("e-order|zeta|2", "Z-0"), tuple("e-order|zeta|2", "Z-1"),
                    tuple("e-order|alpha|2", "B-1"), tuple("e-order|alpha|2", "B-2"));
        // 변동 대상도 함께 온다.
        assertThat(events.unprocessed("e-order").get(0).targetEntityIds()).containsExactly("e-order|Base|Z-0");
        // 다른 Pack 의 것은 섞이지 않는다. 변동이 없는 Pack 은 비어 있다.
        assertThat(events.unprocessed("e-order-none")).isEmpty();
    }

    @Test
    void 받은_때가_같은_판은_판_번호를_수로_견준다() {
        PackDefinition pack = pack("e-ten", "src");
        // 한 입력 자료에 판을 열둘 넣는다. 받은 때는 모두 같다. 판마다 같은 행의 값이 바뀐다.
        for (int no = 1; no <= 12; no++) {
            put(pack, "src", D1.plusDays(no), AT, "code,title\nB-1,값 " + no + "\n");
        }

        // 글자로 견주면 10번째 판이 2번째 판보다 앞선다. 먼저 들어온 판의 변동이 먼저 나와야 한다.
        assertThat(events.unprocessed("e-ten")).extracting(ChangeEvent::editionId)
            .containsExactly("e-ten|src|2", "e-ten|src|3", "e-ten|src|4", "e-ten|src|5", "e-ten|src|6", "e-ten|src|7",
                    "e-ten|src|8", "e-ten|src|9", "e-ten|src|10", "e-ten|src|11", "e-ten|src|12");
    }

    @Test
    void 항목의_키에_구분_글자가_들어_있어도_하나씩_정확히_읽는다() {
        PackDefinition pack = pack("e-chars", "src");
        put(pack, "src", D1, AT, "code,title\n\"a|b\\c%d_e\",하나\n\"a|b\\c%dxe\",하나\na,하나\n%,하나\n");
        put(pack, "src", D2, AT.plusSeconds(1),
                "code,title\n\"a|b\\c%d_e\",고침\n\"a|b\\c%dxe\",고침\na,고침\n%,고침\n");

        List<ChangeEvent> pending = events.unprocessed("e-chars");
        assertThat(pending).hasSize(4);
        // 식별자로 하나를 읽으면 목록의 그것과 같다. 닮은 키와 섞이지 않는다.
        assertThat(pending).allSatisfy(e -> assertThat(events.find(e.id())).contains(e));
        events.markProcessed(pending.get(0).id());
        assertThat(events.unprocessed("e-chars")).containsExactlyElementsOf(pending.subList(1, 4));
    }

    @Test
    void 받은_때가_같은_판은_입력_자료의_식별자_순이다() {
        PackDefinition pack = pack("e-tie", "beta", "alpha");
        put(pack, "beta", D1, AT, "code,title\nB-1,하나\n");
        put(pack, "alpha", D1, AT, "code,title\nA-1,하나\n");
        put(pack, "beta", D2, AT.plusSeconds(5), "code,title\nB-1,고침\n");
        put(pack, "alpha", D2, AT.plusSeconds(5), "code,title\nA-1,고침\n");

        assertThat(events.unprocessed("e-tie")).extracting(ChangeEvent::editionId)
            .containsExactly("e-tie|alpha|2", "e-tie|beta|2");
    }

    @Test
    void 변동_이벤트_하나를_식별자로_읽는다() {
        PackDefinition pack = pack("e-find", "src");
        put(pack, "src", D1, AT, "code,title\nB-1,하나\n");
        put(pack, "src", D2, AT.plusSeconds(1), "code,title\nB-1,고침\n");

        assertThat(events.find("e-find|src|2|B-1")).hasValueSatisfying(e -> {
            assertThat(e.kind()).isEqualTo(ChangeKind.CHANGED);
            assertThat(e.status()).isEqualTo(ChangeEventStatus.PENDING);
            assertThat(e.primaryEntityId()).isEqualTo("e-find|Base|B-1");
            assertThat(e.targetEntityIds()).containsExactly("e-find|Base|B-1");
        });
        assertThat(events.find("e-find|src|2|없는 키")).isEmpty();
    }

    // ── 표시 ───────────────────────────────────────────────────

    @Test
    void 처리됨으로_표시하면_처리되지_않은_목록에서_빠지고_다른_이벤트는_그대로다() {
        PackDefinition pack = twoEvents("e-done");

        events.markProcessed("e-done|src|2|B-1");

        assertThat(events.find("e-done|src|2|B-1").orElseThrow().status()).isEqualTo(ChangeEventStatus.PROCESSED);
        assertThat(events.unprocessed(pack.packId())).extracting(ChangeEvent::itemKey, ChangeEvent::status)
            .containsExactly(tuple("B-2", ChangeEventStatus.PENDING));
        // 판의 변동을 읽는 조회에는 처리된 것도 나온다.
        assertThat(records.changeEvents("e-done|src|2")).extracting(ChangeEvent::status)
            .containsExactly(ChangeEventStatus.PROCESSED, ChangeEventStatus.PENDING);
    }

    @Test
    void 실패로_표시하면_어느_단계에서_왜_실패했는지가_남고_다시_실행할_것으로_나온다() {
        PackDefinition pack = twoEvents("e-fail");

        events.markFailed("e-fail|src|2|B-1", ChangeFailedStage.RULES, "RULE_STORAGE_FAILURE");

        assertThat(events.find("e-fail|src|2|B-1")).hasValueSatisfying(e -> {
            assertThat(e.status()).isEqualTo(ChangeEventStatus.FAILED);
            assertThat(e.failedStage()).isEqualTo("RULES");
            assertThat(e.failureCode()).isEqualTo("RULE_STORAGE_FAILURE");
        });
        // 실패한 것도 처리되지 않은 것이다. 차례는 그대로다.
        assertThat(events.unprocessed(pack.packId())).extracting(ChangeEvent::itemKey, ChangeEvent::status)
            .containsExactly(tuple("B-1", ChangeEventStatus.FAILED), tuple("B-2", ChangeEventStatus.PENDING));
    }

    @Test
    void 실패한_것은_다시_실행한_결과로_다시_표시할_수_있다() {
        twoEvents("e-retry");
        events.markFailed("e-retry|src|2|B-1", ChangeFailedStage.IMPACT, "FIRST");
        events.markFailed("e-retry|src|2|B-2", ChangeFailedStage.IMPACT, "FIRST");

        // 다시 실행해 또 실패: 단계와 이유가 새 것으로 바뀐다.
        events.markFailed("e-retry|src|2|B-1", ChangeFailedStage.RULES, "SECOND");
        // 다시 실행해 성공: 실패의 단계와 이유가 지워진다.
        events.markProcessed("e-retry|src|2|B-2");

        assertThat(events.find("e-retry|src|2|B-1")).hasValueSatisfying(e -> {
            assertThat(e.status()).isEqualTo(ChangeEventStatus.FAILED);
            assertThat(e.failedStage()).isEqualTo("RULES");
            assertThat(e.failureCode()).isEqualTo("SECOND");
        });
        assertThat(events.find("e-retry|src|2|B-2")).hasValueSatisfying(e -> {
            assertThat(e.status()).isEqualTo(ChangeEventStatus.PROCESSED);
            assertThat(e.failedStage()).isNull();
            assertThat(e.failureCode()).isNull();
        });
    }

    @Test
    void 처리됨은_끝이다_같은_표시는_그대로_두고_실패로는_바꾸지_못한다() {
        twoEvents("e-final");
        events.markProcessed("e-final|src|2|B-1");

        // 같은 표시를 다시 해도 결과가 같다.
        events.markProcessed("e-final|src|2|B-1");
        assertThatThrownBy(() -> events.markFailed("e-final|src|2|B-1", ChangeFailedStage.RULES, "LATE"))
            .isInstanceOf(ChangeEventRejectedException.class)
            .hasMessageContaining("e-final|src|2|B-1")
            .hasMessageContaining("PROCESSED");

        assertThat(events.find("e-final|src|2|B-1")).hasValueSatisfying(e -> {
            assertThat(e.status()).isEqualTo(ChangeEventStatus.PROCESSED);
            assertThat(e.failureCode()).isNull();
        });
    }

    @Test
    void 없는_변동_이벤트와_빈_이유는_받지_않는다() {
        twoEvents("e-bad");

        assertThatThrownBy(() -> events.markProcessed("e-bad|src|2|없는 키"))
            .isInstanceOf(ChangeEventRejectedException.class)
            .hasMessageContaining("없는 키");
        assertThatThrownBy(() -> events.markFailed("e-bad|src|9|B-1", ChangeFailedStage.IMPACT, "X"))
            .isInstanceOf(ChangeEventRejectedException.class);
        for (String code : new String[] { "", "   ", "A\u0000B", "\uD800", "A\uDC00", "\uD83D" }) {
            assertThatThrownBy(() -> events.markFailed("e-bad|src|2|B-1", ChangeFailedStage.IMPACT, code))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> events.markFailed("e-bad|src|2|B-1", null, "X"))
            .isInstanceOf(NullPointerException.class);
        // 짝이 맞는 대리 문자(보충 문자)와 긴 코드는 받는다. 글자 그대로 남는다.
        String longCode = "CODE_\uD83D\uDE00_" + "X".repeat(5000);
        events.markFailed("e-bad|src|2|B-2", ChangeFailedStage.IMPACT, longCode);
        assertThat(events.find("e-bad|src|2|B-2").orElseThrow().failureCode()).isEqualTo(longCode);
        // 틀린 표시는 아무것도 바꾸지 않았다.
        assertThat(events.find("e-bad|src|2|B-1").orElseThrow().status()).isEqualTo(ChangeEventStatus.PENDING);
    }

    @Test
    void 틀린_표시가_있어도_다른_이벤트는_대기_그대로다() {
        twoEvents("e-untouched");
        assertThatThrownBy(() -> events.markProcessed("e-untouched|src|2|없는 키"))
            .isInstanceOf(ChangeEventRejectedException.class);

        assertThat(events.unprocessed("e-untouched")).extracting(ChangeEvent::status)
            .containsExactly(ChangeEventStatus.PENDING, ChangeEventStatus.PENDING);
    }

    @Test
    void 변동이_많아도_차례가_지켜지고_하나씩_표시된다() {
        PackDefinition pack = pack("e-many", "src");
        StringBuilder first = new StringBuilder("code,title\n");
        StringBuilder second = new StringBuilder("code,title\n");
        int count = 600;
        for (int i = 0; i < count; i++) {
            int n = (i * 7) % count;
            first.append("B-").append(1000 + n).append(",이름\n");
            second.append("B-").append(1000 + n).append(",고친 이름\n");
        }
        put(pack, "src", D1, AT, first.toString());
        put(pack, "src", D2, AT.plusSeconds(1), second.toString());

        List<ChangeEvent> pending = events.unprocessed("e-many");
        assertThat(pending).hasSize(count);
        assertThat(pending.get(0).itemKey()).isEqualTo("B-1000");
        assertThat(pending.get(count - 1).itemKey()).isEqualTo("B-1599");
        for (int i = 0; i < count; i += 2) {
            events.markProcessed(pending.get(i).id());
        }
        assertThat(events.unprocessed("e-many")).hasSize(count / 2)
            .first()
            .satisfies(e -> assertThat(e.itemKey()).isEqualTo("B-1001"));
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 판을 둘 넣어 B-1 과 B-2 의 변경 이벤트 둘을 만든다. */
    private PackDefinition twoEvents(String packId) {
        PackDefinition pack = pack(packId, "src");
        put(pack, "src", D1, AT, "code,title\nB-1,하나\nB-2,둘\n");
        put(pack, "src", D2, AT.plusSeconds(1), "code,title\nB-1,고침\nB-2,고침\n");
        return pack;
    }

    private void put(PackDefinition pack, String source, LocalDate effectiveDate, Instant receivedAt, String csv) {
        IngestResult result = ingest.ingest(pack, source, "f.csv", csv.getBytes(StandardCharsets.UTF_8), effectiveDate,
                receivedAt);
        assertThat(result.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
    }

    /** 유형 Base 의 개체에 제목을 넣는 매핑을 입력 자료마다 하나씩 가진 Pack. 열: code, title. */
    private static PackDefinition pack(String packId, String... sources) {
        MappedEntityRef base = new MappedEntityRef("Base", "code");
        List<MappingDefinition> mappings = java.util.Arrays.stream(sources)
            .map(source -> new MappingDefinition(source, MappingFileFormat.CSV, null, null, List.of(), List.of("code"),
                    base,
                    List.of(new MappedEntity("Base", "code",
                            List.of(new MappedAttribute("title", "title", null, null, null, null, null, null)))),
                    List.of()))
            .toList();
        return new PackDefinition(packId, "이름표", 3, AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null)), List.of(),
                List.of(new AttributeDefinition("title", "제목", "Base", ValueFormat.STRING)), List.of(), mappings);
    }
}
