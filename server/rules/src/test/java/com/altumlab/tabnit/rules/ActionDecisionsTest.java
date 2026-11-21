package com.altumlab.tabnit.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.ImpactDirection;
import com.altumlab.tabnit.core.ImpactQuery;
import com.altumlab.tabnit.core.ImpactRequest;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.pack.EntityTypeDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.RuleChangeKind;
import com.altumlab.tabnit.pack.RuleDefinition;
import com.altumlab.tabnit.pack.RuleSubject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B-07 수용 기준: 조치를 확정하거나 보류하면 누가 언제 했는지 남는다.
 *
 * <p>상태는 대기에서 확정이나 보류로, 보류에서 확정으로만 바뀐다. 확정은 되돌릴 수 없다 (D2 AD-47).
 * 모든 이름과 값은 지어낸 것이다. 시험마다 다른 Pack 식별자를 쓴다.
 */
class ActionDecisionsTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant CREATED = Instant.parse("2026-06-02T00:00:00Z");

    private static final Instant WHEN = Instant.parse("2026-06-03T09:10:11.123456Z");

    private static final LocalDate D = LocalDate.parse("2026-06-01");

    private final JdbcTemplate jdbc = new JdbcTemplate(RulesTestDatabase.dataSource());

    private final RelationStore relations = new RelationStore(RulesTestDatabase.dataSource());

    private final ImpactQuery impact = new ImpactQuery(RulesTestDatabase.dataSource());

    private final ActionGenerator generator = new ActionGenerator(RulesTestDatabase.dataSource());

    private final ActionRecords records = new ActionRecords(RulesTestDatabase.dataSource());

    private final ActionDecisions decisions = new ActionDecisions(RulesTestDatabase.dataSource());

    // ── 수용 기준 ────────────────────────────────────────────────

    @Test
    void 확정하면_상태가_바뀌고_누가_언제_했는지_이력에_남는다() {
        Action action = actions("d-confirm", 2).get(0);

        Action confirmed = decisions.confirm(action.id(), "officer-1", null, WHEN);

        assertThat(confirmed.status()).isEqualTo(ActionStatus.CONFIRMED);
        assertThat(confirmed.id()).isEqualTo(action.id());
        // 상태 말고는 바뀌지 않는다.
        assertThat(confirmed).usingRecursiveComparison().ignoringFields("status").isEqualTo(action);
        assertThat(records.find(action.id())).contains(confirmed);
        assertThat(records.history(action.id())).containsExactly(new ActionDecision(1, ActionStatus.PENDING,
                ActionStatus.CONFIRMED, "officer-1", WHEN, null));
    }

    @Test
    void 보류한_뒤_확정하면_이력이_차례로_둘_남는다() {
        Action action = actions("d-hold", 1).get(0);

        Action held = decisions.hold(action.id(), "officer-1", "담당 부서의 답을 기다린다", WHEN);
        Action confirmed = decisions.confirm(action.id(), "officer-2", "답을 받았다", WHEN.plusSeconds(3600));

        assertThat(held.status()).isEqualTo(ActionStatus.ON_HOLD);
        assertThat(confirmed.status()).isEqualTo(ActionStatus.CONFIRMED);
        assertThat(records.history(action.id())).containsExactly(
                new ActionDecision(1, ActionStatus.PENDING, ActionStatus.ON_HOLD, "officer-1", WHEN, "담당 부서의 답을 기다린다"),
                new ActionDecision(2, ActionStatus.ON_HOLD, ActionStatus.CONFIRMED, "officer-2", WHEN.plusSeconds(3600),
                        "답을 받았다"));
    }

    // ── 일반 시험 ────────────────────────────────────────────────

    @Test
    void 갈_수_없는_바뀜은_지금의_상태를_알리며_거부하고_이력이_늘지_않는다() {
        List<Action> all = actions("d-rejected", 2);
        String confirmed = all.get(0).id();
        String held = all.get(1).id();
        decisions.confirm(confirmed, "officer-1", null, WHEN);
        decisions.hold(held, "officer-1", "기다린다", WHEN);

        // 확정은 되돌릴 수 없고, 다시 확정할 수도 없다.
        assertThatThrownBy(() -> decisions.confirm(confirmed, "officer-2", null, WHEN.plusSeconds(1)))
            .isInstanceOf(ActionDecisionRejectedException.class)
            .hasMessageContaining("확정")
            .satisfies(e -> assertThat(((ActionDecisionRejectedException) e).currentStatus()).isEqualTo(ActionStatus.CONFIRMED));
        assertThatThrownBy(() -> decisions.hold(confirmed, "officer-2", "미룬다", WHEN.plusSeconds(1)))
            .isInstanceOf(ActionDecisionRejectedException.class);
        // 보류된 것을 다시 보류할 수 없다.
        assertThatThrownBy(() -> decisions.hold(held, "officer-2", "또 미룬다", WHEN.plusSeconds(1)))
            .isInstanceOf(ActionDecisionRejectedException.class)
            .satisfies(e -> assertThat(((ActionDecisionRejectedException) e).currentStatus()).isEqualTo(ActionStatus.ON_HOLD));

        assertThat(records.history(confirmed)).hasSize(1);
        assertThat(records.history(held)).hasSize(1);
        assertThat(records.find(confirmed).orElseThrow().status()).isEqualTo(ActionStatus.CONFIRMED);
        assertThat(records.find(held).orElseThrow().status()).isEqualTo(ActionStatus.ON_HOLD);
    }

    @Test
    void 보류에는_사유가_반드시_있어야_하고_확정에는_없어도_된다() {
        Action action = actions("d-note", 1).get(0);

        // 눈에 보이는 글자가 없으면 빈 것이다: 끊지 않는 공백(U+00A0), 폭 없는 공백(U+200B)도 같다.
        for (String blank : java.util.Arrays.asList(null, "", "  \n", "\u00A0", "\u200B \u00A0")) {
            assertThatThrownBy(() -> decisions.hold(action.id(), "officer-1", blank, WHEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("사유");
        }
        assertThat(records.history(action.id())).isEmpty();
        assertThat(records.find(action.id()).orElseThrow().status()).isEqualTo(ActionStatus.PENDING);
        // 확정의 빈 사유는 없는 것으로 남는다.
        decisions.confirm(action.id(), "officer-1", "   ", WHEN);
        assertThat(records.history(action.id()).get(0).note()).isNull();
    }

    @Test
    void 사유와_아이디의_경계() {
        List<Action> all = actions("d-limits", 3);
        String longest = "가".repeat(1000);
        String id200 = "나".repeat(200);

        // 사유는 1,000자까지, 아이디는 200자까지 받는다. 글자의 수로 센다 (바이트가 아니다).
        decisions.hold(all.get(0).id(), id200, longest, WHEN);
        assertThat(records.history(all.get(0).id()).get(0)).extracting(ActionDecision::decidedBy, ActionDecision::note)
            .containsExactly(id200, longest);
        assertThatThrownBy(() -> decisions.hold(all.get(1).id(), "officer-1", longest + "가", WHEN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1000");
        assertThatThrownBy(() -> decisions.confirm(all.get(1).id(), id200 + "나", null, WHEN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("200");
        // 글자의 수는 바이트도 char 도 아니다: 이모지 하나가 한 글자다.
        String emoji = "\uD83D\uDE00".repeat(1000);
        decisions.hold(all.get(1).id(), "officer-1", emoji, WHEN);
        assertThat(records.history(all.get(1).id()).get(0).note()).isEqualTo(emoji);
        assertThatThrownBy(() -> decisions.confirm(all.get(1).id(), "officer-1", emoji + "\uD83D\uDE00", WHEN))
            .isInstanceOf(IllegalArgumentException.class);
        // 저장소가 담지 못하는 시각. 무엇이 틀렸는지 알린다.
        for (Instant far : List.of(Instant.MAX, Instant.MIN, Instant.parse("+300000-01-01T00:00:00Z"))) {
            assertThatThrownBy(() -> decisions.confirm(all.get(1).id(), "officer-1", null, far))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("언제");
        }
        // 누가 했는지가 없으면 받지 않는다.
        for (String blank : java.util.Arrays.asList("", " ", "\u200B")) {
            assertThatThrownBy(() -> decisions.confirm(all.get(1).id(), blank, null, WHEN))
                .isInstanceOf(IllegalArgumentException.class);
        }
        // 저장소가 담지 못하는 글자.
        assertThatThrownBy(() -> decisions.confirm(all.get(1).id(), "officer\u0000", null, WHEN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.confirm(all.get(1).id(), "officer-1", "사유\uD800", WHEN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.confirm(null, "officer-1", null, WHEN)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> decisions.confirm(all.get(1).id(), null, null, WHEN)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> decisions.confirm(all.get(1).id(), "officer-1", null, null))
            .isInstanceOf(NullPointerException.class);
        assertThat(records.history(all.get(1).id())).hasSize(1);
        // 마이크로초보다 세밀한 시각은 잘라 남긴다. 이모지와 줄바꿈이 든 사유는 그대로 남는다.
        String odd = "첫 줄\n둘째 줄 😀 | \\";
        decisions.confirm(all.get(2).id(), "officer-1", odd, Instant.parse("2026-06-03T09:10:11.123456789Z"));
        assertThat(records.history(all.get(2).id())).containsExactly(new ActionDecision(1, ActionStatus.PENDING,
                ActionStatus.CONFIRMED, "officer-1", WHEN, odd));
    }

    @Test
    void 없는_조치는_오류로_알리고_다른_조치는_바뀌지_않는다() {
        List<Action> all = actions("d-missing", 2);

        assertThatThrownBy(() -> decisions.confirm(all.get(0).id() + "-없음", "officer-1", null, WHEN))
            .isInstanceOf(ActionNotFoundException.class)
            .hasMessageContaining(all.get(0).id())
            .hasMessageContaining("없습니다");
        assertThatThrownBy(() -> decisions.hold("없는 조치", "officer-1", "미룬다", WHEN))
            .isInstanceOf(ActionNotFoundException.class);
        assertThat(records.find("없는 조치")).isEmpty();
        assertThat(records.history("없는 조치")).isEmpty();
        assertThatThrownBy(() -> records.find("조치\uD800")).isInstanceOf(IllegalArgumentException.class);

        decisions.confirm(all.get(0).id(), "officer-1", null, WHEN);
        // 같은 변동의 다른 조치는 그대로 대기다. 상태로 거른 목록에도 그대로 나온다.
        assertThat(records.find(all.get(1).id()).orElseThrow().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(records.packActions("d-missing", ActionStatus.CONFIRMED, null)).extracting(Action::id)
            .containsExactly(all.get(0).id());
        assertThat(records.packActions("d-missing", ActionStatus.PENDING, null)).extracting(Action::id)
            .containsExactly(all.get(1).id());
    }

    @Test
    void 여러_사람이_함께_확정해도_한_사람만_되고_이력은_하나다() throws Exception {
        List<Action> all = actions("d-race", 10);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (Action action : all) {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    String who = "officer-" + i;
                    futures.add(pool.submit(() -> {
                        go.await();
                        try {
                            decisions.confirm(action.id(), who, null, WHEN);
                            return who;
                        }
                        catch (ActionDecisionRejectedException e) {
                            return null;
                        }
                    }));
                }
                go.countDown();
                List<String> winners = new ArrayList<>();
                for (Future<String> future : futures) {
                    String who = future.get();
                    if (who != null) {
                        winners.add(who);
                    }
                }
                assertThat(winners).hasSize(1);
                // 이력에 남은 사람이 실제로 된 사람이다.
                assertThat(records.history(action.id())).extracting(ActionDecision::seq, ActionDecision::decidedBy)
                    .containsExactly(tuple(1, winners.get(0)));
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 확정과_보류가_함께_와도_돌려주는_조치는_자기가_바꾼_상태이고_상태는_이력의_마지막_줄과_같다() throws Exception {
        List<Action> all = actions("d-mixed", 20);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (Action action : all) {
                CountDownLatch go = new CountDownLatch(1);
                Future<Action> held = pool.submit(() -> {
                    go.await();
                    try {
                        return decisions.hold(action.id(), "officer-hold", "미룬다", WHEN);
                    }
                    catch (ActionDecisionRejectedException e) {
                        return null;
                    }
                });
                Future<Action> confirmed = pool.submit(() -> {
                    go.await();
                    return decisions.confirm(action.id(), "officer-confirm", null, WHEN);
                });
                go.countDown();
                // 확정은 언제나 된다 (대기에서든 보류에서든). 보류는 먼저 왔을 때만 된다.
                assertThat(confirmed.get().status()).isEqualTo(ActionStatus.CONFIRMED);
                if (held.get() != null) {
                    // 보류한 사람이 그 뒤에 확정된 상태를 돌려받지 않는다.
                    assertThat(held.get().status()).isEqualTo(ActionStatus.ON_HOLD);
                }
                List<ActionDecision> history = records.history(action.id());
                assertThat(history).hasSize(held.get() == null ? 1 : 2);
                assertThat(records.find(action.id()).orElseThrow().status())
                    .isEqualTo(history.get(history.size() - 1).toStatus())
                    .isEqualTo(ActionStatus.CONFIRMED);
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 확정한_뒤에_같은_변동을_다시_실행해도_상태와_이력은_그대로이고_이력은_시각이_아니라_차례로_나온다() {
        String packId = "d-again";
        Action action = actions(packId, 1).get(0);
        // 보류한 때보다 확정한 때가 이르게 넘어와도 받는다. 이력의 차례는 바꾼 차례다.
        decisions.hold(action.id(), "officer-1", "미룬다", WHEN);
        decisions.confirm(action.id(), "officer-1", null, WHEN.minusSeconds(3600));

        List<Action> again = actions(packId, 1);

        assertThat(again.get(0).status()).isEqualTo(ActionStatus.CONFIRMED);
        assertThat(records.history(action.id())).extracting(ActionDecision::seq, ActionDecision::toStatus)
            .containsExactly(tuple(1, ActionStatus.ON_HOLD), tuple(2, ActionStatus.CONFIRMED));
    }

    @Test
    void 거부를_잡고_계속해도_부르는_쪽의_트랜잭션이_깨지지_않고_되돌리면_확정도_사라진다() {
        List<Action> all = actions("d-outer", 2);
        decisions.confirm(all.get(0).id(), "officer-1", null, WHEN);
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(RulesTestDatabase.dataSource()));

        // 부르는 쪽이 자기 트랜잭션 안에서 거부를 잡고 다른 일을 이어 한다.
        outer.executeWithoutResult(status -> {
            assertThatThrownBy(() -> decisions.confirm(all.get(0).id(), "officer-2", null, WHEN))
                .isInstanceOf(ActionDecisionRejectedException.class);
            assertThatThrownBy(() -> decisions.confirm("없는 조치", "officer-2", null, WHEN))
                .isInstanceOf(ActionNotFoundException.class);
            decisions.confirm(all.get(1).id(), "officer-2", null, WHEN);
        });
        assertThat(records.find(all.get(1).id()).orElseThrow().status()).isEqualTo(ActionStatus.CONFIRMED);

        // 부르는 쪽이 되돌리면 상태와 이력이 함께 사라진다.
        List<Action> more = actions("d-outer-2", 1);
        outer.executeWithoutResult(status -> {
            decisions.confirm(more.get(0).id(), "officer-1", null, WHEN);
            status.setRollbackOnly();
        });
        assertThat(records.find(more.get(0).id()).orElseThrow().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(records.history(more.get(0).id())).isEmpty();
    }

    @Test
    void 이력은_데이터베이스에서도_갈_수_있는_바뀜만_받는다() {
        Action action = actions("d-db", 1).get(0);
        String hash = RulesHash.sha256Hex(action.id());

        // 확정에서 대기로, 사유 없는 보류, 누가 했는지 없는 것은 저장소가 받지 않는다.
        for (String[] row : new String[][] { { "CONFIRMED", "PENDING", "who", "x" }, { "PENDING", "ON_HOLD", "who", null },
                { "PENDING", "CONFIRMED", "", null }, { "ON_HOLD", "ON_HOLD", "who", "x" }, { "ON_HOLD", "PENDING", "who", "x" },
                { "CONFIRMED", "ON_HOLD", "who", "x" }, { "CONFIRMED", "CONFIRMED", "who", null },
                { "PENDING", "CONFIRMED", "who", "" }, { "PENDING", "CONFIRMED", " \n", null },
                { "PENDING", "ON_HOLD", "who", "  " }, { "PENDING", "CONFIRMED", "가".repeat(201), null },
                { "PENDING", "CONFIRMED", "who", "가".repeat(1001) },
                // 첫 줄은 대기에서 시작한다.
                { "ON_HOLD", "CONFIRMED", "who", null } }) {
            assertThatThrownBy(() -> jdbc.update("""
                    insert into action_history (action_id_hash, seq, from_status, to_status, decided_by, decided_at, note)
                    values (?, 1, ?, ?, ?, ?, ?)
                    """, hash, row[0], row[1], row[2], java.time.OffsetDateTime.ofInstant(WHEN, java.time.ZoneOffset.UTC),
                    row[3])).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(records.history(action.id())).isEmpty();
        // 확정된 조치에 확정의 이력을 한 줄 더 넣는 것, 같은 차례를 두 번 쓰는 것을 저장소가 받지 않는다.
        decisions.confirm(action.id(), "officer-1", null, WHEN);
        for (int seq : new int[] { 1, 2 }) {
            assertThatThrownBy(() -> jdbc.update("""
                    insert into action_history (action_id_hash, seq, from_status, to_status, decided_by, decided_at, note)
                    values (?, ?, 'PENDING', 'CONFIRMED', 'who', ?, null)
                    """, hash, seq, java.time.OffsetDateTime.ofInstant(WHEN, java.time.ZoneOffset.UTC)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(records.history(action.id())).hasSize(1);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 지어낸 Pack 에 변동 하나를 처리해 대기인 조치를 만든다. 규칙마다 조치 하나다. */
    private List<Action> actions(String packId, int count) {
        EntityRef start = new EntityRef(packId, "Base", "B-1");
        relations.saveEntity(start, AT);
        List<RuleDefinition> rules = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rules.add(new RuleDefinition(String.format("%04d", i), "규칙 " + i, 1, List.of(RuleChangeKind.values()),
                    RuleSubject.CHANGE_TARGET, "Base", List.of(), "할 일 " + i, 7));
        }
        PackDefinition pack = new PackDefinition(packId, "이름표", 3, AT,
                List.of(new EntityTypeDefinition("Base", "바탕", List.of(), null)), List.of(), List.of(), List.of(), List.of(),
                rules);
        ActionGeneration generation = generator.generate(pack,
                new RuleChange(packId + "-ev", RuleChangeKind.CHANGED, start.id(), List.of(start.id()), D),
                new BasisEdition("src", 2),
                impact.impactScope(new ImpactRequest(List.of(start.id()), List.of(D.minusDays(1), D),
                        Map.<String, ImpactDirection>of(), 3)),
                3, CREATED);
        assertThat(generation.actions()).hasSize(count);
        return generation.actions();
    }
}
