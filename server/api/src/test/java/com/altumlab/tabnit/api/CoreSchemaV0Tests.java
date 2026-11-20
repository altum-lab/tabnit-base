package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SK-03 수용 기준: D5 의 core 테이블(개체, 속성 값, 관계)이 빈 데이터베이스에 자동으로 만들어진다.
 *
 * <p>빈 PostgreSQL 컨테이너에 변경 파일을 처음부터 적용한 뒤, 테이블과 열이 D5 3절과 같은지,
 * D5 가 정한 제약(유일, 참조, 유효기간, 겹침 거부, 정렬 규칙)이 걸려 있는지 본다.
 *
 * <p>시험 자료는 지어낸 값이다. 시험마다 다른 Pack 식별자를 써서 서로 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CoreSchemaV0Tests {

	/** 기록한 때는 밖에서 받아 넘긴다. 시험에서는 고정한 값을 쓴다. */
	private static final Timestamp RECORDED_AT = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));

	@Autowired
	JdbcTemplate jdbc;

	// ── 테이블과 열 ─────────────────────────────────────────────

	@Test
	void core_테이블_셋이_만들어진다() {
		List<String> tables = jdbc.queryForList("""
				select table_name from information_schema.tables
				where table_schema = 'public' and table_name in ('entity', 'attribute_value', 'relation')
				order by table_name""", String.class);

		assertThat(tables).containsExactly("attribute_value", "entity", "relation");
	}

	@Test
	void 개체_테이블의_열이_D5와_같다() {
		assertThat(columnsOf("entity"))
			.containsExactlyInAnyOrder("id", "pack_id", "entity_type", "entity_key", "recorded_at");
	}

	@Test
	void 속성값_테이블의_열이_D5와_같다() {
		assertThat(columnsOf("attribute_value")).containsExactlyInAnyOrder("id", "pack_id", "entity_id",
				"attribute_name", "value", "valid_from", "valid_to", "input_source_id", "edition_no", "recorded_at");
	}

	@Test
	void 관계_테이블의_열이_D5와_같다() {
		assertThat(columnsOf("relation")).containsExactlyInAnyOrder("id", "pack_id", "source_entity_id",
				"target_entity_id", "relation_type", "valid_from", "valid_to", "input_source_id", "edition_no",
				"recorded_at");
	}

	@Test
	void 뼈대_확인용_테이블은_그대로_있다() {
		String schema = jdbc.queryForObject("select value from tabnit_meta where key = 'schema'", String.class);

		assertThat(schema).isEqualTo("v0");
	}

	// ── 개체 ───────────────────────────────────────────────────

	@Test
	void 같은_개체를_두_번_넣으면_거부된다() {
		insertEntity("pack-dup", "T", "k1");

		assertThatThrownBy(() -> insertEntity("pack-dup", "T", "k1"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 기록한_때_없이는_저장되지_않는다() {
		assertThatThrownBy(() -> jdbc.update(
				"insert into entity (id, pack_id, entity_type, entity_key) values (?, ?, ?, ?)", "pack-norec|T|k1",
				"pack-norec", "T", "k1"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 식별자는_글자_코드_순으로_정렬되고_두_번_읽어도_순서가_같다() {
		// 언어 설정에 따른 순서라면 a 가 B 보다 앞선다. 글자 코드 순에서는 대문자가 먼저다.
		insertEntity("pack-sort", "T", "a");
		insertEntity("pack-sort", "T", "가");
		insertEntity("pack-sort", "T", "B");

		String sql = "select entity_key from entity where pack_id = 'pack-sort' order by id";
		List<String> first = jdbc.queryForList(sql, String.class);
		List<String> second = jdbc.queryForList(sql, String.class);

		assertThat(first).containsExactly("B", "a", "가");
		assertThat(second).isEqualTo(first);
	}

	// ── 관계: 참조 ──────────────────────────────────────────────

	@Test
	void 없는_개체를_가리키는_관계는_거부된다() {
		String a = insertEntity("pack-ref", "T", "a");

		assertThatThrownBy(() -> insertRelation("pack-ref", a, "pack-ref|T|missing", "R", "src", 1, "2026-01-01", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 다른_Pack의_개체를_잇는_관계는_거부된다() {
		String a = insertEntity("pack-one", "T", "a");
		String b = insertEntity("pack-two", "T", "b");

		assertThatThrownBy(() -> insertRelation("pack-one", a, b, "R", "src", 1, "2026-01-01", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 출발과_도착이_같은_관계는_거부된다() {
		String a = insertEntity("pack-self", "T", "a");

		assertThatThrownBy(() -> insertRelation("pack-self", a, a, "R", "src", 1, "2026-01-01", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ── 관계: 유효기간 ───────────────────────────────────────────

	@Test
	void 종료일이_시작일과_같으면_거부된다() {
		String[] ab = twoEntities("pack-eq");

		assertThatThrownBy(() -> insertRelation("pack-eq", ab[0], ab[1], "R", "src", 1, "2026-03-01", "2026-03-01"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 종료일이_시작일보다_앞서면_거부된다() {
		String[] ab = twoEntities("pack-before");

		assertThatThrownBy(
				() -> insertRelation("pack-before", ab[0], ab[1], "R", "src", 1, "2026-03-01", "2026-02-28"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 시작일_없이는_저장되지_않는다() {
		String[] ab = twoEntities("pack-nofrom");

		assertThatThrownBy(() -> insertRelation("pack-nofrom", ab[0], ab[1], "R", "src", 1, null, null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 종료일_없는_관계가_저장된다() {
		String[] ab = twoEntities("pack-open");

		assertThatCode(() -> insertRelation("pack-open", ab[0], ab[1], "R", "src", 1, "2026-01-01", null))
			.doesNotThrowAnyException();
	}

	// ── 관계: 겹침 거부 ──────────────────────────────────────────

	@Test
	void 같은_관계의_유효기간이_겹치면_거부된다() {
		String[] ab = twoEntities("pack-ovl");
		insertRelation("pack-ovl", ab[0], ab[1], "R", "src", 1, "2026-03-01", "2026-06-01");

		// 2026-05-31 은 앞 관계의 종료일 전날이라 아직 유효하다.
		assertThatThrownBy(() -> insertRelation("pack-ovl", ab[0], ab[1], "R", "src", 1, "2026-05-31", "2026-07-01"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 종료일_없는_관계와_겹치면_거부된다() {
		String[] ab = twoEntities("pack-ovl-open");
		insertRelation("pack-ovl-open", ab[0], ab[1], "R", "src", 1, "2026-03-01", null);

		assertThatThrownBy(
				() -> insertRelation("pack-ovl-open", ab[0], ab[1], "R", "src", 1, "2030-01-01", "2030-02-01"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 맞닿는_유효기간은_겹침이_아니다() {
		String[] ab = twoEntities("pack-touch");
		insertRelation("pack-touch", ab[0], ab[1], "R", "src", 1, "2026-03-01", "2026-06-01");

		// 앞 관계의 종료일 당일에 시작한다. 종료일은 제외하므로 겹치지 않는다.
		assertThatCode(() -> insertRelation("pack-touch", ab[0], ab[1], "R", "src", 1, "2026-06-01", null))
			.doesNotThrowAnyException();
		// 앞 관계의 시작일 당일에 끝난다.
		assertThatCode(() -> insertRelation("pack-touch", ab[0], ab[1], "R", "src", 1, "2026-01-01", "2026-03-01"))
			.doesNotThrowAnyException();
	}

	@Test
	void 판이_다르면_겹치는_기간에도_둘_다_저장된다() {
		String[] ab = twoEntities("pack-edition");
		insertRelation("pack-edition", ab[0], ab[1], "R", "src", 1, "2026-03-01", null);

		assertThatCode(() -> insertRelation("pack-edition", ab[0], ab[1], "R", "src", 2, "2026-04-01", null))
			.doesNotThrowAnyException();
	}

	@Test
	void 입력_자료가_다르면_겹치는_기간에도_둘_다_저장된다() {
		String[] ab = twoEntities("pack-source");
		insertRelation("pack-source", ab[0], ab[1], "R", "src-1", 1, "2026-03-01", null);

		assertThatCode(() -> insertRelation("pack-source", ab[0], ab[1], "R", "src-2", 1, "2026-03-01", null))
			.doesNotThrowAnyException();
	}

	@Test
	void 관계_유형이나_방향이_다르면_겹침이_아니다() {
		String[] ab = twoEntities("pack-other");
		insertRelation("pack-other", ab[0], ab[1], "R", "src", 1, "2026-03-01", null);

		assertThatCode(() -> insertRelation("pack-other", ab[0], ab[1], "R2", "src", 1, "2026-03-01", null))
			.doesNotThrowAnyException();
		assertThatCode(() -> insertRelation("pack-other", ab[1], ab[0], "R", "src", 1, "2026-03-01", null))
			.doesNotThrowAnyException();
	}

	// ── 속성 값 ─────────────────────────────────────────────────

	@Test
	void 없는_개체의_속성값은_거부된다() {
		assertThatThrownBy(
				() -> insertAttribute("pack-attr-ref", "pack-attr-ref|T|missing", "name", "v", "src", 1, "2026-01-01",
						null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 속성값의_유효기간이_겹치면_출처가_달라도_거부된다() {
		String a = insertEntity("pack-attr-ovl", "T", "a");
		insertAttribute("pack-attr-ovl", a, "name", "v1", "src-1", 1, "2026-03-01", "2026-06-01");

		// 입력 자료도 판도 다르지만, 같은 개체의 같은 속성이라 거부된다.
		assertThatThrownBy(
				() -> insertAttribute("pack-attr-ovl", a, "name", "v2", "src-2", 5, "2026-05-01", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 속성값이_맞닿으면_저장된다() {
		String a = insertEntity("pack-attr-touch", "T", "a");
		insertAttribute("pack-attr-touch", a, "name", "v1", "src", 1, "2026-03-01", "2026-06-01");

		assertThatCode(() -> insertAttribute("pack-attr-touch", a, "name", "v2", "src", 2, "2026-06-01", null))
			.doesNotThrowAnyException();
	}

	@Test
	void 속성이_다르면_같은_기간에_저장된다() {
		String a = insertEntity("pack-attr-other", "T", "a");
		insertAttribute("pack-attr-other", a, "name", "v", "src", 1, "2026-03-01", null);

		assertThatCode(() -> insertAttribute("pack-attr-other", a, "size", "3", "src", 1, "2026-03-01", null))
			.doesNotThrowAnyException();
	}

	@Test
	void 속성값은_비어_있을_수_없다() {
		String a = insertEntity("pack-attr-null", "T", "a");

		assertThatThrownBy(() -> insertAttribute("pack-attr-null", a, "name", null, "src", 1, "2026-03-01", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 속성값의_종료일이_시작일보다_뒤가_아니면_거부된다() {
		String a = insertEntity("pack-attr-eq", "T", "a");

		assertThatThrownBy(
				() -> insertAttribute("pack-attr-eq", a, "name", "v", "src", 1, "2026-03-01", "2026-03-01"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ── 도우미 ──────────────────────────────────────────────────

	private List<String> columnsOf(String table) {
		return jdbc.queryForList("""
				select column_name from information_schema.columns
				where table_schema = 'public' and table_name = ?""", String.class, table);
	}

	/** 식별자는 값을 세로 막대로 이어 붙인 것이다 (D5 1절). 시험 자료의 값에는 세로 막대가 없다. */
	private String insertEntity(String packId, String type, String key) {
		String id = String.join("|", packId, type, key);
		jdbc.update("insert into entity (id, pack_id, entity_type, entity_key, recorded_at) values (?, ?, ?, ?, ?)",
				id, packId, type, key, RECORDED_AT);
		return id;
	}

	private String[] twoEntities(String packId) {
		return new String[] { insertEntity(packId, "T", "a"), insertEntity(packId, "T", "b") };
	}

	private void insertRelation(String packId, String sourceId, String targetId, String type, String inputSourceId,
			int editionNo, String validFrom, String validTo) {
		String id = String.join("|", sourceId, targetId, type, inputSourceId, String.valueOf(editionNo),
				String.valueOf(validFrom));
		jdbc.update("""
				insert into relation (id, pack_id, source_entity_id, target_entity_id, relation_type,
				                      valid_from, valid_to, input_source_id, edition_no, recorded_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, packId, sourceId, targetId, type, date(validFrom),
				date(validTo), inputSourceId, editionNo, RECORDED_AT);
	}

	private void insertAttribute(String packId, String entityId, String name, String value, String inputSourceId,
			int editionNo, String validFrom, String validTo) {
		String id = String.join("|", entityId, name, String.valueOf(validFrom));
		jdbc.update("""
				insert into attribute_value (id, pack_id, entity_id, attribute_name, value,
				                             valid_from, valid_to, input_source_id, edition_no, recorded_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, packId, entityId, name, value, date(validFrom),
				date(validTo), inputSourceId, editionNo, RECORDED_AT);
	}

	private static LocalDate date(String value) {
		return value == null ? null : LocalDate.parse(value);
	}

}
