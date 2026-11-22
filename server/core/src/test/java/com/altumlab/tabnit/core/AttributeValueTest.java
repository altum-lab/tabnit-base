package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A-13 수용 기준: 속성 값이 유효기간과 출처와 함께 저장되고, 기준 날짜를 주면 그 날짜에 유효한 값만 돌려준다.
 * 값이 없으면 값 없음으로 답한다. 열려 있는 관계와 속성 값에 종료일을 넣어 끝낼 수 있다. 경계 날짜 시험 통과.
 *
 * <p>특정 Pack 과 무관한 지어낸 값으로 본다 (D5 3절, D2 AD-39, AD-53 ~ AD-56, AD-162, AD-163).
 * 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다.
 */
class AttributeValueTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Provenance SRC_1 = new Provenance("src", 1);

    private final RelationStore relations = new RelationStore(CoreTestDatabase.dataSource());

    private final AttributeValueStore store = new AttributeValueStore(CoreTestDatabase.dataSource());

    private final AttributeValueQuery query = new AttributeValueQuery(CoreTestDatabase.dataSource());

    // ── 저장과 시점 조회 ──────────────────────────────────────────

    @Test
    void 저장한_속성_값을_유효기간과_출처와_함께_돌려준다() {
        EntityRef e = entity("av-basic", "a");

        String id = store.saveAttributeValue(value(e, "name", "첫 이름", "2026-03-01", null), RECORDED_AT);

        assertThat(id).isEqualTo("av-basic|T|a|name|2026-03-01");
        assertThat(query.valuesAsOf(e.id(), LocalDate.parse("2026-03-01")))
            .containsExactly(new AttributeValue(id, "av-basic", e.id(), "name", "첫 이름",
                    new ValidityPeriod(LocalDate.parse("2026-03-01"), null), SRC_1, RECORDED_AT));
    }

    @Test
    void 시작일은_포함하고_종료일은_제외한다() {
        EntityRef e = entity("av-boundary", "a");
        store.saveAttributeValue(value(e, "name", "값", "2026-03-01", "2026-06-01"), RECORDED_AT);

        // 시작일 전날, 시작일 당일, 종료일 전날, 종료일 당일.
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-02-28"))).isEmpty();
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-03-01"))).map(AttributeValue::value)
            .contains("값");
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-05-31"))).map(AttributeValue::value)
            .contains("값");
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-06-01"))).isEmpty();
    }

    @Test
    void 기준_날짜에_유효한_값이_없으면_값_없음이고_앞의_값을_끌어다_쓰지_않는다() {
        EntityRef e = entity("av-none", "a");
        store.saveAttributeValue(value(e, "name", "앞의 값", "2026-01-01", "2026-03-01"), RECORDED_AT);
        store.saveAttributeValue(value(e, "name", "뒤의 값", "2026-06-01", null), RECORDED_AT);

        // 두 값 사이의 빈 기간.
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-04-01"))).isEmpty();
        assertThat(query.valuesAsOf(e.id(), LocalDate.parse("2026-04-01"))).isEmpty();
        // 속성 값이 하나도 없는 개체, 없는 속성.
        assertThat(query.valuesAsOf(entity("av-none", "empty").id(), LocalDate.parse("2026-04-01"))).isEmpty();
        assertThat(query.valueAsOf(e.id(), "no-such-attribute", LocalDate.parse("2026-06-01"))).isEmpty();
    }

    @Test
    void 값이_바뀌어_맞닿은_두_값은_경계에서_하나씩만_유효하다() {
        EntityRef e = entity("av-touch", "a");
        store.saveAttributeValue(value(e, "name", "앞", "2026-01-01", "2026-03-01"), RECORDED_AT);
        store.saveAttributeValue(value(e, "name", "뒤", "2026-03-01", null), RECORDED_AT);

        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-02-28"))).map(AttributeValue::value)
            .contains("앞");
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-03-01"))).map(AttributeValue::value)
            .contains("뒤");
        assertThat(query.valuesAsOf(e.id(), LocalDate.parse("2026-03-01"))).hasSize(1);
    }

    @Test
    void 속성_여럿은_이름의_글자_코드_순으로_돌려주고_두_번_조회해도_같다() {
        EntityRef e = entity("av-order", "a");
        for (String name : List.of("a", "가", "B", "～", "😀")) {
            store.saveAttributeValue(value(e, name, "v-" + name, "2026-01-01", null), RECORDED_AT);
        }

        List<AttributeValue> first = query.valuesAsOf(e.id(), LocalDate.parse("2026-01-01"));

        // 글자 코드 순: 대문자, 소문자, 한글, U+FF5E, 기본 평면 밖의 글자.
        assertThat(first).extracting(AttributeValue::attributeName)
            .containsExactly("B", "a", "가", "～", "😀");
        assertThat(query.valuesAsOf(e.id(), LocalDate.parse("2026-01-01"))).isEqualTo(first);
    }

    @Test
    void 다른_개체와_다른_Pack의_값은_섞이지_않는다() {
        EntityRef here = entity("av-here", "a");
        EntityRef sibling = entity("av-here", "b");
        EntityRef there = entity("av-there", "a");
        store.saveAttributeValue(value(here, "name", "여기", "2026-01-01", null), RECORDED_AT);
        store.saveAttributeValue(value(sibling, "name", "옆", "2026-01-01", null), RECORDED_AT);
        store.saveAttributeValue(value(there, "name", "저기", "2026-01-01", null), RECORDED_AT);

        assertThat(query.valuesAsOf(here.id(), LocalDate.parse("2026-01-01"))).extracting(AttributeValue::value)
            .containsExactly("여기");
    }

    // ── 다시 저장, 겹침 ───────────────────────────────────────────

    @Test
    void 같은_값을_다시_저장하면_그대로이고_행이_늘지_않는다() {
        EntityRef e = entity("av-twice", "a");
        NewAttributeValue v = value(e, "name", "값", "2026-01-01", null);
        String first = store.saveAttributeValue(v, RECORDED_AT);

        String second = store.saveAttributeValue(v, RECORDED_AT.plusSeconds(3600));

        assertThat(second).isEqualTo(first);
        List<AttributeValue> found = query.valuesAsOf(e.id(), LocalDate.parse("2026-01-01"));
        assertThat(found).hasSize(1);
        // 처음 기록한 때를 바꾸지 않는다.
        assertThat(found.get(0).recordedAt()).isEqualTo(RECORDED_AT);
    }

    @Test
    void 유효기간이_겹치는_값은_출처가_달라도_거부하고_무엇과_겹치는지_알린다() {
        EntityRef e = entity("av-overlap", "a");
        String existing = store.saveAttributeValue(value(e, "name", "앞", "2026-03-01", "2026-06-01"), RECORDED_AT);
        NewAttributeValue fromOtherSource = new NewAttributeValue(e, "name", "다른 자료의 값",
                new ValidityPeriod(LocalDate.parse("2026-05-31"), null), new Provenance("other", 7));

        assertThatThrownBy(() -> store.saveAttributeValue(fromOtherSource, RECORDED_AT))
            .isInstanceOf(AttributeValueRejectedException.class)
            .hasMessageContaining(existing);
        // 종료일 없는 값과도 겹친다.
        store.saveAttributeValue(value(e, "name", "열린 값", "2026-06-01", null), RECORDED_AT);
        assertThatThrownBy(() -> store.saveAttributeValue(value(e, "name", "먼 뒤", "2030-01-01", "2030-02-01"),
                RECORDED_AT)).isInstanceOf(AttributeValueRejectedException.class);
        // 저장된 것은 그대로다.
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-05-31"))).map(AttributeValue::value)
            .contains("앞");
    }

    @Test
    void 같은_시작일에_다른_값이나_다른_종료일이나_다른_출처로_저장하면_거부한다() {
        EntityRef e = entity("av-same-key", "a");
        store.saveAttributeValue(value(e, "name", "값", "2026-01-01", "2026-06-01"), RECORDED_AT);

        assertThatThrownBy(() -> store.saveAttributeValue(value(e, "name", "다른 값", "2026-01-01", "2026-06-01"),
                RECORDED_AT)).isInstanceOf(AttributeValueRejectedException.class);
        assertThatThrownBy(() -> store.saveAttributeValue(value(e, "name", "값", "2026-01-01", "2026-07-01"),
                RECORDED_AT)).isInstanceOf(AttributeValueRejectedException.class);
        assertThatThrownBy(() -> store.saveAttributeValue(new NewAttributeValue(e, "name", "값",
                new ValidityPeriod(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01")),
                new Provenance("src", 2)), RECORDED_AT)).isInstanceOf(AttributeValueRejectedException.class);
    }

    @Test
    void 맞닿는_기간과_다른_속성은_겹침이_아니다() {
        EntityRef e = entity("av-adjacent", "a");
        store.saveAttributeValue(value(e, "name", "가운데", "2026-03-01", "2026-06-01"), RECORDED_AT);

        assertThatCode(() -> {
            store.saveAttributeValue(value(e, "name", "앞", "2026-01-01", "2026-03-01"), RECORDED_AT);
            store.saveAttributeValue(value(e, "name", "뒤", "2026-06-01", null), RECORDED_AT);
            store.saveAttributeValue(value(e, "size", "3", "2026-03-01", null), RECORDED_AT);
        }).doesNotThrowAnyException();
    }

    // ── 잘못된 입력 ─────────────────────────────────────────────

    @Test
    void 없는_개체의_값은_저장할_수_없고_없는_개체는_조회할_수_없다() {
        EntityRef missing = new EntityRef("av-missing", "T", "nobody");

        assertThatThrownBy(() -> store.saveAttributeValue(value(missing, "name", "값", "2026-01-01", null), RECORDED_AT))
            .isInstanceOf(AttributeValueRejectedException.class)
            .hasMessageContaining(missing.id());
        assertThatThrownBy(() -> query.valuesAsOf(missing.id(), LocalDate.parse("2026-01-01")))
            .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> query.valueAsOf(missing.id(), "name", LocalDate.parse("2026-01-01")))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 값과_속성의_이름은_비어_있을_수_없고_끝없는_날짜는_받지_않는다() {
        EntityRef e = entity("av-invalid", "a");

        // 값 없음을 행으로 저장하지 않는다 (D2 AD-55).
        assertThatThrownBy(() -> value(e, "name", "", "2026-01-01", null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("값");
        assertThatThrownBy(() -> value(e, "", "값", "2026-01-01", null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("속성");
        assertThatThrownBy(() -> value(e, "name", "a\u0000b", "2026-01-01", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.saveAttributeValue(
                new NewAttributeValue(e, "name", "값", new ValidityPeriod(LocalDate.parse("2026-01-01"), LocalDate.MAX),
                        SRC_1),
                RECORDED_AT)).isInstanceOf(AttributeValueRejectedException.class)
            .hasMessageContaining("끝없는 날짜");
        // 빈칸만 있는 값은 값이다. 막지 않는다.
        assertThatCode(() -> store.saveAttributeValue(value(e, "blank", " ", "2026-01-01", null), RECORDED_AT))
            .doesNotThrowAnyException();
    }

    // ── 속성 값 끝내기 (D2 AD-162) ─────────────────────────────────

    @Test
    void 열려_있는_속성_값에_종료일을_넣어_끝내면_그날부터_값이_없다() {
        EntityRef e = entity("av-end", "a");
        String id = store.saveAttributeValue(value(e, "name", "값", "2026-01-01", null), RECORDED_AT);

        store.endAttributeValue(id, LocalDate.parse("2026-03-01"));

        // 종료일 전날에는 유효하고 종료일 당일에는 값이 없다.
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-02-28"))).isPresent();
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-03-01"))).isEmpty();
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-02-28")).orElseThrow().period())
            .isEqualTo(new ValidityPeriod(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-01")));
    }

    @Test
    void 앞_값을_끝낸_뒤에_새_값을_저장하면_두_값이_맞닿는다() {
        // 값이 바뀔 때 부르는 쪽이 하는 차례다 (D2 AD-56, AD-163).
        EntityRef e = entity("av-replace", "a");
        String before = store.saveAttributeValue(value(e, "name", "앞", "2026-01-01", null), RECORDED_AT);

        // 끝내지 않고 저장하면 겹친다.
        assertThatThrownBy(() -> store.saveAttributeValue(value(e, "name", "뒤", "2026-03-01", null), RECORDED_AT))
            .isInstanceOf(AttributeValueRejectedException.class);
        store.endAttributeValue(before, LocalDate.parse("2026-03-01"));
        store.saveAttributeValue(value(e, "name", "뒤", "2026-03-01", null), RECORDED_AT);

        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-02-28"))).map(AttributeValue::value)
            .contains("앞");
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-03-01"))).map(AttributeValue::value)
            .contains("뒤");
    }

    @Test
    void 같은_날짜로_다시_끝내면_그대로이고_다른_날짜로는_끝낼_수_없다() {
        EntityRef e = entity("av-end-twice", "a");
        String id = store.saveAttributeValue(value(e, "name", "값", "2026-01-01", null), RECORDED_AT);
        store.endAttributeValue(id, LocalDate.parse("2026-03-01"));

        assertThatCode(() -> store.endAttributeValue(id, LocalDate.parse("2026-03-01"))).doesNotThrowAnyException();
        // 더 이른 날짜로도, 더 늦은 날짜로도 바꾸지 않는다. 지금의 종료일을 알린다.
        for (String other : List.of("2026-02-01", "2026-04-01")) {
            assertThatThrownBy(() -> store.endAttributeValue(id, LocalDate.parse(other)))
                .isInstanceOf(AttributeValueRejectedException.class)
                .hasMessageContaining("2026-03-01");
        }
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2026-02-28")).orElseThrow().period().to())
            .isEqualTo(LocalDate.parse("2026-03-01"));
    }

    @Test
    void 종료일은_시작일보다_뒤여야_하고_없는_값은_끝낼_수_없다() {
        EntityRef e = entity("av-end-invalid", "a");
        String id = store.saveAttributeValue(value(e, "name", "값", "2026-03-01", null), RECORDED_AT);

        for (LocalDate bad : List.of(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-02-28"), LocalDate.MAX)) {
            assertThatThrownBy(() -> store.endAttributeValue(id, bad)).as(bad.toString())
                .isInstanceOf(AttributeValueRejectedException.class);
        }
        assertThatThrownBy(() -> store.endAttributeValue("av-end-invalid|T|a|name|1999-01-01", LocalDate.parse("2026-06-01")))
            .isInstanceOf(AttributeValueRejectedException.class)
            .hasMessageContaining("av-end-invalid|T|a|name|1999-01-01");
        // 거부된 뒤에도 열려 있다.
        assertThat(query.valueAsOf(e.id(), "name", LocalDate.parse("2030-01-01"))).isPresent();
    }

    // ── 관계 끝내기 (D2 AD-162) ───────────────────────────────────

    @Test
    void 열려_있는_관계에_종료일을_넣어_끝내면_그날부터_시점_조회에_나오지_않는다() {
        EntityRef a = entity("rel-end", "a");
        EntityRef b = entity("rel-end", "b");
        String id = relations.saveRelation(
                new NewRelation(a, b, "R", new ValidityPeriod(LocalDate.parse("2026-01-01"), null), SRC_1), RECORDED_AT);
        RelationQuery relationQuery = new RelationQuery(CoreTestDatabase.dataSource());

        relations.endRelation(id, LocalDate.parse("2026-03-01"));

        assertThat(relationQuery.relationsAsOf(a.id(), LocalDate.parse("2026-02-28"))).extracting(Relation::id)
            .containsExactly(id);
        assertThat(relationQuery.relationsAsOf(a.id(), LocalDate.parse("2026-03-01"))).isEmpty();
        assertThat(relationQuery.relationsAsOf(a.id(), LocalDate.parse("2026-02-28")).get(0).period().to())
            .isEqualTo(LocalDate.parse("2026-03-01"));
    }

    @Test
    void 관계도_같은_날짜로_다시_끝내면_그대로이고_다른_날짜와_틀린_날짜와_없는_관계는_거부한다() {
        EntityRef a = entity("rel-end-rules", "a");
        EntityRef b = entity("rel-end-rules", "b");
        String id = relations.saveRelation(
                new NewRelation(a, b, "R", new ValidityPeriod(LocalDate.parse("2026-03-01"), null), SRC_1), RECORDED_AT);

        for (LocalDate bad : List.of(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-02-28"), LocalDate.MAX)) {
            assertThatThrownBy(() -> relations.endRelation(id, bad)).as(bad.toString())
                .isInstanceOf(RelationRejectedException.class);
        }
        relations.endRelation(id, LocalDate.parse("2026-06-01"));
        assertThatCode(() -> relations.endRelation(id, LocalDate.parse("2026-06-01"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> relations.endRelation(id, LocalDate.parse("2026-07-01")))
            .isInstanceOf(RelationRejectedException.class)
            .hasMessageContaining("2026-06-01");
        assertThatThrownBy(() -> relations.endRelation("rel-end-rules|T|a|T|b|R|src|9|2026-03-01", LocalDate.parse("2026-06-01")))
            .isInstanceOf(RelationRejectedException.class)
            .hasMessageContaining("rel-end-rules|T|a|T|b|R|src|9|2026-03-01");
    }

    @Test
    void 처음부터_종료일이_있던_관계는_같은_날짜로만_끝낼_수_있다() {
        EntityRef a = entity("rel-end-closed", "a");
        EntityRef b = entity("rel-end-closed", "b");
        String id = relations.saveRelation(new NewRelation(a, b, "R",
                new ValidityPeriod(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01")), SRC_1), RECORDED_AT);

        assertThatCode(() -> relations.endRelation(id, LocalDate.parse("2026-06-01"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> relations.endRelation(id, LocalDate.parse("2026-03-01")))
            .isInstanceOf(RelationRejectedException.class);
    }

    @Test
    void 끝낸_관계의_뒤에_같은_관계를_다시_이으면_맞닿는다() {
        EntityRef a = entity("rel-end-again", "a");
        EntityRef b = entity("rel-end-again", "b");
        String first = relations.saveRelation(
                new NewRelation(a, b, "R", new ValidityPeriod(LocalDate.parse("2026-01-01"), null), SRC_1), RECORDED_AT);
        relations.endRelation(first, LocalDate.parse("2026-03-01"));

        // 종료일 당일부터 다시 시작한다. 종료일은 제외하므로 겹치지 않는다.
        assertThatCode(() -> relations.saveRelation(
                new NewRelation(a, b, "R", new ValidityPeriod(LocalDate.parse("2026-03-01"), null), SRC_1),
                RECORDED_AT)).doesNotThrowAnyException();
    }

    // ── 저장소가 담지 못하는 값, 트랜잭션 (검토에서 나온 것) ────────────────────

    @Test
    void 저장소가_담지_못하는_날짜와_시각은_저장소까지_가지_않고_거부한다() {
        EntityRef e = entity("av-range", "a");
        EntityRef b = entity("av-range", "b");
        String open = store.saveAttributeValue(value(e, "name", "값", "2026-01-01", null), RECORDED_AT);
        LocalDate tooLate = LocalDate.of(6_000_000, 1, 1);
        LocalDate tooEarly = LocalDate.of(-999_999, 1, 1);

        for (LocalDate bad : List.of(tooLate, tooEarly, LocalDate.of(10_000, 1, 1), LocalDate.of(0, 12, 31))) {
            assertThatThrownBy(() -> store.saveAttributeValue(
                    new NewAttributeValue(e, "far", "값", new ValidityPeriod(bad, null), SRC_1), RECORDED_AT))
                .as(bad.toString())
                .isInstanceOf(AttributeValueRejectedException.class)
                .hasMessageContaining("쓸 수 없는 날짜");
            assertThatThrownBy(() -> query.valuesAsOf(e.id(), bad)).as(bad.toString())
                .isInstanceOf(IllegalArgumentException.class);
        }
        for (LocalDate bad : List.of(tooLate, LocalDate.MAX.minusDays(1), LocalDate.MIN, LocalDate.of(10_000, 1, 1))) {
            assertThatThrownBy(() -> store.endAttributeValue(open, bad)).as(bad.toString())
                .isInstanceOf(AttributeValueRejectedException.class);
        }
        assertThatThrownBy(() -> relations.saveRelation(
                new NewRelation(e, b, "R", new ValidityPeriod(LocalDate.parse("2026-01-01"), tooLate), SRC_1),
                RECORDED_AT)).isInstanceOf(RelationRejectedException.class).hasMessageContaining("쓸 수 없는 날짜");
        // 기록한 때도 같다.
        for (Instant bad : List.of(Instant.MAX, Instant.MIN, Instant.parse("9999-12-31T23:59:59Z").plusSeconds(1))) {
            assertThatThrownBy(() -> store.saveAttributeValue(value(e, "when", "값", "2026-01-01", null), bad))
                .isInstanceOf(AttributeValueRejectedException.class)
                .hasMessageContaining("기록한 때");
        }
        // 범위의 끝은 받는다.
        assertThatCode(() -> store.saveAttributeValue(new NewAttributeValue(e, "edge", "값",
                new ValidityPeriod(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)), SRC_1), RECORDED_AT))
            .doesNotThrowAnyException();
    }

    @Test
    void 거부된_뒤에도_부르는_쪽의_트랜잭션에서_이어서_쓸_수_있다() {
        EntityRef e = entity("av-tx", "a");
        EntityRef b = entity("av-tx", "b");
        String relationId = relations.saveRelation(
                new NewRelation(e, b, "R", new ValidityPeriod(LocalDate.parse("2026-01-01"), null), SRC_1), RECORDED_AT);
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(CoreTestDatabase.dataSource()));

        tx.executeWithoutResult(status -> {
            String first = store.saveAttributeValue(value(e, "name", "앞", "2026-01-01", null), RECORDED_AT);
            // 겹침, 틀린 종료일, 범위 밖 종료일, 없는 식별자: 모두 거부되지만 트랜잭션은 살아 있다.
            assertThatThrownBy(() -> store.saveAttributeValue(value(e, "name", "뒤", "2026-03-01", null), RECORDED_AT))
                .isInstanceOf(AttributeValueRejectedException.class);
            assertThatThrownBy(() -> store.endAttributeValue(first, LocalDate.parse("2025-01-01")))
                .isInstanceOf(AttributeValueRejectedException.class);
            assertThatThrownBy(() -> store.endAttributeValue(first, LocalDate.of(6_000_000, 1, 1)))
                .isInstanceOf(AttributeValueRejectedException.class);
            assertThatThrownBy(() -> relations.endRelation(relationId, LocalDate.of(6_000_000, 1, 1)))
                .isInstanceOf(RelationRejectedException.class);
            assertThatThrownBy(() -> store.endAttributeValue("av-tx|T|a|name|1999-01-01", LocalDate.parse("2026-03-01")))
                .isInstanceOf(AttributeValueRejectedException.class);
            store.endAttributeValue(first, LocalDate.parse("2026-03-01"));
            store.saveAttributeValue(value(e, "name", "뒤", "2026-03-01", null), RECORDED_AT);
            relations.endRelation(relationId, LocalDate.parse("2026-03-01"));
        });

        assertThat(query.valuesAsOf(e.id(), LocalDate.parse("2026-03-01"))).extracting(AttributeValue::value)
            .containsExactly("뒤");
        // 되돌리면 아무것도 남지 않는다.
        tx.executeWithoutResult(status -> {
            store.saveAttributeValue(value(e, "rolled-back", "값", "2026-01-01", null), RECORDED_AT);
            status.setRollbackOnly();
        });
        assertThat(query.valueAsOf(e.id(), "rolled-back", LocalDate.parse("2026-01-01"))).isEmpty();
    }

    @Test
    void 같은_시작일의_값이_다른_내용으로_있으면_무엇이_다른지_알린다() {
        EntityRef e = entity("av-differs", "a");
        store.saveAttributeValue(value(e, "name", "값", "2026-01-01", "2026-06-01"), RECORDED_AT);

        assertThatThrownBy(() -> store.saveAttributeValue(value(e, "name", "다른 값", "2026-01-01", "2026-07-01"),
                RECORDED_AT)).isInstanceOf(AttributeValueRejectedException.class)
            .hasMessageContaining("값")
            .hasMessageContaining("종료일")
            .hasMessageContaining("이미 다른 내용");
    }

    @Test
    void 끝낸_값을_끝낸_모양으로_다시_저장하면_그대로이고_처음_모양으로는_다시_저장할_수_없다() {
        EntityRef e = entity("av-resave", "a");
        String id = store.saveAttributeValue(value(e, "name", "값", "2026-01-01", null), RECORDED_AT);
        store.endAttributeValue(id, LocalDate.parse("2026-03-01"));

        assertThatCode(() -> store.saveAttributeValue(value(e, "name", "값", "2026-01-01", "2026-03-01"), RECORDED_AT))
            .doesNotThrowAnyException();
        // 종료일 없는 처음 모양은 이제 저장된 것과 종료일이 다르다. 부르는 쪽이 이것을 어떻게 다룰지는 정해지지 않았다
        // (D5 "결정 필요"). 지금은 거부하고 무엇이 다른지 알린다.
        assertThatThrownBy(() -> store.saveAttributeValue(value(e, "name", "값", "2026-01-01", null), RECORDED_AT))
            .isInstanceOf(AttributeValueRejectedException.class)
            .hasMessageContaining("종료일");
    }

    @Test
    void 처음부터_종료일이_있던_속성_값은_같은_날짜로만_끝낼_수_있고_종료일_당일에는_목록에도_없다() {
        EntityRef e = entity("av-closed", "a");
        String id = store.saveAttributeValue(value(e, "name", "값", "2026-01-01", "2026-06-01"), RECORDED_AT);

        assertThatCode(() -> store.endAttributeValue(id, LocalDate.parse("2026-06-01"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> store.endAttributeValue(id, LocalDate.parse("2026-03-01")))
            .isInstanceOf(AttributeValueRejectedException.class);
        assertThat(query.valuesAsOf(e.id(), LocalDate.parse("2026-05-31"))).hasSize(1);
        assertThat(query.valuesAsOf(e.id(), LocalDate.parse("2026-06-01"))).isEmpty();
    }

    @Test
    void 겹치는_값이_여럿이면_식별자_순으로_모두_알리고_앞에서_시작해_겹치는_것도_거부한다() {
        EntityRef e = entity("av-overlap-many", "a");
        String second = store.saveAttributeValue(value(e, "name", "둘째", "2026-06-01", "2026-09-01"), RECORDED_AT);
        String first = store.saveAttributeValue(value(e, "name", "첫째", "2026-03-01", "2026-06-01"), RECORDED_AT);

        // 둘보다 앞에서 시작해 둘에 걸친다.
        assertThatThrownBy(() -> store.saveAttributeValue(value(e, "name", "걸침", "2026-01-01", "2026-07-01"), RECORDED_AT))
            .isInstanceOf(AttributeValueRejectedException.class)
            .hasMessageContaining(first + " (")
            .hasMessageContaining(second + " (")
            .satisfies(t -> assertThat(t.getMessage().indexOf(first)).isLessThan(t.getMessage().indexOf(second)));
    }

    @Test
    void 값과_속성의_이름에_든_특수한_글자가_그대로_돌아오고_식별자가_섞이지_않는다() {
        EntityRef e = entity("av-chars", "a");
        String odd = "a\nb|c\\d\uD83D\uDE00\r\n\t'\";--";
        String withBar = store.saveAttributeValue(value(e, "a|b", odd, "2026-01-01", null), RECORDED_AT);
        String withEscapedBar = store.saveAttributeValue(value(e, "a\\|b", "다른 값", "2026-01-01", null), RECORDED_AT);

        assertThat(withBar).isNotEqualTo(withEscapedBar);
        assertThat(query.valueAsOf(e.id(), "a|b", LocalDate.parse("2026-01-01"))).map(AttributeValue::value).contains(odd);
        assertThat(query.valueAsOf(e.id(), "a\\|b", LocalDate.parse("2026-01-01"))).map(AttributeValue::value)
            .contains("다른 값");
    }

    @Test
    void 조회와_끝내기의_인자도_비어_있거나_깨진_글자면_받지_않는다() {
        EntityRef e = entity("av-args", "a");
        LocalDate day = LocalDate.parse("2026-01-01");

        assertThatThrownBy(() -> query.valueAsOf(e.id(), "a\u0000b", day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.valuesAsOf("av-args|T|a\u0000", day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.valuesAsOf("", day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.endAttributeValue("x\u0000", day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relations.endRelation("x\uD800", day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.endAttributeValue(null, day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relations.endRelation("some-id", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 속성이_많은_개체도_모두_정해진_순서로_돌려준다() {
        EntityRef e = entity("av-many", "a");
        int count = 300;
        for (int i = 0; i < count; i++) {
            // 넣는 순서를 섞는다 (7 은 300 과 서로소다).
            store.saveAttributeValue(value(e, String.format("n%03d", (i * 7) % count), "v", "2026-01-01", null), RECORDED_AT);
        }

        List<AttributeValue> found = query.valuesAsOf(e.id(), LocalDate.parse("2026-01-01"));

        assertThat(found).hasSize(count);
        assertThat(found.get(0).attributeName()).isEqualTo("n000");
        assertThat(found.get(count - 1).attributeName()).isEqualTo("n299");
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private EntityRef entity(String pack, String key) {
        EntityRef ref = new EntityRef(pack, "T", key);
        relations.saveEntity(ref, RECORDED_AT);
        return ref;
    }

    // ── 어느 날부터 유효한 값 (C-08, D2 AD-344) ─────────────────────────

    @Test
    void 어느_날부터_유효한_값은_그날_뒤로_하루라도_유효한_것이고_개체_여럿을_한_번에_묻는다() {
        EntityRef a = entity("av-from", "a");
        EntityRef b = entity("av-from", "b");
        String past = store.saveAttributeValue(value(a, "name", "지난 값", "2026-01-01", "2026-03-01"), RECORDED_AT);
        String ending = store.saveAttributeValue(value(a, "name", "끝날 값", "2026-03-01", "2026-03-02"), RECORDED_AT);
        String later = store.saveAttributeValue(value(a, "size", "뒤에 시작하는 값", "2026-09-01", null), RECORDED_AT);
        String open = store.saveAttributeValue(value(b, "name", "열린 값", "2026-02-01", null), RECORDED_AT);
        LocalDate from = LocalDate.parse("2026-03-01");

        // 종료일이 그날인 것은 그날부터 유효하지 않다 (종료일 제외). 그날 뒤에 시작하는 것도 든다. 식별자 순이다.
        assertThat(query.valuesInEffectFrom(List.of(b.id(), a.id(), a.id()), from)).extracting(AttributeValue::id)
            .containsExactly(ending, later, open)
            .doesNotContain(past);
        assertThat(query.valuesInEffectFrom(List.of(a.id()), LocalDate.parse("2026-03-02")))
            .extracting(AttributeValue::id)
            .containsExactly(later);
        // 두 번 물어도 같다.
        assertThat(query.valuesInEffectFrom(List.of(a.id(), b.id()), from))
            .isEqualTo(query.valuesInEffectFrom(List.of(b.id(), a.id()), from));
    }

    @Test
    void 어느_날부터_유효한_값을_물을_때_없는_개체와_빈_목록은_빈_답이고_틀린_인자는_받지_않는다() {
        EntityRef a = entity("av-from-none", "a");

        // 아직 없는 개체는 값도 없다. 새 판이 만들 개체를 미리 물을 수 있어야 한다.
        assertThat(query.valuesInEffectFrom(List.of("av-from-none|T|없는 것"), LocalDate.parse("2026-03-01"))).isEmpty();
        assertThat(query.valuesInEffectFrom(List.of(), LocalDate.parse("2026-03-01"))).isEmpty();
        assertThat(query.valuesInEffectFrom(List.of(a.id()), LocalDate.parse("2026-03-01"))).isEmpty();
        assertThatThrownBy(() -> query.valuesInEffectFrom(List.of(a.id()), null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.valuesInEffectFrom(List.of(""), LocalDate.parse("2026-03-01")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.valuesInEffectFrom(List.of(a.id()), LocalDate.MAX))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 개체가_많아도_어느_날부터_유효한_값을_모두_돌려준다() {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            ids.add("av-from-many|T|k" + i);
        }
        EntityRef first = entity("av-from-many", "k0");
        EntityRef last = entity("av-from-many", "k2499");
        store.saveAttributeValue(value(first, "name", "처음", "2026-03-01", null), RECORDED_AT);
        store.saveAttributeValue(value(last, "name", "끝", "2026-03-01", null), RECORDED_AT);
        // 글자 코드 순으로 맨 끝인 개체. 마지막의 덜 찬 묶음에 든다.
        EntityRef tail = entity("av-from-many", "k999");
        store.saveAttributeValue(value(tail, "name", "맨 끝 묶음", "2026-03-01", null), RECORDED_AT);

        assertThat(query.valuesInEffectFrom(ids, LocalDate.parse("2026-03-01"))).extracting(AttributeValue::value)
            .containsExactly("처음", "끝", "맨 끝 묶음");
    }

    private static NewAttributeValue value(EntityRef entity, String name, String value, String from, String to) {
        return new NewAttributeValue(entity, name, value,
                new ValidityPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to)), SRC_1);
    }
}
