package com.altumlab.tabnit.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * B-17: 자료가 한도보다 크면 제약을 검사하지 않고 그렇다고 답한다 (D7 "제약의 위반", D2 AD-395).
 * 한도를 아주 작게 설정해 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "tabnit.constraints.max-rows=5")
class ConstraintViolationsLimitApiTests {

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@TempDir
	Path temp;

	@Test
	void 자료가_한도보다_크면_검사하지_못했다고_답하고_위반이_없다고_답하지_않는다() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
		String packId = "cv-limit";
		// 제약을 어기게 바꿔 적는다: 검사했다면 위반이 나온다.
		Path pack = PackCopies.load(this.dataSource, this.temp, packId,
				text -> text.replace("sh:minInclusive 1 ;", "sh:minInclusive 100000 ;"));

		// 자료가 없으면 한도 안이다.
		mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("asOf", "2026-06-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.checked").value(true))
			.andExpect(jsonPath("$.maxRows").value(5))
			.andExpect(jsonPath("$.total").value(0));

		Path file = pack.resolve("data/guideline-v1.csv");
		mvc.perform(multipart("/api/v1/editions").param("packId", packId)
			.param("inputSourceId", "guideline")
			.param("effectiveDate", "2026-01-01")
			.file(new MockMultipartFile("file", "guideline-v1.csv", "text/csv", Files.readAllBytes(file))))
			.andExpect(status().isOk());

		mvc.perform(get("/api/v1/constraint-violations").param("packId", packId).param("asOf", "2026-06-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.checked").value(false))
			.andExpect(jsonPath("$.maxRows").value(5))
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.violations", hasSize(0)))
			.andExpect(jsonPath("$.entityCount").value(3))
			.andExpect(jsonPath("$.valueCount").value(6));
	}
}
