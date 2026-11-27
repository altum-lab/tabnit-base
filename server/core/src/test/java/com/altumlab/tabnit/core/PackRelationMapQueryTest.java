package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * A-20 수용 기준 (core 쪽): 가운데 개체 없이 Pack 의 지도를 기준 날짜로 연다. 이어진 개체가 한도를 넘으면 유형별
 * 묶음만 준다. 유형마다의 수를 더한 것이 전체 수와 같다. 순서가 정해져 있고 두 번 물은 답이 같다 (D2 AD-419).
 *
 * <p>모든 이름과 값은 지어낸 것이다. 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다.
 */
class PackRelationMapQueryTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Provenance SRC_1 = new Provenance("src", 1);

    private static final LocalDate DAY = LocalDate.parse("2026-06-01");

    private final RelationStore store = new RelationStore(CoreTestDatabase.dataSource());

    private final PackRelationMapQuery query = new PackRelationMapQuery(CoreTestDatabase.dataSource());

    @Test
    void 개체가_하나도_없는_Pack_은_빈_지도다() {
        PackRelationMap map = consistent(query.packRelationMap("pm-nothing", DAY, 200));

        assertThat(map.connectedEntities()).isZero();
        assertThat(map.isolatedEntities()).isZero();
        assertThat(map.relations()).isZero();
        assertThat(map.grouped()).isFalse();
        assertThat(map.typeGroups()).isEmpty();
        assertThat(map.relationGroups()).isEmpty();
        assertThat(map.nodes()).isEmpty();
        assertThat(map.edges()).isEmpty();
    }

    @Test
    void 관계가_없는_개체는_지도에_넣지_않고_수만_센다() {
        String p = "pm-isolated";
        entity(p, "T", "alone-1");
        entity(p, "U", "alone-2");
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        relation(a, b, "R", "2026-01-01", null);

        PackRelationMap map = consistent(query.packRelationMap(p, DAY, 200));

        assertThat(map.connectedEntities()).isEqualTo(2);
        assertThat(map.isolatedEntities()).isEqualTo(2);
        assertThat(map.nodes()).extracting(PackRelationMap.Node::entityId).containsExactly(a.id(), b.id());
    }

    @Test
    void 한도_안이면_개체와_관계가_정해진_순서로_나오고_두_번_물은_답이_같다() {
        String p = "pm-order";
        // 넣는 순서는 일부러 섞는다.
        EntityRef z = entity(p, "B-type", "z");
        EntityRef y = entity(p, "A-type", "y");
        EntityRef x = entity(p, "B-type", "x");
        EntityRef w = entity(p, "A-type", "w");
        String zy = relation(z, y, "S", "2026-01-01", null);
        String xw = relation(x, w, "R", "2026-01-01", null);
        String wy = relation(w, y, "R", "2026-01-01", null);
        String xy = relation(x, y, "R", "2026-01-01", null);

        PackRelationMap map = consistent(query.packRelationMap(p, DAY, 200));

        // 노드: 유형, 식별자 순.
        assertThat(map.nodes()).extracting(PackRelationMap.Node::entityId, PackRelationMap.Node::entityType)
            .containsExactly(tuple(w.id(), "A-type"), tuple(y.id(), "A-type"), tuple(x.id(), "B-type"), tuple(z.id(), "B-type"));
        // 간선: 관계 유형, 출발 개체, 도착 개체, 식별자 순.
        assertThat(map.edges()).extracting(Relation::id).containsExactly(wy, xw, xy, zy);
        assertThat(map.grouped()).isFalse();
        assertThat(map.typeGroups()).extracting(PackRelationMap.TypeGroup::entityType, PackRelationMap.TypeGroup::entityCount)
            .containsExactly(tuple("A-type", 2), tuple("B-type", 2));
        assertThat(map.relationGroups())
            .extracting(PackRelationMap.RelationGroup::sourceEntityType, PackRelationMap.RelationGroup::relationType,
                    PackRelationMap.RelationGroup::targetEntityType, PackRelationMap.RelationGroup::relationCount)
            .containsExactly(tuple("A-type", "R", "A-type", 1), tuple("B-type", "R", "A-type", 2),
                    tuple("B-type", "S", "A-type", 1));
        assertThat(map.relations()).isEqualTo(4);
        assertThat(query.packRelationMap(p, DAY, 200)).isEqualTo(map);
    }

    @Test
    void 유효기간의_경계에서_시작일은_넣고_종료일은_뺀다() {
        String p = "pm-boundary";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        EntityRef c = entity(p, "T", "c");
        // d 는 끝나는 관계의 출발 쪽으로만 닿는다: 종료일 당일에 지도에서 빠져야 한다.
        EntityRef d = entity(p, "U", "d");
        String ab = relation(a, b, "R", "2026-03-01", "2026-06-01");
        String ac = relation(a, c, "R", "2026-06-01", null);
        String db = relation(d, b, "R", "2026-03-01", "2026-06-01");

        // 시작일 전날: 아무것도 유효하지 않다. 개체 넷은 모두 외톨이다.
        PackRelationMap before = consistent(query.packRelationMap(p, LocalDate.parse("2026-02-28"), 200));
        assertThat(before.connectedEntities()).isZero();
        assertThat(before.relations()).isZero();
        assertThat(before.isolatedEntities()).isEqualTo(4);
        // 시작일 당일과 종료일 전날: 앞의 두 관계.
        for (String day : new String[] { "2026-03-01", "2026-05-31" }) {
            PackRelationMap map = consistent(query.packRelationMap(p, LocalDate.parse(day), 200));
            assertThat(map.edges()).as(day).extracting(Relation::id).containsExactly(ab, db);
            assertThat(map.nodes()).as(day).extracting(PackRelationMap.Node::entityId).containsExactly(a.id(), b.id(), d.id());
            assertThat(map.isolatedEntities()).as(day).isEqualTo(1);
            assertThat(map.typeGroups()).as(day)
                .extracting(PackRelationMap.TypeGroup::entityType, PackRelationMap.TypeGroup::entityCount)
                .containsExactly(tuple("T", 2), tuple("U", 1));
            assertThat(map.relationGroups()).as(day)
                .extracting(PackRelationMap.RelationGroup::sourceEntityType, PackRelationMap.RelationGroup::relationCount)
                .containsExactly(tuple("T", 1), tuple("U", 1));
        }
        // 종료일 당일: 앞의 두 관계는 끝났고 뒤의 관계가 시작됐다. b 와 d 는 외톨이가 된다.
        PackRelationMap endDay = consistent(query.packRelationMap(p, LocalDate.parse("2026-06-01"), 200));
        assertThat(endDay.edges()).extracting(Relation::id).containsExactly(ac);
        assertThat(endDay.nodes()).extracting(PackRelationMap.Node::entityId).containsExactly(a.id(), c.id());
        assertThat(endDay.isolatedEntities()).isEqualTo(2);
        assertThat(endDay.typeGroups()).extracting(PackRelationMap.TypeGroup::entityType, PackRelationMap.TypeGroup::entityCount)
            .containsExactly(tuple("T", 2));
        assertThat(endDay.relations()).isEqualTo(1);
        // 먼 뒷날: 종료일이 없는 관계는 계속 유효하다.
        assertThat(consistent(query.packRelationMap(p, LocalDate.parse("2030-01-01"), 200)).edges()).extracting(Relation::id)
            .containsExactly(ac);

        // 묶인 답도 날짜마다 같은 수다 (한도 1: 받는 가장 작은 값).
        PackRelationMap groupedLastDay = consistent(query.packRelationMap(p, LocalDate.parse("2026-05-31"), 1));
        assertThat(groupedLastDay.grouped()).isTrue();
        assertThat(groupedLastDay.connectedEntities()).isEqualTo(3);
        assertThat(groupedLastDay.relations()).isEqualTo(2);
        PackRelationMap groupedEndDay = consistent(query.packRelationMap(p, LocalDate.parse("2026-06-01"), 1));
        assertThat(groupedEndDay.grouped()).isTrue();
        assertThat(groupedEndDay.connectedEntities()).isEqualTo(2);
        assertThat(groupedEndDay.relations()).isEqualTo(1);
        assertThat(groupedEndDay.typeGroups())
            .extracting(PackRelationMap.TypeGroup::entityType, PackRelationMap.TypeGroup::entityCount)
            .containsExactly(tuple("T", 2));
    }

    @Test
    void 출처만_다른_같은_관계는_간선_둘이고_식별자_순이며_개체는_한_번만_센다() {
        String p = "pm-twice";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        String second = store.saveRelation(new NewRelation(a, b, "R",
                new ValidityPeriod(LocalDate.parse("2026-01-01"), null), new Provenance("src-z", 1)), RECORDED_AT);
        String first = store.saveRelation(new NewRelation(a, b, "R",
                new ValidityPeriod(LocalDate.parse("2026-01-01"), null), new Provenance("src-a", 1)), RECORDED_AT);

        PackRelationMap map = consistent(query.packRelationMap(p, DAY, 200));

        // 관계 유형, 출발, 도착이 같다: 마지막 기준인 관계의 식별자로 차례가 정해진다.
        assertThat(map.edges()).extracting(Relation::id)
            .containsExactlyElementsOf(java.util.stream.Stream.of(first, second).sorted(CodePointOrder.STRINGS).toList());
        assertThat(map.relations()).isEqualTo(2);
        assertThat(map.connectedEntities()).isEqualTo(2);
    }

    @Test
    void 두_유형_사이의_방향이_다른_관계는_따로_묶인다() {
        String p = "pm-both-ways";
        EntityRef a = entity(p, "A", "a");
        EntityRef b = entity(p, "B", "b");
        relation(a, b, "R", "2026-01-01", null);
        relation(b, a, "R", "2026-01-01", null);

        PackRelationMap map = consistent(query.packRelationMap(p, DAY, 200));

        assertThat(map.relationGroups())
            .extracting(PackRelationMap.RelationGroup::sourceEntityType, PackRelationMap.RelationGroup::relationType,
                    PackRelationMap.RelationGroup::targetEntityType, PackRelationMap.RelationGroup::relationCount)
            .containsExactly(tuple("A", "R", "B", 1), tuple("B", "R", "A", 1));
    }

    @Test
    void 이어진_개체가_한도를_넘으면_유형별_묶음만_주고_수를_더한_것이_전체와_같다() {
        String p = "pm-grouped";
        EntityRef hub = entity(p, "Hub", "h");
        for (int i = 0; i < 7; i++) {
            relation(hub, entity(p, "Leaf", "l" + i), "has", "2026-01-01", null);
        }
        for (int i = 0; i < 3; i++) {
            relation(entity(p, "Other", "o" + i), hub, "points", "2026-01-01", null);
        }
        // 끝난 관계와 그 끝의 개체는 세지 않는다.
        relation(hub, entity(p, "Leaf", "ended"), "has", "2025-01-01", "2026-01-01");

        PackRelationMap map = consistent(query.packRelationMap(p, DAY, 10));

        assertThat(map.grouped()).isTrue();
        assertThat(map.nodes()).isEmpty();
        assertThat(map.edges()).isEmpty();
        assertThat(map.connectedEntities()).isEqualTo(11);
        assertThat(map.isolatedEntities()).isEqualTo(1);
        assertThat(map.typeGroups()).extracting(PackRelationMap.TypeGroup::entityType, PackRelationMap.TypeGroup::entityCount)
            .containsExactly(tuple("Hub", 1), tuple("Leaf", 7), tuple("Other", 3));
        assertThat(map.typeGroups().stream().mapToInt(PackRelationMap.TypeGroup::entityCount).sum())
            .isEqualTo(map.connectedEntities());
        assertThat(map.relationGroups())
            .extracting(PackRelationMap.RelationGroup::sourceEntityType, PackRelationMap.RelationGroup::relationType,
                    PackRelationMap.RelationGroup::targetEntityType, PackRelationMap.RelationGroup::relationCount)
            .containsExactly(tuple("Hub", "has", "Leaf", 7), tuple("Other", "points", "Hub", 3));
        assertThat(map.relationGroups().stream().mapToInt(PackRelationMap.RelationGroup::relationCount).sum())
            .isEqualTo(map.relations());
        assertThat(query.packRelationMap(p, DAY, 10)).isEqualTo(map);
        // 한도와 꼭 같으면 묶지 않는다.
        PackRelationMap exact = consistent(query.packRelationMap(p, DAY, 11));
        assertThat(exact.grouped()).isFalse();
        assertThat(exact.nodes()).hasSize(11);
        assertThat(exact.edges()).hasSize(10);
        assertThat(exact.typeGroups()).isEqualTo(map.typeGroups());
    }

    @Test
    void 같은_두_개체_사이의_관계가_여럿이고_돌아오는_관계가_있어도_개체는_한_번만_센다() {
        String p = "pm-cycle";
        EntityRef a = entity(p, "T", "a");
        EntityRef b = entity(p, "T", "b");
        EntityRef c = entity(p, "T", "c");
        relation(a, b, "R", "2026-01-01", null);
        relation(b, a, "R", "2026-01-01", null);
        relation(a, b, "S", "2026-01-01", null);
        relation(b, c, "R", "2026-01-01", null);
        relation(c, a, "R", "2026-01-01", null);

        PackRelationMap map = consistent(query.packRelationMap(p, DAY, 200));

        assertThat(map.connectedEntities()).isEqualTo(3);
        assertThat(map.nodes()).hasSize(3);
        assertThat(map.relations()).isEqualTo(5);
        assertThat(map.edges()).hasSize(5);
    }

    @Test
    void 다른_Pack_의_것은_섞이지_않는다() {
        EntityRef a = entity("pm-mine", "T", "a");
        relation(a, entity("pm-mine", "T", "b"), "R", "2026-01-01", null);
        EntityRef other = entity("pm-theirs", "T", "a");
        relation(other, entity("pm-theirs", "T", "b"), "R", "2026-01-01", null);
        relation(other, entity("pm-theirs", "T", "c"), "R", "2026-01-01", null);

        PackRelationMap map = consistent(query.packRelationMap("pm-mine", DAY, 200));

        assertThat(map.connectedEntities()).isEqualTo(2);
        assertThat(map.nodes()).allMatch(node -> node.entityId().startsWith("pm-mine|"));
        assertThat(map.edges()).allMatch(edge -> edge.packId().equals("pm-mine"));
    }

    @Test
    void 틀린_입력은_거부한다() {
        assertThatThrownBy(() -> query.packRelationMap("pm-bad", DAY, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("한도");
        assertThatThrownBy(() -> query.packRelationMap("", DAY, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.packRelationMap("pm\u0000bad", DAY, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.packRelationMap(null, DAY, 10)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.packRelationMap("pm-bad", null, 10)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.packRelationMap("pm-bad", LocalDate.of(10000, 1, 1), 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 어느 답에서나 지켜져야 하는 것: 묶음의 합이 전체와 같고, 묶이지 않았으면 노드와 간선의 수와도 같다. */
    private static PackRelationMap consistent(PackRelationMap map) {
        assertThat(map.typeGroups().stream().mapToInt(PackRelationMap.TypeGroup::entityCount).sum())
            .isEqualTo(map.connectedEntities());
        assertThat(map.relationGroups().stream().mapToInt(PackRelationMap.RelationGroup::relationCount).sum())
            .isEqualTo(map.relations());
        assertThat(map.isolatedEntities()).isGreaterThanOrEqualTo(0);
        if (map.grouped()) {
            assertThat(map.nodes()).isEmpty();
            assertThat(map.edges()).isEmpty();
        }
        else {
            assertThat(map.nodes()).hasSize(map.connectedEntities());
            assertThat(map.edges()).hasSize(map.relations());
        }
        return map;
    }

    private EntityRef entity(String pack, String type, String key) {
        EntityRef ref = new EntityRef(pack, type, key);
        store.saveEntity(ref, RECORDED_AT);
        return ref;
    }

    private String relation(EntityRef source, EntityRef target, String type, String from, String to) {
        return store.saveRelation(new NewRelation(source, target, type,
                new ValidityPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to)), SRC_1), RECORDED_AT);
    }
}
