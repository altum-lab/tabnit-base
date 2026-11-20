package com.altumlab.tabnit.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewRelation;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.core.ValidityPeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * SK-07 (서버 쪽): 시점 조회 API 가 규격(docs/api/openapi.yaml 의 getRelationsAsOf)대로 답한다.
 *
 * <p>D7 4절: 조회 시점에 유효한 관계만, 정해진 순서로 돌려주고, 응답에 어느 날짜로 답했는지 담는다.
 * 오류는 공통 오류 형식(RFC 9457 에 code 와 field 를 더한 것)으로 낸다.
 *
 * <p>시험 자료는 지어낸 값이다. 실제 PostgreSQL 에 core 의 저장 API 로 넣는다.
 */
@Import({ TestcontainersConfiguration.class, RelationsApiTests.FixedClock.class })
@SpringBootTest
class RelationsApiTests {

	private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

	private static final Provenance SRC_1 = new Provenance("src", 1);

	/**
	 * 서버의 시계를 고정한다. 이 순간은 협정 세계시로는 2026-02-28 이고 Asia/Seoul 로는 2026-03-01 이다.
	 * 조회 시점이 없을 때의 "오늘"이 설치 설정의 시간대(기본 Asia/Seoul)로 정해지는지 본다 (D2 AD-40).
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClock {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-02-28T15:30:00Z"), ZoneOffset.UTC);
		}

	}

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	MockMvc mvc;

	RelationStore store;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		store = new RelationStore(dataSource);
	}

	// ── 수용 기준: 조회 시점에 유효한 관계만 ─────────────────────────

	@Test
	void 조회_시점에_유효한_관계만_돌려주고_답한_날짜를_담는다() throws Exception {
		String pack = "api-valid";
		EntityRef ga = entity(pack, "ga");
		String r1 = relation(ga, entity(pack, "na"), "R", "2026-03-01", "2026-06-01");
		relation(ga, entity(pack, "da"), "R", "2026-06-01", null);
		relation(ga, entity(pack, "ra"), "R", "2026-01-01", "2026-03-01");

		mvc.perform(get("/api/v1/relations").param("entityId", ga.id()).param("asOf", "2026-03-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.asOf").value("2026-03-01"))
			.andExpect(jsonPath("$.entityId").value(ga.id()))
			.andExpect(jsonPath("$.relations[*].id", contains(r1)))
			.andExpect(jsonPath("$.relations[0].relationType").value("R"))
			.andExpect(jsonPath("$.relations[0].sourceEntityId").value(ga.id()))
			.andExpect(jsonPath("$.relations[0].targetEntityId").value(pack + "|T|na"))
			.andExpect(jsonPath("$.relations[0].validFrom").value("2026-03-01"))
			.andExpect(jsonPath("$.relations[0].validTo").value("2026-06-01"))
			.andExpect(jsonPath("$.relations[0].provenance.inputSourceId").value("src"))
			.andExpect(jsonPath("$.relations[0].provenance.editionNo").value(1))
			.andExpect(jsonPath("$.relations[0].recordedAt").value("2026-01-01T00:00:00Z"));
	}

	@Test
	void 종료일이_없는_관계는_validTo_가_null_로_나온다() throws Exception {
		String pack = "api-open";
		EntityRef a = entity(pack, "a");
		relation(a, entity(pack, "b"), "R", "2026-01-01", null);

		mvc.perform(get("/api/v1/relations").param("entityId", a.id()).param("asOf", "2026-03-01"))
			.andExpect(status().isOk())
			// 항목을 빼지 않고 null 로 적는다 (D7 2절).
			.andExpect(jsonPath("$.relations[0]").value(org.hamcrest.Matchers.hasKey("validTo")))
			.andExpect(jsonPath("$.relations[0].validTo").value(nullValue()));
	}

	@Test
	void 순서는_관계_유형_상대_개체_관계_식별자_순이다() throws Exception {
		String pack = "api-order";
		EntityRef me = entity(pack, "me");
		EntityRef x = entity(pack, "x");
		EntityRef y = entity(pack, "y");
		String bToY = relation(me, y, "b-type", "2026-01-01", null);
		String aToY = relation(me, y, "a-type", "2026-01-01", null);
		String aFromX = relation(x, me, "a-type", "2026-01-01", null);
		String aToX = relation(me, x, "a-type", "2026-01-01", null);

		mvc.perform(get("/api/v1/relations").param("entityId", me.id()).param("asOf", "2026-06-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.relations[*].id", contains(aToX, aFromX, aToY, bToY)));
	}

	// ── 조회 시점의 기본값 ──────────────────────────────────────────

	@Test
	void 조회_시점이_없으면_설치_설정의_시간대에서의_오늘로_답하고_그_날짜를_담는다() throws Exception {
		String pack = "api-today";
		EntityRef a = entity(pack, "a");
		// 시계는 협정 세계시 2026-02-28, Asia/Seoul 2026-03-01 이다. 이 관계는 2026-03-01 에 시작한다.
		String startsToday = relation(a, entity(pack, "b"), "R", "2026-03-01", null);

		mvc.perform(get("/api/v1/relations").param("entityId", a.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.asOf").value("2026-03-01"))
			.andExpect(jsonPath("$.relations[*].id", contains(startsToday)));
	}

	// ── 결과 없음과 오류 ────────────────────────────────────────────

	@Test
	void 유효한_관계가_없으면_빈_목록이다() throws Exception {
		EntityRef alone = entity("api-empty", "alone");

		mvc.perform(get("/api/v1/relations").param("entityId", alone.id()).param("asOf", "2026-03-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.asOf").value("2026-03-01"))
			.andExpect(jsonPath("$.relations", hasSize(0)));
	}

	@Test
	void 없는_개체는_404_와_공통_오류_형식으로_알린다() throws Exception {
		mvc.perform(get("/api/v1/relations").param("entityId", "api-none|T|missing").param("asOf", "2026-03-01"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.code").value("ENTITY_NOT_FOUND"))
			.andExpect(jsonPath("$.title").isNotEmpty())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("api-none|T|missing")));
	}

	@Test
	void 조회_시점의_형식이_틀리면_400_과_틀린_항목을_알린다() throws Exception {
		EntityRef a = entity("api-badasof", "a");

		for (String bad : new String[] { "2026-3-1", "20260301", "2026-02-30", "내일", "" }) {
			mvc.perform(get("/api/v1/relations").param("entityId", a.id()).param("asOf", bad))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.code").value("INVALID_AS_OF"))
				.andExpect(jsonPath("$.field").value("asOf"))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("2026-03-01")));
		}
	}

	@Test
	void 개체의_id_가_없으면_400_과_틀린_항목을_알린다() throws Exception {
		mvc.perform(get("/api/v1/relations").param("asOf", "2026-03-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_ENTITY_ID"))
			.andExpect(jsonPath("$.field").value("entityId"));
		mvc.perform(get("/api/v1/relations").param("entityId", "").param("asOf", "2026-03-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_ENTITY_ID"));
	}

	@Test
	void 오류_응답에_내부_예외_문구가_나가지_않는다() throws Exception {
		// NUL 글자가 든 id 는 저장소가 받지 않는다. 그래도 내부 문구나 스택이 나가면 안 된다.
		mvc.perform(get("/api/v1/relations").param("entityId", "a\u0000b").param("asOf", "2026-03-01"))
			.andExpect(status().is5xxServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL"))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))));
	}

	@Test
	void 없는_주소와_허용하지_않는_방식은_서버_오류가_아니라_제_상태로_답한다() throws Exception {
		// 프레임워크가 내는 오류를 "서버 쪽의 문제"(500)로 뭉개지 않는다.
		mvc.perform(get("/api/v1/no-such-thing"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(404));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/relations"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.status").value(405));
	}

	@Test
	void 같은_요청을_두_번_하면_응답이_같다() throws Exception {
		String pack = "api-twice";
		EntityRef me = entity(pack, "me");
		for (String key : new String[] { "k3", "k1", "k2" }) {
			relation(me, entity(pack, key), "R", "2026-01-01", null);
		}

		String first = mvc.perform(get("/api/v1/relations").param("entityId", me.id()).param("asOf", "2026-06-01"))
			.andReturn().getResponse().getContentAsString();
		String second = mvc.perform(get("/api/v1/relations").param("entityId", me.id()).param("asOf", "2026-06-01"))
			.andReturn().getResponse().getContentAsString();

		org.assertj.core.api.Assertions.assertThat(first).contains("k1").isEqualTo(second);
	}

	// ── 도우미 ──────────────────────────────────────────────────

	private EntityRef entity(String pack, String key) {
		EntityRef ref = new EntityRef(pack, "T", key);
		store.saveEntity(ref, RECORDED_AT);
		return ref;
	}

	private String relation(EntityRef source, EntityRef target, String type, String from, String to) {
		ValidityPeriod period = new ValidityPeriod(LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
		return store.saveRelation(new NewRelation(source, target, type, period, SRC_1), RECORDED_AT);
	}

}
