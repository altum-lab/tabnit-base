package com.altumlab.tabnit.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E-03 수용 기준: 누가 언제 무엇을 했는지 한 곳에 기록된다.
 *
 * <p>감사 기록은 더하기만 한다. 한 줄의 식별자는 데이터베이스가 매기는 일련번호다 (D2 AD-276).
 * 현재 시각을 읽지 않는다: 언제인지는 밖에서 받는다. 모든 이름과 값은 지어낸 것이다.
 */
class AuditLogTest {

    private static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");

    private final AuditLog log = new AuditLog(CommonTestDatabase.dataSource());

    private final JdbcTemplate jdbc = new JdbcTemplate(CommonTestDatabase.dataSource());

    @Test
    void 남긴_것이_그대로_한_줄로_저장되고_식별자는_남긴_차례대로_커진다() {
        long before = log.lastId();

        long started = log.record(new AuditEntry(T0, "aud-officer", "decideAction", "actionId=a-1", null,
                AuditOutcome.STARTED, null, null, null));
        long finished = log.record(new AuditEntry(T0.plusMillis(250), "aud-officer", "decideAction", "actionId=a-1", null,
                AuditOutcome.SUCCESS, 200, "decision=CONFIRM", started));
        long read = log.record(new AuditEntry(T0.plusSeconds(1), "aud-admin", "getRelationsAsOf", "entityId=e|T|a",
                LocalDate.parse("2026-03-01"), AuditOutcome.FAILED, 404, null, null));

        assertThat(started).isGreaterThan(before);
        assertThat(finished).isGreaterThan(started);
        assertThat(read).isGreaterThan(finished);
        assertThat(log.lastId()).isGreaterThanOrEqualTo(read);
        List<AuditRecord> rows = log.after(before).stream().filter(row -> row.actor() != null && row.actor().startsWith("aud-")).toList();
        assertThat(rows).containsExactly(
                new AuditRecord(started, T0, "aud-officer", "decideAction", "actionId=a-1", null, AuditOutcome.STARTED, null,
                        null, null),
                new AuditRecord(finished, T0.plusMillis(250), "aud-officer", "decideAction", "actionId=a-1", null,
                        AuditOutcome.SUCCESS, 200, "decision=CONFIRM", started),
                new AuditRecord(read, T0.plusSeconds(1), "aud-admin", "getRelationsAsOf", "entityId=e|T|a",
                        LocalDate.parse("2026-03-01"), AuditOutcome.FAILED, 404, null, null));
        // 그 뒤의 것만 읽는다.
        assertThat(log.after(read)).noneMatch(row -> row.id() <= read);
    }

