package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditRecord;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * C-15: Pack 의 변동 이벤트의 목록을 주는 API (docs/api/openapi.yaml 의 listChangeEvents, D7 5절 "변동 이벤트의 목록",
 * D2 AD-417).
 *
 * <p>수용 기준의 "줄마다의 수가 판으로 들어가 본 그 변동의 수와 같다"는 판의 목록과 판 하나의 API (listEditions, getEdition),
 * 변동의 영향 범위의 API (getChangeImpactScope)로 따로 읽은 것과 견준다. 시험용 Pack 의 사본을 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChangeEventsApiTests {

	private static final String[][] EDITIONS = { { "program", "program-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v1.csv", "2026-01-01" },
			{ "guideline-program", "guideline-program-v1.csv", "2026-01-01" },
			{ "partner-health", "partner-health-v1.csv", "2026-01-01" },
			{ "partner-private", "partner-private-v1.csv", "2026-01-01" }, { "team", "team-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v2.csv", "2026-03-01" },
			{ "guideline-program", "guideline-program-v2.csv", "2026-03-01" },
			{ "program", "program-v2.csv", "2026-04-01" }, { "guideline", "guideline-v3.csv", "2026-05-01" } };

	private static final RequestPostProcessor OFFICER = user("ce-officer").roles("OFFICER");

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	AuditLog audit;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	@Test
	void 변동의_목록이_판으로_들어가_본_변동과_같고_가장_최근의_판부터_나온다() throws Exception {
		String packId = "ce-list";
		load(packId);
		// 판으로 들어가 읽는다: 판의 목록(가장 최근 것부터)을 받고 판마다 그 변동을 받는다.
		String editions = get("/api/v1/editions?packId=" + packId + "&limit=200");
		List<String> editionIds = JsonPath.read(editions, "$.editions[*].editionId");
		List<String> expectedIds = new ArrayList<>();
		List<Integer> expectedActionCounts = new ArrayList<>();
		List<String> expectedStatuses = new ArrayList<>();
		for (String editionId : editionIds) {
			String edition = get("/api/v1/edition?editionId=" + editionId);
			expectedIds.addAll(JsonPath.<List<String>>read(edition, "$.changes[*].changeEventId"));
			expectedActionCounts.addAll(JsonPath.<List<Integer>>read(edition, "$.changes[*].processing.actionCount"));
			expectedStatuses.addAll(JsonPath.<List<String>>read(edition, "$.changes[*].processing.status"));
		}
		assertThat(expectedIds).as("변동이 여럿 있어야 이 시험에 뜻이 있다").hasSizeGreaterThan(3);
		assertThat(expectedActionCounts).as("조치가 있는 변동이 있어야 한다").anyMatch(count -> count > 0);

		String body = get("/api/v1/change-events?packId=" + packId + "&limit=200");

		assertThat(JsonPath.<Integer>read(body, "$.total")).isEqualTo(expectedIds.size());
		// 차례까지 같다: 가장 최근에 받은 판의 변동부터, 판 안에서는 그 판의 변동의 차례대로.
		assertThat(JsonPath.<List<String>>read(body, "$.changes[*].changeEventId")).containsExactlyElementsOf(expectedIds);
		assertThat(JsonPath.<List<Integer>>read(body, "$.changes[*].actionCount")).containsExactlyElementsOf(expectedActionCounts);
		assertThat(JsonPath.<List<String>>read(body, "$.changes[*].status")).containsExactlyElementsOf(expectedStatuses);
		// 영향 대상의 수: 변동마다 그 영향 범위를 따로 읽은 전체 수와 같다.
		List<Integer> targetCounts = JsonPath.read(body, "$.changes[*].impactTargetCount");
		for (int i = 0; i < expectedIds.size(); i++) {
			String scope = this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
					.get("/api/v1/impact-scopes").with(OFFICER).param("changeEventId", expectedIds.get(i)).param("limit", "1"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
			assertThat(targetCounts.get(i)).as(expectedIds.get(i)).isEqualTo(JsonPath.<Integer>read(scope, "$.total"));
		}
		assertThat(targetCounts).as("영향 대상이 있는 변동이 있어야 한다").anyMatch(count -> count != null && count > 0);
		// 줄마다 어느 판의 변동인지와 주 개체가 이름과 함께 온다.
		assertThat(JsonPath.<String>read(body, "$.changes[0].editionId")).isEqualTo(editionIds.get(0));
		assertThat(JsonPath.<String>read(body, "$.changes[0].inputSourceLabel")).isNotBlank();
		assertThat(JsonPath.<String>read(body, "$.changes[0].effectiveDate")).matches("\\d{4}-\\d{2}-\\d{2}");
		assertThat(JsonPath.<String>read(body, "$.changes[0].primaryEntity.entityId"))
			.isEqualTo(JsonPath.<String>read(body, "$.changes[0].primaryEntityId"));
		assertThat(JsonPath.<String>read(body, "$.changes[0].kind")).isIn("ADDED", "CHANGED", "REMOVED");
		// 두 번 물어도 같다.
		assertThat(get("/api/v1/change-events?packId=" + packId + "&limit=200")).isEqualTo(body);
	}

	@Test
	void 나눠_받은_것을_이으면_한_번에_받은_것과_순서까지_같고_상태로_거른다() throws Exception {
		String packId = "ce-page";
		load(packId);
		String whole = get("/api/v1/change-events?packId=" + packId + "&limit=200");
		List<String> all = JsonPath.read(whole, "$.changes[*].changeEventId");
		int total = JsonPath.read(whole, "$.total");

		List<String> joined = new ArrayList<>();
		for (int offset = 0; offset < total; offset += 2) {
			String page = get("/api/v1/change-events?packId=" + packId + "&offset=" + offset + "&limit=2");
			assertThat(JsonPath.<Integer>read(page, "$.total")).isEqualTo(total);
			assertThat(JsonPath.<Integer>read(page, "$.offset")).isEqualTo(offset);
			joined.addAll(JsonPath.<List<String>>read(page, "$.changes[*].changeEventId"));
		}
		assertThat(joined).containsExactlyElementsOf(all);

		// 이 시나리오의 변동은 모두 처리됐다.
		String processed = get("/api/v1/change-events?packId=" + packId + "&status=PROCESSED&limit=200");
		assertThat(JsonPath.<Integer>read(processed, "$.total")).isEqualTo(total);
		assertThat(JsonPath.<String>read(processed, "$.status")).isEqualTo("PROCESSED");
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/change-events")
				.with(OFFICER).param("packId", packId).param("status", "FAILED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total", is(0)))
			.andExpect(jsonPath("$.changes", hasSize(0)));
		// 끝을 넘으면 빈 목록이고 전체 수는 그대로다.
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/change-events")
				.with(OFFICER).param("packId", packId).param("offset", String.valueOf(total)))
			.andExpect(jsonPath("$.changes", hasSize(0)))
			.andExpect(jsonPath("$.total", is(total)));
	}

	@Test
	void 변동이_없는_Pack_은_빈_목록이고_틀린_입력은_어느_항목인지_알리며_거부한다() throws Exception {
		String packId = "ce-empty";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/change-events")
				.with(OFFICER).param("packId", packId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.packId", is(packId)))
			.andExpect(jsonPath("$.total", is(0)))
			.andExpect(jsonPath("$.changes", hasSize(0)));
		problem("/api/v1/change-events", 400, "MISSING_PACK_ID", "packId");
		problem("/api/v1/change-events?packId=ce-없는-Pack", 404, "PACK_NOT_FOUND", "packId");
		problem("/api/v1/change-events?packId=" + packId + "&status=DONE", 400, "INVALID_STATUS", "status");
		problem("/api/v1/change-events?packId=" + packId + "&status=", 400, "INVALID_STATUS", "status");
		problem("/api/v1/change-events?packId=" + packId + "&offset=-1", 400, "INVALID_OFFSET", "offset");
		problem("/api/v1/change-events?packId=" + packId + "&limit=201", 400, "INVALID_LIMIT", "limit");
	}

	@Test
	void 로그인해야_보고_본_것은_감사_기록에_남는다() throws Exception {
		String packId = "ce-audit";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/change-events")
				.param("packId", packId))
			.andExpect(status().isUnauthorized());
		long mark = this.audit.lastId();
		get("/api/v1/change-events?packId=" + packId + "&status=PENDING");

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action).containsExactly("listChangeEvents", "listChangeEvents");
		assertThat(rows.get(1).target()).contains(packId).contains("PENDING");
	}

	private String get(String url) throws Exception {
		return this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(OFFICER))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private void problem(String url, int status, String code, String field) throws Exception {
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(OFFICER))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.code", is(code)))
			.andExpect(jsonPath("$.field", is(field)));
	}

	private void load(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : EDITIONS) {
			Path file = pack.resolve("data/" + edition[1]);
			this.mvc.perform(multipart("/api/v1/editions").with(OFFICER).with(csrf()).param("packId", packId)
					.param("inputSourceId", edition[0]).param("effectiveDate", edition[2])
					.file(new MockMultipartFile("file", edition[1], "text/csv", Files.readAllBytes(file))))
				.andExpect(status().isOk());
		}
	}
}
