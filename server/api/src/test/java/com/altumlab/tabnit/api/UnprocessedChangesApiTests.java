package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.ChangeEventStatus;
import com.altumlab.tabnit.link.ChangeEvents;
import com.altumlab.tabnit.rules.ActionRecords;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * C-08 (둘째): 처리에 실패한 변동의 까닭을 보고, 관리자가 다시 처리한다 (D7 5절, D2 AD-321, AD-345).
 *
 * <p>규격은 docs/api/openapi.yaml 의 listUnprocessedChanges, reprocessChanges 다. 시험용 Pack 의 사본을 쓴다:
 * 규칙 하나의 기한을 날짜로 담을 수 없게 고쳐, 그 규칙에 걸리는 변동이 실패하게 한다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UnprocessedChangesApiTests {

	private static final RequestPostProcessor OFFICER = user("unp-officer").roles("OFFICER");

	private static final RequestPostProcessor ADMIN = user("unp-admin").roles("ADMIN");

	/** 시험용 Pack 의 처음 자료. 시나리오의 앞 여섯 단계와 같다. */
	private static final String[][] FIRST = { { "program", "program-v1.csv" }, { "guideline", "guideline-v1.csv" },
			{ "guideline-program", "guideline-program-v1.csv" }, { "partner-health", "partner-health-v1.csv" },
			{ "partner-private", "partner-private-v1.csv" }, { "team", "team-v1.csv" } };

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	// ── 수용 기준: 실패한 까닭이 보이고, 고친 뒤 관리자가 다시 처리하면 처리된다 ──────────

	@Test
	void 실패한_변동은_까닭과_함께_보이고_고친_뒤_관리자가_다시_처리하면_처리되어_목록에서_빠진다() throws Exception {
		String packId = "unp-failed";
		Path pack = brokenPack(packId);

		// 판을 올린 직후의 결과에도 까닭이 붙는다.
		ResultActions uploaded = upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv"));
		uploaded.andExpect(status().isOk())
			.andExpect(jsonPath("$.changes[?(@.processing.status == 'FAILED')].processing.failures[0].ruleNo",
					everyItem(is("0001"))))
			.andExpect(jsonPath("$.changes[?(@.processing.status == 'PROCESSED')].processing.failures[*]", hasSize(0)));

		List<ChangeEvent> failed = new ChangeEvents(this.dataSource).unprocessed(packId);
		assertThat(failed).isNotEmpty().allSatisfy(event -> assertThat(event.status()).isEqualTo(ChangeEventStatus.FAILED));
		// 담당자도 본다. 처리하는 차례다.
		ResultActions listed = this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", packId).with(OFFICER));
		listed.andExpect(status().isOk())
			.andExpect(jsonPath("$.packId").value(packId))
			.andExpect(jsonPath("$.changes", hasSize(failed.size())));
		for (int i = 0; i < failed.size(); i++) {
			ChangeEvent event = failed.get(i);
			String at = "$.changes[" + i + "]";
			listed.andExpect(jsonPath(at + ".changeEventId").value(event.id()))
				.andExpect(jsonPath(at + ".editionId").value(packId + "|guideline|2"))
				.andExpect(jsonPath(at + ".inputSourceId").value("guideline"))
				.andExpect(jsonPath(at + ".editionNo").value(2))
				.andExpect(jsonPath(at + ".effectiveDate").value("2026-03-01"))
				.andExpect(jsonPath(at + ".itemKey").value(event.itemKey()))
				.andExpect(jsonPath(at + ".kind").value(event.kind().name()))
				.andExpect(jsonPath(at + ".primaryEntityId").value(event.primaryEntityId()))
				.andExpect(jsonPath(at + ".processing.status").value("FAILED"))
				.andExpect(jsonPath(at + ".processing.failedStage").value("RULES"))
				.andExpect(jsonPath(at + ".processing.failureCode").value("RULE_FAILED"))
				.andExpect(jsonPath(at + ".processing.actionCount").value(0))
				// 어느 규칙이 왜 실패했는지: 규칙 번호와 코드.
				.andExpect(jsonPath(at + ".processing.failures", hasSize(1)))
				.andExpect(jsonPath(at + ".processing.failures[0].ruleNo").value("0001"))
				.andExpect(jsonPath(at + ".processing.failures[0].code").value("DUE_DATE_OUT_OF_RANGE"));
		}
		// 두 번 읽어도 같다.
		String once = listed.andReturn().getResponse().getContentAsString();
		assertThat(this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", packId).with(ADMIN))
			.andReturn()
			.getResponse()
			.getContentAsString()).isEqualTo(once);

		// 고치지 않고 다시 처리하면 다시 실패하고 까닭이 그대로 남는다.
		this.mvc.perform(reprocess(packId).with(ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.processedCount").value(0))
			.andExpect(jsonPath("$.failedCount").value(failed.size()))
			.andExpect(jsonPath("$.changes", hasSize(failed.size())))
			.andExpect(jsonPath("$.changes[*].processing.status", everyItem(is("FAILED"))))
			.andExpect(jsonPath("$.changes[*].processing.failures[0].code", everyItem(is("DUE_DATE_OUT_OF_RANGE"))));

		// 규칙을 고친 Pack 을 다시 적재하고 관리자가 다시 처리한다.
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		this.mvc.perform(reprocess(packId).with(ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.packId").value(packId))
			.andExpect(jsonPath("$.processedCount").value(failed.size()))
			.andExpect(jsonPath("$.failedCount").value(0))
			.andExpect(jsonPath("$.changes", hasSize(failed.size())))
			.andExpect(jsonPath("$.changes[0].changeEventId").value(failed.get(0).id()))
			.andExpect(jsonPath("$.changes[*].processing.status", everyItem(is("PROCESSED"))))
			.andExpect(jsonPath("$.changes[*].processing.failures[*]", hasSize(0)))
			.andExpect(jsonPath("$.changes[0].processing.actionCount").value(greaterThan(0)));

		this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", packId).with(OFFICER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.changes", hasSize(0)));
		assertThat(new ActionRecords(this.dataSource).actions(failed.get(0).id())).isNotEmpty();
		// 남은 것이 없을 때 다시 처리해도 된다.
		this.mvc.perform(reprocess(packId).with(ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.changes", hasSize(0)));
	}

	// ── 역할 ────────────────────────────────────────────────────

	@Test
	void 다시_처리는_관리자만_하고_담당자는_403_이며_아무것도_바뀌지_않는다() throws Exception {
		String packId = "unp-role";
		Path pack = brokenPack(packId);
		upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv")).andExpect(status().isOk());
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		int remaining = new ChangeEvents(this.dataSource).unprocessed(packId).size();
		assertThat(remaining).isPositive();

		this.mvc.perform(reprocess(packId).with(OFFICER))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		this.mvc.perform(reprocess(packId)).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", packId)).andExpect(status().isUnauthorized());

		assertThat(new ChangeEvents(this.dataSource).unprocessed(packId)).hasSize(remaining);
	}

	// ── 일반 시험: 대기, 규칙이 아닌 실패, 까닭이 여럿인 실패 ───────────────────

	@Test
	void 대기인_변동과_규칙이_아닌_실패에는_까닭의_목록이_비어_있고_까닭이_여럿이면_넘겨진_차례로_나온다() throws Exception {
		String packId = "unp-kinds";
		Path pack = brokenPack(packId);
		upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv")).andExpect(status().isOk());
		ChangeEvents events = new ChangeEvents(this.dataSource);
		List<ChangeEvent> failed = events.unprocessed(packId);
		assertThat(failed).hasSizeGreaterThanOrEqualTo(2);
		// 첫째는 규칙이 아닌 실패로, 둘째는 까닭이 둘인 실패로 다시 표시한다 (처리 서비스가 남길 수 있는 모양이다).
		events.markFailed(failed.get(0).id(), com.altumlab.tabnit.link.ChangeFailedStage.IMPACT, "STORAGE_FAILURE");
		events.markFailed(failed.get(1).id(), com.altumlab.tabnit.link.ChangeFailedStage.RULES, "RULE_FAILED",
				List.of(new com.altumlab.tabnit.link.ChangeFailureDetail("0002", "VALUE_FORMAT_MISMATCH", "개체", "속성", "값"),
						new com.altumlab.tabnit.link.ChangeFailureDetail("0001", "지어낸_코드", null, null, null)));
		// 대기인 변동을 하나 만든다: 처리하지 않고 판만 넣는다.
		new com.altumlab.tabnit.link.EditionIngest(this.dataSource).ingest(
				new com.altumlab.tabnit.pack.PackDefinitions(this.dataSource).find(packId).orElseThrow(), "program",
				"program-v2.csv", Files.readAllBytes(pack.resolve("data/program-v2.csv")),
				java.time.LocalDate.parse("2026-04-01"), java.time.Instant.parse("2027-01-01T00:00:00Z"));

		ResultActions listed = this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", packId).with(OFFICER));

		listed.andExpect(status().isOk())
			.andExpect(jsonPath("$.changes[0].processing.failedStage").value("IMPACT"))
			.andExpect(jsonPath("$.changes[0].processing.failureCode").value("STORAGE_FAILURE"))
			.andExpect(jsonPath("$.changes[0].processing.failures", hasSize(0)))
			.andExpect(jsonPath("$.changes[1].processing.failures[*].ruleNo",
					org.hamcrest.Matchers.contains("0002", "0001")))
			.andExpect(jsonPath("$.changes[1].processing.failures[0].entityId").value("개체"))
			.andExpect(jsonPath("$.changes[1].processing.failures[0].attributeName").value("속성"))
			.andExpect(jsonPath("$.changes[1].processing.failures[0].value").value("값"))
			.andExpect(jsonPath("$.changes[1].processing.failures[1].entityId").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.changes[?(@.processing.status == 'PENDING')]", hasSize(greaterThan(0))))
			.andExpect(jsonPath("$.changes[?(@.processing.status == 'PENDING')].processing.failedStage",
					everyItem(org.hamcrest.Matchers.nullValue())))
			.andExpect(jsonPath("$.changes[?(@.processing.status == 'PENDING')].processing.failures[*]", hasSize(0)));
	}

	// ── 일반 시험: 빈 것, 없는 것, 틀린 것 ────────────────────────────────

	@Test
	void 처리되지_않은_변동이_없는_Pack_은_빈_목록이다() throws Exception {
		String packId = "unp-empty";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", packId).with(OFFICER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.packId").value(packId))
			.andExpect(jsonPath("$.changes", hasSize(0)));
		this.mvc.perform(reprocess(packId).with(ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.changes", hasSize(0)));
	}

	@Test
	void 빠진_Pack_은_400_이고_없는_Pack_은_404_다() throws Exception {
		this.mvc.perform(get("/api/v1/unprocessed-changes").with(OFFICER))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_PACK_ID"))
			.andExpect(jsonPath("$.field").value("packId"));
		this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", "").with(OFFICER))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_PACK_ID"));
		this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", "unp-없는-pack").with(OFFICER))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PACK_NOT_FOUND"));
		this.mvc
			.perform(post("/api/v1/change-reprocessings").with(ADMIN).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_PACK_ID"));
		// 빈칸뿐인 것도 빠진 것이다. 조회와 같다.
		this.mvc.perform(reprocess("  ").with(ADMIN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_PACK_ID"));
		this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", "  ").with(OFFICER))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_PACK_ID"));
		this.mvc
			.perform(post("/api/v1/change-reprocessings").with(ADMIN).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"packId\": 7}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PACK_NOT_FOUND"));
		this.mvc.perform(reprocess("unp-없는-pack").with(ADMIN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PACK_NOT_FOUND"));
	}

	// ── 도우미 ──────────────────────────────────────────────────

	/** 규칙 하나의 기한을 날짜로 담을 수 없게 고친 사본을 적재하고 처음 자료를 넣는다. */
	private Path brokenPack(String packId) throws Exception {
		PackCopies.requireUnstorableDueDate();
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, PackCopies::unstorableDueDate);
		for (String[] first : FIRST) {
			upload(packId, first[0], "2026-01-01", pack.resolve("data/" + first[1])).andExpect(status().isOk());
		}
		return pack;
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, Path file) throws Exception {
		return this.mvc.perform(multipart("/api/v1/editions")
			.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv", Files.readAllBytes(file)))
			.param("packId", packId)
			.param("inputSourceId", inputSourceId)
			.param("effectiveDate", effectiveDate)
			.with(OFFICER)
			.with(csrf()));
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reprocess(String packId) {
		return post("/api/v1/change-reprocessings").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"packId\": \"" + packId + "\"}");
	}
}