    @Test
    void 누가인지_모르는_줄도_남고_언제와_무엇과_결과는_반드시_있어야_한다() {
        long id = log.record(new AuditEntry(T0, null, "login", null, null, AuditOutcome.DENIED, 401, null, null));

        AuditRecord row = log.after(id - 1).get(0);
        assertThat(row.actor()).isNull();
        assertThat(row.target()).isNull();
        assertThat(row.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThatThrownBy(() -> new AuditEntry(null, "a", "x", null, null, AuditOutcome.SUCCESS, 200, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditEntry(T0, "a", null, null, null, AuditOutcome.SUCCESS, 200, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditEntry(T0, "a", "x", null, null, null, 200, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditEntry(T0, "a", " ", null, null, AuditOutcome.SUCCESS, 200, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> log.record(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditLog(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 저장할_수_없는_글자와_너무_긴_값_때문에_기록이_실패하지_않는다() {
        // 요청에서 온 값은 무엇이든 들어온다. 그 때문에 기록을 못 하면 요청 전체가 실패한다.
        String longTarget = "가".repeat(5000);
        long id = log.record(new AuditEntry(T0, "aud-x\u0000y", "listActions", "packId=a\u0000b\nc\td" + longTarget, null,
                AuditOutcome.SUCCESS, 200, "x".repeat(9000), null));

        AuditRecord row = log.after(id - 1).get(0);
        assertThat(row.actor()).doesNotContain("\u0000").startsWith("aud-x");
        assertThat(row.target()).doesNotContain("\u0000").doesNotContain("\n").doesNotContain("\t").startsWith("packId=a");
        // 잘린 값은 잘렸다는 표시로 끝난다.
        assertThat(row.target().codePointCount(0, row.target().length())).isEqualTo(AuditLog.MAX_TARGET_LENGTH);
        assertThat(row.target()).endsWith("…");
        assertThat(row.detail().length()).isEqualTo(AuditLog.MAX_DETAIL_LENGTH);
        assertThat(row.detail()).endsWith("…");
        // 딱 한도인 값은 자르지 않는다.
        long exact = log.record(new AuditEntry(T0, "aud-exact", "x", "t".repeat(AuditLog.MAX_TARGET_LENGTH), null,
                AuditOutcome.SUCCESS, 200, null, null));
        assertThat(log.after(exact - 1).get(0).target()).isEqualTo("t".repeat(AuditLog.MAX_TARGET_LENGTH));
        // 짝 없는 대리 문자도 저장을 막지 않는다.
        long lone = log.record(new AuditEntry(T0, "aud-lone", "x", "a\uD83Db", null, AuditOutcome.SUCCESS, 200, null, null));
        assertThat(log.after(lone - 1).get(0).target()).startsWith("a").endsWith("b");
    }

    @Test
    void 결과의_줄은_있는_시작의_줄만_가리킬_수_있고_저장소가_값의_종류를_지킨다() {
        long missing = log.lastId() + 1_000_000;

        assertThatThrownBy(() -> log.record(
                new AuditEntry(T0, "aud-fk", "x", null, null, AuditOutcome.SUCCESS, 200, null, missing)))
            .isInstanceOf(AuditLogException.class);
        // 코드를 거치지 않고 틀린 결과를 넣을 수 없다.
        assertThatThrownBy(() -> jdbc.update(
                "insert into audit_log (occurred_at, action, outcome) values (timestamptz '2026-06-01T00:00:00Z', 'x', 'MAYBE')"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into audit_log (occurred_at, action, outcome) values (timestamptz '2026-06-01T00:00:00Z', '', 'SUCCESS')"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 한꺼번에_남겨도_빠지는_줄이_없다() throws Exception {
        long before = log.lastId();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Long>> ids = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int n = i;
                ids.add(pool.submit(() -> log.record(new AuditEntry(T0, "aud-many", "x", "n=" + n, null, AuditOutcome.SUCCESS,
                        200, null, null))));
            }
            java.util.Set<Long> distinct = new java.util.TreeSet<>();
            for (java.util.concurrent.Future<Long> id : ids) {
                distinct.add(id.get());
            }
            assertThat(distinct).hasSize(40);
        }
        finally {
            pool.shutdownNow();
        }
        List<AuditRecord> rows = log.after(before).stream().filter(row -> "aud-many".equals(row.actor())).toList();
        assertThat(rows).hasSize(40);
        // 읽은 것은 식별자 순이다.
        assertThat(rows).extracting(AuditRecord::id).isSorted();
    }

    // ── 데이터베이스가 지킨다: 고치거나 지울 수 없다 (E-18, D2 AD-276, AD-329) ──────────

    @Test
    void 남긴_줄은_데이터베이스에서_고치거나_지울_수_없고_더하는_것은_된다() {
        long id = log.record(new AuditEntry(T0, "guard-user", "guardAction", "t=1", null, AuditOutcome.STARTED, null,
                null, null));

        assertThatThrownBy(() -> jdbc.update("update audit_log set actor = '다른 사람' where id = ?", id))
            .isInstanceOf(org.springframework.dao.DataAccessException.class)
            .hasMessageContaining("audit_log");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log where id = ?", id))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("truncate audit_log"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // 걸리는 줄이 없는 문장은 오류가 아니다. 아무것도 바뀌지 않는다.
        assertThat(jdbc.update("delete from audit_log where id = -1")).isZero();

        // 줄은 그대로이고, 그 뒤에도 더할 수 있다.
        assertThat(log.after(id - 1)).first().satisfies(row -> {
            assertThat(row.id()).isEqualTo(id);
            assertThat(row.actor()).isEqualTo("guard-user");
        });
        long next = log.record(new AuditEntry(T0, "guard-user", "guardAction", null, null, AuditOutcome.SUCCESS, 200,
                null, id));
        assertThat(next).isGreaterThan(id);
    }
}
