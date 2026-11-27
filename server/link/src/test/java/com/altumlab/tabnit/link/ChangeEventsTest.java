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
import java.util.ArrayList;
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

    // ── Pack 의 변동을 나눠 읽기 (카드 C-15, D2 AD-417) ───────────────────────

    @Test
    void Pack_의_변동_전부를_가장_최근에_받은_판부터_그_안에서는_항목의_키_순으로_나눠_읽는다() {
        PackDefinition pack = pack("e-list", "alpha", "zeta");
        put(pack, "alpha", D1, AT, "code,title\nB-1,하나\nB-2,둘\n");
        put(pack, "zeta", D1, AT, "code,title\nZ-1,하나\n");
        put(pack, "zeta", D2, AT.plusSeconds(10), "code,title\nZ-1,고침\nZ-0,새 행\n");
        put(pack, "alpha", D2, AT.plusSeconds(20), "code,title\nB-2,고침\nB-1,고침\n");

        ChangeEventPage page = events.packChangeEvents("e-list", null, 0, 50);

        // 첫 판은 변동을 만들지 않는다. 나중에 받은 alpha 의 둘째 판이 먼저다.
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.events()).extracting(ChangeEventSummary::editionId, ChangeEventSummary::itemKey)
            .containsExactly(tuple("e-list|alpha|2", "B-1"), tuple("e-list|alpha|2", "B-2"), tuple("e-list|zeta|2", "Z-0"),
                    tuple("e-list|zeta|2", "Z-1"));
        // 줄마다 그 판과 변동의 내용이 담긴다. 처리되지 않은 변동을 읽은 것과 같은 값이다.
        ChangeEventSummary first = page.events().get(0);
        ChangeEvent same = events.find(first.id()).orElseThrow();
        assertThat(first.inputSourceId()).isEqualTo("alpha");
        assertThat(first.editionNo()).isEqualTo(2);
        assertThat(first.effectiveDate()).isEqualTo(D2);
        assertThat(first.kind()).isEqualTo(same.kind());
        assertThat(first.primaryEntityId()).isEqualTo(same.primaryEntityId());
        assertThat(first.status()).isEqualTo(ChangeEventStatus.PENDING);
        assertThat(first.failedStage()).isNull();
        assertThat(page.events().get(2).kind()).isEqualTo(ChangeKind.ADDED);
        // 두 번 읽어도 같다. 다른 Pack 의 것은 섞이지 않고, 변동이 없는 Pack 은 비어 있다.
        assertThat(events.packChangeEvents("e-list", null, 0, 50)).isEqualTo(page);
        assertThat(events.packChangeEvents("e-list-none", null, 0, 50)).isEqualTo(new ChangeEventPage(List.of(), 0));
    }

    @Test
    void 상태로_거르고_나눠_받은_것을_이으면_한_번에_받은_것과_순서까지_같다() {
        PackDefinition pack = pack("e-list-page", "src");
        StringBuilder first = new StringBuilder("code,title\n");
        StringBuilder second = new StringBuilder("code,title\n");
        for (int i = 0; i < 25; i++) {
            first.append("K-").append(String.format("%02d", i)).append(",하나\n");
            second.append("K-").append(String.format("%02d", i)).append(",고침\n");
        }
        put(pack, "src", D1, AT, first.toString());
        put(pack, "src", D2, AT.plusSeconds(1), second.toString());
        List<ChangeEventSummary> whole = events.packChangeEvents("e-list-page", null, 0, 200).events();
        assertThat(whole).hasSize(25);
        // 셋은 처리됨, 둘은 실패로 표시한다.
        for (int i = 0; i < 3; i++) {
            events.markProcessed(whole.get(i).id());
        }
        events.markFailed(whole.get(10).id(), ChangeFailedStage.RULES, "RULE_FAILED");
        events.markFailed(whole.get(11).id(), ChangeFailedStage.IMPACT, "IMPACT_FAILED");

        assertThat(events.packChangeEvents("e-list-page", ChangeEventStatus.PROCESSED, 0, 50).events())
            .extracting(ChangeEventSummary::id)
            .containsExactly(whole.get(0).id(), whole.get(1).id(), whole.get(2).id());
        ChangeEventPage failed = events.packChangeEvents("e-list-page", ChangeEventStatus.FAILED, 0, 50);
        assertThat(failed.total()).isEqualTo(2);
        assertThat(failed.events()).extracting(ChangeEventSummary::failedStage, ChangeEventSummary::failureCode)
            .containsExactly(tuple("RULES", "RULE_FAILED"), tuple("IMPACT", "IMPACT_FAILED"));
        assertThat(events.packChangeEvents("e-list-page", ChangeEventStatus.PENDING, 0, 50).total()).isEqualTo(20);
        // 상태로 거른 채 나눠 받는다: 전체 수는 거른 수이고, 이으면 거른 것 전부다.
        List<String> pending = new ArrayList<>();
        for (int offset = 0; offset < 20; offset += 7) {
            ChangeEventPage page = events.packChangeEvents("e-list-page", ChangeEventStatus.PENDING, offset, 7);
            assertThat(page.total()).isEqualTo(20);
            page.events().forEach(event -> pending.add(event.id()));
        }
        assertThat(pending).containsExactlyElementsOf(whole.stream().map(ChangeEventSummary::id)
            .filter(id -> !List.of(whole.get(0).id(), whole.get(1).id(), whole.get(2).id(), whole.get(10).id(),
                    whole.get(11).id()).contains(id))
            .toList());

        List<String> joined = new ArrayList<>();
        for (int offset = 0; offset < 25; offset += 7) {
            ChangeEventPage page = events.packChangeEvents("e-list-page", null, offset, 7);
            assertThat(page.total()).isEqualTo(25);
            page.events().forEach(event -> joined.add(event.id()));
        }
        assertThat(joined).containsExactlyElementsOf(whole.stream().map(ChangeEventSummary::id).toList());
        // 끝을 넘으면 빈 목록이고 전체 수는 그대로다.
        assertThat(events.packChangeEvents("e-list-page", null, 25, 7)).isEqualTo(new ChangeEventPage(List.of(), 25));
    }

    @Test
    void 받은_때가_같은_판의_변동은_입력_자료의_식별자_순이고_다른_Pack_의_변동은_섞이지_않는다() {
        PackDefinition pack = pack("e-list-same", "alpha", "zeta");
        put(pack, "alpha", D1, AT, "code,title\na-1,하나\nB-1,하나\n");
        put(pack, "zeta", D1, AT, "code,title\nZ-1,하나\n");
        // 둘째 판 둘을 같은 때에 받는다. zeta 를 먼저 넣는다: 넣은 차례가 아니라 식별자의 순이어야 한다.
        put(pack, "zeta", D2, AT.plusSeconds(10), "code,title\nZ-1,고침\n");
        put(pack, "alpha", D2, AT.plusSeconds(10), "code,title\na-1,고침\nB-1,고침\n");
        // 다른 Pack 에도 변동을 만든다.
        PackDefinition other = pack("e-list-same-other", "alpha");
        put(other, "alpha", D1, AT, "code,title\nB-1,하나\n");
        put(other, "alpha", D2, AT.plusSeconds(10), "code,title\nB-1,고침\n");

        ChangeEventPage page = events.packChangeEvents("e-list-same", null, 0, 50);

        // 한 판 안에서는 항목의 키의 글자 코드 순이다: 대문자 B 가 소문자 a 보다 앞선다.
        assertThat(page.events()).extracting(ChangeEventSummary::editionId, ChangeEventSummary::itemKey)
            .containsExactly(tuple("e-list-same|alpha|2", "B-1"), tuple("e-list-same|alpha|2", "a-1"),
                    tuple("e-list-same|zeta|2", "Z-1"));
        assertThat(page.total()).isEqualTo(3);
        assertThat(events.packChangeEvents("e-list-same-other", null, 0, 50).events())
            .extracting(ChangeEventSummary::editionId)
            .containsExactly("e-list-same-other|alpha|2");
    }

    @Test
    void 받은_때가_같은_판의_변동은_판_번호가_큰_것부터_수로_견줘_나온다() {
        PackDefinition pack = pack("e-list-ten", "src");
        for (int no = 1; no <= 12; no++) {
            put(pack, "src", D1.plusDays(no), AT, "code,title\nB-1,값 " + no + "\n");
        }

        // 글자로 견주면 9번째 판이 12번째 판보다 뒤선다. 판 번호가 큰 것부터 나와야 한다.
        assertThat(events.packChangeEvents("e-list-ten", null, 0, 50).events()).extracting(ChangeEventSummary::editionNo)
            .containsExactly(12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2);
    }

    @Test
    void 나눠_읽는_값이_틀리면_거부한다() {
        assertThatThrownBy(() -> events.packChangeEvents(null, null, 0, 50)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> events.packChangeEvents("e-list-bad", null, -1, 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("건너뛸 수");
        assertThatThrownBy(() -> events.packChangeEvents("e-list-bad", null, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("한 번에 줄 수");
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

    // ── 실패의 까닭 (C-08, D2 AD-321, AD-345) ─────────────────────────

    @Test
    void 실패로_표시할_때_넘긴_까닭이_넘긴_차례로_남고_처리되지_않은_것의_까닭으로_읽힌다() {
        PackDefinition pack = twoEvents("e-why");
        List<ChangeFailureDetail> details = List.of(
                new ChangeFailureDetail("0002", "VALUE_FORMAT_MISMATCH", "e-why|Base|B-1", "title", "값"),
                new ChangeFailureDetail("0001", "DUE_DATE_OUT_OF_RANGE", null, null, null));

        events.markFailed("e-why|src|2|B-1", ChangeFailedStage.RULES, "RULE_FAILED", details);

        // 넘긴 차례 그대로다. 까닭이 없는 것(대기)은 들어 있지 않다.
        assertThat(events.unprocessedFailureDetails(pack.packId())).containsOnlyKeys("e-why|src|2|B-1")
            .containsEntry("e-why|src|2|B-1", details);
        // 두 번 읽어도 같다.
        assertThat(events.unprocessedFailureDetails(pack.packId()))
            .isEqualTo(events.unprocessedFailureDetails(pack.packId()));
        // 다른 Pack 의 것은 섞이지 않는다.
        assertThat(events.unprocessedFailureDetails("e-why-없는-pack")).isEmpty();
    }

    @Test
    void 다시_실패하면_까닭이_새것으로_바뀌고_처리되면_없어진다() {
        PackDefinition pack = twoEvents("e-why-again");
        ChangeFailureDetail first = new ChangeFailureDetail("0001", "FIRST", null, null, null);
        ChangeFailureDetail second = new ChangeFailureDetail("0009", "SECOND", "e", "a", "v");
        events.markFailed("e-why-again|src|2|B-1", ChangeFailedStage.RULES, "RULE_FAILED", List.of(first, first));
        events.markFailed("e-why-again|src|2|B-2", ChangeFailedStage.RULES, "RULE_FAILED", List.of(first));

        events.markFailed("e-why-again|src|2|B-1", ChangeFailedStage.RULES, "RULE_FAILED", List.of(second));
        events.markProcessed("e-why-again|src|2|B-2");

        assertThat(events.unprocessedFailureDetails(pack.packId())).containsOnlyKeys("e-why-again|src|2|B-1")
            .containsEntry("e-why-again|src|2|B-1", List.of(second));
        // 까닭 없이 다시 실패로 표시하면 앞의 까닭도 없어진다: 지금의 상태의 까닭만 남는다.
        events.markFailed("e-why-again|src|2|B-1", ChangeFailedStage.IMPACT, "STORAGE_FAILURE");
        assertThat(events.unprocessedFailureDetails(pack.packId())).isEmpty();
        Integer rows = new org.springframework.jdbc.core.JdbcTemplate(LinkTestDatabase.dataSource()).queryForObject(
                "select count(*) from change_event_failure where change_event_id like 'e-why-again|%'", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void 틀린_까닭은_받지_않고_표시도_바뀌지_않는다() {
        twoEvents("e-why-bad");

        assertThatThrownBy(() -> events.markFailed("e-why-bad|src|2|B-1", ChangeFailedStage.RULES, "RULE_FAILED", null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> events.markFailed("e-why-bad|src|2|B-1", ChangeFailedStage.RULES, "RULE_FAILED",
                List.of(new ChangeFailureDetail("", "CODE", null, null, null))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> events.markFailed("e-why-bad|src|2|B-1", ChangeFailedStage.RULES, "RULE_FAILED",
                List.of(new ChangeFailureDetail("0001", "CODE", null, null, "nul\u0000"))))
            .isInstanceOf(IllegalArgumentException.class);
        // 처리됨은 실패로 바꾸지 못하고 까닭도 남지 않는다.
        events.markProcessed("e-why-bad|src|2|B-2");
        assertThatThrownBy(() -> events.markFailed("e-why-bad|src|2|B-2", ChangeFailedStage.RULES, "RULE_FAILED",
                List.of(new ChangeFailureDetail("0001", "CODE", null, null, null))))
            .isInstanceOf(ChangeEventRejectedException.class);

        assertThat(events.find("e-why-bad|src|2|B-1")).hasValueSatisfying(
                e -> assertThat(e.status()).isEqualTo(ChangeEventStatus.PENDING));
        assertThat(events.unprocessedFailureDetails("e-why-bad")).isEmpty();
    }

    @Test
    void 까닭이_많아도_모두_차례대로_남는다() {
        PackDefinition pack = twoEvents("e-why-many");
        List<ChangeFailureDetail> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add(new ChangeFailureDetail(String.format("%04d", 300 - i), "CODE", "e" + i, "a", "v"));
        }

        events.markFailed("e-why-many|src|2|B-1", ChangeFailedStage.RULES, "RULE_FAILED", many);

        assertThat(events.unprocessedFailureDetails(pack.packId()).get("e-why-many|src|2|B-1")).isEqualTo(many);
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
