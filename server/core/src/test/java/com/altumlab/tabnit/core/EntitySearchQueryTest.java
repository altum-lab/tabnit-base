package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A-17 수용 기준 (core 쪽): 키, 식별자, 기준 날짜에 유효한 속성 값의 일부로 개체를 찾는다. 유형으로 거른다.
 * 순서는 식별자의 글자 코드 순이고 나눠 준다 (D2 AD-411).
 *
 * <p>모든 이름과 값은 지어낸 것이다. 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다.
 */
class EntitySearchQueryTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Provenance SRC_1 = new Provenance("src", 1);

    private static final List<String> ALL_TYPES = List.of();

    private final RelationStore relations = new RelationStore(CoreTestDatabase.dataSource());

    private final AttributeValueStore values = new AttributeValueStore(CoreTestDatabase.dataSource());

    private final EntitySearchQuery query = new EntitySearchQuery(CoreTestDatabase.dataSource());

    @Test
    void 키의_일부로_찾고_식별자의_글자_코드_순으로_돌려준다() {
        String p = "es-key";
        // 넣는 차례를 섞는다. K-10 이 K-2 보다 앞선다.
        EntityRef b1 = entity(p, "TypeB", "K-1");
        EntityRef a2 = entity(p, "TypeA", "K-2");
        EntityRef a10 = entity(p, "TypeA", "K-10");
        entity(p, "TypeA", "가");

        EntityPage found = query.search(p, "K-", ALL_TYPES, date("2026-06-01"), 0, 50);

        assertThat(found.entities()).containsExactly(a10, a2, b1);
        assertThat(found.total()).isEqualTo(3);
        assertThat(query.search(p, "K-1", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(a10, b1);
        // 두 번 물어도 같다.
        assertThat(query.search(p, "K-", ALL_TYPES, date("2026-06-01"), 0, 50)).isEqualTo(found);
    }

    @Test
    void 검색어가_없으면_그_Pack_의_개체_전부다() {
        String p = "es-empty";
        EntityRef a = entity(p, "TypeA", "K-1");
        EntityRef b = entity(p, "TypeB", "K-1");
        entity("es-empty-other", "TypeA", "K-1");

        assertThat(query.search(p, "", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(a, b);
        assertThat(query.search(p, "", ALL_TYPES, date("2026-06-01"), 0, 50).total()).isEqualTo(2);
    }

    @Test
    void 맞는_것이_없거나_없는_Pack_이면_빈_목록이다() {
        String p = "es-none";
        entity(p, "TypeA", "K-1");

        EntityPage none = query.search(p, "없는 글자", ALL_TYPES, date("2026-06-01"), 0, 50);

        assertThat(none.entities()).isEmpty();
        assertThat(none.total()).isZero();
        assertThat(query.search("es-none-없는-Pack", "", ALL_TYPES, date("2026-06-01"), 0, 50).total()).isZero();
        // 다른 Pack 의 개체는 찾지 않는다.
        assertThat(query.search("es-none-", "K-1", ALL_TYPES, date("2026-06-01"), 0, 50).total()).isZero();
    }

    @Test
    void 식별자_전체를_적으면_그_개체를_찾는다() {
        String p = "es-id";
        EntityRef a = entity(p, "TypeA", "K-1");
        entity(p, "TypeB", "K-1");

        assertThat(query.search(p, "es-id|TypeA|K-1", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(a);
        // 식별자의 일부(Pack 이나 유형의 이름)로는 찾지 않는다: 그 Pack 의 개체가 모두 걸리게 된다.
        assertThat(query.search(p, "es-id", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).isEmpty();
        assertThat(query.search(p, "TypeA", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).isEmpty();
    }

    @Test
    void 속성_값의_일부로_찾는다_기준_날짜에_유효한_값만_본다() {
        String p = "es-value";
        EntityRef a = entity(p, "TypeA", "K-1");
        EntityRef b = entity(p, "TypeA", "K-2");
        EntityRef c = entity(p, "TypeA", "K-3");
        // a 의 이름은 3월 1일에 바뀐다. 시작일은 포함, 종료일은 제외다.
        value(a, "name", "첫째 세정기", "2026-01-01", "2026-03-01");
        value(a, "name", "첫째 건조기", "2026-03-01", null);
        value(b, "note", "세정기의 옆", "2026-02-01", null);
        value(c, "name", "셋째", "2026-01-01", null);

        assertThat(query.search(p, "세정기", ALL_TYPES, date("2026-01-31"), 0, 50).entities()).containsExactly(a);
        assertThat(query.search(p, "세정기", ALL_TYPES, date("2026-02-01"), 0, 50).entities()).containsExactly(a, b);
        // 종료일 전날까지 유효하다.
        assertThat(query.search(p, "세정기", ALL_TYPES, date("2026-02-28"), 0, 50).entities()).containsExactly(a, b);
        // 종료일 당일에는 앞의 값이 유효하지 않고 새 값이 유효하다.
        assertThat(query.search(p, "세정기", ALL_TYPES, date("2026-03-01"), 0, 50).entities()).containsExactly(b);
        assertThat(query.search(p, "건조기", ALL_TYPES, date("2026-03-01"), 0, 50).entities()).containsExactly(a);
        assertThat(query.search(p, "건조기", ALL_TYPES, date("2026-02-28"), 0, 50).entities()).isEmpty();
        // 값이 들어오기 전의 날짜에는 값으로 찾지 못한다. 키로는 찾는다: 개체에는 유효기간이 없다.
        assertThat(query.search(p, "세정기", ALL_TYPES, date("2025-12-31"), 0, 50).entities()).isEmpty();
        assertThat(query.search(p, "K-1", ALL_TYPES, date("2025-12-31"), 0, 50).entities()).containsExactly(a);
    }

    @Test
    void 키와_값에_함께_맞거나_값_여럿에_맞아도_한_번만_나온다() {
        String p = "es-once";
        EntityRef a = entity(p, "TypeA", "가-1");
        value(a, "name", "가 하나", "2026-01-01", null);
        value(a, "note", "가 둘", "2026-01-01", null);

        EntityPage found = query.search(p, "가", ALL_TYPES, date("2026-06-01"), 0, 50);

        assertThat(found.entities()).containsExactly(a);
        assertThat(found.total()).isEqualTo(1);
    }

    @Test
    void 영어_대소문자만_가리지_않는다() {
        String p = "es-case";
        EntityRef a = entity(p, "TypeA", "Pump-A");
        EntityRef b = entity(p, "TypeA", "K-2");
        value(b, "name", "Main PUMP", "2026-01-01", null);
        EntityRef c = entity(p, "TypeA", "K-3");
        value(c, "name", "É 가", "2026-01-01", null);

        assertThat(query.search(p, "pump", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(b, a);
        assertThat(query.search(p, "PUMP", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(b, a);
        // 영어 글자가 아닌 것은 적힌 그대로 견준다.
        assertThat(query.search(p, "É", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(c);
        assertThat(query.search(p, "é", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).isEmpty();
    }

    @Test
    void 특수_글자가_든_검색어는_글자_그대로_찾는다() {
        String p = "es-chars";
        EntityRef percent = entity(p, "T", "100%");
        EntityRef underscore = entity(p, "T", "a_b");
        EntityRef plain = entity(p, "T", "aXb");
        EntityRef bar = entity(p, "T", "x|y\\z");
        EntityRef quote = entity(p, "T", "따옴표 ' \" 😀");
        value(plain, "name", "50% 할인_가", "2026-01-01", null);

        // % 와 _ 는 아무 글자나 뜻하지 않는다.
        assertThat(query.search(p, "%", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(percent, plain);
        // 글자 코드 순이다: X 가 _ 보다 앞선다.
        assertThat(query.search(p, "_", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(plain, underscore);
        assertThat(query.search(p, "a_b", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(underscore);
        // 키의 세로 막대와 역빗금은 식별자에서만 역빗금이 붙는다. 키의 글자 그대로 찾는다.
        assertThat(query.search(p, "x|y\\z", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(bar);
        assertThat(query.search(p, bar.id(), ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(bar);
        assertThat(query.search(p, "' \" 😀", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(quote);
        // 빈칸도 글자다.
        assertThat(query.search(p, " ", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(plain, quote);
    }

    @Test
    void 유형으로_거른다() {
        String p = "es-type";
        EntityRef a = entity(p, "TypeA", "K-1");
        EntityRef b = entity(p, "TypeB", "K-1");
        EntityRef c = entity(p, "TypeC", "K-1");

        assertThat(query.search(p, "K-1", List.of("TypeB"), date("2026-06-01"), 0, 50).entities()).containsExactly(b);
        // 넘긴 차례와 겹침은 결과에 닿지 않는다.
        EntityPage two = query.search(p, "", List.of("TypeC", "TypeA", "TypeC"), date("2026-06-01"), 0, 50);
        assertThat(two.entities()).containsExactly(a, c);
        assertThat(two.total()).isEqualTo(2);
        // 유형의 이름은 글자 그대로 견준다. 없는 유형이면 빈 목록이다.
        assertThat(query.search(p, "", List.of("typea"), date("2026-06-01"), 0, 50).entities()).isEmpty();
        assertThat(query.search(p, "", List.of("Type"), date("2026-06-01"), 0, 50).entities()).isEmpty();
    }

    @Test
    void 유형으로_거르면서_속성_값으로_찾는다() {
        String p = "es-type-value";
        EntityRef a = entity(p, "TypeA", "K-1");
        EntityRef b = entity(p, "TypeB", "K-2");
        value(a, "name", "같은 이름", "2026-01-01", null);
        value(b, "name", "같은 이름", "2026-01-01", null);

        assertThat(query.search(p, "같은", List.of("TypeB"), date("2026-06-01"), 0, 50).entities()).containsExactly(b);
        assertThat(query.search(p, "같은", List.of("TypeB"), date("2026-06-01"), 0, 50).total()).isEqualTo(1);
        assertThat(query.search(p, "같은", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(a, b);
    }

    @Test
    void 다른_Pack_의_개체의_값으로는_찾지_않는다() {
        EntityRef mine = entity("es-pack-a", "T", "K-1");
        EntityRef other = entity("es-pack-b", "T", "K-1");
        value(other, "name", "저쪽의 값", "2026-01-01", null);
        value(mine, "name", "이쪽의 값", "2026-01-01", null);

        assertThat(query.search("es-pack-a", "저쪽", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).isEmpty();
        assertThat(query.search("es-pack-a", "의 값", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(mine);
        assertThat(query.search("es-pack-b", "의 값", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(other);
    }

    @Test
    void 식별자는_대소문자까지_같아야_찾는다() {
        String p = "es-id-case";
        EntityRef a = entity(p, "TypeA", "가-1");

        assertThat(query.search(p, "es-id-case|TypeA|가-1", ALL_TYPES, date("2026-06-01"), 0, 50).entities())
            .containsExactly(a);
        assertThat(query.search(p, "es-id-case|typea|가-1", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).isEmpty();
    }

    @Test
    void 영어_글자가_아닌_닮은_글자는_바꿔_견주지_않는다() {
        String p = "es-fold";
        // 켈빈 기호(U+212A)와 점이 있는 대문자 I(U+0130)는 다른 방식으로 소문자로 바꾸면 k 와 i 가 된다.
        EntityRef kelvin = entity(p, "T", "\u212A-1");
        EntityRef dotted = entity(p, "T", "\u0130-2");
        value(kelvin, "name", "온도 \u212A", "2026-01-01", null);

        assertThat(query.search(p, "k", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).isEmpty();
        assertThat(query.search(p, "i", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).isEmpty();
        assertThat(query.search(p, "\u212A", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(kelvin);
        assertThat(query.search(p, "\u0130", ALL_TYPES, date("2026-06-01"), 0, 50).entities()).containsExactly(dotted);
    }

    @Test
    void 값이_끝나고_다음_값이_없는_기간과_받는_범위의_끝_날짜로도_찾는다() {
        String p = "es-gap";
        EntityRef a = entity(p, "T", "K-1");
        value(a, "name", "앞의 이름", "2026-01-01", "2026-02-01");
        value(a, "name", "뒤의 이름", "2026-04-01", null);

        assertThat(query.search(p, "이름", ALL_TYPES, date("2026-03-01"), 0, 50).entities()).isEmpty();
        assertThat(query.search(p, "이름", ALL_TYPES, date("0001-01-01"), 0, 50).entities()).isEmpty();
        assertThat(query.search(p, "뒤의", ALL_TYPES, date("9999-12-31"), 0, 50).entities()).containsExactly(a);
    }

    @Test
    void 값으로_맞은_개체도_나눠_받으면_전체_수와_순서가_같다() {
        String p = "es-page-value";
        List<EntityRef> matching = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            EntityRef saved = entity(p, "T", "K-" + i);
            // 셋 가운데 둘이 값으로 맞는다. 맞는 값이 둘인 개체도 한 번만 센다.
            if (i % 3 != 0) {
                value(saved, "name", "맞는 값 " + i, "2026-01-01", null);
                value(saved, "note", "또 맞는 값", "2026-01-01", null);
                matching.add(saved);
            }
            else {
                value(saved, "name", "다른 것 " + i, "2026-01-01", null);
            }
        }
        matching.sort((left, right) -> CodePointOrder.compare(left.id(), right.id()));

        List<EntityRef> joined = new ArrayList<>();
        for (int offset = 0; offset < 200; offset += 70) {
            EntityPage page = query.search(p, "맞는 값", ALL_TYPES, date("2026-06-01"), offset, 70);
            assertThat(page.total()).isEqualTo(200);
            joined.addAll(page.entities());
        }
        assertThat(joined).containsExactlyElementsOf(matching);
    }

    @Test
    void 나눠_받은_것을_이으면_한_번에_받은_것과_순서까지_같다() {
        String p = "es-page";
        // 수천 개체. 절반만 검색어에 맞는다.
        List<EntityRef> matching = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            EntityRef saved = entity(p, i % 3 == 0 ? "TypeA" : "TypeB", (i % 2 == 0 ? "match-" : "other-") + i);
            if (i % 2 == 0) {
                matching.add(saved);
            }
        }
        matching.sort((left, right) -> CodePointOrder.compare(left.id(), right.id()));

        List<EntityRef> joined = new ArrayList<>();
        for (int offset = 0; offset < 1600; offset += 200) {
            EntityPage page = query.search(p, "match-", ALL_TYPES, date("2026-06-01"), offset, 200);
            assertThat(page.total()).isEqualTo(1500);
            joined.addAll(page.entities());
        }

        assertThat(joined).containsExactlyElementsOf(matching);
        // 끝을 넘어가면 빈 목록이고 전체 수는 그대로다.
        EntityPage beyond = query.search(p, "match-", ALL_TYPES, date("2026-06-01"), 1500, 200);
        assertThat(beyond.entities()).isEmpty();
        assertThat(beyond.total()).isEqualTo(1500);
        assertThat(query.search(p, "match-", ALL_TYPES, date("2026-06-01"), 1499, 200).entities())
            .containsExactly(matching.get(1499));
    }

    @Test
    void 찾는_일은_아무것도_바꾸지_않는다() {
        String p = "es-readonly";
        entity(p, "TypeA", "K-1");
        PackDataQuery data = new PackDataQuery(CoreTestDatabase.dataSource());
        PackData before = data.packDataAsOf(p, date("2026-06-01"), 1000);

        query.search(p, "새 글자", ALL_TYPES, date("2026-06-01"), 0, 50);

        assertThat(data.packDataAsOf(p, date("2026-06-01"), 1000)).isEqualTo(before);
    }

    @Test
    void 틀린_인자는_무엇이_틀렸는지_알리며_거부한다() {
        LocalDate asOf = date("2026-06-01");

        assertThatThrownBy(() -> query.search("", "가", ALL_TYPES, asOf, 0, 50)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Pack 의 식별자");
        assertThatThrownBy(() -> query.search("es-bad", null, ALL_TYPES, asOf, 0, 50))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.search("es-bad", "가\u0000", ALL_TYPES, asOf, 0, 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("검색어");
        assertThatThrownBy(() -> query.search("es-bad", "\ud83d", ALL_TYPES, asOf, 0, 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("검색어");
        assertThatThrownBy(() -> query.search("es-bad", "가", null, asOf, 0, 50)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.search("es-bad", "가", List.of(""), asOf, 0, 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("개체 유형");
        assertThatThrownBy(() -> query.search("es-bad", "가", ALL_TYPES, null, 0, 50))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.search("es-bad", "가", ALL_TYPES, LocalDate.MAX, 0, 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("기준 날짜");
        assertThatThrownBy(() -> query.search("es-bad", "가", ALL_TYPES, asOf, -1, 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("건너뛸 수");
        assertThatThrownBy(() -> query.search("es-bad", "가", ALL_TYPES, asOf, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("한 번에 받을 수");
    }

    private EntityRef entity(String packId, String type, String key) {
        EntityRef entity = new EntityRef(packId, type, key);
        relations.saveEntity(entity, RECORDED_AT);
        return entity;
    }

    private void value(EntityRef entity, String attribute, String value, String from, String to) {
        values.saveAttributeValue(new NewAttributeValue(entity, attribute, value,
                new ValidityPeriod(date(from), to == null ? null : date(to)), SRC_1), RECORDED_AT);
    }

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }
}
