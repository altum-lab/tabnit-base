package com.altumlab.tabnit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SK-05 관계 저장: 개체와 관계를 유효기간, 출처와 함께 저장한다 (보드 CORE-01, D5 3절).
 *
 * <p>잘못된 입력은 거부하고, 무엇이 왜 틀렸는지 알린다. 데이터베이스의 오류 문구를 그대로 내보내지 않는다.
 */
class RelationStoreTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final Provenance SRC_1 = new Provenance("src", 1);

    private final RelationStore store = new RelationStore(CoreTestDatabase.dataSource());

    private final JdbcTemplate jdbc = new JdbcTemplate(CoreTestDatabase.dataSource());

    // ── 개체 ───────────────────────────────────────────────────

    @Test
    void 개체를_저장하면_식별자를_돌려준다() {
        String id = store.saveEntity(new EntityRef("pack-save", "T", "a"), RECORDED_AT);

        assertThat(id).isEqualTo("pack-save|T|a");
        assertThat(count("entity", "pack-save")).isEqualTo(1);
    }

    @Test
    void 같은_개체를_다시_저장해도_행이_늘지_않고_처음_기록한_때가_남는다() {
        EntityRef a = new EntityRef("pack-again", "T", "a");
        store.saveEntity(a, RECORDED_AT);
        store.saveEntity(a, Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(count("entity", "pack-again")).isEqualTo(1);
        Instant recorded = jdbc.queryForObject("select recorded_at from entity where id = ?",
                (rs, n) -> rs.getTimestamp(1).toInstant(), a.id());
        assertThat(recorded).isEqualTo(RECORDED_AT);
    }

    @Test
    void 기록한_때_없이는_저장할_수_없다() {
        assertThatThrownBy(() -> store.saveEntity(new EntityRef("pack-norec", "T", "a"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("기록한 때");
    }

    // ── 관계 ───────────────────────────────────────────────────

    @Test
    void 관계를_저장하면_값에서_정해진_식별자를_돌려준다() {
        EntityRef a = saved("pack-rel", "a");
        EntityRef b = saved("pack-rel", "b");

        String id = store.saveRelation(new NewRelation(a, b, "R", period("2026-03-01", null), SRC_1), RECORDED_AT);

        assertThat(id).isEqualTo("pack-rel|T|a|T|b|R|src|1|2026-03-01");
        assertThat(count("relation", "pack-rel")).isEqualTo(1);
    }

    @Test
    void 같은_관계를_다시_저장해도_행이_늘지_않는다() {
        EntityRef a = saved("pack-rel-again", "a");
        EntityRef b = saved("pack-rel-again", "b");
        NewRelation relation = new NewRelation(a, b, "R", period("2026-03-01", "2026-06-01"), SRC_1);

        String first = store.saveRelation(relation, RECORDED_AT);
        String second = store.saveRelation(relation, Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(second).isEqualTo(first);
        assertThat(count("relation", "pack-rel-again")).isEqualTo(1);
    }

    @Test
    void 없는_개체를_가리키는_관계는_거부하고_어느_개체인지_알린다() {
        EntityRef a = saved("pack-missing", "a");
        EntityRef missing = new EntityRef("pack-missing", "T", "missing");

        assertThatThrownBy(
                () -> store.saveRelation(new NewRelation(a, missing, "R", period("2026-03-01", null), SRC_1),
                        RECORDED_AT))
                .isInstanceOf(RelationRejectedException.class)
                .hasMessageContaining("도착 개체")
                .hasMessageContaining("pack-missing|T|missing");
        assertThatThrownBy(
                () -> store.saveRelation(new NewRelation(missing, a, "R", period("2026-03-01", null), SRC_1),
                        RECORDED_AT))
                .isInstanceOf(RelationRejectedException.class)
                .hasMessageContaining("출발 개체");
        assertThat(count("relation", "pack-missing")).isZero();
    }

    @Test
    void 출발과_도착이_같은_관계는_거부한다() {
        EntityRef a = saved("pack-self", "a");

        assertThatThrownBy(
                () -> store.saveRelation(new NewRelation(a, a, "R", period("2026-03-01", null), SRC_1), RECORDED_AT))
                .isInstanceOf(RelationRejectedException.class)
                .hasMessageContaining("출발 개체와 도착 개체가 같");
    }

    @Test
    void 서로_다른_Pack의_개체를_잇는_관계는_거부한다() {
        EntityRef a = saved("pack-one", "a");
        EntityRef b = saved("pack-two", "b");

        assertThatThrownBy(
                () -> store.saveRelation(new NewRelation(a, b, "R", period("2026-03-01", null), SRC_1), RECORDED_AT))
                .isInstanceOf(RelationRejectedException.class)
                .hasMessageContaining("같은 Pack");
    }

    @Test
    void 같은_관계의_유효기간이_겹치면_거부하고_무엇과_겹치는지_알린다() {
        EntityRef a = saved("pack-ovl", "a");
        EntityRef b = saved("pack-ovl", "b");
        String existing = store.saveRelation(
                new NewRelation(a, b, "R", period("2026-03-01", "2026-06-01"), SRC_1), RECORDED_AT);

        assertThatThrownBy(
                () -> store.saveRelation(new NewRelation(a, b, "R", period("2026-05-31", "2026-07-01"), SRC_1),
                        RECORDED_AT))
                .isInstanceOf(RelationRejectedException.class)
                .hasMessageContaining("겹칩니다")
                .hasMessageContaining(existing)
                .hasMessageContaining("2026-03-01")
                .hasMessageContaining("2026-06-01")
                // 데이터베이스의 제약 이름이나 오류 문구를 그대로 내보내지 않는다.
                .hasMessageNotContaining("relation_no_overlap")
                .hasMessageNotContaining("ERROR");
        assertThat(count("relation", "pack-ovl")).isEqualTo(1);
    }

    @Test
    void 같은_시작일의_관계를_다른_종료일로_다시_저장하면_거부한다() {
        EntityRef a = saved("pack-same-from", "a");
        EntityRef b = saved("pack-same-from", "b");
        store.saveRelation(new NewRelation(a, b, "R", period("2026-03-01", "2026-06-01"), SRC_1), RECORDED_AT);

        assertThatThrownBy(
                () -> store.saveRelation(new NewRelation(a, b, "R", period("2026-03-01", null), SRC_1), RECORDED_AT))
                .isInstanceOf(RelationRejectedException.class)
                .hasMessageContaining("겹칩니다");
    }

    @Test
    void 맞닿는_유효기간은_저장된다() {
        EntityRef a = saved("pack-touch", "a");
        EntityRef b = saved("pack-touch", "b");
        store.saveRelation(new NewRelation(a, b, "R", period("2026-03-01", "2026-06-01"), SRC_1), RECORDED_AT);
        store.saveRelation(new NewRelation(a, b, "R", period("2026-06-01", null), SRC_1), RECORDED_AT);

        assertThat(count("relation", "pack-touch")).isEqualTo(2);
    }

    @Test
    void 관계_유형이_비어_있으면_거부한다() {
        EntityRef a = new EntityRef("pack-blank", "T", "a");
        EntityRef b = new EntityRef("pack-blank", "T", "b");

        assertThatThrownBy(() -> new NewRelation(a, b, "", period("2026-03-01", null), SRC_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("관계 유형");
    }

    @Test
    void 출처의_판_번호는_1부터다() {
        assertThatThrownBy(() -> new Provenance("src", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("판 번호");
        assertThatThrownBy(() -> new Provenance("", 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("입력 자료");
    }

    @Test
    void 구분자가_든_키도_저장하고_조회할_수_있다() {
        EntityRef a = saved("pack-escape", "a|b");
        EntityRef b = saved("pack-escape", "c\\d");

        String id = store.saveRelation(new NewRelation(a, b, "R", period("2026-03-01", null), SRC_1), RECORDED_AT);

        assertThat(id).isEqualTo("pack-escape|T|a\\|b|T|c\\\\d|R|src|1|2026-03-01");
        assertThat(new RelationQuery(CoreTestDatabase.dataSource()).relationsAsOf(a.id(), LocalDate.parse("2026-03-01")))
                .extracting(Relation::id)
                .containsExactly(id);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private EntityRef saved(String pack, String key) {
        EntityRef ref = new EntityRef(pack, "T", key);
        store.saveEntity(ref, RECORDED_AT);
        return ref;
    }

    private int count(String table, String pack) {
        return jdbc.queryForObject("select count(*) from " + table + " where pack_id = ?", Integer.class, pack);
    }

    private static ValidityPeriod period(String from, String to) {
        return new ValidityPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
    }
}
