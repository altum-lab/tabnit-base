package com.altumlab.tabnit.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-21: 한 번에 내보내는 행의 한도 (D2 AD-412). 한도를 작게 두고 본다: 넘으면 일부만 주지 않고 거부한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "tabnit.exports.max-rows=2")
class ListExportsLimitApiTests {

	private static final RequestPostProcessor OFFICER = user("exl-officer").roles("OFFICER");

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

	@Test
	void 한도를_넘는_목록은_일부만_주지_않고_거부하며_한도와_같으면_준다() throws Exception {
		String packId = "exl-limit";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : new String[][] { { "program", "program-v1.csv" }, { "guideline", "guideline-v1.csv" },
				{ "guideline-program", "guideline-program-v1.csv" }, { "partner-health", "partner-health-v1.csv" },
				{ "partner-private", "partner-private-v1.csv" }, { "team", "team-v1.csv" } }) {
			this.mvc.perform(multipart("/api/v1/editions").with(OFFICER).with(csrf()).param("packId", packId)
					.param("inputSourceId", edition[0]).param("effectiveDate", "2026-01-01")
					.file(new MockMultipartFile("file", edition[1], "text/csv",
							Files.readAllBytes(pack.resolve("data/" + edition[1])))))
				.andExpect(status().isOk());
		}
		// 사업 P-01 의 관계는 한도(2)보다 많고, 전담팀 T-01 의 관계는 한도와 같다.
		String many = packId + "|의료사업|P-01";
		String two = packId + "|전담팀|T-01";
		this.mvc.perform(get("/api/v1/relations").with(OFFICER).param("entityId", two).param("asOf", "2026-02-01"))
			.andExpect(jsonPath("$.total", is(2)));

		for (String format : new String[] { "csv", "json" }) {
			this.mvc.perform(get("/api/v1/relation-exports").with(OFFICER).param("entityId", many).param("asOf", "2026-02-01")
					.param("format", format))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code", is("EXPORT_TOO_LARGE")))
				.andExpect(jsonPath("$.detail", containsString("2")));
			this.mvc.perform(get("/api/v1/relation-exports").with(OFFICER).param("entityId", two).param("asOf", "2026-02-01")
					.param("format", format))
				.andExpect(status().isOk());
		}
	}
}
