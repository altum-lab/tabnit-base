package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.ChangeEventStatus;
import com.altumlab.tabnit.link.Edition;
import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.link.IngestRecord;
import com.altumlab.tabnit.rules.ActionRecords;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-05 (서버 쪽): 판을 올리는 API 가 규격(docs/api/openapi.yaml 의 submitEdition)대로 답한다.
 *
 * <p>D7 5절: 받아들인 판의 바뀐 항목과 그 처리 결과를 돌려준다. 내용이 같은 것은 200, 파일을 받아들이지 않은 것은
 * 422 다. 오류는 공통 오류 형식으로 낸다. 시험용 Pack 의 사본을 쓴다. 모든 이름과 값은 지어낸 것이다.
 * 파일 크기의 한도는 이 시험에서만 작게 둔다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.servlet.multipart.max-file-size=4KB")
class EditionsApiTests {

	/** 시험용 Pack 의 처음 자료. 입력 자료, 파일, 적용일. 시나리오의 앞 여섯 단계와 같다. */
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
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	// ── 수용 기준: 새 판을 올리면 바뀐 항목이 나온다 ─────────────────────

	@Test
	void 첫_판을_올리면_받아들이고_바뀐_항목은_없다() throws Exception {
		String packId = "up-first";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		upload(packId, "guideline", "2026-01-01", "C:\\자료\\guideline-v1.csv", pack.resolve("data/guideline-v1.csv"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/json"))
			.andExpect(jsonPath("$.outcome").value("ACCEPTED"))
			.andExpect(jsonPath("$.editionId").value(packId + "|guideline|1"))
			.andExpect(jsonPath("$.packId").value(packId))
			.andExpect(jsonPath("$.inputSourceId").value("guideline"))
			.andExpect(jsonPath("$.editionNo").value(1))
			.andExpect(jsonPath("$.effectiveDate").value("2026-01-01"))
			.andExpect(jsonPath("$.itemCount").value(org.hamcrest.Matchers.greaterThan(0)))
			.andExpect(jsonPath("$.changes", hasSize(0)))
			.andExpect(jsonPath("$.notices").isArray())
			.andExpect(jsonPath("$.retriedChanges", hasSize(0)))
			.andExpect(jsonPath("$.processingNotStarted").value(false));

		// 파일의 이름은 폴더 부분을 떼고 남긴다.
		Edition edition = new EditionRecords(this.dataSource).findEdition(packId + "|guideline|1").orElseThrow();
		assertThat(edition.fileName()).isEqualTo("guideline-v1.csv");
	}

	@Test
	void 다음_판을_올리면_바뀐_항목과_처리_결과가_키의_순서로_나오고_조치가_저장되어_있다() throws Exception {
		String packId = "up-next";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] first : FIRST) {
			upload(packId, first[0], "2026-01-01", first[1], pack.resolve("data/" + first[1])).andExpect(status().isOk());
		}

		ResultActions result = upload(packId, "guideline", "2026-03-01", "guideline-v2.csv",
				pack.resolve("data/guideline-v2.csv"));

		String editionId = packId + "|guideline|2";
		List<ChangeEvent> events = new EditionRecords(this.dataSource).changeEvents(editionId);
		assertThat(events).isNotEmpty().allSatisfy(event -> assertThat(event.status()).isEqualTo(ChangeEventStatus.PROCESSED));
		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("ACCEPTED"))
			.andExpect(jsonPath("$.editionId").value(editionId))
			.andExpect(jsonPath("$.editionNo").value(2))
			.andExpect(jsonPath("$.effectiveDate").value("2026-03-01"))
			// 저장된 변동 이벤트와 같은 것이, 항목의 키의 글자 코드 순으로 나온다.
			.andExpect(jsonPath("$.changes[*].changeEventId", contains(events.stream().map(ChangeEvent::id).toArray())))
			.andExpect(jsonPath("$.changes[*].itemKey", contains(events.stream().map(ChangeEvent::itemKey).toArray())))
			.andExpect(jsonPath("$.changes[*].kind", contains(events.stream().map(e -> e.kind().name()).toArray())))
			.andExpect(jsonPath("$.changes[*].primaryEntityId",
					contains(events.stream().map(ChangeEvent::primaryEntityId).toArray())))
			.andExpect(jsonPath("$.changes[*].processing.status", everyItem(is("PROCESSED"))))
			.andExpect(jsonPath("$.changes[*].processing.failedStage", everyItem(nullValue())))
			.andExpect(jsonPath("$.changes[*].processing.failureCode", everyItem(nullValue())));
		// 조치의 수는 저장된 것과 같다. 조치가 하나도 없이 통과하지 않게 한다.
		ActionRecords actions = new ActionRecords(this.dataSource);
		int total = 0;
		for (int i = 0; i < events.size(); i++) {
			int stored = actions.actions(events.get(i).id()).size();
			result.andExpect(jsonPath("$.changes[" + i + "].processing.actionCount").value(stored))
				.andExpect(jsonPath("$.changes[" + i + "].targetEntityIds",
						contains(events.get(i).targetEntityIds().toArray())));
			total += stored;
		}
		assertThat(total).isPositive();
	}

	@Test
	void 내용이_앞_판과_같으면_200_으로_답하고_판을_만들지_않는다() throws Exception {
		String packId = "up-same";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		upload(packId, "guideline", "2026-01-01", "a.csv", pack.resolve("data/guideline-v1.csv")).andExpect(status().isOk());

		upload(packId, "guideline", "2026-02-01", "b.csv", pack.resolve("data/guideline-v1.csv"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("UNCHANGED"))
			// 내용이 같았던 앞 판의 것이다.
			.andExpect(jsonPath("$.editionId").value(packId + "|guideline|1"))
			.andExpect(jsonPath("$.editionNo").value(1))
			.andExpect(jsonPath("$.effectiveDate").value("2026-01-01"))
			.andExpect(jsonPath("$.itemCount").value(0))
			.andExpect(jsonPath("$.changes", hasSize(0)))
			.andExpect(jsonPath("$.notices", hasSize(0)));
	}

	// ── 거부 ─────────────────────────────────────────────────────

	@Test
	void 파일을_받아들이지_않으면_422_로_이유의_코드와_고칠_것을_알리고_투입_기록에_남는다() throws Exception {
		String packId = "up-rejected";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		upload(packId, "guideline", "2026-01-01", "틀린 파일.csv", "이런 열은 없다\n값\n".getBytes(StandardCharsets.UTF_8))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(422))
			.andExpect(jsonPath("$.code").value("EDITION_REJECTED"))
			.andExpect(jsonPath("$.reasonCode").value("MISSING_COLUMN"))
			.andExpect(jsonPath("$.title").value("받아들일 수 없는 파일"))
			.andExpect(jsonPath("$.reasonCode").value(not(emptyOrNullString())))
			.andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasKey("reasonDetail")))
			// 한국어로, 무엇을 하면 되는지까지.
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("다시 올리세요")));

		// 이유의 값이 없는 거부: 항목을 빼지 않고 null 로 담는다. 빈 파일은 열 이름의 줄이 없다.
		upload(packId, "guideline", "2026-01-01", "빈 파일.csv", new byte[0])
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reasonCode").value("NO_HEADER"))
			.andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasKey("reasonDetail")))
			.andExpect(jsonPath("$.reasonDetail").value(nullValue()))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("열 이름")));
		// UTF-8 이 아닌 파일.
		upload(packId, "guideline", "2026-01-01", "깨진 파일.csv", new byte[] { (byte) 0xff, (byte) 0xfe, 0x41 })
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reasonCode").value("NOT_UTF8"))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("UTF-8")));

		List<IngestRecord> records = new EditionRecords(this.dataSource).ingestRecords(packId, "guideline");
		assertThat(records).hasSize(3);
		assertThat(new EditionRecords(this.dataSource).findEdition(packId + "|guideline|1")).isEmpty();
	}

	@Test
	void 크기_한도를_넘는_파일은_413_이다() throws Exception {
		String packId = "up-large";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		// 이 시험의 한도는 4KB 다.
		byte[] large = "가".repeat(3000).getBytes(StandardCharsets.UTF_8);

		upload(packId, "guideline", "2026-01-01", "큰 파일.csv", large)
			.andExpect(status().isContentTooLarge())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("4")));
	}

	// ── 틀린 요청 ──────────────────────────────────────────────────

	@Test
	void 빠진_항목과_틀린_날짜는_400_으로_어느_항목인지_알린다() throws Exception {
		String packId = "up-bad";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		byte[] content = "a\n".getBytes(StandardCharsets.UTF_8);
		MockMultipartFile file = new MockMultipartFile("file", "a.csv", "text/csv", content);

		expectProblem(this.mvc.perform(request(null, "guideline", "2026-01-01").file(file)), 400, "MISSING_PACK_ID", "packId");
		expectProblem(this.mvc.perform(request(" ", "guideline", "2026-01-01").file(file)), 400, "MISSING_PACK_ID", "packId");
		expectProblem(this.mvc.perform(request(packId, null, "2026-01-01").file(file)), 400, "MISSING_INPUT_SOURCE_ID",
				"inputSourceId");
		expectProblem(this.mvc.perform(request(packId, "guideline", null).file(file)), 400, "MISSING_EFFECTIVE_DATE",
				"effectiveDate");
		expectProblem(this.mvc.perform(request(packId, "guideline", "2026-1-1").file(file)), 400, "INVALID_EFFECTIVE_DATE",
				"effectiveDate");
		expectProblem(this.mvc.perform(request(packId, "guideline", "2026-02-30").file(file)), 400, "INVALID_EFFECTIVE_DATE",
				"effectiveDate");
		expectProblem(this.mvc.perform(request(packId, "guideline", "2026-01-01")), 400, "MISSING_FILE", "file");
		// link 가 받지 않는 값: 쓸 수 없는 글자가 든 파일 이름.
		this.mvc
			.perform(request(packId, "guideline", "2026-01-01")
				.file(new MockMultipartFile("file", "a\u0000.csv", "text/csv", content)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_EDITION"))
			.andExpect(jsonPath("$.field").value("file"));
		assertThat(new EditionRecords(this.dataSource).ingestRecords(packId, "guideline")).isEmpty();
	}

	@Test
	void 없는_Pack_과_없는_입력_자료는_404_다() throws Exception {
		String packId = "up-missing";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		MockMultipartFile file = new MockMultipartFile("file", "a.csv", "text/csv", "a\n".getBytes(StandardCharsets.UTF_8));

		expectProblem(this.mvc.perform(request("up-없는-pack", "guideline", "2026-01-01").file(file)), 404, "PACK_NOT_FOUND",
				"packId");
		expectProblem(this.mvc.perform(request(packId, "없는 입력 자료", "2026-01-01").file(file)), 404,
				"INPUT_SOURCE_NOT_FOUND", "inputSourceId");
	}

	// ── 도우미 ──────────────────────────────────────────────────

	@Test
	void 건너뛴_행의_알림에는_코드와_값과_함께_풀어_쓴_문구가_온다() throws Exception {
		String packId = "up-notice";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		// 첫 행은 받고, 둘째 행은 지침번호가 비었다.
		ResultActions result = upload(packId, "guideline", "2026-01-01", "g.csv",
				"지침번호,제목,점검주기일\nG-91,지어낸 지침,30\n,번호가 없는 지침,30\n"
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.notices", hasSize(1)))
			.andExpect(jsonPath("$.notices[0].kind").value("SKIPPED_ROW"))
			.andExpect(jsonPath("$.notices[0].code").value("BLANK_KEY"))
			.andExpect(jsonPath("$.notices[0].rowNo").value(3))
			.andExpect(jsonPath("$.notices[0].message", org.hamcrest.Matchers.startsWith("3번째 행")))
			.andExpect(jsonPath("$.notices[0].message", org.hamcrest.Matchers.containsString("지침번호")));
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, String fileName, Path file)
			throws Exception {
		return upload(packId, inputSourceId, effectiveDate, fileName, Files.readAllBytes(file));
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, String fileName, byte[] content)
			throws Exception {
		return this.mvc.perform(request(packId, inputSourceId, effectiveDate)
			.file(new MockMultipartFile("file", fileName, "text/csv", content)));
	}

	private static MockMultipartHttpServletRequestBuilder request(String packId, String inputSourceId, String effectiveDate) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/editions");
		if (packId != null) {
			builder.param("packId", packId);
		}
		if (inputSourceId != null) {
			builder.param("inputSourceId", inputSourceId);
		}
		if (effectiveDate != null) {
			builder.param("effectiveDate", effectiveDate);
		}
		return builder;
	}

	private static void expectProblem(ResultActions result, int status, String code, String field) throws Exception {
		result.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.field").value(field))
			.andExpect(jsonPath("$.detail").value(not(emptyOrNullString())));
	}
}
