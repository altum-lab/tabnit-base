package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A-15 수용 기준: Pack 하나의 개체, 그 날짜에 유효한 관계와 속성 값을 기준 날짜를 받아 정해진 순서로 읽는다
 * (D2 AD-391). 경계 날짜: 시작일은 포함, 종료일은 제외.
 *
 * <p>모든 이름과 값은 지어낸 것이다. 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다.
 */
class PackDataQueryTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Provenance SRC_1 = new Provenance("src", 1);

    private final RelationStore relations = new RelationStore(CoreTestDatabase.dataSource());

    private final AttributeValueStore values = new AttributeValueStore(CoreTestDatabase.dataSource());

    private final PackDataQuery query = new PackDataQuery(CoreTestDatabase.dataSource());

    @Test
    void Pack_의_개체_전부와_그_날짜에_유효한_관계와_속성_값을_정해진_순서로_읽는다() {
        String p = "pd-all";
        // 넣는 차례를 섞는다. 식별자의 글자 코드 순으로 나와야 한다 (K-10 이 K-2 보다 앞선다).
        EntityRef b = entity(p, "TypeB", "K-1");
        EntityRef a2 = entity(p, "TypeA", "K-2");
        EntityRef a10 = entity(p, "TypeA", "K-10");
        // 관계도 값도 없는 개체도 나온다: 개체에는 유효기간이 없다.
        EntityRef alone = entity(p, "TypeA", "가");
        String r2 = relation(a2, b, "uses", "2026-01-01", null);
        String r1 = relation(a10, b, "uses", "2026-01-01", null);
        String v3 = value(b, "name", "비", "2026-01-01", null);
        String v1 = value(a10, "name", "열", "2026-01-01", null);
        String v2 = value(a10, "count", "3", "2026-01-01", null);
        // 속성의 이름이 다른 이름의 앞부분이다. 이름의 순(n, nb)은 값의 식별자의 순(nb, n)과 다르다 (D5 3절, D2 AD-370).
        String vNb = value(alone, "nb", "둘", "2026-01-01", null);
        String vN = value(alone, "n", "하나", "2026-01-01", null);

        PackData data = query.packDataAsOf(p, date("2026-06-01"), 1000);

        assertThat(data.complete()).isTrue();
        assertThat(data.entities()).containsExactly(a10, a2, alone, b);
        assertThat(data.relations()).extracting(Relation::id).containsExactly(r1, r2);
        // 속성 값은 개체의 식별자, 속성의 이름, 시작일 순이다 (다른 조회와 같다).
        assertThat(data.values()).extracting(AttributeValue::id).containsExactly(v2, v1, vN, vNb, v3);
        assertThat(data.values()).extracting(AttributeValue::value).containsExactly("3", "열", "하나", "둘", "비");
        assertThat(CodePointOrder.compare(vNb, vN)).as("식별자의 순은 이름의 순과 달라야 이 시험에 뜻이 있다").isNegative();
        assertThat(data.entityCount()).isEqualTo(4);
        assertThat(data.relationCount()).isEqualTo(2);
        assertThat(data.valueCount()).isEqualTo(5);
        // 두 번 읽어도 같다.
        assertThat(query.packDataAsOf(p, date("2026-06-01"), 1000)).isEqualTo(data);
    }

    @Test
    void 시작일_당일부터_종료일_전날까지만_유효하다() {
        String p = "pd-edges";
        EntityRef a = entity(p, "TypeA", "K-1");
        EntityRef b = entity(p, "TypeB", "K-1");
        String relation = relation(a, b, "uses", "2026-03-01", "2026-06-01");
        String old = value(a, "name", "옛 이름", "2026-03-01", "2026-06-01");
        String now = value(a, "name", "새 이름", "2026-06-01", null);

        // 시작일 전날: 관계도 값도 없다. 개체는 있다.
        PackData before = query.packDataAsOf(p, date("2026-02-28"), 1000);
        assertThat(before.entities()).containsExactly(a, b);
        assertThat(before.relations()).isEmpty();
        assertThat(before.values()).isEmpty();
        // 시작일 당일과 종료일 전날: 관계와 옛 값.
        for (String day : List.of("2026-03-01", "2026-05-31")) {
            PackData during = query.packDataAsOf(p, date(day), 1000);
            assertThat(during.relations()).as(day).extracting(Relation::id).containsExactly(relation);
            assertThat(during.values()).as(day).extracting(AttributeValue::id).containsExactly(old);
        }
        // 종료일 당일: 관계는 끝났고 값은 새 것이다.
        PackData after = query.packDataAsOf(p, date("2026-06-01"), 1000);
        assertThat(after.relations()).isEmpty();
        assertThat(after.values()).extracting(AttributeValue::id).containsExactly(now);
        // 세는 것도 같은 경계를 쓴다: 날짜마다 수가 목록과 같고, 한도를 그 합에 딱 맞추면 모두 담긴다.
        for (String day : List.of("2026-02-28", "2026-03-01", "2026-05-31", "2026-06-01")) {
            PackData counted = query.packDataAsOf(p, date(day), 1000);
            assertThat(counted.entityCount()).as(day).isEqualTo(2);
            assertThat(counted.relationCount()).as(day).isEqualTo(counted.relations().size());
            assertThat(counted.valueCount()).as(day).isEqualTo(counted.values().size());
            int rows = 2 + counted.relations().size() + counted.values().size();
            assertThat(query.packDataAsOf(p, date(day), rows).complete()).as(day).isTrue();
            if (rows > 1) {
                assertThat(query.packDataAsOf(p, date(day), rows - 1).complete()).as(day).isFalse();
            }
        }
        assertThat(query.packDataAsOf(p, date("2026-06-01"), 1000).relationCount()).isZero();
        assertThat(query.packDataAsOf(p, date("2026-05-31"), 1000).relationCount()).isEqualTo(1);
        // 먼 미래: 종료일이 없는 값은 계속 유효하다.
        assertThat(query.packDataAsOf(p, date("9999-12-31"), 1000).values()).extracting(AttributeValue::id)
            .containsExactly(now);
    }

    @Test
    void 다른_Pack_의_자료는_섞이지_않고_없는_Pack_은_비어_있다() {
        EntityRef mine = entity("pd-mine", "TypeA", "K-1");
        EntityRef mineToo = entity("pd-mine", "TypeB", "K-1");
        String mineRelation = relation(mine, mineToo, "uses", "2026-01-01", null);
        value(mine, "name", "내 것", "2026-01-01", null);
        EntityRef other = entity("pd-other", "TypeA", "K-1");
        EntityRef otherToo = entity("pd-other", "TypeB", "K-1");
        relation(other, otherToo, "uses", "2026-01-01", null);
        value(other, "name", "남의 것", "2026-01-01", null);

        PackData data = query.packDataAsOf("pd-mine", date("2026-06-01"), 1000);

        assertThat(data.entities()).containsExactly(mine, mineToo);
        assertThat(data.relations()).extracting(Relation::id, Relation::packId)
            .containsExactly(org.assertj.core.api.Assertions.tuple(mineRelation, "pd-mine"));
        assertThat(data.values()).extracting(AttributeValue::value).containsExactly("내 것");
        PackData none = query.packDataAsOf("pd-없는-pack", date("2026-06-01"), 1000);
        assertThat(none.complete()).isTrue();
        assertThat(none.entities()).isEmpty();
        assertThat(none.relations()).isEmpty();
        assertThat(none.values()).isEmpty();
        // Pack 식별자가 다른 Pack 의 앞부분이어도 섞이지 않는다.
        EntityRef near = entity("pd-mine-2", "TypeA", "K-1");
        EntityRef nearToo = entity("pd-mine-2", "TypeB", "K-1");
        relation(near, nearToo, "uses", "2026-01-01", null);
        value(near, "name", "이름이 비슷한 Pack 의 것", "2026-01-01", null);
        PackData again = query.packDataAsOf("pd-mine", date("2026-06-01"), 1000);
        assertThat(again).isEqualTo(data);
        // 세는 것도 그 Pack 의 것만 센다.
        assertThat(again.entityCount()).isEqualTo(2);
        assertThat(again.relationCount()).isEqualTo(1);
        assertThat(again.valueCount()).isEqualTo(1);
    }

    @Test
    void 한도를_넘으면_자료를_주지_않고_수만_알린다() {
        String p = "pd-limit";
        EntityRef hub = entity(p, "TypeA", "hub");
        for (int i = 0; i < 6; i++) {
            EntityRef leaf = entity(p, "TypeB", "L-" + i);
            relation(hub, leaf, "uses", "2026-01-01", null);
            value(leaf, "name", "잎 " + i, "2026-01-01", null);
        }
        // 개체 7, 관계 6, 값 6: 모두 19 행.
        PackData exact = query.packDataAsOf(p, date("2026-06-01"), 19);
        assertThat(exact.complete()).isTrue();
        assertThat(exact.entities()).hasSize(7);

        PackData over = query.packDataAsOf(p, date("2026-06-01"), 18);

        // 일부만 주면 없는 것을 없다고 잘못 읽게 된다. 아무것도 주지 않는다.
        assertThat(over.complete()).isFalse();
        assertThat(over.entities()).isEmpty();
        assertThat(over.relations()).isEmpty();
        assertThat(over.values()).isEmpty();
        assertThat(over.entityCount()).isEqualTo(7);
        assertThat(over.relationCount()).isEqualTo(6);
        assertThat(over.valueCount()).isEqualTo(6);
        // 아직 시작하지 않은 관계와 값은 수에 들지 않는다: 그 날짜에 유효한 것만 센다.
        PackData early = query.packDataAsOf(p, date("2025-12-31"), 7);
        assertThat(early.complete()).isTrue();
        assertThat(early.entityCount()).isEqualTo(7);
        assertThat(early.relationCount()).isZero();
        assertThat(early.valueCount()).isZero();
        // 받는 가장 이른 날짜도 읽힌다.
        assertThat(query.packDataAsOf(p, LocalDate.of(1, 1, 1), 7).relations()).isEmpty();
    }

    @Test
    void 유형과_키에_구분_글자가_들어도_식별자의_글자_코드_순으로_읽는다() {
        String p = "pd-chars";
        // 유형의 이름이 다른 이름의 앞부분이다. 식별자에서는 구분 글자(|)가 숫자와 영어 글자보다 뒤라서 Type2 가 앞선다.
        EntityRef plain = entity(p, "Type", "K");
        EntityRef longer = entity(p, "Type2", "K");
        EntityRef bar = entity(p, "Type", "a|b");
        EntityRef slash = entity(p, "Type", "a\\b");
        EntityRef other = entity(p, "유형 가", "키 % _ ' \" 😀");
        String toLonger = relation(plain, longer, "uses", "2026-01-01", null);
        String toBar = relation(bar, slash, "uses|it", "2026-01-01", null);
        value(bar, "name|x", "값 | \\", "2026-01-01", null);

        PackData data = query.packDataAsOf(p, date("2026-06-01"), 1000);

        List<EntityRef> expected = new java.util.ArrayList<>(List.of(plain, longer, bar, slash, other));
        expected.sort(java.util.Comparator.comparing(EntityRef::id, CodePointOrder.STRINGS));
        assertThat(data.entities()).containsExactlyElementsOf(expected);
        assertThat(data.entities().indexOf(longer)).isLessThan(data.entities().indexOf(plain));
        List<String> relationIds = new java.util.ArrayList<>(List.of(toLonger, toBar));
        relationIds.sort(CodePointOrder.STRINGS);
        assertThat(data.relations()).extracting(Relation::id).containsExactlyElementsOf(relationIds);
        assertThat(data.values()).singleElement().satisfies(found -> {
            assertThat(found.entityId()).isEqualTo(bar.id());
            assertThat(found.attributeName()).isEqualTo("name|x");
            assertThat(found.value()).isEqualTo("값 | \\");
        });
    }

    @Test
    void 자료가_천_행쯤이어도_순서와_수가_맞고_한도의_경계가_지켜진다() {
        String p = "pd-big";
        // 행마다 따로 넣어 느리다. 한 번에 받는 묶음(수백 행)을 넘기는 크기면 된다.
        int leaves = 400;
        EntityRef hub = entity(p, "TypeA", "hub");
        List<String> relationIds = new java.util.ArrayList<>();
        for (int i = 0; i < leaves; i++) {
            // 넣는 차례를 섞는다.
            int n = (i * 7) % leaves;
            EntityRef leaf = entity(p, "TypeB", "L-" + n);
            relationIds.add(relation(hub, leaf, "uses", "2026-01-01", n % 2 == 0 ? null : "2026-03-01"));
            value(leaf, "name", "잎 " + n, "2026-01-01", null);
        }
        int rows = (leaves + 1) + leaves / 2 + leaves;

        PackData data = query.packDataAsOf(p, date("2026-06-01"), rows);

        assertThat(data.complete()).isTrue();
        assertThat(data.entities()).hasSize(leaves + 1).isSortedAccordingTo(
                java.util.Comparator.comparing(EntityRef::id, CodePointOrder.STRINGS));
        // 끝난 관계(절반)는 빠진다.
        assertThat(data.relations()).hasSize(leaves / 2);
        assertThat(data.relations()).extracting(Relation::id).isSortedAccordingTo(CodePointOrder.STRINGS)
            .isSubsetOf(relationIds);
        assertThat(data.values()).hasSize(leaves).extracting(AttributeValue::entityId)
            .isSortedAccordingTo(CodePointOrder.STRINGS);
        assertThat(query.packDataAsOf(p, date("2026-06-01"), rows - 1).complete()).isFalse();
        assertThat(query.packDataAsOf(p, date("2026-06-01"), rows)).isEqualTo(data);
    }

    @Test
    void 기준_날짜와_Pack_식별자와_한도가_틀리면_받지_않는다() {
        assertThatThrownBy(() -> query.packDataAsOf(null, date("2026-01-01"), 10)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.packDataAsOf("pd-args", null, 10)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.packDataAsOf("", date("2026-01-01"), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.packDataAsOf("pd-\u0000", date("2026-01-01"), 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.packDataAsOf("pd-args", LocalDate.of(10000, 1, 1), 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.packDataAsOf("pd-args", date("2026-01-01"), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.packDataAsOf("pd-args", date("2026-01-01"), -1))
            .isInstanceOf(IllegalArgumentException.class);
        // 빈 Pack 은 한도 1 로도 읽힌다.
        assertThat(query.packDataAsOf("pd-args", date("2026-01-01"), 1).complete()).isTrue();
    }

    private EntityRef entity(String pack, String type, String key) {
        EntityRef ref = new EntityRef(pack, type, key);
        relations.saveEntity(ref, RECORDED_AT);
        return ref;
    }

    private String relation(EntityRef source, EntityRef target, String type, String from, String to) {
        return relations.saveRelation(new NewRelation(source, target, type, period(from, to), SRC_1), RECORDED_AT);
    }

    private String value(EntityRef entity, String name, String value, String from, String to) {
        return values.saveAttributeValue(new NewAttributeValue(entity, name, value, period(from, to), SRC_1), RECORDED_AT);
    }

    private static ValidityPeriod period(String from, String to) {
        return new ValidityPeriod(date(from), to == null ? null : date(to));
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}
