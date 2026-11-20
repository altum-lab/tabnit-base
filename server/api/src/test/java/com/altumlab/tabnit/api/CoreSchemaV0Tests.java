package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
 * <p>거부를 확인하는 시험은 어느 제약이 거부했는지까지 본다. 다른 제약 때문에 우연히 통과하지 않게 하려는 것이다.
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

	// 열마다 "이름 형식 null허용 정렬규칙 기본값" 을 본다.
	// 식별자와 이름 열은 정렬 규칙이 C 이고, 어느 열에도 기본값이 없다 (기록한 때 포함).

	@Test
	void 개체_테이블의_열이_D5와_같다() {
		assertThat(columnsOf("entity")).containsExactlyInAnyOrder(
				"id text NO C -",
				"pack_id text NO C -",
				"entity_type text NO C -",
				"entity_key text NO C -",
				"recorded_at timestamp with time zone NO - -");
	}

	@Test
	void 속성값_테이블의_열이_D5와_같다() {
		assertThat(columnsOf("attribute_value")).containsExactlyInAnyOrder(
				"id text NO C -",
				"pack_id text NO C -",
				"entity_id text NO C -",
				"attribute_name text NO C -",
				"value text NO - -",
				"valid_from date NO - -",
				"valid_to date YES - -",
				"input_source_id text NO C -",
				"edition_no integer NO - -",
				"recorded_at timestamp with time zone NO - -");
	}

	@Test
	void 관계_테이블의_열이_D5와_같다() {
		assertThat(columnsOf("relation")).containsExactlyInAnyOrder(
				"id text NO C -",
				"pack_id text NO C -",
				"source_entity_id text NO C -",
				"target_entity_id text NO C -",
				"relation_type text NO C -",
				"valid_from date NO - -",
				"valid_to date YES - -",
				"input_source_id text NO C -",
				"edition_no integer NO - -",
				"recorded_at timestamp with time zone NO - -");
	}

	@Test
	void 뼈대_확인용_테이블은_그대로_있다() {
		String schema = jdbc.queryForObject("select value from tabnit_meta where key = 'schema'", String.class);

		assertThat(schema).isEqualTo("v0");
	}

	// ── 개체 ───────────────────────────────────────────────────

	@Test
	void 식별자가_같은_개체는_거부된다() {
		insertEntity("pack-dup-id", "T", "k1");

		assertRejectedBy("entity_pk", () -> insertEntity("pack-dup-id", "T", "k1"));
	}

	@Test
	void 식별자가_달라도_Pack과_유형과_키가_같은_개체는_거부된다() {
		insertEntity("pack-dup-key", "T", "k1");

		assertRejectedBy("entity_key_uq",
				() -> insertEntityWithId("다른-식별자", "pack-dup-key", "T", "k1"));
	}

	@Test
	void 기록한_때_없이는_저장되지_않는다() {
		assertRejectedBy("recorded_at", () -> jdbc.update(
				"insert into entity (id, pack_id, entity_type, entity_key) values (?, ?, ?, ?)", "pack-norec|T|k1",
				"pack-norec", "T", "k1"));
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

	@Test
	void 관계_유형도_글자_코드_순으로_정렬된다() {
		String[] ab = twoEntities("pack-sort-type");
		insertRelation("pack-sort-type", ab[0], ab[1], "a", "src", 1, "2026-01-01", null);
		insertRelation("pack-sort-type", ab[0], ab[1], "가", "src", 1, "2026-01-01", null);
		insertRelation("pack-sort-type", ab[0], ab[1], "B", "src", 1, "2026-01-01", null);

		List<String> types = jdbc.queryForList(
				"select relation_type from relation where pack_id = 'pack-sort-type' order by relation_type, id",
				String.class);

		assertThat(types).containsExactly("B", "a", "가");
	}

	@Test
	void 관계나_속성값이_가리키는_개체는_지울_수_없다() {
		String[] ab = twoEntities("pack-nodelete");
		insertRelation("pack-nodelete", ab[0], ab[1], "R", "src", 1, "2026-01-01", null);
		String c = insertEntity("pack-nodelete", "T", "c");
		insertAttribute("pack-nodelete", c, "name", "v", "src", 1, "2026-01-01", null);

		assertRejectedBy("relation_source_fk", () -> jdbc.update("delete from entity where id = ?", ab[0]));
		assertRejectedBy("relation_target_fk", () -> jdbc.update("delete from entity where id = ?", ab[1]));
		assertRejectedBy("attribute_value_entity_fk", () -> jdbc.update("delete from entity where id = ?", c));
	}

	// ── 관계: 참조와 Pack 경계 ────────────────────────────────────

	@Test
	void 없는_개체를_가리키는_관계는_거부된다() {
		String a = insertEntity("pack-ref", "T", "a");

		assertRejectedBy("relation_target_fk",
				() -> insertRelation("pack-ref", a, "pack-ref|T|missing", "R", "src", 1, "2026-01-01", null));
		assertRejectedBy("relation_source_fk",
				() -> insertRelation("pack-ref", "pack-ref|T|missing", a, "R", "src", 1, "2026-01-01", null));
	}

	@Test
	void 도착_개체가_다른_Pack이면_거부된다() {
		String a = insertEntity("pack-one", "T", "a");
		String b = insertEntity("pack-two", "T", "b");

		assertRejectedBy("relation_target_fk",
				() -> insertRelation("pack-one", a, b, "R", "src", 1, "2026-01-01", null));
	}

	@Test
	void 출발_개체가_다른_Pack이면_거부된다() {
		String a = insertEntity("pack-three", "T", "a");
		String b = insertEntity("pack-four", "T", "b");

		assertRejectedBy("relation_source_fk",
				() -> insertRelation("pack-four", a, b, "R", "src", 1, "2026-01-01", null));
	}

	@Test
	void 두_개체는_같은_Pack인데_관계의_Pack만_다르면_거부된다() {
		String[] ab = twoEntities("pack-five");

		assertRejectedBy("relation_source_fk",
				() -> insertRelation("pack-six", ab[0], ab[1], "R", "src", 1, "2026-01-01", null));
	}

	@Test
	void 출발과_도착이_같은_관계는_거부된다() {
		String a = insertEntity("pack-self", "T", "a");

		assertRejectedBy("relation_not_self_ck",
				() -> insertRelation("pack-self", a, a, "R", "src", 1, "2026-01-01", null));
	}

	// ── 관계: 유효기간 ───────────────────────────────────────────

	@Test
	void 종료일이_시작일과_같으면_거부된다() {
		String[] ab = twoEntities("pack-eq");

		assertRejectedBy("relation_period_ck",
				() -> insertRelation("pack-eq", ab[0], ab[1], "R", "src", 1, "2026-03-01", "2026-03-01"));
	}

	@Test
	void 종료일이_시작일보다_앞서면_거부된다() {
		String[] ab = twoEntities("pack-before");

		// 검사 제약이 없어도 날짜 범위를 만들다 오류가 난다. 어느 쪽이 먼저 걸리는지는 정해져 있지 않으므로
		// 거부된다는 것만 본다. 검사 제약 자체는 위의 "같으면 거부" 시험이 확인한다.
		assertThatThrownBy(
				() -> insertRelation("pack-before", ab[0], ab[1], "R", "src", 1, "2026-03-01", "2026-02-28"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 시작일_없이는_저장되지_않는다() {
		String[] ab = twoEntities("pack-nofrom");

		assertRejectedBy("valid_from",
				() -> insertRelation("pack-nofrom", ab[0], ab[1], "R", "src", 1, null, null));
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
		assertRejectedBy("relation_no_overlap",
				() -> insertRelation("pack-ovl", ab[0], ab[1], "R", "src", 1, "2026-05-31", "2026-07-01"));
	}

	@Test
	void 종료일_없는_관계와_겹치면_거부된다() {
		String[] ab = twoEntities("pack-ovl-open");
		insertRelation("pack-ovl-open", ab[0], ab[1], "R", "src", 1, "2026-03-01", null);

		assertRejectedBy("relation_no_overlap",
				() -> insertRelation("pack-ovl-open", ab[0], ab[1], "R", "src", 1, "2030-01-01", "2030-02-01"));
		assertRejectedBy("relation_no_overlap",
				() -> insertRelation("pack-ovl-open", ab[0], ab[1], "R", "src", 1, "2020-01-01", null));
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
		assertRejectedBy("attribute_value_entity_fk", () -> insertAttribute("pack-attr-ref",
				"pack-attr-ref|T|missing", "name", "v", "src", 1, "2026-01-01", null));
	}

	@Test
	void 개체와_다른_Pack의_속성값은_거부된다() {
		String a = insertEntity("pack-attr-one", "T", "a");

		assertRejectedBy("attribute_value_entity_fk",
				() -> insertAttribute("pack-attr-two", a, "name", "v", "src", 1, "2026-01-01", null));
	}

	@Test
	void 속성값의_유효기간이_겹치면_출처가_달라도_거부된다() {
		String a = insertEntity("pack-attr-ovl", "T", "a");
		insertAttribute("pack-attr-ovl", a, "name", "v1", "src-1", 1, "2026-03-01", "2026-06-01");

		// 입력 자료도 판도 다르지만, 같은 개체의 같은 속성이라 거부된다.
		assertRejectedBy("attribute_value_no_overlap",
				() -> insertAttribute("pack-attr-ovl", a, "name", "v2", "src-2", 5, "2026-05-01", null));
	}

	@Test
	void 종료일_없는_속성값과_겹치면_거부된다() {
		String a = insertEntity("pack-attr-open", "T", "a");
		insertAttribute("pack-attr-open", a, "name", "v1", "src", 1, "2026-03-01", null);

		assertRejectedBy("attribute_value_no_overlap",
				() -> insertAttribute("pack-attr-open", a, "name", "v2", "src", 2, "2030-01-01", "2030-02-01"));
	}

	@Test
	void 속성값이_맞닿으면_저장된다() {
		String a = insertEntity("pack-attr-touch", "T", "a");
		insertAttribute("pack-attr-touch", a, "name", "v1", "src", 1, "2026-03-01", "2026-06-01");

		// 앞 값의 종료일 당일에 시작한다.
		assertThatCode(() -> insertAttribute("pack-attr-touch", a, "name", "v2", "src", 2, "2026-06-01", null))
			.doesNotThrowAnyException();
		// 앞 값의 시작일 당일에 끝난다.
		assertThatCode(
				() -> insertAttribute("pack-attr-touch", a, "name", "v0", "src", 1, "2026-01-01", "2026-03-01"))
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

		assertRejectedBy("value",
				() -> insertAttribute("pack-attr-null", a, "name", null, "src", 1, "2026-03-01", null));
	}

	@Test
	void 속성값의_종료일이_시작일과_같으면_거부된다() {
		String a = insertEntity("pack-attr-eq", "T", "a");

		assertRejectedBy("attribute_value_period_ck",
				() -> insertAttribute("pack-attr-eq", a, "name", "v", "src", 1, "2026-03-01", "2026-03-01"));
	}

	// ── 끝없는 날짜, 판 번호, 빈 값 ─────────────────────────────────

	@Test
	void 관계의_유효기간에_끝없는_날짜는_거부된다() {
		String[] ab = twoEntities("pack-inf");

		// 종료일이 없다는 것은 valid_to 가 비어 있는 것 하나로만 나타낸다.
		assertRejectedBy("relation_period_finite_ck",
				() -> insertRelation("pack-inf", ab[0], ab[1], "R", "src", 1, "2026-03-01", "infinity"));
		assertRejectedBy("relation_period_finite_ck",
				() -> insertRelation("pack-inf", ab[0], ab[1], "R", "src", 1, "-infinity", "2026-03-01"));
		assertRejectedBy("relation_period_finite_ck",
				() -> insertRelation("pack-inf", ab[0], ab[1], "R", "src", 1, "-infinity", null));
	}

	@Test
	void 속성값의_유효기간에_끝없는_날짜는_거부된다() {
		String a = insertEntity("pack-attr-inf", "T", "a");

		assertRejectedBy("attribute_value_period_finite_ck",
				() -> insertAttribute("pack-attr-inf", a, "name", "v", "src", 1, "2026-03-01", "infinity"));
		assertRejectedBy("attribute_value_period_finite_ck",
				() -> insertAttribute("pack-attr-inf", a, "name", "v", "src", 1, "-infinity", null));
	}

	@Test
	void 판_번호는_1부터다() {
		String[] ab = twoEntities("pack-edno");

		assertRejectedBy("relation_edition_no_ck",
				() -> insertRelation("pack-edno", ab[0], ab[1], "R", "src", 0, "2026-03-01", null));
		assertRejectedBy("relation_edition_no_ck",
				() -> insertRelation("pack-edno", ab[0], ab[1], "R", "src", -1, "2026-03-01", null));
		assertRejectedBy("attribute_value_edition_no_ck",
				() -> insertAttribute("pack-edno", ab[0], "name", "v", "src", 0, "2026-03-01", null));
		assertThatCode(() -> insertRelation("pack-edno", ab[0], ab[1], "R", "src", 1, "2026-03-01", null))
			.doesNotThrowAnyException();
	}

	@Test
	void 개체의_Pack과_유형과_키는_빈_글자일_수_없다() {
		assertRejectedBy("entity_not_blank_ck", () -> insertEntityWithId("blank-1", "", "T", "k"));
		assertRejectedBy("entity_not_blank_ck", () -> insertEntityWithId("blank-2", "pack-blank", "", "k"));
		assertRejectedBy("entity_not_blank_ck", () -> insertEntityWithId("blank-3", "pack-blank", "T", ""));
		assertRejectedBy("entity_not_blank_ck", () -> insertEntityWithId("", "pack-blank", "T", "k"));
	}

	@Test
	void 관계의_유형과_입력_자료는_빈_글자일_수_없다() {
		String[] ab = twoEntities("pack-rel-blank");

		assertRejectedBy("relation_not_blank_ck",
				() -> insertRelation("pack-rel-blank", ab[0], ab[1], "", "src", 1, "2026-03-01", null));
		assertRejectedBy("relation_not_blank_ck",
				() -> insertRelation("pack-rel-blank", ab[0], ab[1], "R", "", 1, "2026-03-01", null));
	}

	@Test
	void 속성의_이름과_입력_자료는_빈_글자일_수_없다() {
		String a = insertEntity("pack-attr-blank", "T", "a");

		assertRejectedBy("attribute_value_not_blank_ck",
				() -> insertAttribute("pack-attr-blank", a, "", "v", "src", 1, "2026-03-01", null));
		assertRejectedBy("attribute_value_not_blank_ck",
				() -> insertAttribute("pack-attr-blank", a, "name", "v", "", 1, "2026-03-01", null));
	}

	@Test
	void 속성값은_빈_글자여도_저장된다() {
		String a = insertEntity("pack-attr-empty", "T", "a");

		// 값이 없는 것(행이 없음)과 빈 글자인 값은 다르다. 빈 글자를 허용할지는 속성의 형식(D6)이 정한다.
		assertThatCode(() -> insertAttribute("pack-attr-empty", a, "name", "", "src", 1, "2026-03-01", null))
			.doesNotThrowAnyException();
	}

	// ── 도우미 ──────────────────────────────────────────────────

	/** 저장이 거부되고, 거부한 것이 그 제약(또는 그 열의 null 금지)인지 본다. */
	private void assertRejectedBy(String constraintOrColumn, ThrowingCallable insert) {
		assertThatThrownBy(insert).isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("\"" + constraintOrColumn + "\"");
	}

	/** 열마다 "이름 형식 null허용 정렬규칙 기본값". 없는 값은 "-" 로 적는다. */
	private List<String> columnsOf(String table) {
		return jdbc.queryForList("""
				select column_name || ' ' || data_type || ' ' || is_nullable || ' '
				       || coalesce(collation_name, '-') || ' ' || coalesce(column_default, '-')
				from information_schema.columns
				where table_schema = 'public' and table_name = ?""", String.class, table);
	}

	/** 식별자는 값을 세로 막대로 이어 붙인 것이다 (D5 1절). 시험 자료의 값에는 세로 막대가 없다. */
	private String insertEntity(String packId, String type, String key) {
		return insertEntityWithId(String.join("|", packId, type, key), packId, type, key);
	}

	private String insertEntityWithId(String id, String packId, String type, String key) {
		jdbc.update("insert into entity (id, pack_id, entity_type, entity_key, recorded_at) values (?, ?, ?, ?, ?)",
				id, packId, type, key, RECORDED_AT);
		return id;
	}

	private String[] twoEntities(String packId) {
		return new String[] { insertEntity(packId, "T", "a"), insertEntity(packId, "T", "b") };
	}

	/** 날짜는 글자로 넘겨 데이터베이스에서 바꾼다. 끝없는 날짜(infinity)도 넣어 볼 수 있다. */
	private void insertRelation(String packId, String sourceId, String targetId, String type, String inputSourceId,
			int editionNo, String validFrom, String validTo) {
		String id = String.join("|", sourceId, targetId, type, inputSourceId, String.valueOf(editionNo),
				String.valueOf(validFrom));
		jdbc.update("""
				insert into relation (id, pack_id, source_entity_id, target_entity_id, relation_type,
				                      valid_from, valid_to, input_source_id, edition_no, recorded_at)
				values (?, ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?)""", id, packId, sourceId, targetId, type, validFrom,
				validTo, inputSourceId, editionNo, RECORDED_AT);
	}

	private void insertAttribute(String packId, String entityId, String name, String value, String inputSourceId,
			int editionNo, String validFrom, String validTo) {
		String id = String.join("|", entityId, name, String.valueOf(validFrom));
		jdbc.update("""
				insert into attribute_value (id, pack_id, entity_id, attribute_name, value,
				                             valid_from, valid_to, input_source_id, edition_no, recorded_at)
				values (?, ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?)""", id, packId, entityId, name, value, validFrom,
				validTo, inputSourceId, editionNo, RECORDED_AT);
	}

}
