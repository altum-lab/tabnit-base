package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A-06 수용 기준: 관계 지도 화면이 쓰는 형식으로 돌려준다 (노드와 간선, 시점 지정. D2 AD-352).
 *
 * <p>일반 시험이다. 특정 Pack 과 무관한 지어낸 값으로, 관계가 없는 개체, 유효기간 경계, 깊이, 방향, 돌아오는 관계,
 * 한도, 순서, 잘못된 입력을 본다.
 */
class RelationMapQueryTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Provenance SRC_1 = new Provenance("src", 1);

    private final RelationStore store = new RelationStore(CoreTestDatabase.dataSource());

    private final RelationMapQuery query = new RelationMapQuery(CoreTestDatabase.dataSource());

    // ── 빈 것과 잘못된 입력 ───────────────────────────────────────

    @Test
    void 없는_개체를_가운데로_두면_오류로_알린다() {
        assertThatThrownBy(() -> query.relationMap("map-none|T|missing", date("2026-03-01"), 1, 200))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining("map-none|T|missing");
    }

    @Test
    void 관계가_없는_개체는_자신만_노드로_돌려준다() {
        EntityRef alone = entity("map-alone", "T", "a");

        RelationMap map = query.relationMap(alone.id(), date("2026-03-01"), 3, 200);

        assertThat(map.nodes()).containsExactly(new RelationMapNode(alone.id(), "T", 0));
        assertThat(map.edges()).isEmpty();
        assertThat(map.totalNodes()).isEqualTo(1);
        assertThat(map.truncated()).isFalse();
        // 더 따라갈 것이 없어 끝까지 간 것이다: 요청한 깊이를 적는다.
        assertThat(map.reachedDepth()).isEqualTo(3);
    }

    @Test
    void 기준_날짜와_깊이와_한도는_반드시_받고_틀린_값은_거부한다() {
        EntityRef a = entity("map-args", "T", "a");
        assertThatThrownBy(() -> query.relationMap(a.id(), null, 1, 200)).isInstanceOf(NullPointerException.class)
            .hasMessageContaining("기준 날짜");
        assertThatThrownBy(() -> query.relationMap(null, date("2026-03-01"), 1, 200))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("개체");
        assertThatThrownBy(() -> query.relationMap(a.id(), date("2026-03-01"), 0, 200))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("깊이");
        assertThatThrownBy(() -> query.relationMap(a.id(), date("2026-03-01"), 1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("노드");
    }

    // A-10 (D2 AD-370): 관계 지도도 같은 범위의 날짜만 받는다.
    @Test
    void 받는_범위_밖의_기준_날짜와_깨진_식별자는_저장소까지_가지_않고_입력이_틀렸다고_알린다() {
        EntityRef a = entity("map-range", "T", "a");

        for (LocalDate bad : List.of(LocalDate.MAX, LocalDate.MIN, LocalDate.of(10000, 1, 1), LocalDate.of(0, 12, 31))) {
            assertThatThrownBy(() -> query.relationMap(a.id(), bad, 1, 200)).as(bad.toString())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기준 날짜");
        }
        assertThat(query.relationMap(a.id(), LocalDate.of(1, 1, 1), 1, 200).nodes()).hasSize(1);
        assertThat(query.relationMap(a.id(), LocalDate.of(9999, 12, 31), 1, 200).nodes()).hasSize(1);
        for (String bad : List.of("", "map-range|T|a\u0000", "map-range|T|\uD800")) {
            assertThatThrownBy(() -> query.relationMap(bad, date("2026-03-01"), 1, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("개체");
        }
    }

    // ── 방향, 깊이, 시점 ─────────────────────────────────────────

    @Test
    void 들어오는_관계와_나가는_관계를_모두_따라가고_간선에는_적힌_방향이_남는다() {
        String pack = "map-both";
        EntityRef center = entity(pack, "T", "center");
        EntityRef up = entity(pack, "T", "up");
        EntityRef down = entity(pack, "T", "down");
        String in = relation(up, center, "R", "2026-01-01", null);
        String out = relation(center, down, "R", "2026-01-01", null);

        RelationMap map = query.relationMap(center.id(), date("2026-03-01"), 1, 200);

        assertThat(map.nodes()).containsExactly(new RelationMapNode(center.id(), "T", 0),
                new RelationMapNode(down.id(), "T", 1), new RelationMapNode(up.id(), "T", 1));
        assertThat(map.edges()).extracting(Relation::id).containsExactlyInAnyOrder(in, out);
        Relation incoming = map.edges().stream().filter(edge -> edge.id().equals(in)).findFirst().orElseThrow();
        assertThat(incoming.sourceEntityId()).isEqualTo(up.id());
        assertThat(incoming.targetEntityId()).isEqualTo(center.id());
    }

    @Test
    void 깊이만큼만_따라가고_노드마다_가운데에서_가장_가까운_깊이를_적는다() {
        String pack = "map-depth";
        EntityRef a = entity(pack, "T", "a");
        EntityRef b = entity(pack, "T", "b");
        EntityRef c = entity(pack, "T", "c");
        EntityRef d = entity(pack, "T", "d");
        relation(a, b, "R", "2026-01-01", null);
        relation(b, c, "R", "2026-01-01", null);
        relation(c, d, "R", "2026-01-01", null);
        // 돌아오는 관계와 지름길: c 는 a 에서 바로도 닿는다 (깊이 1).
        relation(c, a, "R", "2026-01-01", null);

        assertThat(depths(query.relationMap(a.id(), date("2026-03-01"), 1, 200)))
            .containsExactly(a.id() + "=0", b.id() + "=1", c.id() + "=1");
        RelationMap two = query.relationMap(a.id(), date("2026-03-01"), 2, 200);
        assertThat(depths(two)).containsExactly(a.id() + "=0", b.id() + "=1", c.id() + "=1", d.id() + "=2");
        // 간선: 돌려준 노드끼리 이은 관계는 모두 들어 있다 (같은 깊이의 노드 사이도).
        assertThat(two.edges()).hasSize(4);
        // 깊이 1 에서는 d 가 없으므로 c 와 d 의 관계도 없다.
        assertThat(query.relationMap(a.id(), date("2026-03-01"), 1, 200).edges()).hasSize(3);
    }

    @Test
    void 기준_날짜에_유효한_관계만_따라가고_간선으로_돌려준다() {
        String pack = "map-asof";
        EntityRef ga = entity(pack, "T", "ga");
        EntityRef na = entity(pack, "T", "na");
        EntityRef da = entity(pack, "T", "da");
        String first = relation(ga, na, "R", "2026-01-01", "2026-03-01");
        String second = relation(ga, da, "R", "2026-03-01", null);

        // 종료일 전날: 첫 관계만.
        RelationMap before = query.relationMap(ga.id(), date("2026-02-28"), 1, 200);
        assertThat(before.edges()).extracting(Relation::id).containsExactly(first);
        assertThat(depths(before)).containsExactly(ga.id() + "=0", na.id() + "=1");
        // 종료일 당일: 종료일은 제외, 시작일은 포함.
        RelationMap on = query.relationMap(ga.id(), date("2026-03-01"), 1, 200);
        assertThat(on.edges()).extracting(Relation::id).containsExactly(second);
        assertThat(depths(on)).containsExactly(ga.id() + "=0", da.id() + "=1");
        // 어느 관계도 시작하지 않은 날.
        assertThat(query.relationMap(ga.id(), date("2025-12-31"), 1, 200).nodes()).hasSize(1);
    }

    // ── 순서와 한도 ──────────────────────────────────────────────

    @Test
    void 노드는_깊이_유형_식별자_순이고_간선은_유형_출발_도착_식별자_순이다() {
        String pack = "map-order";
        EntityRef center = entity(pack, "M", "center");
        EntityRef zType = entity(pack, "Z", "a");
        EntityRef aTypeB = entity(pack, "A", "b");
        EntityRef aTypeA = entity(pack, "A", "a");
        String r3 = relation(center, zType, "R2", "2026-01-01", null);
        String r2 = relation(center, aTypeB, "R1", "2026-01-01", null);
        String r1 = relation(aTypeA, center, "R1", "2026-01-01", null);

        RelationMap map = query.relationMap(center.id(), date("2026-03-01"), 1, 200);

        assertThat(map.nodes()).extracting(RelationMapNode::entityId)
            .containsExactly(center.id(), aTypeA.id(), aTypeB.id(), zType.id());
        // R1 가운데서는 출발 개체의 식별자 순: A|a 에서 나가는 것이 M|center 에서 나가는 것보다 앞이다.
        assertThat(map.edges()).extracting(Relation::id).containsExactly(r1, r2, r3);
    }

    @Test
    void 한도를_넘으면_가까운_것부터_한도만큼만_담고_잘렸다는_표시와_전체_수를_준다() {
        String pack = "map-cut";
        EntityRef center = entity(pack, "T", "center");
        List<EntityRef> near = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            EntityRef one = entity(pack, "T", "near-" + i);
            near.add(one);
            relation(center, one, "R", "2026-01-01", null);
        }
        EntityRef far = entity(pack, "A", "far");
        relation(near.get(4), far, "R", "2026-01-01", null);

        RelationMap cut = query.relationMap(center.id(), date("2026-03-01"), 2, 4);

        // 가운데와 깊이 1 의 앞의 셋. 깊이 2 의 것은 유형이 앞서도 뒤다.
        assertThat(cut.nodes()).extracting(RelationMapNode::entityId)
            .containsExactly(center.id(), near.get(0).id(), near.get(1).id(), near.get(2).id());
        // 깊이 1 에서 이미 한도를 넘었다 (가운데와 이웃 다섯). 더 깊이 따라가지 않는다 (D2 AD-357):
        // 수는 따라간 데까지 찾은 것이고, 깊이 2 의 far 는 세지 않는다.
        assertThat(cut.reachedDepth()).isEqualTo(1);
        assertThat(cut.totalNodes()).isEqualTo(6);
        assertThat(cut.truncated()).isTrue();
        // 간선은 담긴 노드끼리의 것만.
        assertThat(cut.edges()).hasSize(3);
        // 한도와 꼭 같으면 잘리지 않았다.
        RelationMap exact = query.relationMap(center.id(), date("2026-03-01"), 2, 7);
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.reachedDepth()).isEqualTo(2);
        assertThat(exact.nodes()).hasSize(7);
        assertThat(exact.edges()).hasSize(6);
        // 한도가 1 이면 가운데만.
        RelationMap one = query.relationMap(center.id(), date("2026-03-01"), 2, 1);
        assertThat(one.nodes()).extracting(RelationMapNode::entityId).containsExactly(center.id());
        assertThat(one.edges()).isEmpty();
        assertThat(one.truncated()).isTrue();
        assertThat(one.reachedDepth()).isEqualTo(1);
        assertThat(one.totalNodes()).isEqualTo(6);
    }

    @Test
    void 출처가_달라_겹쳐_저장된_관계는_간선_둘로_돌려주고_두_번_돌려도_같다() {
        String pack = "map-twice";
        EntityRef a = entity(pack, "T", "a");
        EntityRef b = entity(pack, "T", "b");
        relation(a, b, "R", "2026-01-01", null);
        store.saveRelation(new NewRelation(a, b, "R", new ValidityPeriod(date("2026-02-01"), null), new Provenance("other", 1)),
                RECORDED_AT);

        RelationMap first = query.relationMap(a.id(), date("2026-03-01"), 2, 200);
        RelationMap second = query.relationMap(a.id(), date("2026-03-01"), 2, 200);

        assertThat(first.edges()).hasSize(2);
        assertThat(first.nodes()).hasSize(2);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void 이웃이_많아도_끝까지_세고_한도만큼만_담는다() {
        String pack = "map-many";
        EntityRef center = entity(pack, "T", "center");
        for (int i = 0; i < 300; i++) {
            relation(center, entity(pack, "T", String.format("n-%03d", i)), "R", "2026-01-01", null);
        }

        RelationMap map = query.relationMap(center.id(), date("2026-03-01"), 1, 200);

        assertThat(map.totalNodes()).isEqualTo(301);
        assertThat(map.nodes()).hasSize(200);
        assertThat(map.edges()).hasSize(199);
        assertThat(map.truncated()).isTrue();
    }

    // ── 검토에서 더한 경우 ────────────────────────────────────────

    @Test
    void 담긴_노드_사이의_관계라도_기준_날짜에_끝났으면_간선으로_주지_않는다() {
        String pack = "map-ended-edge";
        EntityRef ga = entity(pack, "T", "ga");
        EntityRef na = entity(pack, "T", "na");
        EntityRef da = entity(pack, "T", "da");
        String toNa = relation(ga, na, "R", "2026-01-01", null);
        String toDa = relation(ga, da, "R", "2026-01-01", null);
        // 나와 다는 둘 다 가에서 닿아 담긴다. 그 둘 사이의 관계는 기준 날짜에 끝났다.
        String ended = relation(na, da, "R", "2026-01-01", "2026-03-01");

        RelationMap on = query.relationMap(ga.id(), date("2026-03-01"), 1, 200);
        assertThat(on.nodes()).hasSize(3);
        assertThat(on.edges()).extracting(Relation::id).containsExactlyInAnyOrder(toNa, toDa);
        // 종료일 전날에는 간선이다. 종료일이 있는 관계의 시작일 당일에도 유효하다.
        assertThat(query.relationMap(ga.id(), date("2026-02-28"), 1, 200).edges()).extracting(Relation::id)
            .containsExactlyInAnyOrder(toNa, toDa, ended);
        // 나를 가운데 두면 가와 다가 모두 담기므로, 그 셋 사이의 관계가 모두 간선이다.
        assertThat(query.relationMap(na.id(), date("2026-01-01"), 1, 200).edges()).extracting(Relation::id)
            .containsExactlyInAnyOrder(toNa, toDa, ended);
    }

    @Test
    void 들어오는_방향으로만_이어진_사슬도_깊이_셋까지_따라간다() {
        String pack = "map-upstream";
        EntityRef a = entity(pack, "T", "a");
        EntityRef b = entity(pack, "T", "b");
        EntityRef c = entity(pack, "T", "c");
        EntityRef d = entity(pack, "T", "d");
        EntityRef e = entity(pack, "T", "e");
        relation(b, a, "R", "2026-01-01", null);
        relation(c, b, "R", "2026-01-01", null);
        relation(d, c, "R", "2026-01-01", null);
        relation(e, d, "R", "2026-01-01", null);

        RelationMap map = query.relationMap(a.id(), date("2026-03-01"), 3, 200);

        assertThat(depths(map)).containsExactly(a.id() + "=0", b.id() + "=1", c.id() + "=2", d.id() + "=3");
        assertThat(map.edges()).hasSize(3);
        // 깊이 너머의 개체(e)는 세지 않는다.
        assertThat(map.totalNodes()).isEqualTo(4);
        assertThat(map.truncated()).isFalse();
        assertThat(map.reachedDepth()).isEqualTo(3);
    }

    @Test
    void 여러_길로_닿는_노드는_한_번만_세고_둘째_깊이의_가운데에서도_순서대로_자른다() {
        String pack = "map-diamond";
        EntityRef top = entity(pack, "T", "top");
        EntityRef left = entity(pack, "T", "left");
        EntityRef right = entity(pack, "T", "right");
        relation(top, left, "R", "2026-01-01", null);
        relation(top, right, "R", "2026-01-01", null);
        List<EntityRef> bottoms = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            EntityRef bottom = entity(pack, "T", "bottom-" + i);
            bottoms.add(bottom);
            // 두 길로 닿는다.
            relation(left, bottom, "R", "2026-01-01", null);
            relation(right, bottom, "R", "2026-01-01", null);
        }

        RelationMap whole = query.relationMap(top.id(), date("2026-03-01"), 2, 200);
        assertThat(whole.totalNodes()).isEqualTo(7);
        assertThat(whole.nodes()).hasSize(7);
        assertThat(whole.edges()).hasSize(10);
        // 한도 5: 가운데, 깊이 1 의 둘, 깊이 2 의 앞의 둘.
        RelationMap cut = query.relationMap(top.id(), date("2026-03-01"), 2, 5);
        assertThat(cut.nodes()).extracting(RelationMapNode::entityId)
            .containsExactly(top.id(), left.id(), right.id(), bottoms.get(0).id(), bottoms.get(1).id());
        assertThat(cut.totalNodes()).isEqualTo(7);
        assertThat(cut.truncated()).isTrue();
        // 깊이 1 까지는 한도 안이라 깊이 2 까지 따라갔다.
        assertThat(cut.reachedDepth()).isEqualTo(2);
        // 담긴 노드끼리의 관계만: 위의 둘과, 담긴 아래 둘에 닿는 넷.
        assertThat(cut.edges()).hasSize(6);
    }

    @Test
    void 같은_두_개체_사이에_유형이_다른_관계가_여럿이면_간선도_여럿이고_노드는_하나다() {
        String pack = "map-types";
        EntityRef a = entity(pack, "T", "a");
        EntityRef b = entity(pack, "T", "b");
        String second = relation(a, b, "R2", "2026-01-01", null);
        String first = relation(a, b, "R1", "2026-01-01", null);
        String back = relation(b, a, "R1", "2026-01-01", null);

        RelationMap map = query.relationMap(a.id(), date("2026-03-01"), 1, 200);

        assertThat(map.nodes()).hasSize(2);
        assertThat(map.edges()).extracting(Relation::id).containsExactly(first, back, second);
    }

    @Test
    void 기본_평면_밖의_글자가_든_식별자도_노드와_간선에서_같은_글자_코드_순이다() {
        String pack = "map-codepoint";
        EntityRef center = entity(pack, "T", "center");
        // 글자 코드로는 U+FF5E 가 U+1F600 보다 앞이다. UTF-16 의 단위로 견주면 거꾸로 나온다.
        EntityRef bmp = entity(pack, "T", "x\uFF5E");
        EntityRef beyond = entity(pack, "T", "x\uD83D\uDE00");
        String toBeyond = relation(center, beyond, "R", "2026-01-01", null);
        String toBmp = relation(center, bmp, "R", "2026-01-01", null);

        RelationMap map = query.relationMap(center.id(), date("2026-03-01"), 1, 200);

        assertThat(map.nodes()).extracting(RelationMapNode::entityId).containsExactly(center.id(), bmp.id(), beyond.id());
        assertThat(map.edges()).extracting(Relation::id).containsExactly(toBmp, toBeyond);
        // 한도로 자를 때도 같은 순서다.
        assertThat(query.relationMap(center.id(), date("2026-03-01"), 1, 2).nodes()).extracting(RelationMapNode::entityId)
            .containsExactly(center.id(), bmp.id());
    }

    @Test
    void 한도를_넘은_단계에서_멈춰도_담기는_노드와_간선은_끝까지_따라갔을_때와_같다() {
        // 가운데에서 이웃 여섯, 그 이웃마다 다시 이웃 셋 (깊이 2 에 열여덟).
        String pack = "map-stop";
        EntityRef center = entity(pack, "T", "center");
        for (int i = 0; i < 6; i++) {
            EntityRef near = entity(pack, "T", "near-" + i);
            relation(center, near, "R", "2026-01-01", null);
            for (int j = 0; j < 3; j++) {
                // 유형의 이름이 앞서도 깊이가 먼저다: 깊이 2 의 것은 담기지 않는다.
                relation(near, entity(pack, "A", "far-" + i + "-" + j), "R", "2026-01-01", null);
            }
        }

        // 한도 5: 깊이 1 에서 일곱이 되어 넘는다. 깊이 3 을 청해도 깊이 1 에서 멈춘다.
        RelationMap stopped = query.relationMap(center.id(), date("2026-03-01"), 3, 5);
        assertThat(stopped.reachedDepth()).isEqualTo(1);
        assertThat(stopped.totalNodes()).isEqualTo(7);
        assertThat(stopped.truncated()).isTrue();
        // 담기는 것은 깊이를 1 로 청했을 때와 같다.
        RelationMap shallow = query.relationMap(center.id(), date("2026-03-01"), 1, 5);
        assertThat(stopped.nodes()).isEqualTo(shallow.nodes());
        assertThat(stopped.edges()).isEqualTo(shallow.edges());
        assertThat(stopped.nodes()).extracting(RelationMapNode::entityId)
            .containsExactly(center.id(), id(pack, "near-0"), id(pack, "near-1"), id(pack, "near-2"), id(pack, "near-3"));
        assertThat(stopped.edges()).hasSize(4);

        // 한도가 넉넉하면 끝까지 따라간다.
        RelationMap whole = query.relationMap(center.id(), date("2026-03-01"), 3, 200);
        assertThat(whole.reachedDepth()).isEqualTo(3);
        assertThat(whole.totalNodes()).isEqualTo(25);
        assertThat(whole.truncated()).isFalse();
        // 한도와 꼭 같은 수에서는 멈추지 않는다: 깊이 1 까지 일곱, 한도 일곱.
        RelationMap exactly = query.relationMap(center.id(), date("2026-03-01"), 2, 7);
        assertThat(exactly.reachedDepth()).isEqualTo(2);
        assertThat(exactly.totalNodes()).isEqualTo(25);
        assertThat(exactly.truncated()).isTrue();
        assertThat(exactly.nodes()).hasSize(7);
    }

    @Test
    void 가운데_단계에서_한도를_넘으면_그_단계에서_멈추고_더_깊은_노드는_담지도_세지도_않는다() {
        // 깊이 1 에 둘, 깊이 2 에 여섯, 깊이 3 에 열둘. 깊이 3 의 유형은 글자 순으로 가장 앞선다.
        String pack = "map-stop-middle";
        EntityRef center = entity(pack, "T", "center");
        for (int i = 0; i < 2; i++) {
            EntityRef one = entity(pack, "T", "one-" + i);
            relation(center, one, "R", "2026-01-01", null);
            for (int j = 0; j < 3; j++) {
                EntityRef two = entity(pack, "T", "two-" + i + "-" + j);
                relation(one, two, "R", "2026-01-01", null);
                for (int k = 0; k < 2; k++) {
                    relation(two, entity(pack, "A", "three-" + i + "-" + j + "-" + k), "R", "2026-01-01", null);
                }
            }
        }
        // 깊이 2 의 노드 둘을 깊이 3 의 노드 하나를 거쳐서도 잇는다. 깊이 2 의 노드끼리 바로 잇는 관계도 하나 둔다.
        relation(entity(pack, "T", "two-0-1"), entity(pack, "A", "three-0-0-0"), "R", "2026-01-01", null);
        String sibling = relation(entity(pack, "T", "two-0-0"), entity(pack, "T", "two-0-1"), "R", "2026-01-01", null);

        // 한도 5: 깊이 1 까지 셋(넘지 않음), 깊이 2 까지 아홉(넘음). 깊이 3 을 청해도 깊이 2 에서 멈춘다.
        RelationMap stopped = query.relationMap(center.id(), date("2026-03-01"), 3, 5);

        assertThat(stopped.reachedDepth()).isEqualTo(2);
        assertThat(stopped.totalNodes()).isEqualTo(9);
        assertThat(stopped.truncated()).isTrue();
        // 깊이 1 의 둘과 깊이 2 의 앞의 둘. 깊이 3 의 것은 유형이 앞서도 없다.
        assertThat(depths(stopped)).containsExactly(center.id() + "=0", id(pack, "one-0") + "=1", id(pack, "one-1") + "=1",
                id(pack, "two-0-0") + "=2", id(pack, "two-0-1") + "=2");
        // 간선: 가운데에서 둘, one-0 에서 담긴 둘, 그리고 담긴 깊이 2 의 노드끼리의 하나.
        assertThat(stopped.edges()).hasSize(5);
        assertThat(stopped.edges()).extracting(Relation::id).contains(sibling);
        // 깊이 2 로 청한 것과 같다.
        RelationMap two = query.relationMap(center.id(), date("2026-03-01"), 2, 5);
        assertThat(stopped.nodes()).isEqualTo(two.nodes());
        assertThat(stopped.edges()).isEqualTo(two.edges());
        // 한도가 넉넉하면 깊이 3 까지 간다: 스물하나.
        RelationMap whole = query.relationMap(center.id(), date("2026-03-01"), 3, 200);
        assertThat(whole.reachedDepth()).isEqualTo(3);
        assertThat(whole.totalNodes()).isEqualTo(21);
        // 끝까지 따라갔을 때의 앞의 다섯이 멈췄을 때 담긴 것과 같다.
        assertThat(whole.nodes().subList(0, 5)).isEqualTo(stopped.nodes());
    }

    private static String id(String pack, String key) {
        return new EntityRef(pack, "T", key).id();
    }

    private static List<String> depths(RelationMap map) {
        return map.nodes().stream().map(node -> node.entityId() + "=" + node.depth()).toList();
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
