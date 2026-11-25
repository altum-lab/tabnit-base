package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * D-16: 지난 판의 목록과 지난 판 하나의 결과 (docs/api/openapi.yaml 의 listEditions, getEdition. D7 5절 "지난 판",
 * D2 AD-383).
 *
 * <p>시험용 Pack 의 사본을 쓴다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PastEditionsApiTests {

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	@Test
	void 판의_목록은_받은_것이_늦은_것부터_항목과_변동과_알림의_수와_함께_온다() throws Exception {
		String packId = "pe-list";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		upload(packId, "guideline", "2026-01-01", pack.resolve("data/guideline-v1.csv")).andExpect(status().isOk());
		upload(packId, "program", "2026-01-01", pack.resolve("data/program-v1.csv")).andExpect(status().isOk());
		upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv")).andExpect(status().isOk());
		// 거부된 투입은 판이 아니다 (적용일이 앞 판보다 늦지 않다).
		upload(packId, "guideline", "2026-02-01", pack.resolve("data/guideline-v3.csv"))
			.andExpect(status().isUnprocessableEntity());

		ResultActions listed = this.mvc.perform(get("/api/v1/editions").param("packId", packId));

		listed.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/json"))
			.andExpect(jsonPath("$.packId").value(packId))
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.offset").value(0))
			.andExpect(jsonPath("$.limit").value(50))
			.andExpect(jsonPath("$.editions", hasSize(3)))
			// 가장 늦게 받은 판이 맨 위다.
			.andExpect(jsonPath("$.editions[0].editionId").value(packId + "|guideline|2"))
			.andExpect(jsonPath("$.editions[0].inputSourceId").value("guideline"))
			.andExpect(jsonPath("$.editions[0].editionNo").value(2))
			.andExpect(jsonPath("$.editions[0].effectiveDate").value("2026-03-01"))
			.andExpect(jsonPath("$.editions[0].receivedAt").isString())
			.andExpect(jsonPath("$.editions[0].fileName").value("guideline-v2.csv"))
			.andExpect(jsonPath("$.editions[0].itemCount").value(3))
			.andExpect(jsonPath("$.editions[0].changeCount").value(3))
			.andExpect(jsonPath("$.editions[0].noticeCount").value(0))
			.andExpect(jsonPath("$.editions[1].editionId").value(packId + "|program|1"))
			.andExpect(jsonPath("$.editions[1].changeCount").value(0))
			.andExpect(jsonPath("$.editions[2].editionId").value(packId + "|guideline|1"));

		// 나눠 받는다. 전체 수는 나누기 전의 수다.
		this.mvc.perform(get("/api/v1/editions").param("packId", packId).param("offset", "1").param("limit", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.offset").value(1))
			.andExpect(jsonPath("$.limit").value(1))
			.andExpect(jsonPath("$.editions", hasSize(1)))
			.andExpect(jsonPath("$.editions[0].editionId").value(packId + "|program|1"));
		// 두 번 읽어도 같다.
		assertThat(this.mvc.perform(get("/api/v1/editions").param("packId", packId)).andReturn().getResponse().getContentAsString())
			.isEqualTo(listed.andReturn().getResponse().getContentAsString());
	}

	@Test
	void 지난_판_하나의_답은_올린_직후의_답과_바뀐_항목과_알림이_같다() throws Exception {
		String packId = "pe-one";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] first : new String[][] { { "program", "program-v1.csv" }, { "guideline", "guideline-v1.csv" },
				{ "guideline-program", "guideline-program-v1.csv" }, { "partner-health", "partner-health-v1.csv" },
				{ "partner-private", "partner-private-v1.csv" }, { "team", "team-v1.csv" } }) {
			upload(packId, first[0], "2026-01-01", pack.resolve("data/" + first[1])).andExpect(status().isOk());
		}
		// 조치가 만들어지는 판과, 알림이 있는 판 (보건기관의 파일이 전담팀을 가리킬 때는 전담팀의 자료가 이미 있다:
		// 알림은 지어낸 행으로 만든다).
		String submitted = upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String noticed = upload(packId, "guideline", "2026-04-01", "지침번호,제목,점검주기일\nG-01,방문 건강 관리 운영 기준,14\n,키가 빈 행,30\n")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.notices", hasSize(1)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		ObjectMapper json = new ObjectMapper();

		for (String[] expected : new String[][] { { packId + "|guideline|2", submitted }, { packId + "|guideline|3", noticed } }) {
			String stored = this.mvc.perform(get("/api/v1/edition").param("editionId", expected[0]))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ACCEPTED"))
				.andExpect(jsonPath("$.editionId").value(expected[0]))
				.andExpect(jsonPath("$.retriedChanges", hasSize(0)))
				.andExpect(jsonPath("$.processingNotStarted").value(false))
				.andReturn()
				.getResponse()
				.getContentAsString();
			JsonNode before = json.readTree(expected[1]);
			JsonNode after = json.readTree(stored);
			// 바뀐 항목(개체의 이름, 바뀐 값, 처리 상태, 조치의 수), 알림, 판의 것이 올린 직후의 답과 같다.
			for (String field : new String[] { "changes", "notices", "packId", "inputSourceId", "editionNo", "effectiveDate",
					"itemCount" }) {
				assertThat(after.get(field)).as(expected[0] + " " + field).isEqualTo(before.get(field));
			}
		}
		// 조치가 만들어진 판인지 확인한다: 그렇지 않으면 조치의 수를 견준 것이 뜻이 없다.
		assertThat(json.readTree(submitted).get("changes").toString()).contains("\"actionCount\":").doesNotContain("\"status\":\"FAILED\"");
		assertThat(json.readTree(submitted).findValues("actionCount").stream().mapToInt(JsonNode::asInt).sum()).isPositive();
	}

	@Test
	void 처리에_실패한_판은_까닭과_함께_다시_보이고_다시_처리된_뒤에는_지금의_상태와_조치의_수로_보인다() throws Exception {
		String packId = "pe-failed";
		// 규칙 하나의 기한을 날짜로 담을 수 없게 고친 Pack: 그 규칙에 걸리는 변동이 실패한다.
		PackCopies.requireUnstorableDueDate();
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, PackCopies::unstorableDueDate);
		for (String[] first : new String[][] { { "program", "program-v1.csv" }, { "guideline", "guideline-v1.csv" },
				{ "guideline-program", "guideline-program-v1.csv" } }) {
			upload(packId, first[0], "2026-01-01", pack.resolve("data/" + first[1])).andExpect(status().isOk());
		}
		String submitted = upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		ObjectMapper json = new ObjectMapper();
		JsonNode uploaded = json.readTree(submitted);
		// 실패한 변동이 있어야 이 시험에 뜻이 있다.
		assertThat(uploaded.get("changes").toString()).contains("\"status\":\"FAILED\"").contains("DUE_DATE_OUT_OF_RANGE");
		String editionId = packId + "|guideline|2";

		// 지난 판으로 다시 읽으면 실패의 단계와 코드와 평가에 실패한 규칙까지 올린 직후의 답과 같다.
		JsonNode failed = json.readTree(this.mvc.perform(get("/api/v1/edition").param("editionId", editionId))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
		assertThat(failed.get("changes")).isEqualTo(uploaded.get("changes"));

		// 규칙을 고친 Pack 을 다시 적재하고 다시 처리한다.
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		JsonNode reprocessed = json.readTree(this.mvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/change-reprocessings")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"packId\": \"" + packId + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
		assertThat(reprocessed.get("failedCount").asInt()).isZero();
		assertThat(reprocessed.get("processedCount").asInt()).isPositive();

		// 같은 판을 다시 읽으면 지금 저장된 상태다: 모두 처리됐고, 조치의 수는 다시 처리한 답의 것과 같다.
		JsonNode now = json.readTree(this.mvc.perform(get("/api/v1/edition").param("editionId", editionId))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
		assertThat(now.get("changes").toString()).doesNotContain("\"status\":\"FAILED\"");
		for (JsonNode done : reprocessed.get("changes")) {
			JsonNode shown = null;
			for (JsonNode change : now.get("changes")) {
				if (change.get("changeEventId").equals(done.get("changeEventId"))) {
					shown = change;
				}
			}
			assertThat(shown).as(done.get("changeEventId").asString()).isNotNull();
			assertThat(shown.get("processing").get("status").asString()).isEqualTo("PROCESSED");
			assertThat(shown.get("processing").get("failures")).isEmpty();
			assertThat(shown.get("processing").get("actionCount")).isEqualTo(done.get("processing").get("actionCount"));
		}
		// 바뀐 항목과 바뀐 값은 다시 처리해도 그대로다.
		assertThat(now.get("changes").findValues("changedValues")).isEqualTo(uploaded.get("changes").findValues("changedValues"));
	}

	@Test
	void 틀린_요청은_무엇이_틀렸는지_알린다() throws Exception {
		String packId = "pe-wrong";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		this.mvc.perform(get("/api/v1/editions"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_PACK_ID"));
		this.mvc.perform(get("/api/v1/editions").param("packId", "pe-no-such-pack"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PACK_NOT_FOUND"));
		this.mvc.perform(get("/api/v1/editions").param("packId", packId).param("limit", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
		this.mvc.perform(get("/api/v1/editions").param("packId", packId).param("offset", "-1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_OFFSET"));
		// 판이 없는 Pack 은 빈 목록이다.
		this.mvc.perform(get("/api/v1/editions").param("packId", packId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.editions", hasSize(0)));

		this.mvc.perform(get("/api/v1/edition"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_EDITION_ID"));
		this.mvc.perform(get("/api/v1/edition").param("editionId", packId + "|guideline|9"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EDITION_NOT_FOUND"));
		this.mvc.perform(get("/api/v1/edition").param("editionId", "a\u0000b"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EDITION_NOT_FOUND"));
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, Path file) throws Exception {
		return upload(packId, inputSourceId, effectiveDate, file.getFileName().toString(), Files.readAllBytes(file));
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, String csv) throws Exception {
		return upload(packId, inputSourceId, effectiveDate, "지어낸 판.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, String name, byte[] content)
			throws Exception {
		return this.mvc.perform(multipart("/api/v1/editions").param("packId", packId)
			.param("inputSourceId", inputSourceId)
			.param("effectiveDate", effectiveDate)
			.file(new MockMultipartFile("file", name, "text/csv", content)));
	}
}
