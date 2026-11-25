package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * B-17: 제약의 위반을 보는 API (docs/api/openapi.yaml 의 listConstraintViolations, D7 "제약의 위반", D2 AD-390, AD-395).
 *
 * <p>시험용 Pack 의 사본에 제약을 바꿔 적어 위반이 나오게 한다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConstraintViolationsApiTests {

	private static final String[][] FIRST = { { "program", "program-v1.csv" }, { "guideline", "guideline-v1.csv" },
			{ "guideline-program", "guideline-program-v1.csv" }, { "partner-health", "partner-health-v1.csv" },
			{ "partner-private", "partner-private-v1.csv" }, { "team", "team-v1.csv" } };

	/** 시험용 Pack 의 제약에서 고칠 곳: 점검 주기의 아래쪽 한계. */
	private static final String MIN_CYCLE = "sh:minInclusive 1 ;";

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
	void 조회_시점의_자료가_제약을_어긴_곳을_개체와_속성과_값과_문구와_함께_돌려준다() throws Exception {
		String packId = "cv-violations";
		// 점검 주기가 십만 일 이상이어야 한다고 바꿔 적는다: 지침마다 어긴다.
		Path pack = loadWith(packId, MIN_CYCLE, "sh:minInclusive 100000 ;");
		int guidelines = (int) Files.readAllLines(pack.resolve("data/guideline-v1.csv")).stream().skip(1)
			.filter(line -> !line.isBlank()).count();
		assertThat(guidelines).isGreaterThan(1);

		String body = this.mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("asOf", "2026-06-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.packId").value(packId))
			.andExpect(jsonPath("$.asOf").value("2026-06-01"))
			.andExpect(jsonPath("$.checked").value(true))
			.andExpect(jsonPath("$.total").value(guidelines))
			.andExpect(jsonPath("$.offset").value(0))
			.andExpect(jsonPath("$.limit").value(50))
			.andExpect(jsonPath("$.violations", hasSize(guidelines)))
			.andExpect(jsonPath("$.violations[*].severity", everyItem(is("VIOLATION"))))
			.andExpect(jsonPath("$.violations[*].constraintKind", everyItem(is("MinInclusive"))))
			.andExpect(jsonPath("$.violations[*].pathKind", everyItem(is("ATTRIBUTE"))))
			.andExpect(jsonPath("$.violations[*].pathName", everyItem(is("점검주기일"))))
			.andExpect(jsonPath("$.violations[*].entity.entityType", everyItem(is("운영지침"))))
			.andExpect(jsonPath("$.violations[*].constraintName", everyItem(is("운영지침제약"))))
			.andExpect(jsonPath("$.violations[*].message", everyItem(containsString("100000"))))
			.andExpect(jsonPath("$.violations[*].message", everyItem(not(containsString("urn:")))))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		// 개체의 id 의 글자 코드 순이고, 개체는 유형의 이름표와 표시 이름과 함께 온다. 값은 저장된 글자다.
		List<String> ids = JsonPath.read(body, "$.violations[*].entity.entityId");
		assertThat(ids).isSorted().doesNotHaveDuplicates().first().isEqualTo(packId + "|운영지침|G-01");
		assertThat(JsonPath.<String>read(body, "$.violations[0].entity.displayName")).isNotBlank();
		assertThat(JsonPath.<String>read(body, "$.violations[0].entity.entityTypeLabel")).isNotBlank();
		assertThat(JsonPath.<String>read(body, "$.violations[0].pathLabel")).isNotBlank().isNotEqualTo("점검주기일");
		assertThat(JsonPath.<String>read(body, "$.violations[0].value")).isEqualTo("30");
		assertThat(JsonPath.<Object>read(body, "$.violations[0].valueEntity")).isNull();
		// 수는 조회 시점의 것이다.
		assertThat(JsonPath.<Integer>read(body, "$.entityCount")).isGreaterThan(guidelines);
		assertThat(JsonPath.<Integer>read(body, "$.valueCount")).isPositive();
		assertThat(JsonPath.<Integer>read(body, "$.relationCount")).isPositive();
		// 두 번 물어도 같다.
		assertThat(this.mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("asOf", "2026-06-01"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(body);

		// 나눠 받아 이으면 한 번에 받은 것과 같다. 전체 수는 나누기 전의 수다.
		List<String> joined = new ArrayList<>();
		for (int offset = 0; offset < guidelines + 1; offset++) {
			String page = this.mvc
				.perform(get("/api/v1/constraint-violations").param("packId", packId)
					.param("asOf", "2026-06-01")
					.param("offset", String.valueOf(offset))
					.param("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(guidelines))
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
			joined.addAll(JsonPath.<List<String>>read(page, "$.violations[*].entity.entityId"));
		}
		assertThat(joined).isEqualTo(ids);
	}

	@Test
	void 조회_시점에_값이_하나도_없는_개체도_검사한다() throws Exception {
		String packId = "cv-before";
		Path pack = loadWith(packId, MIN_CYCLE, MIN_CYCLE);
		int guidelines = (int) Files.readAllLines(pack.resolve("data/guideline-v1.csv")).stream().skip(1)
			.filter(line -> !line.isBlank()).count();

		// 판의 적용일(2026-01-01)보다 앞선 날짜: 개체는 있고 값은 없다. 제목이 있어야 한다는 제약에 지침마다 걸린다.
		this.mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("asOf", "2025-06-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.checked").value(true))
			.andExpect(jsonPath("$.valueCount").value(0))
			.andExpect(jsonPath("$.relationCount").value(0))
			.andExpect(jsonPath("$.total").value(guidelines))
			.andExpect(jsonPath("$.violations", hasSize(guidelines)))
			.andExpect(jsonPath("$.violations[*].constraintKind", everyItem(is("MinCount"))))
			.andExpect(jsonPath("$.violations[*].value", everyItem(is((Object) null))));
		// 판의 적용일부터는 제약을 지킨다: 시험용 Pack 의 처음 자료는 제 제약을 어기지 않는다.
		this.mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("asOf", "2026-01-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.checked").value(true))
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.violations", hasSize(0)));
	}

	@Test
	void 조회_시점을_비우면_서버가_오늘로_정하고_그_날짜를_답에_담는다() throws Exception {
		String packId = "cv-today";
		loadWith(packId, MIN_CYCLE, MIN_CYCLE);

		String body = this.mvc.perform(get("/api/v1/constraint-violations").param("packId", packId))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(JsonPath.<String>read(body, "$.asOf")).matches("\\d{4}-\\d{2}-\\d{2}");
	}

	@Test
	void 틀린_요청은_무엇이_틀렸는지_알리며_거부한다() throws Exception {
		String packId = "cv-bad";
		loadWith(packId, MIN_CYCLE, MIN_CYCLE);

		this.mvc.perform(get("/api/v1/constraint-violations"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_PACK_ID"));
		this.mvc.perform(get("/api/v1/constraint-violations").param("packId", "cv-없는-pack"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PACK_NOT_FOUND"));
		this.mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("asOf", "어제"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AS_OF"));
		this.mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("asOf", "10000-01-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AS_OF"));
		this.mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("limit", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
		this.mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("offset", "-1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_OFFSET"));
	}

	/** 시험용 Pack 의 사본을 제약의 한 곳을 바꿔 적재하고 처음 자료를 올린다. */
	private Path loadWith(String packId, String from, String to) throws Exception {
		assertThat(Files.readString(PackCopies.SOURCE.resolve("constraints.ttl"))).as("시험용 Pack 의 제약에 고칠 곳이 있어야 한다")
			.contains(from);
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text.replace(from, to));
		for (String[] first : FIRST) {
			upload(packId, first[0], "2026-01-01", pack.resolve("data/" + first[1])).andExpect(status().isOk());
		}
		return pack;
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, Path file) throws Exception {
		return this.mvc.perform(multipart("/api/v1/editions").param("packId", packId)
			.param("inputSourceId", inputSourceId)
			.param("effectiveDate", effectiveDate)
			.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv", Files.readAllBytes(file))));
	}
}
