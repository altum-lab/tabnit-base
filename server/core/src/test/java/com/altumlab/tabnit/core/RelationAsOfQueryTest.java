package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SK-05 수용 기준: 기준 날짜를 주면 그 날짜에 유효한 관계만 돌려준다.
 *
 * <p>일반 시험이다. 특정 Pack 과 무관한 지어낸 값으로, 빈 저장소, 관계가 없는 개체, 유효기간 경계,
 * 과거·현재·미래 시점, 결과의 순서를 본다. 예시는 D3 5절의 표와 같다.
 */
class RelationAsOfQueryTest {

    /** 기록한 때는 밖에서 받아 넘긴다. 시험에서는 고정한 값을 쓴다. */
    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Provenance SRC_1 = new Provenance("src", 1);

    private final RelationStore store = new RelationStore(CoreTestDatabase.dataSource());

    private final RelationQuery query = new RelationQuery(CoreTestDatabase.dataSource());

    // ── 빈 저장소, 관계가 없는 개체 ────────────────────────────────

    @Test
    void 없는_개체를_조회하면_빈_목록이_아니라_오류로_알린다() {
        assertThatThrownBy(() -> query.relationsAsOf("pack-none|T|missing", date("2026-03-01")))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("pack-none|T|missing");
    }

    @Test
    void 관계가_없는_개체는_빈_목록을_돌려준다() {
        EntityRef alone = entity("pack-alone", "a");

        assertThat(query.relationsAsOf(alone.id(), date("2026-03-01"))).isEmpty();
    }

