package com.altumlab.tabnit.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E-22 수용 기준 (common 쪽): 감사 기록을 누가, 언제, 무엇으로 걸러 나눠 읽는다. 순서는 일련번호의 내림차순이다
 * (D2 AD-414).
 *
 * <p>시험들이 한 저장소를 함께 쓴다. 시험마다 다른 사람의 이름을 써서 그 사람의 줄만 걸러 본다.
 * 모든 이름과 값은 지어낸 것이다.
 */
class AuditLogPageTest {

    private static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");

    private final AuditLog log = new AuditLog(CommonTestDatabase.dataSource());

    @Test
    void 누가로_거르면_그_사람의_줄만_가장_최근_것부터_나온다() {
        long first = entry("page-who", "listPacks", AuditOutcome.STARTED, T0);
        long second = entry("page-who", "listPacks", AuditOutcome.SUCCESS, T0.plusSeconds(1));
        entry("page-who-다른-사람", "listPacks", AuditOutcome.SUCCESS, T0.plusSeconds(2));
        long third = entry("page-who", "getAction", AuditOutcome.FAILED, T0.plusSeconds(3));

        AuditPage page = log.page(new AuditFilter("page-who", null, null, null, null), 0, 50);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.records()).extracting(AuditRecord::id).containsExactly(third, second, first);
        // 두 번 읽어도 같다.
        assertThat(log.page(new AuditFilter("page-who", null, null, null, null), 0, 50)).isEqualTo(page);
        // 이름의 일부로는 찾지 않는다.
        assertThat(log.page(new AuditFilter("page-wh", null, null, null, null), 0, 50).total()).isZero();
    }

    @Test
    void 거르는_값은_남길_때와_같은_법으로_다듬어_견준다() {
        // 남길 때 제어 문자는 빈칸으로 바꾼다. 거르는 값도 그렇게 바꿔야 그 줄을 찾는다.
        long tabbed = entry("page-clean\t이름", "page-clean\n기능", AuditOutcome.SUCCESS, T0);

        assertThat(ids(new AuditFilter("page-clean\t이름", null, null, null, null))).containsExactly(tabbed);
        assertThat(ids(new AuditFilter(null, "page-clean\n기능", null, null, null))).containsExactly(tabbed);
        // 저장된 꼴(빈칸)로 물어도 같다.
        assertThat(ids(new AuditFilter("page-clean 이름", null, null, null, null))).containsExactly(tabbed);
    }

    @Test
    void 일련번호의_위쪽_끝을_주면_그_뒤에_남긴_줄은_수에도_목록에도_없다() {
        // 쪽을 넘기는 사이에 줄이 늘어도 목록이 밀리지 않게 한다 (D2 AD-424).
        long first = entry("page-max", "listPacks", AuditOutcome.SUCCESS, T0);
        long second = entry("page-max", "listPacks", AuditOutcome.SUCCESS, T0.plusSeconds(1));
        long later = entry("page-max", "listPacks", AuditOutcome.SUCCESS, T0.plusSeconds(2));

        AuditPage bounded = log.page(new AuditFilter("page-max", null, null, null, null, second), 0, 50);
        assertThat(bounded.total()).isEqualTo(2);
        assertThat(bounded.records()).extracting(AuditRecord::id).containsExactly(second, first);
        // 끝은 포함한다. 주지 않으면 모두다.
        assertThat(ids(new AuditFilter("page-max", null, null, null, null, later))).containsExactly(later, second, first);
        assertThat(ids(new AuditFilter("page-max", null, null, null, null))).containsExactly(later, second, first);
        // 1 보다 작은 끝은 틀린 인자다.
        assertThatThrownBy(() -> new AuditFilter(null, null, null, null, null, 0L)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("일련번호");
    }

    @Test
    void 무엇을과_결과로_거른다_조건을_함께_주면_모두_맞는_줄이다() {
        String who = "page-what";
        long a = entry(who, "listPacks", AuditOutcome.SUCCESS, T0);
        long b = entry(who, "getAction", AuditOutcome.SUCCESS, T0.plusSeconds(1));
        long c = entry(who, "getAction", AuditOutcome.DENIED, T0.plusSeconds(2));

        assertThat(ids(new AuditFilter(who, "getAction", null, null, null))).containsExactly(c, b);
        assertThat(ids(new AuditFilter(who, null, AuditOutcome.SUCCESS, null, null))).containsExactly(b, a);
        assertThat(ids(new AuditFilter(who, "getAction", AuditOutcome.SUCCESS, null, null))).containsExactly(b);
        assertThat(ids(new AuditFilter(who, "listPacks", AuditOutcome.DENIED, null, null))).isEmpty();
        // 기능의 이름은 글자 그대로 견준다.
        assertThat(ids(new AuditFilter(who, "getaction", null, null, null))).isEmpty();
    }

    @Test
    void 언제로_거른다_시작은_포함하고_끝은_제외한다() {
        String who = "page-when";
        long before = entry(who, "listPacks", AuditOutcome.SUCCESS, T0.minusMillis(1));
        long atStart = entry(who, "listPacks", AuditOutcome.SUCCESS, T0);
        long inside = entry(who, "listPacks", AuditOutcome.SUCCESS, T0.plusSeconds(30));
        long atEnd = entry(who, "listPacks", AuditOutcome.SUCCESS, T0.plusSeconds(60));

        assertThat(ids(new AuditFilter(who, null, null, T0, T0.plusSeconds(60)))).containsExactly(inside, atStart);
        assertThat(ids(new AuditFilter(who, null, null, T0, null))).containsExactly(atEnd, inside, atStart);
        assertThat(ids(new AuditFilter(who, null, null, null, T0))).containsExactly(before);
        // 시작과 끝이 같으면 아무것도 없다.
        assertThat(ids(new AuditFilter(who, null, null, T0, T0))).isEmpty();
    }

    @Test
    void 나눠_받은_것을_이으면_한_번에_받은_것과_순서까지_같다() {
        String who = "page-many";
        List<Long> written = new ArrayList<>();
        for (int i = 0; i < 230; i++) {
            written.add(entry(who, i % 2 == 0 ? "listPacks" : "getAction", AuditOutcome.SUCCESS, T0.plusSeconds(i)));
        }
        List<Long> newestFirst = new ArrayList<>(written);
        java.util.Collections.reverse(newestFirst);
        AuditFilter filter = new AuditFilter(who, null, null, null, null);

        List<Long> joined = new ArrayList<>();
        for (int offset = 0; offset < 230; offset += 100) {
            AuditPage page = log.page(filter, offset, 100);
            assertThat(page.total()).isEqualTo(230);
            page.records().forEach(record -> joined.add(record.id()));
        }

        assertThat(joined).containsExactlyElementsOf(newestFirst);
        AuditPage beyond = log.page(filter, 230, 100);
        assertThat(beyond.records()).isEmpty();
        assertThat(beyond.total()).isEqualTo(230);
        assertThat(log.page(filter, 229, 100).records()).extracting(AuditRecord::id).containsExactly(written.get(0));
    }

    @Test
    void 거르지_않으면_모든_줄이고_줄의_내용은_남긴_그대로다() {
        long mark = log.lastId();
        long started = log.record(new AuditEntry(T0, "page-all", "decideAction", "actionId=a-1", null, AuditOutcome.STARTED,
                null, null, null));
        long finished = log.record(new AuditEntry(T0.plusMillis(250), "page-all", "decideAction", "actionId=a-1",
                java.time.LocalDate.parse("2026-03-01"), AuditOutcome.SUCCESS, 200, "decision=CONFIRM", started));

        AuditPage page = log.page(AuditFilter.NONE, 0, 2);

        // 다른 시험이 그 사이에 남겼을 수 있다: 전체 수는 적어도 그만큼이다.
        assertThat(page.total()).isGreaterThanOrEqualTo((int) (finished - mark));
        AuditRecord row = log.page(new AuditFilter("page-all", null, AuditOutcome.SUCCESS, null, null), 0, 1).records().get(0);
        assertThat(row).isEqualTo(new AuditRecord(finished, T0.plusMillis(250), "page-all", "decideAction", "actionId=a-1",
                java.time.LocalDate.parse("2026-03-01"), AuditOutcome.SUCCESS, 200, "decision=CONFIRM", started));
    }

    @Test
    void 읽는_일은_줄을_더하지_않는다() {
        entry("page-readonly", "listPacks", AuditOutcome.SUCCESS, T0);
        AuditFilter filter = new AuditFilter("page-readonly", null, null, null, null);

        log.page(filter, 0, 50);
        log.page(filter, 0, 50);

        assertThat(log.page(filter, 0, 50).total()).isEqualTo(1);
    }

    @Test
    void 틀린_인자는_무엇이_틀렸는지_알리며_거부한다() {
        assertThatThrownBy(() -> log.page(null, 0, 50)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> log.page(AuditFilter.NONE, -1, 50)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("건너뛸 수");
        assertThatThrownBy(() -> log.page(AuditFilter.NONE, 0, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("한 번에 받을 수");
        assertThatThrownBy(() -> new AuditFilter("", null, null, null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("누가");
        assertThatThrownBy(() -> new AuditFilter(null, "", null, null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("무엇을");
        assertThatThrownBy(() -> new AuditFilter("가\u0000", null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditFilter(null, null, null, T0.plusSeconds(1), T0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("끝");
    }

    private long entry(String actor, String action, AuditOutcome outcome, Instant at) {
        return log.record(new AuditEntry(at, actor, action, null, null, outcome,
                outcome == AuditOutcome.STARTED ? null : 200, null, null));
    }

    private List<Long> ids(AuditFilter filter) {
        return log.page(filter, 0, 200).records().stream().map(AuditRecord::id).toList();
    }
}
