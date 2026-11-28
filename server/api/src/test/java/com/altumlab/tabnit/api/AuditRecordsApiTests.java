package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditEntry;
import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditOutcome;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * E-22: 감사 기록을 보는 API (docs/api/openapi.yaml 의 listAuditRecords, D7 "감사 기록 보기", D2 AD-414).
 *
 * <p>시험들이 한 저장소를 함께 쓴다. 시험마다 다른 사람의 이름으로 요청을 보내고 그 사람의 줄만 걸러 본다.
 * 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuditRecordsApiTests {

	private static final RequestPostProcessor ADMIN = user("ar-admin").roles("ADMIN");

	@Autowired
	WebApplicationContext context;

	@Autowired
	AuditLog audit;

	@Autowired
	ZoneId installationZone;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	// ── 수용 기준: 보낸 요청이 모두 조회 결과에 있다 (누락 0) ───────────────────────

	@Test
	void 보낸_요청이_하나도_빠지지_않고_조회_결과에_있다() throws Exception {
		RequestPostProcessor reader = user("ar-complete").roles("OFFICER");
		// 성공, 틀린 입력, 없는 대상, 권한 없음: 결과가 무엇이든 요청마다 남는다. 처리에 들어간 요청은 두 줄(시작, 결과)이고,
		// 권한이 없어 들어가지 못한 요청은 거부의 한 줄이다.
		this.mvc.perform(get("/api/v1/packs").with(reader)).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/relations").with(reader)).andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/v1/relations").with(reader).param("entityId", "ar|T|없는-개체"))
			.andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/pack-loads").with(reader).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"packName\":\"없는-Pack\"}"))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/v1/actions").with(reader).param("packId", "ar-없는-Pack")).andExpect(status().isNotFound());

		String body = list().param("actor", "ar-complete").andExpect(status().isOk())
			.andExpect(jsonPath("$.actor", is("ar-complete")))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		// 요청 다섯에 아홉 줄이다 (넷은 두 줄씩, 거부된 하나는 한 줄). 가장 최근 것부터 나온다.
		assertThat(JsonPath.<Integer>read(body, "$.total")).isEqualTo(9);
		List<String> outcomes = JsonPath.read(body, "$.records[*].outcome");
		assertThat(outcomes).containsExactly("FAILED", "STARTED", "DENIED", "FAILED", "STARTED", "FAILED", "STARTED", "SUCCESS",
				"STARTED");
		List<String> actions = JsonPath.read(body, "$.records[*].action");
		assertThat(actions.subList(0, 2)).containsExactly("listActions", "listActions");
		assertThat(actions.subList(3, 9)).containsExactly("getRelationsAsOf", "getRelationsAsOf", "getRelationsAsOf",
				"getRelationsAsOf", "listPacks", "listPacks");
		// 거부의 줄: 무엇을 하려 했는지와 응답의 상태가 남는다.
		assertThat(actions.get(2)).isNotBlank();
		assertThat(JsonPath.<Integer>read(body, "$.records[2].httpStatus")).isEqualTo(403);
		List<Integer> ids = JsonPath.read(body, "$.records[*].id");
		assertThat(ids).isSortedAccordingTo((left, right) -> Integer.compare(right, left)).doesNotHaveDuplicates();
		// 결과의 줄은 시작의 줄을 가리키고 응답의 상태를 담는다.
		assertThat(JsonPath.<Integer>read(body, "$.records[0].startedId")).isEqualTo(ids.get(1));
		assertThat(JsonPath.<Integer>read(body, "$.records[0].httpStatus")).isEqualTo(404);
		assertThat(JsonPath.<Object>read(body, "$.records[1].httpStatus")).isNull();
		assertThat(JsonPath.<String>read(body, "$.records[0].occurredAt")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
	}

	// ── 수용 기준: 누가, 언제, 무엇으로 거른다 ─────────────────────────────

	@Test
	void 누가와_무엇을과_결과로_거른다() throws Exception {
		RequestPostProcessor reader = user("ar-filter").roles("OFFICER");
		this.mvc.perform(get("/api/v1/packs").with(reader)).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/relations").with(reader)).andExpect(status().isBadRequest());

		list().param("actor", "ar-filter").andExpect(jsonPath("$.total", is(4)));
		list().param("actor", "ar-filter").param("action", "listPacks").andExpect(jsonPath("$.total", is(2)))
			.andExpect(jsonPath("$.action", is("listPacks")));
		list().param("actor", "ar-filter").param("outcome", "FAILED").andExpect(jsonPath("$.total", is(1)))
			.andExpect(jsonPath("$.records[0].action", is("getRelationsAsOf")))
			.andExpect(jsonPath("$.outcome", is("FAILED")));
		list().param("actor", "ar-filter").param("action", "listPacks").param("outcome", "FAILED")
			.andExpect(jsonPath("$.total", is(0)))
			.andExpect(jsonPath("$.records", hasSize(0)));
		// 이름의 일부로는 찾지 않는다.
		list().param("actor", "ar-filt").andExpect(jsonPath("$.total", is(0)));
	}

	@Test
	void 언제로_거른다_날짜는_설치_설정의_시간대의_하루이고_끝_날짜를_포함한다() throws Exception {
		String who = "ar-when";
		// 설치 설정의 시간대에서 2026-03-10 의 하루와 그 앞뒤.
		Instant dayStart = LocalDate.parse("2026-03-10").atStartOfDay(this.installationZone).toInstant();
		Instant nextDayStart = LocalDate.parse("2026-03-11").atStartOfDay(this.installationZone).toInstant();
		long before = entry(who, dayStart.minusMillis(1));
		long first = entry(who, dayStart);
		long last = entry(who, nextDayStart.minusMillis(1));
		long after = entry(who, nextDayStart);

		assertThat(ids(list().param("actor", who).param("from", "2026-03-10").param("to", "2026-03-10")))
			.containsExactly(last, first);
		assertThat(ids(list().param("actor", who).param("from", "2026-03-10"))).containsExactly(after, last, first);
		assertThat(ids(list().param("actor", who).param("to", "2026-03-09"))).containsExactly(before);
		assertThat(ids(list().param("actor", who).param("from", "2026-03-09").param("to", "2026-03-11")))
			.containsExactly(after, last, first, before);
		list().param("actor", who).param("from", "2026-03-10").param("to", "2026-03-10")
			.andExpect(jsonPath("$.from", is("2026-03-10")))
			.andExpect(jsonPath("$.to", is("2026-03-10")));
	}

	// ── 수용 기준: 나눠 준다 ──────────────────────────────────────

	@Test
	void 나눠_받은_것을_이으면_한_번에_받은_것과_순서까지_같다() throws Exception {
		String who = "ar-page";
		List<Long> written = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			written.add(entry(who, Instant.parse("2026-06-01T09:00:00Z").plusSeconds(i)));
		}
		java.util.Collections.reverse(written);

		List<Long> joined = new ArrayList<>();
		for (int offset = 0; offset < 7; offset += 3) {
			ResultActions page = list().param("actor", who).param("offset", String.valueOf(offset)).param("limit", "3")
				.andExpect(jsonPath("$.total", is(7)))
				.andExpect(jsonPath("$.offset", is(offset)))
				.andExpect(jsonPath("$.limit", is(3)));
			joined.addAll(ids(page));
		}

		assertThat(joined).containsExactlyElementsOf(written);
		list().param("actor", who).param("offset", "7").andExpect(jsonPath("$.records", hasSize(0)))
			.andExpect(jsonPath("$.total", is(7)));
	}

	@Test
	void 맨_위의_id_를_붙들고_넘기면_보는_사이에_줄이_늘어도_겹치지_않는다() throws Exception {
		// 거르지 않고 본다: 이 조회 자체가 볼 때마다 두 줄을 더한다 (D2 AD-424).
		for (int i = 0; i < 6; i++) {
			entry("ar-pin", Instant.parse("2026-06-01T09:00:00Z").plusSeconds(i));
		}
		ResultActions first = list().param("limit", "3").andExpect(status().isOk());
		List<Long> firstIds = ids(first);
		int total = JsonPath.read(first.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.total");
		String pin = String.valueOf(firstIds.get(0));

		// 붙들지 않으면 밀린다: 다음 쪽에 앞 쪽의 줄이 다시 나온다.
		assertThat(ids(list().param("limit", "3").param("offset", "3"))).containsAnyElementsOf(firstIds);

		// 붙들면 전체 수가 그대로이고, 다음 쪽은 앞 쪽의 바로 아래에서 이어진다.
		ResultActions second = list().param("limit", "3").param("offset", "3").param("maxId", pin)
			.andExpect(jsonPath("$.total", is(total)))
			.andExpect(jsonPath("$.maxId", is(firstIds.get(0).intValue())));
		List<Long> secondIds = ids(second);
		assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
		assertThat(secondIds.get(0)).isLessThan(firstIds.get(2));
		// 붙든 채로 첫 쪽을 다시 받으면 처음과 같다.
		assertThat(ids(list().param("limit", "3").param("maxId", pin))).containsExactlyElementsOf(firstIds);

		problem(list().param("maxId", "0"), "INVALID_MAX_ID", "maxId");
		problem(list().param("maxId", "abc"), "INVALID_MAX_ID", "maxId");
		problem(list().param("maxId", ""), "INVALID_MAX_ID", "maxId");
	}

	// ── 수용 기준: 담당자는 403, 보는 것도 남는다, 고치는 길이 없다 ────────────────────

	@Test
	void 담당자가_부르면_403_이고_로그인하지_않으면_401_이며_거부도_감사_기록에_남는다() throws Exception {
		RequestPostProcessor officer = user("ar-officer").roles("OFFICER");

		this.mvc.perform(get("/api/v1/audit-records").with(officer)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("FORBIDDEN")));
		this.mvc.perform(get("/api/v1/audit-records")).andExpect(status().isUnauthorized());

		list().param("actor", "ar-officer").andExpect(jsonPath("$.total", is(1)))
			.andExpect(jsonPath("$.records[0].outcome", is("DENIED")))
			.andExpect(jsonPath("$.records[0].httpStatus", is(403)));
	}

	@Test
	void 담당자는_다른_방식이나_닮은_주소로도_감사_기록을_받지_못한다() throws Exception {
		RequestPostProcessor officer = user("ar-officer-variants").roles("OFFICER");

		// 다른 방식: 주소가 같으면 관리자만이다.
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head("/api/v1/audit-records")
				.with(officer))
			.andExpect(status().isForbidden());
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/audit-records")
				.with(officer))
			.andExpect(status().isForbidden());
		// 닮은 주소: 그런 API 가 없다. 감사 기록이 나가지 않는다.
		for (String path : List.of("/api/v1/audit-records/", "/api/v1/AUDIT-RECORDS", "/api/v1/audit-records.json",
				"/api/v1/audit-records/1")) {
			String body = this.mvc.perform(get(path).with(officer)).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
			int status = this.mvc.perform(get(path).with(officer)).andReturn().getResponse().getStatus();
			assertThat(status).as(path).isIn(403, 404);
			assertThat(body).as(path).doesNotContain("\"records\"");
		}
	}

	@Test
	void 받는_날짜의_양_끝도_받는다() throws Exception {
		list().param("actor", "ar-아무도-아닌-사람").param("from", "0001-01-01").param("to", "9999-12-31")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total", is(0)));
	}

	@Test
	void 감사_기록을_보는_것도_감사_기록에_남는다() throws Exception {
		RequestPostProcessor viewer = user("ar-viewer").roles("ADMIN");

		this.mvc.perform(get("/api/v1/audit-records").with(viewer).param("actor", "ar-아무도-아닌-사람").param("outcome", "DENIED"))
			.andExpect(status().isOk());

		String body = list().param("actor", "ar-viewer").andExpect(jsonPath("$.total", is(2))).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(JsonPath.<List<String>>read(body, "$.records[*].action")).containsOnly("listAuditRecords");
		assertThat(JsonPath.<List<String>>read(body, "$.records[*].outcome")).containsExactly("SUCCESS", "STARTED");
		// 무엇으로 걸러 봤는지도 남는다.
		assertThat(JsonPath.<String>read(body, "$.records[0].target")).contains("ar-아무도-아닌-사람").contains("DENIED");
	}

	@Test
	void 감사_기록을_고치거나_지우거나_더하는_API_는_없다() throws Exception {
		this.mvc.perform(post("/api/v1/audit-records").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isMethodNotAllowed());
		this.mvc.perform(put("/api/v1/audit-records").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isMethodNotAllowed());
		this.mvc.perform(delete("/api/v1/audit-records").with(ADMIN).with(csrf())).andExpect(status().isMethodNotAllowed());
	}

	// ── 틀린 입력 ───────────────────────────────────────────────

	@Test
	void 틀린_입력은_어느_항목인지_알리며_거부한다() throws Exception {
		problem(list().param("outcome", "DONE"), "INVALID_OUTCOME", "outcome");
		problem(list().param("outcome", ""), "INVALID_OUTCOME", "outcome");
		problem(list().param("actor", ""), "INVALID_ACTOR", "actor");
		problem(list().param("actor", "가".repeat(201)), "INVALID_ACTOR", "actor");
		problem(list().param("action", ""), "INVALID_ACTION", "action");
		problem(list().param("action", "a".repeat(101)), "INVALID_ACTION", "action");
		problem(list().param("from", "2026-13-01"), "INVALID_FROM", "from");
		problem(list().param("to", "어제"), "INVALID_TO", "to");
		problem(list().param("from", "2026-03-02").param("to", "2026-03-01"), "INVALID_TO", "to");
		problem(list().param("offset", "-1"), "INVALID_OFFSET", "offset");
		problem(list().param("limit", "201"), "INVALID_LIMIT", "limit");
		// 같은 날짜는 그 하루다.
		list().param("from", "2026-03-01").param("to", "2026-03-01").andExpect(status().isOk());
	}

	// ── 도우미 ────────────────────────────────────────────────

	private Request list() {
		return new Request(get("/api/v1/audit-records").with(ADMIN));
	}

	/** 요청을 이어 적다가 보내는 작은 도우미. */
	private final class Request {

		private final MockHttpServletRequestBuilder builder;

		Request(MockHttpServletRequestBuilder builder) {
			this.builder = builder;
		}

		Request param(String name, String value) {
			this.builder.param(name, value);
			return this;
		}

		ResultActions andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
			return AuditRecordsApiTests.this.mvc.perform(this.builder).andExpect(matcher);
		}
	}

	private List<Long> ids(Request request) throws Exception {
		return ids(request.andExpect(status().isOk()));
	}

	private static List<Long> ids(ResultActions result) throws Exception {
		List<Number> ids = JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8),
				"$.records[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	private static void problem(Request request, String code, String field) throws Exception {
		request.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is(code)))
			.andExpect(jsonPath("$.field", is(field)));
	}

	private long entry(String actor, Instant at) {
		return this.audit.record(new AuditEntry(at, actor, "listPacks", null, null, AuditOutcome.SUCCESS, 200, null, null));
	}
}