    @Test
    void 기준_날짜_없이는_조회할_수_없다() {
        EntityRef a = entity("pack-noasof", "a");

        assertThatThrownBy(() -> query.relationsAsOf(a.id(), null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("기준 날짜");
    }

    // A-10 (D2 AD-370): 관계의 조회도 속성 값의 조회와 같은 범위의 날짜만 받는다.
    @Test
    void 받는_범위_밖의_기준_날짜와_깨진_식별자는_저장소까지_가지_않고_입력이_틀렸다고_알린다() {
        EntityRef a = entity("pack-range", "a");

        for (LocalDate bad : List.of(LocalDate.MAX, LocalDate.MIN, LocalDate.of(10000, 1, 1), LocalDate.of(0, 12, 31),
                LocalDate.of(5874898, 1, 1))) {
            assertThatThrownBy(() -> query.relationsAsOf(a.id(), bad)).as(bad.toString())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기준 날짜");
            assertThatThrownBy(() -> query.relationsAsOf(a.id(), bad, 0, 10)).as(bad.toString())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기준 날짜");
        }
        // 범위의 두 끝은 받는다.
        assertThat(query.relationsAsOf(a.id(), LocalDate.of(1, 1, 1))).isEmpty();
        assertThat(query.relationsAsOf(a.id(), LocalDate.of(9999, 12, 31))).isEmpty();
        assertThat(query.relationsAsOf(a.id(), LocalDate.of(9999, 12, 31), 0, 10).relations()).isEmpty();
        // NUL 글자와 짝 없는 대리 문자와 빈 글자는 없는 개체가 아니라 틀린 입력이다.
        for (String bad : List.of("", "pack-range|T|a\u0000", "pack-range|T|\uD800")) {
            assertThatThrownBy(() -> query.relationsAsOf(bad, date("2026-03-01"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("개체");
            assertThatThrownBy(() -> query.relationsAsOf(bad, date("2026-03-01"), 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("개체");
        }
    }

    @Test
    void 개체_식별자_없이는_조회할_수_없다() {
        assertThatThrownBy(() -> query.relationsAsOf(null, date("2026-03-01")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("개체");
    }

    // ── 유효기간 경계와 과거·현재·미래 (D3 5절의 예시) ─────────────────

    @Test
    void 기준_날짜에_유효한_관계만_돌려준다() {
        String pack = "pack-d3";
        EntityRef ga = entity(pack, "ga");
        String r1 = relation(ga, entity(pack, "na"), "R", "2026-03-01", "2026-06-01");
        String r2 = relation(ga, entity(pack, "da"), "R", "2026-06-01", null);
        String r3 = relation(ga, entity(pack, "ra"), "R", "2026-01-01", "2026-03-01");

        // 어느 관계도 아직 시작하지 않았다.
        assertThat(idsAsOf(ga, "2025-12-31")).isEmpty();
        // R3 의 시작일 당일. 시작일은 포함한다.
        assertThat(idsAsOf(ga, "2026-01-01")).containsExactly(r3);
        // R3 의 종료일 전날.
        assertThat(idsAsOf(ga, "2026-02-28")).containsExactly(r3);
        // R3 는 종료일 당일이라 빠지고, R1 은 시작일 당일이라 들어온다.
        assertThat(idsAsOf(ga, "2026-03-01")).containsExactly(r1);
        // R1 의 종료일 전날.
        assertThat(idsAsOf(ga, "2026-05-31")).containsExactly(r1);
        // R1 은 종료일 당일이라 빠지고, R2 가 시작한다.
        assertThat(idsAsOf(ga, "2026-06-01")).containsExactly(r2);
        // 종료일이 없으면 계속 유효하다.
        assertThat(idsAsOf(ga, "2030-01-01")).containsExactly(r2);
    }

    @Test
    void 개체가_도착_쪽이어도_돌려준다() {
        String pack = "pack-target";
        EntityRef a = entity(pack, "a");
        EntityRef b = entity(pack, "b");
        String r = relation(a, b, "R", "2026-03-01", null);

        List<Relation> found = query.relationsAsOf(b.id(), date("2026-03-01"));

        assertThat(found).extracting(Relation::id).containsExactly(r);
        assertThat(found.get(0).sourceEntityId()).isEqualTo(a.id());
        assertThat(found.get(0).targetEntityId()).isEqualTo(b.id());
    }

    @Test
    void 도착_쪽_개체로_조회해도_종료일_당일에는_빠진다() {
        String pack = "pack-target-end";
        EntityRef a = entity(pack, "a");
        EntityRef b = entity(pack, "b");
        String r = relation(a, b, "R", "2026-03-01", "2026-06-01");

        assertThat(idsAsOf(b, "2026-05-31")).containsExactly(r);
        assertThat(idsAsOf(b, "2026-06-01")).isEmpty();
    }

    @Test
    void 돌려주는_관계에는_유효기간과_출처와_기록한_때가_담긴다() {
        String pack = "pack-fields";
        EntityRef a = entity(pack, "a");
        EntityRef b = entity(pack, "b");
        relation(a, b, "R", "2026-03-01", "2026-06-01");

        Relation found = query.relationsAsOf(a.id(), date("2026-04-01")).get(0);

        assertThat(found.packId()).isEqualTo(pack);
        assertThat(found.relationType()).isEqualTo("R");
        assertThat(found.period()).isEqualTo(new ValidityPeriod(date("2026-03-01"), date("2026-06-01")));
        assertThat(found.provenance()).isEqualTo(SRC_1);
        assertThat(found.recordedAt()).isEqualTo(RECORDED_AT);
    }

    @Test
    void 다른_개체의_관계는_돌려주지_않는다() {
        String pack = "pack-others";
        EntityRef a = entity(pack, "a");
        EntityRef b = entity(pack, "b");
        EntityRef c = entity(pack, "c");
        relation(b, c, "R", "2026-03-01", null);

        assertThat(query.relationsAsOf(a.id(), date("2026-03-01"))).isEmpty();
    }

    @Test
    void 출처가_달라_겹쳐_저장된_관계는_둘_다_돌려준다() {
        String pack = "pack-two-sources";
        EntityRef a = entity(pack, "a");
        EntityRef b = entity(pack, "b");
        String first = store.saveRelation(
                new NewRelation(a, b, "R", period("2026-03-01", null), new Provenance("src", 1)), RECORDED_AT);
        String second = store.saveRelation(
                new NewRelation(a, b, "R", period("2026-04-01", null), new Provenance("src", 2)), RECORDED_AT);

        assertThat(idsAsOf(a, "2026-03-15")).containsExactly(first);
        assertThat(idsAsOf(a, "2026-05-01")).containsExactly(first, second);
    }

    // ── 결과의 순서 (D2 AD-38): 관계 유형, 상대 개체의 식별자, 관계의 식별자 ──────

    @Test
    void 관계_유형_다음_상대_개체_다음_관계_식별자_순으로_돌려준다() {
        String pack = "pack-order";
        EntityRef me = entity(pack, "me");
        EntityRef x = entity(pack, "x");
        EntityRef y = entity(pack, "y");
        // 넣는 순서를 일부러 섞는다. 결과는 넣은 순서와 무관해야 한다.
        String typeBtoY = relation(me, y, "b-type", "2026-01-01", null);
        String typeAtoY = relation(me, y, "a-type", "2026-01-01", null);
        String typeAfromX = relation(x, me, "a-type", "2026-01-01", null);
        String typeAtoXEdition2 = store.saveRelation(
                new NewRelation(me, x, "a-type", period("2026-01-01", null), new Provenance("src", 2)), RECORDED_AT);
        String typeAtoXEdition1 = relation(me, x, "a-type", "2026-01-01", null);

        List<String> ids = idsAsOf(me, "2026-06-01");

        // a-type 이 b-type 보다 먼저. a-type 안에서는 상대 개체 x 가 y 보다 먼저.
        // 상대 개체가 x 인 셋은 관계 식별자 순: me|x 로 시작하는 둘(판 1, 판 2)이 x|me 로 시작하는 것보다 먼저.
        assertThat(ids).containsExactly(typeAtoXEdition1, typeAtoXEdition2, typeAfromX, typeAtoY, typeBtoY);
    }

    @Test
    void 순서는_글자_코드_순이다() {
        String pack = "pack-order-code";
        EntityRef me = entity(pack, "me");
        EntityRef other = entity(pack, "other");
        // 언어 설정에 따른 순서라면 a 가 B 보다 앞선다. 글자 코드 순에서는 대문자가 먼저다.
        String lower = relation(me, other, "a", "2026-01-01", null);
        String korean = relation(me, other, "가", "2026-01-01", null);
        String upper = relation(me, other, "B", "2026-01-01", null);

        assertThat(idsAsOf(me, "2026-06-01")).containsExactly(upper, lower, korean);
    }

    @Test
    void 판_번호는_수가_아니라_글자_코드_순으로_비교된다() {
        // 식별자 순은 식별자 문자열의 글자 코드 순이다 (D2 AD-65). 그래서 판 10 이 판 2 보다 앞에 온다.
        // 자릿수를 맞출지는 D5 "결정 필요" 9번에 열려 있다. 정해지면 이 시험을 함께 고친다.
        String pack = "pack-edition-order";
        EntityRef me = entity(pack, "me");
        EntityRef other = entity(pack, "other");
        String edition2 = store.saveRelation(
                new NewRelation(me, other, "R", period("2026-01-01", null), new Provenance("src", 2)), RECORDED_AT);
        String edition10 = store.saveRelation(
                new NewRelation(me, other, "R", period("2026-01-01", null), new Provenance("src", 10)), RECORDED_AT);

        assertThat(idsAsOf(me, "2026-06-01")).containsExactly(edition10, edition2);
    }

    @Test
    void 같은_요청을_두_번_하면_결과와_순서가_같다() {
        String pack = "pack-twice";
        EntityRef me = entity(pack, "me");
        for (String key : List.of("k3", "k1", "k5", "k2", "k4")) {
            relation(me, entity(pack, key), "R", "2026-01-01", null);
        }

        List<Relation> first = query.relationsAsOf(me.id(), date("2026-06-01"));
        List<Relation> second = query.relationsAsOf(me.id(), date("2026-06-01"));

        // 섞어 넣었지만 상대 개체의 식별자 순으로 나온다.
        assertThat(first).extracting(Relation::targetEntityId)
                .containsExactly("pack-twice|T|k1", "pack-twice|T|k2", "pack-twice|T|k3", "pack-twice|T|k4",
                        "pack-twice|T|k5");
        assertThat(second).isEqualTo(first);
    }

    // ── 큰 입력, Pack 사이의 경계 ──────────────────────────────────

    @Test
    void 관계가_많아도_유효한_것만_정해진_순서로_돌려준다() {
        String pack = "pack-many";
        EntityRef me = entity(pack, "me");
        int total = 300;
        List<String> expected = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            // 넣는 순서를 섞는다 (7 은 300 과 서로소라 모든 번호가 한 번씩 나온다).
            int n = (i * 7) % total;
            EntityRef other = entity(pack, String.format("k%03d", n));
            // 셋 가운데 하나는 기준 날짜 전에 끝난 관계다.
            boolean ended = n % 3 == 0;
            String id = relation(me, other, "R", "2026-01-01", ended ? "2026-03-01" : null);
            if (!ended) {
                expected.add(id);
            }
        }
        java.util.Collections.sort(expected);

        List<String> found = idsAsOf(me, "2026-03-01");

        assertThat(found).hasSize(200);
        // 관계 유형이 모두 같으므로 상대 개체의 식별자 순이고, 이 자료에서는 관계 식별자 순과 같다.
        assertThat(found).containsExactlyElementsOf(expected);
    }

    @Test
    void 다른_Pack의_같은_유형과_키인_개체와_섞이지_않는다() {
        EntityRef hereA = entity("pack-here", "a");
        EntityRef hereB = entity("pack-here", "b");
        EntityRef thereA = entity("pack-there", "a");
        EntityRef thereB = entity("pack-there", "b");
        String here = relation(hereA, hereB, "R", "2026-01-01", null);
        String there = relation(thereA, thereB, "R", "2026-01-01", null);

        assertThat(idsAsOf(hereA, "2026-06-01")).containsExactly(here);
        assertThat(idsAsOf(thereA, "2026-06-01")).containsExactly(there);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    // ── 나눠 주기 (D-13, D2 AD-347) ──────────────────────────────

    @Test
    void 나눠_받은_것을_이으면_한_번에_받은_것과_순서까지_같고_전체_수가_함께_온다() {
        EntityRef hub = entity("pack-page", "hub");
        for (int i = 0; i < 23; i++) {
            // 넣는 차례를 섞는다. 유형도 둘로 나눈다. 유효하지 않은 것도 섞는다.
            int n = (i * 7) % 23;
            relation(hub, entity("pack-page", "t" + n), n % 2 == 0 ? "b-type" : "a-type", "2026-01-01", null);
        }
        relation(hub, entity("pack-page", "ended"), "a-type", "2026-01-01", "2026-03-01");
        LocalDate asOf = date("2026-03-01");
        List<Relation> all = query.relationsAsOf(hub.id(), asOf);
        assertThat(all).hasSize(23);

        List<Relation> joined = new java.util.ArrayList<>();
        for (int offset = 0; offset < 30; offset += 5) {
            RelationPage page = query.relationsAsOf(hub.id(), asOf, offset, 5);
            // 전체 수는 나누기 전의 수다. 어느 쪽에서나 같다.
            assertThat(page.total()).isEqualTo(23);
            assertThat(page.relations()).hasSize(Math.max(0, Math.min(5, 23 - offset)));
            joined.addAll(page.relations());
        }
        assertThat(joined).isEqualTo(all);
        // 크기가 달라도, 한 번에 다 받아도 같다.
        assertThat(query.relationsAsOf(hub.id(), asOf, 0, 200).relations()).isEqualTo(all);
        assertThat(query.relationsAsOf(hub.id(), asOf, 22, 1).relations()).containsExactly(all.get(22));
        // 두 번 물어도 같다.
        assertThat(query.relationsAsOf(hub.id(), asOf, 5, 5)).isEqualTo(query.relationsAsOf(hub.id(), asOf, 5, 5));
    }

    @Test
    void 전체_수를_넘겨_건너뛰면_빈_목록이고_전체_수는_그대로다() {
        EntityRef a = entity("pack-page-end", "a");
        relation(a, entity("pack-page-end", "b"), "R", "2026-01-01", null);
        EntityRef alone = entity("pack-page-end", "alone");

        RelationPage past = query.relationsAsOf(a.id(), date("2026-03-01"), 1, 10);
        assertThat(past.relations()).isEmpty();
        assertThat(past.total()).isEqualTo(1);
        assertThat(query.relationsAsOf(a.id(), date("2026-03-01"), 1000, 10).total()).isEqualTo(1);
        // 관계가 없는 개체와 유효한 것이 없는 날짜는 전체 수가 0 이다.
        assertThat(query.relationsAsOf(alone.id(), date("2026-03-01"), 0, 10))
            .isEqualTo(new RelationPage(List.of(), 0));
        assertThat(query.relationsAsOf(a.id(), date("2025-12-31"), 0, 10).total()).isZero();
    }

    @Test
    void 나눠_물을_때도_전체_수와_목록이_유효기간의_경계에서_함께_바뀐다() {
        EntityRef a = entity("pack-page-edge", "a");
        String kept = relation(a, entity("pack-page-edge", "b"), "R", "2026-01-01", null);
        String ending = relation(a, entity("pack-page-edge", "c"), "R", "2026-02-01", "2026-03-01");

        // 시작일 전날, 시작일 당일, 종료일 전날, 종료일 당일. 전체 수는 따로 세므로 목록과 같은 날에 바뀌는지 본다.
        String[][] cases = { { "2025-12-31" }, { "2026-01-01", kept }, { "2026-01-31", kept }, { "2026-02-01", kept, ending },
                { "2026-02-28", kept, ending }, { "2026-03-01", kept } };
        for (String[] expected : cases) {
            RelationPage page = query.relationsAsOf(a.id(), date(expected[0]), 0, 10);
            List<String> ids = page.relations().stream().map(Relation::id).toList();
            assertThat(ids).as(expected[0]).containsExactlyInAnyOrder(java.util.Arrays.copyOfRange(expected, 1, expected.length));
            assertThat(page.total()).as(expected[0]).isEqualTo(expected.length - 1);
            // 한 번에 읽는 조회와 같다.
            assertThat(page.relations()).as(expected[0]).isEqualTo(query.relationsAsOf(a.id(), date(expected[0])));
        }
    }

    @Test
    void 나눠_물을_때도_없는_개체는_오류이고_틀린_건너뛸_수와_크기는_받지_않는다() {
        EntityRef a = entity("pack-page-bad", "a");

        assertThatThrownBy(() -> query.relationsAsOf("pack-page-bad|T|missing", date("2026-03-01"), 0, 10))
            .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> query.relationsAsOf(a.id(), null, 0, 10)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query.relationsAsOf(a.id(), date("2026-03-01"), -1, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.relationsAsOf(a.id(), date("2026-03-01"), 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.relationsAsOf(a.id(), date("2026-03-01"), 0, -5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private EntityRef entity(String pack, String key) {
        EntityRef ref = new EntityRef(pack, "T", key);
        store.saveEntity(ref, RECORDED_AT);
        return ref;
    }

    private String relation(EntityRef source, EntityRef target, String type, String from, String to) {
        return store.saveRelation(new NewRelation(source, target, type, period(from, to), SRC_1), RECORDED_AT);
    }

    private List<String> idsAsOf(EntityRef entity, String asOf) {
        return query.relationsAsOf(entity.id(), date(asOf)).stream().map(Relation::id).toList();
    }

    private static ValidityPeriod period(String from, String to) {
        return new ValidityPeriod(date(from), to == null ? null : date(to));
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}
