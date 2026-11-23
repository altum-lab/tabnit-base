package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.link.ChangeEvent;
import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.link.ValueChange;
import com.altumlab.tabnit.pack.AttributeDefinition;
import com.altumlab.tabnit.pack.PackDefinition;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.pack.PackLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * C-07: 개정 조항 표를 넣으면 이전 조문과 비교된다 (D2 AD-367, D7 5절의 changedValues).
 *
 * <p>판을 올린 응답이 바뀐 속성마다 이전 값과 새 값을 준다. 일반 시험은 시험용 Pack 의 사본으로, 수용 기준은 저장소의
 * 퇴직연금 Pack 의 요건 표(지어낸 자료)로 본다. 견주는 일 자체의 경계는 link 의 시험(EditionDiffTest)이 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChangedValuesApiTests {

	private static final Path PENSION = Path.of("../../packs/retirement-pension");

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
	void 응답의_바뀐_값은_저장된_변동_이벤트의_것과_같고_속성의_이름표가_붙는다() throws Exception {
		String packId = "cv-same";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		upload(packId, "guideline", "2026-01-01", pack.resolve("data/guideline-v1.csv")).andExpect(status().isOk());

		ResultActions result = upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv"));

		List<ChangeEvent> events = new EditionRecords(this.dataSource).changeEvents(packId + "|guideline|2");
		PackDefinition definition = new PackDefinitions(this.dataSource).find(packId).orElseThrow();
		assertThat(events).isNotEmpty();
		int changedValues = 0;
		result.andExpect(status().isOk());
		for (int i = 0; i < events.size(); i++) {
			List<ValueChange> stored = events.get(i).changedValues();
			assertThat(stored).isNotNull();
			result.andExpect(jsonPath("$.changes[" + i + "].changedValues", hasSize(stored.size())));
			for (int j = 0; j < stored.size(); j++) {
				ValueChange one = stored.get(j);
				String at = "$.changes[" + i + "].changedValues[" + j + "]";
				String label = definition.attributes()
					.stream()
					.filter(attribute -> attribute.name().equals(one.attributeName()))
					.map(AttributeDefinition::label)
					.findFirst()
					.orElseThrow();
				result.andExpect(jsonPath(at + ".entityId").value(one.entityId()))
					.andExpect(jsonPath(at + ".attributeName").value(one.attributeName()))
					.andExpect(jsonPath(at + ".attributeLabel").value(label))
					.andExpect(jsonPath(at + ".before").value(one.before()))
					.andExpect(jsonPath(at + ".after").value(one.after()));
				changedValues++;
			}
		}
		// 바뀐 값이 하나도 없이 통과하지 않게 한다.
		assertThat(changedValues).isPositive();
	}

	@Test
	void 이전_값을_알_수_없는_변동은_바뀐_값이_빈_목록이_아니라_null_이다() throws Exception {
		String packId = "cv-unknown";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		upload(packId, "guideline", "2026-01-01", pack.resolve("data/guideline-v1.csv")).andExpect(status().isOk());
		// 값을 남기기 전에 들어간 판의 모양으로 되돌린다.
		new JdbcTemplate(this.dataSource).update("update edition_item_output set value = null where edition_id = ?",
				packId + "|guideline|1");

		ResultActions result = upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv"));

		List<ChangeEvent> events = new EditionRecords(this.dataSource).changeEvents(packId + "|guideline|2");
		assertThat(events).isNotEmpty();
		int unknown = 0;
		result.andExpect(status().isOk());
		for (int i = 0; i < events.size(); i++) {
			if (events.get(i).changedValues() == null) {
				result.andExpect(jsonPath("$.changes[" + i + "].changedValues").value(nullValue()));
				unknown++;
			}
		}
		assertThat(unknown).isPositive();
	}

	@Test
	void 퇴직연금_Pack_의_개정된_요건_표를_올리면_요건의_이전_값과_새_값이_나온다() throws Exception {
		new PackLoader(this.dataSource).load(PENSION, Instant.parse("2026-07-01T00:00:00Z"));
		String packId = "retirement-pension";
		upload(packId, "requirement", "2026-01-01", PENSION.resolve("data/requirement-v1.csv")).andExpect(status().isOk());

		ResultActions result = upload(packId, "requirement", "2026-04-01", PENSION.resolve("data/requirement-v2.csv"));

		// 두 파일에서 다른 줄을 직접 찾는다: 시험이 Pack 의 값을 외우지 않는다.
		// 이 시험이 기대는 것은 그 두 파일의 모양이다 (엔진이 아니다): 첫 열이 항목의 키이고, 열의 이름이 속성의 이름과
		// 같고, 칸에 쉼표와 따옴표가 없고, 적힌 값이 저장되는 글자와 같다. 요건 표나 그 매핑을 바꿔 이 시험이 깨지면
		// 엔진보다 이 가정을 먼저 본다.
		List<String> before = Files.readAllLines(PENSION.resolve("data/requirement-v1.csv"), StandardCharsets.UTF_8);
		List<String> after = Files.readAllLines(PENSION.resolve("data/requirement-v2.csv"), StandardCharsets.UTF_8);
		String[] header = before.get(0).split(",", -1);
		assertThat(after.get(0)).isEqualTo(before.get(0));
		assertThat(after).hasSameSizeAs(before);
		int changedRows = 0;
		result.andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ACCEPTED"));
		for (int row = 1; row < before.size(); row++) {
			if (before.get(row).equals(after.get(row))) {
				continue;
			}
			String[] old = before.get(row).split(",", -1);
			String[] fresh = after.get(row).split(",", -1);
			// 이 시험의 자료에서는 바뀐 줄마다 열 하나만 다르다.
			int column = -1;
			for (int i = 0; i < header.length; i++) {
				if (!old[i].equals(fresh[i])) {
					assertThat(column).as("바뀐 열은 하나다").isEqualTo(-1);
					column = i;
				}
			}
			String at = "$.changes[" + changedRows + "]";
			result.andExpect(jsonPath(at + ".itemKey").value(old[0]))
				.andExpect(jsonPath(at + ".kind").value("CHANGED"))
				.andExpect(jsonPath(at + ".changedValues", hasSize(1)))
				.andExpect(jsonPath(at + ".changedValues[0].attributeName").value(header[column]))
				.andExpect(jsonPath(at + ".changedValues[0].before").value(old[column]))
				.andExpect(jsonPath(at + ".changedValues[0].after").value(fresh[column]));
			changedRows++;
		}
		assertThat(changedRows).isPositive();
		result.andExpect(jsonPath("$.changes", hasSize(changedRows)));
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, Path file) throws Exception {
		return this.mvc.perform(multipart("/api/v1/editions").param("packId", packId)
			.param("inputSourceId", inputSourceId)
			.param("effectiveDate", effectiveDate)
			.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv", Files.readAllBytes(file))));
	}

}
