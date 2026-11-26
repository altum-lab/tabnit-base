package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A-18 수용 기준 (core 쪽): 두 개체와 기준 날짜를 주면 그 날짜에 유효한 관계만으로 이은 가장 짧은 경로가 나온다.
 * 관계의 방향은 가리지 않는다. 길이가 같은 경로가 여럿이면 출발 쪽부터 관계의 식별자를 견줘 앞선 것 하나다
 * (D2 AD-413).
 *
 * <p>모든 이름과 값은 지어낸 것이다. 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다.
 */
class RelationPathQueryTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Provenance SRC_1 = new Provenance("src", 1);

    private static final LocalDate DAY = LocalDate.parse("2026-06-01");

    private final RelationStore store = new RelationStore(CoreTestDatabase.dataSource());

    private final RelationPathQuery query = new RelationPathQuery(CoreTestDatabase.dataSource());

    @Test
    void 바로_이어진_두_개체는_길이_1_의_경로다() {
        EntityRef a = entity("rp-direct", "T", "a");
        EntityRef b = entity("rp-direct", "T", "b");
        String r = relation(a, b, "R", "2026-01-01", null);

        RelationPath path = query.shortestPath(a.id(), b.id(), DAY, 6, 1000);

        assertThat(path.found()).isTrue();
        assertThat(path.limitReached()).isFalse();
        assertThat(path.relations()).extracting(Relation::id).containsExactly(r);
        assertThat(path.entityIds()).containsExactly(a.id(), b.id());
        // 두 번 물어도 같다.
        assertThat(query.shortestPath(a.id(), b.id(), DAY, 6, 1000)).isEqualTo(path);
    }

    @Test
    void 관계의_방향을_가리지_않고_잇고_거꾸로_물으면_거꾸로_된_경로다() {
        String p = "rp-direction";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        EntityRef c = entity(p, "T", "c");
        // a → b ← c: 방향을 따르면 a 에서 c 로 갈 수 없다.
        String ab = relation(a, b, "R", "2026-01-01", null);
        String cb = relation(c, b, "R", "2026-01-01", null);

        RelationPath forward = query.shortestPath(a.id(), c.id(), DAY, 6, 1000);
        RelationPath backward = query.shortestPath(c.id(), a.id(), DAY, 6, 1000);

        assertThat(forward.relations()).extracting(Relation::id).containsExactly(ab, cb);
        assertThat(forward.entityIds()).containsExactly(a.id(), b.id(), c.id());
        assertThat(backward.relations()).extracting(Relation::id).containsExactly(cb, ab);
        assertThat(backward.entityIds()).containsExactly(c.id(), b.id(), a.id());
        // 관계에 적힌 방향은 그대로다.
        assertThat(forward.relations().get(1).sourceEntityId()).isEqualTo(c.id());
    }

    @Test
    void 길이가_다른_경로가_있으면_가장_짧은_것이다() {
        String p = "rp-shortest";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        EntityRef c = entity(p, "T", "c");
        EntityRef d = entity(p, "T", "d");
        EntityRef x = entity(p, "T", "x");
        relation(a, b, "R", "2026-01-01", null);
        relation(b, c, "R", "2026-01-01", null);
        relation(c, d, "R", "2026-01-01", null);
        String ax = relation(a, x, "R", "2026-01-01", null);
        String xd = relation(x, d, "R", "2026-01-01", null);

        RelationPath path = query.shortestPath(a.id(), d.id(), DAY, 6, 1000);

        assertThat(path.relations()).extracting(Relation::id).containsExactly(ax, xd);
        assertThat(path.entityIds()).containsExactly(a.id(), x.id(), d.id());
    }

    @Test
    void 길이가_같은_경로가_여럿이면_이웃의_식별자가_아니라_관계의_식별자가_앞선_것이다() {
        String p = "rp-tie";
        EntityRef m = entity(p, "T", "m");
        EntityRef b = entity(p, "T", "b");
        EntityRef c = entity(p, "T", "c");
        EntityRef d = entity(p, "T", "d");
        // 관계의 식별자는 출발 개체의 식별자로 시작한다. m → b 는 m 으로, c → m 은 c 로 시작한다.
        // 이웃의 식별자로 고르면 b 쪽이고, 관계의 식별자로 고르면 c 쪽이다.
        String mB = relation(m, b, "R", "2026-01-01", null);
        relation(b, d, "R", "2026-01-01", null);
        String cM = relation(c, m, "R", "2026-01-01", null);
        String cD = relation(c, d, "R", "2026-01-01", null);
        assertThat(CodePointOrder.compare(cM, mB)).as("이 시험의 전제: c → m 의 식별자가 앞선다").isNegative();
        assertThat(CodePointOrder.compare(b.id(), c.id())).as("이 시험의 전제: 이웃은 b 가 앞선다").isNegative();

        RelationPath path = query.shortestPath(m.id(), d.id(), DAY, 6, 1000);

        assertThat(path.relations()).extracting(Relation::id).containsExactly(cM, cD);
        assertThat(path.entityIds()).containsExactly(m.id(), c.id(), d.id());
    }

    @Test
    void 출발_쪽의_걸음이_먼저다_뒤의_걸음이_더_앞서도_앞의_걸음으로_정한다() {
        String p = "rp-front";
        EntityRef m = entity(p, "T", "m");
        EntityRef c = entity(p, "T", "c");
        EntityRef n = entity(p, "T", "n");
        EntityRef q = entity(p, "T", "q");
        // 길 가: c → m, q → c. 첫 걸음의 식별자(c 로 시작)가 가장 앞서고 둘째 걸음(q 로 시작)은 가장 뒤선다.
        String cM = relation(c, m, "R", "2026-01-01", null);
        String qC = relation(q, c, "R", "2026-01-01", null);
        // 길 나: m → n, n → q. 도착 쪽부터 고르거나 가장 작은 식별자 하나만 보면 갈린다.
        String mN = relation(m, n, "R", "2026-01-01", null);
        String nQ = relation(n, q, "R", "2026-01-01", null);
        assertThat(CodePointOrder.compare(cM, mN)).as("첫 걸음은 길 가가 앞선다").isNegative();
        assertThat(CodePointOrder.compare(nQ, qC)).as("둘째 걸음은 길 나가 앞선다").isNegative();

        assertThat(query.shortestPath(m.id(), q.id(), DAY, 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(cM, qC);
        // 거꾸로 물으면 출발 쪽이 q 다: q 에 닿은 관계 가운데 앞선 것은 n → q 다.
        assertThat(query.shortestPath(q.id(), m.id(), DAY, 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(nQ, mN);
    }

    @Test
    void 첫_걸음이_같으면_다음_걸음의_식별자로_정한다() {
        String p = "rp-second";
        EntityRef m = entity(p, "T", "m");
        EntityRef x = entity(p, "T", "x");
        EntityRef y1 = entity(p, "T", "y1");
        EntityRef y2 = entity(p, "T", "y2");
        EntityRef q = entity(p, "T", "q");
        String mX = relation(m, x, "R", "2026-01-01", null);
        // 넣는 차례를 섞는다.
        relation(x, y2, "R", "2026-01-01", null);
        relation(y2, q, "R", "2026-01-01", null);
        String xY1 = relation(x, y1, "R", "2026-01-01", null);
        String y1Q = relation(y1, q, "R", "2026-01-01", null);

        assertThat(query.shortestPath(m.id(), q.id(), DAY, 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(mX, xY1, y1Q);
    }

    @Test
    void 같은_거리의_두_개체를_잇는_관계는_경로에_끼지_않는다() {
        String p = "rp-triangle";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        EntityRef c = entity(p, "T", "c");
        relation(a, b, "R", "2026-01-01", null);
        String ac = relation(a, c, "R", "2026-01-01", null);
        relation(b, c, "R", "2026-01-01", null);

        assertThat(query.shortestPath(a.id(), c.id(), DAY, 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(ac);
    }

    @Test
    void 두_개체_사이에_서로_반대로_적힌_관계가_있으면_식별자가_앞선_것이다() {
        String p = "rp-opposite";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        String ab = relation(a, b, "R", "2026-01-01", null);
        String ba = relation(b, a, "R", "2026-01-01", null);
        assertThat(CodePointOrder.compare(ab, ba)).isNegative();

        assertThat(query.shortestPath(a.id(), b.id(), DAY, 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(ab);
        assertThat(query.shortestPath(b.id(), a.id(), DAY, 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(ab);
    }

    @Test
    void 같은_관계가_끝난_날에_새로_시작하면_그_날에는_새_것만_쓴다() {
        String p = "rp-renew";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        String before = relation(a, b, "R", "2026-01-01", "2026-03-01");
        String after = relation(a, b, "R", "2026-03-01", null);

        assertThat(query.shortestPath(a.id(), b.id(), date("2026-02-28"), 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(before);
        assertThat(query.shortestPath(a.id(), b.id(), date("2026-03-01"), 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(after);
    }

    @Test
    void 관계가_하나도_없는_개체에서는_없다고_답한다_한도_때문이_아니다() {
        EntityRef alone = entity("rp-alone", "T", "alone");
        EntityRef other = entity("rp-alone", "T", "other");

        RelationPath none = query.shortestPath(alone.id(), other.id(), DAY, 6, 1000);

        assertThat(none.found()).isFalse();
        assertThat(none.limitReached()).isFalse();
    }

    @Test
    void 식별자가_앞선_관계라도_도착_개체에_닿지_못하는_길이면_고르지_않는다() {
        String p = "rp-deadend";
        EntityRef a = entity(p, "T", "a");
        EntityRef dead = entity(p, "T", "b");
        EntityRef live = entity(p, "T", "c");
        EntityRef d = entity(p, "T", "d");
        // a - b 는 식별자가 앞서지만 b 는 막다른 곳이다.
        relation(a, dead, "R", "2026-01-01", null);
        String ac = relation(a, live, "R", "2026-01-01", null);
        String cd = relation(live, d, "R", "2026-01-01", null);

        RelationPath path = query.shortestPath(a.id(), d.id(), DAY, 6, 1000);

        assertThat(path.relations()).extracting(Relation::id).containsExactly(ac, cd);
    }

    @Test
    void 같은_두_개체_사이에_관계가_여럿이면_식별자가_앞선_것이다() {
        String p = "rp-parallel";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        String late = relation(a, b, "z-type", "2026-01-01", null);
        String early = relation(a, b, "a-type", "2026-01-01", null);
        assertThat(CodePointOrder.compare(early, late)).isNegative();

        assertThat(query.shortestPath(a.id(), b.id(), DAY, 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(early);
    }

    @Test
    void 기준_날짜에_유효한_관계만_쓴다_시작일은_포함_종료일은_제외다() {
        String p = "rp-asof";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        EntityRef c = entity(p, "T", "c");
        relation(a, b, "R", "2026-01-01", null);
        String bc = relation(b, c, "R", "2026-03-01", "2026-06-01");

        // 시작일 전날에는 없다.
        assertThat(query.shortestPath(a.id(), c.id(), date("2026-02-28"), 6, 1000).found()).isFalse();
        // 시작일 당일과 종료일 전날에는 있다.
        assertThat(query.shortestPath(a.id(), c.id(), date("2026-03-01"), 6, 1000).relations()).extracting(Relation::id)
            .contains(bc);
        assertThat(query.shortestPath(a.id(), c.id(), date("2026-05-31"), 6, 1000).found()).isTrue();
        // 종료일 당일에는 그 관계를 쓰지 않는다.
        RelationPath ended = query.shortestPath(a.id(), c.id(), date("2026-06-01"), 6, 1000);
        assertThat(ended.found()).isFalse();
        assertThat(ended.limitReached()).isFalse();
        assertThat(ended.relations()).isEmpty();
        assertThat(ended.entityIds()).isEmpty();
    }

    @Test
    void 날짜에_따라_가장_짧은_경로가_달라진다() {
        String p = "rp-shift";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        EntityRef c = entity(p, "T", "c");
        relation(a, b, "R", "2026-01-01", null);
        relation(b, c, "R", "2026-01-01", null);
        String direct = relation(a, c, "R", "2026-04-01", null);

        assertThat(query.shortestPath(a.id(), c.id(), date("2026-03-31"), 6, 1000).relations()).hasSize(2);
        assertThat(query.shortestPath(a.id(), c.id(), date("2026-04-01"), 6, 1000).relations()).extracting(Relation::id)
            .containsExactly(direct);
    }

    @Test
    void 두_개체가_같으면_길이_0_의_경로다() {
        EntityRef a = entity("rp-same", "T", "a");
        relation(a, entity("rp-same", "T", "b"), "R", "2026-01-01", null);

        RelationPath path = query.shortestPath(a.id(), a.id(), DAY, 6, 1000);

        assertThat(path.found()).isTrue();
        assertThat(path.relations()).isEmpty();
        assertThat(path.entityIds()).containsExactly(a.id());
    }

    @Test
    void 없는_개체는_어느_쪽이_없는지_알린다() {
        EntityRef a = entity("rp-missing", "T", "a");

        assertThatThrownBy(() -> query.shortestPath("rp-missing|T|없는-출발", a.id(), DAY, 6, 1000))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining("없는-출발");
        assertThatThrownBy(() -> query.shortestPath(a.id(), "rp-missing|T|없는-도착", DAY, 6, 1000))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining("없는-도착");
        assertThatThrownBy(() -> query.shortestPath("rp-missing|T|없는-출발", "rp-missing|T|없는-출발", DAY, 6, 1000))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 이어져_있지_않으면_없다고_답하고_돌아오는_관계에서도_끝난다() {
        String p = "rp-cycle";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        EntityRef c = entity(p, "T", "c");
        EntityRef island = entity(p, "T", "island");
        relation(a, b, "R", "2026-01-01", null);
        relation(b, c, "R", "2026-01-01", null);
        relation(c, a, "R", "2026-01-01", null);

        RelationPath none = query.shortestPath(a.id(), island.id(), DAY, 6, 1000);

        assertThat(none.found()).isFalse();
        // 한도 때문이 아니라 정말 없는 것이다.
        assertThat(none.limitReached()).isFalse();
        assertThat(query.shortestPath(a.id(), c.id(), DAY, 6, 1000).relations()).hasSize(1);
        // 다른 Pack 의 개체와는 이어질 수 없다.
        EntityRef other = entity("rp-cycle-other", "T", "a");
        assertThat(query.shortestPath(a.id(), other.id(), DAY, 6, 1000).found()).isFalse();
    }

    @Test
    void 최대_길이_안에서_찾지_못하면_없는_것과_구별해_한도에서_멈췄다고_답한다() {
        String p = "rp-length";
        List<EntityRef> chain = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            chain.add(entity(p, "T", "n" + i));
        }
        for (int i = 0; i < 4; i++) {
            relation(chain.get(i), chain.get(i + 1), "R", "2026-01-01", null);
        }
        String from = chain.get(0).id();
        String to = chain.get(4).id();

        RelationPath stopped = query.shortestPath(from, to, DAY, 3, 1000);
        assertThat(stopped.found()).isFalse();
        assertThat(stopped.limitReached()).isTrue();
        // 길이가 한도와 같으면 찾는다.
        RelationPath found = query.shortestPath(from, to, DAY, 4, 1000);
        assertThat(found.found()).isTrue();
        assertThat(found.limitReached()).isFalse();
        assertThat(found.relations()).hasSize(4);
        assertThat(found.entityIds()).containsExactlyElementsOf(chain.stream().map(EntityRef::id).toList());
        // 이어지지 않은 개체: 최대 길이까지 넓혔는데 그 끝에 개체가 남아 있으면, 그 너머는 읽지 않았으므로 한도에서 멈춘 것이다.
        EntityRef island = entity(p, "T", "island");
        assertThat(query.shortestPath(from, island.id(), DAY, 4, 1000).limitReached()).isTrue();
        // 한 단계 더 읽어 더 갈 곳이 없는 것을 봤으면 정말 없는 것이다.
        RelationPath none = query.shortestPath(from, island.id(), DAY, 5, 1000);
        assertThat(none.found()).isFalse();
        assertThat(none.limitReached()).isFalse();
    }

    @Test
    void 훑은_개체가_한도를_넘으면_멈추고_그렇다고_답한다() {
        String p = "rp-entities";
        EntityRef a = entity(p, "T", "a");
        EntityRef gate = null;
        for (int i = 0; i < 50; i++) {
            EntityRef neighbor = entity(p, "T", String.format("n%02d", i));
            relation(a, neighbor, "R", "2026-01-01", null);
            if (i == 49) {
                gate = neighbor;
            }
        }
        EntityRef far = entity(p, "T", "far");
        relation(gate, far, "R", "2026-01-01", null);

        RelationPath stopped = query.shortestPath(a.id(), far.id(), DAY, 6, 10);
        assertThat(stopped.found()).isFalse();
        assertThat(stopped.limitReached()).isTrue();
        // 한도가 넉넉하면 찾는다.
        assertThat(query.shortestPath(a.id(), far.id(), DAY, 6, 100).entityIds()).containsExactly(a.id(), gate.id(), far.id());
        // 한도를 넘긴 단계에서 도착 개체를 찾았으면 찾은 것이다.
        assertThat(query.shortestPath(a.id(), gate.id(), DAY, 6, 10).found()).isTrue();
        // 찾은 개체의 수가 한도와 딱 같으면 멈추지 않는다: 출발 개체와 이웃 50 이 51 이다.
        assertThat(query.shortestPath(a.id(), far.id(), DAY, 6, 51).found()).isTrue();
        assertThat(query.shortestPath(a.id(), far.id(), DAY, 6, 50).limitReached()).isTrue();
    }

    @Test
    void 긴_사슬에서도_가장_짧은_경로를_찾는다() {
        String p = "rp-long";
        List<EntityRef> chain = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            chain.add(entity(p, "T", String.format("n%03d", i)));
        }
        for (int i = 0; i < 199; i++) {
            // 방향을 번갈아 적는다.
            if (i % 2 == 0) {
                relation(chain.get(i), chain.get(i + 1), "R", "2026-01-01", null);
            }
            else {
                relation(chain.get(i + 1), chain.get(i), "R", "2026-01-01", null);
            }
        }

        RelationPath path = query.shortestPath(chain.get(0).id(), chain.get(199).id(), DAY, 300, 1000);

        assertThat(path.found()).isTrue();
        assertThat(path.relations()).hasSize(199);
        assertThat(path.entityIds()).containsExactlyElementsOf(chain.stream().map(EntityRef::id).toList());
    }

    @Test
    void 틀린_인자는_무엇이_틀렸는지_알리며_거부한다() {
        EntityRef a = entity("rp-bad", "T", "a");

        assertThatThrownBy(() -> query.shortestPath("", a.id(), DAY, 6, 1000)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("출발 개체");
        assertThatThrownBy(() -> query.shortestPath(a.id(), "a\u0000", DAY, 6, 1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("도착 개체");
        assertThatThrownBy(() -> query.shortestPath(a.id(), a.id(), null, 6, 1000)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.shortestPath(a.id(), a.id(), LocalDate.MAX, 6, 1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("기준 날짜");
        assertThatThrownBy(() -> query.shortestPath(a.id(), a.id(), DAY, 0, 1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("최대 길이");
        assertThatThrownBy(() -> query.shortestPath(a.id(), a.id(), DAY, 6, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("개체의 한도");
    }

    private EntityRef entity(String pack, String type, String key) {
        EntityRef ref = new EntityRef(pack, type, key);
        store.saveEntity(ref, RECORDED_AT);
        return ref;
    }

    private String relation(EntityRef source, EntityRef target, String type, String from, String to) {
        return store.saveRelation(
                new NewRelation(source, target, type, new ValidityPeriod(date(from), to == null ? null : date(to)), SRC_1),
                RECORDED_AT);
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}
